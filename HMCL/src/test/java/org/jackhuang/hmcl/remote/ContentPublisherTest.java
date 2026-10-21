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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Publishing uses the real Markdown parser to discover image/document dependencies and generate exact file hashes.
@NotNullByDefault
final class ContentPublisherTest {
    /// Isolated authoring and public output directories.
    @TempDir
    Path root;

    /// Reference-style images are indexed automatically and only referenced public payloads are exported.
    @Test
    void publishesMarkdownDependencies() throws Exception {
        Path source = root.resolve("author/.launcher/server");
        Files.createDirectories(source.resolve("announcements/assets"));
        Files.writeString(source.resolve(ContentFixtures.BODY), "# News\n![banner][pic]\n\n[pic]: assets/banner.png");
        Files.writeString(source.resolve(ContentFixtures.IMAGE), "image-bytes");
        Files.writeString(source.resolve("private-unused.txt"), "must not be published");
        var json = ContentFixtures.markdown("main", "unused", new byte[0]);
        json.remove("files");
        Files.writeString(source.resolve("content.json"), json.toString());
        Path output = root.resolve("public/instances/main");
        ContentPublisher.publish(source, output);
        var manifest = RemoteContent.parse(Files.readString(output.resolve("manifest.json")), "main");
        assertEquals(2, manifest.files().size());
        assertTrue(manifest.announcements().get(0).resources().contains(ContentFixtures.IMAGE));
        assertEquals(RemoteContent.hash("image-bytes"), manifest.files().get(ContentFixtures.IMAGE).sha256());
        assertTrue(Files.isRegularFile(output.resolve(ContentFixtures.IMAGE)));
        assertFalse(Files.exists(output.resolve("content.json")));
        assertFalse(Files.exists(output.resolve("private-unused.txt")));
    }

    /// Missing local files stop publishing before an incomplete new manifest becomes visible.
    @Test
    void rejectsMissingImagesBeforeReplacingManifest() throws Exception {
        Path source = root.resolve("author/.launcher/server");
        Files.createDirectories(source.resolve("announcements"));
        Files.writeString(source.resolve(ContentFixtures.BODY), "![missing](assets/not-there.png)");
        var json = ContentFixtures.markdown("main", "unused", new byte[0]);
        json.remove("files");
        Files.writeString(source.resolve("content.json"), json.toString());
        Path output = root.resolve("public");
        Files.createDirectories(output);
        Files.writeString(output.resolve("manifest.json"), "old-valid-marker");
        assertThrows(IOException.class, () -> ContentPublisher.publish(source, output));
        assertEquals("old-valid-marker", Files.readString(output.resolve("manifest.json")));
    }
}
