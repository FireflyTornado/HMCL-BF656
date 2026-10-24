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

/// Covers the standalone overall schema and publishing without touching instance metadata.
@NotNullByDefault
final class OverallContentTest {
    /// Isolated authoring and publication directories.
    @TempDir
    Path root;

    /// A global manifest requires neither an instance key nor a protocol and can explicitly restore local appearance.
    @Test
    void parsesIndependentEmptyAndDisabledPools() throws Exception {
        assertTrue(OverallContent.parse("{\"schemaVersion\":1}").background().isEmpty());
        assertTrue(OverallContent.parse("{\"schemaVersion\":1,\"background\":{\"enabled\":false,"
                + "\"urls\":[\"https://example.com/api\"]}}").needed().isEmpty());
        assertFalse(OverallContent.parse("{\"schemaVersion\":1,\"background\":{"
                + "\"urls\":[\"https://example.com/api\"]}}").background().isEmpty());
    }

    /// Malformed or ambiguous source metadata cannot replace a previously usable cache.
    @Test
    void rejectsInvalidMetadata() {
        for (String json : new String[]{
                "{\"schemaVersion\":2}", "{\"schemaVersion\":\"1\"}", "{\"schemaVersion\":1.5}",
                "{\"schemaVersion\":1,\"background\":{\"images\":[\"../bad.jpg\"]}}",
                "{\"schemaVersion\":1,\"background\":{\"images\":[\"missing.jpg\"]}}",
                "{\"schemaVersion\":1,\"background\":{\"urls\":[\"http://example.com/a\"]}}",
                "{\"schemaVersion\":1,\"background\":{\"urls\":[42]}}",
                "{\"schemaVersion\":1,\"background\":null}"})
            assertThrows(IOException.class, () -> OverallContent.parse(json));
    }

    /// Only referenced files are exported; authoring filenames remain readable and hashes are generated automatically.
    @Test
    void publishesReferencedImagesAndRetainsFutureGlobalFields() throws Exception {
        Path source = root.resolve("overall");
        Files.createDirectories(source.resolve("backgrounds"));
        Files.writeString(source.resolve("backgrounds/a.png"), "image:A");
        Files.writeString(source.resolve("private.txt"), "private");
        Files.writeString(source.resolve("content.json"), "{\"schemaVersion\":1,\"futureSetting\":{\"value\":1},"
                + "\"background\":{\"images\":[\"backgrounds/a.png\"],\"urls\":[\"https://example.com/api\"]}}");
        Path output = root.resolve("public/overall");
        OverallPublisher.publish(source, output);
        String body = Files.readString(output.resolve("manifest.json"));
        var manifest = OverallContent.parse(body);
        assertEquals(1, manifest.needed().size());
        assertEquals(RemoteContent.hash("image:A"), manifest.files().get("backgrounds/a.png").sha256());
        assertEquals("image:A", Files.readString(output.resolve("backgrounds/a.png")));
        assertTrue(JsonParser.parseString(body).getAsJsonObject().has("futureSetting"));
        assertFalse(Files.exists(output.resolve("content.json")));
        assertFalse(Files.exists(output.resolve("private.txt")));
    }

    /// Missing resources fail before the public manifest or previously published image is changed.
    @Test
    void publishingFailurePreservesPriorPublication() throws Exception {
        Path source = root.resolve("overall");
        Path output = root.resolve("public/overall");
        Files.createDirectories(source);
        Files.createDirectories(output);
        Files.writeString(output.resolve("manifest.json"), "previous");
        Files.writeString(source.resolve("content.json"), "{\"schemaVersion\":1,"
                + "\"background\":{\"images\":[\"backgrounds/missing.jpg\"]}}");
        assertThrows(IOException.class, () -> OverallPublisher.publish(source, output));
        assertEquals("previous", Files.readString(output.resolve("manifest.json")));
    }

    /// Explicit CDN descriptors are indexed without downloading private authoring resources during publication.
    @Test
    void supportsFixedDigestCdnImages() throws Exception {
        Path source = root.resolve("overall");
        Files.createDirectories(source);
        Files.writeString(source.resolve("content.json"), "{\"schemaVersion\":1,"
                + "\"background\":{\"images\":[\"backgrounds/cdn.jpg\"]},"
                + "\"externalFiles\":{\"backgrounds/cdn.jpg\":{\"url\":\"https://example.com/cdn.jpg\","
                + "\"sha256\":\"" + RemoteContent.hash("image:CDN") + "\"}}}");
        Path output = root.resolve("public");
        OverallPublisher.publish(source, output);
        var manifest = OverallContent.parse(Files.readString(output.resolve("manifest.json")));
        assertEquals("https://example.com/cdn.jpg", manifest.files().get("backgrounds/cdn.jpg").url());
        assertFalse(Files.exists(output.resolve("backgrounds/cdn.jpg")));
    }

    /// Local connection configuration is optional and separate from all version-root configuration files.
    @Test
    void validatesGlobalClientConfiguration() throws Exception {
        assertFalse(OverallSync.Config.load(root).enabled());
        Files.createDirectories(root.resolve("client"));
        Files.writeString(root.resolve("client/config.json"), "{\"enabled\":true,\"serverBaseUrl\":\"https://example.com/launcher/\"}");
        var config = OverallSync.Config.load(root);
        assertEquals("https://example.com/launcher/overall/", config.endpoint().toString());
        Files.writeString(root.resolve("client/config.json"), "{\"enabled\":true,\"serverBaseUrl\":\"https://example.com/launcher\"}");
        assertThrows(IOException.class, () -> OverallSync.Config.load(root));
    }
}
