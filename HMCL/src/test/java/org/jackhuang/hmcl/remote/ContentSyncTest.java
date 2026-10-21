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

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises real loopback HTTP, failure-only retries, ETag reuse and source-specific offline fallback.
@NotNullByDefault
final class ContentSyncTest {
    /// Isolated client installation.
    @TempDir
    Path directory;
    /// Bound loopback test server, closed after each test.
    private @Nullable HttpServer server;

    /// Binds an ephemeral loopback port without accessing the internet.
    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    /// Releases the local socket.
    @AfterEach
    void stopServer() {
        if (server != null)
            server.stop(0);
    }

    /// Successful resources stop at one request while only the failed endpoint consumes three retries.
    @Test
    void retriesFailuresOnlyAndStopsAfterSuccess() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        AtomicInteger agreements = new AtomicInteger();
        AtomicInteger notices = new AtomicInteger();
        AtomicInteger announcements = new AtomicInteger();
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if (path.endsWith("agreement.json")) {
                agreements.incrementAndGet();
                body = RemoteContentTest.AGREEMENT;
            } else if (path.endsWith("notices.json")) {
                status = notices.incrementAndGet() < 4 ? 503 : 200;
                body = "{\"schemaVersion\":1,\"notices\":[]}";
            } else {
                announcements.incrementAndGet();
                body = "{\"schemaVersion\":1,\"announcements\":[]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        http.start();
        URI source = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/");
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(new ContentSync.Config(true, source),
                new ContentStore(directory, source.toString()), events::add, new long[]{0, 0, 0})) {
            sync.start();
            for (int i = 0; i < 6; i++)
                assertNotNull(events.poll(5, TimeUnit.SECONDS));
            assertNull(events.poll(300, TimeUnit.MILLISECONDS));
            assertEquals(1, agreements.get());
            assertEquals(4, notices.get());
            assertEquals(1, announcements.get());
            sync.start();
            assertNull(events.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    /// Failures are finite and report last-good cache while retaining the original fetch timestamp.
    @Test
    void exhaustsRetriesAndUsesOfflineCache() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        http.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        http.start();
        URI source = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/");
        var store = new ContentStore(directory, source.toString());
        Instant cachedAt = Instant.parse("2026-10-20T00:00:00Z");
        store.cache(RemoteContent.Kind.AGREEMENT, new ContentStore.Cache(RemoteContentTest.AGREEMENT, "\"v1\"", cachedAt));
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(new ContentSync.Config(true, source), store, events::add, new long[]{0, 0, 0})) {
            sync.start();
            int exhausted = 0;
            for (int i = 0; i < 12; i++) {
                var event = events.poll(5, TimeUnit.SECONDS);
                assertNotNull(event);
                assertNotNull(event.error());
                if (event.exhausted())
                    exhausted++;
                if (event.kind() == RemoteContent.Kind.AGREEMENT) {
                    assertTrue(event.cached());
                    assertEquals(cachedAt, event.fetchedAt());
                    assertEquals(1, event.items().size());
                }
            }
            assertEquals(3, exhausted);
            assertNull(events.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    /// A validated HTTP 304 is successful and retains cached text instead of demanding a response body.
    @Test
    void revalidatesEtagAndManualRefreshesSuccesses() throws Exception {
        HttpServer http = java.util.Objects.requireNonNull(server);
        AtomicInteger validated = new AtomicInteger();
        http.createContext("/", exchange -> {
            if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                validated.incrementAndGet();
                exchange.sendResponseHeaders(304, -1);
            } else {
                String name = exchange.getRequestURI().getPath().contains("notices") ? "notices" : "announcements";
                byte[] body = ("{\"schemaVersion\":1,\"" + name + "\":[]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        http.start();
        URI source = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/");
        var store = new ContentStore(directory, source.toString());
        store.cache(RemoteContent.Kind.AGREEMENT, new ContentStore.Cache(RemoteContentTest.AGREEMENT, "\"v1\"", Instant.EPOCH));
        var events = new LinkedBlockingQueue<ContentSync.Result>();
        try (var sync = new ContentSync(new ContentSync.Config(true, source), store, events::add, new long[]{})) {
            sync.start();
            for (int i = 0; i < 3; i++) {
                var event = events.poll(5, TimeUnit.SECONDS);
                assertNotNull(event);
                assertNull(event.error());
                assertFalse(event.cached());
            }
            sync.refresh();
            for (int i = 0; i < 3; i++)
                assertNotNull(events.poll(5, TimeUnit.SECONDS));
            assertEquals(2, validated.get());
        }
    }
}
