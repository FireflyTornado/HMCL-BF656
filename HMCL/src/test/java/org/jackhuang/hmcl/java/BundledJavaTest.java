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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies portability, artifact anchoring and validation without invoking a JVM or touching user settings.
@NotNullByDefault
public final class BundledJavaTest {
    /// Spaces, Unicode and a move across roots must not change the stored executable path.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void followsMovedLauncherIndependentlyOfWorkingDirectory(boolean windows) throws IOException {
        try (var fs = Jimfs.newFileSystem(windows ? Configuration.windows().toBuilder().setRoots("C:\\", "D:\\").build() : Configuration.unix())) {
            Path original = fs.getPath(windows ? "C:/portable/启动器 folder" : "/portable/启动器 folder");
            Path moved = fs.getPath(windows ? "D:/moved/新位置" : "/moved/新位置");
            Path unrelated = fs.getPath(windows ? "C:/other working directory" : "/other working directory");
            Files.createDirectories(unrelated);
            Files.createDirectories(moved.getParent());
            Path executable = original.resolve("runtime/java/bin/" + (windows ? "java.exe" : "java"));
            Files.createDirectories(executable.getParent());
            Files.writeString(executable, "placeholder executable");
            String relative = BundledJava.relativize(original, executable);
            assertEquals("runtime/java/bin/" + (windows ? "java.exe" : "java"), relative);
            Path artifact = original.resolve(windows ? "HMCL.exe" : "HMCL.jar");
            Files.writeString(artifact, "placeholder launcher");
            assertEquals(original, BundledJava.launcherDirectory(artifact, unrelated));
            Files.move(original, moved);
            Path newRoot = BundledJava.launcherDirectory(moved.resolve(artifact.getFileName()), unrelated);
            Path resolved = BundledJava.resolve(newRoot, relative);
            assertTrue(Files.isRegularFile(resolved));
            assertEquals(moved.resolve("runtime/java/bin/" + (windows ? "java.exe" : "java")), resolved);
            assertFalse(Files.exists(BundledJava.resolve(original, relative)));
        }
    }

    /// Absolute paths and escapes cannot silently become working-directory based Java selections.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void rejectsNonPortablePaths(boolean windows) throws IOException {
        try (var fs = Jimfs.newFileSystem(windows ? Configuration.windows().toBuilder().setRoots("C:\\", "D:\\").build() : Configuration.unix())) {
            Path root = fs.getPath(windows ? "C:/launcher" : "/launcher");
            assertThrows(IllegalArgumentException.class, () -> BundledJava.resolve(root, ""));
            assertThrows(IllegalArgumentException.class, () -> BundledJava.resolve(root, " "));
            assertThrows(IllegalArgumentException.class, () -> BundledJava.resolve(root, "."));
            assertThrows(IllegalArgumentException.class, () -> BundledJava.resolve(root, "../external/java"));
            assertThrows(IllegalArgumentException.class, () -> BundledJava.resolve(root, "runtime/../../external/java"));
            assertThrows(IllegalArgumentException.class, () -> BundledJava.resolve(root, root.resolve("java").toString()));
            assertThrows(IllegalArgumentException.class, () -> BundledJava.relativize(root, root.resolveSibling("external/java")));
            assertEquals(root.resolve("runtime/bin/java"), BundledJava.resolve(root, "./runtime/lib/../bin/java"));
            if (windows) {
                assertThrows(IllegalArgumentException.class, () -> BundledJava.resolve(root, "C:runtime/java.exe"));
                assertThrows(IllegalArgumentException.class, () -> BundledJava.resolve(root, "\\runtime\\java.exe"));
                assertThrows(IllegalArgumentException.class, () -> BundledJava.relativize(root, fs.getPath("D:/java.exe")));
            }
        }
    }

    /// Development runs without a packaged artifact keep the documented working-directory fallback.
    @Test
    public void supportsDevelopmentRuns() {
        Path working = Path.of(".").toAbsolutePath().normalize();
        assertEquals(working, BundledJava.launcherDirectory(null, working));
    }
}
