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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/// Tests publication windows, independent consent identity, safe relative documents and durable state.
@NotNullByDefault
final class RemoteContentTest {
    /// An isolated installation, including paths with Chinese names and spaces.
    @TempDir
    Path directory;

    /// Minimal valid agreement manifest reused by transport and state tests.
    static final String AGREEMENT = """
            {"schemaVersion":1,"agreement":{"id":"terms","revision":1,"title":"协议","content":"正文"}}
            """;

    /// Empty lists are valid server snapshots and remove previous content.
    @Test
    void acceptsEmptyListsAndRequiresAgreement() throws Exception {
        assertTrue(RemoteContent.parse(RemoteContent.Kind.NOTICES, "{\"schemaVersion\":1,\"notices\":[]}").isEmpty());
        assertTrue(RemoteContent.parse(RemoteContent.Kind.ANNOUNCEMENTS, "{\"schemaVersion\":1,\"announcements\":[]}").isEmpty());
        assertThrows(IOException.class, () -> RemoteContent.parse(RemoteContent.Kind.AGREEMENT, "{\"schemaVersion\":1}"));
    }

    /// Invalid schema, fractional versions and unsupported markup cannot become cached consent.
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":2}",
            "{\"schemaVersion\":1,\"agreement\":null}",
            "{\"schemaVersion\":1,\"agreement\":{\"id\":\"a\",\"revision\":1.5,\"title\":\"t\",\"content\":\"x\"}}",
            "{\"schemaVersion\":1,\"agreement\":{\"id\":\"a\",\"revision\":1,\"title\":\"t\",\"content\":\"<script>x</script>\"}}"
    })
    void rejectsMalformedAgreement(String manifest) {
        assertThrows(IOException.class, () -> RemoteContent.parse(RemoteContent.Kind.AGREEMENT, manifest));
    }

    /// Same-ID revisions and accidental body changes both require fresh consent after restart.
    @Test
    void persistsConsentAndSeparatesSources() throws Exception {
        var first = RemoteContent.parse(RemoteContent.Kind.AGREEMENT, AGREEMENT).get(0);
        var revision = RemoteContent.parse(RemoteContent.Kind.AGREEMENT, AGREEMENT.replace("\"revision\":1", "\"revision\":2")).get(0);
        var changed = RemoteContent.parse(RemoteContent.Kind.AGREEMENT, AGREEMENT.replace("正文", "新正文")).get(0);
        var store = new ContentStore(directory, "https://one.example/launcher/");
        assertFalse(store.accepted(first));
        store.accept(first);
        var restored = new ContentStore(directory, "https://one.example/launcher/");
        assertTrue(restored.accepted(first));
        assertFalse(restored.accepted(revision));
        assertFalse(restored.accepted(changed));
        assertFalse(new ContentStore(directory, "https://two.example/launcher/").accepted(first));
        restored.remember("notices", "message");
        assertTrue(new ContentStore(directory, "https://one.example/launcher/").remembered("notices", "message"));
        assertTrue(restored.accepted(first));
    }

    /// A failed state write never produces consent even within the same process.
    @Test
    void doesNotAcceptWhenPersistenceFails() throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "x");
        var store = new ContentStore(file, "source");
        var item = RemoteContent.parse(RemoteContent.Kind.AGREEMENT, AGREEMENT).get(0);
        assertThrows(IOException.class, () -> store.accept(item));
        assertFalse(store.accepted(item));
    }

    /// A start boundary is inclusive and expiry is exclusive, independent of timestamp timezone.
    @Test
    void honorsPublicationWindowAndPriority() throws Exception {
        var items = RemoteContent.parse(RemoteContent.Kind.NOTICES, """
                {"schemaVersion":1,"notices":[
                  {"id":"a","revision":1,"title":"a","content":"a","priority":0},
                  {"id":"b","revision":1,"title":"b","content":"b","priority":10,
                   "startsAt":"2026-10-21T12:00:00+08:00","expiresAt":"2026-10-21T13:00:00+08:00"}
                ]}
                """);
        assertEquals("b", items.get(0).id());
        assertFalse(items.get(0).active(Instant.parse("2026-10-21T03:59:59Z")));
        assertTrue(items.get(0).active(Instant.parse("2026-10-21T04:00:00Z")));
        assertFalse(items.get(0).active(Instant.parse("2026-10-21T05:00:00Z")));
    }

    /// Relative files are resolved against the supplied installation, including percent-encoded Unicode.
    @Test
    void opensRelativeDocumentsAndRequiresFilesBeforeConsent() throws Exception {
        Path documents = Files.createDirectories(directory.resolve("documents"));
        Path document = documents.resolve("用户 协议.html");
        Files.writeString(document, "terms");
        assertEquals(document.toRealPath().toUri(), ContentLinks.resolve(directory, "documents/用户 协议.html"));
        assertEquals(document.toRealPath().toUri(), ContentLinks.resolve(directory,
                "documents/%E7%94%A8%E6%88%B7%20%E5%8D%8F%E8%AE%AE.html"));
        var item = RemoteContent.parse(RemoteContent.Kind.AGREEMENT,
                AGREEMENT.replace("正文", "<a href='documents/用户 协议.html'>协议</a>")).get(0);
        ContentLinks.validateAgreement(directory, item);
        Files.delete(document);
        assertThrows(IOException.class, () -> ContentLinks.validateAgreement(directory, item));
    }

    /// Remote text cannot open executables, invoke launcher protocols or escape via encoded paths.
    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "%2e%2e/outside.txt", "/absolute.txt", "C:/Windows/test.txt",
            "file:///C:/Windows/test.txt", "hmcl://game/launch", "javascript:alert(1)",
            "//server/share.txt", "documents\\test.txt", "tool.exe", "missing.pdf"})
    void rejectsUnsafeOrMissingLinks(String href) {
        assertThrows(IOException.class, () -> ContentLinks.resolve(directory, href));
    }

    /// Rich text is bounded to display elements and does not expand declarations or external entities.
    @Test
    void rejectsEntitiesAndParsesSupportedMarkup() throws Exception {
        assertThrows(IOException.class, () -> ContentLinks.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///secret'>]>&e;"));
        var segments = ContentLinks.parse("请阅读<b>协议</b><br/><a href='documents/terms.pdf'>打开</a>");
        assertEquals(4, segments.size());
        assertTrue(segments.get(1).bold());
        assertEquals("documents/terms.pdf", segments.get(3).href());
    }

    /// Public endpoints must use HTTPS; loopback HTTP remains available for deployment checks.
    @Test
    void validatesConfiguration() throws Exception {
        assertFalse(ContentSync.Config.load(directory).enabled());
        ContentSync.Config.validateBase(java.net.URI.create("http://127.0.0.1:8000/"));
        assertThrows(IOException.class, () -> ContentSync.Config.validateBase(java.net.URI.create("http://example.com/")));
        assertThrows(IOException.class, () -> ContentSync.Config.validateBase(java.net.URI.create("https://example.com/launcher")));
        Files.createDirectories(directory.resolve(".hmcl"));
        Files.writeString(directory.resolve(".hmcl/remote-content.json"), "broken");
        assertThrows(IOException.class, () -> ContentSync.Config.load(directory));
    }
}
