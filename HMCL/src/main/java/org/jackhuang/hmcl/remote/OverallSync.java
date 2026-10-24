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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// One launcher-wide background session: one manifest check per refresh, digest-based reuse and fresh image APIs.
@NotNullByDefault
public final class OverallSync<T> implements AutoCloseable {
    /// Serializes cache mutations when a configuration replacement briefly overlaps a closing session.
    private static final Map<Path, Object> ROOT_LOCKS = new ConcurrentHashMap<>();
    /// Global root beneath Metadata.HMCL_LOCAL_HOME, never a game version directory.
    private final Path root;
    /// Independent global source configuration.
    private final Config config;
    /// Reuses ownership-bounded resource installation and cleanup without the instance protocol schema.
    private final ContentStore store;
    /// Source-bound conditional manifest state.
    private final Path state;
    /// Image decoder, injectable for HTTP tests without a JavaFX toolkit.
    private final Decoder<T> decoder;
    /// Worker-thread result consumer; desktop callers marshal results to JavaFX.
    private final Consumer<Result<T>> listener;
    /// API successes and failures shared for this launcher process, including across explicit refreshes.
    private final Map<String, Optional<T>> apiImages;
    /// Shared lock for this global root across successive source configurations.
    private final Object rootLock;
    /// Serial background work prevents concurrent cache writes.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BF656 overall background");
        thread.setDaemon(true);
        return thread;
    });
    /// Refreshes and shutdown invalidate in-flight work before it can publish a stale result.
    private volatile long generation;
    /// Shutdown state is visible to the network worker.
    private volatile boolean closed;
    /// Current HTTP request is disconnected on refresh or shutdown.
    private volatile @Nullable HttpURLConnection connection;

    /// Local connection settings; no selected instance is consulted or required.
    @NotNullByDefault
    public record Config(boolean enabled, URI serverBaseUrl) {
        /// Reads .hmcl/overall/client/config.json; missing configuration leaves the normal background untouched.
        public static Config load(Path root) throws IOException {
            Path path = root.resolve("client/config.json");
            if (!Files.exists(path))
                return new Config(false, URI.create("https://example.com/launcher/"));
            try {
                if (Files.size(path) > 16_384)
                    throw new IOException("Overall client configuration exceeds 16 KiB");
                JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                Config config = new Config(RemoteContent.bool(json, "enabled", false),
                        URI.create(RemoteContent.string(json, "serverBaseUrl", "https://example.com/launcher/")));
                config.validate();
                return config;
            } catch (RuntimeException e) {
                throw new IOException("Invalid overall client configuration", e);
            }
        }

        /// Accepts a public HTTPS base directory, with loopback HTTP supported for local deployment tests.
        public void validate() {
            RemoteContent.validateHttp(serverBaseUrl);
            if (serverBaseUrl.getQuery() != null || serverBaseUrl.getFragment() != null
                    || !serverBaseUrl.getPath().endsWith("/"))
                throw new IllegalArgumentException("Overall serverBaseUrl must end with / and have no query or fragment");
        }

        /// Published overall directory next to the existing public instances directory.
        public URI endpoint() {
            return serverBaseUrl.resolve("overall/");
        }
    }

    /// Decodes image bytes and rejects HTML, JSON and unusable image responses.
    @NotNullByDefault
    @FunctionalInterface
    public interface Decoder<T> {
        /// Returns the decoded image or throws when this candidate cannot be displayed.
        T decode(byte @Unmodifiable [] bytes, String name) throws Exception;
    }

    /// Final update for one cycle; a null background requests normal local appearance.
    @NotNullByDefault
    public record Result<T>(@Nullable T background, boolean cached, @Nullable String error) { }

    /// Source-specific HTTP validator and validated manifest body.
    @NotNullByDefault
    private record Cache(String body, String etag) { }

    /// Bounded response bytes and HTTP validation metadata.
    @NotNullByDefault
    private record Response(byte @Unmodifiable [] bytes, String etag, boolean notModified) { }

    /// Creates an idle session, with API outcome storage owned by the launcher process.
    public OverallSync(Path root, Config config, Decoder<T> decoder, Consumer<Result<T>> listener,
                       Map<String, Optional<T>> apiImages) {
        config.validate();
        this.root = root.toAbsolutePath().normalize();
        rootLock = ROOT_LOCKS.computeIfAbsent(this.root, ignored -> new Object());
        this.config = config;
        this.decoder = decoder;
        this.listener = listener;
        this.apiImages = apiImages;
        store = new ContentStore(this.root, config.endpoint().toString());
        state = this.root.resolve("client/state").resolve(RemoteContent.hash(config.endpoint().toString()))
                .resolve("overall-sync.json");
    }

    /// Starts exactly one background cycle; this service never polls on page changes or instance selection.
    public synchronized void refresh() {
        if (closed)
            return;
        long token = ++generation;
        if (connection != null)
            connection.disconnect();
        worker.execute(() -> {
            synchronized (rootLock) {
                run(token);
            }
        });
    }

    /// Checks the manifest once, reuses verified resources, then chooses a usable random wallpaper.
    private void run(long token) {
        try {
            checkActive(token);
            if (!config.enabled()) {
                listener.accept(new Result<>(null, false, null));
                return;
            }
            @Nullable Cache cache = readCache();
            @Nullable OverallContent.Manifest manifest = cache == null ? null : OverallContent.parse(cache.body());
            boolean cached = true;
            List<String> errors = new ArrayList<>();
            try {
                Response response = download(config.endpoint().resolve("manifest.json"),
                        cache == null ? "" : cache.etag(), 2_097_152, false, token);
                if (response.notModified() && cache == null)
                    throw new IOException("HTTP 304 without a cached overall manifest");
                String body = response.notModified() ? java.util.Objects.requireNonNull(cache).body()
                        : new String(response.bytes(), StandardCharsets.UTF_8);
                OverallContent.Manifest next = OverallContent.parse(body);
                checkActive(token);
                manifest = next;
                cached = false;
                saveCache(new Cache(body, response.etag()));
            } catch (IOException | IllegalArgumentException e) {
                errors.add("Manifest: " + e.getMessage());
            }
            if (manifest == null) {
                deliver(new Result<>(null, true, String.join("\n", errors)), token);
                return;
            }
            for (RemoteContent.Resource resource : manifest.needed()) {
                checkActive(token);
                try {
                    if (!store.verified(resource)) {
                        byte[] bytes = download(resourceUri(resource), "", 16_777_216, true, token).bytes();
                        checkActive(token);
                        store.install(resource, bytes);
                    }
                } catch (IOException | IllegalArgumentException e) {
                    errors.add(resource.path() + ": " + e.getMessage());
                }
            }
            checkActive(token);
            @Nullable T image = choose(manifest, errors, token);
            checkActive(token);
            // A successfully received empty/disabled pool is authoritative: old images cannot revive on restart.
            try {
                store.prune(manifest.needed());
            } catch (IOException e) {
                errors.add("Cache cleanup: " + e.getMessage());
            }
            deliver(new Result<>(image, cached, errors.isEmpty() ? null : String.join("\n", errors)), token);
        } catch (CancellationException ignored) {
            // Refresh or shutdown owns the replacement result.
        } catch (Exception e) {
            if (!closed && generation == token)
                deliver(new Result<>(null, true, e.getMessage()), token);
        }
    }

    /// Randomizes once per cycle; failed candidates cannot hide other working cached images.
    private @Nullable T choose(OverallContent.Manifest manifest, List<String> errors, long token) {
        if (manifest.background().isEmpty())
            return null;
        List<String> candidates = new ArrayList<>(manifest.background().images());
        candidates.addAll(manifest.background().urls());
        Collections.shuffle(candidates);
        boolean attemptedApi = false;
        for (String name : candidates) {
            checkActive(token);
            try {
                if (manifest.background().images().contains(name)) {
                    RemoteContent.Resource resource = manifest.files().get(name);
                    if (store.verified(resource))
                        return decoder.decode(Files.readAllBytes(store.file(resource)), name);
                } else if (!attemptedApi) {
                    // Only one dynamic URL is attempted per choice; its outcome survives manual refreshes.
                    attemptedApi = true;
                    Optional<T> image = apiImages.computeIfAbsent(name, url -> {
                        try {
                            return Optional.of(decoder.decode(download(URI.create(url), "", 16_777_216, true, token).bytes(), url));
                        } catch (Exception e) {
                            if (!(e instanceof CancellationException))
                                LOG.warning("Couldn't load overall image API", e);
                            return Optional.empty();
                        }
                    });
                    if (image.isPresent())
                        return image.get();
                    errors.add("Image API unavailable");
                }
            } catch (Exception e) {
                if (e instanceof CancellationException cancelled)
                    throw cancelled;
                errors.add("Background: " + e.getMessage());
            }
        }
        return null;
    }

    /// Loads only validated, source-bound metadata; malformed state is treated as an empty cache.
    private @Nullable Cache readCache() {
        try {
            if (!Files.isRegularFile(state) || Files.size(state) > 12_582_912)
                return null;
            JsonObject json = JsonParser.parseString(Files.readString(state)).getAsJsonObject();
            String body = RemoteContent.string(json, "body", "");
            OverallContent.parse(body);
            return new Cache(body, RemoteContent.string(json, "etag", ""));
        } catch (IOException | RuntimeException e) {
            LOG.warning("Ignored invalid overall cache", e);
            return null;
        }
    }

    /// Saves validated metadata before payloads; empty pools remain authoritative even if later work fails.
    private void saveCache(Cache cache) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("body", cache.body());
        json.addProperty("etag", cache.etag());
        json.addProperty("checkedAt", Instant.now().toString());
        OverallContent.write(root, state, json.toString().getBytes(StandardCharsets.UTF_8));
        OverallContent.write(root, root.resolve("server/manifest.json"), cache.body().getBytes(StandardCharsets.UTF_8));
    }

    /// Resolves a managed relative path or an explicit fixed-digest CDN URL.
    private URI resourceUri(RemoteContent.Resource resource) throws IOException {
        if (!resource.url().isEmpty())
            return URI.create(resource.url());
        try {
            return config.endpoint().resolve(new URI(null, null, resource.path(), null));
        } catch (URISyntaxException e) {
            throw new IOException("Invalid overall resource URL", e);
        }
    }

    /// Fetches bounded bytes with finite timeouts; image URLs may follow at most five validated redirects.
    private Response download(URI initial, String etag, int limit, boolean redirects, long token) throws IOException {
        URI uri = initial;
        for (int hop = 0; hop <= 5; hop++) {
            checkActive(token);
            RemoteContent.validateHttp(uri);
            HttpURLConnection request = (HttpURLConnection) URI.create(uri.toASCIIString()).toURL().openConnection();
            connection = request;
            request.setConnectTimeout(5000);
            request.setReadTimeout(8000);
            request.setInstanceFollowRedirects(false);
            request.setUseCaches(false);
            request.setRequestProperty("Cache-Control", "no-cache");
            if (!etag.isEmpty())
                request.setRequestProperty("If-None-Match", etag);
            try {
                checkActive(token);
                int status = request.getResponseCode();
                if (status == 304)
                    return new Response(new byte[0], etag, true);
                if (redirects && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
                    @Nullable String location = request.getHeaderField("Location");
                    if (location == null)
                        throw new IOException("Image redirect has no Location");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200)
                    throw new IOException("Overall HTTP " + status);
                try (var input = request.getInputStream()) {
                    byte[] bytes = input.readNBytes(limit + 1);
                    if (bytes.length > limit)
                        throw new IOException("Overall response exceeds size limit");
                    return new Response(bytes, java.util.Objects.toString(request.getHeaderField("ETag"), ""), false);
                }
            } finally {
                request.disconnect();
                connection = null;
            }
        }
        throw new IOException("Too many overall image redirects");
    }

    /// Rejects stale callbacks after refreshes and shutdown.
    private void deliver(Result<T> result, long token) {
        checkActive(token);
        listener.accept(result);
    }

    /// Prevents obsolete cycles from installing files, making requests or publishing results.
    private void checkActive(long token) {
        if (closed || generation != token || Thread.currentThread().isInterrupted())
            throw new CancellationException();
    }

    /// Stops network work without touching the user's local configuration or background images.
    @Override
    public synchronized void close() {
        closed = true;
        generation++;
        if (connection != null)
            connection.disconnect();
        worker.shutdownNow();
    }
}
