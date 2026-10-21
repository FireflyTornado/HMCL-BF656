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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// Synchronizes independent resources once, retrying failures only, with no periodic polling.
@NotNullByDefault
public final class ContentSync implements AutoCloseable {
    /// Production retry intervals in seconds following the initial request.
    private static final long @Unmodifiable [] RETRY_SECONDS = {15, 60, 180};
    /// Content source configuration, loaded beside the actual launcher artifact.
    private final Config config;
    /// Source-specific durable cache and receipts.
    private final ContentStore store;
    /// Receives results on worker threads; UI clients must marshal them to the FX thread.
    private final Consumer<Result> listener;
    /// Daemon workers allow independent downloads without preventing application exit.
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "BF656 content sync");
        thread.setDaemon(true);
        return thread;
    });
    /// Per-resource running, completion and retry state.
    private final EnumMap<RemoteContent.Kind, Resource> resources = new EnumMap<>(RemoteContent.Kind.class);
    /// Retry delays, injectable for deterministic integration tests.
    private final long @Unmodifiable [] retrySeconds;

    /// Configuration is opt-in; HTTPS is required except for loopback development servers.
    @NotNullByDefault
    public record Config(boolean enabled, URI baseUrl) {
        /// Reads installation-local settings; missing configuration leaves upstream behavior intact.
        public static Config load(Path directory) throws IOException {
            Path path = directory.resolve(".hmcl/remote-content.json");
            if (!Files.exists(path))
                return new Config(false, URI.create("https://example.com/launcher/"));
            try {
                if (Files.size(path) > 16_384)
                    throw new IOException("Remote content configuration is too large");
                JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                boolean enabled = json.get("enabled").getAsBoolean();
                URI base = URI.create(json.get("baseUrl").getAsString());
                validateBase(base);
                return new Config(enabled, base);
            } catch (RuntimeException e) {
                throw new IOException("远程内容配置无效：" + path, e);
            }
        }

        /// Rejects credential-bearing URLs, non-directory URLs and non-HTTPS public endpoints.
        public static void validateBase(URI base) throws IOException {
            boolean local = "http".equals(base.getScheme())
                    && ("localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost())
                    || "[::1]".equals(base.getHost()));
            if ((!"https".equals(base.getScheme()) && !local) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || base.getPath() == null || !base.getPath().endsWith("/"))
                throw new IOException("baseUrl 必须是以 / 结尾的 HTTPS 地址（本机测试允许 HTTP）");
        }
    }

    /// One attempt's parsed result; cached data is fallback data, never a successful synchronization.
    @NotNullByDefault
    public record Result(RemoteContent.Kind kind, @Unmodifiable List<RemoteContent.Item> items,
                         boolean cached, @Nullable Instant fetchedAt, @Nullable String error, boolean exhausted) { }

    /// Mutable scheduling state, accessed under this sync object's monitor.
    @NotNullByDefault
    private static final class Resource {
        /// Whether a request is currently executing.
        boolean running;
        /// Whether this resource synchronized successfully.
        boolean success;
        /// Number of attempts in the current automatic retry cycle.
        int attempts;
        /// Scheduled retry, if any.
        @Nullable ScheduledFuture<?> retry;
    }

    /// Creates a production synchronizer.
    public ContentSync(Config config, ContentStore store, Consumer<Result> listener) {
        this(config, store, listener, RETRY_SECONDS);
    }

    /// Creates a synchronizer with explicit retry intervals for tests.
    ContentSync(Config config, ContentStore store, Consumer<Result> listener, long @Unmodifiable [] retrySeconds) {
        this.config = config;
        this.store = store;
        this.listener = listener;
        this.retrySeconds = retrySeconds.clone();
        for (var kind : RemoteContent.Kind.values())
            resources.put(kind, new Resource());
    }

    /// Starts all three requests, exactly once unless explicitly refreshed.
    public void start() {
        for (var kind : RemoteContent.Kind.values())
            request(kind, false);
    }

    /// Explicitly refreshes all resources; automatic retries never invoke this method.
    public void refresh() {
        for (var kind : RemoteContent.Kind.values())
            request(kind, true);
    }

    /// Retries a failed agreement at the user's request, resetting its finite retry budget.
    public void retryAgreement() {
        request(RemoteContent.Kind.AGREEMENT, true);
    }

    /// Schedules a request while preventing duplicate or already-successful automatic requests.
    private synchronized void request(RemoteContent.Kind kind, boolean manual) {
        Resource resource = resources.get(kind);
        if (workers.isShutdown() || resource.running || (!manual && resource.success))
            return;
        if (manual) {
            if (resource.retry != null)
                resource.retry.cancel(false);
            resource.attempts = 0;
        }
        resource.running = true;
        workers.execute(() -> fetch(kind));
    }

    /// Fetches a bounded response, validates before caching, and reports errors with last-good fallback.
    private void fetch(RemoteContent.Kind kind) {
        @Nullable ContentStore.Cache cache = store.cache(kind);
        @Nullable ContentStore.Cache fresh = null;
        @Unmodifiable List<RemoteContent.Item> downloadedItems = List.of();
        boolean downloadedValid = false;
        Result result;
        try {
            fresh = download(kind, cache);
            downloadedItems = RemoteContent.parse(kind, fresh.body());
            downloadedValid = true;
            store.cache(kind, fresh);
            result = new Result(kind, downloadedItems, false, fresh.fetchedAt(), null, false);
        } catch (IOException | RuntimeException e) {
            @Unmodifiable List<RemoteContent.Item> fallback = List.of();
            if (cache != null) {
                try {
                    fallback = RemoteContent.parse(kind, cache.body());
                } catch (IOException ignored) { }
            }
            result = new Result(kind, fallback, cache != null, cache == null ? null : cache.fetchedAt(),
                    e.toString(), false);
            // A known new agreement must never fall back to older consent merely because cache writing failed.
            if (fresh != null && downloadedValid)
                result = new Result(kind, downloadedItems, false, fresh.fetchedAt(), e.toString(), false);
        }
        synchronized (this) {
            Resource resource = resources.get(kind);
            resource.running = false;
            resource.attempts++;
            resource.success = result.error() == null;
            boolean exhausted = resource.attempts > retrySeconds.length;
            result = new Result(result.kind(), result.items(), result.cached(), result.fetchedAt(), result.error(), exhausted);
            if (!workers.isShutdown())
                listener.accept(result);
            if (!resource.success && !exhausted && !workers.isShutdown()) {
                resource.retry = workers.schedule(() -> request(kind, false),
                        retrySeconds[resource.attempts - 1], TimeUnit.SECONDS);
            }
        }
    }

    /// Downloads UTF-8 JSON with ETag revalidation; redirects are rejected rather than downgrading HTTPS.
    private ContentStore.Cache download(RemoteContent.Kind kind, @Nullable ContentStore.Cache cache) throws IOException {
        var connection = (HttpURLConnection) config.baseUrl().resolve(kind.filename).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (cache != null && !cache.etag().isEmpty())
            connection.setRequestProperty("If-None-Match", cache.etag());
        try {
            int status = connection.getResponseCode();
            if (status == 304 && cache != null)
                return new ContentStore.Cache(cache.body(), cache.etag(), Instant.now());
            if (status != 200)
                throw new IOException("HTTP " + status + " (" + kind.filename + ")");
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(1_048_577);
                if (bytes.length > 1_048_576)
                    throw new IOException("Response exceeds 1 MiB");
                @Nullable String etag = connection.getHeaderField("ETag");
                return new ContentStore.Cache(new String(bytes, StandardCharsets.UTF_8),
                        etag == null ? "" : etag, Instant.now());
            }
        } finally {
            connection.disconnect();
        }
    }

    /// Cancels future retries and ignores late results after the launcher closes.
    @Override
    public void close() {
        workers.shutdownNow();
    }
}
