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

import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jetbrains.annotations.NotNullByDefault;

/// Creates BF656-specific about-page content separately from HMCL's original entries.
@NotNullByDefault
public final class BF656About {
    /// Prevents construction of this about-page utility.
    private BF656About() {
    }

    /// Creates the custom launcher entry with its independently maintained release version.
    public static LineButton createLauncherEntry() {
        var launcher = new LineButton();
        launcher.setLargeTitle(true);
        launcher.setLeading(FXUtils.newBuiltinImage(BF656Icons.ICON));
        launcher.setTitle(BF656Metadata.FULL_NAME);
        launcher.setSubtitle(BF656Metadata.VERSION);
        return launcher;
    }
}
