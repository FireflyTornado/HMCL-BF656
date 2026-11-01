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
package org.jackhuang.hmcl.ui.main;

import com.jfoenix.controls.JFXButton;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.beans.value.WeakChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.layout.Region;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.bf656.BF656Metadata;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineComponent;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.main.LauncherUpdateStatus.State;
import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jackhuang.hmcl.upgrade.UpdateChecker;
import org.jackhuang.hmcl.upgrade.UpdateHandler;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Player-facing launcher updates with one action and the existing popup preference.
@NotNullByDefault
public final class LauncherUpdatePane extends ComponentList {
    /// State binding retained while this pane is visible.
    private final ObservableValue<LauncherUpdateStatus> status;
    /// Current version and plain-language update status.
    private final UpdateRow updateRow;
    /// Primary action: check, retry, update, or manual download as appropriate.
    private final JFXButton actionButton = new JFXButton();
    /// Positive-worded preference backed by the original inverse setting.
    private final LineToggleButton popupReminder = new LineToggleButton();
    /// Strong reference for the weak status subscription.
    private final ChangeListener<LauncherUpdateStatus> statusListener = (observable, oldValue, newValue) ->
            render(Objects.requireNonNull(newValue));
    /// Strong reference for the weak subscription to preferences changed elsewhere.
    private final ChangeListener<Boolean> reminderListener;

    /// Connects the player interface to the configured BF656 update source.
    public LauncherUpdatePane() {
        this(observeStatus(), settings().disableAutoShowUpdateDialogProperty(), UpdateChecker::requestCheckUpdate, () -> {
            @Nullable RemoteVersion latest = UpdateChecker.getLatestVersion();
            if (latest != null && UpdateChecker.isOutdated())
                UpdateHandler.updateFrom(latest);
        });
    }

    /// Allows isolated verification of actual controls without network requests or replacing the update service.
    LauncherUpdatePane(ObservableValue<LauncherUpdateStatus> status, BooleanProperty disableAutoPopup,
                       Runnable checkUpdate, Runnable installUpdate) {
        this.status = status;
        setId("bf656-launcher-update");
        actionButton.setId("bf656-update-action");
        actionButton.getStyleClass().add("jfx-button-border");
        actionButton.setMinWidth(Region.USE_PREF_SIZE);
        updateRow = new UpdateRow(actionButton);
        updateRow.setId("bf656-update-status");
        updateRow.setTitle(i18n("bf656.update.current_version", BF656Metadata.VERSION));

        popupReminder.setId("bf656-update-popup-reminder");
        popupReminder.setTitle(i18n("bf656.update.popup_reminder"));
        popupReminder.setSubtitle(i18n("bf656.update.popup_reminder.subtitle"));
        // Preserve the serialized preference: enabled reminder means the old disable flag is false.
        popupReminder.setSelected(!disableAutoPopup.get());
        popupReminder.selectedProperty().addListener((observable, oldValue, newValue) -> disableAutoPopup.set(!newValue));
        reminderListener = (observable, oldValue, newValue) -> popupReminder.setSelected(!newValue);
        disableAutoPopup.addListener(new WeakChangeListener<>(reminderListener));

        actionButton.setOnAction(event -> {
            State state = Objects.requireNonNull(status.getValue()).state();
            if (state == State.AVAILABLE) {
                installUpdate.run();
            } else if (state == State.UNVERIFIED && !Metadata.MANUAL_UPDATE_URL.isEmpty()) {
                FXUtils.openLink(Metadata.MANUAL_UPDATE_URL);
            } else if (state != State.DISABLED && state != State.CHECKING && state != State.UNVERIFIED) {
                checkUpdate.run();
            }
        });
        getContent().setAll(updateRow, popupReminder);
        status.addListener(new WeakChangeListener<>(statusListener));
        render(Objects.requireNonNull(status.getValue()));
    }

    /// Observes update checks without exposing channels or exception messages to players.
    private static ObservableValue<LauncherUpdateStatus> observeStatus() {
        return Bindings.createObjectBinding(() -> LauncherUpdateStatus.resolve(UpdateChecker.isEnabled(), UpdateChecker.isCheckingUpdate(),
                        UpdateChecker.getLatestVersion(), UpdateChecker.errorProperty().get(), UpdateChecker.isOutdated()),
                UpdateChecker.latestVersionProperty(), UpdateChecker.checkingUpdateProperty(),
                UpdateChecker.errorProperty(), UpdateChecker.outdatedProperty());
    }

    /// Renders status and the action together so stale upgrade actions cannot remain enabled during a check.
    private void render(LauncherUpdateStatus value) {
        State state = value.state();
        String text = state == State.AVAILABLE
                ? i18n("bf656.update.state.available", Objects.requireNonNull(value.targetVersion()))
                : i18n("bf656.update.state." + state.name().toLowerCase(java.util.Locale.ROOT));
        updateRow.setSubtitle(text);
        updateRow.pseudoClassStateChanged(PseudoClass.getPseudoClass("active"), state == State.AVAILABLE);
        boolean manualDownload = state == State.UNVERIFIED && !Metadata.MANUAL_UPDATE_URL.isEmpty();
        actionButton.setText(i18n(switch (state) {
            case AVAILABLE -> "bf656.update.action.install";
            case FAILED -> "bf656.update.action.retry";
            case CHECKING -> "bf656.update.action.checking";
            case DISABLED -> "bf656.update.action.unavailable";
            case UNVERIFIED -> manualDownload ? "bf656.update.action.download" : "bf656.update.action.unavailable";
            default -> "bf656.update.action.check";
        }));
        actionButton.setDisable(state == State.DISABLED || state == State.CHECKING
                || (state == State.UNVERIFIED && !manualDownload));
        popupReminder.setDisable(state == State.DISABLED);
    }

    /// Noninteractive row that leaves the primary button as the only clickable update action.
    @NotNullByDefault
    private static final class UpdateRow extends LineComponent {
        /// Places the update action at the trailing edge of the version/status row.
        private UpdateRow(JFXButton action) {
            setNode(IDX_TRAILING, action);
        }
    }
}
