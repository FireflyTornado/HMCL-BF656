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

import org.jackhuang.hmcl.launch.GcStrategyException;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Shows a translated, actionable GC failure without modifying or automatically retrying the strategy.
@NotNullByDefault
public final class GcStrategyErrorDialog {
    /// Utility class.
    private GcStrategyErrorDialog() {
    }

    /// Creates a failure dialog for game launch or script export, with the actual runtime version and path.
    public static MessageDialogPane create(GcStrategyException failure, String title, Runnable openSettings) {
        String strategy = i18n("settings.advanced.gc_strategy." + failure.getGcStrategy().name().toLowerCase(Locale.ROOT));
        String message = i18n("launch.failed.gc." + failure.getReason().name().toLowerCase(Locale.ROOT), strategy)
                + "\n\n" + i18n("launch.failed.gc.java", failure.getJavaVersion(), failure.getExecutable());
        return new MessageDialogPane.Builder(message, title, MessageDialogPane.MessageType.ERROR)
                .addAction(i18n("launch.failed.gc.open_settings"), openSettings)
                .addCancel(i18n("launch.failed.gc.cancel"), null)
                .build();
    }
}
