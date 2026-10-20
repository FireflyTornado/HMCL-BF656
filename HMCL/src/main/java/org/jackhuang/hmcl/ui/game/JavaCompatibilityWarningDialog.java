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
package org.jackhuang.hmcl.ui.game;

import com.jfoenix.controls.JFXCheckBox;
import javafx.scene.control.Tooltip;
import org.jackhuang.hmcl.game.JavaCompatibilityWarnings;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Builds the optional Java-advice confirmation with an instance-local, default-checked remember choice.
@NotNullByDefault
public final class JavaCompatibilityWarningDialog {
    /// Utility class.
    private JavaCompatibilityWarningDialog() {
    }

    /// Saves only on Continue with Remember selected; Cancel and window close never save the preference.
    public static MessageDialogPane create(String message, GameSettings.@Nullable Instance instance,
                                           boolean writable, Runnable proceed, Runnable cancel) {
        var remember = new JFXCheckBox(i18n("launch.java_warning.remember"));
        remember.setId("remember-java-warning");
        remember.setSelected(true);
        remember.setDisable(!writable || instance == null || !instance.isSavable());
        remember.setTooltip(new Tooltip(i18n("launch.java_warning.remember.subtitle")));
        return new MessageDialogPane.Builder(message, i18n("message.warning"), MessageDialogPane.MessageType.WARNING)
                .addBodyNode(remember)
                .addAction(i18n("launch.java_warning.continue"), () -> {
                    JavaCompatibilityWarnings.remember(instance, !remember.isDisabled() && remember.isSelected());
                    proceed.run();
                })
                .addCancel(cancel)
                .build();
    }
}
