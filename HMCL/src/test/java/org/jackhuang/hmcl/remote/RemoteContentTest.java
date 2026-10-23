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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

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

    /// Receipts belong to an instance directory and server source, and survive payload garbage collection.
    @Test
    void isolatesAndPersistsReceipts() throws Exception {
        var manifest = RemoteContent.parse(ContentFixtures.basic("main").toString(), "main");
        var store = new ContentStore(root.resolve("A/.launcher"), "source-main");
        store.accept(manifest);
        store.remember("notices", "notice-key");
        store.prune(Set.of());
        var restored = new ContentStore(root.resolve("A/.launcher"), "source-main");
        assertTrue(restored.accepted(manifest));
        assertTrue(restored.remembered("notices", "notice-key"));
        assertFalse(new ContentStore(root.resolve("B/.launcher"), "source-main").accepted(manifest));
        assertFalse(new ContentStore(root.resolve("A/.launcher"), "different-source").accepted(manifest));
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
        Path unknown = root.resolve("server/files/user-note.txt");
        Files.writeString(unknown, "not managed");
        store.prune(Set.of(image));
        assertFalse(Files.exists(store.file(old)));
        assertTrue(Files.exists(store.file(image)));
        assertTrue(Files.exists(manual));
        assertTrue(Files.exists(unknown));
        store.prune(Set.of());
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
        var cache = new ContentStore.Cache(json.toString(), "\"etag\"", Instant.EPOCH);
        store.cache(cache);
        var compacted = store.compact(cache, "main", Instant.parse("2026-10-21T00:00:00Z"));
        assertFalse(compacted.body().contains("expired-secret-marker"));
        assertEquals("\"etag\"", compacted.etag());
        assertTrue(RemoteContent.parse(compacted.body(), "main").notices().isEmpty());
        assertFalse(Files.readString(root.resolve("server/manifest.json")).contains("expired-secret-marker"));
    }
}
