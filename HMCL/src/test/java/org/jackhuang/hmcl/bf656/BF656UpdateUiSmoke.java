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

import org.jackhuang.hmcl.ui.main.LauncherUpdateUiSmoke;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

/// Retains the existing smoke command while verifying the current player-facing update interface.
@NotNullByDefault
public final class BF656UpdateUiSmoke {
    /// Prevents construction of this compatibility entry point.
    private BF656UpdateUiSmoke() {
    }

    /// Runs the isolated JavaFX state, preference, and confirmation verification.
    public static void main(String @Unmodifiable [] args) {
        LauncherUpdateUiSmoke.main(args);
    }
}
