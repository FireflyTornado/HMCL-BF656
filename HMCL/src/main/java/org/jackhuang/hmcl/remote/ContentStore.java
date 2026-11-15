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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/// Instance-local receipts and content-addressed downloads, with ownership-bounded garbage collection.
@NotNullByDefault
public final class ContentStore {
    /// Bounds local metadata reads and writes, including the escaped current manifest.
    private static final int MAX_STATE_BYTES = 12_582_912;
    /// Serializes local writers before acquiring the per-source file lock shared by launcher processes.
    private static final Object CACHE_WRITE_LOCK = new Object();
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

    /// Current server response and the displayable announcement revisions retained during its download.
    @NotNullByDefault
    public record Cache(String body, String etag, Instant checkedAt,
                        @Unmodifiable Map<String, RemoteContent.Announcement> announcementFallbacks,
                        boolean fallbackIndexNeedsRewrite) {
        /// Makes a snapshot safe to publish to the JavaFX thread.
        public Cache {
            announcementFallbacks = Map.copyOf(announcementFallbacks);
        }

        /// Creates a cache response before selecting any announcement fallbacks.
        public Cache(String body, String etag, Instant checkedAt) {
            this(body, etag, checkedAt, Map.of());
        }

        /// Creates a snapshot with the fallback revisions selected for the current manifest.
        public Cache(String body, String etag, Instant checkedAt,
                     @Unmodifiable Map<String, RemoteContent.Announcement> announcementFallbacks) {
            this(body, etag, checkedAt, announcementFallbacks, false);
        }

        /// Replaces the fallback set without altering the current manifest or validator.
        Cache withAnnouncementFallbacks(@Unmodifiable Map<String, RemoteContent.Announcement> fallbacks) {
            return new Cache(body, etag, checkedAt, fallbacks);
        }
    }

    /// Loads a source-bound valid manifest; corrupt HTTP state is not treated as usable content.
    public synchronized @Nullable Cache cache(String instanceKey) {
        try {
            Path path = stateDirectory.resolve("sync.json");
            if (!Files.isRegularFile(path))
                return null;
            JsonObject json = read(path);
            String body = json.get("body").getAsString();
            RemoteContent.Manifest manifest = RemoteContent.parse(body, instanceKey);
            @Nullable JsonElement index = json.get("announcementFallbacks");
            var fallbacks = readAnnouncementFallbacks(index, manifest);
            boolean rewrite = index != null && (!index.isJsonObject() || index.getAsJsonObject().size() != fallbacks.size());
            return new Cache(body, json.get("etag").getAsString(), Instant.parse(json.get("checkedAt").getAsString()),
                    fallbacks, rewrite);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Loads only ready fallbacks for items that still appear in the latest manifest and need files.
    private @Unmodifiable Map<String, RemoteContent.Announcement> readAnnouncementFallbacks(
            @Nullable JsonElement index, RemoteContent.Manifest manifest) {
        if (index == null || !index.isJsonObject())
            return Map.of();
        JsonObject references = index.getAsJsonObject();
        if (references.size() == 0)
            return Map.of();

        Path directory = fallbackDirectory();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return Map.of();
        try {
            checkFallbackDirectory(directory);
        } catch (IOException ignored) {
            return Map.of();
        }
        Instant now = Instant.now();
        Map<String, RemoteContent.Announcement> fallbacks = new LinkedHashMap<>();
        for (var item : manifest.announcements()) {
            String id = item.id();
            @Nullable JsonElement reference = references.get(id);
            if (reference == null || !reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString())
                continue;
            String digest = reference.getAsString();
            if (!digest.matches("[0-9a-f]{64}") || item.expired(now) || ready(manifest, item))
                continue;
            try {
                Path path = directory.resolve(digest + ".json");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    continue;
                byte[] bytes;
                try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = stream.readNBytes(MAX_STATE_BYTES + 1);
                }
                if (bytes.length > MAX_STATE_BYTES || !RemoteContent.digest(bytes).equals(digest))
                    continue;
                var announcement = RemoteContent.readAnnouncement(
                        JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
                if (announcement.item().id().equals(id) && !announcement.item().expired(now) && ready(announcement))
                    fallbacks.put(id, announcement);
            } catch (IOException | RuntimeException ignored) {
                // A damaged fallback cannot invalidate the latest manifest or its other entries.
            }
        }
        return Map.copyOf(fallbacks);
    }

    /// Saves validated metadata before payload downloads, so a known new agreement cannot revert to old consent.
    public synchronized void cache(Cache cache) throws IOException {
        Files.createDirectories(stateDirectory);
        checkFallbackDirectory(stateDirectory);
        synchronized (CACHE_WRITE_LOCK) {
            try (var channel = FileChannel.open(stateDirectory.resolve("sync.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 @Nullable FileLock lock = channel.tryLock()) {
                if (lock == null)
                    throw new IOException("Another launcher is saving this instance's content cache");
                saveCache(cache);
            }
        }
    }

    /// Installs immutable snapshots and publishes their index before collecting unreferenced files.
    private void saveCache(Cache cache) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("body", cache.body());
        json.addProperty("etag", cache.etag());
        json.addProperty("checkedAt", cache.checkedAt().toString());
        Path directory = fallbackDirectory();
        JsonObject references = new JsonObject();
        Set<String> keep = new HashSet<>();
        if (!cache.announcementFallbacks().isEmpty()) {
            Files.createDirectories(directory);
            checkFallbackDirectory(directory);
            for (var entry : cache.announcementFallbacks().entrySet()) {
                byte[] snapshot = RemoteContent.writeAnnouncement(entry.getValue()).toString().getBytes(StandardCharsets.UTF_8);
                String digest = RemoteContent.digest(snapshot);
                String filename = digest + ".json";
                Path file = directory.resolve(filename);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != snapshot.length
                        || !Arrays.equals(Files.readAllBytes(file), snapshot))
                    write(file, snapshot);
                references.addProperty(entry.getKey(), digest);
                keep.add(filename);
            }
        }
        json.add("announcementFallbacks", references);
        write(stateDirectory.resolve("sync.json"), json.toString().getBytes(StandardCharsets.UTF_8));
        pruneFallbackSnapshots(directory, keep);
        ensureServer();
        write(server.resolve("manifest.json"), cache.body().getBytes(StandardCharsets.UTF_8));
    }

    /// Resolves the snapshot directory dedicated to this instance and remote source.
    private Path fallbackDirectory() {
        return stateDirectory.resolve("announcement-fallbacks");
    }

    /// Rejects linked directories that resolve outside their exact instance and source path.
    private void checkFallbackDirectory(Path directory) throws IOException {
        if (!directory.toRealPath().equals(root.toRealPath().resolve(root.relativize(directory))))
            throw new IOException("Announcement fallback cache escapes the instance state directory");
    }

    /// Cleans previously published or interrupted snapshots once the replacement index is durable.
    private void pruneFallbackSnapshots(Path directory, Set<String> keep) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return;
        try {
            checkFallbackDirectory(directory);
            try (var files = Files.newDirectoryStream(directory, "*.json")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (name.matches("[0-9a-f]{64}\\.json") && !keep.contains(name)
                            && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot prune announcement fallback snapshots", e);
        }
    }

    /// Returns the content-addressed location without using an untrusted server path as a filesystem path.
    public Path file(RemoteContent.Resource resource) {
        return server.resolve("files").resolve(resource.cacheName());
    }

    /// Lightweight presentation check; full SHA-256 verification happens off the FX thread during synchronization.
    public synchronized boolean ready(RemoteContent.Manifest manifest, RemoteContent.Item item) {
        return ready(item, manifest.files());
    }

    /// Checks an announcement using the index belonging to its own revision.
    synchronized boolean ready(RemoteContent.Announcement announcement) {
        return ready(announcement.item(), announcement.files());
    }

    /// Checks that each file required by the item is present in the local cache.
    private boolean ready(RemoteContent.Item item, @Unmodifiable Map<String, RemoteContent.Resource> files) {
        return item.files().stream().allMatch(path -> Files.isRegularFile(file(files.get(path))));
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
        Cache compacted = new Cache(json.toString(), cache.etag(), cache.checkedAt(), cache.announcementFallbacks());
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
        return body(item, manifest.files());
    }

    /// Reads a document with the resource index that belongs to that revision.
    String body(RemoteContent.Item item, @Unmodifiable Map<String, RemoteContent.Resource> files) throws IOException {
        if (item.contentFile().isEmpty())
            return item.content();
        Path path = file(files.get(item.contentFile()));
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
        if (Files.size(path) > MAX_STATE_BYTES)
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
