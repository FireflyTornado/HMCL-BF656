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
package org.jackhuang.hmcl.game;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.platform.NativeUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Checks packaging, native ABI and result handling without elevation or memory cleanup.
@NotNullByDefault
public final class MemoryCleanerTest {
    /// Every selectable profile must resolve to the native operation set without performing cleanup.
    @ParameterizedTest
    @CsvSource({"SAFE,7", "DEFAULT,231", "FULL,255"})
    public void selectedProfilesMatchNativeOperations(MemoryCleaner.Profile profile, int expectedMask,
                                                     @TempDir Path directory) throws Exception {
        assumeTrue(MemoryCleaner.isSupported());
        String arguments = MemoryCleaner.cleanArguments(profile);
        assertEquals("--clean --profile " + profile.argument(), arguments);
        // Exercise the same selected arguments with the operation mode replaced by read-only dry-run.
        Process process = new ProcessBuilder(MemoryCleaner.extract(directory).toString(),
                "--dry-run", "--profile", profile.argument()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            var result = JsonParser.parseString(new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(expectedMask, result.get("requestedMask").getAsInt());
            assertFalse(result.get("cleanupExecuted").getAsBoolean());
        } finally {
            process.destroy();
        }
    }

    /// Exercises the real shell/process bindings with a read-only probe, never runas or --clean.
    @Test
    public void tracksReadOnlyNativeProbe(@TempDir Path directory) throws IOException {
        assumeTrue(MemoryCleaner.isSupported());
        var shell = Objects.requireNonNull(NativeUtils.load("shell32", MemoryCleaner.Shell.class));
        var kernel = Objects.requireNonNull(NativeUtils.load("kernel32", MemoryCleaner.Kernel.class));
        var ole = Objects.requireNonNull(NativeUtils.load("ole32", MemoryCleaner.Ole.class));
        assertTrue(ole.CoInitializeEx(null, 2) >= 0);
        try {
            var info = new MemoryCleaner.ShellExecuteInfo();
            info.cbSize = info.size();
            info.fMask = 0x40 | 0x100 | 0x400;
            info.lpVerb = new WString("open");
            info.lpFile = new WString(MemoryCleaner.extract(directory).toString());
            info.lpParameters = new WString("--probe");
            assertTrue(shell.ShellExecuteExW(info));
            var process = Objects.requireNonNull(info.hProcess);
            try {
                assertEquals(0, kernel.WaitForSingleObject(process, 10000));
                var code = new IntByReference();
                assertTrue(kernel.GetExitCodeProcess(process, code));
                assertEquals(0, code.getValue());
            } finally {
                kernel.CloseHandle(process);
            }
        } finally {
            ole.CoUninitialize();
        }
    }

    /// Extraction must repair a damaged helper and must never accept an unrelated executable.
    @Test
    public void repairsCorruptPackagedHelper(@TempDir Path directory) throws IOException {
        Path executable = MemoryCleaner.extract(directory);
        assertEquals(MemoryCleaner.SHA256, DigestUtils.digestToString("SHA-256", executable));
        Files.writeString(executable, "not an executable");
        assertEquals(executable, MemoryCleaner.extract(directory));
        assertEquals(MemoryCleaner.SHA256, DigestUtils.digestToString("SHA-256", executable));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    /// Pointer padding must match the native Windows shell structure.
    @Test
    public void matchesShellStructureLayout() {
        assertEquals(Native.POINTER_SIZE == 8 ? 112 : 60, new MemoryCleaner.ShellExecuteInfo().size());
    }

    /// Partial and global errors, including DWORD values, must remain failures in logs.
    @Test
    public void distinguishesFailuresFromSuccess() {
        assertEquals("success", MemoryCleaner.describeExitCode(0));
        assertEquals("partial failure, failed operation mask=0x5", MemoryCleaner.describeExitCode(65541));
        assertEquals("failed, exit code=740", MemoryCleaner.describeExitCode(740));
        assertEquals("failed, exit code=4294967295", MemoryCleaner.describeExitCode(-1));
        assertEquals("failed, exit code=65536", MemoryCleaner.describeExitCode(65536));
    }
}
