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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
    /// Valid PNG payload used by the ordinary synchronization scenarios.
    private static final byte @Unmodifiable [] IMAGE = fixtureImage();

    /// Builds a small real image for the background decode checks.
    private static byte @Unmodifiable [] fixtureImage() {
        try {
            var bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /// Starts a loopback listener without internet access.
    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    /// Lets an older HTTP response finish after a second session has published a newer agreement.
    @Test
    void retriesLateManifestUsingTheNewerDiskState() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        CountDownLatch oldRequest = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<@Nullable String> retriedValidator = new AtomicReference<>();
        JsonObject original = ContentFixtures.basic("main");
        JsonObject newer = original.deepCopy();
        newer.getAsJsonObject("agreement").addProperty("revision", 2);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
            http.setExecutor(handlers);
            http.createContext("/", exchange -> {
                int request = requests.incrementAndGet();
                if (request == 1) {
                    oldRequest.countDown();
                    try {
                        if (!releaseOld.await(5, TimeUnit.SECONDS)) {
                            send(exchange, 503, new byte[0]);
                            return;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                    exchange.getResponseHeaders().add("ETag", "\"old\"");
                    send(exchange, 200, original.toString().getBytes(StandardCharsets.UTF_8));
                } else if (request == 2) {
                    exchange.getResponseHeaders().add("ETag", "\"new\"");
                    send(exchange, 200, newer.toString().getBytes(StandardCharsets.UTF_8));
                } else {
                    retriedValidator.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
                    send(exchange, 304, new byte[0]);
                }
            });
            http.start();
            var config = config();
            var storeA = new ContentStore(directory, config.endpoint().toString());
            var storeB = new ContentStore(directory, config.endpoint().toString());
            var eventsA = new LinkedBlockingQueue<ContentSync.Result>();
            var eventsB = new LinkedBlockingQueue<ContentSync.Result>();
            try (var launcherA = new ContentSync(config, storeA, eventsA::add, new long[0]);
                 var launcherB = new ContentSync(config, storeB, eventsB::add, new long[0])) {
                launcherA.activate();
                assertTrue(oldRequest.await(5, TimeUnit.SECONDS));
                launcherB.activate();
                assertEquals(2, java.util.Objects.requireNonNull(awaitFinal(eventsB, true).manifest()).agreement().revision());
                releaseOld.countDown();
                ContentSync.Result accepted = awaitFinal(eventsA, true);
                assertEquals(2, java.util.Objects.requireNonNull(accepted.manifest()).agreement().revision());
                assertEquals("\"new\"", retriedValidator.get());
                assertEquals(3, requests.get());
                assertEquals(newer.toString(), java.util.Objects.requireNonNull(storeA.cache("main")).body());
                assertEquals(newer.toString(), Files.readString(directory.resolve("server/manifest.json")));
            } finally {
                releaseOld.countDown();
                http.stop(0);
            }
        }
    }

    /// Keeps verified announcements visible through a blocked publication and retries against the latest disk state.
    @Test
    void publicationContentionKeepsVerifiedAnnouncements() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        JsonObject cached = ContentFixtures.markdown("main", BODY, IMAGE);
        JsonObject fetched = cached.deepCopy();
        fetched.getAsJsonObject("agreement").addProperty("revision", 2);
        JsonObject latest = cached.deepCopy();
        latest.getAsJsonObject("agreement").addProperty("revision", 3);
        http.createContext("/", exchange -> {
            if (requests.incrementAndGet() == 1) {
                firstRequest.countDown();
                try {
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) {
                        send(exchange, 503, new byte[0]);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exchange.close();
                    return;
                }
            }
            if ("\"latest\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                send(exchange, 304, new byte[0]);
            } else {
                exchange.getResponseHeaders().add("ETag", "\"fetched\"");
                send(exchange, 200, fetched.toString().getBytes(StandardCharsets.UTF_8));
            }
        });
        http.start();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        var cachedManifest = RemoteContent.parse(cached.toString(), "main");
        ContentFixtures.publishCache(store, new ContentStore.Cache(cached.toString(), "\"cached\"", Instant.EPOCH));
        store.install(java.util.Objects.requireNonNull(cachedManifest.files().get(ContentFixtures.BODY)),
                BODY.getBytes(StandardCharsets.UTF_8));
        store.install(java.util.Objects.requireNonNull(cachedManifest.files().get(ContentFixtures.IMAGE)), IMAGE);
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(config, store, events::add, new long[]{0})) {
            sync.activate();
            assertTrue(firstRequest.await(5, TimeUnit.SECONDS));
            var initial = java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS));
            assertTrue(initial.readyAnnouncements().contains("news"));
            Path ready = directory.resolve("publication-lock-held");
            Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
            if (!Files.isRegularFile(javaExecutable))
                javaExecutable = javaExecutable.resolveSibling("java.exe");
            Path classes = Path.of(PublicationLockPeer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Process peer = new ProcessBuilder(javaExecutable.toString(), "-cp", classes.toString(), PublicationLockPeer.class.getName(),
                    directory.resolve("client/content.lock").toString(), ready.toString()).redirectErrorStream(true).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(ready) && peer.isAlive() && System.nanoTime() < deadline)
                    Thread.sleep(20);
                assertTrue(Files.exists(ready), "The other process must hold the publication lock");
                releaseResponse.countDown();
                var waiting = java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS));
                assertTrue(waiting.publicationBlocked());
                assertFalse(waiting.exhausted());
                assertEquals(cachedManifest, waiting.manifest());
                assertTrue(waiting.readyAnnouncements().contains("news"));
                assertNull(waiting.agreementManifest());
                var exhausted = awaitFinal(events, false);
                assertEquals(cachedManifest, exhausted.manifest());
                assertTrue(exhausted.readyAnnouncements().contains("news"));
                assertNull(exhausted.agreementManifest());
                assertEquals(2, requests.get());
                peer.getOutputStream().close();
                assertTrue(peer.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, peer.exitValue());
            } finally {
                if (peer.isAlive()) {
                    peer.destroyForcibly();
                    peer.waitFor(5, TimeUnit.SECONDS);
                }
            }
            assertEquals(cached.toString(), java.util.Objects.requireNonNull(store.cache("main")).body());
            ContentFixtures.publishCache(new ContentStore(directory, config.endpoint().toString()),
                    new ContentStore.Cache(latest.toString(), "\"latest\"", Instant.now()));
            sync.retryFailed();
            var complete = awaitFinal(events, true);
            assertFalse(complete.publicationBlocked());
            assertEquals(3, java.util.Objects.requireNonNull(complete.agreementManifest()).agreement().revision());
            assertTrue(complete.readyAnnouncements().contains("news"));
            assertEquals(latest.toString(), java.util.Objects.requireNonNull(store.cache("main")).body());
            assertEquals(3, requests.get());
        } finally {
            releaseResponse.countDown();
        }
    }

    /// Holds the actual publication lock from another JVM.
    @NotNullByDefault
    public static final class PublicationLockPeer {
        /// Releases the lock when the parent closes the input pipe.
        public static void main(String @Unmodifiable [] args) throws Exception {
            try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                Files.createFile(Path.of(args[1]));
                System.in.read();
            }
        }
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

    /// Retains a verified future announcement through cleanup and restart while its replacement is unavailable.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsScheduledFallbackBeforePublication(boolean replacementReady) throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        Instant start = Instant.now().plusSeconds(3_600);
        Instant originalStart = replacementReady ? Instant.now().minusSeconds(60) : start;
        String replacementBody = "# New scheduled revision";
        JsonObject original = ContentFixtures.markdown("main", BODY, IMAGE);
        original.getAsJsonArray("announcements").get(0).getAsJsonObject().addProperty("startsAt", originalStart.toString());
        JsonObject replacement = ContentFixtures.markdown("main", replacementBody, new byte[0]);
        JsonObject next = replacement.getAsJsonArray("announcements").get(0).getAsJsonObject();
        next.addProperty("revision", 2);
        next.addProperty("startsAt", start.toString());
        AtomicReference<String> catalog = new AtomicReference<>(original.toString());
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("manifest.json")) {
                send(exchange, 200, catalog.get().getBytes(StandardCharsets.UTF_8));
            } else if (path.endsWith("news.md")) {
                boolean isOriginal = catalog.get().equals(original.toString());
                String body = isOriginal ? BODY : replacementBody;
                send(exchange, isOriginal || replacementReady ? 200 : 503, body.getBytes(StandardCharsets.UTF_8));
            } else {
                send(exchange, 200, IMAGE);
            }
        });
        http.start();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(config, store, events::add, new long[0])) {
            sync.activate();
            assertTrue(awaitFinal(events, true).readyAnnouncements().contains("news"));
            catalog.set(replacement.toString());
            sync.refresh();
            ContentSync.Result waiting = awaitFinal(events, replacementReady);
            assertEquals(replacementReady, waiting.readyAnnouncements().contains("news"));
            var fallback = waiting.announcementFallbacks().get("news");
            assertNotNull(fallback);
            assertEquals(originalStart, fallback.item().startsAt());
            assertEquals(replacementReady, fallback.item().active(start.minusNanos(1)));
            assertTrue(fallback.item().active(start));
            assertTrue(store.verified(fallback.files().get(ContentFixtures.BODY)));
        }

        var reopened = new ContentStore(directory, config.endpoint().toString());
        try (var sync = new ContentSync(config, reopened, events::add, new long[0])) {
            sync.activate();
            var cached = java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS));
            assertTrue(cached.cached());
            var fallback = cached.announcementFallbacks().get("news");
            assertNotNull(fallback);
            assertEquals(1, fallback.item().revision());
            assertTrue(fallback.item().active(start));
        }
    }

    /// Blocks a corrupt cached agreement until its verified payload becomes available again.
    @Test
    void agreementIsUnavailableWhileItsCachedBodyIsCorrupt() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        String body = "# Original agreement";
        String bodyPath = "agreement/terms.md";
        JsonObject catalog = ContentFixtures.basic("main");
        JsonObject agreement = catalog.getAsJsonObject("agreement");
        agreement.remove("content");
        agreement.addProperty("contentFile", bodyPath);
        ContentFixtures.file(catalog, bodyPath, body.getBytes(StandardCharsets.UTF_8));
        AtomicBoolean bodyAvailable = new AtomicBoolean();
        http.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("manifest.json")) {
                send(exchange, 200, catalog.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                send(exchange, bodyAvailable.get() ? 200 : 503, body.getBytes(StandardCharsets.UTF_8));
            }
        });
        http.start();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        var manifest = RemoteContent.parse(catalog.toString(), "main");
        var resource = java.util.Objects.requireNonNull(manifest.files().get(bodyPath));
        ContentFixtures.publishCache(store, new ContentStore.Cache(catalog.toString(), "", Instant.EPOCH));
        store.install(resource, body.getBytes(StandardCharsets.UTF_8));
        store.accept(manifest);
        Files.writeString(store.file(resource), "# Modified agreement");
        assertTrue(store.accepted(manifest));
        assertThrows(IOException.class, () ->
                ContentDocuments.validate(directory, manifest, manifest.agreement(), store, config.endpoint(), true));

        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(config, store, events::add, new long[0])) {
            sync.activate();
            assertTrue(java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS)).cached());
            var checked = java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS));
            assertTrue(checked.pending());
            assertFalse(checked.cached());
            assertFalse(checked.agreementReady());
            assertNull(checked.agreementManifest());
            assertNull(awaitFinal(events, false).agreementManifest());

            bodyAvailable.set(true);
            sync.retryFailed();
            var recovered = awaitFinal(events, true);
            assertEquals(manifest, recovered.agreementManifest());
            ContentDocuments.validate(directory, manifest, manifest.agreement(), store, config.endpoint(), true);
            assertTrue(store.accepted(manifest));
        }
    }

    /// Keeps the old revision through invalid image references or bytes and switches after correction.
    @ParameterizedTest
    @ValueSource(strings = {"undeclared.png", "unsupported.txt", "broken.png"})
    void invalidReplacementDocumentKeepsFallbackUntilCorrected(String invalidImage) throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        String originalBody = "# Last usable announcement\n\n[Rules](rules.txt)";
        String invalidBody = "# Invalid update\n\n![Invalid image](" + invalidImage + ")";
        String correctedBody = "# Corrected update";
        JsonObject original = ContentFixtures.markdown("main", originalBody, new byte[0]);
        String rulesPath = "announcements/rules.txt";
        byte @Unmodifiable [] rules = "Original rules".getBytes(StandardCharsets.UTF_8);
        ContentFixtures.file(original, rulesPath, rules);
        JsonObject invalid = ContentFixtures.markdown("main", invalidBody, new byte[0]);
        JsonObject corrected = ContentFixtures.markdown("main", correctedBody, new byte[0]);
        JsonObject invalidItem = invalid.getAsJsonArray("announcements").get(0).getAsJsonObject();
        invalidItem.addProperty("revision", 2);
        byte @Unmodifiable [] invalidBytes = "not an image".getBytes(StandardCharsets.UTF_8);
        if (!invalidImage.equals("undeclared.png")) {
            String imagePath = "announcements/" + invalidImage;
            invalidItem.getAsJsonArray("resources").add(imagePath);
            ContentFixtures.file(invalid, imagePath, invalidBytes);
        }
        corrected.getAsJsonArray("announcements").get(0).getAsJsonObject().addProperty("revision", 3);
        AtomicBoolean fixed = new AtomicBoolean();
        http.createContext("/", exchange -> {
            boolean useCorrection = fixed.get();
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/" + invalidImage)) {
                send(exchange, 200, invalidBytes);
                return;
            }
            String content = path.endsWith("manifest.json")
                    ? (useCorrection ? corrected : invalid).toString() : (useCorrection ? correctedBody : invalidBody);
            send(exchange, 200, content.getBytes(StandardCharsets.UTF_8));
        });
        http.start();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        var originalManifest = RemoteContent.parse(original.toString(), "main");
        var originalFile = java.util.Objects.requireNonNull(originalManifest.files().get(ContentFixtures.BODY));
        var rulesFile = java.util.Objects.requireNonNull(originalManifest.files().get(rulesPath));
        ContentFixtures.publishCache(store, new ContentStore.Cache(original.toString(), "", Instant.EPOCH));
        store.install(originalFile, originalBody.getBytes(StandardCharsets.UTF_8));
        store.install(rulesFile, rules);
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(config, store, events::add, new long[0])) {
            sync.activate();
            var failed = awaitFinal(events, false);
            assertFalse(failed.readyAnnouncements().contains("news"));
            assertTrue(java.util.Objects.requireNonNull(failed.error()).contains(invalidImage));
            assertEquals(1, java.util.Objects.requireNonNull(failed.announcementFallbacks().get("news")).item().revision());
            assertTrue(store.verified(originalFile));
        }

        var reopened = new ContentStore(directory, config.endpoint().toString());
        try (var sync = new ContentSync(config, reopened, events::add, new long[0])) {
            sync.activate();
            var restored = java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS));
            assertTrue(restored.cached());
            assertFalse(restored.readyAnnouncements().contains("news"));
            var fallback = java.util.Objects.requireNonNull(restored.announcementFallbacks().get("news"));
            assertEquals(1, fallback.item().revision());
            assertEquals(Map.of(rulesPath, rulesFile), fallback.linkedFiles());
            assertEquals(reopened.file(rulesFile).toUri(), ContentDocuments.link(directory, fallback.linkIndex(),
                    fallback.item(), reopened, config.endpoint(), "rules.txt"));
            var failed = awaitFinal(events, false);
            assertFalse(failed.readyAnnouncements().contains("news"));
            Files.writeString(reopened.file(rulesFile), "Damaged rules");
            IOException corrupt = assertThrows(IOException.class, () -> ContentDocuments.link(directory, fallback.linkIndex(),
                    fallback.item(), reopened, config.endpoint(), "rules.txt"));
            assertTrue(corrupt.getMessage().contains("缓存文档校验失败"));
            assertThrows(IOException.class, () -> ContentDocuments.link(directory, originalManifest,
                    originalManifest.announcements().getFirst(), reopened, config.endpoint(), "rules.txt"));
            fixed.set(true);
            sync.retryFailed();
            var ready = awaitFinal(events, true);
            assertTrue(ready.readyAnnouncements().contains("news"));
            assertEquals(3, java.util.Objects.requireNonNull(ready.manifest()).announcements().getFirst().revision());
            assertTrue(ready.announcementFallbacks().isEmpty());
            assertTrue(java.util.Objects.requireNonNull(reopened.cache("main")).announcementFallbacks().isEmpty());
            assertFalse(Files.exists(reopened.file(originalFile)));
            assertFalse(Files.exists(reopened.file(rulesFile)));
        }
    }

    /// Repeats only local maintenance after a lock conflict or unreadable ownership index.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retriesCleanupWithoutRepeatingTheManifestRequest(boolean lockBusy) throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        AtomicInteger requests = new AtomicInteger();
        String empty = ContentFixtures.basic("main").toString();
        http.createContext("/", exchange -> {
            requests.incrementAndGet();
            send(exchange, 200, empty.getBytes(StandardCharsets.UTF_8));
        });
        http.start();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        String body = "# Withdrawn";
        String original = ContentFixtures.markdown("main", body, new byte[0]).toString();
        var manifest = RemoteContent.parse(original, "main");
        var file = java.util.Objects.requireNonNull(manifest.files().get(ContentFixtures.BODY));
        ContentFixtures.publishCache(store, new ContentStore.Cache(original, "", Instant.EPOCH));
        store.install(file, body.getBytes(StandardCharsets.UTF_8));
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        var held = new AtomicReference<@Nullable FileLock>();
        AtomicBoolean obstructed = new AtomicBoolean();
        Path lockPath = directory.resolve("client/content.lock");
        Path inventory = directory.resolve("client/managed-files.json");
        String validInventory = Files.readString(inventory);
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             var sync = new ContentSync(config, store, result -> {
                 if (result.pending() && !result.cached() && obstructed.compareAndSet(false, true)) {
                     try {
                         if (lockBusy)
                             held.set(channel.lock());
                         else
                             Files.writeString(inventory, "{invalid");
                     } catch (IOException e) {
                         throw new java.io.UncheckedIOException(e);
                     }
                 }
                 events.add(result);
             }, new long[0])) {
            sync.activate();
            ContentSync.Result incomplete;
            do {
                incomplete = java.util.Objects.requireNonNull(events.poll(5, TimeUnit.SECONDS));
            } while (incomplete.pending());
            assertTrue(incomplete.contentSynced());
            assertFalse(incomplete.success());
            String error = java.util.Objects.requireNonNull(incomplete.cleanupError());
            assertTrue(error.startsWith("清理缓存文件失败："));
            assertTrue(error.contains(lockBusy ? "Another launcher" : "managed-files.json"));
            assertNull(incomplete.error());
            assertTrue(Files.exists(store.file(file)));
            if (lockBusy)
                java.util.Objects.requireNonNull(held.get()).release();
            else
                Files.writeString(inventory, validInventory);
            assertNull(awaitFinal(events, true).cleanupError());
            assertFalse(Files.exists(store.file(file)), "Deferred local cleanup must remove the withdrawn payload");
            assertEquals(1, requests.get());
        }
    }

    /// Retries a failed snapshot write before reclaiming files, even after network retries are exhausted.
    @Test
    void retriesDirtyCachePersistenceBeforePruning() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        String oldBody = "# Old announcement";
        String original = ContentFixtures.markdown("main", oldBody, new byte[0]).toString();
        JsonObject updated = ContentFixtures.markdown("main", "# Pending announcement", new byte[0]);
        JsonObject notice = new JsonObject();
        notice.addProperty("id", "expired");
        notice.addProperty("revision", 1);
        notice.addProperty("title", "Expired notice");
        notice.addProperty("content", "Expired notice");
        notice.addProperty("expiresAt", "2000-01-01T00:00:00Z");
        updated.getAsJsonArray("notices").add(notice);
        AtomicInteger requests = new AtomicInteger();
        http.createContext("/", exchange -> {
            requests.incrementAndGet();
            boolean manifest = exchange.getRequestURI().getPath().endsWith("manifest.json");
            send(exchange, manifest ? 200 : 503, manifest ? updated.toString().getBytes(StandardCharsets.UTF_8) : new byte[0]);
        });
        http.start();
        var config = config();
        var store = new ContentStore(directory, config.endpoint().toString());
        var originalManifest = RemoteContent.parse(original, "main");
        var oldFile = java.util.Objects.requireNonNull(originalManifest.files().get(ContentFixtures.BODY));
        var obsolete = new RemoteContent.Resource("announcements/obsolete.txt", RemoteContent.hash("obsolete"), "");
        ContentFixtures.publishCache(store, new ContentStore.Cache(original, "", Instant.EPOCH));
        store.install(oldFile, oldBody.getBytes(StandardCharsets.UTF_8));
        store.install(obsolete, "obsolete".getBytes(StandardCharsets.UTF_8));
        Path snapshots = directory.resolve("client/state").resolve(RemoteContent.hash(config.endpoint().toString()))
                .resolve("announcement-fallbacks");
        Path backup = directory.resolve("snapshot-backup");
        var blocked = new AtomicReference<@Nullable Path>();
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(config, store, result -> {
            if (result.pending() && !result.cached() && blocked.get() == null) {
                try (var paths = Files.list(snapshots)) {
                    Path snapshot = paths.findFirst().orElseThrow();
                    Files.move(snapshot, backup);
                    Files.createDirectory(snapshot);
                    Files.createFile(snapshot.resolve("block"));
                    blocked.set(snapshot);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            events.add(result);
        }, new long[0])) {
            sync.activate();
            var failed = awaitFinal(events, false);
            assertTrue(java.util.Objects.requireNonNull(failed.cleanupError()).startsWith("保存缓存索引失败："));
            assertTrue(Files.exists(store.file(obsolete)), "Pruning must wait until the cache write succeeds");
            assertTrue(java.util.Objects.requireNonNull(store.cache("main")).body().contains("Expired notice"));
            Path snapshot = java.util.Objects.requireNonNull(blocked.get());
            Files.delete(snapshot.resolve("block"));
            Files.delete(snapshot);
            Files.move(backup, snapshot);

            var recovered = awaitFinal(events, false);
            assertNull(recovered.cleanupError());
            assertEquals(failed.error(), recovered.error());
            assertFalse(Files.exists(store.file(obsolete)));
            assertTrue(Files.exists(store.file(oldFile)));
            var persisted = java.util.Objects.requireNonNull(store.cache("main"));
            assertFalse(persisted.body().contains("Expired notice"));
            assertTrue(persisted.announcementFallbacks().containsKey("news"));
            assertEquals(2, requests.get());
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
            ContentFixtures.publishCache(store, new ContentStore.Cache(replacement, "", Instant.EPOCH, Map.of("news", fallback)));
        } else {
            ContentFixtures.publishCache(store, new ContentStore.Cache(original, "", Instant.EPOCH));
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
        ContentFixtures.publishCache(store, new ContentStore.Cache(original, "\"v1\"", checkedAt));
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
