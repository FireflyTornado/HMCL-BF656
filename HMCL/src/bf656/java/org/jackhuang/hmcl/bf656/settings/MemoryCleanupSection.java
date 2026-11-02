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
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.input.MouseEvent;
import org.jackhuang.hmcl.game.MemoryCleaner;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Presents Off and three cleanup profiles while retaining legacy independent override flags.
@NotNullByDefault
public final class MemoryCleanupSection extends LineSelectButton<MemoryCleaner.@Nullable Profile> implements AutoCloseable {
    /// Narrow host settings and inheritance capabilities.
    private final SettingsSectionContext context;
    /// Inheritance control, absent for presets.
    private final @Nullable JFXButton inheritButton;
    /// Prevents refreshes and partial writes from feeding back into the selector.
    private boolean updating;
    /// Writes explicit selections; removed when this section is disposed.
    private final ChangeListener<MemoryCleaner.@Nullable Profile> selectionListener;
    /// Toggles both override flags without changing them while merely loading a configuration.
    private final EventHandler<MouseEvent> inheritanceHandler;
    /// Weak subscription to the currently selected and inherited models.
    private final SettingsSectionContext.SettingsObservation observation;

    /// Creates the existing cleanup row and binds it without normalizing old configurations.
    public MemoryCleanupSection(SettingsSectionContext context) {
        this.context = context;
        setTitle(i18n("settings.memory.cleanup"));
        boolean supported = MemoryCleaner.isSupported();
        disableProperty().bind(Bindings.createBooleanBinding(
                () -> !supported || context.isReadOnly(), context.readOnlyProperty()));
        setSubtitle(i18n(supported ? "settings.memory.cleanup.subtitle" : "settings.memory.cleanup.unsupported"));
        setItems(null, MemoryCleaner.Profile.SAFE, MemoryCleaner.Profile.DEFAULT, MemoryCleaner.Profile.FULL);
        setConverter((MemoryCleaner.@Nullable Profile profile) -> profile == null ? i18n("settings.memory.cleanup.off")
                : i18n("settings.memory.cleanup.profile." + profile.argument()));
        setDescriptionConverter((MemoryCleaner.@Nullable Profile profile) -> profile == null ? ""
                : i18n("settings.memory.cleanup.profile." + profile.argument() + ".desc") + " "
                        + i18n("settings.memory.cleanup.profile.hint"));
        setDescriptionAsTooltip(true);
        inheritButton = context.createInheritanceButton();
        if (inheritButton != null)
            setTitleTrailing(inheritButton);
        selectionListener = (observable, oldValue, newValue) -> selectProfile(newValue);
        valueProperty().addListener(selectionListener);
        inheritanceHandler = event -> {
            toggleInheritance();
            event.consume();
        };
        if (inheritButton != null)
            inheritButton.addEventFilter(MouseEvent.MOUSE_CLICKED, inheritanceHandler);
        observation = context.observeSettings(this::refresh);
    }

    /// Displays effective values without writing either legacy property or either override flag.
    private void refresh() {
        @Nullable GameSettings setting = context.currentSetting();
        if (setting == null || updating)
            return;
        updating = true;
        try {
            setValue(context.effectiveValue(setting, GameSettings::memoryCleanupProperty)
                    ? context.effectiveValue(setting, GameSettings::memoryCleanupProfileProperty) : null);
            if (inheritButton != null) {
                context.updateInheritanceButton(inheritButton,
                        !context.isOverridden(setting, setting.memoryCleanupProperty())
                                && !context.isOverridden(setting, setting.memoryCleanupProfileProperty()));
            }
        } finally {
            updating = false;
        }
    }

    /// Applies a user choice to both properties, keeping the remembered profile when selecting Off.
    private void selectProfile(MemoryCleaner.@Nullable Profile profile) {
        @Nullable GameSettings setting = context.currentSetting();
        if (setting == null || updating)
            return;
        if (context.isReadOnly()) {
            refresh();
            return;
        }
        updating = true;
        try {
            MemoryCleaner.Profile retained = context.effectiveValue(setting, GameSettings::memoryCleanupProfileProperty);
            context.setOverridden(setting, setting.memoryCleanupProperty(), true);
            context.setOverridden(setting, setting.memoryCleanupProfileProperty(), true);
            setting.memoryCleanupProfileProperty().setValue(profile != null ? profile : retained);
            setting.memoryCleanupProperty().setValue(profile != null);
        } finally {
            updating = false;
        }
        refresh();
    }

    /// Restores both inherited values or captures the current effective pair as an explicit override.
    private void toggleInheritance() {
        @Nullable GameSettings setting = context.currentSetting();
        if (setting == null || updating || context.isReadOnly())
            return;
        updating = true;
        try {
            boolean overridden = context.isOverridden(setting, setting.memoryCleanupProperty())
                    || context.isOverridden(setting, setting.memoryCleanupProfileProperty());
            if (!overridden) {
                boolean enabled = context.effectiveValue(setting, GameSettings::memoryCleanupProperty);
                MemoryCleaner.Profile profile = context.effectiveValue(setting, GameSettings::memoryCleanupProfileProperty);
                setting.memoryCleanupProperty().setValue(enabled);
                setting.memoryCleanupProfileProperty().setValue(profile);
            }
            context.setOverridden(setting, setting.memoryCleanupProperty(), !overridden);
            context.setOverridden(setting, setting.memoryCleanupProfileProperty(), !overridden);
        } finally {
            updating = false;
        }
        refresh();
    }

    /// Removes this section's listeners when its owner explicitly disposes it.
    /// Ordinary page hiding does not dispose reusable settings controls.
    @Override
    public void close() {
        observation.close();
        valueProperty().removeListener(selectionListener);
        if (inheritButton != null)
            inheritButton.removeEventFilter(MouseEvent.MOUSE_CLICKED, inheritanceHandler);
        disableProperty().unbind();
    }
}
