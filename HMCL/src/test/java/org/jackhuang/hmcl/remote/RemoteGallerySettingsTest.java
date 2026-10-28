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
import org.jackhuang.hmcl.setting.BackgroundType;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.theme.ThemeBackground;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies settings persistence, theme round-tripping and shared-cache integrity independently of JavaFX.
@NotNullByDefault
final class RemoteGallerySettingsTest {
    /// Common cache root isolated from the player's real files.
    @TempDir
    Path root;

    /// Switching source types preserves both addresses in the standard launcher settings object.
    @Test
    void persistsSeparateNetworkAndGalleryAddresses() {
        LauncherSettings settings = new LauncherSettings();
        settings.backgroundTypeProperty().set(BackgroundType.REMOTE_GALLERY);
        settings.remoteGalleryManifestUrlProperty().set("https://example.com/overall/manifest.json?channel=a");
        settings.networkBackgroundImageUrlProperty().set("https://example.com/random");
        LauncherSettings restored = Objects.requireNonNull(LauncherSettings.fromJson(JsonParser.parseString(settings.toJson()).getAsJsonObject()));
        assertEquals(BackgroundType.REMOTE_GALLERY, restored.backgroundTypeProperty().get());
        assertEquals(settings.remoteGalleryManifestUrlProperty().get(), restored.remoteGalleryManifestUrlProperty().get());
        assertEquals(settings.networkBackgroundImageUrlProperty().get(), restored.networkBackgroundImageUrlProperty().get());
        restored.backgroundTypeProperty().set(BackgroundType.NETWORK);
        assertEquals(settings.remoteGalleryManifestUrlProperty().get(), restored.remoteGalleryManifestUrlProperty().get());
    }

    /// Exported themes retain the manifest source rather than an old random image or an implicit background override.
    @Test
    void themeSourceRoundTripsGalleryUrl() {
        ThemeBackground.Gallery gallery = new ThemeBackground.Gallery("https://example.com/overall/manifest.json");
        JsonObject json = gallery.toJsonObject();
        assertEquals(gallery, ThemeBackground.fromJson(json));
        assertThrows(IllegalArgumentException.class, () -> new ThemeBackground.Gallery("http://example.com/manifest.json"));
        assertThrows(IllegalArgumentException.class, () -> new ThemeBackground.Gallery("https://example.com/manifest.json#fragment"));
    }

    /// SHA-256 images share the common repository while the original SHA-1 network cache remains functional.
    @Test
    void repositorySupportsVerifiedSha256WithoutBreakingSha1() throws Exception {
        CacheRepository repository = new CacheRepository();
        repository.changeDirectory(root);
        Path original = root.resolve("original.png");
        Files.writeString(original, "image");
        String sha1 = DigestUtils.digestToString(CacheRepository.SHA1, original);
        Path ordinary = repository.cacheFile(original, CacheRepository.SHA1, sha1);
        assertEquals(ordinary, repository.checkExistentFile(null, CacheRepository.SHA1, sha1).orElseThrow());
        String sha256 = RemoteContent.hash("image");
        Path gallery = repository.cacheFile(original, CacheRepository.SHA256, sha256);
        assertEquals(gallery, repository.checkExistentFile(null, CacheRepository.SHA256, sha256).orElseThrow());
        assertTrue(gallery.startsWith(repository.getCacheDirectory()));
        Files.writeString(gallery, "damaged");
        assertTrue(repository.checkExistentFile(null, CacheRepository.SHA256, sha256).isEmpty());
        assertTrue(Files.exists(ordinary));
        assertThrows(java.io.IOException.class, () -> repository.cacheFile(original, CacheRepository.SHA256, "../invalid"));
    }
}
