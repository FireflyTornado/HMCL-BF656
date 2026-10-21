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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Immutable instance-specific version-two manifests, content identities and resource ownership.
@NotNullByDefault
public final class RemoteContent {
    /// Prevents construction of the manifest utility.
    private RemoteContent() { }

    /// Types of instance content sharing one manifest and synchronization cycle.
    @NotNullByDefault
    public enum Kind {
        /// Agreement checked immediately before launching this instance.
        AGREEMENT,
        /// Notices shown when selecting this instance.
        NOTICES,
        /// Markdown cards displayed for the selected instance.
        ANNOUNCEMENTS
    }

    /// One remotely managed file; optional URL allows an explicitly indexed CDN resource.
    @NotNullByDefault
    public record Resource(String path, String sha256, String url) {
        /// Stable content-addressed filename retains the extension needed by document viewers.
        public String cacheName() {
            String name = path.substring(path.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "bin" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            return sha256 + "." + (extension.matches("[a-z0-9]{1,12}") ? extension : "bin");
        }
    }

    /// Published item metadata; Markdown bodies are stored separately from the manifest.
    @NotNullByDefault
    public record Item(String id, int revision, String title, String content, String contentFile,
                       @Unmodifiable List<String> resources, String publishedAt,
                       @Nullable Instant startsAt, @Nullable Instant expiresAt,
                       int priority, boolean pinned, String repeat) {
        /// Whether the item should currently be presented.
        public boolean active(Instant now) {
            return (startsAt == null || !now.isBefore(startsAt)) && !expired(now);
        }

        /// Future scheduled content is retained; only content past its expiry is disposable.
        public boolean expired(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }

        /// Every file owned by this item, including its Markdown body.
        public @Unmodifiable Set<String> files() {
            Set<String> result = new HashSet<>(resources);
            if (!contentFile.isEmpty())
                result.add(contentFile);
            return Set.copyOf(result);
        }
    }

    /// A complete instance catalog; one conditional request checks all three content types.
    @NotNullByDefault
    public record Manifest(String instanceKey, Item agreement, @Unmodifiable List<Item> notices,
                           @Unmodifiable List<Item> announcements, @Unmodifiable Map<String, Resource> files) {
        /// Returns an immutable list for one content type.
        public @Unmodifiable List<Item> items(Kind kind) {
            return switch (kind) {
                case AGREEMENT -> List.of(agreement);
                case NOTICES -> notices;
                case ANNOUNCEMENTS -> announcements;
            };
        }

        /// All current and future files, excluding expired notices and announcements.
        public @Unmodifiable Set<Resource> needed(Instant now) {
            Set<Resource> needed = new HashSet<>();
            for (Kind kind : Kind.values()) {
                for (Item item : items(kind)) {
                    if (!item.expired(now)) {
                        for (String path : item.files())
                            needed.add(files.get(path));
                    }
                }
            }
            return Set.copyOf(needed);
        }

        /// Receipt identity changes when the body or any associated file changes, even with an unchanged revision.
        public String key(Item item) {
            StringBuilder identity = new StringBuilder(item.id()).append('\n').append(item.revision())
                    .append('\n').append(item.title()).append('\n').append(item.content());
            item.files().stream().sorted().forEach(path -> identity.append('\n').append(path)
                    .append(':').append(files.get(path).sha256()));
            return hash(identity.toString());
        }
    }

    /// Parses and validates an instance-bound manifest before it can replace known-good metadata.
    public static Manifest parse(String text, String expectedInstanceKey) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (integer(root, "schemaVersion", 0) != 2)
                throw new IllegalArgumentException("schemaVersion must be 2");
            String key = required(root, "instanceKey", 64);
            if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || !key.equals(expectedInstanceKey))
                throw new IllegalArgumentException("Manifest instanceKey does not match client configuration");
            Map<String, Resource> files = new LinkedHashMap<>();
            for (var entry : root.getAsJsonObject("files").entrySet()) {
                String path = logicalPath(entry.getKey());
                JsonObject descriptor = entry.getValue().getAsJsonObject();
                String digest = required(descriptor, "sha256", 64).toLowerCase(java.util.Locale.ROOT);
                if (!digest.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid SHA-256: " + path);
                String url = string(descriptor, "url", "");
                if (!url.isEmpty())
                    validateHttp(URI.create(url));
                if (files.size() >= 500)
                    throw new IllegalArgumentException("Too many managed files");
                files.put(path, new Resource(path, digest, url));
            }
            Item agreement = item(root.getAsJsonObject("agreement"), true);
            @Unmodifiable List<Item> notices = items(root, "notices");
            @Unmodifiable List<Item> announcements = items(root, "announcements");
            Manifest manifest = new Manifest(key, agreement, notices, announcements, Map.copyOf(files));
            for (Kind kind : Kind.values()) {
                for (Item item : manifest.items(kind)) {
                    for (String path : item.files()) {
                        if (!files.containsKey(path))
                            throw new IllegalArgumentException("File missing from index: " + path);
                    }
                }
            }
            return manifest;
        } catch (RuntimeException e) {
            throw new IOException("Invalid manifest.json: " + e.getMessage(), e);
        }
    }

    /// Parses a bounded list and rejects duplicate IDs within the content type.
    private static @Unmodifiable List<Item> items(JsonObject root, String name) throws IOException {
        List<Item> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray(name)) {
            Item item = item(element.getAsJsonObject(), false);
            if (result.size() >= 100 || !ids.add(item.id()))
                throw new IllegalArgumentException("Duplicate ID or too many items: " + name);
            result.add(item);
        }
        result.sort(Comparator.comparing(Item::pinned).reversed()
                .thenComparing(Comparator.comparingInt(Item::priority).reversed()));
        return List.copyOf(result);
    }

    /// Reads one item with either inline rich text or a UTF-8 Markdown resource.
    private static Item item(JsonObject json, boolean agreement) throws IOException {
        int revision = integer(json, "revision", 0);
        if (revision < 1)
            throw new IllegalArgumentException("revision must be a positive integer");
        String content = string(json, "content", "");
        String file = string(json, "contentFile", "");
        if (content.isBlank() == file.isBlank() || content.length() > 200_000)
            throw new IllegalArgumentException("Specify exactly one of content and contentFile");
        if (!content.isEmpty())
            ContentLinks.parse(content);
        if (!file.isEmpty()) {
            logicalPath(file);
            if (!file.toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
                throw new IllegalArgumentException("contentFile must be a Markdown file");
        }
        List<String> resources = new ArrayList<>();
        if (json.has("resources")) {
            for (JsonElement resource : json.getAsJsonArray("resources"))
                resources.add(logicalPath(resource.getAsString()));
        }
        @Nullable Instant starts = timestamp(json, "startsAt");
        @Nullable Instant expires = timestamp(json, "expiresAt");
        if (agreement && (starts != null || expires != null))
            throw new IllegalArgumentException("Agreements cannot expire or be scheduled");
        if (starts != null && expires != null && !expires.isAfter(starts))
            throw new IllegalArgumentException("expiresAt must follow startsAt");
        String published = string(json, "publishedAt", "");
        if (!published.isEmpty())
            OffsetDateTime.parse(published);
        String repeat = string(json, "repeat", "once");
        if (!repeat.equals("once") && !repeat.equals("each_start"))
            throw new IllegalArgumentException("Unsupported repeat policy");
        return new Item(required(json, "id", 200), revision, required(json, "title", 500), content, file,
                List.copyOf(resources), published, starts, expires, integer(json, "priority", 0),
                bool(json, "pinned", false), repeat);
    }

    /// Accepts only unambiguous portable paths within the instance's public content namespace.
    public static String logicalPath(String path) {
        if (path.isBlank() || path.length() > 500 || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.contains("%") || path.contains("?") || path.contains("#")
                || path.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("Invalid relative resource path: " + path);
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals(".."))
                throw new IllegalArgumentException("Resource path escapes its directory: " + path);
        }
        return path;
    }

    /// Requires HTTPS except for a loopback development endpoint.
    public static void validateHttp(URI uri) {
        boolean loopback = "http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]")
                .contains(uri.getHost() == null ? "" : uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !loopback) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("HTTPS required (loopback HTTP is allowed for tests)");
    }

    /// Reads a strictly typed optional string.
    static String string(JsonObject json, String name, String fallback) {
        if (!json.has(name))
            return fallback;
        JsonElement value = json.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected string: " + name);
        return value.getAsString();
    }

    /// Reads a nonempty string with a bounded length.
    private static String required(JsonObject json, String name, int limit) {
        String value = string(json, name, "");
        if (value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException("Missing or oversized " + name);
        return value;
    }

    /// Reads a boolean without accepting string coercion.
    static boolean bool(JsonObject json, String name, boolean fallback) {
        if (!json.has(name))
            return fallback;
        JsonElement value = json.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Expected boolean: " + name);
        return value.getAsBoolean();
    }

    /// Reads an exact numeric integer.
    private static int integer(JsonObject json, String name, int fallback) {
        if (!json.has(name))
            return fallback;
        JsonElement value = json.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected integer: " + name);
        return value.getAsBigDecimal().intValueExact();
    }

    /// Reads a timestamp carrying an explicit UTC offset.
    private static @Nullable Instant timestamp(JsonObject json, String name) {
        String value = string(json, name, "");
        return value.isEmpty() ? null : OffsetDateTime.parse(value).toInstant();
    }

    /// Computes an ASCII SHA-256 digest for a UTF-8 identifier.
    public static String hash(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    /// Computes a digest for downloaded bytes and local file verification.
    public static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
