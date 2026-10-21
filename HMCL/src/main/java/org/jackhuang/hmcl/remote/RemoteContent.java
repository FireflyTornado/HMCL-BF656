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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/// Validated, immutable version-one server content and its display rules.
@NotNullByDefault
public final class RemoteContent {
    /// Prevents construction of the manifest utility.
    private RemoteContent() { }

    /// Independently synchronized resources; filenames are relative to the configured base URL.
    @NotNullByDefault
    public enum Kind {
        /// Mandatory launcher agreement.
        AGREEMENT("agreement.json", "agreement"),
        /// Acknowledged startup messages.
        NOTICES("notices.json", "notices"),
        /// Dismissible home-page cards.
        ANNOUNCEMENTS("announcements.json", "announcements");

        /// Resource filename.
        public final String filename;
        /// Response member holding the content.
        final String member;

        /// Creates a resource descriptor.
        Kind(String filename, String member) {
            this.filename = filename;
            this.member = member;
        }
    }

    /// One published item; revisions are positive integers independent of launcher versions.
    @NotNullByDefault
    public record Item(String id, int revision, String title, String content, String publishedAt,
                       @Nullable Instant startsAt, @Nullable Instant expiresAt, int priority,
                       boolean pinned, String repeat) {
        /// Includes content in identity so accidental same-revision edits also require acknowledgement.
        public String key() {
            return hash(id + "\n" + revision + "\n" + title + "\n" + content);
        }

        /// Whether the item is within its publication window.
        public boolean active(Instant now) {
            return (startsAt == null || !now.isBefore(startsAt))
                    && (expiresAt == null || now.isBefore(expiresAt));
        }
    }

    /// Parses a complete resource; malformed items fail that resource without affecting other kinds.
    public static @Unmodifiable List<Item> parse(Kind kind, String text) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (integer(root, "schemaVersion", -1) != 1)
                throw new IllegalArgumentException("Unsupported schemaVersion");
            List<JsonObject> objects = new ArrayList<>();
            if (kind == Kind.AGREEMENT) {
                objects.add(root.getAsJsonObject(kind.member));
            } else {
                for (JsonElement element : root.getAsJsonArray(kind.member)) {
                    if (objects.size() >= 100)
                        throw new IllegalArgumentException("Too many items");
                    objects.add(element.getAsJsonObject());
                }
            }
            List<Item> items = new ArrayList<>();
            var ids = new HashSet<String>();
            for (JsonObject object : objects) {
                String id = required(object, "id", 200);
                int revision = integer(object, "revision", 0);
                if (revision < 1 || !ids.add(id))
                    throw new IllegalArgumentException("Invalid revision or duplicate id");
                String content = required(object, "content", 200_000);
                ContentLinks.parse(content);
                @Nullable Instant starts = timestamp(object, "startsAt");
                @Nullable Instant expires = timestamp(object, "expiresAt");
                if (starts != null && expires != null && !expires.isAfter(starts))
                    throw new IllegalArgumentException("expiresAt must follow startsAt");
                String repeat = optional(object, "repeat", "once");
                if (!repeat.equals("once") && !repeat.equals("each_start"))
                    throw new IllegalArgumentException("Unsupported repeat policy");
                String published = optional(object, "publishedAt", "");
                if (!published.isEmpty())
                    OffsetDateTime.parse(published);
                if (kind == Kind.AGREEMENT && (starts != null || expires != null))
                    throw new IllegalArgumentException("Agreements cannot have a publication window");
                items.add(new Item(id, revision, required(object, "title", 500), content, published,
                        starts, expires, integer(object, "priority", 0),
                        object.has("pinned") && object.get("pinned").getAsBoolean(), repeat));
            }
            // List.sort is stable, preserving server order when priorities match.
            items.sort(Comparator.comparing(Item::pinned).reversed()
                    .thenComparing(Comparator.comparingInt(Item::priority).reversed()));
            return List.copyOf(items);
        } catch (RuntimeException e) {
            throw new IOException("Invalid " + kind.filename + ": " + e.getMessage(), e);
        }
    }

    /// Reads a bounded nonempty string.
    private static String required(JsonObject object, String name, int limit) {
        String value = optional(object, name, "");
        if (value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException("Missing or oversized " + name);
        return value;
    }

    /// Reads an optional string, rejecting implicit number/boolean conversion.
    private static String optional(JsonObject object, String name, String fallback) {
        if (!object.has(name))
            return fallback;
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected string: " + name);
        return value.getAsString();
    }

    /// Reads an exact integer rather than silently truncating decimals.
    private static int integer(JsonObject object, String name, int fallback) {
        if (!object.has(name))
            return fallback;
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected integer: " + name);
        return value.getAsBigDecimal().intValueExact();
    }

    /// Reads an optional timestamp with an explicit UTC offset.
    private static @Nullable Instant timestamp(JsonObject object, String name) {
        String value = optional(object, name, "");
        return value.isEmpty() ? null : OffsetDateTime.parse(value).toInstant();
    }

    /// Produces a stable UTF-8 SHA-256 identifier for source and content state.
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
