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

import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jackhuang.hmcl.upgrade.SelfVerificationException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Immutable player-facing view data, independent of JavaFX controls and raw exception text.
///
/// @param state phase shown to the player
/// @param targetVersion release version, present only when an update is available
@NotNullByDefault
record LauncherUpdateStatus(State state, @Nullable String targetVersion) {
    /// Resolves asynchronous state; an active check or failure takes precedence over a stale response.
    static LauncherUpdateStatus resolve(boolean enabled, boolean checking, @Nullable RemoteVersion latest,
                                        @Nullable Throwable failure, boolean outdated) {
        if (!enabled)
            return new LauncherUpdateStatus(State.DISABLED, null);
        if (checking)
            return new LauncherUpdateStatus(State.CHECKING, null);
        if (failure != null)
            return new LauncherUpdateStatus(failure instanceof SelfVerificationException ? State.UNVERIFIED : State.FAILED, null);
        if (latest == null)
            return new LauncherUpdateStatus(State.UNCHECKED, null);
        return outdated ? new LauncherUpdateStatus(State.AVAILABLE, latest.version()) : new LauncherUpdateStatus(State.CURRENT, null);
    }

    /// Player-visible phases of a launcher update check.
    @NotNullByDefault
    enum State {
        /// No update service is configured for this distribution.
        DISABLED,
        /// No successful response has been received yet.
        UNCHECKED,
        /// A new request is running.
        CHECKING,
        /// The configured release is already installed.
        CURRENT,
        /// An eligible release is available.
        AVAILABLE,
        /// A request failed and may be retried.
        FAILED,
        /// This installation cannot safely apply an online update.
        UNVERIFIED
    }
}
