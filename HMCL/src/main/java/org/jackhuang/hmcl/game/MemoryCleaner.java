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

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.NativeUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.SystemInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Runs the independently built Mem Reduct derivative before heap allocation is calculated.
/// The launcher remains unelevated; only the fixed, verified helper requests UAC elevation.
@NotNullByDefault
public final class MemoryCleaner {
    /// The helper's three operation sets; names are a fixed command-line allowlist.
    @NotNullByDefault
    public enum Profile {
        /// Working sets, system file cache and low-priority standby pages.
        SAFE,
        /// Basic operations plus page combining, registry reconciliation and volume flushing.
        DEFAULT,
        /// All eight supported operations, including standby purge and modified-page flushing.
        FULL;

        /// Returns the helper protocol's fixed profile identifier.
        public String argument() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /// Packaged helper hash, updated only together with its versioned source and binary.
    static final String SHA256 = "c44981ad7d70454f1d2a28177dbe99e261eb9d3fa82c9ccdca73b5cfdc5086b9";
    /// Packaged x64 helper resource.
    private static final String RESOURCE = "/assets/memory/windows-x86_64/bf656-memory-cleaner.exe";
    /// Prevents another launch from overlapping an outstanding cleanup in this launcher.
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();

    /// Utility class.
    private MemoryCleaner() {
    }

    /// Whether this build can run its packaged Windows 10/11 x64 helper.
    public static boolean isSupported() {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                && Architecture.SYSTEM_ARCH == Architecture.X86_64
                && Architecture.CURRENT_ARCH == Architecture.X86_64
                && NativeUtils.USE_JNA
                && System.getProperty("os.version", "").startsWith("10.");
    }

    /// Waits for cleanup before the caller creates launch options.
    /// Ordinary cleanup errors and declined UAC are logged and allow launch to continue.
    /// A cancelled wait or a helper that has not exited prevents this launch from proceeding.
    public static void beforeLaunch(boolean enabled, Profile profile) throws IOException, InterruptedException {
        if (!ACTIVE.compareAndSet(false, true)) {
            throw new IOException("A memory cleanup is still running. Wait before launching the game again.");
        }
        @Nullable Pointer process = null;
        try {
            if (!enabled || !isSupported()) {
                return;
            }
            long before = SystemInfo.getPhysicalMemoryStatus().available();
            try {
                LOG.info("Starting memory cleanup with profile=" + profile.argument());
                process = startElevated(extract(Metadata.HMCL_LOCAL_HOME.resolve("components/memory-cleaner/1.0.0/windows-x86_64")), profile);
            } catch (IOException | LinkageError | RuntimeException e) {
                LOG.warning("Memory cleanup skipped: could not start helper", e);
                return;
            }
            if (process == null) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Memory cleanup wait cancelled");
                }
                int wait = Windows.KERNEL.WaitForSingleObject(process, 250);
                if (wait == 0) {
                    IntByReference code = new IntByReference();
                    if (!Windows.KERNEL.GetExitCodeProcess(process, code)) {
                        LOG.warning("Memory cleanup exited, but its exit code could not be read: " + Windows.KERNEL.GetLastError());
                    } else {
                        if (code.getValue() == 170) {
                            throw new IOException("Another memory cleanup is running. Wait before launching the game again.");
                        }
                        LOG.info("Memory cleanup: profile=" + profile.argument() + ", " + describeExitCode(code.getValue())
                                + "; available bytes before=" + before
                                + ", after=" + SystemInfo.getPhysicalMemoryStatus().available());
                    }
                    Windows.KERNEL.CloseHandle(process);
                    process = null;
                    return;
                }
                if (wait != 258 || System.nanoTime() >= deadline) {
                    throw new IOException("Memory cleanup has not been confirmed stopped. Game launch was stopped; wait before trying again.");
                }
            }
        } finally {
            if (process != null) {
                monitor(process);
            } else {
                ACTIVE.set(false);
            }
        }
    }

    /// Extracts and verifies the bundled binary without executing it.
    static Path extract(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve("bf656-memory-cleaner.exe");
        if (Files.isRegularFile(target) && SHA256.equalsIgnoreCase(DigestUtils.digestToString("SHA-256", target))) {
            return target.toAbsolutePath();
        }
        Path temporary = Files.createTempFile(directory, "cleaner-", ".tmp");
        try {
            try (@Nullable InputStream resource = MemoryCleaner.class.getResourceAsStream(RESOURCE)) {
                if (resource == null) {
                    throw new IOException("Bundled memory cleaner is missing");
                }
                Files.copy(resource, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!SHA256.equalsIgnoreCase(DigestUtils.digestToString("SHA-256", temporary))) {
                throw new IOException("Bundled memory cleaner checksum mismatch");
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toAbsolutePath();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Decodes protocol v1 exit codes; a partial failure must never be reported as success.
    static String describeExitCode(int code) {
        if (code == 0) {
            return "success";
        }
        if (code > 65536 && code <= 65791) {
            return "partial failure, failed operation mask=0x" + Integer.toHexString(code & 255);
        }
        return "failed, exit code=" + Integer.toUnsignedString(code);
    }

    /// Opens a directly tracked elevated helper; no intermediate process or command shell is used.
    private static @Nullable Pointer startElevated(Path executable, Profile profile) throws IOException {
        int initialized = Windows.OLE.CoInitializeEx(null, 2);
        if (initialized < 0) {
            throw new IOException("Cannot initialize cleanup COM apartment: " + Integer.toHexString(initialized));
        }
        try {
            ShellExecuteInfo info = new ShellExecuteInfo();
            info.cbSize = info.size();
            info.fMask = 0x40 | 0x100 | 0x400; // NOCLOSEPROCESS, NOASYNC, FLAG_NO_UI; UAC is still shown.
            info.lpVerb = new WString("runas");
            info.lpFile = new WString(executable.toString());
            info.lpParameters = new WString(cleanArguments(profile));
            info.lpDirectory = new WString(executable.getParent().toString());
            info.nShow = 0;
            if (!Windows.SHELL.ShellExecuteExW(info)) {
                int error = Windows.KERNEL.GetLastError();
                if (error == 1223) {
                    LOG.info("Memory cleanup skipped: user declined UAC");
                    return null;
                }
                throw new IOException("Cannot start memory cleaner: Windows error " + error);
            }
            if (info.hProcess == null) {
                throw new IOException("Windows did not return a memory cleaner process handle");
            }
            return info.hProcess;
        } finally {
            Windows.OLE.CoUninitialize();
        }
    }

    /// Builds fixed helper arguments from a typed profile, without accepting arbitrary command text.
    static String cleanArguments(Profile profile) {
        return "--clean --profile " + profile.argument();
    }

    /// Keeps ownership of a timed out or cancelled helper until it actually exits.
    private static void monitor(Pointer process) {
        Thread monitor = new Thread(() -> {
            while (true) {
                int result = Windows.KERNEL.WaitForSingleObject(process, 1000);
                if (result == 0) {
                    Windows.KERNEL.CloseHandle(process);
                    ACTIVE.set(false);
                    return;
                }
                if (result != 258) {
                    // Unknown process state: retain the guard instead of allowing a launch to overlap.
                    LOG.warning("Cannot monitor memory cleaner; cleanup guard retained: " + Windows.KERNEL.GetLastError());
                    Windows.KERNEL.CloseHandle(process);
                    return;
                }
            }
        }, "memory-cleaner-wait");
        monitor.setDaemon(true);
        monitor.start();
    }

    /// Lazy Windows-only native bindings, avoiding DLL loading on other platforms.
    @NotNullByDefault
    private static final class Windows {
        /// Process lifetime and exit status API.
        private static final Kernel KERNEL = Objects.requireNonNull(NativeUtils.load("kernel32", Kernel.class));
        /// Elevation API.
        private static final Shell SHELL = Objects.requireNonNull(NativeUtils.load("shell32", Shell.class));
        /// COM apartment API required by the shell.
        private static final Ole OLE = Objects.requireNonNull(NativeUtils.load("ole32", Ole.class));
    }

    /// Minimal process API; all handles are owned by the caller.
    @NotNullByDefault
    public interface Kernel extends StdCallLibrary {
        /// Waits for process termination or the specified timeout.
        int WaitForSingleObject(Pointer process, int milliseconds);

        /// Gets the terminated process's DWORD exit code.
        boolean GetExitCodeProcess(Pointer process, IntByReference code);

        /// Releases a process handle.
        boolean CloseHandle(Pointer process);

        /// Reads the calling thread's last Win32 error.
        int GetLastError();
    }

    /// Unicode shell elevation API.
    @NotNullByDefault
    public interface Shell extends StdCallLibrary {
        /// Executes the fixed helper and returns its process handle.
        boolean ShellExecuteExW(ShellExecuteInfo info);
    }

    /// COM apartment lifecycle API.
    @NotNullByDefault
    public interface Ole extends StdCallLibrary {
        /// Initializes the current thread's apartment.
        int CoInitializeEx(@Nullable Pointer reserved, int flags);

        /// Balances a successful initialization on the current thread.
        void CoUninitialize();
    }

    /// ABI layout of SHELLEXECUTEINFOW; unused pointer members remain null.
    @NotNullByDefault
    @Structure.FieldOrder({"cbSize", "fMask", "hwnd", "lpVerb", "lpFile", "lpParameters", "lpDirectory", "nShow",
            "hInstApp", "lpIDList", "lpClass", "hkeyClass", "dwHotKey", "hIcon", "hProcess"})
    public static final class ShellExecuteInfo extends Structure {
        /// Structure size in bytes.
        public int cbSize;
        /// Shell execution flags.
        public int fMask;
        /// Optional owner window.
        public @Nullable Pointer hwnd;
        /// Shell verb.
        public @Nullable WString lpVerb;
        /// Absolute executable path.
        public @Nullable WString lpFile;
        /// Fixed arguments.
        public @Nullable WString lpParameters;
        /// Helper working directory.
        public @Nullable WString lpDirectory;
        /// Window display mode.
        public int nShow;
        /// Legacy shell instance result.
        public @Nullable Pointer hInstApp;
        /// Unused item list.
        public @Nullable Pointer lpIDList;
        /// Unused association class.
        public @Nullable WString lpClass;
        /// Unused registry class key.
        public @Nullable Pointer hkeyClass;
        /// Unused hotkey.
        public int dwHotKey;
        /// Unused icon/monitor union.
        public @Nullable Pointer hIcon;
        /// Actual elevated process, owned by the caller.
        public @Nullable Pointer hProcess;
    }
}
