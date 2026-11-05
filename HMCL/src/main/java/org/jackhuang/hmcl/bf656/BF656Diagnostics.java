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

import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;

/// Formats launcher identity consistently for crash reports, exported logs, and the crash window.
@NotNullByDefault
public final class BF656Diagnostics {
    /// Reserved archive entry containing the current launcher's identity in UTF-8.
    public static final String FILE_NAME = "launcher-info.txt";

    /// Prevents construction of this formatting utility.
    private BF656Diagnostics() {
    }

    /// Describes the custom project for reports that already contain an upstream version field.
    public static String projectInfo() {
        return "Launcher: " + BF656Metadata.FULL_NAME + "\n"
                + "BF656 Version: " + BF656Metadata.VERSION + "\n";
    }

    /// Describes both the custom release and its underlying HMCL version using locale-independent field names.
    public static String describe() {
        return projectInfo() + "HMCL Version: " + Metadata.VERSION + "\n";
    }

    /// Separates launcher identity from the original body of a plain-text log export.
    public static String logHeader() {
        return "---- Launcher Information ----\n" + describe() + "\n";
    }

    /// Places each version on its own line so the crash window need not reserve one long horizontal field.
    public static String versionSummary() {
        return "BF656 " + BF656Metadata.VERSION + "\nHMCL " + Metadata.VERSION;
    }
}
