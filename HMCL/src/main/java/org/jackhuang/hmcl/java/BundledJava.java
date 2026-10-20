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
package org.jackhuang.hmcl.java;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// Resolves a portable Java executable relative to the launcher artifact rather than its working directory.
@NotNullByDefault
public final class BundledJava {
    /// Suggested location; selecting this mode does not download or install a runtime.
    public static final String DEFAULT_PATH = "runtime/java/bin/"
            + (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "java.exe" : "java");

    /// Utility class.
    private BundledJava() {
    }

    /// Returns the containing directory of the running EXE/JAR; development class directories use the working directory.
    public static Path launcherDirectory() {
        return launcherDirectory(JarUtils.thisJarPath(), Metadata.CURRENT_DIRECTORY);
    }

    /// Selects the artifact's parent without interpreting a configuration directory as the artifact location.
    static Path launcherDirectory(@Nullable Path artifact, Path developmentDirectory) {
        if (artifact != null) {
            @Nullable Path parent = artifact.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return developmentDirectory.toAbsolutePath().normalize();
    }

    /// Resolves a nonempty relative executable path beneath the launcher directory.
    /// Throws for absolute, drive-relative or escaping paths; custom external Java retains its separate mode.
    public static Path resolve(Path launcherDirectory, String relativePath) {
        Path relative = launcherDirectory.getFileSystem().getPath(relativePath);
        if (relativePath.isBlank() || relative.isAbsolute() || relative.getRoot() != null) {
            throw new IllegalArgumentException("Bundled Java requires a relative executable path");
        }
        Path root = launcherDirectory.toAbsolutePath().normalize();
        Path executable = root.resolve(relative).normalize();
        if (executable.equals(root) || !executable.startsWith(root)) {
            throw new IllegalArgumentException("Bundled Java must be inside the launcher directory");
        }
        return executable;
    }

    /// Converts a file-picker result to a portable relative path, using forward slashes for storage.
    public static String relativize(Path launcherDirectory, Path executable) {
        Path root = launcherDirectory.toAbsolutePath().normalize();
        Path target = executable.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IllegalArgumentException("Select bundled Java from inside the launcher directory");
        }
        return root.relativize(target).toString().replace('\\', '/');
    }
}
