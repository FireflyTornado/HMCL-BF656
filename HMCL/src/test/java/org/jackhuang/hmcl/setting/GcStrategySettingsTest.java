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
package org.jackhuang.hmcl.setting;

import com.google.gson.JsonParser;
import org.jackhuang.hmcl.game.GcStrategy;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies GC strategy persistence, forward-compatible defaults and independent preset inheritance.
@NotNullByDefault
public final class GcStrategySettingsTest {
    /// Missing, null and unknown strategy values preserve the original tuned-G1 default.
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"gcStrategy\":null}", "{\"gcStrategy\":\"FUTURE_COLLECTOR\"}"})
    public void defaultsOldAndUnknownConfigurations(String json) {
        var instance = LauncherSettings.SETTINGS_GSON.fromJson(json, GameSettings.Instance.class);
        var presetJson = JsonParser.parseString(json).getAsJsonObject();
        presetJson.addProperty("id", "game-settings-preset:123e4567-e89b-12d3-a456-426614174000");
        var preset = LauncherSettings.SETTINGS_GSON.fromJson(presetJson, GameSettings.Preset.class);
        instance.getOverrideProperties().add(GameSettings.PROPERTY_GC_STRATEGY);
        assertEquals(GcStrategy.TUNED_G1,
                GameSettings.resolve(preset, instance).getInheritable(GameSettings::gcStrategyProperty));
        assertEquals(GcStrategy.TUNED_G1,
                GameSettings.resolve(preset, null).getInheritable(GameSettings::gcStrategyProperty));
    }

    /// Every strategy round-trips while heap size and automatic-optimization preferences stay independent.
    @ParameterizedTest
    @EnumSource(GcStrategy.class)
    public void inheritsAndPersistsStrategyWithoutChangingMemory(GcStrategy selected) {
        var preset = new GameSettings.Preset(
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174000"));
        preset.gcStrategyProperty().setValue(GcStrategy.ZGC);
        preset.maxMemoryProperty().setValue(6144);
        var instance = new GameSettings.Instance();
        instance.gcStrategyProperty().setValue(selected);
        var effective = GameSettings.resolve(preset, instance);
        assertEquals(GcStrategy.ZGC, effective.getInheritable(GameSettings::gcStrategyProperty));
        instance.getOverrideProperties().add(GameSettings.PROPERTY_GC_STRATEGY);
        var restored = LauncherSettings.SETTINGS_GSON.fromJson(
                LauncherSettings.SETTINGS_GSON.toJson(instance, GameSettings.Instance.class), GameSettings.Instance.class);
        var resolved = GameSettings.resolve(preset, restored);
        assertEquals(selected, resolved.getInheritable(GameSettings::gcStrategyProperty));
        assertEquals(6144, resolved.getMaxMemory());
        assertFalse(resolved.getInheritable(GameSettings::noJVMOptionsProperty));
        assertFalse(resolved.getInheritable(GameSettings::noOptimizingJVMOptionsProperty));
        var restoredPreset = LauncherSettings.SETTINGS_GSON.fromJson(
                LauncherSettings.SETTINGS_GSON.toJson(preset, GameSettings.Preset.class), GameSettings.Preset.class);
        assertEquals(GcStrategy.ZGC, restoredPreset.gcStrategyProperty().getValue());
        restored.getOverrideProperties().remove(GameSettings.PROPERTY_GC_STRATEGY);
        assertEquals(GcStrategy.ZGC, resolved.getInheritable(GameSettings::gcStrategyProperty));
    }
}
