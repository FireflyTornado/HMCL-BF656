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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies instance identity, protocol receipts, Markdown resources and ownership-bounded cleanup.
@NotNullByDefault
final class RemoteContentTest {
    /// Isolated instance `.launcher` directory.
    @TempDir
    Path root;

    /// Optional presentation text stays compatible with old manifests and does not force renewed consent on its own.
    @Test
    void supportsBoundedAgreementIntroductionWithoutChangingConsent() throws Exception {
        var json = ContentFixtures.basic("main");
        var original = RemoteContent.parse(json.toString(), "main");
        assertEquals("", original.agreement().introduction());
        var store = new ContentStore(root, "source");
        ContentFixtures.publishCache(store, ContentFixtures.cache(json.toString(), "", Instant.EPOCH));
        store.accept(original);
        json.getAsJsonObject("agreement").addProperty("introduction", "游玩本服务器前，请仔细阅读下方的用户协议。\n阅读完成后请勾选确认。");
        var annotated = RemoteContent.parse(json.toString(), "main");
        assertTrue(annotated.agreement().introduction().contains("\n"));
        assertTrue(store.accepted(annotated));
        json.getAsJsonObject("agreement").addProperty("revision", 2);
        assertFalse(store.accepted(RemoteContent.parse(json.toString(), "main")));
        json.getAsJsonObject("agreement").addProperty("introduction", "长".repeat(2001));
        assertThrows(IOException.class, () -> RemoteContent.parse(json.toString(), "main"));
    }

    /// Local file dispatch receives the original Unicode path rather than a percent-encoded URL string.
    @Test
    void localDocumentUrisRoundTripUnicodeSpacesAndPercentSigns() throws Exception {
        Path file = root.resolve("client/documents/用户 协议 100%.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<html>协议</html>");
        String href = new URI(null, null, "client/documents/用户 协议 100%.html", null).toASCIIString();
        URI target = ContentLinks.resolve(root, href);
        assertEquals("file", target.getScheme());
        assertEquals(file.toRealPath(), Path.of(target));
        assertFalse(Path.of(target).toString().contains("%E7"));
    }

    /// A key mismatch cannot bind another server instance's protocol or announcements.
    @Test
    void requiresMatchingInstanceAndNewSchema() throws Exception {
        String body = ContentFixtures.basic("main").toString();
        assertEquals("main", RemoteContent.parse(body, "main").instanceKey());
        assertThrows(IOException.class, () -> RemoteContent.parse(body, "test"));
        assertThrows(IOException.class, () -> RemoteContent.parse(body.replace("\"schemaVersion\":2", "\"schemaVersion\":1"), "main"));
    }

    /// Item identity changes when a Markdown payload changes even if the administrator forgets to bump revision.
    @Test
    void keysIncludeResourceDigestsButIgnoreDisplayOrdering() throws Exception {
        var first = RemoteContent.parse(ContentFixtures.markdown("main", "# Old", new byte[0]).toString(), "main");
        var nextJson = ContentFixtures.markdown("main", "# New", new byte[0]);
        var next = RemoteContent.parse(nextJson.toString(), "main");
        assertNotEquals(first.key(first.announcements().get(0)), next.key(next.announcements().get(0)));
        nextJson.getAsJsonArray("announcements").get(0).getAsJsonObject().addProperty("priority", 999);
        var reordered = RemoteContent.parse(nextJson.toString(), "main");
        assertEquals(next.key(next.announcements().get(0)), reordered.key(reordered.announcements().get(0)));
    }

    /// Requires an exact file index during construction and cache reload, with an immutable copy for valid indexes.
    @Test
    void announcementSnapshotsAndValidatesItsFileIndex() throws Exception {
        var catalog = ContentFixtures.markdown("main", "# Announcement", new byte[]{1});
        String extra = "notices/other.md";
        ContentFixtures.file(catalog, extra, new byte[]{2});
        var manifest = RemoteContent.parse(catalog.toString(), "main");
        var item = manifest.announcements().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new RemoteContent.Announcement(item, manifest.files()));
        var captured = RemoteContent.Announcement.capture(manifest, item);
        assertEquals(item.files(), captured.files().keySet());
        var wrongPath = new RemoteContent.Resource("announcements/other.txt", RemoteContent.hash("wrong"), "");
        var mismatched = new HashMap<>(captured.files());
        mismatched.put(ContentFixtures.BODY, wrongPath);
        assertThrows(IllegalArgumentException.class, () -> new RemoteContent.Announcement(item, mismatched));
        var resources = new ArrayList<>(item.resources());
        var copiedItem = new RemoteContent.Item(item.id(), item.revision(), item.title(), item.introduction(), item.content(),
                item.contentFile(), resources, item.publishedAt(), item.startsAt(), item.expiresAt(),
                item.priority(), item.pinned(), item.repeat());
        var copied = new RemoteContent.Announcement(copiedItem, captured.files());
        resources.clear();
        assertEquals(item.files(), copied.item().files());

        var index = new HashMap<>(captured.files());
        var announcement = new RemoteContent.Announcement(item, index);
        assertEquals(manifest.key(item), announcement.key());
        var snapshot = RemoteContent.writeAnnouncement(announcement);
        assertEquals(announcement, RemoteContent.readAnnouncement(snapshot));
        snapshot.getAsJsonObject("files").add(extra, catalog.getAsJsonObject("files").get(extra));
        assertThrows(IOException.class, () -> RemoteContent.readAnnouncement(snapshot));

        index.remove(ContentFixtures.IMAGE);
        assertTrue(announcement.files().containsKey(ContentFixtures.IMAGE));
        assertThrows(UnsupportedOperationException.class, () -> announcement.files().remove(ContentFixtures.IMAGE));
        var failure = assertThrows(IllegalArgumentException.class, () -> new RemoteContent.Announcement(item, index));
        assertTrue(failure.getMessage().contains(ContentFixtures.IMAGE));
    }

    /// Receipts belong to an instance directory and server source, and survive payload garbage collection.
    @Test
    void isolatesAndPersistsReceipts() throws Exception {
        String body = ContentFixtures.basic("main").toString();
        var manifest = RemoteContent.parse(body, "main");
        var store = new ContentStore(root.resolve("A/.launcher"), "source-main");
        ContentFixtures.publishCache(store, ContentFixtures.cache(body, "", Instant.EPOCH));
        store.accept(manifest);
        store.remember("notices", "notice-key");
        ContentFixtures.prune(store, Set.of());
        var restored = new ContentStore(root.resolve("A/.launcher"), "source-main");
        assertTrue(restored.accepted(manifest));
        assertTrue(restored.remembered("notices", "notice-key"));
        assertFalse(new ContentStore(root.resolve("B/.launcher"), "source-main").accepted(manifest));
        assertFalse(new ContentStore(root.resolve("A/.launcher"), "different-source").accepted(manifest));
    }

    /// Waits for a competing cache writer and preserves both protocol consent and notice acknowledgement.
    @Test
    void mergesReceiptsFromConcurrentStoreInstances() throws Exception {
        var first = new ContentStore(root, "source");
        var second = new ContentStore(root, "source");
        String body = ContentFixtures.basic("main").toString();
        var manifest = RemoteContent.parse(body, "main");
        ContentFixtures.publishCache(first, ContentFixtures.cache(body, "", Instant.EPOCH));
        CountDownLatch started = new CountDownLatch(2);
        try (var channel = FileChannel.open(root.resolve("client/content.lock"), StandardOpenOption.WRITE);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var lock = channel.lock();
            try {
                var consent = executor.submit(() -> {
                    started.countDown();
                    first.accept(manifest);
                    return true;
                });
                var notice = executor.submit(() -> {
                    started.countDown();
                    second.remember("notices", "notice-key");
                    return true;
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> consent.get(25, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> notice.get(25, TimeUnit.MILLISECONDS));
                lock.release();
                assertTrue(consent.get(5, TimeUnit.SECONDS));
                assertTrue(notice.get(5, TimeUnit.SECONDS));
            } finally {
                lock.close();
            }
        }
        assertTrue(second.acceptedCurrent(manifest));
        assertTrue(first.remembered("notices", "notice-key"));
    }

    /// Rejects a stale protocol decision without invalidating consent after unrelated manifest updates.
    @Test
    void rejectsReceiptsForAnOutdatedAgreement() throws Exception {
        var first = new ContentStore(root, "source");
        var second = new ContentStore(root, "source");
        JsonObject json = ContentFixtures.basic("main");
        var original = RemoteContent.parse(json.toString(), "main");
        var initialCache = ContentFixtures.cache(json.toString(), "", Instant.EPOCH);
        ContentFixtures.publishCache(first, initialCache);
        first.accept(original);
        assertSame(initialCache.manifest(), java.util.Objects.requireNonNull(first.cache("main")).manifest());

        json.getAsJsonObject("agreement").addProperty("introduction", "Updated introduction");
        ContentFixtures.publishCache(second, ContentFixtures.cache(json.toString(), "", Instant.now()));
        assertTrue(first.acceptedCurrent(original));
        var updated = java.util.Objects.requireNonNull(first.cache("main")).manifest();
        assertEquals("Updated introduction", updated.agreement().introduction());
        assertSame(updated, java.util.Objects.requireNonNull(first.cache("main")).manifest());
        assertNull(first.cache("other"));

        json.getAsJsonObject("agreement").addProperty("revision", 2);
        var current = RemoteContent.parse(json.toString(), "main");
        ContentFixtures.publishCache(second, ContentFixtures.cache(json.toString(), "", Instant.now()));
        assertThrows(ContentStore.AgreementChangedException.class, () -> first.acceptedCurrent(original));
        assertFalse(second.acceptedCurrent(current));
        second.accept(current);
        assertThrows(ContentStore.AgreementChangedException.class, () -> first.accept(original));
        assertTrue(second.acceptedCurrent(current));
    }

    /// A failed write must never create in-memory-only consent.
    @Test
    void rejectsUnsavableConsent() throws Exception {
        Path file = root.resolve("not-a-directory");
        Files.writeString(file, "x");
        var store = new ContentStore(file, "source");
        var manifest = RemoteContent.parse(ContentFixtures.basic("main").toString(), "main");
        assertThrows(IOException.class, () -> store.accept(manifest));
        assertFalse(store.accepted(manifest));
    }

    /// Expired and withdrawn payloads are deleted while shared active/future resources and manual documents remain.
    @Test
    void cleansManagedFilesOnly() throws Exception {
        var store = new ContentStore(root, "source");
        var old = new RemoteContent.Resource("announcements/old.md", RemoteContent.hash("old"), "");
        var image = new RemoteContent.Resource("announcements/shared.png", RemoteContent.hash("image"), "");
        store.install(old, "old".getBytes(StandardCharsets.UTF_8));
        store.install(image, "image".getBytes(StandardCharsets.UTF_8));
        Path manual = root.resolve("client/documents/用户协议.html");
        Files.createDirectories(manual.getParent());
        Files.writeString(manual, "manual");
        Path unknown = store.file(old).resolveSibling("user-note.txt");
        Files.writeString(unknown, "not managed");
        ContentFixtures.prune(store, Set.of(image));
        assertFalse(Files.exists(store.file(old)));
        assertTrue(Files.exists(store.file(image)));
        assertTrue(Files.exists(manual));
        assertTrue(Files.exists(unknown));
        ContentFixtures.prune(store, Set.of());
        assertFalse(Files.exists(store.file(image)));
    }

    /// Wrong bytes and later local corruption cannot be reused as a successful digest cache hit.
    @Test
    void validatesPayloadHashes() throws Exception {
        var store = new ContentStore(root, "source");
        var resource = new RemoteContent.Resource("a.md", RemoteContent.hash("expected"), "");
        assertThrows(IOException.class, () -> store.install(resource, "wrong".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Files.exists(store.file(resource)));
        store.install(resource, "expected".getBytes(StandardCharsets.UTF_8));
        assertTrue(store.verified(resource));
        Files.writeString(store.file(resource), "changed");
        assertFalse(store.verified(resource));
    }

    /// Relative resource identifiers cannot escape the server namespace or become absolute Windows paths.
    @ParameterizedTest
    @ValueSource(strings = {"../x.md", "/x.md", "a/../x.md", "a//b.png", "C:/x.md", "a\\x.md", "%2e%2e/x.md", "a.md?x"})
    void rejectsEscapingResourcePaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> RemoteContent.logicalPath(path));
    }

    /// Local document links now resolve beneath the instance `.launcher` and remain required before consent.
    @Test
    void validatesInstanceLocalAgreementDocuments() throws Exception {
        Path file = root.resolve("client/documents/用户 协议.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "terms");
        var json = ContentFixtures.basic("main");
        json.getAsJsonObject("agreement").addProperty("content", "请阅读<a href='client/documents/用户 协议.html'>协议</a>");
        var manifest = RemoteContent.parse(json.toString(), "main");
        var store = new ContentStore(root, "source");
        ContentDocuments.validate(root, manifest, manifest.agreement(), store, URI.create("https://example.com/main/"), true);
        assertEquals(file.toRealPath().toUri(), ContentLinks.resolve(root, "client/documents/用户 协议.html"));
        Files.delete(file);
        assertThrows(IOException.class, () -> ContentDocuments.validate(root, manifest, manifest.agreement(), store,
                URI.create("https://example.com/main/"), true));
    }

    /// Markdown images resolve against the Markdown URL, not the EXE or game working directory.
    @Test
    void resolvesAndRequiresIndexedImages() throws Exception {
        byte[] image = "image-bytes".getBytes(StandardCharsets.UTF_8);
        String body = "# News\n![banner](assets/banner.png)";
        var manifest = RemoteContent.parse(ContentFixtures.markdown("main", body, image).toString(), "main");
        var store = new ContentStore(root, "source");
        store.install(manifest.files().get(ContentFixtures.BODY), body.getBytes(StandardCharsets.UTF_8));
        store.install(manifest.files().get(ContentFixtures.IMAGE), image);
        URI endpoint = URI.create("https://example.com/instances/main/");
        var item = manifest.announcements().get(0);
        assertEquals(manifest.files().get(ContentFixtures.IMAGE), ContentDocuments.resource(manifest, item, endpoint, "assets/banner.png"));
        ContentDocuments.validate(root, manifest, item, store, endpoint, false);
        Files.delete(store.file(manifest.files().get(ContentFixtures.IMAGE)));
        assertThrows(IOException.class, () -> ContentDocuments.validate(root, manifest, item, store, endpoint, false));
    }

    /// Future resources stay referenced, while expiry is exclusive and does not depend on publication labels.
    @Test
    void retainsScheduledContentAndDropsExpiredReferences() throws Exception {
        var json = ContentFixtures.markdown("main", "future", new byte[0]);
        var item = json.getAsJsonArray("announcements").get(0).getAsJsonObject();
        item.addProperty("startsAt", "2026-10-23T12:00:00+08:00");
        item.addProperty("expiresAt", "2026-10-24T12:00:00+08:00");
        var manifest = RemoteContent.parse(json.toString(), "main");
        Instant before = Instant.parse("2026-10-23T00:00:00Z");
        assertFalse(manifest.announcements().get(0).active(before));
        assertEquals(1, manifest.needed(before).size());
        assertTrue(manifest.needed(Instant.parse("2026-10-24T04:00:00Z")).isEmpty());
    }

    /// Configuration lives under the instance and an explicit key determines the public server directory.
    @Test
    void loadsInstanceConfiguration() throws Exception {
        assertFalse(ContentSync.Config.load(root).enabled());
        Files.createDirectories(root.resolve("client"));
        Files.writeString(root.resolve("client/config.json"), """
                {"enabled":true,"serverBaseUrl":"https://example.com/launcher/","instanceKey":"bf656-main"}
                """);
        var config = ContentSync.Config.load(root);
        assertEquals("https://example.com/launcher/instances/bf656-main/", config.endpoint().toString());
        assertThrows(IllegalArgumentException.class, () -> new ContentSync.Config(true, URI.create("http://example.com/"), "main").validate());
        assertThrows(IllegalArgumentException.class, () -> new ContentSync.Config(true, URI.create("https://example.com/"), "../other").validate());
    }

    /// Restricted inline markup cannot expand external XML entities.
    @Test
    void rejectsXmlEntities() {
        assertThrows(IOException.class, () -> ContentLinks.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///secret'>]>&e;"));
    }

    /// Rejects a mismatched fallback key and reloads a valid hashed snapshot independently of the latest manifest.
    @Test
    void storesFallbackBodyOutsideSyncIndex() throws Exception {
        String oldBody = "旧公告正文".repeat(20_000);
        JsonObject original = ContentFixtures.basic("main");
        JsonObject old = new JsonObject();
        old.addProperty("id", "news");
        old.addProperty("revision", 1);
        old.addProperty("title", "旧公告");
        old.addProperty("content", oldBody);
        original.getAsJsonArray("announcements").add(old);
        RemoteContent.Manifest originalManifest = RemoteContent.parse(original.toString(), "main");
        RemoteContent.Announcement fallback = RemoteContent.Announcement.capture(originalManifest,
                originalManifest.announcements().getFirst());

        String latest = ContentFixtures.markdown("main", "# 新公告", new byte[0]).toString();
        var store = new ContentStore(root, "source");
        assertThrows(IllegalArgumentException.class,
                () -> ContentFixtures.cache(latest, "\"next\"", Instant.EPOCH, Map.of("wrong-id", fallback)));
        ContentFixtures.publishCache(store, ContentFixtures.cache(latest, "\"next\"", Instant.EPOCH, Map.of("news", fallback)));
        Path state = root.resolve("client/state").resolve(RemoteContent.hash("source"));
        Path sync = state.resolve("sync.json");
        var reopened = new ContentStore(root, "source");

        String stateText = Files.readString(sync);
        assertTrue(Files.size(sync) < 4_096);
        assertFalse(stateText.contains("旧公告正文"));
        JsonObject references = JsonParser.parseString(stateText).getAsJsonObject().getAsJsonObject("announcementFallbacks");
        String digest = references.get("news").getAsString();
        Path snapshot = state.resolve("announcement-fallbacks").resolve(digest + ".json");
        assertEquals(digest, RemoteContent.digest(Files.readAllBytes(snapshot)));
        assertEquals(oldBody, java.util.Objects.requireNonNull(reopened.cache("main"))
                .announcementFallbacks().get("news").item().content());
        assertFalse(java.util.Objects.requireNonNull(reopened.cache("main")).fallbackIndexNeedsRewrite());
        assertNull(new ContentStore(root, "other-source").cache("main"));

        Files.writeString(snapshot, "{}");
        var damaged = java.util.Objects.requireNonNull(reopened.cache("main"));
        assertEquals(latest, damaged.body());
        assertTrue(damaged.announcementFallbacks().isEmpty());
        assertTrue(damaged.fallbackIndexNeedsRewrite());
        ContentFixtures.publishCache(reopened, damaged.withAnnouncementFallbacks(Map.of()));
        assertTrue(Files.exists(snapshot));
        ContentFixtures.prune(reopened, damaged.manifest().needed(Instant.now()));
        assertFalse(Files.exists(snapshot));
    }

    /// Preserves an older reader's payload and snapshot and rejects a stale publisher before reclaiming either file.
    @Test
    void protectsSharedCacheUntilTheOtherReaderCloses() throws Exception {
        var store = new ContentStore(root, "source");
        var readerStore = new ContentStore(root, "source");
        String oldBody = "# Old announcement";
        var old = RemoteContent.parse(ContentFixtures.markdown("main", oldBody, new byte[0]).toString(), "main");
        var resource = old.files().get(ContentFixtures.BODY);
        store.install(resource, oldBody.getBytes(StandardCharsets.UTF_8));
        var fallback = RemoteContent.Announcement.capture(old, old.announcements().getFirst());
        String waiting = ContentFixtures.markdown("main", "# Replacement", new byte[0]).toString();
        ContentFixtures.publishCache(store, ContentFixtures.cache(waiting, "", Instant.EPOCH, Map.of("news", fallback)));
        Path state = root.resolve("client/state").resolve(RemoteContent.hash("source"));
        var initial = JsonParser.parseString(Files.readString(state.resolve("sync.json"))).getAsJsonObject();
        String digest = initial.getAsJsonObject("announcementFallbacks").get("news").getAsString();
        Path snapshot = state.resolve("announcement-fallbacks").resolve(digest + ".json");
        String replacement = ContentFixtures.basic("main").toString();

        try (var writer = store.registerUsage()) {
            String baseline = store.loadCache("main").version();
            String published;
            try (var reader = readerStore.registerUsage()) {
                assertNotNull(reader);
                assertEquals(fallback, readerStore.loadCache("main").cache().announcementFallbacks().get("news"));
                published = store.publishCache(ContentFixtures.cache(replacement, "", Instant.EPOCH), baseline, writer);
                assertThrows(ContentStore.CacheChangedException.class, () -> readerStore.publishCache(
                        ContentFixtures.cache(waiting, "", Instant.EPOCH, Map.of("news", fallback)), baseline, reader));
                assertThrows(ContentStore.CacheChangedException.class,
                        () -> readerStore.maintainCache(null, baseline, reader, Set.of()));
                assertNull(store.maintainCache(null, published, writer, Set.of()).cleanupFailure());
                assertTrue(Files.isRegularFile(snapshot));
                assertEquals(oldBody, readerStore.body(fallback.item(), fallback.files()));
            }

            assertNull(store.maintainCache(null, published, writer, Set.of()).cleanupFailure());
            assertFalse(Files.exists(snapshot));
            assertFalse(Files.exists(store.file(resource)));
            assertEquals(replacement, java.util.Objects.requireNonNull(store.cache("main")).body());
        }
    }

    /// Exercises the OS lock path with a reader in a separate JVM.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectsSharedPayloadAcrossProcesses(boolean nonNumericRegistration) throws Exception {
        var store = new ContentStore(root, "source");
        byte @Unmodifiable [] bytes = "announcement".getBytes(StandardCharsets.UTF_8);
        var resource = new RemoteContent.Resource("announcements/shared.md", RemoteContent.digest(bytes), "");
        store.install(resource, bytes);
        Path ready = root.resolve("peer-ready");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isRegularFile(java))
            java = java.resolveSibling("java.exe");
        String classPath = String.join(File.pathSeparator,
                Path.of(PeerReader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(ContentStore.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(JsonObject.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());

        try (var writer = store.registerUsage()) {
            Process peer = new ProcessBuilder(java.toString(), "-cp", classPath, PeerReader.class.getName(),
                    root.toString(), ready.toString(), Boolean.toString(nonNumericRegistration)).redirectErrorStream(true).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(ready) && peer.isAlive() && System.nanoTime() < deadline)
                    Thread.sleep(20);
                assertTrue(Files.exists(ready), "The second JVM must hold an instance registration");
                assertNull(store.maintainCache(null, "", writer, Set.of()).cleanupFailure());
                assertTrue(Files.exists(store.file(resource)));
                peer.getOutputStream().close();
                assertTrue(peer.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, peer.exitValue(), new String(peer.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } finally {
                if (peer.isAlive()) {
                    peer.destroyForcibly();
                    peer.waitFor(5, TimeUnit.SECONDS);
                }
            }
            assertNull(store.maintainCache(null, "", writer, Set.of()).cleanupFailure());
            assertFalse(Files.exists(store.file(resource)));
        }
    }

    /// Holds the real cache registration in another launcher process.
    @NotNullByDefault
    public static final class PeerReader {
        /// Holds the registration until the parent closes its input pipe.
        public static void main(String @Unmodifiable [] args) throws Exception {
            if (Boolean.parseBoolean(args[2])) {
                Path path = Path.of(args[0]).resolve("client/content-users/session-peer.lock");
                try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                     var lock = channel.lock()) {
                    Files.createFile(Path.of(args[1]));
                    System.in.read();
                }
                return;
            }
            var store = new ContentStore(Path.of(args[0]), "other-source");
            try (var registration = store.registerUsage()) {
                Files.createFile(Path.of(args[1]));
                System.in.read();
            }
        }
    }

    /// Expired inline text is removed from persisted metadata while its conditional HTTP validator remains usable.
    @Test
    void compactsExpiredBodiesAndKeepsEtag() throws Exception {
        var json = ContentFixtures.basic("main");
        var notice = new com.google.gson.JsonObject();
        notice.addProperty("id", "expired");
        notice.addProperty("revision", 1);
        notice.addProperty("title", "old");
        notice.addProperty("content", "expired-secret-marker");
        notice.addProperty("expiresAt", "2026-10-20T00:00:00Z");
        json.getAsJsonArray("notices").add(notice);
        var store = new ContentStore(root, "source");
        var cache = ContentFixtures.cache(json.toString(), "\"etag\"", Instant.EPOCH);
        ContentFixtures.publishCache(store, cache);
        var compacted = ContentStore.compacted(cache, Instant.parse("2026-10-21T00:00:00Z"));
        try (var usage = store.registerUsage()) {
            var before = store.loadCache("main");
            store.publishCache(compacted, before.version(), usage);
        }
        assertFalse(compacted.body().contains("expired-secret-marker"));
        assertEquals("\"etag\"", compacted.etag());
        assertTrue(compacted.manifest().notices().isEmpty());
        assertEquals(RemoteContent.parse(compacted.body(), "main"), compacted.manifest());
        assertEquals(compacted.body(), java.util.Objects.requireNonNull(store.cache("main")).body());
    }
}
