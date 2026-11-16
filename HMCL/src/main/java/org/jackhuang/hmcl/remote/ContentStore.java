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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Instance-local receipts and content-addressed downloads, with ownership-bounded garbage collection.
@NotNullByDefault
public final class ContentStore {
    /// Largest payload accepted for storage or verified reuse.
    static final int MAX_PAYLOAD_BYTES = 16_777_216;
    /// Bounds local metadata reads and writes, including the escaped current manifest.
    private static final int MAX_STATE_BYTES = 12_582_912;
    /// Bounded delays while another launcher holds the lock needed for a receipt operation.
    private static final long @Unmodifiable [] RECEIPT_RETRY_MILLIS = {100, 300, 900};
    /// Serializes local writers by real instance path before taking the cross-process lock.
    private static final Map<Path, Object> INSTANCE_WRITE_LOCKS = new ConcurrentHashMap<>();
    /// Avoids opening a second channel to a locally locked registration.
    private static final Set<Path> LOCAL_USERS = ConcurrentHashMap.newKeySet();
    /// This instance's `.launcher` directory.
    private final Path root;
    /// Instance-side state, file ownership and cache coordination.
    private final Path client;
    /// Source-specific receipt and HTTP state directory.
    private final Path stateDirectory;
    /// Public downloaded content beneath the instance's launcher directory.
    private final Path server;
    /// Parsed index reusable by synchronization and receipts after checking the on-disk fingerprint.
    private volatile @Nullable ParsedState parsedState;

    /// Creates storage for one instance and one server content source.
    public ContentStore(Path launcherDirectory, String source) {
        root = launcherDirectory.toAbsolutePath().normalize();
        client = root.resolve("client");
        server = root.resolve("server");
        stateDirectory = client.resolve("state").resolve(RemoteContent.hash(source));
    }

    /// Current server response and the displayable announcement revisions retained during its download.
    @NotNullByDefault
    public record Cache(String body, RemoteContent.Manifest manifest, String etag, Instant checkedAt,
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

        /// Parses a new manifest before selecting announcement fallbacks.
        static Cache parse(String body, String instanceKey, String etag, Instant checkedAt) throws IOException {
            return new Cache(body, RemoteContent.parse(body, instanceKey), etag, checkedAt, Map.of(), false);
        }

        /// Reuses the validated response after HTTP 304 while the current fallbacks are selected again.
        Cache revalidated(Instant now) {
            return new Cache(body, manifest, etag, now, Map.of(), false);
        }

        /// Replaces the fallback set without altering the current manifest or validator.
        Cache withAnnouncementFallbacks(@Unmodifiable Map<String, RemoteContent.Announcement> fallbacks) {
            return new Cache(body, manifest, etag, checkedAt, fallbacks, false);
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

    /// Reloads metadata and sidecars only when the caller's disk fingerprint changed.
    synchronized @Nullable CacheState loadChangedCache(String instanceKey, @Nullable String knownVersion) throws IOException {
        DiskState disk = readSyncState();
        if (disk.version().equals(knownVersion))
            return null;
        if (disk.version().isEmpty())
            return new CacheState(null, disk.version());
        try {
            ParsedState parsed = parseState(disk, instanceKey);
            JsonObject json = parsed.json();
            String body = json.get("body").getAsString();
            RemoteContent.Manifest manifest = parsed.manifest();
            @Nullable JsonElement index = json.get("announcementFallbacks");
            @Unmodifiable Map<String, RemoteContent.Announcement> fallbacks = readAnnouncementFallbacks(index, manifest);
            boolean rewrite = index != null && (!index.isJsonObject() || index.getAsJsonObject().size() != fallbacks.size());
            Cache cache = new Cache(body, manifest, json.get("etag").getAsString(),
                    Instant.parse(json.get("checkedAt").getAsString()), fallbacks, rewrite);
            return new CacheState(cache, disk.version());
        } catch (IOException | RuntimeException e) {
            return new CacheState(null, disk.version());
        }
    }

    /// One atomic read of the state file. An empty version identifies an absent file.
    @NotNullByDefault
    private record DiskState(byte @Unmodifiable [] bytes, String version) {
        /// Decodes the state only for operations that need its contents in addition to its fingerprint.
        private JsonObject json() {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /// Private, read-only view of a validated index and its exact disk fingerprint.
    private record ParsedState(String version, JsonObject json, RemoteContent.Manifest manifest) { }

    /// Shares the parsed manifest without skipping the caller's fresh disk fingerprint check.
    private ParsedState parseState(DiskState disk, String instanceKey) throws IOException {
        @Nullable ParsedState parsed = parsedState;
        if (parsed != null && parsed.version().equals(disk.version()) && parsed.manifest().instanceKey().equals(instanceKey))
            return parsed;
        JsonObject json = disk.json();
        var manifest = RemoteContent.parse(json.get("body").getAsString(), instanceKey);
        parsed = new ParsedState(disk.version(), json, manifest);
        parsedState = parsed;
        return parsed;
    }

    /// Fingerprints the whole state, including fallback-only and compaction changes.
    private DiskState readSyncState() throws IOException {
        try (var input = Files.newInputStream(stateDirectory.resolve("sync.json"), LinkOption.NOFOLLOW_LINKS)) {
            byte @Unmodifiable [] bytes = input.readNBytes(MAX_STATE_BYTES + 1);
            if (bytes.length > MAX_STATE_BYTES)
                throw new IOException("Content state is too large");
            return new DiskState(bytes, RemoteContent.digest(bytes));
        } catch (NoSuchFileException e) {
            return new DiskState(new byte[0], "");
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
            @Nullable String digest = fallbackDigest(references.get(id));
            if (digest == null || item.expired(now))
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

    /// Returns the digest of a valid snapshot reference.
    private static @Nullable String fallbackDigest(@Nullable JsonElement reference) {
        if (reference == null || !reference.isJsonPrimitive() || !reference.getAsJsonPrimitive().isString())
            return null;
        String digest = reference.getAsString();
        return digest.matches("[0-9a-f]{64}") ? digest : null;
    }

    /// Publishes only if nobody has replaced the source cache since the caller read it.
    synchronized String publishCache(Cache cache, String expectedVersion, Usage usage) throws IOException {
        return withInstanceLock(() -> {
            usage.checkOpen();
            checkCacheVersion(expectedVersion);
            return saveCache(cache).version();
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

    /// Rejects a protocol decision based on an agreement that is no longer the current cached version.
    @NotNullByDefault
    static final class AgreementChangedException extends IOException {
        /// Describes an outdated protocol decision.
        private AgreementChangedException() {
            super("此实例的用户协议已变更，请重新检查");
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
        Files.createDirectories(client);
        Path realClient = checkCachePath(client);
        Object localLock = INSTANCE_WRITE_LOCKS.computeIfAbsent(realClient, ignored -> new Object());
        synchronized (localLock) {
            try (var channel = FileChannel.open(client.resolve("content.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                try (@Nullable FileLock lock = tryLock(channel)) {
                    if (lock == null)
                        throw new CacheBusyException();
                    return operation.run();
                }
            }
        }
    }

    /// Treats both OS contention and overlapping JVM locks as unavailable, propagating I/O failures.
    private static @Nullable FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    /// Records the committed version and the snapshots its index references.
    private record SavedCache(String version, @Unmodifiable Set<String> fallbackFiles) { }

    /// Installs immutable snapshots and publishes their index before any later cleanup can remove files.
    private SavedCache saveCache(Cache cache) throws IOException {
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
                writeIfChanged(directory.resolve(filename), snapshot);
                references.addProperty(entry.getKey(), digest);
                keep.add(filename);
            }
        }
        json.add("announcementFallbacks", references);
        byte @Unmodifiable [] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        String version = RemoteContent.digest(bytes);
        write(stateDirectory.resolve("sync.json"), bytes);
        parsedState = new ParsedState(version, json, cache.manifest());
        return new SavedCache(version, Set.copyOf(keep));
    }

    /// Avoids replacing an identical fallback snapshot.
    private static void writeIfChanged(Path path, byte @Unmodifiable [] bytes) throws IOException {
        try {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == bytes.length) {
                try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (Arrays.equals(input.readNBytes(bytes.length + 1), bytes))
                        return;
                }
            }
        } catch (IOException ignored) {
            // The existing file may be replaceable even when it cannot be read.
        }
        write(path, bytes);
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
            try {
                channel.close();
            } finally {
                // Readers may defer cleanup until the channel is fully released.
                LOCAL_USERS.remove(path);
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
        return client.resolve("content-users");
    }

    /// Checks for live users and removes registrations left unlocked by closed or crashed launchers.
    private boolean hasOtherUsers(Usage own) throws IOException {
        Path directory = usageDirectory();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return false;
        checkCachePath(directory);
        try (var paths = Files.newDirectoryStream(directory.toRealPath(), "session-*.lock")) {
            for (Path path : paths) {
                if (path.equals(own.path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    continue;
                if (LOCAL_USERS.contains(path))
                    return true;
                try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     @Nullable FileLock lock = tryLock(channel)) {
                    if (lock == null)
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

    /// Returns the real directory after checking that it resolves to the expected cache path.
    private Path checkCachePath(Path directory) throws IOException {
        Path resolved = directory.toRealPath();
        if (!resolved.equals(root.toRealPath().resolve(root.relativize(directory))))
            throw new IOException("Content cache path escapes its instance directory");
        return resolved;
    }

    /// Cleans previously published or interrupted snapshots once the replacement index is durable.
    private void pruneFallbackSnapshots(Path directory, @Unmodifiable Set<String> keep) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return;
        checkCachePath(directory);
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.matches("[0-9a-f]{64}\\.json") && !keep.contains(name)
                        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    Files.deleteIfExists(file);
            }
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

    /// Separates a committed index update from any subsequent failure reclaiming files.
    record Maintenance(String version, @Nullable Exception cleanupFailure) { }

    /// Checks the baseline, writes any updated index and reclaims managed files under the same instance lock.
    synchronized Maintenance maintainCache(@Nullable Cache replacement, String expectedVersion, Usage usage,
                                           @Unmodifiable Set<RemoteContent.Resource> keep) throws IOException {
        return withInstanceLock(() -> {
            usage.checkOpen();
            DiskState state = checkCacheVersion(expectedVersion);
            @Nullable SavedCache saved = replacement == null ? null : saveCache(replacement);
            String version = saved == null ? state.version() : saved.version();
            try {
                if (!hasOtherUsers(usage)) {
                    // Resolve the live snapshot set before deleting anything.
                    @Unmodifiable Set<String> snapshots = saved == null ? publishedFallbacks(state) : saved.fallbackFiles();
                    pruneFiles(keep);
                    pruneFallbackSnapshots(fallbackDirectory(), snapshots);
                }
            } catch (IOException | RuntimeException e) {
                return new Maintenance(version, e);
            }
            return new Maintenance(version, null);
        });
    }

    /// Reads the references needed to reclaim snapshots left behind by an earlier publication.
    private @Unmodifiable Set<String> publishedFallbacks(DiskState state) {
        if (state.version().isEmpty())
            return Set.of();
        @Nullable ParsedState parsed = parsedState;
        JsonObject json = parsed != null && parsed.version().equals(state.version()) ? parsed.json() : state.json();
        @Nullable JsonElement index = json.get("announcementFallbacks");
        Set<String> keep = new HashSet<>();
        if (index != null && index.isJsonObject()) {
            for (JsonElement value : index.getAsJsonObject().asMap().values()) {
                @Nullable String digest = fallbackDigest(value);
                if (digest != null)
                    keep.add(digest + ".json");
            }
        }
        return Set.copyOf(keep);
    }

    /// Removes managed payload objects under the instance lock.
    private void pruneFiles(@Unmodifiable Set<RemoteContent.Resource> keep) throws IOException {
        Set<String> names = new HashSet<>();
        for (var resource : keep)
            names.add(resource.cacheName());
        Set<String> owned = owned();
        boolean changed = false;
        for (String name : Set.copyOf(owned)) {
            if (!names.contains(name)) {
                if (!name.matches("[0-9a-f]{64}\\.[a-z0-9]{1,12}"))
                    throw new IOException("Invalid managed object name");
                Path path = server.resolve("files").resolve(name);
                checkOwnedPath(path);
                Files.deleteIfExists(path);
                owned.remove(name);
                changed = true;
            }
        }
        if (changed)
            saveOwned(owned);
    }

    /// Prepares compaction without publishing it, so synchronization can check its cache version first.
    static Cache compacted(Cache cache, Instant now) {
        RemoteContent.Manifest manifest = cache.manifest();
        @Unmodifiable List<RemoteContent.Item> notices = manifest.notices().stream().filter(item -> !item.expired(now)).toList();
        @Unmodifiable List<RemoteContent.Item> announcements = manifest.announcements().stream()
                .filter(item -> !item.expired(now)).toList();
        @Unmodifiable Set<RemoteContent.Resource> needed = manifest.needed(now);
        if (notices.size() == manifest.notices().size() && announcements.size() == manifest.announcements().size()
                && needed.size() == manifest.files().size())
            return cache;
        Map<String, RemoteContent.Resource> files = new LinkedHashMap<>();
        for (RemoteContent.Resource resource : needed)
            files.put(resource.path(), resource);
        JsonObject json = JsonParser.parseString(cache.body()).getAsJsonObject();
        retainItems(json.getAsJsonArray("notices"), notices);
        retainItems(json.getAsJsonArray("announcements"), announcements);
        json.getAsJsonObject("files").entrySet().removeIf(entry -> !files.containsKey(entry.getKey()));
        var compacted = new RemoteContent.Manifest(manifest.instanceKey(), manifest.agreement(), notices, announcements,
                Map.copyOf(files));
        return new Cache(json.toString(), compacted, cache.etag(), cache.checkedAt(), cache.announcementFallbacks(), false);
    }

    /// Removes expired entries while preserving the source JSON for retained items.
    private static void retainItems(JsonArray items, @Unmodifiable List<RemoteContent.Item> retained) {
        Set<String> ids = new HashSet<>();
        for (RemoteContent.Item item : retained)
            ids.add(item.id());
        items.asList().removeIf(item -> !ids.contains(item.getAsJsonObject().get("id").getAsString()));
    }

    /// Reads a receipt for a specific agreement independently of the current cache.
    boolean accepted(RemoteContent.Manifest manifest) {
        try {
            return hasAgreementReceipt(manifest.key(manifest.agreement()));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /// Checks a launch receipt against the current persisted agreement while cache writers are excluded.
    boolean acceptedCurrent(RemoteContent.Manifest manifest) throws IOException {
        return withReceiptLock(() -> hasAgreementReceipt(checkCurrentAgreement(manifest)));
    }

    /// Durably records consent before any game launch continuation is allowed to run.
    public void accept(RemoteContent.Manifest manifest) throws IOException {
        withReceiptLock(() -> {
            String key = checkCurrentAgreement(manifest);
            JsonObject state = receipts();
            state.addProperty("agreementKey", key);
            state.addProperty("agreementId", manifest.agreement().id());
            state.addProperty("agreementRevision", manifest.agreement().revision());
            state.addProperty("acceptedAt", Instant.now().toString());
            saveReceipts(state);
            return true;
        });
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
    public void remember(String category, String key) throws IOException {
        withReceiptLock(() -> {
            JsonObject state = receipts();
            JsonArray previous = state.has(category) ? state.getAsJsonArray(category) : new JsonArray();
            JsonArray next = new JsonArray();
            for (int i = Math.max(0, previous.size() - 499); i < previous.size(); i++) {
                if (!key.equals(previous.get(i).getAsString()))
                    next.add(previous.get(i));
            }
            next.add(key);
            state.add(category, next);
            saveReceipts(state);
            return true;
        });
    }

    /// Retries receipt access without holding the instance lock during the delay.
    private <T> T withReceiptLock(LockedOperation<T> operation) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                return withInstanceLock(operation);
            } catch (CacheBusyException e) {
                if (attempt >= RECEIPT_RETRY_MILLIS.length)
                    throw e;
                try {
                    Thread.sleep(RECEIPT_RETRY_MILLIS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for the content receipt lock", interrupted);
                }
            }
        }
    }

    /// Compares the agreement key under the instance lock, allowing unrelated manifest updates.
    private String checkCurrentAgreement(RemoteContent.Manifest manifest) throws IOException {
        DiskState disk = readSyncState();
        if (disk.version().isEmpty())
            throw new AgreementChangedException();
        RemoteContent.Manifest current;
        try {
            current = parseState(disk, manifest.instanceKey()).manifest();
        } catch (RuntimeException e) {
            throw new IOException("无法读取当前用户协议", e);
        }
        String key = manifest.key(manifest.agreement());
        if (!key.equals(current.key(current.agreement())))
            throw new AgreementChangedException();
        return key;
    }

    /// Checks the stored receipt for the given agreement key.
    private boolean hasAgreementReceipt(String key) throws IOException {
        JsonObject state = receipts();
        return state.has("agreementKey") && key.equals(state.get("agreementKey").getAsString());
    }

    /// Atomically replaces the receipt file while the caller holds the instance lock.
    private void saveReceipts(JsonObject state) throws IOException {
        Files.createDirectories(stateDirectory);
        checkCachePath(stateDirectory);
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
        Path path = client.resolve("managed-files.json");
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
        write(client.resolve("managed-files.json"), json.toString().getBytes(StandardCharsets.UTF_8));
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
