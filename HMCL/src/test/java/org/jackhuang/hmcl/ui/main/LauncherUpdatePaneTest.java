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
package org.jackhuang.hmcl.ui.main;

import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jackhuang.hmcl.upgrade.SelfVerificationException;
import org.jackhuang.hmcl.upgrade.UpdateChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/// Checks asynchronous update-state precedence without a UI toolkit or network requests.
@NotNullByDefault
public final class LauncherUpdatePaneTest {
    /// A missing endpoint and an active request override any previously received release.
    @Test
    public void disabledAndCheckingStatesTakePrecedence() {
        var latest = release();
        assertEquals(LauncherUpdateStatus.State.DISABLED,
                LauncherUpdateStatus.resolve(false, false, latest, null, true).state());
        assertEquals(LauncherUpdateStatus.State.CHECKING,
                LauncherUpdateStatus.resolve(true, true, latest, new IOException("old failure"), true).state());
    }

    /// A successful response is required before the UI may claim the launcher is current.
    @Test
    public void distinguishesUncheckedCurrentAndAvailable() {
        assertEquals(LauncherUpdateStatus.State.UNCHECKED,
                LauncherUpdateStatus.resolve(true, false, null, null, false).state());
        assertEquals(LauncherUpdateStatus.State.CURRENT,
                LauncherUpdateStatus.resolve(true, false, release(), null, false).state());
        var available = LauncherUpdateStatus.resolve(true, false, release(), null, true);
        assertEquals(LauncherUpdateStatus.State.AVAILABLE, available.state());
        assertEquals("0.3.13", available.targetVersion());
    }

    /// Raw exception details never become view data, and a failure suppresses stale install actions.
    @Test
    public void distinguishesRetryableAndUnverifiedFailures() {
        var failed = LauncherUpdateStatus.resolve(true, false, release(), new IOException("private diagnostic"), true);
        assertEquals(LauncherUpdateStatus.State.FAILED, failed.state());
        assertNull(failed.targetVersion());
        assertFalse(failed.toString().contains("private diagnostic"));
        assertEquals(LauncherUpdateStatus.State.UNVERIFIED,
                LauncherUpdateStatus.resolve(true, false, null, new SelfVerificationException(), false).state());
    }

    /// Creates a realistic response without querying a server.
    private static RemoteVersion release() {
        return new RemoteVersion(UpdateChannel.STABLE, "0.3.13", "https://example.com/launcher.jar",
                RemoteVersion.Type.JAR, new IntegrityCheck("SHA-1", "0".repeat(40)), false, false);
    }
}
