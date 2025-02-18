/*
 * The MIT License
 *
 * Copyright 2015 Tim Boudreau.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.tinymavenproxy;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.bunyan.ActeurBunyanModule;
import com.mastfrog.acteur.header.entities.CacheControl;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.acteur.server.ServerBuilder;
import com.mastfrog.acteur.server.ServerModule;
import static com.mastfrog.acteur.server.ServerModule.BYTEBUF_ALLOCATOR_SETTINGS_KEY;
import static com.mastfrog.acteur.server.ServerModule.DIRECT_ALLOCATOR;
import static com.mastfrog.acteur.server.ServerModule.EVENT_THREADS;
import static com.mastfrog.acteur.server.ServerModule.HTTP_COMPRESSION;
import static com.mastfrog.acteur.server.ServerModule.PORT;
import static com.mastfrog.acteur.server.ServerModule.WORKER_THREADS;
import com.mastfrog.acteur.util.ServerControl;
import com.mastfrog.bunyan.java.v2.Log;
import com.mastfrog.bunyan.java.v2.Logs;
import static com.mastfrog.giulius.SettingsBindings.BOOLEAN;
import static com.mastfrog.giulius.SettingsBindings.INT;
import static com.mastfrog.giulius.SettingsBindings.STRING;
import com.mastfrog.giulius.bunyan.java.v2.LoggingModule;
import static com.mastfrog.giulius.bunyan.java.v2.LoggingModule.SETTINGS_KEY_ASYNC_LOGGING;
import static com.mastfrog.giulius.bunyan.java.v2.LoggingModule.SETTINGS_KEY_LOG_LEVEL;
import com.mastfrog.settings.Settings;
import com.mastfrog.settings.SettingsBuilder;
import com.mastfrog.url.URL;
import com.mastfrog.util.libversion.VersionInfo;
import static com.mastfrog.util.preconditions.Checks.notNull;
import com.mastfrog.util.preconditions.ConfigurationError;
import com.mastfrog.util.streams.Streams;
import com.mastfrog.util.strings.AlignedText;
import com.mastfrog.util.strings.UniqueIDs;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import static io.netty.handler.codec.http.HttpResponseStatus.GONE;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.concurrent.ExecutorService;

/**
 *
 * @author Tim Boudreau
 */
public class TinyMavenProxy extends AbstractModule {

    public static final String APPLICATION_NAME = "tiny-maven-proxy";
    public static final String SETTINGS_KEY_DOWNLOAD_THREADS = "download.threads";
    public static final String DOWNLOAD_LOGGER = "download";
    public static final String ACCESS_LOGGER = ActeurBunyanModule.ACCESS_LOGGER;
    public static final String ERROR_LOGGER = ActeurBunyanModule.ERROR_LOGGER;
    public static final String SETTINGS_KEY_DOWNLOAD_CHUNK_SIZE = "download.chunk.size";
    public static final String SETTINGS_KEY_HTTP_CLIENT_CONNECT_TIMEOUT_SECONDS
            = "http.client.connect.timeout.seconds";
    static final int DEFAULT_DOWNLOAD_CHUNK_SIZE = 1480;

    static SettingsBuilder defaultSettings() {
        return new SettingsBuilder(APPLICATION_NAME)
                .add("application.name", APPLICATION_NAME)
                .add("cors.enabled", false)
                .add("download-tmp", System.getProperty("java.io.tmpdir"))
                .add(HTTP_COMPRESSION, "false")
                .add(SETTINGS_KEY_DOWNLOAD_THREADS, 24)
                .add(SETTINGS_KEY_ASYNC_LOGGING, false)
                .add(LoggingModule.SETTINGS_KEY_LOG_TO_CONSOLE, true)
                .add(WORKER_THREADS, 6)
                .add(EVENT_THREADS, 3)
                .add(ServerModule.BACKGROUND_THREADS, 4)
                .add(ServerModule.SETTINGS_KEY_SOCKET_WRITE_SPIN_COUNT, 32)
                .add(SETTINGS_KEY_LOG_LEVEL, "trace")
//                .add(MAX_CONTENT_LENGTH, "128") // we don't accept PUTs, no need for a big buffer
                .add(PORT, "5956")
                .add(BYTEBUF_ALLOCATOR_SETTINGS_KEY, DIRECT_ALLOCATOR);
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        System.setProperty("acteur.debug", "true");

        Settings settings = defaultSettings()
                .add(LoggingModule.SETTINGS_KEY_LOG_TO_CONSOLE, true)
                .add(LoggingModule.SETTINGS_KEY_LOG_FILE, "/tmp/tmproxy.log")
                .addFilesystemAndClasspathLocations()
                .parseCommandLineArguments(args).build();
        ServerControl ctrl = new ServerBuilder(APPLICATION_NAME)
                .add(new TinyMavenProxy())
                .add(new ActeurBunyanModule(true)
                        .bindLogger(DOWNLOAD_LOGGER).bindLogger("startup")
                )
                .add(binder -> {
                    binder.bind(VersionInfo.class)
                            .toInstance(VersionInfo.find(TinyMavenProxy.class,
                                    "com.mastfrog", "tiny-maven-proxy"));

                })
                .disableCORS()
                .enableOnlyBindingsFor(BOOLEAN, INT, STRING)
                .add(settings)
                .build().start();

        ctrl.await();
    }

    @Override
    protected void configure() {
        bind(HttpClient.class).toProvider(JavaHttpClientProvider.class).in(Scopes.SINGLETON);
        bind(StartupLogger.class).asEagerSingleton();
        bind(UniqueIDs.class).toProvider(UniqueIDsProvider.class).in(Scopes.SINGLETON);
        bind(ByteBuf.class).annotatedWith(Names.named("index")).toProvider(IndexPageProvider.class);
        bind(String.class).annotatedWith(Names.named("indexHash")).toProvider(IndexPageHashProvider.class);
        bind(String.class).annotatedWith(Names.named("runId")).toProvider(RunIdProvider.class).in(Scopes.SINGLETON);
    }

    @Singleton
    static final class IndexPageData {

        final ByteBuf buf;
        final String hash;

        @Inject
        IndexPageData(ByteBufAllocator alloc) throws IOException, NoSuchAlgorithmException {
            byte[] bytes;
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();) {
                try (InputStream in = Browse.IndexPageWriter.class.getResourceAsStream("index.html")) {
                    if (in == null) {
                        throw new ConfigurationError("index.html missing from classpath.");
                    }
                    Streams.copy(in, out);
                }
                bytes = out.toByteArray();
            }
            ByteBuf buf = alloc.ioBuffer(bytes.length, bytes.length);
            buf.writeBytes(bytes);
            buf.retain();
            this.buf = Unpooled.unreleasableBuffer(buf);
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            this.hash = Base64.getEncoder().encodeToString(digest);
        }
    }

    public static final String SETTINGS_KEY_UIDS_FILE = "uids.base";

    @Singleton
    static final class RunIdProvider implements Provider<String> {

        private final String runId;

        @Inject
        RunIdProvider(UniqueIDs ids) {
            runId = ids.newId();
        }

        @Override
        public String get() {
            return runId;
        }
    }

    @Singleton
    static class JavaHttpClientProvider implements Provider<HttpClient> {

        private final java.net.http.HttpClient client;

        @Inject
        JavaHttpClientProvider(@Named(ServerModule.BACKGROUND_THREAD_POOL_NAME) ExecutorService executor,
                Settings settings) {
            client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(
                            settings.getLong(SETTINGS_KEY_HTTP_CLIENT_CONNECT_TIMEOUT_SECONDS, 20)))
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .build();
        }

        @Override
        public HttpClient get() {
            return client;
        }
    }

    @Singleton
    static final class UniqueIDsProvider implements Provider<UniqueIDs> {

        private final UniqueIDs uniqueIds;

        @Inject
        UniqueIDsProvider(Settings settings) throws IOException {
            uniqueIds = new UniqueIDs(new File(settings.getString("uids.base", ".uids")));
        }

        @Override
        public UniqueIDs get() {
            return uniqueIds;
        }
    }

    static final class StartupLogger {

        @Inject
        StartupLogger(Settings settings, Config config, @Named("startup") Logs startup) {
            StringBuilder sb = new StringBuilder("TinyMavenProxy 1.5 on port\t" + settings.getInt("port") + " serving:\n");
            try (Log log = startup.info("config")) {
                log.add(config);
                for (URL u : config) {
                    sb.append("\tRepo:\t").append(u).append('\n');
                }
                sb.append("Settings:\n");
                for (String key : new String[]{SETTINGS_KEY_DOWNLOAD_THREADS, WORKER_THREADS, EVENT_THREADS}) {
                    sb.append(key).append("\t").append(settings.getString(key)).append('\n');
                    log.add(key, settings.getString(key));
                }
                sb.append("From:\n").append(config.dir);
            }
            System.out.println(AlignedText.formatTabbed(sb));
        }
    }

    @Singleton
    static final class IndexPageProvider implements Provider<ByteBuf> {

        private final IndexPageData data;

        @Inject
        IndexPageProvider(IndexPageData data) throws IOException {
            this.data = data;
        }

        @Override
        public ByteBuf get() {
            return data.buf.duplicate();
        }
    }

    @Singleton
    static final class IndexPageHashProvider implements Provider<String> {

        private final IndexPageData data;

        @Inject
        IndexPageHashProvider(IndexPageData data) throws IOException {
            this.data = data;
            notNull("hash", data.hash);
        }

        @Override
        public String get() {
            return data.hash;
        }
    }

    @HttpCall(order = Integer.MIN_VALUE)
    @PathRegex({"^favicon.ico$"})
    @Methods({GET, HEAD})
    @Description("Sends 404 for /favicon.ico")
    static class FaviconPage extends Acteur {

        @Inject
        FaviconPage() {
            add(Headers.CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE_MAX_AGE_1_DAY);
            add(Headers.EXPIRES, ZonedDateTime.now().plus(Duration.ofDays(1)));
            reply(GONE, "Does not exist\n");
        }
    }

}
