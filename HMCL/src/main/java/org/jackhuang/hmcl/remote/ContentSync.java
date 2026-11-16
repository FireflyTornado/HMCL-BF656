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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// One selectable instance session: conditional manifest check, digest-based downloads and failure-only retries.
@NotNullByDefault
public final class ContentSync implements AutoCloseable {
    /// Retry delays following each failed cycle; pause/resume preserves the remaining budget.
    private static final long @Unmodifiable [] RETRY_SECONDS = {15, 60, 180};
    /// Delays between bounded retries of failed local cache maintenance.
    private static final long @Unmodifiable [] CLEANUP_RETRY_SECONDS = {1, 2, 4};
    /// Instance-scoped settings and server endpoint.
    private final Config config;
    /// Cache and receipts beneath this instance's `.launcher` directory.
    private final ContentStore store;
    /// Worker-thread result consumer; desktop callers marshal updates to JavaFX.
    private final Consumer<Result> listener;
    /// Retry intervals, injectable in HTTP integration tests.
    private final long @Unmodifiable [] retrySeconds;
    /// Serial worker prevents overlapping writes and preserves result ordering for this instance.
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BF656 instance content");
        thread.setDaemon(true);
        return thread;
    });
    /// Current validated manifest, possibly restored from disk or missing some payloads.
    private @Nullable RemoteContent.Manifest manifest;
    /// Previous manifest retained temporarily for agreement and notice files.
    private @Nullable RemoteContent.Manifest previous;
    /// Conditional-request validator and original response.
    private @Nullable ContentStore.Cache cache;
    /// Fingerprint of the authoritative cache used for the request and subsequent maintenance.
    private String cacheVersion = "";
    /// Protects all cached files this session may still present, even while it is paused.
    private volatile @Nullable ContentStore.Usage usage;
    /// A metadata write must complete before managed file cleanup can use the new state.
    private boolean cacheDirty;
    /// Whether the manifest has been checked successfully during this process session.
    private boolean checked;
    /// Whether an invalid announcement requires fetching a corrected manifest on the next attempt.
    private boolean recheckAnnouncementManifest;
    /// Whether a manifest check reached publication but could not acquire the instance lock.
    private boolean publicationBlocked;
    /// Whether all current resources are available and no automatic requests remain necessary.
    private boolean success;
    /// Whether this instance is selected and allowed to issue requests.
    private boolean active;
    /// Whether one synchronization cycle is running or queued.
    private boolean running;
    /// Consecutive failed cycles, excluding selection-driven cancellation.
    private int failures;
    /// Generation invalidates in-flight results after pause or shutdown.
    private volatile long generation;
    /// Scheduled retry, cancelled when leaving the instance.
    private @Nullable ScheduledFuture<?> retry;
    /// Scheduled local cleanup, independent of the network retry budget.
    private @Nullable ScheduledFuture<?> cleanupRetry;
    /// Most recent local maintenance failure, cleared after successful maintenance.
    private @Nullable String cleanupError;
    /// Last result delivered by the worker, used to update just the local maintenance status.
    private @Nullable Result lastResult;
    /// Active connection is disconnected on selection changes rather than waiting for its read timeout.
    private volatile @Nullable HttpURLConnection connection;

    /// Settings read only from the selected instance; folder names are not used as server identifiers.
    @NotNullByDefault
    public record Config(boolean enabled, URI serverBaseUrl, String instanceKey) {
        /// Reads `.launcher/client/config.json`; ordinary instances without it remain unaffected.
        public static Config load(Path launcherDirectory) throws IOException {
            Path path = launcherDirectory.resolve("client/config.json");
            if (!Files.exists(path))
                return new Config(false, URI.create("https://example.com/launcher/"), "unconfigured");
            try {
                if (Files.size(path) > 16_384)
                    throw new IOException("Instance content configuration is too large");
                JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                Config config = new Config(RemoteContent.bool(json, "enabled", false),
                        URI.create(RemoteContent.string(json, "serverBaseUrl", "https://example.com/launcher/")),
                        RemoteContent.string(json, "instanceKey", "unconfigured"));
                config.validate();
                return config;
            } catch (RuntimeException e) {
                throw new IOException("实例内容配置无效：" + path + "\n" + e.getMessage(), e);
            }
        }

        /// Validates public HTTPS endpoints and a stable portable instance key.
        public void validate() {
            RemoteContent.validateHttp(serverBaseUrl);
            if (serverBaseUrl.getQuery() != null || serverBaseUrl.getFragment() != null
                    || !serverBaseUrl.getPath().endsWith("/")
                    || !instanceKey.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                throw new IllegalArgumentException("Invalid serverBaseUrl or instanceKey");
        }

        /// Resolves the public directory corresponding to this instance's explicit key.
        public URI endpoint() {
            return serverBaseUrl.resolve("instances/" + instanceKey + "/");
        }
    }

    /// Carries current metadata and the announcement revisions that passed background payload and document checks.
    @NotNullByDefault
    public record Result(@Nullable RemoteContent.Manifest manifest,
                         @Unmodifiable Map<String, RemoteContent.Announcement> announcementFallbacks,
                         @Unmodifiable Set<String> readyAnnouncements,
                         boolean cached, boolean pending,
                         boolean contentSynced, @Nullable Instant checkedAt, @Nullable String error, boolean exhausted,
                         boolean publicationBlocked, @Nullable String cleanupError, boolean agreementReady) {
        /// Detaches the presentation state from the mutable verification run.
        public Result {
            announcementFallbacks = Map.copyOf(announcementFallbacks);
            readyAnnouncements = Set.copyOf(readyAnnouncements);
        }

        /// Creates a result without pending cache errors.
        public Result(@Nullable RemoteContent.Manifest manifest,
                      @Unmodifiable Map<String, RemoteContent.Announcement> announcementFallbacks,
                      @Unmodifiable Set<String> readyAnnouncements, boolean cached, boolean pending,
                      boolean success, @Nullable Instant checkedAt, @Nullable String error, boolean exhausted) {
            this(manifest, announcementFallbacks, readyAnnouncements, cached, pending, success, checkedAt, error,
                    exhausted, false, null, false);
        }

        /// Whether both content synchronization and local maintenance succeeded.
        public boolean success() {
            return contentSynced && cleanupError == null;
        }

        /// Records a local maintenance result while preserving the current content and its network status.
        private Result withCleanupError(@Nullable String error) {
            return new Result(manifest, announcementFallbacks, readyAnnouncements, cached, pending, contentSynced,
                    checkedAt, this.error, exhausted, publicationBlocked, error, agreementReady);
        }

        /// Returns the manifest once its agreement can be used for a launch decision.
        @Nullable RemoteContent.Manifest agreementManifest() {
            return !agreementReady || publicationBlocked || (cached && pending) ? null : manifest;
        }
    }

    /// Creates a session with production retry intervals.
    public ContentSync(Config config, ContentStore store, Consumer<Result> listener) {
        this(config, store, listener, RETRY_SECONDS);
    }

    /// Creates a session with deterministic retry intervals for tests.
    ContentSync(Config config, ContentStore store, Consumer<Result> listener, long @Unmodifiable [] retrySeconds) {
        config.validate();
        this.config = config;
        this.store = store;
        this.listener = listener;
        this.retrySeconds = retrySeconds.clone();
    }

    /// Activates once per selection, retaining successful work across repeated selections in this process.
    public synchronized void activate() {
        active = true;
        if (success) {
            prune();
        } else if (!running && failures <= retrySeconds.length) {
            running = true;
            long token = generation;
            worker.execute(() -> run(token));
        }
    }

    /// Stops network work when deselected without resetting success or the finite retry budget.
    public synchronized void pause() {
        active = false;
        generation++;
        running = false;
        if (retry != null)
            retry.cancel(false);
        @Nullable HttpURLConnection request = connection;
        if (request != null)
            java.util.concurrent.CompletableFuture.runAsync(request::disconnect);
    }

    /// Explicit refresh rechecks the manifest while reusing unchanged content-addressed files.
    public synchronized void refresh() {
        if (running)
            return;
        checked = false;
        success = false;
        failures = 0;
        if (retry != null)
            retry.cancel(false);
        activate();
    }

    /// Explicit retry resets the finite failure budget while preserving a successfully checked manifest.
    public synchronized void retryFailed() {
        if (running)
            return;
        success = false;
        failures = 0;
        if (retry != null)
            retry.cancel(false);
        activate();
    }

    /// Runs local expiry cleanup even after all network work has stopped.
    public synchronized void prune() {
        if (!worker.isShutdown())
            worker.execute(() -> pruneOnWorker(0));
    }

    /// Runs cleanup and restarts synchronization if another launcher changed the cache baseline.
    private void pruneOnWorker(int attempt) {
        CleanupOutcome outcome = cleanup(new Verification(), attempt);
        synchronized (this) {
            if (worker.isShutdown())
                return;
            if (outcome == CleanupOutcome.CACHE_CHANGED) {
                if (active && !running) {
                    success = false;
                    failures = 0;
                    activate();
                }
            } else if (lastResult != null && !java.util.Objects.equals(lastResult.cleanupError(), cleanupError)) {
                lastResult = lastResult.withCleanupError(cleanupError);
                listener.accept(lastResult);
            }
        }
    }

    /// Queues at most one delayed local cleanup after a maintenance failure.
    private synchronized void retryCleanup(int attempt) {
        if (worker.isShutdown() || cleanupRetry != null)
            return;
        cleanupRetry = worker.schedule(() -> {
            synchronized (this) {
                cleanupRetry = null;
            }
            pruneOnWorker(attempt + 1);
        }, CLEANUP_RETRY_SECONDS[attempt], TimeUnit.SECONDS);
    }

    /// Discards a queued retry after cleanup succeeds or detects a newer baseline.
    private synchronized void cancelCleanupRetry() {
        if (cleanupRetry != null) {
            cleanupRetry.cancel(false);
            cleanupRetry = null;
        }
    }

    /// Acquires filesystem protection before loading any documents into this session.
    private synchronized void registerUsage(long token) throws IOException {
        checkActive(token);
        if (usage == null)
            usage = store.registerUsage();
    }

    /// Adopts a newer on-disk state without treating its cached response as a fresh network check.
    private void reloadCache() throws IOException {
        @Nullable ContentStore.CacheState disk = store.loadChangedCache(config.instanceKey(),
                cache == null ? null : cacheVersion);
        if (disk == null)
            return;
        previous = manifest;
        cacheVersion = disk.version();
        cache = disk.cache();
        manifest = cache == null ? null : RemoteContent.parse(cache.body(), config.instanceKey());
        cacheDirty = cache != null && cache.fallbackIndexNeedsRewrite();
        checked = false;
    }

    /// Discards authorization metadata when a conflicting or incomplete publication must be reloaded.
    private void clearCacheState() {
        cache = null;
        manifest = null;
        previous = null;
        cacheDirty = false;
        checked = false;
    }

    /// Executes one attempt, continuing past individual file failures so unrelated items can still become ready.
    private void run(long token) {
        Verification verification = new Verification();
        StringBuilder errors = new StringBuilder();
        try {
            registerUsage(token);
            reloadCache();
            emit(token, verification, true, null);
            if (!checked || recheckAnnouncementManifest) {
                checkManifest(token, verification);
                emit(token, verification, true, null);
            }
            RemoteContent.Manifest current = java.util.Objects.requireNonNull(manifest);
            for (RemoteContent.Resource resource : current.needed(Instant.now())) {
                checkActive(token);
                try {
                    if (!verification.resource(resource)) {
                        byte @Unmodifiable [] bytes = download(resourceUri(resource), "", ContentStore.MAX_PAYLOAD_BYTES, token).bytes();
                        checkActive(token);
                        store.install(resource, bytes, java.util.Objects.requireNonNull(usage));
                        verification.installed(resource);
                        emit(token, verification, true, null);
                    }
                } catch (IOException | IllegalArgumentException e) {
                    errors.append(resource.path()).append(": ").append(e.getMessage()).append('\n');
                }
            }
            for (RemoteContent.Kind kind : RemoteContent.Kind.values()) {
                for (RemoteContent.Item item : current.items(kind)) {
                    if (item.expired(Instant.now()))
                        continue;
                    if (kind == RemoteContent.Kind.ANNOUNCEMENTS) {
                        @Nullable String error = verification.announcement(RemoteContent.Announcement.capture(current, item)).error();
                        if (error != null) {
                            errors.append(item.id()).append(": ").append(error).append('\n');
                            recheckAnnouncementManifest = true;
                        }
                    } else if (store.ready(current, item)) {
                        try {
                            ContentDocuments.validate(Path.of("."), current, item, store, config.endpoint(), false);
                        } catch (IOException e) {
                            errors.append(item.id()).append(": ").append(e.getMessage()).append('\n');
                        }
                    }
                }
            }
            checkActive(token);
        } catch (CancellationException ignored) {
            return;
        } catch (IOException | RuntimeException e) {
            if (generation != token)
                return;
            errors.append(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        synchronized (this) {
            if (!active || generation != token || worker.isShutdown())
                return;
            running = false;
            success = errors.isEmpty();
            if (cleanup(verification, 0) == CleanupOutcome.CACHE_CHANGED) {
                success = false;
                errors.append("Another launcher updated the content cache during synchronization");
            }
            if (success) {
                previous = null;
            } else {
                failures++;
            }
            emit(token, verification, false, errors.isEmpty() ? null : errors.toString());
            if (!success && failures <= retrySeconds.length) {
                retry = worker.schedule(() -> {
                    synchronized (ContentSync.this) {
                        if (active)
                            activate();
                    }
                }, retrySeconds[failures - 1], TimeUnit.SECONDS);
            }
        }
    }

    /// Publishes a response against its original disk baseline, retrying once from a competing publication.
    private void checkManifest(long token, Verification verification) throws IOException {
        for (int attempt = 0; ; attempt++) {
            ContentStore.Cache fresh = downloadManifest(token);
            RemoteContent.Manifest next = RemoteContent.parse(fresh.body(), config.instanceKey());
            checkActive(token);
            @Unmodifiable Map<String, RemoteContent.Announcement> fallbacks =
                    selectAnnouncementFallbacks(next, manifest, Instant.now(), verification);
            ContentStore.Cache replacement = fresh.withAnnouncementFallbacks(fallbacks);
            checkActive(token);
            try {
                cacheVersion = store.publishCache(replacement, cacheVersion, java.util.Objects.requireNonNull(usage));
            } catch (ContentStore.CacheChangedException e) {
                clearCacheState();
                reloadCache();
                verification.clear();
                if (attempt > 0)
                    throw e;
                emit(token, verification, true, null);
                continue;
            } catch (ContentStore.CacheBusyException e) {
                publicationBlocked = true;
                throw e;
            } catch (IOException e) {
                clearCacheState();
                throw e;
            }
            if (!next.equals(manifest))
                previous = manifest;
            manifest = next;
            cache = replacement;
            cacheDirty = false;
            checked = true;
            recheckAnnouncementManifest = false;
            publicationBlocked = false;
            return;
        }
    }

    /// Availability of a revision and the document error, if all files arrived but the document is invalid.
    @NotNullByDefault
    private record AnnouncementCheck(boolean ready, @Nullable String error) {
        /// The revision is waiting for verified payloads.
        private static final AnnouncementCheck MISSING = new AnnouncementCheck(false, null);
        /// Both the payloads and document passed validation.
        private static final AnnouncementCheck READY = new AnnouncementCheck(true, null);
    }

    /// Shares file and document checks across publication, presentation and cleanup within one background attempt.
    private final class Verification {
        /// Digest checks by content-addressed filename, updated when a download is installed.
        private final Map<String, Boolean> resources = new HashMap<>();
        /// Document checks by exact revision and resource descriptors, after every payload is present.
        private final Map<RemoteContent.Announcement, AnnouncementCheck> announcements = new HashMap<>();

        /// Checks the cached bytes once per attempt.
        private boolean resource(RemoteContent.Resource resource) {
            return resources.computeIfAbsent(resource.cacheName(), ignored -> {
                try {
                    return store.verified(resource);
                } catch (IOException e) {
                    return false;
                }
            });
        }

        /// Records a payload whose digest the store checked during installation.
        private void installed(RemoteContent.Resource resource) {
            resources.put(resource.cacheName(), true);
        }

        /// Checks all declared agreement files before exposing the protocol to the launch flow.
        private boolean agreementReady(RemoteContent.Manifest manifest) {
            return manifest.agreement().files().stream()
                    .allMatch(path -> resource(java.util.Objects.requireNonNull(manifest.files().get(path))));
        }

        /// Considers a revision ready only after validating its payloads and document.
        private AnnouncementCheck announcement(RemoteContent.Announcement announcement) {
            if (!announcement.files().values().stream().allMatch(this::resource))
                return AnnouncementCheck.MISSING;
            return announcements.computeIfAbsent(announcement, revision -> {
                try {
                    ContentDocuments.validateAnnouncement(revision, store, config.endpoint());
                    return AnnouncementCheck.READY;
                } catch (IOException e) {
                    return new AnnouncementCheck(false, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            });
        }

        /// Revalidates files and documents after adopting a concurrent publication.
        private void clear() {
            resources.clear();
            announcements.clear();
        }
    }

    /// Retains a usable fallback until an active replacement is usable or the announcement is withdrawn.
    private @Unmodifiable Map<String, RemoteContent.Announcement> selectAnnouncementFallbacks(
            RemoteContent.Manifest current, @Nullable RemoteContent.Manifest prior, Instant now, Verification verification) {
        Map<String, RemoteContent.Item> previousItems = new HashMap<>();
        if (prior != null) {
            for (RemoteContent.Item item : prior.announcements())
                previousItems.put(item.id(), item);
        }
        @Unmodifiable Map<String, RemoteContent.Announcement> saved = cache == null ? Map.of() : cache.announcementFallbacks();
        Map<String, RemoteContent.Announcement> fallbacks = new HashMap<>();
        for (RemoteContent.Item item : current.announcements()) {
            if (item.expired(now))
                continue;
            RemoteContent.Announcement replacement = RemoteContent.Announcement.capture(current, item);
            if (item.active(now) && verification.announcement(replacement).ready())
                continue;
            @Nullable RemoteContent.Announcement fallback = usableFallback(saved.get(item.id()), replacement, now, verification);
            @Nullable RemoteContent.Item old = previousItems.get(item.id());
            if (prior != null && old != null) {
                @Nullable RemoteContent.Announcement candidate =
                        usableFallback(RemoteContent.Announcement.capture(prior, old), replacement, now, verification);
                if (candidate != null && (fallback == null || candidate.item().active(now) || !fallback.item().active(now)))
                    fallback = captureFallbackLinks(candidate, prior);
            }
            if (fallback != null)
                fallbacks.put(item.id(), fallback);
        }
        return Map.copyOf(fallbacks);
    }

    /// Validates an unexpired cached revision that differs from its replacement.
    private @Nullable RemoteContent.Announcement usableFallback(@Nullable RemoteContent.Announcement candidate,
            RemoteContent.Announcement replacement, Instant now, Verification verification) {
        if (candidate == null || candidate.item().expired(now)
                || (candidate.item().equals(replacement.item()) && candidate.files().equals(replacement.files())))
            return null;
        return verification.announcement(candidate).ready() ? candidate : null;
    }

    /// Retains the optional hyperlink targets from the original manifest when taking a fallback snapshot.
    private RemoteContent.Announcement captureFallbackLinks(RemoteContent.Announcement candidate, RemoteContent.Manifest prior) {
        try {
            return new RemoteContent.Announcement(candidate.item(), candidate.files(),
                    ContentDocuments.linkedFiles(candidate, prior, store, config.endpoint()));
        } catch (IOException e) {
            return candidate;
        }
    }

    /// Writes current metadata and its fallback index before any files can be garbage-collected.
    private void persistCache() throws IOException {
        if (cacheDirty && cache != null) {
            cacheVersion = store.publishCache(cache, cacheVersion, java.util.Objects.requireNonNull(usage));
            cacheDirty = false;
        }
    }

    /// Whether local maintenance succeeded, needs a local retry, or found a competing publication.
    private enum CleanupOutcome {
        /// Maintenance completed or there is no eligible cache to maintain.
        COMPLETE,
        /// Maintenance failed locally and uses its own retry budget.
        FAILED,
        /// The persisted cache version changed, requiring synchronization from the new baseline.
        CACHE_CHANGED
    }

    /// Retains current resources and files still owned by an available fallback revision.
    private CleanupOutcome cleanup(Verification verification, int attempt) {
        if (publicationBlocked)
            return CleanupOutcome.COMPLETE;
        String operation = "保存缓存索引";
        try {
            @Nullable RemoteContent.Manifest current = manifest;
            @Nullable ContentStore.Usage registered = usage;
            if (current == null || registered == null)
                return CleanupOutcome.COMPLETE;
            Instant now = Instant.now();
            Set<RemoteContent.Resource> keep = new HashSet<>(current.needed(now));
            if (cache != null) {
                @Unmodifiable Map<String, RemoteContent.Announcement> fallbacks =
                        selectAnnouncementFallbacks(current, null, now, verification);
                if (!fallbacks.equals(cache.announcementFallbacks())) {
                    cache = cache.withAnnouncementFallbacks(fallbacks);
                    cacheDirty = true;
                }
                ContentStore.Cache compacted = ContentStore.compacted(cache, config.instanceKey(), now);
                if (compacted != cache) {
                    cache = compacted;
                    cacheDirty = true;
                }
                persistCache();
                for (var fallback : fallbacks.values()) {
                    keep.addAll(fallback.files().values());
                    keep.addAll(fallback.linkedFiles().values());
                }
            }
            @Nullable RemoteContent.Manifest old = previous;
            if (old != null && !success) {
                for (RemoteContent.Kind kind : RemoteContent.Kind.values()) {
                    if (kind == RemoteContent.Kind.ANNOUNCEMENTS)
                        continue;
                    for (RemoteContent.Item item : old.items(kind)) {
                        boolean retained = current.items(kind).stream()
                                .anyMatch(next -> next.id().equals(item.id()) && !next.expired(now));
                        if (retained && !item.expired(now)) {
                            for (String path : item.files())
                                keep.add(old.files().get(path));
                        }
                    }
                }
            }
            operation = "清理缓存文件";
            store.prune(Set.copyOf(keep), cacheVersion, registered);
            cleanupError = null;
            cancelCleanupRetry();
        } catch (ContentStore.CacheChangedException e) {
            cancelCleanupRetry();
            cleanupError = null;
            lastResult = null;
            clearCacheState();
            verification.clear();
            try {
                reloadCache();
            } catch (IOException failure) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot reload the instance content cache", failure);
            }
            return CleanupOutcome.CACHE_CHANGED;
        } catch (IOException | RuntimeException e) {
            cleanupError = operation + "失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            if (attempt < CLEANUP_RETRY_SECONDS.length)
                retryCleanup(attempt);
            else
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(cleanupError, e);
            return CleanupOutcome.FAILED;
        }
        return CleanupOutcome.COMPLETE;
    }

    /// Delivers ordered snapshots; cancellation checks prevent old selection generations from updating the UI.
    private void emit(long token, Verification verification, boolean pending, @Nullable String error) {
        if (worker.isShutdown() || generation != token)
            return;
        @Nullable RemoteContent.Manifest current = manifest;
        Instant now = Instant.now();
        Set<String> ready = new HashSet<>();
        if (current != null) {
            for (RemoteContent.Item item : current.announcements()) {
                if (!item.expired(now) && verification.announcement(RemoteContent.Announcement.capture(current, item)).ready())
                    ready.add(item.id());
            }
        }
        @Unmodifiable Map<String, RemoteContent.Announcement> fallbacks = current == null || cache == null ? Map.of()
                : selectAnnouncementFallbacks(current, null, now, verification);
        if (!worker.isShutdown() && generation == token) {
            lastResult = new Result(current, fallbacks, ready, !checked, pending, success,
                    cache == null ? null : cache.checkedAt(), error, failures > retrySeconds.length, publicationBlocked,
                    cleanupError, current != null && verification.agreementReady(current));
            listener.accept(lastResult);
        }
    }

    /// Conditional manifest request is the only network work when all referenced files are unchanged.
    private ContentStore.Cache downloadManifest(long token) throws IOException {
        @Nullable ContentStore.Cache old = cache;
        Response response = download(config.endpoint().resolve("manifest.json"), old == null ? "" : old.etag(), 2_097_152, token);
        if (response.notModified()) {
            if (old == null)
                throw new IOException("HTTP 304 without cached manifest");
            return new ContentStore.Cache(old.body(), old.etag(), Instant.now());
        }
        return new ContentStore.Cache(new String(response.bytes(), StandardCharsets.UTF_8), response.etag(), Instant.now());
    }

    /// Resolves an indexed public resource without interpreting logical paths as URL query syntax.
    private URI resourceUri(RemoteContent.Resource resource) throws IOException {
        if (!resource.url().isEmpty())
            return URI.create(resource.url());
        try {
            return config.endpoint().resolve(new URI(null, null, resource.path(), null));
        } catch (URISyntaxException e) {
            throw new IOException("Invalid resource URL", e);
        }
    }

    /// Bounded response bytes and optional ETag from an explicit non-redirected endpoint.
    @NotNullByDefault
    private record Response(byte @Unmodifiable [] bytes, String etag, boolean notModified) { }

    /// Downloads one resource with finite timeouts; TLS downgrades and untracked redirects are rejected.
    private Response download(URI uri, String etag, int limit, long token) throws IOException {
        checkActive(token);
        RemoteContent.validateHttp(uri);
        HttpURLConnection request = (HttpURLConnection) URI.create(uri.toASCIIString()).toURL().openConnection();
        connection = request;
        request.setConnectTimeout(5000);
        request.setReadTimeout(8000);
        request.setInstanceFollowRedirects(false);
        request.setRequestProperty("Cache-Control", "no-cache");
        if (!etag.isEmpty())
            request.setRequestProperty("If-None-Match", etag);
        try {
            int status = request.getResponseCode();
            if (status == 304)
                return new Response(new byte[0], etag, true);
            if (status != 200)
                throw new IOException("HTTP " + status);
            try (var input = request.getInputStream()) {
                byte @Unmodifiable [] bytes = input.readNBytes(limit + 1);
                if (bytes.length > limit)
                    throw new IOException("Response exceeds size limit");
                @Nullable String validator = request.getHeaderField("ETag");
                return new Response(bytes, validator == null ? "" : validator, false);
            }
        } finally {
            request.disconnect();
            connection = null;
        }
    }

    /// Rejects obsolete work before another request or filesystem mutation is issued.
    private synchronized void checkActive(long token) {
        if (!active || generation != token || worker.isShutdown())
            throw new CancellationException();
    }

    /// Stops requests and removes all scheduled retries for this instance.
    @Override
    public synchronized void close() {
        pause();
        cancelCleanupRetry();
        worker.shutdownNow();
        @Nullable ContentStore.Usage registered = usage;
        usage = null;
        if (registered != null) {
            try {
                registered.close();
            } catch (IOException e) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot release instance content cache", e);
            }
        }
    }
}
