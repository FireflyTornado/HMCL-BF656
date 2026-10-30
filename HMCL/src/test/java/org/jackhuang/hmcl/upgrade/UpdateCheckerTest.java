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
package org.jackhuang.hmcl.upgrade;

import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.bf656.BF656Metadata;
import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Guards BF656 release comparisons, disabled checks, and the update manifest wire protocol.
@NotNullByDefault
public final class UpdateCheckerTest {
    /// Stable BF656 upgrades depend only on the BF656 release, including numeric multi-digit ordering.
    @Test
    public void stableUpdatesUseTheIndependentReleaseVersion() {
        assertTrue(UpdateChecker.isOutdated("0.3.11", UpdateChannel.STABLE, release("0.3.12")));
        assertTrue(UpdateChecker.isOutdated("0.3.9", UpdateChannel.STABLE, release("0.3.11")));
        assertFalse(UpdateChecker.isOutdated("0.3.11", UpdateChannel.STABLE, release("0.3.11")));
        assertFalse(UpdateChecker.isOutdated("0.3.11", UpdateChannel.STABLE, release("0.3.10")));
        assertFalse(UpdateChecker.isOutdated("0.3.11", UpdateChannel.STABLE, null));
    }

    /// Development filtering applies to the BF656 version, while forced and cross-channel choices remain possible.
    @Test
    public void preservesExplicitReleaseAndChannelChoices() {
        var forced = new RemoteVersion(UpdateChannel.STABLE, "0.3.10", "https://example.com/launcher.jar",
                RemoteVersion.Type.JAR, new IntegrityCheck("SHA-1", "0".repeat(40)), false, true);
        assertTrue(UpdateChecker.isOutdated("0.3.11", UpdateChannel.STABLE, forced));
        assertFalse(UpdateChecker.isOutdated("0.3.10", UpdateChannel.STABLE, forced));
        assertTrue(UpdateChecker.isOutdated("0.3.11", UpdateChannel.DEVELOPMENT, release("0.3.10")));
        assertFalse(UpdateChecker.isOutdated("0.3.SNAPSHOT", UpdateChannel.STABLE, release("0.3.12")));
        assertFalse(UpdateChecker.isOutdated("@develop@", UpdateChannel.STABLE, forced));
    }

    /// Without a source, both entry points return before JavaFX scheduling, settings access, or signature checks.
    @Test
    public void disabledChecksDoNoWork() {
        assumeFalse(UpdateChecker.isEnabled(), "This distribution has an explicit update source");
        UpdateChecker.init();
        UpdateChecker.requestCheckUpdate(UpdateChannel.STABLE, false);
        assertFalse(UpdateChecker.isCheckingUpdate());
        assertFalse(UpdateChecker.isOutdated());
        assertNull(UpdateChecker.getLatestVersion());
        assertNull(UpdateChecker.errorProperty().get());
    }

    /// Existing endpoint parameters are preserved and the protocol uses the custom version and selected channel.
    @Test
    public void sendsBf656VersionAndPreviewChannel() {
        String url = UpdateChecker.buildUpdateUrl("https://example.com/update.json?release=bf656",
                UpdateChannel.DEVELOPMENT, true);
        assertTrue(url.contains("release=bf656"));
        assertTrue(url.contains("version=" + BF656Metadata.VERSION));
        assertTrue(url.contains("channel=dev-preview"));
        assertThrows(IllegalArgumentException.class,
                () -> UpdateChecker.buildUpdateUrl("", UpdateChannel.STABLE, false));
    }

    /// A local endpoint sees BF656 request parameters and its response remains compatible with the existing downloader.
    @Test
    public void fetchesManifestFromAnExplicitLocalSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> request = new AtomicReference<>("");
        server.createContext("/update.json", exchange -> {
            try (exchange) {
                request.set(exchange.getRequestURI().toString());
                byte[] response = ("""
                        {"version":"0.3.12","jar":"https://example.com/launcher.jar",
                        "jarsha1":"%s","force":false}
                        """.formatted("0".repeat(40))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            String source = "http://127.0.0.1:" + server.getAddress().getPort() + "/update.json";
            String url = UpdateChecker.buildUpdateUrl(source, UpdateChannel.STABLE, false);
            RemoteVersion latest = RemoteVersion.fetch(UpdateChannel.STABLE, false, url);
            assertTrue(request.get().contains("version=" + BF656Metadata.VERSION));
            assertTrue(request.get().contains("channel=stable"));
            assertEquals("0.3.12", latest.version());
            assertEquals("https://example.com/launcher.jar", latest.url());
            assertFalse(latest.force());
        } finally {
            server.stop(0);
        }
    }

    /// Creates a release manifest without touching the network.
    private static RemoteVersion release(String version) {
        return new RemoteVersion(UpdateChannel.STABLE, version, "https://example.com/launcher.jar",
                RemoteVersion.Type.JAR, new IntegrityCheck("SHA-1", "0".repeat(40)), false, false);
    }
}
