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

import org.jackhuang.hmcl.util.CacheRepository;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/// Stores gallery metadata and images in HMCL's existing cache, without an overall client/server directory.
@NotNullByDefault
public final class GalleryCache {
    /// Shared repository also used by ordinary network images.
    private final CacheRepository repository;
    /// Snapshot of the selected cache root, used to invalidate work when the user changes it.
    private final Path directory;

    /// Binds the current repository location without migrating old background-specific data.
    public GalleryCache(CacheRepository repository) {
        this.repository = repository;
        directory = repository.getCacheDirectory().toAbsolutePath().normalize();
    }

    /// Current cache root, shared with ordinary network-image downloads.
    public Path directory() {
        return directory;
    }

    /// Whether the repository still points to the cache root captured for this synchronization.
    public boolean current() {
        return directory.equals(repository.getCacheDirectory().toAbsolutePath().normalize());
    }

    /// One automatically maintained metadata file per full manifest URL, including its query.
    public Path state(URI manifest) {
        return directory.resolve("galleries").resolve(RemoteContent.hash(manifest.toString())).resolve("state.json");
    }

    /// Returns only a fully verified shared image; absence or corruption is repaired by the synchronizer.
    public @Nullable Path file(RemoteContent.Resource resource) throws IOException {
        if (!current())
            throw new IOException("Gallery cache location changed");
        return repository.checkExistentFile(null, CacheRepository.SHA256, resource.sha256()).orElse(null);
    }

    /// Publishes bytes through the common repository after checking the manifest's expected SHA-256 digest.
    public void install(RemoteContent.Resource resource, byte @Unmodifiable [] bytes) throws IOException {
        if (bytes.length > 16_777_216 || !RemoteContent.digest(bytes).equals(resource.sha256()))
            throw new IOException("Gallery SHA-256 mismatch: " + resource.path());
        if (!current())
            throw new IOException("Gallery cache location changed");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "gallery-", ".tmp");
        try {
            Files.write(temporary, bytes);
            repository.cacheFile(temporary, CacheRepository.SHA256, resource.sha256());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
