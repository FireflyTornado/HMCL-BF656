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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Launcher-wide public settings; independent of the instance content schema and launch consent.
@NotNullByDefault
public final class OverallContent {
    /// Prevents construction.
    private OverallContent() { }

    /// Managed wallpaper paths and fresh image-response URLs, including random-image APIs.
    @NotNullByDefault
    public record Background(boolean enabled, @Unmodifiable List<String> images, @Unmodifiable List<String> urls) {
        /// An absent, disabled or empty pool delegates to the existing local background settings.
        public boolean isEmpty() {
            return !enabled || images.isEmpty() && urls.isEmpty();
        }
    }

    /// Validated overall manifest; future global settings can coexist with the background member.
    @NotNullByDefault
    public record Manifest(Background background, @Unmodifiable Map<String, RemoteContent.Resource> files) {
        /// Files owned by the currently enabled wallpaper pool.
        public @Unmodifiable Set<RemoteContent.Resource> needed() {
            if (background.isEmpty())
                return Set.of();
            var result = new java.util.HashSet<RemoteContent.Resource>();
            for (String path : background.images())
                result.add(files.get(path));
            return Set.copyOf(result);
        }
    }

    /// Parses a bounded manifest without relying on an instance key, agreement or selected game.
    public static Manifest parse(String text) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            @Nullable JsonElement version = root.get("schemaVersion");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                    || version.getAsBigDecimal().intValueExact() != 1)
                throw new IllegalArgumentException("Overall schemaVersion must be 1");
            Background background = background(root);
            Map<String, RemoteContent.Resource> files = new LinkedHashMap<>();
            if (root.has("files")) {
                for (var entry : root.getAsJsonObject("files").entrySet()) {
                    String path = RemoteContent.logicalPath(entry.getKey());
                    JsonObject descriptor = entry.getValue().getAsJsonObject();
                    String digest = RemoteContent.string(descriptor, "sha256", "").toLowerCase(java.util.Locale.ROOT);
                    if (!digest.matches("[0-9a-f]{64}") || files.size() >= 100)
                        throw new IllegalArgumentException("Invalid overall file digest or too many files");
                    String url = RemoteContent.string(descriptor, "url", "");
                    if (!url.isEmpty())
                        RemoteContent.validateHttp(URI.create(url));
                    files.put(path, new RemoteContent.Resource(path, digest, url));
                }
            }
            for (String path : background.images()) {
                if (!files.containsKey(path))
                    throw new IllegalArgumentException("Background missing from file index: " + path);
            }
            return new Manifest(background, Map.copyOf(files));
        } catch (RuntimeException e) {
            throw new IOException("Invalid overall manifest: " + e.getMessage(), e);
        }
    }

    /// Reads the editable pool before the publisher has generated its file index.
    static Background background(JsonObject root) {
        if (!root.has("background"))
            return new Background(false, List.of(), List.of());
        JsonObject json = root.getAsJsonObject("background");
        List<String> images = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (String member : List.of("images", "urls")) {
            if (!json.has(member))
                continue;
            for (JsonElement value : json.getAsJsonArray(member)) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || images.size() + urls.size() >= 100)
                    throw new IllegalArgumentException("Expected at most 100 background paths/URLs");
                String name = value.getAsString();
                if (member.equals("images")) {
                    images.add(RemoteContent.logicalPath(name));
                } else {
                    if (name.length() > 4096)
                        throw new IllegalArgumentException("Background URL is too long");
                    RemoteContent.validateHttp(URI.create(name));
                    urls.add(name);
                }
            }
        }
        return new Background(RemoteContent.bool(json, "enabled", true), List.copyOf(images), List.copyOf(urls));
    }

    /// Publishes complete bytes atomically, rejecting destination parents linked outside the designated directory.
    static void write(Path root, Path destination, byte @Unmodifiable [] bytes) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path path = destination.toAbsolutePath().normalize();
        if (!path.startsWith(base) || path.equals(base))
            throw new IOException("Overall output escapes its directory");
        Files.createDirectories(base);
        Path ancestor = path.getParent();
        while (!Files.exists(ancestor))
            ancestor = java.util.Objects.requireNonNull(ancestor.getParent());
        if (!ancestor.toRealPath().startsWith(base.toRealPath()))
            throw new IOException("Linked overall output escapes its directory");
        Files.createDirectories(path.getParent());
        if (!path.getParent().toRealPath().startsWith(base.toRealPath()))
            throw new IOException("Linked overall output escapes its directory");
        Path temporary = Files.createTempFile(path.getParent(), "overall-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
