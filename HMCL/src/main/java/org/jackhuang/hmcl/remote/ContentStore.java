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
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/// Instance-local receipts and content-addressed downloads, with ownership-bounded garbage collection.
@NotNullByDefault
public final class ContentStore {
    /// This instance's `.launcher` directory.
    private final Path root;
    /// Source-specific receipt and HTTP state directory beneath `client`.
    private final Path stateDirectory;
    /// Public downloaded content; cleanup never crosses into `client`.
    private final Path server;

    /// Creates storage for one instance and one server content source.
    public ContentStore(Path launcherDirectory, String source) {
        root = launcherDirectory.toAbsolutePath().normalize();
        server = root.resolve("server");
        stateDirectory = root.resolve("client/state").resolve(RemoteContent.hash(source));
    }

    /// Persisted manifest response and HTTP validator, retained even while some payloads are unavailable.
    @NotNullByDefault
    public record Cache(String body, String etag, Instant checkedAt) { }

    /// Loads a source-bound valid manifest; corrupt HTTP state is not treated as usable content.
    public synchronized @Nullable Cache cache(String instanceKey) {
        try {
            Path path = stateDirectory.resolve("sync.json");
            if (!Files.isRegularFile(path))
                return null;
            JsonObject json = read(path);
            String body = json.get("body").getAsString();
            RemoteContent.parse(body, instanceKey);
            return new Cache(body, json.get("etag").getAsString(), Instant.parse(json.get("checkedAt").getAsString()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Saves validated metadata before payload downloads, so a known new agreement cannot revert to old consent.
    public synchronized void cache(Cache cache) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("body", cache.body());
        json.addProperty("etag", cache.etag());
        json.addProperty("checkedAt", cache.checkedAt().toString());
        write(stateDirectory.resolve("sync.json"), json.toString().getBytes(StandardCharsets.UTF_8));
        ensureServer();
        write(server.resolve("manifest.json"), cache.body().getBytes(StandardCharsets.UTF_8));
    }

    /// Returns the content-addressed location without using an untrusted server path as a filesystem path.
    public Path file(RemoteContent.Resource resource) {
        return server.resolve("files").resolve(resource.cacheName());
    }

    /// Lightweight presentation check; full SHA-256 verification happens off the FX thread during synchronization.
    public synchronized boolean ready(RemoteContent.Manifest manifest, RemoteContent.Item item) {
        return item.files().stream().allMatch(path -> Files.isRegularFile(file(manifest.files().get(path))));
    }

    /// Verifies cached bytes before deciding that a network download is unnecessary.
    public synchronized boolean verified(RemoteContent.Resource resource) throws IOException {
        Path path = file(resource);
        if (!Files.isRegularFile(path))
            return false;
        checkOwnedPath(path);
        return Files.size(path) <= 16_777_216 && RemoteContent.digest(Files.readAllBytes(path)).equals(resource.sha256());
    }

    /// Installs hash-verified bytes atomically and records ownership before creating the managed file.
    public synchronized void install(RemoteContent.Resource resource, byte[] bytes) throws IOException {
        if (!RemoteContent.digest(bytes).equals(resource.sha256()))
            throw new IOException("SHA-256 mismatch: " + resource.path());
        ensureServer();
        Path path = file(resource);
        Files.createDirectories(path.getParent());
        checkOwnedPath(path);
        Set<String> owned = owned();
        owned.add(resource.cacheName());
        saveOwned(owned);
        write(path, bytes);
    }

    /// Deletes only previously managed objects no longer needed by active/future content or a pending replacement.
    public synchronized void prune(@Unmodifiable Set<RemoteContent.Resource> keep) throws IOException {
        Set<String> names = new HashSet<>();
        for (var resource : keep)
            names.add(resource.cacheName());
        Set<String> owned = owned();
        for (String name : Set.copyOf(owned)) {
            if (!names.contains(name)) {
                if (!name.matches("[0-9a-f]{64}\\.[a-z0-9]{1,12}"))
                    throw new IOException("Invalid managed object name");
                Path path = server.resolve("files").resolve(name);
                checkOwnedPath(path);
                Files.deleteIfExists(path);
                owned.remove(name);
            }
        }
        if (!owned.isEmpty() || Files.exists(root.resolve("client/managed-files.json")))
            saveOwned(owned);
    }

    /// Removes expired inline bodies and unused file descriptors while retaining the HTTP validator for revalidation.
    public synchronized Cache compact(Cache cache, String instanceKey, Instant now) throws IOException {
        RemoteContent.Manifest manifest = RemoteContent.parse(cache.body(), instanceKey);
        JsonObject json = JsonParser.parseString(cache.body()).getAsJsonObject();
        boolean changed = false;
        for (String category : java.util.List.of("notices", "announcements")) {
            JsonArray array = json.getAsJsonArray(category);
            for (int i = array.size() - 1; i >= 0; i--) {
                JsonObject item = array.get(i).getAsJsonObject();
                if (item.has("expiresAt") && !item.get("expiresAt").getAsString().isEmpty()
                        && !now.isBefore(java.time.OffsetDateTime.parse(item.get("expiresAt").getAsString()).toInstant())) {
                    array.remove(i);
                    changed = true;
                }
            }
        }
        Set<String> needed = new HashSet<>();
        for (RemoteContent.Resource resource : manifest.needed(now))
            needed.add(resource.path());
        var iterator = json.getAsJsonObject("files").entrySet().iterator();
        while (iterator.hasNext()) {
            if (!needed.contains(iterator.next().getKey())) {
                iterator.remove();
                changed = true;
            }
        }
        if (!changed)
            return cache;
        Cache compacted = new Cache(json.toString(), cache.etag(), cache.checkedAt());
        cache(compacted);
        return compacted;
    }

    /// Whether this exact agreement, including its indexed document/image digests, was accepted.
    public synchronized boolean accepted(RemoteContent.Manifest manifest) {
        try {
            JsonObject state = receipts();
            return state.has("agreementKey") && manifest.key(manifest.agreement()).equals(state.get("agreementKey").getAsString());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /// Durably records consent before any game launch continuation is allowed to run.
    public synchronized void accept(RemoteContent.Manifest manifest) throws IOException {
        JsonObject state = receipts();
        state.addProperty("agreementKey", manifest.key(manifest.agreement()));
        state.addProperty("agreementId", manifest.agreement().id());
        state.addProperty("agreementRevision", manifest.agreement().revision());
        state.addProperty("acceptedAt", Instant.now().toString());
        write(stateDirectory.resolve("receipts.json"), state.toString().getBytes(StandardCharsets.UTF_8));
    }

    /// Whether a content revision was previously acknowledged or dismissed in this instance/source.
    public synchronized boolean remembered(String category, String key) {
        try {
            JsonObject state = receipts();
            if (state.has(category)) {
                for (var item : state.getAsJsonArray(category)) {
                    if (key.equals(item.getAsString()))
                        return true;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Damaged preferences never hide unacknowledged content.
        }
        return false;
    }

    /// Saves bounded receipt history without deleting the current agreement receipt during payload cleanup.
    public synchronized void remember(String category, String key) throws IOException {
        JsonObject state = receipts();
        JsonArray previous = state.has(category) ? state.getAsJsonArray(category) : new JsonArray();
        JsonArray next = new JsonArray();
        for (int i = Math.max(0, previous.size() - 499); i < previous.size(); i++) {
            if (!key.equals(previous.get(i).getAsString()))
                next.add(previous.get(i));
        }
        next.add(key);
        state.add(category, next);
        write(stateDirectory.resolve("receipts.json"), state.toString().getBytes(StandardCharsets.UTF_8));
    }

    /// Reads UTF-8 Markdown after its file has passed synchronization checks.
    public String body(RemoteContent.Manifest manifest, RemoteContent.Item item) throws IOException {
        if (item.contentFile().isEmpty())
            return item.content();
        Path path = file(manifest.files().get(item.contentFile()));
        if (!Files.isRegularFile(path) || Files.size(path) > 2_097_152)
            throw new IOException("Markdown body is missing or too large: " + item.contentFile());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /// Reads source-specific receipts without converting damaged state into consent.
    private JsonObject receipts() throws IOException {
        Path path = stateDirectory.resolve("receipts.json");
        return Files.exists(path) ? read(path) : new JsonObject();
    }

    /// Reads the installation's managed-object inventory, shared across source changes for safe cleanup.
    private Set<String> owned() throws IOException {
        Set<String> result = new HashSet<>();
        Path path = root.resolve("client/managed-files.json");
        if (Files.exists(path)) {
            for (var name : read(path).getAsJsonArray("files"))
                result.add(name.getAsString());
        }
        return result;
    }

    /// Atomically persists the exact set of remotely managed objects.
    private void saveOwned(Set<String> owned) throws IOException {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        owned.stream().sorted().forEach(array::add);
        json.add("files", array);
        write(root.resolve("client/managed-files.json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /// Creates the public cache and rejects filesystem links escaping this instance's launcher directory.
    private void ensureServer() throws IOException {
        Files.createDirectories(server);
        if (!server.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("Public cache escapes the instance launcher directory");
    }

    /// Verifies both lexical containment and existing filesystem ancestors before any write or deletion.
    private void checkOwnedPath(Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(server.resolve("files")))
            throw new IOException("Cache operation outside managed files");
        if (!Files.exists(server))
            return;
        Path realRoot = root.toRealPath();
        @Nullable Path current = path;
        while (current != null && !Files.exists(current))
            current = current.getParent();
        if (current == null || !current.toRealPath().startsWith(realRoot.resolve("server")))
            throw new IOException("Linked cache path escapes its managed directory");
    }

    /// Reads a bounded JSON object; metadata may contain an escaped copy of a two-megabyte manifest.
    private static JsonObject read(Path path) throws IOException {
        if (Files.size(path) > 12_582_912)
            throw new IOException("Content state is too large");
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid content state: " + path, e);
        }
    }

    /// Writes in the destination directory and replaces atomically where the filesystem supports it.
    private static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "content-", ".tmp");
        try {
            Files.write(temporary, bytes);
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
