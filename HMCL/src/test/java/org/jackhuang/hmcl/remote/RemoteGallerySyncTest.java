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
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Real loopback HTTP verifies request counts, digest reuse, offline behavior, revocation and dynamic API freshness.
@NotNullByDefault
final class RemoteGallerySyncTest {
    /// Isolated launcher-global storage.
    @TempDir
    Path root;
    /// Test listener, allocated per test.
    private @Nullable HttpServer server;
    /// Current public manifest response.
    private final AtomicReference<String> body = new AtomicReference<>("{\"schemaVersion\":1}");
    /// Manifest status, switched to simulate an offline service.
    private final AtomicInteger manifestStatus = new AtomicInteger(200);
    /// Image API status, independent from the static resource host.
    private final AtomicInteger apiStatus = new AtomicInteger(200);
    /// Managed static payloads indexed by request path.
    private final Map<String, byte[]> payloads = new ConcurrentHashMap<>();
    /// Counts individual HTTP requests, including conditional manifest requests and redirects.
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    /// Process-lifetime API outcomes shared by refreshes and replacement synchronizers.
    private final Map<String, Optional<String>> apiImages = new ConcurrentHashMap<>();
    /// Delivered final snapshots.
    private final LinkedBlockingQueue<RemoteGallerySync.Result<String>> results = new LinkedBlockingQueue<>();

    /// Different manifests share identical SHA-256 image bytes; withdrawal only removes that manifest's references.
    @Test
    void sharesImagesAcrossFullManifestUrlsWithoutDeletingOtherReferences() throws Exception {
        body.set(pool("backgrounds/shared.png").toString());
        var manifest = OverallContent.parse(body.get());
        GalleryCache cache = new GalleryCache(repository());
        try (var first = sync(apiImages)) {
            first.refresh();
            assertNotNull(await().background());
            URI secondAddress = URI.create(manifestUrl() + "?channel=second");
            try (var second = new RemoteGallerySync<>(repository(), secondAddress, RemoteGallerySyncTest::decode, results::add, apiImages)) {
                second.refresh();
                assertNotNull(await().background());
                assertEquals(1, calls("/overall/backgrounds/shared.png"));
                assertNotEquals(cache.state(manifestUrl()), cache.state(secondAddress));
                body.set("{\"schemaVersion\":1,\"background\":{\"enabled\":false}}");
                first.refresh();
                assertNull(await().background());
                assertNotNull(cache.file(manifest.files().get("backgrounds/shared.png")));
            }
        }
    }

    /// Starts a local listener; no internet or game repository is required.
    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int count = requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            int status = 200;
            byte[] bytes;
            if (path.endsWith("manifest.json")) {
                String etag = "\"" + RemoteContent.hash(body.get()) + "\"";
                exchange.getResponseHeaders().add("ETag", etag);
                status = manifestStatus.get();
                if (status == 200 && etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match")))
                    status = 304;
                bytes = body.get().getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api")) {
                status = apiStatus.get();
                bytes = ("image:API-" + count).getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/redirect")) {
                status = 302;
                exchange.getResponseHeaders().add("Location", "/api");
                bytes = new byte[0];
            } else if (path.equals("/loop")) {
                status = 302;
                exchange.getResponseHeaders().add("Location", "/loop");
                bytes = new byte[0];
            } else {
                bytes = payloads.getOrDefault(path, new byte[0]);
                if (!payloads.containsKey(path))
                    status = 404;
            }
            if (status == 304 || status == 302) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    /// Releases the listener after each test.
    @AfterEach
    void cleanup() {
        if (server != null)
            server.stop(0);
    }

    /// Unchanged manifests perform one conditional check per cycle and never re-request complete static resources.
    @Test
    void unchangedManifestReusesEveryImageAndRepairsOnlyDamage() throws Exception {
        JsonObject json = pool("backgrounds/a.png", "backgrounds/b.jpg");
        body.set(json.toString());
        var manifest = OverallContent.parse(body.get());
        GalleryCache store = new GalleryCache(repository());
        try (var sync = sync(apiImages)) {
            sync.refresh();
            assertNotNull(await().background());
            assertEquals(1, calls("/overall/backgrounds/a.png"));
            assertEquals(1, calls("/overall/backgrounds/b.jpg"));
        }
        try (var sync = sync(new ConcurrentHashMap<>())) {
            sync.refresh();
            assertNotNull(await().background());
            assertEquals(2, calls("/overall/manifest.json"));
            assertEquals(1, calls("/overall/backgrounds/a.png"));
            assertEquals(1, calls("/overall/backgrounds/b.jpg"));
            Files.writeString(store.file(manifest.files().get("backgrounds/a.png")), "damaged");
            sync.refresh();
            await();
            assertEquals(2, calls("/overall/backgrounds/a.png"));
            assertEquals(1, calls("/overall/backgrounds/b.jpg"));
            Files.delete(store.file(manifest.files().get("backgrounds/b.jpg")));
            sync.refresh();
            await();
            assertEquals(2, calls("/overall/backgrounds/a.png"));
            assertEquals(2, calls("/overall/backgrounds/b.jpg"));
        }
    }

    /// A changed manifest downloads only changed bytes, while withdrawn resources are garbage-collected.
    @Test
    void changedManifestDownloadsOnlyChangedImage() throws Exception {
        JsonObject json = pool("backgrounds/a.png", "backgrounds/b.jpg");
        body.set(json.toString());
        var original = OverallContent.parse(body.get());
        GalleryCache store = new GalleryCache(repository());
        try (var sync = sync(apiImages)) {
            sync.refresh();
            await();
            byte[] changed = "image:changed".getBytes(StandardCharsets.UTF_8);
            payloads.put("/overall/backgrounds/b.jpg", changed);
            json.getAsJsonObject("files").getAsJsonObject("backgrounds/b.jpg")
                    .addProperty("sha256", RemoteContent.digest(changed));
            body.set(json.toString());
            sync.refresh();
            await();
            assertEquals(1, calls("/overall/backgrounds/a.png"));
            assertEquals(2, calls("/overall/backgrounds/b.jpg"));
            assertNotNull(store.file(original.files().get("backgrounds/b.jpg")));
            assertNotNull(store.file(original.files().get("backgrounds/a.png")));
        }
    }

    /// Offline startup uses verified remote cache, but an explicit empty response persists local fallback across restarts.
    @Test
    void offlineCacheAndAuthoritativeEmptyPoolAreDifferent() throws Exception {
        body.set(pool("backgrounds/a.png").toString());
        try (var sync = sync(apiImages)) {
            sync.refresh();
            assertNotNull(await().background());
        }
        manifestStatus.set(503);
        try (var sync = sync(apiImages)) {
            sync.refresh();
            var result = await();
            assertTrue(result.cached());
            assertNotNull(result.background());
            assertNotNull(result.error());
            manifestStatus.set(200);
            body.set("{\"schemaVersion\":1,\"background\":{\"images\":[],\"urls\":[]}}");
            sync.refresh();
            assertNull(await().background());
        }
        manifestStatus.set(503);
        try (var sync = sync(apiImages)) {
            sync.refresh();
            assertNull(await().background());
        }
        assertEquals(1, calls("/overall/backgrounds/a.png"));
    }

    /// New metadata must pass validation; an invalid replacement cannot remove the known-good offline background.
    @Test
    void invalidManifestAndImageFailuresPreserveUsableFallbacks() throws Exception {
        body.set(pool("backgrounds/a.png").toString());
        try (var sync = sync(apiImages)) {
            sync.refresh();
            await();
            body.set("{\"schemaVersion\":9}");
            sync.refresh();
            var result = await();
            assertTrue(result.cached());
            assertNotNull(result.background());
            assertNotNull(result.error());
        }
        body.set(pool("backgrounds/a.png", "backgrounds/missing.png").toString());
        payloads.remove("/overall/backgrounds/missing.png");
        try (var sync = sync(apiImages)) {
            sync.refresh();
            var result = await();
            assertNotNull(result.background());
            assertNotNull(result.error());
        }
    }

    /// Each API address is fetched at most once per process, including manual refresh; a new process fetches fresh bytes.
    @Test
    void redirectingApiIsFreshOnlyOncePerProcess() throws Exception {
        body.set(api("/redirect"));
        try (var sync = sync(apiImages)) {
            sync.refresh();
            assertEquals("image:API-1", await().background());
            sync.refresh();
            assertEquals("image:API-1", await().background());
        }
        try (var sync = sync(apiImages)) {
            sync.refresh();
            assertEquals("image:API-1", await().background());
        }
        assertEquals(1, calls("/redirect"));
        assertEquals(1, calls("/api"));
        try (var sync = sync(new ConcurrentHashMap<>())) {
            sync.refresh();
            assertEquals("image:API-2", await().background());
        }
        assertEquals(2, calls("/api"));
    }

    /// Failed APIs are not retried repeatedly, redirect loops terminate and local fallback remains usable.
    @Test
    void brokenApiReturnsLocalFallbackWithoutRepeatedRequests() throws Exception {
        body.set(api("/api"));
        apiStatus.set(503);
        try (var sync = sync(apiImages)) {
            sync.refresh();
            assertNull(await().background());
            sync.refresh();
            assertNull(await().background());
            assertEquals(1, calls("/api"));
        }
        body.set(api("/loop"));
        try (var sync = sync(apiImages)) {
            sync.refresh();
            assertNull(await().background());
            assertEquals(6, calls("/loop"));
        }
    }

    /// A different server base cannot accidentally display a previous server's cached manifest.
    @Test
    void sourceChangeDoesNotReuseAnotherServersManifest() throws Exception {
        body.set(pool("backgrounds/a.png").toString());
        try (var sync = sync(apiImages)) {
            sync.refresh();
            await();
        }
        manifestStatus.set(503);
        URI different = manifestUrl().resolve("/different/manifest.json");
        try (var sync = new RemoteGallerySync<>(repository(), different, RemoteGallerySyncTest::decode, results::add, apiImages)) {
            sync.refresh();
            assertNull(await().background());
        }
    }

    /// Gallery URLs are explicit HTTPS manifest addresses rather than independent local connection files.
    @Test
    void rejectsInvalidGalleryAddressWithoutContactingServer() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteGallerySync<>(repository(),
                URI.create("http://example.com/manifest.json"), RemoteGallerySyncTest::decode, results::add, apiImages));
        assertTrue(requests.isEmpty());
    }

    /// Creates a process session with an injectable API-attempt history.
    private RemoteGallerySync<String> sync(Map<String, Optional<String>> apis) {
        return new RemoteGallerySync<>(repository(), manifestUrl(), RemoteGallerySyncTest::decode, results::add, apis);
    }

    /// Rejects fake HTML/JSON bytes while keeping the HTTP tests independent of JavaFX initialization.
    private static String decode(byte @Unmodifiable [] bytes, String name) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.startsWith("image:"))
            throw new IOException("Invalid image response: " + name);
        return text;
    }

    /// Full manifest address entered in the background option.
    private URI manifestUrl() {
        return URI.create("http://127.0.0.1:" + Objects.requireNonNull(server).getAddress().getPort() + "/overall/manifest.json");
    }

    /// Uses the same common cache repository root for every simulated process in this test.
    private CacheRepository repository() {
        CacheRepository repository = new CacheRepository();
        repository.changeDirectory(root);
        return repository;
    }

    /// Waits for a final worker result, failing finitely if synchronization stalls.
    private RemoteGallerySync.Result<String> await() throws InterruptedException {
        return Objects.requireNonNull(results.poll(15, TimeUnit.SECONDS), "No overall sync result");
    }

    /// Counts network requests for a specific path.
    private int calls(String path) {
        return requests.getOrDefault(path, new AtomicInteger()).get();
    }

    /// Builds a managed image pool and its server payloads.
    private JsonObject pool(String @Unmodifiable ... names) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        JsonObject background = new JsonObject();
        JsonArray images = new JsonArray();
        JsonObject files = new JsonObject();
        for (String name : names) {
            images.add(name);
            byte[] bytes = ("image:" + name).getBytes(StandardCharsets.UTF_8);
            payloads.put("/overall/" + name, bytes);
            JsonObject descriptor = new JsonObject();
            descriptor.addProperty("sha256", RemoteContent.digest(bytes));
            files.add(name, descriptor);
        }
        background.add("images", images);
        json.add("background", background);
        json.add("files", files);
        return json;
    }

    /// Builds an API-only overall manifest using the ephemeral local endpoint.
    private String api(String path) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        JsonObject background = new JsonObject();
        JsonArray urls = new JsonArray();
        urls.add(manifestUrl().resolve(path).toString());
        background.add("urls", urls);
        json.add("background", background);
        return json.toString();
    }
}
