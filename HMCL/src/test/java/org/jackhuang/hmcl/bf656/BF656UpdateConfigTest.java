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
package org.jackhuang.hmcl.bf656;

import org.jackhuang.hmcl.upgrade.UpdateChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Validates disabled defaults and the supported public and local update endpoints.
@NotNullByDefault
public final class BF656UpdateConfigTest {
    /// An intentionally empty source stays disabled even if optional pages are configured.
    @Test
    public void missingSourceDoesNotFallBackToOfficialHmcl() {
        var config = new BF656UpdateConfig("  ", "https://example.com/download", "", UpdateChannel.STABLE);
        assertFalse(config.isEnabled());
        assertEquals("", config.updateUrl());
        assertEquals("", config.changelogUrl());
        assertEquals(UpdateChannel.STABLE, config.channel());
    }

    /// Public HTTPS and explicit local HTTP addresses remain usable for release and test environments.
    @Test
    public void acceptsHttpsAndLoopbackHttp() {
        assertTrue(new BF656UpdateConfig(" https://example.com/update.json?release=bf656 ", "", "",
                UpdateChannel.STABLE).isEnabled());
        @Unmodifiable List<String> sources = List.of("http://localhost:8123/update.json",
                "http://127.0.0.1:8123/update.json", "http://[::1]:8123/update.json");
        for (String source : sources) {
            var config = new BF656UpdateConfig(source, "", "", UpdateChannel.DEVELOPMENT);
            assertEquals(source, config.updateUrl());
        }
    }

    /// Invalid or insecure remote URLs must not turn into network requests or browser links.
    @Test
    public void rejectsInvalidUrlsForEveryConfiguredPage() {
        @Unmodifiable List<String> urls = List.of("http://example.com/update.json", "/update.json",
                "file:///tmp/update.json", "https://user:password@example.com/update.json",
                "https://example.com/update.json#fragment");
        for (String url : urls) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BF656UpdateConfig(url, "", "", UpdateChannel.STABLE), url);
            assertThrows(IllegalArgumentException.class,
                    () -> new BF656UpdateConfig("", url, "", UpdateChannel.STABLE), url);
            assertThrows(IllegalArgumentException.class,
                    () -> new BF656UpdateConfig("", "", url, UpdateChannel.STABLE), url);
        }
    }
}
