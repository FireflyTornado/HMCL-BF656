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

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Real publication verifies directory discovery, scalar metadata, stable identities and list-free updates.
@NotNullByDefault
final class DirectoryPublishingTest {
    /// Isolated editing and publication directories.
    @TempDir
    Path root;

    /// New Markdown is discovered automatically, titles come from Markdown, and headers are not public body bytes.
    @Test
    void discoversDocumentsAndStripsMetadataBeforeHashing() throws Exception {
        Path source = source();
        Files.createDirectories(source.resolve("announcements/assets"));
        Files.writeString(source.resolve("announcements/news.md"), "---\r\n"
                + "id: launch-news\r\npriority: 20\r\npinned: true\r\n"
                + "publishedAt: 2026-10-25T12:00:00+08:00\r\n---\r\n"
                + "# **Launch** `news`\r\n\r\n![poster](assets/banner.png)\r\n");
        Files.writeString(source.resolve("announcements/assets/banner.png"), "poster");
        Files.writeString(source.resolve("announcements/assets/attachment.md"), "# Attachment");
        Files.writeString(source.resolve("announcements/_draft.md"), "# Draft");
        Files.writeString(source.resolve("announcements/.hidden.md"), "# Hidden");
        Files.createDirectories(source.resolve("notices"));
        Files.writeString(source.resolve("notices/hello.md"), "---\nrepeat: each_start\n---\nHello without a heading");
        Path output = root.resolve("public");
        ContentPublisher.publish(source, output, "main");
        var manifest = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        assertEquals(1, manifest.announcements().size());
        var news = manifest.announcements().get(0);
        assertEquals("launch-news", news.id());
        assertEquals("Launch news", news.title());
        assertTrue(news.pinned());
        assertEquals(20, news.priority());
        assertTrue(news.resources().contains("announcements/assets/banner.png"));
        String body = "# **Launch** `news`\r\n\r\n![poster](assets/banner.png)\r\n";
        assertEquals(body, Files.readString(output.resolve("announcements/news.md")));
        assertEquals(RemoteContent.hash(body), manifest.files().get("announcements/news.md").sha256());
        assertEquals("notices/hello", manifest.notices().get(0).id());
        assertEquals("hello", manifest.notices().get(0).title());
        assertEquals("each_start", manifest.notices().get(0).repeat());
        assertFalse(Files.exists(output.resolve("announcements/assets/attachment.md")));
        assertFalse(Files.exists(output.resolve("announcements/_draft.md")));
    }

    /// Body and linked-image edits change content identity without requiring a manual revision bump.
    @Test
    void updatesAndWithdrawsWithoutEditingAList() throws Exception {
        Path source = source();
        Files.createDirectories(source.resolve("announcements/assets"));
        Path document = source.resolve("announcements/activity.md");
        Path image = source.resolve("announcements/assets/a.jpg");
        Files.writeString(document, "# Activity\n![image](assets/a.jpg)");
        Files.writeString(image, "first");
        Path output = root.resolve("public");
        ContentPublisher.publish(source, output, "main");
        var first = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        String firstKey = first.key(first.announcements().get(0));
        Files.writeString(image, "second");
        ContentPublisher.publish(source, output, "main");
        var second = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        assertEquals(first.announcements().get(0).id(), second.announcements().get(0).id());
        assertEquals(1, second.announcements().get(0).revision());
        assertNotEquals(firstKey, second.key(second.announcements().get(0)));
        Files.writeString(document, "# Updated activity\n![image](assets/a.jpg)");
        ContentPublisher.publish(source, output, "main");
        var third = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        assertNotEquals(second.key(second.announcements().get(0)), third.key(third.announcements().get(0)));
        Files.delete(document);
        ContentPublisher.publish(source, output, "main");
        var withdrawn = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        assertTrue(withdrawn.announcements().isEmpty());
        assertFalse(withdrawn.files().containsKey("announcements/assets/a.jpg"));
    }

    /// Agreement presentation edits do not change protocol identity, while explicit revisions still can force consent.
    @Test
    void introductionMetadataPreservesProtocolIdentity() throws Exception {
        Path source = source();
        Path agreement = source.resolve("agreements/terms.md");
        Files.writeString(agreement, "---\nid: terms\nintroduction: \"Read first\\nThen confirm\"\n---\n# Terms\nBody");
        Path output = root.resolve("public");
        ContentPublisher.publish(source, output, "main");
        var first = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        assertEquals("Read first\nThen confirm", first.agreement().introduction());
        Files.writeString(agreement, "---\nid: terms\nintroduction: 'Updated notice'\n---\n# Terms\nBody");
        ContentPublisher.publish(source, output, "main");
        var second = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        assertEquals(first.key(first.agreement()), second.key(second.agreement()));
        Files.writeString(agreement, "---\nid: terms\nrevision: 2\n---\n# Terms\nBody");
        ContentPublisher.publish(source, output, "main");
        var third = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        assertNotEquals(second.key(second.agreement()), third.key(third.agreement()));
    }

    /// The agreement cannot be selected ambiguously and invalid metadata must not replace a valid publication.
    @Test
    void rejectsAmbiguousAgreementsAndInvalidMetadata() throws Exception {
        Path source = source();
        Path output = root.resolve("public");
        ContentPublisher.publish(source, output, "main");
        String original = Files.readString(output.resolve("manifest.json"));
        Files.writeString(source.resolve("agreements/other.md"), "# Other");
        assertThrows(IOException.class, () -> ContentPublisher.publish(source, output, "main"));
        Files.delete(source.resolve("agreements/other.md"));
        Files.createDirectories(source.resolve("announcements"));
        for (String header : new String[]{"pinned: yes", "priority: 1.5", "title: First\ntitle: Second",
                "unknown: value", "expiresAt: invalid-date", "revision: 0", "title: |\n  multiline"}) {
            Files.writeString(source.resolve("announcements/bad.md"), "---\n" + header + "\n---\n# Body");
            assertThrows(IOException.class, () -> ContentPublisher.publish(source, output, "main"));
            assertEquals(original, Files.readString(output.resolve("manifest.json")));
        }
        Files.writeString(source.resolve("announcements/bad.md"), "---\ntitle: Missing closing delimiter\n");
        assertThrows(IOException.class, () -> ContentPublisher.publish(source, output, "main"));
    }

    /// Existing path layout can infer the server key while a custom source uses the explicit command-line key.
    @Test
    void supportsExistingServerDirectoryLayout() throws Exception {
        Path source = root.resolve("instances/bf656-main/.launcher/server");
        Files.createDirectories(source.resolve("agreements"));
        Files.writeString(source.resolve("agreements/terms.md"), "# Terms");
        Path output = root.resolve("public");
        ContentPublisher.publish(source, output);
        assertEquals("bf656-main", RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "bf656-main").instanceKey());
        assertThrows(IOException.class, () -> ContentPublisher.publish(source(), root.resolve("custom")));
    }

    /// Every supported background image is discovered recursively; URL lines and an optional switch need no JSON list.
    @Test
    void discoversImagesAndUrlsAndReflectsAdditionsAndDeletions() throws Exception {
        Path source = root.resolve("overall");
        Files.createDirectories(source.resolve("backgrounds/events"));
        Files.writeString(source.resolve("backgrounds/events/a.JPG"), "image-a");
        Files.writeString(source.resolve("backgrounds/_draft.png"), "draft");
        Files.writeString(source.resolve("backgrounds/README.txt"), "private notes");
        Files.writeString(source.resolve("backgrounds/urls.txt"), "# API addresses\nhttps://example.com/api\n\nhttps://example.com/api\n");
        Path output = root.resolve("public/overall");
        OverallPublisher.publish(source, output);
        var first = OverallContent.parse(Files.readString(output.resolve("manifest.json")));
        assertEquals(1, first.background().images().size());
        assertEquals(1, first.background().urls().size());
        assertEquals("backgrounds/events/a.JPG", first.background().images().get(0));
        assertFalse(Files.exists(output.resolve("backgrounds/urls.txt")));
        Files.writeString(source.resolve("backgrounds/b.webp"), "image-b");
        OverallPublisher.publish(source, output);
        assertEquals(2, OverallContent.parse(Files.readString(output.resolve("manifest.json"))).background().images().size());
        Files.delete(source.resolve("backgrounds/events/a.JPG"));
        Files.delete(source.resolve("backgrounds/b.webp"));
        Files.writeString(source.resolve("backgrounds/urls.txt"), "# none\n");
        OverallPublisher.publish(source, output);
        assertTrue(OverallContent.parse(Files.readString(output.resolve("manifest.json"))).background().isEmpty());
        Files.writeString(source.resolve("settings.properties"), "background.enabled=false\n");
        OverallPublisher.publish(source, output);
        assertFalse(OverallContent.parse(Files.readString(output.resolve("manifest.json"))).background().enabled());
    }

    /// Invalid dynamic URLs are rejected before the existing public manifest changes.
    @Test
    void rejectsInvalidDirectorySettingsAndUrls() throws Exception {
        Path source = root.resolve("overall");
        Files.createDirectories(source.resolve("backgrounds"));
        Path output = root.resolve("public");
        OverallPublisher.publish(source, output);
        String first = Files.readString(output.resolve("manifest.json"));
        Files.writeString(source.resolve("backgrounds/urls.txt"), "http://example.com/insecure-api");
        assertThrows(IOException.class, () -> OverallPublisher.publish(source, output));
        assertEquals(first, Files.readString(output.resolve("manifest.json")));
        Files.delete(source.resolve("backgrounds/urls.txt"));
        Files.writeString(source.resolve("settings.properties"), "background.enabled=yes\n");
        assertThrows(IOException.class, () -> OverallPublisher.publish(source, output));
    }

    /// Identical files publish identical manifest bytes, independently of filesystem timestamps.
    @Test
    void stablePublicationDoesNotUseFileModificationTimes() throws Exception {
        Path source = source();
        Path output = root.resolve("public");
        ContentPublisher.publish(source, output, "main");
        String first = Files.readString(output.resolve("manifest.json"));
        Files.setLastModifiedTime(source.resolve("agreements/terms.md"), java.nio.file.attribute.FileTime.fromMillis(123456789));
        ContentPublisher.publish(source, output, "main");
        assertEquals(first, Files.readString(output.resolve("manifest.json")));
        assertFalse(JsonParser.parseString(first).getAsJsonObject().has("content"));
    }

    /// Creates a minimal standalone instance source with a required protocol and no content.json.
    private Path source() throws IOException {
        Path source = root.resolve("author");
        Files.createDirectories(source.resolve("agreements"));
        Files.writeString(source.resolve("agreements/terms.md"), "# Terms\nRead and accept.");
        return source;
    }
}
