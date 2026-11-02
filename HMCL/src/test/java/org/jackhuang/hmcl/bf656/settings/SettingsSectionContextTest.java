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
package org.jackhuang.hmcl.bf656.settings;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.jackhuang.hmcl.game.MemoryCleaner;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameSettingsPresetID;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.property.InheritableProperty;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// Tests model subscriptions and supplies cleanup UI checks for the isolated memory smoke process.
@NotNullByDefault
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
public final class SettingsSectionContextTest {
    /// Old instances and parents stop notifying the section, and explicit close detaches every source.
    @Test
    public void observationsFollowSelectionAndDispose() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            var first = new GameSettings.Instance();
            var second = new GameSettings.Instance();
            var oldParent = preset();
            var newParent = preset();
            fixture.selected.set(first);
            fixture.parent.set(oldParent);
            var calls = new AtomicInteger();
            try (var observation = fixture.context.observeSettings(calls::incrementAndGet)) {
                fixture.selected.set(second);
                int afterSwitch = calls.get();
                first.memoryCleanupProperty().setValue(true);
                assertEquals(afterSwitch, calls.get(), "The previous instance must no longer be observed");
                second.memoryCleanupProperty().setValue(true);
                assertTrue(calls.get() > afterSwitch);
                fixture.parent.set(newParent);
                int afterParentSwitch = calls.get();
                oldParent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.FULL);
                assertEquals(afterParentSwitch, calls.get(), "The previous parent must no longer be observed");
                newParent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.FULL);
                assertTrue(calls.get() > afterParentSwitch);
                int beforeReadOnly = calls.get();
                fixture.readOnly.set(true);
                fixture.readOnly.set(false);
                assertEquals(beforeReadOnly + 2, calls.get());
            }
            int afterClose = calls.get();
            fixture.selected.set(first);
            fixture.parent.set(oldParent);
            second.memoryCleanupProperty().setValue(false);
            newParent.memoryCleanupProperty().setValue(true);
            fixture.readOnly.set(true);
            assertEquals(afterClose, calls.get(), "Disposal must detach host and model listeners");
        });
    }

    /// Runs control checks after the caller has initialized isolated settings and the JavaFX runtime.
    public static void verifyCleanupUi() throws Exception {
        cleanupSwitchingPreservesStoredValues();
        cleanupRejectsReadOnlyWrites();
        cleanupDisposalStopsReadingAndWriting();
    }

    /// Loading and switching partial legacy overrides must not serialize new values or override flags.
    private static void cleanupSwitchingPreservesStoredValues() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            var parent = preset();
            parent.memoryCleanupProperty().setValue(true);
            parent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.FULL);
            fixture.parent.set(parent);
            var first = new GameSettings.Instance();
            first.memoryCleanupProperty().setValue(false);
            first.getOverrideProperties().add(GameSettings.PROPERTY_MEMORY_CLEANUP);
            var second = new GameSettings.Instance();
            fixture.selected.set(first);
            String beforeFirst = LauncherSettings.SETTINGS_GSON.toJson(first, GameSettings.Instance.class);
            String beforeSecond = LauncherSettings.SETTINGS_GSON.toJson(second, GameSettings.Instance.class);
            try (var section = new MemoryCleanupSection(fixture.context)) {
                assertNull(section.getValue());
                fixture.selected.set(second);
                assertEquals(MemoryCleaner.Profile.FULL, section.getValue());
                assertEquals(beforeFirst, LauncherSettings.SETTINGS_GSON.toJson(first, GameSettings.Instance.class));
                assertEquals(beforeSecond, LauncherSettings.SETTINGS_GSON.toJson(second, GameSettings.Instance.class));
                fixture.selected.set(first);
                assertNull(section.getValue());
                assertEquals(beforeFirst, LauncherSettings.SETTINGS_GSON.toJson(first, GameSettings.Instance.class));
                parent.memoryCleanupProperty().setValue(false);
                fixture.selected.set(second);
                assertNull(section.getValue());
            }
        });
    }

    /// Read-only controls reject writes, then resume using the same properties when the host unlocks them.
    private static void cleanupRejectsReadOnlyWrites() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            var parent = preset();
            parent.memoryCleanupProperty().setValue(true);
            parent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.FULL);
            fixture.parent.set(parent);
            var instance = new GameSettings.Instance();
            fixture.selected.set(instance);
            fixture.readOnly.set(true);
            String before = LauncherSettings.SETTINGS_GSON.toJson(instance, GameSettings.Instance.class);
            try (var section = new MemoryCleanupSection(fixture.context)) {
                assertTrue(section.isDisabled());
                section.setValue(MemoryCleaner.Profile.SAFE);
                assertEquals(MemoryCleaner.Profile.FULL, section.getValue());
                assertEquals(before, LauncherSettings.SETTINGS_GSON.toJson(instance, GameSettings.Instance.class));
                fixture.readOnly.set(false);
                assertEquals(!MemoryCleaner.isSupported(), section.isDisabled());
                section.setValue(MemoryCleaner.Profile.SAFE);
                assertTrue(instance.memoryCleanupProperty().getValue());
                assertEquals(MemoryCleaner.Profile.SAFE, instance.memoryCleanupProfileProperty().getValue());
                assertTrue(instance.getOverrideProperties().contains(GameSettings.PROPERTY_MEMORY_CLEANUP));
                assertTrue(instance.getOverrideProperties().contains(GameSettings.PROPERTY_MEMORY_CLEANUP_PROFILE));
            }
        });
    }

    /// Closing a discarded cleanup section stops both refreshes and writes from its old controls.
    private static void cleanupDisposalStopsReadingAndWriting() throws Exception {
        onFx(() -> {
            var fixture = new Fixture();
            var parent = preset();
            parent.memoryCleanupProperty().setValue(true);
            parent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.FULL);
            fixture.parent.set(parent);
            var instance = new GameSettings.Instance();
            fixture.selected.set(instance);
            var section = new MemoryCleanupSection(fixture.context);
            section.close();
            section.close();
            parent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.DEFAULT);
            assertEquals(MemoryCleaner.Profile.FULL, section.getValue());
            section.setValue(MemoryCleaner.Profile.SAFE);
            assertTrue(instance.getOverrideProperties().isEmpty());
            assertFalse(instance.memoryCleanupProperty().getValue());
        });
    }

    /// Creates an unregistered preset; these tests never access a real user's settings.
    private static GameSettings.Preset preset() {
        return new GameSettings.Preset(
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174000"));
    }

    /// Runs UI assertions on the already initialized JavaFX application thread.
    private static void onFx(Runnable action) throws Exception {
        var task = new FutureTask<>(action, true);
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    /// Rejects unexpected selector use; memory sections do not use the host's single-value binder.
    private static <T> void unusedSelector(LineSelectButton<T> button,
                                           Function<GameSettings, InheritableProperty<T>> getter) {
        throw new AssertionError("No single-value selector binding expected");
    }

    /// In-memory host capabilities for exercising section behavior independently of page internals.
    @NotNullByDefault
    private static final class Fixture {
        /// Selection changed explicitly by each scenario.
        private final SimpleObjectProperty<@Nullable GameSettings> selected = new SimpleObjectProperty<>();
        /// Parent preset changed explicitly without any settings registry.
        private final SimpleObjectProperty<GameSettings.@Nullable Preset> parent = new SimpleObjectProperty<>();
        /// Host write protection.
        private final SimpleBooleanProperty readOnly = new SimpleBooleanProperty();
        /// The same production context used by extracted sections.
        private final SettingsSectionContext context = new SettingsSectionContext(selected, parent, false, readOnly,
                this::resolve, SettingsSectionContextTest::unusedSelector, JFXButton::new, (button, inherited) -> { });

        /// Uses HMCL's actual inheritance resolver with the fixture's current parent.
        private GameSettings.Effective resolve(GameSettings setting) {
            return setting instanceof GameSettings.Preset preset
                    ? GameSettings.resolve(preset, null)
                    : GameSettings.resolve(Objects.requireNonNull(parent.get()), (GameSettings.Instance) setting);
        }
    }
}
