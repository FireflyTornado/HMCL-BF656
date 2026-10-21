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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/// Atomically persists cache and acknowledgements, isolated by installation and server URL.
@NotNullByDefault
public final class ContentStore {
    /// Directory containing state for exactly one content server.
    private final Path directory;

    /// Creates a source-specific store under the installation's `.hmcl` directory.
    public ContentStore(Path launcherDirectory, String source) {
        directory = launcherDirectory.resolve(".hmcl/remote-content").resolve(RemoteContent.hash(source));
    }

    /// A previously validated response and its HTTP validator.
    @NotNullByDefault
    public record Cache(String body, String etag, Instant fetchedAt) { }

    /// Loads a cache only if its envelope and contained manifest remain valid.
    public synchronized @Nullable Cache cache(RemoteContent.Kind kind) {
        try {
            Path path = directory.resolve(kind.filename);
            if (!Files.isRegularFile(path))
                return null;
            JsonObject json = read(path);
            String body = json.get("body").getAsString();
            RemoteContent.parse(kind, body);
            return new Cache(body, json.get("etag").getAsString(), Instant.parse(json.get("fetchedAt").getAsString()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Saves a validated response before it can be reused on another startup.
    public synchronized void cache(RemoteContent.Kind kind, Cache cache) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("body", cache.body());
        json.addProperty("etag", cache.etag());
        json.addProperty("fetchedAt", cache.fetchedAt().toString());
        write(directory.resolve(kind.filename), json);
    }

    /// Whether this exact agreement body and revision were accepted in this installation.
    public synchronized boolean accepted(RemoteContent.Item item) {
        try {
            JsonObject state = state();
            return state.has("agreementKey") && item.key().equals(state.get("agreementKey").getAsString());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /// Records acceptance durably; callers must not unlock the launcher if this fails.
    public synchronized void accept(RemoteContent.Item item) throws IOException {
        JsonObject state = state();
        state.addProperty("agreementKey", item.key());
        state.addProperty("agreementId", item.id());
        state.addProperty("agreementRevision", item.revision());
        state.addProperty("acceptedAt", Instant.now().toString());
        write(directory.resolve("state.json"), state);
    }

    /// Whether an item was previously acknowledged or dismissed.
    public synchronized boolean remembered(String category, String key) {
        try {
            JsonObject state = state();
            if (state.has(category)) {
                for (var item : state.getAsJsonArray(category)) {
                    if (key.equals(item.getAsString()))
                        return true;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Missing or damaged preferences never hide new content.
        }
        return false;
    }

    /// Saves bounded acknowledgement history without dropping the agreement receipt.
    public synchronized void remember(String category, String key) throws IOException {
        JsonObject state = state();
        JsonArray previous = state.has(category) ? state.getAsJsonArray(category) : new JsonArray();
        JsonArray next = new JsonArray();
        for (int i = Math.max(0, previous.size() - 499); i < previous.size(); i++) {
            if (!key.equals(previous.get(i).getAsString()))
                next.add(previous.get(i));
        }
        next.add(key);
        state.add(category, next);
        write(directory.resolve("state.json"), state);
    }

    /// Loads state without treating corrupt state as consent.
    private JsonObject state() throws IOException {
        Path path = directory.resolve("state.json");
        return Files.exists(path) ? read(path) : new JsonObject();
    }

    /// Reads a size-bounded UTF-8 JSON object.
    private static JsonObject read(Path path) throws IOException {
        if (Files.size(path) > 8_000_000)
            throw new IOException("Content state is too large");
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid content state: " + path, e);
        }
    }

    /// Replaces a JSON file atomically where supported, keeping the previous file on write failure.
    private static void write(Path path, JsonObject json) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "content-", ".tmp");
        try {
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
