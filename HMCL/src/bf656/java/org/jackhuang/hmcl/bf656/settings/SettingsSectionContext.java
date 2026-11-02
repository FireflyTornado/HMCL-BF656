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
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.property.InheritableProperty;
import org.jackhuang.hmcl.setting.property.SettingProperty;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/// Restricted access to host settings and inheritance controls without exposing the page itself.
/// All methods and subscriptions are used on the JavaFX application thread.
@NotNullByDefault
public final class SettingsSectionContext {
    /// Currently edited settings, including an empty selection.
    private final ObservableValue<? extends @Nullable GameSettings> currentSetting;
    /// The host's resolved parent preset, updated when instance or preset selection changes.
    private final ObservableValue<GameSettings.@Nullable Preset> parentSetting;
    /// Whether this page edits presets rather than instance overrides.
    private final boolean presetSetting;
    /// Host read-only state; extensions must not write while this is true.
    private final ObservableBooleanValue readOnly;
    /// Uses the host's repository-aware resolution of effective settings.
    private final Function<GameSettings, GameSettings.Effective> resolve;
    /// Reuses the host's existing selector binding, owned for the lifetime of the page.
    private final SelectorBinder selectorBinder;
    /// Creates the host's existing inheritance button with its original style.
    private final Supplier<JFXButton> inheritanceButton;
    /// Updates inheritance icons and tooltips using the host's existing presentation.
    private final BiConsumer<JFXButton, Boolean> updateInheritanceButton;

    /// Captures narrow host capabilities; no page object or reflection is required by sections.
    public SettingsSectionContext(
            ObservableValue<? extends @Nullable GameSettings> currentSetting,
            ObservableValue<GameSettings.@Nullable Preset> parentSetting,
            boolean presetSetting,
            ObservableBooleanValue readOnly,
            Function<GameSettings, GameSettings.Effective> resolve,
            SelectorBinder selectorBinder,
            Supplier<JFXButton> inheritanceButton,
            BiConsumer<JFXButton, Boolean> updateInheritanceButton) {
        this.currentSetting = currentSetting;
        this.parentSetting = parentSetting;
        this.presetSetting = presetSetting;
        this.readOnly = readOnly;
        this.resolve = resolve;
        this.selectorBinder = selectorBinder;
        this.inheritanceButton = inheritanceButton;
        this.updateInheritanceButton = updateInheritanceButton;
    }

    /// Returns the current selection without allowing a section to change it.
    public @Nullable GameSettings currentSetting() {
        return currentSetting.getValue();
    }

    /// Returns effective values through HMCL's existing preset and instance resolution.
    public <T> T effectiveValue(GameSettings setting, Function<GameSettings, InheritableProperty<T>> getter) {
        return resolve.apply(setting).getInheritable(getter);
    }

    /// Exposes read-only changes for UI bindings.
    public ObservableBooleanValue readOnlyProperty() {
        return readOnly;
    }

    /// Reports whether writes are currently blocked by the host.
    public boolean isReadOnly() {
        return readOnly.get();
    }

    /// Binds a single inheritable selector using the host's existing generic binding.
    public <T> void bindSelector(LineSelectButton<T> button, Function<GameSettings, InheritableProperty<T>> getter) {
        selectorBinder.bind(button, getter);
    }

    /// Returns an instance inheritance button, or no button when editing a preset.
    public @Nullable JFXButton createInheritanceButton() {
        return presetSetting ? null : inheritanceButton.get();
    }

    /// Updates the host's icon, tooltip and override pseudo class.
    public void updateInheritanceButton(JFXButton button, boolean inherited) {
        updateInheritanceButton.accept(button, inherited);
    }

    /// Returns whether a setting uses its direct value instead of inheriting a preset value.
    public boolean isOverridden(GameSettings setting, SettingProperty<?> property) {
        return !(setting instanceof GameSettings.Instance instance)
                || instance.getOverrideProperties().contains(property.getName());
    }

    /// Updates an instance override flag; preset values have no override flags.
    public void setOverridden(GameSettings setting, SettingProperty<?> property, boolean overridden) {
        if (isReadOnly() || !(setting instanceof GameSettings.Instance instance))
            return;
        if (overridden)
            instance.getOverrideProperties().add(property.getName());
        else
            instance.getOverrideProperties().remove(property.getName());
    }

    /// Observes current and parent models without retaining an abandoned page through either model.
    /// The owner keeps the returned subscription alive and may close it to remove all its listeners.
    public SettingsObservation observeSettings(Runnable refresh) {
        return new SettingsObservation(refresh);
    }

    /// Generic adapter to the host's established selection binding.
    @FunctionalInterface
    @NotNullByDefault
    public interface SelectorBinder {
        /// Binds the selected value and inheritance button for the lifetime of the host page.
        <T> void bind(LineSelectButton<T> button, Function<GameSettings, InheritableProperty<T>> getter);
    }

    /// Rebinds weak model listeners on selection changes and supports explicit disposal.
    @NotNullByDefault
    public final class SettingsObservation implements AutoCloseable {
        /// Callback retained by the section while its subscription is alive.
        private final InvalidationListener listener;
        /// Wrapper installed on host properties and models, which cannot keep the section alive.
        private final WeakInvalidationListener weakListener;
        /// Current model whose changes are being observed.
        private @Nullable GameSettings setting;
        /// Parent model whose changes are being observed.
        private GameSettings.@Nullable Preset parent;
        /// Whether all listeners have already been removed.
        private boolean closed;

        /// Installs the subscription, then refreshes the section without writing settings.
        private SettingsObservation(Runnable refresh) {
            listener = ignored -> {
                if (!closed) {
                    rebindModels();
                    refresh.run();
                }
            };
            weakListener = new WeakInvalidationListener(listener);
            currentSetting.addListener(weakListener);
            parentSetting.addListener(weakListener);
            readOnly.addListener(weakListener);
            rebindModels();
            refresh.run();
        }

        /// Detaches obsolete models before subscribing to the current selection and parent.
        private void rebindModels() {
            // Reading the property rearms invalidation even when the callback only reads settings.
            readOnly.get();
            @Nullable GameSettings selected = currentSetting();
            GameSettings.@Nullable Preset selectedParent = parentSetting.getValue();
            if (setting != selected) {
                if (setting != null)
                    setting.removeListener(weakListener);
                setting = selected;
                if (setting != null)
                    setting.addListener(weakListener);
            }
            if (parent != selectedParent) {
                if (parent != null)
                    parent.removeListener(weakListener);
                parent = selectedParent;
                if (parent != null)
                    parent.addListener(weakListener);
            }
        }

        /// Removes property and model listeners; repeated calls have no effect.
        @Override
        public void close() {
            if (closed)
                return;
            closed = true;
            currentSetting.removeListener(weakListener);
            parentSetting.removeListener(weakListener);
            readOnly.removeListener(weakListener);
            if (setting != null)
                setting.removeListener(weakListener);
            if (parent != null)
                parent.removeListener(weakListener);
            setting = null;
            parent = null;
        }
    }
}
