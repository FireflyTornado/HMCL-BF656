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
package org.jackhuang.hmcl.bf656;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.UpgradeDialog;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jackhuang.hmcl.ui.main.SettingsPage;
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

/// Isolated smoke check of step-one defaults; renders real controls without showing windows or starting an update.
@NotNullByDefault
public final class BF656UpdateUiSmoke {
    /// Prevents construction of this standalone verification utility.
    private BF656UpdateUiSmoke() {
    }

    /// Keeps all state in the supplied QA directory and exits after validating unconfigured update UI.
    public static void main(String @Unmodifiable [] args) {
        try {
            Path output = Path.of(args[0]).toAbsolutePath();
            Files.createDirectories(output);
            Path state = output.resolve("state-" + System.nanoTime());
            System.setProperty("hmcl.home", state.resolve("user").toString());
            System.setProperty("hmcl.dir", state.resolve("local").toString());
            System.setProperty("hmcl.version.override", "3.17.SNAPSHOT");
            System.setProperty("hmcl.update_source.override", "");
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            SettingsManager.init();
            require(Metadata.VERSION.equals("3.17.SNAPSHOT"), "Upstream version must remain independent");
            require(UpdateChannel.getChannel() == UpdateChannel.STABLE, "BF656 must default to stable");
            UpdateChecker.init();
            require(UpdateChecker.errorProperty().get() == null, "Disabled updates must not attempt signature verification");
            Platform.startup(() -> Platform.setImplicitExit(false));
            FutureTask<Boolean> check = new FutureTask<>(() -> {
                verifySettings(output);
                verifyDialog(output);
                return true;
            });
            Platform.runLater(check);
            require(check.get(45, TimeUnit.SECONDS), "UI verification did not complete");
            System.out.println("BF656 update UI smoke passed: disabled settings, independent version/channel, optional notes, update/cancel actions.");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Checks the actual update row, including the disabled message instead of a misleading latest-version claim.
    private static void verifySettings(Path output) throws Exception {
        SettingsPage page = new SettingsPage();
        LineSelectButton<?> updateRow = descendants(page).stream()
                .filter(node -> node instanceof LineSelectButton<?> row
                        && row.getTitle().equals(i18n("update") + " (" + BF656Metadata.VERSION + ")"))
                .map(node -> (LineSelectButton<?>) node).findFirst().orElseThrow();
        require(updateRow.isDisable(), "Unconfigured updates must disable channel selection");
        require(updateRow.getSubtitle().equals(i18n("bf656.update.disabled")), "Unconfigured updates must have an explicit status");
        require(updateRow.getValue() == UpdateChannel.STABLE, "The update row must show the BF656 channel");
        snapshot(new StackPane(page), 920, 600, output.resolve("settings-disabled.png"));
    }

    /// Confirms missing notes keep working actions and never introduce a link to upstream HMCL release notes.
    private static void verifyDialog(Path output) throws Exception {
        require(Metadata.CHANGELOG_URL.isEmpty(), "This smoke check expects the unconfigured step-one notes default");
        require(Metadata.MANUAL_UPDATE_URL.isEmpty(), "Step-one manual downloads must not fall back to HMCL");
        Decorator decorator = new Decorator(new StackPane(), stage -> { });
        var field = Controllers.class.getDeclaredField("decorator");
        field.setAccessible(true);
        @Nullable Object previous = field.get(null);
        Stage stage = new Stage();
        try {
            decorator.attachStage(stage);
            field.set(null, decorator);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger cancelled = new AtomicInteger();
            var release = new RemoteVersion(UpdateChannel.STABLE, "0.3.12", "https://example.com/launcher.jar",
                    RemoteVersion.Type.JAR, new IntegrityCheck("SHA-1", "0".repeat(40)), false, false);
            UpgradeDialog dialog = new UpgradeDialog(release, accepted::incrementAndGet);
            dialog.addEventHandler(DialogCloseEvent.CLOSE, event -> cancelled.incrementAndGet());
            require(dialog.getActions().size() == 2, "Missing notes must leave only update and cancel actions");
            require(dialog.getBody().size() == 1 && dialog.getBody().get(0) instanceof Label label
                    && label.getText().equals(i18n("update.changelog.empty")), "Missing notes must render an explicit placeholder");
            ((ButtonBase) dialog.getActions().get(0)).fire();
            ((ButtonBase) dialog.getActions().get(1)).fire();
            require(accepted.get() == 1 && cancelled.get() == 1, "Update and cancel must remain functional without notes");
            snapshot(new StackPane(dialog), 920, 450, output.resolve("dialog-without-notes.png"));
        } finally {
            field.set(null, previous);
            decorator.detachStage();
            stage.close();
        }
    }

    /// Traverses component content before skins have been installed by a scene.
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

    /// Renders a styled, unshown scene to a PNG for visual verification.
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

    /// Fails with an actionable description instead of relying on JVM assertion flags.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
