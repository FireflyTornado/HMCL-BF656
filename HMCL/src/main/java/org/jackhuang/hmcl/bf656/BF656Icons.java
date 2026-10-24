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

import javafx.stage.Stage;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/// Centralizes custom launcher icons while preserving the original HMCL image resources.
@NotNullByDefault
public final class BF656Icons {
    /// Small application icon also used by the custom about-page entry.
    public static final String ICON = "/assets/img/bf656-icon.png";

    /// Title-bar image; JavaFX resolves the adjacent `@2x` resource on high-DPI displays.
    public static final String TITLE_ICON = "/assets/img/bf656-icon-title.png";

    /// Compile-time resource path usable by the AWT taskbar before JavaFX is initialized.
    public static final String MAC_ICON = "/assets/img/bf656-icon-mac.png";

    /// Prevents construction of this icon utility.
    private BF656Icons() {
    }

    /// Returns immutable window icon paths appropriate for the requested platform.
    public static @Unmodifiable List<String> windowResources(OperatingSystem platform) {
        if (platform == OperatingSystem.MACOS) {
            return List.of(MAC_ICON);
        }
        return List.of(ICON, "/assets/img/bf656-icon@2x.png",
                "/assets/img/bf656-icon@4x.png", "/assets/img/bf656-icon@8x.png");
    }

    /// Adds custom application icons at multiple resolutions to a launcher window.
    public static void setWindowIcons(Stage stage) {
        for (String resource : windowResources(OperatingSystem.CURRENT_OS)) {
            stage.getIcons().add(FXUtils.newBuiltinImage(resource));
        }
    }
}
