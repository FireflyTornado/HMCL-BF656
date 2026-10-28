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
package org.jackhuang.hmcl.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/// A launch-stopping GC compatibility error, distinguished from an inconclusive or failed runtime probe.
@NotNullByDefault
public final class GcStrategyException extends IOException {
    /// User-facing failure categories; only confirmed collector rejection is reported as unsupported.
    @NotNullByDefault
    public enum Reason {
        /// Neither supported ZGC mode is available for the selected Java.
        UNSUPPORTED,
        /// The executable could not be run, or Java failed for a reason unrelated to collector availability.
        DETECTION_FAILED,
        /// The selected Java did not finish its capability check within the time limit.
        DETECTION_TIMED_OUT
    }

    /// The reason the launch was stopped.
    private final Reason reason;
    /// Full version of the Java runtime selected for the game.
    private final String javaVersion;
    /// Actual Java executable selected for the game, without substitution.
    private final Path executable;

    /// Creates a typed failure while retaining technical diagnostics in the cause for logs.
    public GcStrategyException(Reason reason, String javaVersion, Path executable, @Nullable Throwable cause) {
        super("GC strategy check failed: " + reason + ", Java=" + javaVersion + ", executable=" + executable, cause);
        this.reason = reason;
        this.javaVersion = javaVersion;
        this.executable = executable;
    }

    /// Returns the user-facing failure category.
    public Reason getReason() {
        return reason;
    }

    /// Returns the full version of the selected game Java.
    public String getJavaVersion() {
        return javaVersion;
    }

    /// Returns the executable whose collector availability was checked.
    public Path getExecutable() {
        return executable;
    }
}
