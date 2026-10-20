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
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies remembered advice cannot disable required validation, affect another instance or bypass persistence.
@NotNullByDefault
public final class JavaCompatibilityWarningsTest {
    /// Every mandatory or non-version recommendation remains visible even with the preference enabled.
    @ParameterizedTest
    @EnumSource(JavaVersionConstraint.class)
    public void ignoresOnlyAllowedAdvice(JavaVersionConstraint constraint) {
        var instance = new GameSettings.Instance();
        assertFalse(JavaCompatibilityWarnings.shouldIgnore(instance, constraint, null));
        JavaCompatibilityWarnings.remember(instance, true);
        boolean expected = switch (constraint) {
            case MODDED_JAVA_7, MODDED_JAVA_8, MODDED_JAVA_16, MODDED_JAVA_17, MODDED_JAVA_21 -> true;
            default -> false;
        };
        assertEquals(expected, JavaCompatibilityWarnings.shouldIgnore(instance, constraint, null));
        if (constraint.isMandatory()) {
            assertFalse(JavaCompatibilityWarnings.shouldIgnore(instance, constraint, null));
        }
        assertFalse(JavaCompatibilityWarnings.shouldIgnore(null, constraint, null));
        assertFalse(instance.notCheckJVMProperty().getValue());
    }

    /// A remembered continuation survives reload and can be revoked without changing a second instance.
    @Test
    public void persistsOnlyCurrentInstanceAndCanBeRevoked() {
        var instance = LauncherSettings.SETTINGS_GSON.fromJson("{}", GameSettings.Instance.class);
        var other = new GameSettings.Instance();
        JavaCompatibilityWarnings.remember(instance, false);
        assertFalse(JavaCompatibilityWarnings.shouldIgnore(instance, JavaVersionConstraint.MODDED_JAVA_17, null));
        JavaCompatibilityWarnings.remember(instance, true);
        String json = LauncherSettings.SETTINGS_GSON.toJson(instance, GameSettings.Instance.class);
        var restored = LauncherSettings.SETTINGS_GSON.fromJson(json, GameSettings.Instance.class);
        assertTrue(JavaCompatibilityWarnings.shouldIgnore(restored, JavaVersionConstraint.MODDED_JAVA_17, null));
        assertFalse(JavaCompatibilityWarnings.shouldIgnore(other, JavaVersionConstraint.MODDED_JAVA_17, null));
        assertTrue(restored.getOverrideProperties().isEmpty());
        assertFalse(restored.notCheckJVMProperty().getValue());
        restored.ignoreJavaCompatibilityWarningsProperty().setValue(false);
        assertFalse(JavaCompatibilityWarnings.shouldIgnore(restored, JavaVersionConstraint.MODDED_JAVA_17, null));
    }

    /// Forge builds known to break on Java 17 must keep their warning even after advice is remembered.
    @Test
    public void retainsKnownBrokenForgeWarnings() {
        var instance = new GameSettings.Instance();
        JavaCompatibilityWarnings.remember(instance, true);
        assertFalse(JavaCompatibilityWarnings.shouldIgnore(instance, JavaVersionConstraint.MODDED_JAVA_16, "37.0.59"));
        assertTrue(JavaCompatibilityWarnings.shouldIgnore(instance, JavaVersionConstraint.MODDED_JAVA_16, "37.0.60"));
        assertFalse(JavaCompatibilityWarnings.shouldIgnore(instance, JavaVersionConstraint.MODLAUNCHER_8, null));
        assertFalse(JavaCompatibilityWarnings.shouldIgnore(instance, JavaVersionConstraint.VANILLA_JAVA_8_51, null));
    }

    /// A read-only instance cannot gain an unsavable remembered preference.
    @Test
    public void doesNotRememberReadOnlySettings() {
        var instance = new GameSettings.Instance();
        instance.setSavable(false);
        JavaCompatibilityWarnings.remember(instance, true);
        assertFalse(instance.ignoreJavaCompatibilityWarningsProperty().getValue());
    }
}
