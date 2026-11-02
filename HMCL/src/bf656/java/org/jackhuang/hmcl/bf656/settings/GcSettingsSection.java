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

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.beans.value.ObservableValue;
import org.jackhuang.hmcl.game.GcStrategy;
import org.jackhuang.hmcl.launch.GcStrategySupport;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// GC strategy selector with the existing inheritance binding and effective-state warnings.
@NotNullByDefault
public final class GcSettingsSection extends LineSelectButton<GcStrategy> {
    /// Builds the five choices without changing launch policy or serialized settings.
    public GcSettingsSection(SettingsSectionContext context) {
        setNullSafeConverter(strategy -> i18n(key(strategy)));
        setDescriptionConverter((@Nullable GcStrategy strategy) -> strategy != null
                ? i18n(key(strategy) + ".desc") + "\n\n" + i18n("settings.advanced.gc_strategy.hint") : "");
        setItems(GcStrategy.values());
        setTitle(i18n("settings.advanced.gc_strategy"));
        setDescriptionAsTooltip(true);
        context.bindSelector(this, GameSettings::gcStrategyProperty);
    }

    /// Connects warnings to the host's effective generation switches and displayed JVM arguments.
    public void bindEffectiveState(ObservableBooleanValue noJvmArguments,
                                   ObservableBooleanValue noOptimizingArguments,
                                   ObservableValue<String> jvmArguments) {
        subtitleProperty().bind(Bindings.createStringBinding(() ->
                noJvmArguments.get() || noOptimizingArguments.get()
                        ? i18n("settings.advanced.gc_strategy.disabled").stripTrailing()
                        : StringUtils.tokenize(jvmArguments.getValue()).stream().anyMatch(GcStrategySupport::isGcArgument)
                        ? i18n("settings.advanced.gc_strategy.custom").stripTrailing() : "",
                noJvmArguments, noOptimizingArguments, jvmArguments));
    }

    /// Returns the unchanged translation key for a collector choice.
    private static String key(GcStrategy strategy) {
        return "settings.advanced.gc_strategy." + strategy.name().toLowerCase(Locale.ROOT);
    }
}
