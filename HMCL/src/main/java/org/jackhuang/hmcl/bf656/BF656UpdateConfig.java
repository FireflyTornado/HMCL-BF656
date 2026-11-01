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
import org.jetbrains.annotations.Nullable;

import java.net.URI;

/// Independent self-update settings; empty URLs never fall back to upstream HMCL services.
///
/// @param updateUrl update manifest URL, or an empty string to disable checks
/// @param downloadUrl optional manual download page
/// @param changelogUrl optional complete changelog HTML URL
/// @param channel default BF656 release channel, independent of the HMCL build type
@NotNullByDefault
public record BF656UpdateConfig(String updateUrl, String downloadUrl, String changelogUrl, UpdateChannel channel) {
    /// Bundled defaults with the existing JVM update-source override retained for local testing.
    public static final BF656UpdateConfig CURRENT = new BF656UpdateConfig(
            System.getProperty("hmcl.update_source.override", BF656Metadata.optionalProperty("update.url", "")),
            BF656Metadata.optionalProperty("update.downloadUrl", ""),
            BF656Metadata.optionalProperty("update.changelogUrl", ""),
            parseChannel(BF656Metadata.optionalProperty("update.channel", "stable")));

    /// Validates configured URLs before any update request or browser navigation can occur.
    public BF656UpdateConfig {
        updateUrl = validateUrl("update.url", updateUrl);
        downloadUrl = validateUrl("update.downloadUrl", downloadUrl);
        changelogUrl = validateUrl("update.changelogUrl", changelogUrl);
    }

    /// Reports whether this distribution has an explicit update source.
    public boolean isEnabled() {
        return !updateUrl.isEmpty();
    }

    /// Parses only supported BF656 channels so spelling errors cannot select an upstream default.
    private static UpdateChannel parseChannel(String value) {
        return switch (value) {
            case "stable" -> UpdateChannel.STABLE;
            case "dev" -> UpdateChannel.DEVELOPMENT;
            case "nightly" -> UpdateChannel.NIGHTLY;
            default -> throw new IllegalArgumentException("Invalid update.channel: " + value);
        };
    }

    /// Accepts empty settings, HTTPS URLs, and explicit loopback HTTP URLs for local testing.
    private static String validateUrl(String key, String value) {
        String url = value.trim();
        if (url.isEmpty()) {
            return url;
        }

        URI uri = URI.create(url);
        @Nullable String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme()) || (loopback && "http".equalsIgnoreCase(uri.getScheme())))) {
            throw new IllegalArgumentException(key + " must be HTTPS (or loopback HTTP): " + url);
        }
        return url;
    }
}
