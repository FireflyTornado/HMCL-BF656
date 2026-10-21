/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.remote;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Uses real loopback HTTP to verify one manifest check, file-level retries, cancellation and managed cleanup.
@NotNullByDefault
final class ContentSyncTest {
    /// Isolated per-test version directory.
    @TempDir
    Path directory;
    /// Ephemeral local HTTP server.
    private @Nullable HttpServer server;
    /// Shared fixture Markdown bytes.
    private static final String BODY = "# 公告\n\n![示例图片](assets/banner.png)";
    /// Hash-verified fixture image payload; visual decoding is covered by the desktop smoke test.
    private static final byte @Unmodifiable [] IMAGE = "fixture-image".getBytes(StandardCharsets.UTF_8);

    /// Starts a loopback listener without internet access.
    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    /// Releases the local listener.
    @AfterEach
    void cleanup() {
        if (server != null)
            server.stop(0);
    }

    /// An unchanged instance performs only a conditional manifest request on the next launcher process.
    @Test
    void unchangedStartupChecksManifestOnly() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        AtomicInteger manifests = new AtomicInteger();
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger images = new AtomicInteger();
        String catalog = ContentFixtures.markdown("main", BODY, IMAGE).toString();
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("manifest.json")) {
                manifests.incrementAndGet();
                if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    send(exchange, 304, new byte[0]);
                } else {
                    exchange.getResponseHeaders().add("ETag", "\"v1\"");
                    send(exchange, 200, catalog.getBytes(StandardCharsets.UTF_8));
                }
            } else if (path.endsWith("news.md")) {
                bodies.incrementAndGet();
                send(exchange, 200, BODY.getBytes(StandardCharsets.UTF_8));
            } else {
                images.incrementAndGet();
                send(exchange, 200, IMAGE);
            }
        });
        http.start();
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        var config = config();
        var store = new ContentStore(directory.resolve(".launcher"), config.endpoint().toString());
        try (var sync = new ContentSync(config, store, events::add, new long[]{0, 0, 0})) {
            sync.activate();
            awaitFinal(events, true);
            sync.pause();
            sync.activate();
            assertNull(events.poll(150, TimeUnit.MILLISECONDS));
            assertEquals(1, manifests.get());
            assertEquals(1, bodies.get());
            assertEquals(1, images.get());
        }
        try (var nextProcess = new ContentSync(config, store, events::add, new long[]{0, 0, 0})) {
            nextProcess.activate();
            awaitFinal(events, true);
            assertEquals(2, manifests.get());
            assertEquals(1, bodies.get());
            assertEquals(1, images.get());
            Files.delete(store.file(RemoteContent.parse(catalog, "main").files().get(ContentFixtures.IMAGE)));
            nextProcess.refresh();
            awaitFinal(events, true);
            assertEquals(3, manifests.get());
            assertEquals(1, bodies.get());
            assertEquals(2, images.get());
        }
    }

    /// A failed Markdown file retries independently without repeating a successful manifest or image download.
    @Test
    void retriesOnlyFailedFiles() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        AtomicInteger manifests = new AtomicInteger();
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger images = new AtomicInteger();
        String catalog = ContentFixtures.markdown("main", BODY, IMAGE).toString();
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("manifest.json")) {
                manifests.incrementAndGet();
                send(exchange, 200, catalog.getBytes(StandardCharsets.UTF_8));
            } else if (path.endsWith("news.md")) {
                send(exchange, bodies.incrementAndGet() < 4 ? 503 : 200, BODY.getBytes(StandardCharsets.UTF_8));
            } else {
                images.incrementAndGet();
                send(exchange, 200, IMAGE);
            }
        });
        http.start();
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        var config = config();
        try (var sync = new ContentSync(config, new ContentStore(directory, config.endpoint().toString()), events::add,
                new long[]{0, 0, 0})) {
            sync.activate();
            awaitFinal(events, true);
            assertEquals(1, manifests.get());
            assertEquals(4, bodies.get());
            assertEquals(1, images.get());
            assertNull(events.poll(150, TimeUnit.MILLISECONDS));
        }
    }

    /// A bad response keeps valid cache and stops after the initial attempt plus three retries.
    @Test
    void finiteFailuresKeepValidOfflineMetadata() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        AtomicInteger count = new AtomicInteger();
        http.createContext("/", exchange -> {
            count.incrementAndGet();
            send(exchange, 200, "broken JSON".getBytes(StandardCharsets.UTF_8));
        });
        http.start();
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        String original = ContentFixtures.basic("main").toString();
        Instant checkedAt = Instant.parse("2026-10-20T00:00:00Z");
        store.cache(new ContentStore.Cache(original, "\"v1\"", checkedAt));
        try (var sync = new ContentSync(config, store, events::add, new long[]{0, 0, 0})) {
            sync.activate();
            ContentSync.Result failed = awaitFinal(events, false);
            assertTrue(failed.exhausted());
            assertTrue(failed.cached());
            assertNotNull(failed.manifest());
            assertEquals(checkedAt, failed.checkedAt());
            assertEquals(4, count.get());
            assertEquals(original, java.util.Objects.requireNonNull(store.cache("main")).body());
            assertNull(events.poll(150, TimeUnit.MILLISECONDS));
        }
    }

    /// A valid empty announcement snapshot deletes the previous body and images without touching client documents.
    @Test
    void withdrawalDeletesPayloads() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        AtomicReference<JsonObject> catalog = new AtomicReference<>(ContentFixtures.markdown("main", BODY, IMAGE));
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            send(exchange, 200, path.endsWith("manifest.json") ? catalog.get().toString().getBytes(StandardCharsets.UTF_8)
                    : path.endsWith("news.md") ? BODY.getBytes(StandardCharsets.UTF_8) : IMAGE);
        });
        http.start();
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        try (var sync = new ContentSync(config, store, events::add, new long[]{0})) {
            sync.activate();
            var loaded = java.util.Objects.requireNonNull(awaitFinal(events, true).manifest());
            Path body = store.file(loaded.files().get(ContentFixtures.BODY));
            Path image = store.file(loaded.files().get(ContentFixtures.IMAGE));
            assertTrue(Files.exists(body));
            assertTrue(Files.exists(image));
            JsonObject next = ContentFixtures.basic("main");
            catalog.set(next);
            sync.refresh();
            awaitFinal(events, true);
            assertFalse(Files.exists(body));
            assertFalse(Files.exists(image));
        }
    }

    /// A slow deselected request cannot write payloads or deliver stale results; returning reuses its checked manifest.
    @Test
    void selectionCancellationSuppressesStaleWrites() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger manifests = new AtomicInteger();
        String body = "# Slow";
        String catalog = ContentFixtures.markdown("main", body, new byte[0]).toString();
        http.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().endsWith("manifest.json")) {
                    manifests.incrementAndGet();
                    send(exchange, 200, catalog.getBytes(StandardCharsets.UTF_8));
                } else {
                    blocked.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    send(exchange, 200, body.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                exchange.close();
            }
        });
        http.start();
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        try (var sync = new ContentSync(config, store, events::add, new long[]{0})) {
            sync.activate();
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            sync.pause();
            events.clear();
            release.countDown();
            assertNull(events.poll(250, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(store.file(RemoteContent.parse(catalog, "main").files().get(ContentFixtures.BODY))));
            sync.activate();
            awaitFinal(events, true);
            assertEquals(1, manifests.get());
        } finally {
            release.countDown();
        }
    }

    /// Returns a config pointing to the ephemeral loopback source.
    private ContentSync.Config config() {
        return new ContentSync.Config(true, URI.create("http://127.0.0.1:"
                + java.util.Objects.requireNonNull(server).getAddress().getPort() + "/"), "main");
    }

    /// Waits for terminal success or exhausted failure while ignoring progress snapshots.
    private static ContentSync.Result awaitFinal(LinkedBlockingQueue<ContentSync.Result> events, boolean success) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            @Nullable ContentSync.Result result = events.poll(2, TimeUnit.SECONDS);
            if (result != null && !result.pending() && (result.success() || result.exhausted())) {
                assertEquals(success, result.success(), result.error());
                return result;
            }
        }
        throw new AssertionError("No terminal synchronization result");
    }

    /// Sends one bounded response and closes its exchange.
    private static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, status == 304 ? -1 : bytes.length);
        if (status != 304)
            exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
