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

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.bf656.BF656Metadata;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.UpgradeDialog;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.LineComponent;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineToggleButton;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jackhuang.hmcl.upgrade.UpdateChannel;
import org.jackhuang.hmcl.upgrade.UpdateChecker;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Exercises real update controls and snapshots all states without networking, visible windows, or actual installation.
@NotNullByDefault
public final class LauncherUpdateUiSmoke {
    /// Prevents construction of this standalone verifier.
    private LauncherUpdateUiSmoke() {
    }

    /// Keeps settings and screenshots inside the supplied QA directory and exits after JavaFX verification.
    public static void main(String @Unmodifiable [] args) {
        try {
            Path output = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
            Path state = output.resolve("state-" + System.nanoTime());
            System.setProperty("hmcl.home", state.resolve("user").toString());
            System.setProperty("hmcl.dir", state.resolve("local").toString());
            System.setProperty("hmcl.version.override", "3.17.SNAPSHOT");
            System.setProperty("hmcl.update_source.override", "");
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            SettingsManager.init();
            UpdateChecker.init();
            require(UpdateChecker.errorProperty().get() == null, "Disabled checks must not start networking");
            Platform.startup(() -> Platform.setImplicitExit(false));
            FutureTask<Boolean> check = new FutureTask<>(() -> {
                verifySettings(output);
                verifyStatesAndPreferences(output);
                verifyDialog(output);
                return true;
            });
            Platform.runLater(check);
            require(check.get(45, TimeUnit.SECONDS), "UI verification timed out");
            System.out.println("BF656 update UI passed: seven states, actions, inverse preference compatibility, no player channel controls, compact confirmation.");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Verifies the actual settings page contains exactly the new status card and positive reminder.
    private static void verifySettings(Path output) throws Exception {
        SettingsManager.settings().disableAutoShowUpdateDialogProperty().set(true);
        SettingsPage page = new SettingsPage();
        LauncherUpdatePane pane = descendants(page).stream().filter(LauncherUpdatePane.class::isInstance)
                .map(LauncherUpdatePane.class::cast).findFirst().orElseThrow();
        require(pane.getContent().size() == 2, "Updates must contain one status row and one reminder");
        require(descendants(pane).stream().noneMatch(LineSelectButton.class::isInstance), "Player update channels must be removed");
        LineComponent row = (LineComponent) find(pane, "bf656-update-status");
        require(row.getTitle().equals(i18n("bf656.update.current_version", BF656Metadata.VERSION)), "Current BF656 version is missing");
        require(row.getSubtitle().equals(i18n("bf656.update.state.disabled")), "Disabled state leaks implementation details");
        LineToggleButton reminder = (LineToggleButton) find(pane, "bf656-update-popup-reminder");
        require(!reminder.isSelected(), "Existing disabled-popup preference was not preserved");
        SettingsManager.settings().disableAutoShowUpdateDialogProperty().set(false);
        require(reminder.isSelected(), "External popup preference changes must reach the control");
        reminder.setSelected(false);
        require(SettingsManager.settings().disableAutoShowUpdateDialogProperty().get(), "Positive toggle must update the inverse legacy field");
        snapshot(new StackPane(page), 920, 600, output.resolve("settings-disabled.png"));
    }

    /// Checks actual button dispatch and reminder synchronization through every view state.
    private static void verifyStatesAndPreferences(Path output) throws Exception {
        var state = new SimpleObjectProperty<>(new LauncherUpdateStatus(LauncherUpdateStatus.State.UNCHECKED, null));
        var disablePopup = new SimpleBooleanProperty(true);
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger installs = new AtomicInteger();
        LauncherUpdatePane pane = new LauncherUpdatePane(state, disablePopup, checks::incrementAndGet, installs::incrementAndGet);
        ButtonBase action = (ButtonBase) find(pane, "bf656-update-action");
        LineComponent row = (LineComponent) find(pane, "bf656-update-status");
        LineToggleButton reminder = (LineToggleButton) find(pane, "bf656-update-popup-reminder");
        require(!reminder.isSelected(), "Legacy disabled-popup value must start unchecked");
        reminder.setSelected(true);
        require(!disablePopup.get(), "Enabling reminders must clear the legacy disable flag");
        disablePopup.set(true);
        require(!reminder.isSelected(), "Disabling reminders elsewhere must uncheck the control");

        for (var phase : LauncherUpdateStatus.State.values()) {
            state.set(new LauncherUpdateStatus(phase, phase == LauncherUpdateStatus.State.AVAILABLE ? "0.3.13" : null));
            boolean disabled = phase == LauncherUpdateStatus.State.DISABLED || phase == LauncherUpdateStatus.State.CHECKING
                    || phase == LauncherUpdateStatus.State.UNVERIFIED;
            require(action.isDisable() == disabled, "Incorrect primary action availability: " + phase);
            String expectedStatus = phase == LauncherUpdateStatus.State.AVAILABLE
                    ? i18n("bf656.update.state.available", "0.3.13")
                    : i18n("bf656.update.state." + phase.name().toLowerCase(Locale.ROOT));
            require(row.getSubtitle().equals(expectedStatus), "Incorrect visible status: " + phase);
            int previousChecks = checks.get();
            int previousInstalls = installs.get();
            action.fire();
            require(checks.get() == previousChecks + (!disabled && phase != LauncherUpdateStatus.State.AVAILABLE ? 1 : 0),
                    "Incorrect check/retry dispatch: " + phase);
            require(installs.get() == previousInstalls + (phase == LauncherUpdateStatus.State.AVAILABLE ? 1 : 0),
                    "Incorrect install dispatch: " + phase);
            require(reminder.isDisable() == (phase == LauncherUpdateStatus.State.DISABLED), "Reminder enabled state is incorrect");
            pane.setMaxHeight(Region.USE_PREF_SIZE);
            snapshot(new StackPane(pane), 920, 230,
                    output.resolve("state-" + phase.name().toLowerCase(Locale.ROOT) + ".png"));
        }
        state.set(new LauncherUpdateStatus(LauncherUpdateStatus.State.AVAILABLE, "0.3.13"));
        snapshot(new StackPane(pane), 640, 240, output.resolve("state-available-compact.png"));
    }

    /// Confirms missing notes create no empty placeholder and both actions work with explicit restart wording.
    private static void verifyDialog(Path output) throws Exception {
        require(Metadata.CHANGELOG_URL.isEmpty(), "This fixture expects no configured release notes");
        Decorator decorator = new Decorator(new StackPane(), stage -> { });
        var field = Controllers.class.getDeclaredField("decorator");
        field.setAccessible(true);
        @Nullable Object previous = field.get(null);
        Stage stage = new Stage();
        try {
            decorator.attachStage(stage);
            field.set(null, decorator);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger closed = new AtomicInteger();
            var release = new RemoteVersion(UpdateChannel.STABLE, "0.3.13", "https://example.com/launcher.jar",
                    RemoteVersion.Type.JAR, new IntegrityCheck("SHA-1", "0".repeat(40)), false, false);
            UpgradeDialog dialog = new UpgradeDialog(release, accepted::incrementAndGet);
            dialog.addEventHandler(DialogCloseEvent.CLOSE, event -> closed.incrementAndGet());
            require(dialog.getHeading().get(0) instanceof Label title
                    && title.getText().equals(i18n("bf656.update.confirm.title", "0.3.13")), "Confirmation must identify the target version");
            require(dialog.getActions().size() == 2, "Missing notes must not add a browser link");
            ButtonBase accept = (ButtonBase) dialog.getActions().get(0);
            ButtonBase later = (ButtonBase) dialog.getActions().get(1);
            require(accept.getText().equals(i18n("bf656.update.confirm.accept")), "Restart action is unclear");
            require(later.getText().equals(i18n("bf656.update.confirm.later")), "Deferral action is unclear");
            require(dialog.getBody().get(0) instanceof VBox body && body.getChildren().get(0) instanceof Label message
                    && message.getText().equals(i18n("bf656.update.confirm.summary")), "Confirmation must explain restart and retained data");
            Node notes = find(dialog, "bf656-update-notes");
            require(!notes.isManaged() && !notes.isVisible(), "Unconfigured notes must not occupy layout space");
            snapshot(new StackPane(dialog), 920, 360, output.resolve("dialog-without-notes.png"));
            snapshot(new StackPane(dialog), 640, 320, output.resolve("dialog-compact.png"));
            accept.fire();
            later.fire();
            require(accepted.get() == 1 && closed.get() == 2, "Confirmation actions must install once and close correctly");
        } finally {
            field.set(null, previous);
            decorator.detachStage();
            stage.close();
        }
    }

    /// Finds a semantic control before or after JavaFX skins have been installed.
    private static Node find(Node root, String id) {
        return descendants(root).stream().filter(node -> id.equals(node.getId())).findFirst().orElseThrow();
    }

    /// Traverses semantic component contents as well as scene children without opening a stage.
    private static List<Node> descendants(Node node) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(node);
        if (node instanceof ScrollPane scrollPane) {
            @Nullable Node content = scrollPane.getContent();
            if (content != null)
                nodes.addAll(descendants(content));
        } else if (node instanceof ComponentList list) {
            for (Node child : list.getContent())
                nodes.addAll(descendants(child));
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable())
                nodes.addAll(descendants(child));
        }
        return nodes;
    }

    /// Saves actual styled JavaFX controls for visual review at normal and compact widths.
    private static void snapshot(StackPane surface, int width, int height, Path path) throws Exception {
        Scene scene = new Scene(surface, width, height);
        StyleSheets.init(scene);
        surface.setStyle("-fx-background-color: white; -fx-padding: 16;");
        surface.applyCss();
        surface.layout();
        WritableImage image = surface.snapshot(null, null);
        PixelReader pixels = Objects.requireNonNull(image.getPixelReader());
        BufferedImage bitmap = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++)
                bitmap.setRGB(x, y, pixels.getArgb(x, y));
        }
        ImageIO.write(bitmap, "png", path.toFile());
    }

    /// Fails verification independently of JVM assertion flags.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
