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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/// Independent BF656 branding loaded from the bundled `config/bf656.properties`.
/// Does not initialize HMCL metadata, so HMCL can safely delegate its display fields here.
@NotNullByDefault
public final class BF656Metadata {
    /// Branding values read once from the packaged UTF-8 resource.
    private static final Properties PROPERTIES = loadProperties();

    /// Short launcher name, independent of the upstream HMCL version.
    public static final String NAME = requiredProperty("name");

    /// Full launcher name displayed in window titles and the about page.
    public static final String FULL_NAME = requiredProperty("fullName");

    /// Custom release version maintained separately from the HMCL branch version.
    public static final String VERSION = requiredProperty("version");

    /// Prevents construction of this metadata utility.
    private BF656Metadata() {
    }

    /// Formats the short title using the caller's current HMCL branch version.
    public static String title(String hmclVersion) {
        return NAME + " " + VERSION + " (HMCL " + hmclVersion + ")";
    }

    /// Formats the full window title using the custom launcher name and release version.
    public static String fullTitle() {
        return FULL_NAME + " v" + VERSION;
    }

    /// Loads the configuration copied into the application by `gradle/bf656.gradle.kts`.
    private static Properties loadProperties() {
        @Nullable InputStream input = BF656Metadata.class.getResourceAsStream("/assets/bf656.properties");
        if (input == null) {
            throw new ExceptionInInitializerError("Missing bundled assets/bf656.properties");
        }
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /// Reads a required branding value and rejects incomplete packaged configurations.
    private static String requiredProperty(String key) {
        @Nullable String value = PROPERTIES.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new ExceptionInInitializerError("Missing or blank '" + key + "' in assets/bf656.properties");
        }
        return value.trim();
    }
}
