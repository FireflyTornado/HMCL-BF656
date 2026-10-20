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

import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Limits the instance preference to optional modded Java version advice after validation has run.
@NotNullByDefault
public final class JavaCompatibilityWarnings {
    /// Utility class.
    private JavaCompatibilityWarnings() {
    }

    /// Only version recommendations may be hidden; mandatory and known-broken combinations remain visible.
    public static boolean canIgnore(JavaVersionConstraint constraint, @Nullable String forgeVersion) {
        boolean brokenForgeJava16 = constraint == JavaVersionConstraint.MODDED_JAVA_16
                && forgeVersion != null && VersionNumber.compare(forgeVersion, "37.0.60") < 0;
        return !constraint.isMandatory() && !brokenForgeJava16 && switch (constraint) {
            case MODDED_JAVA_7, MODDED_JAVA_8, MODDED_JAVA_16, MODDED_JAVA_17, MODDED_JAVA_21 -> true;
            default -> false;
        };
    }

    /// Returns whether an evaluated advice item is hidden for this instance only.
    public static boolean shouldIgnore(GameSettings.@Nullable Instance instance, JavaVersionConstraint constraint,
                                       @Nullable String forgeVersion) {
        return instance != null && Boolean.TRUE.equals(instance.ignoreJavaCompatibilityWarningsProperty().getValue())
                && canIgnore(constraint, forgeVersion);
    }

    /// Records an affirmative remembered continuation without enabling the JVM-check bypass.
    public static void remember(GameSettings.@Nullable Instance instance, boolean requested) {
        if (requested && instance != null && instance.isSavable()) {
            instance.ignoreJavaCompatibilityWarningsProperty().setValue(true);
        }
    }
}
