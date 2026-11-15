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
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /// Keeps a good revision across restart when the new file is corrupt and its download is interrupted.
    @Test
    void restartKeepsLastReadyAnnouncementDuringReplacement() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        AtomicInteger revision = new AtomicInteger(1);
        AtomicBoolean replacementAvailable = new AtomicBoolean();
        AtomicBoolean blockFirstReplacement = new AtomicBoolean(true);
        CountDownLatch waitingForBody = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        http.createContext("/", exchange -> {
            int published = revision.get();
            String body = BODY + "\n\nRevision " + published;
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("manifest.json")) {
                JsonObject catalog = ContentFixtures.markdown("main", body, IMAGE);
                catalog.getAsJsonArray("announcements").get(0).getAsJsonObject().addProperty("revision", published);
                catalog.getAsJsonObject("agreement").addProperty("revision", published);
                send(exchange, 200, catalog.toString().getBytes(StandardCharsets.UTF_8));
            } else if (path.endsWith("news.md")) {
                if (published == 2 && blockFirstReplacement.getAndSet(false)) {
                    waitingForBody.countDown();
                    try {
                        releaseBody.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                send(exchange, published == 1 || replacementAvailable.get() ? 200 : 503, body.getBytes(StandardCharsets.UTF_8));
            } else {
                send(exchange, 200, IMAGE);
            }
        });
        http.start();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        Path oldBody;
        Path snapshot;
        try (var sync = new ContentSync(config, store, events::add, new long[0])) {
            sync.activate();
            RemoteContent.Manifest original = java.util.Objects.requireNonNull(awaitFinal(events, true).manifest());
            oldBody = store.file(original.files().get(ContentFixtures.BODY));
            String nextBody = BODY + "\n\nRevision 2";
            var nextManifest = RemoteContent.parse(ContentFixtures.markdown("main", nextBody, IMAGE).toString(), "main");
            RemoteContent.Resource corrupt = nextManifest.files().get(ContentFixtures.BODY);
            store.install(corrupt, nextBody.getBytes(StandardCharsets.UTF_8));
            Files.writeString(store.file(corrupt), "# Damaged replacement");
            revision.set(2);
            sync.refresh();
            assertTrue(waitingForBody.await(5, TimeUnit.SECONDS), "The replacement request must start after persisting its metadata");
            var saved = java.util.Objects.requireNonNull(store.cache("main"));
            assertEquals(2, RemoteContent.parse(saved.body(), "main").agreement().revision());
            assertEquals(1, saved.announcementFallbacks().get("news").item().revision());
            Path state = directory.resolve("client/state").resolve(RemoteContent.hash(config.endpoint().toString()));
            JsonObject index = JsonParser.parseString(Files.readString(state.resolve("sync.json"))).getAsJsonObject();
            String digest = index.getAsJsonObject("announcementFallbacks").get("news").getAsString();
            snapshot = state.resolve("announcement-fallbacks").resolve(digest + ".json");
            assertTrue(Files.isRegularFile(snapshot));
        } finally {
            releaseBody.countDown();
        }

        var reopened = new ContentStore(directory, config.endpoint().toString());
        events.clear();
        try (var nextProcess = new ContentSync(config, reopened, events::add, new long[0])) {
            nextProcess.activate();
            var first = java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS));
            assertTrue(first.cached());
            assertFalse(first.readyAnnouncements().contains("news"));
            assertEquals(2, java.util.Objects.requireNonNull(first.manifest()).agreement().revision());
            var fallback = first.announcementFallbacks().get("news");
            assertNotNull(fallback);
            assertEquals(1, fallback.item().revision());
            assertTrue(reopened.verified(fallback.files().get(ContentFixtures.BODY)));
            assertEquals(BODY + "\n\nRevision 1", reopened.body(fallback.item(), fallback.files()));
            awaitFinal(events, false);

            revision.set(3);
            nextProcess.refresh();
            var third = awaitFinal(events, false);
            assertEquals(3, java.util.Objects.requireNonNull(third.manifest()).agreement().revision());
            assertEquals(fallback, third.announcementFallbacks().get("news"));
            assertTrue(Files.exists(oldBody));
            assertTrue(Files.isRegularFile(snapshot), "The same fallback snapshot must survive another update");

            replacementAvailable.set(true);
            nextProcess.refresh();
            var complete = awaitFinal(events, true);
            assertTrue(complete.readyAnnouncements().contains("news"));
            assertTrue(complete.announcementFallbacks().isEmpty());
            assertTrue(java.util.Objects.requireNonNull(reopened.cache("main")).announcementFallbacks().isEmpty());
            assertFalse(Files.exists(oldBody));
            assertFalse(Files.exists(snapshot));
        }
    }

    /// Rejects a corrupted associated image both when capturing an old revision and when restoring a saved fallback.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void corruptAnnouncementCannotBecomeAReadyFallback(boolean savedFallback) throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        String original = ContentFixtures.markdown("main", BODY, IMAGE).toString();
        RemoteContent.Manifest old = RemoteContent.parse(original, "main");
        String replacement = ContentFixtures.markdown("main", "# New revision", new byte[0]).toString();
        http.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("manifest.json"))
                send(exchange, 200, replacement.getBytes(StandardCharsets.UTF_8));
            else
                send(exchange, 503, new byte[0]);
        });
        http.start();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        RemoteContent.Resource body = old.files().get(ContentFixtures.BODY);
        RemoteContent.Resource image = old.files().get(ContentFixtures.IMAGE);
        store.install(body, BODY.getBytes(StandardCharsets.UTF_8));
        store.install(image, IMAGE);
        if (savedFallback) {
            var fallback = RemoteContent.Announcement.capture(old, old.announcements().getFirst());
            store.cache(new ContentStore.Cache(replacement, "", Instant.EPOCH, Map.of("news", fallback)));
        } else {
            store.cache(new ContentStore.Cache(original, "", Instant.EPOCH));
        }
        Files.writeString(store.file(image), "Damaged image");

        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(config, store, events::add, new long[0])) {
            sync.activate();
            var cached = java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS));
            assertTrue(cached.cached());
            assertTrue(cached.readyAnnouncements().isEmpty());
            assertTrue(cached.announcementFallbacks().isEmpty());
            var failed = awaitFinal(events, false);
            assertTrue(failed.readyAnnouncements().isEmpty());
            assertTrue(failed.announcementFallbacks().isEmpty());
            assertTrue(java.util.Objects.requireNonNull(store.cache("main")).announcementFallbacks().isEmpty());
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
