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
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/// Instance-local receipts and content-addressed downloads, with ownership-bounded garbage collection.
@NotNullByDefault
public final class ContentStore {
    /// Largest payload accepted for storage or verified reuse.
    static final int MAX_PAYLOAD_BYTES = 16_777_216;
    /// Bounds local metadata reads and writes, including the escaped current manifest.
    private static final int MAX_STATE_BYTES = 12_582_912;
    /// Serializes local writers before taking the instance lock shared across launcher processes.
    private static final Object CACHE_WRITE_LOCK = new Object();
    /// Avoids opening a second channel to a locally locked registration.
    private static final Set<Path> LOCAL_USERS = new HashSet<>();
    /// This instance's `.launcher` directory.
    private final Path root;
    /// Separates managed files and metadata from launchers using the previous cache layout.
    private final Path cacheDirectory;
    /// Source-specific receipt and HTTP state directory for this cache format.
    private final Path stateDirectory;
    /// Previous source-specific state, read only when seeding this cache.
    private final Path legacyStateDirectory;
    /// Downloaded content owned by this cache format.
    private final Path server;

    /// Creates storage for one instance and one server content source.
    public ContentStore(Path launcherDirectory, String source) {
        root = launcherDirectory.toAbsolutePath().normalize();
        cacheDirectory = root.resolve("client/content-cache-v2");
        server = cacheDirectory.resolve("server");
        String sourceKey = RemoteContent.hash(source);
        stateDirectory = cacheDirectory.resolve("state").resolve(sourceKey);
        legacyStateDirectory = root.resolve("client/state").resolve(sourceKey);
    }

    /// Current server response and the displayable announcement revisions retained during its download.
    @NotNullByDefault
    public record Cache(String body, String etag, Instant checkedAt,
                        @Unmodifiable Map<String, RemoteContent.Announcement> announcementFallbacks,
                        boolean fallbackIndexNeedsRewrite) {
        /// Snapshots the fallbacks and requires each key to match the stored announcement ID.
        public Cache {
            announcementFallbacks = Map.copyOf(announcementFallbacks);
            for (var entry : announcementFallbacks.entrySet()) {
                String id = entry.getValue().item().id();
                if (!entry.getKey().equals(id))
                    throw new IllegalArgumentException("Announcement fallback key " + entry.getKey() + " does not match ID " + id);
            }
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
            return loadCache(instanceKey).cache();
        } catch (IOException e) {
            return null;
        }
    }

    /// Associates a loaded HTTP cache with the exact on-disk state a subsequent write may replace.
    @NotNullByDefault
    record CacheState(@Nullable Cache cache, String version) { }

    /// Reads the authoritative state and its fingerprint in one pass.
    synchronized CacheState loadCache(String instanceKey) throws IOException {
        return Objects.requireNonNull(loadChangedCache(instanceKey, null));
    }

    /// Seeds an empty cache from verified legacy data without writing to the legacy directories.
    synchronized void importLegacyCache(String instanceKey, Usage usage) throws IOException {
        Path legacySync = legacyStateDirectory.resolve("sync.json");
        if (!Files.isRegularFile(legacySync, LinkOption.NOFOLLOW_LINKS) || !readSyncState().version().isEmpty())
            return;
        Cache old;
        RemoteContent.Manifest manifest;
        try {
            checkCachePath(legacyStateDirectory);
            JsonObject json = read(legacySync);
            String body = json.get("body").getAsString();
            manifest = RemoteContent.parse(body, instanceKey);
            old = new Cache(body, json.get("etag").getAsString(), Instant.parse(json.get("checkedAt").getAsString()));
        } catch (IOException | RuntimeException e) {
            return;
        }
        Path legacyFiles = root.resolve("server/files");
        if (Files.isDirectory(legacyFiles, LinkOption.NOFOLLOW_LINKS)) {
            checkCachePath(legacyFiles);
            for (RemoteContent.Resource resource : manifest.needed(Instant.now())) {
                Path file = legacyFiles.resolve(resource.cacheName());
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    continue;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    byte @Unmodifiable [] bytes = input.readNBytes(MAX_PAYLOAD_BYTES + 1);
                    if (bytes.length <= MAX_PAYLOAD_BYTES && RemoteContent.digest(bytes).equals(resource.sha256()))
                        install(resource, bytes, usage);
                } catch (CacheBusyException e) {
                    throw e;
                } catch (IOException ignored) {
                    // The legacy launcher may have reclaimed this file; the next sync can download it.
                }
            }
        }
        try {
            publishCache(old, "", usage);
        } catch (CacheChangedException ignored) {
            // A concurrent launcher initialized the new cache first.
        }
    }

    /// Reloads metadata and sidecars only when the caller's disk fingerprint changed.
    synchronized @Nullable CacheState loadChangedCache(String instanceKey, @Nullable String knownVersion) throws IOException {
        DiskState disk = readSyncState();
        if (disk.version().equals(knownVersion))
            return null;
        if (disk.version().isEmpty())
            return new CacheState(null, disk.version());
        try {
            JsonObject json = JsonParser.parseString(disk.text()).getAsJsonObject();
            String body = json.get("body").getAsString();
            RemoteContent.Manifest manifest = RemoteContent.parse(body, instanceKey);
            @Nullable JsonElement index = json.get("announcementFallbacks");
            @Unmodifiable Map<String, RemoteContent.Announcement> fallbacks = readAnnouncementFallbacks(index, manifest);
            boolean rewrite = index != null && (!index.isJsonObject() || index.getAsJsonObject().size() != fallbacks.size());
            Cache cache = new Cache(body, json.get("etag").getAsString(),
                    Instant.parse(json.get("checkedAt").getAsString()), fallbacks, rewrite);
            return new CacheState(cache, disk.version());
        } catch (IOException | RuntimeException e) {
            return new CacheState(null, disk.version());
        }
    }

    /// One atomic read of the state file. An empty version identifies an absent file.
    @NotNullByDefault
    private record DiskState(String text, String version) { }

    /// Fingerprints the whole state, including fallback-only and compaction changes.
    private DiskState readSyncState() throws IOException {
        try (var input = Files.newInputStream(stateDirectory.resolve("sync.json"), LinkOption.NOFOLLOW_LINKS)) {
            byte @Unmodifiable [] bytes = input.readNBytes(MAX_STATE_BYTES + 1);
            if (bytes.length > MAX_STATE_BYTES)
                throw new IOException("Content state is too large");
            return new DiskState(new String(bytes, StandardCharsets.UTF_8), RemoteContent.digest(bytes));
        } catch (NoSuchFileException e) {
            return new DiskState("", "");
        }
    }

    /// Loads fallback metadata for items still listed in the latest manifest. Synchronization verifies the payloads.
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
            checkCachePath(directory);
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
            if (!digest.matches("[0-9a-f]{64}") || item.expired(now))
                continue;
            try {
                Path path = directory.resolve(digest + ".json");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    continue;
                byte @Unmodifiable [] bytes;
                try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = stream.readNBytes(MAX_STATE_BYTES + 1);
                }
                if (bytes.length > MAX_STATE_BYTES || !RemoteContent.digest(bytes).equals(digest))
                    continue;
                var announcement = RemoteContent.readAnnouncement(
                        JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
                if (announcement.item().id().equals(id) && !announcement.item().expired(now))
                    fallbacks.put(id, announcement);
            } catch (IOException | RuntimeException ignored) {
                // A damaged fallback cannot invalidate the latest manifest or its other entries.
            }
        }
        return Map.copyOf(fallbacks);
    }

    /// Saves validated metadata before payload downloads, so a known new agreement cannot revert to old consent.
    public synchronized void cache(Cache cache) throws IOException {
        withInstanceLock(() -> saveCache(cache, null));
    }

    /// Publishes only if nobody has replaced the source cache since the caller read it.
    synchronized String publishCache(Cache cache, String expectedVersion, Usage usage) throws IOException {
        return withInstanceLock(() -> {
            usage.checkOpen();
            checkCacheVersion(expectedVersion);
            return saveCache(cache, usage);
        });
    }

    /// Signals a concurrent publication so the caller can reload the authoritative state.
    @NotNullByDefault
    static final class CacheChangedException extends IOException {
        /// Describes a rejected update based on an older snapshot.
        private CacheChangedException() {
            super("Another launcher updated this instance's content cache");
        }
    }

    /// Signals that the protected operation could not start because another launcher holds the lock.
    @NotNullByDefault
    static final class CacheBusyException extends IOException {
        /// Describes temporary contention for the instance cache.
        private CacheBusyException() {
            super("Another launcher is saving this instance's content cache");
        }
    }

    /// Compares the expected fingerprint while publication and file cleanup hold the same instance lock.
    private DiskState checkCacheVersion(String expectedVersion) throws IOException {
        DiskState state = readSyncState();
        if (!state.version().equals(expectedVersion))
            throw new CacheChangedException();
        return state;
    }

    /// A short filesystem operation protected by the instance lock.
    @FunctionalInterface
    @NotNullByDefault
    private interface LockedOperation<T> {
        /// Performs the protected operation.
        T run() throws IOException;
    }

    /// Shares one lock across all sources that use this instance's managed files.
    private <T> T withInstanceLock(LockedOperation<T> operation) throws IOException {
        Files.createDirectories(cacheDirectory);
        checkCachePath(cacheDirectory);
        synchronized (CACHE_WRITE_LOCK) {
            try (var channel = FileChannel.open(cacheDirectory.resolve("content.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                @Nullable FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    throw new CacheBusyException();
                }
                if (lock == null)
                    throw new CacheBusyException();
                try (lock) {
                    return operation.run();
                }
            }
        }
    }

    /// Installs immutable snapshots and publishes their index before collecting unreferenced files.
    private String saveCache(Cache cache, @Nullable Usage usage) throws IOException {
        Files.createDirectories(stateDirectory);
        checkCachePath(stateDirectory);
        JsonObject json = new JsonObject();
        json.addProperty("cacheRevision", UUID.randomUUID().toString());
        json.addProperty("body", cache.body());
        json.addProperty("etag", cache.etag());
        json.addProperty("checkedAt", cache.checkedAt().toString());
        Path directory = fallbackDirectory();
        JsonObject references = new JsonObject();
        Set<String> keep = new HashSet<>();
        if (!cache.announcementFallbacks().isEmpty()) {
            Files.createDirectories(directory);
            checkCachePath(directory);
            for (var entry : cache.announcementFallbacks().entrySet()) {
                byte @Unmodifiable [] snapshot = RemoteContent.writeAnnouncement(entry.getValue()).toString()
                        .getBytes(StandardCharsets.UTF_8);
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
        byte @Unmodifiable [] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        write(stateDirectory.resolve("sync.json"), bytes);
        try {
            ensureServer();
            write(server.resolve("manifest.json"), cache.body().getBytes(StandardCharsets.UTF_8));
            if (!hasOtherUsers(usage))
                pruneFallbackSnapshots(directory, keep);
        } catch (IOException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot update the public content cache", e);
        }
        return RemoteContent.digest(bytes);
    }

    /// A launcher keeps this registration open for as long as it can present the instance's cached files.
    @NotNullByDefault
    static final class Usage implements AutoCloseable {
        /// Unique registration file within the instance.
        private final Path path;
        /// The open channel holds the exclusive OS lock until close or process exit.
        private final FileChannel channel;

        /// Owns a newly locked registration.
        private Usage(Path path, FileChannel channel) {
            this.path = path;
            this.channel = channel;
        }

        /// Prevents queued work from changing storage after this registration has closed.
        private void checkOpen() throws IOException {
            if (!channel.isOpen())
                throw new IOException("The instance content cache is no longer in use");
        }

        /// Releases the registration for the next cleanup to collect.
        @Override
        public void close() throws IOException {
            synchronized (CACHE_WRITE_LOCK) {
                try {
                    channel.close();
                } finally {
                    LOCAL_USERS.remove(path);
                }
            }
        }
    }

    /// Registers before loading or presenting cached files, under the same lock used by garbage collection.
    synchronized Usage registerUsage() throws IOException {
        return withInstanceLock(() -> {
            Path directory = usageDirectory();
            Files.createDirectories(directory);
            checkCachePath(directory);
            Path path = Files.createTempFile(directory.toRealPath(), "session-", ".lock");
            FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                channel.lock();
                LOCAL_USERS.add(path);
                return new Usage(path, channel);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        });
    }

    /// Keeps usage registrations at the instance level because resources are shared across sources.
    private Path usageDirectory() {
        return cacheDirectory.resolve("content-users");
    }

    /// Checks for live users and removes registrations left unlocked by closed or crashed launchers.
    private boolean hasOtherUsers(@Nullable Usage own) throws IOException {
        Path directory = usageDirectory();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return false;
        checkCachePath(directory);
        try (var paths = Files.newDirectoryStream(directory.toRealPath(), "session-*.lock")) {
            for (Path path : paths) {
                if ((own != null && path.equals(own.path)) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    continue;
                if (LOCAL_USERS.contains(path))
                    return true;
                try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     @Nullable FileLock lock = channel.tryLock()) {
                    if (lock == null)
                        return true;
                } catch (OverlappingFileLockException e) {
                    return true;
                }
                Files.deleteIfExists(path);
            }
        }
        return false;
    }

    /// Resolves the snapshot directory dedicated to this instance and remote source.
    private Path fallbackDirectory() {
        return stateDirectory.resolve("announcement-fallbacks");
    }

    /// Rejects linked directories that resolve outside their exact instance and cache path.
    private void checkCachePath(Path directory) throws IOException {
        if (!directory.toRealPath().equals(root.toRealPath().resolve(root.relativize(directory))))
            throw new IOException("Content cache path escapes its instance directory");
    }

    /// Cleans previously published or interrupted snapshots once the replacement index is durable.
    private void pruneFallbackSnapshots(Path directory, Set<String> keep) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return;
        try {
            checkCachePath(directory);
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
        return Files.size(path) <= MAX_PAYLOAD_BYTES && RemoteContent.digest(Files.readAllBytes(path)).equals(resource.sha256());
    }

    /// Installs hash-verified bytes atomically and records ownership before creating the managed file.
    public synchronized void install(RemoteContent.Resource resource, byte @Unmodifiable [] bytes) throws IOException {
        install(resource, bytes, null);
    }

    /// Installs a verified payload while its synchronization session still owns a usage registration.
    synchronized void install(RemoteContent.Resource resource, byte @Unmodifiable [] bytes, @Nullable Usage usage)
            throws IOException {
        if (!RemoteContent.digest(bytes).equals(resource.sha256()))
            throw new IOException("SHA-256 mismatch: " + resource.path());
        withInstanceLock(() -> {
            if (usage != null)
                usage.checkOpen();
            ensureServer();
            Path path = file(resource);
            Files.createDirectories(path.getParent());
            checkOwnedPath(path);
            Set<String> owned = owned();
            owned.add(resource.cacheName());
            saveOwned(owned);
            write(path, bytes);
            return true;
        });
    }

    /// Deletes only previously managed objects no longer needed by active/future content or a pending replacement.
    public synchronized void prune(@Unmodifiable Set<RemoteContent.Resource> keep) throws IOException {
        withInstanceLock(() -> {
            if (!hasOtherUsers(null))
                pruneFiles(keep);
            return true;
        });
    }

    /// Reclaims files only for the latest persisted source state and while this launcher is the sole user.
    synchronized void prune(@Unmodifiable Set<RemoteContent.Resource> keep, String expectedVersion, Usage usage)
            throws IOException {
        withInstanceLock(() -> {
            usage.checkOpen();
            DiskState state = checkCacheVersion(expectedVersion);
            if (!hasOtherUsers(usage)) {
                pruneFiles(keep);
                if (!state.version().isEmpty())
                    prunePublishedFallbacks(state.text());
            }
            return true;
        });
    }

    /// Reclaims obsolete snapshots left behind when cleanup was deferred during publication.
    private void prunePublishedFallbacks(String text) {
        @Nullable JsonElement index = JsonParser.parseString(text).getAsJsonObject().get("announcementFallbacks");
        Set<String> keep = new HashSet<>();
        if (index != null && index.isJsonObject()) {
            for (var entry : index.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                    keep.add(value.getAsString() + ".json");
            }
        }
        pruneFallbackSnapshots(fallbackDirectory(), keep);
    }

    /// Removes managed payload objects under the instance lock.
    private void pruneFiles(@Unmodifiable Set<RemoteContent.Resource> keep) throws IOException {
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
        if (!owned.isEmpty() || Files.exists(cacheDirectory.resolve("managed-files.json")))
            saveOwned(owned);
    }

    /// Removes expired inline bodies and unused file descriptors while retaining the HTTP validator for revalidation.
    public synchronized Cache compact(Cache cache, String instanceKey, Instant now) throws IOException {
        Cache compacted = compacted(cache, instanceKey, now);
        if (compacted != cache)
            cache(compacted);
        return compacted;
    }

    /// Prepares compaction without publishing it, so synchronization can check its cache version first.
    static Cache compacted(Cache cache, String instanceKey, Instant now) throws IOException {
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
        return new Cache(json.toString(), cache.etag(), cache.checkedAt(), cache.announcementFallbacks());
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
        if (Files.exists(path))
            return read(path);
        Path legacy = legacyStateDirectory.resolve("receipts.json");
        if (Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)) {
            try {
                checkCachePath(legacyStateDirectory);
                return read(legacy);
            } catch (IOException ignored) {
                // An unreadable legacy receipt cannot grant consent.
            }
        }
        return new JsonObject();
    }

    /// Reads the installation's managed-object inventory, shared across source changes for safe cleanup.
    private Set<String> owned() throws IOException {
        Set<String> result = new HashSet<>();
        Path path = cacheDirectory.resolve("managed-files.json");
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
        write(cacheDirectory.resolve("managed-files.json"), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /// Creates the public cache and rejects filesystem links escaping this instance's launcher directory.
    private void ensureServer() throws IOException {
        Files.createDirectories(server);
        checkCachePath(server);
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
        if (current == null || !current.toRealPath().startsWith(realRoot.resolve(root.relativize(server))))
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
    private static void write(Path path, byte @Unmodifiable [] bytes) throws IOException {
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
