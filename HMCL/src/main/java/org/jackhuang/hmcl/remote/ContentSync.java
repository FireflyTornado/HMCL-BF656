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
    /// A metadata write must complete before managed file cleanup can use the new state.
    private boolean cacheDirty;
    /// Whether the manifest has been checked successfully during this process session.
    private boolean checked;
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

    /// Carries current metadata and the announcement revisions whose payloads passed background verification.
    @NotNullByDefault
    public record Result(@Nullable RemoteContent.Manifest manifest,
                         @Unmodifiable Map<String, RemoteContent.Announcement> announcementFallbacks,
                         @Unmodifiable Set<String> readyAnnouncements,
                         boolean cached, boolean pending,
                         boolean success, @Nullable Instant checkedAt, @Nullable String error, boolean exhausted) {
        /// Detaches the presentation state from the mutable verification run.
        public Result {
            announcementFallbacks = Map.copyOf(announcementFallbacks);
            readyAnnouncements = Set.copyOf(readyAnnouncements);
        }

        /// Creates a result for callers that have no announcement fallbacks.
        public Result(@Nullable RemoteContent.Manifest manifest, boolean cached, boolean pending, boolean success,
                      @Nullable Instant checkedAt, @Nullable String error, boolean exhausted) {
            this(manifest, Map.of(), Set.of(), cached, pending, success, checkedAt, error, exhausted);
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
    public void prune() {
        if (!worker.isShutdown())
            worker.execute(() -> cleanup(new HashMap<>()));
    }

    /// Executes one attempt, continuing past individual file failures so unrelated items can still become ready.
    private void run(long token) {
        Map<String, Boolean> verification = new HashMap<>();
        StringBuilder errors = new StringBuilder();
        try {
            checkActive(token);
            if (cache == null) {
                cache = store.cache(config.instanceKey());
                if (cache != null) {
                    manifest = RemoteContent.parse(cache.body(), config.instanceKey());
                    cacheDirty = cache.fallbackIndexNeedsRewrite();
                }
            }
            emit(token, verification, true, null);
            if (!checked) {
                ContentStore.Cache fresh = downloadManifest(token);
                RemoteContent.Manifest next = RemoteContent.parse(fresh.body(), config.instanceKey());
                checkActive(token);
                @Unmodifiable Map<String, RemoteContent.Announcement> fallbacks =
                        selectAnnouncementFallbacks(next, manifest, Instant.now(), verification);
                checkActive(token);
                if (!next.equals(manifest))
                    previous = manifest;
                manifest = next;
                cache = fresh.withAnnouncementFallbacks(fallbacks);
                cacheDirty = true;
                persistCache();
                checked = true;
                emit(token, verification, true, null);
            }
            RemoteContent.Manifest current = java.util.Objects.requireNonNull(manifest);
            for (RemoteContent.Resource resource : current.needed(Instant.now())) {
                checkActive(token);
                try {
                    String name = resource.cacheName();
                    @Nullable Boolean verified = verification.get(name);
                    if (verified == null) {
                        verified = store.verified(resource);
                        verification.put(name, verified);
                    }
                    if (!verified) {
                        byte[] bytes = download(resourceUri(resource), "", 16_777_216, token).bytes();
                        checkActive(token);
                        store.install(resource, bytes);
                        verification.put(name, true);
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
                    boolean ready = kind == RemoteContent.Kind.ANNOUNCEMENTS
                            ? verifyAnnouncement(item, current.files(), verification) : store.ready(current, item);
                    if (ready) {
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
            if (success) {
                previous = null;
            } else {
                failures++;
            }
            cleanup(verification);
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

    /// Checks each cache object at most once per attempt, including when revisions share the same bytes.
    private boolean verifyResource(RemoteContent.Resource resource, Map<String, Boolean> verification) {
        return verification.computeIfAbsent(resource.cacheName(), ignored -> {
            try {
                return store.verified(resource);
            } catch (IOException e) {
                return false;
            }
        });
    }

    /// Checks the body and all declared resources against the index for this exact announcement revision.
    private boolean verifyAnnouncement(RemoteContent.Item item, @Unmodifiable Map<String, RemoteContent.Resource> files,
                                       Map<String, Boolean> verification) {
        return item.files().stream().allMatch(path -> verifyResource(files.get(path), verification));
    }

    /// Keeps verified, unexpired revisions while replacements download, including revisions scheduled for later.
    private @Unmodifiable Map<String, RemoteContent.Announcement> selectAnnouncementFallbacks(
            RemoteContent.Manifest current, @Nullable RemoteContent.Manifest prior, Instant now, Map<String, Boolean> verification) {
        Map<String, RemoteContent.Item> previousItems = new HashMap<>();
        if (prior != null) {
            for (RemoteContent.Item item : prior.announcements())
                previousItems.put(item.id(), item);
        }
        @Unmodifiable Map<String, RemoteContent.Announcement> saved = cache == null ? Map.of() : cache.announcementFallbacks();
        Map<String, RemoteContent.Announcement> fallbacks = new HashMap<>();
        for (RemoteContent.Item item : current.announcements()) {
            if (item.expired(now) || verifyAnnouncement(item, current.files(), verification))
                continue;
            @Nullable RemoteContent.Item old = previousItems.get(item.id());
            if (prior != null && old != null && !old.expired(now) && verifyAnnouncement(old, prior.files(), verification)) {
                fallbacks.put(item.id(), RemoteContent.Announcement.capture(prior, old));
            } else {
                @Nullable RemoteContent.Announcement cached = saved.get(item.id());
                if (cached != null && !cached.item().expired(now) && verifyAnnouncement(cached.item(), cached.files(), verification))
                    fallbacks.put(item.id(), cached);
            }
        }
        return Map.copyOf(fallbacks);
    }

    /// Writes current metadata and its fallback index before any files can be garbage-collected.
    private void persistCache() throws IOException {
        if (cacheDirty && cache != null) {
            store.cache(cache);
            cacheDirty = false;
        }
    }

    /// Retains current resources and files still owned by an available fallback revision.
    private void cleanup(Map<String, Boolean> verification) {
        try {
            @Nullable RemoteContent.Manifest current = manifest;
            if (current == null)
                return;
            Instant now = Instant.now();
            Set<RemoteContent.Resource> keep = new HashSet<>(current.needed(now));
            if (cache != null) {
                @Unmodifiable Map<String, RemoteContent.Announcement> fallbacks =
                        selectAnnouncementFallbacks(current, null, now, verification);
                if (!fallbacks.equals(cache.announcementFallbacks())) {
                    cache = cache.withAnnouncementFallbacks(fallbacks);
                    cacheDirty = true;
                }
                persistCache();
                for (var fallback : fallbacks.values())
                    keep.addAll(fallback.files().values());
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
            store.prune(Set.copyOf(keep));
            if (cache != null)
                cache = store.compact(cache, config.instanceKey(), now);
        } catch (IOException | RuntimeException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot prune instance content", e);
        }
    }

    /// Delivers ordered snapshots; cancellation checks prevent old selection generations from updating the UI.
    private void emit(long token, Map<String, Boolean> verification, boolean pending, @Nullable String error) {
        if (worker.isShutdown() || generation != token)
            return;
        @Nullable RemoteContent.Manifest current = manifest;
        Instant now = Instant.now();
        Set<String> ready = new HashSet<>();
        if (current != null) {
            for (RemoteContent.Item item : current.announcements()) {
                if (!item.expired(now) && verifyAnnouncement(item, current.files(), verification))
                    ready.add(item.id());
            }
        }
        @Unmodifiable Map<String, RemoteContent.Announcement> fallbacks = current == null || cache == null ? Map.of()
                : selectAnnouncementFallbacks(current, null, now, verification);
        if (!worker.isShutdown() && generation == token)
            listener.accept(new Result(current, fallbacks, ready, !checked, pending, success,
                    cache == null ? null : cache.checkedAt(), error, failures > retrySeconds.length));
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
                byte[] bytes = input.readNBytes(limit + 1);
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
        worker.shutdownNow();
    }
}
