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

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ButtonBase;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.game.GcStrategy;
import org.jackhuang.hmcl.launch.GcStrategyException;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.GameSettingsPresetID;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Isolated JavaFX smoke check of the actual settings row, its bindings and wrapped descriptions.
@NotNullByDefault
public final class GcStrategyUiSmoke {
    /// Utility entry point.
    private GcStrategyUiSmoke() {
    }

    /// Keeps all settings in an isolated workspace and never opens an interactive window or starts Minecraft.
    public static void main(String @Unmodifiable [] args) {
        try {
            Path output = Path.of(args[0]).toAbsolutePath();
            Files.createDirectories(output);
            Path state = output.resolve("state-" + System.nanoTime());
            System.setProperty("hmcl.home", state.resolve("user").toString());
            System.setProperty("hmcl.dir", state.resolve("local").toString());
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            SettingsManager.init();
            Platform.startup(() -> Platform.setImplicitExit(false));
            onFx(() -> {
                var initialize = Launcher.class.getDeclaredMethod("initializeSettingsRuntime");
                initialize.setAccessible(true);
                initialize.invoke(null);
                verifyAndRender(output);
                verifyAndRenderErrors(output);
                return true;
            });
            System.out.println("GC strategy UI smoke passed: four strategies, descriptions, overrides, memory, layout and failure actions.");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Exercises actual settings controls, then snapshots each selected strategy at two useful widths.
    @SuppressWarnings("unchecked")
    private static void verifyAndRender(Path output) throws Exception {
        var page = new GameSettingsPage<>(GameSettings.Preset.class, null);
        var preset = new GameSettings.Preset(
                GameSettingsPresetID.parse("game-settings-preset:123e4567-e89b-12d3-a456-426614174000"));
        preset.maxMemoryProperty().setValue(6144);
        var select = GameSettingsPage.class.getDeclaredMethod("selectPreset", GameSettings.Preset.class);
        select.setAccessible(true);
        select.invoke(page, preset);
        new Scene(page, 960, 720);
        page.applyCss();
        page.layout();

        LineSelectButton<GcStrategy> row = (LineSelectButton<GcStrategy>) descendants(page).stream()
                .filter(node -> node instanceof LineSelectButton<?> button
                        && i18n("settings.advanced.gc_strategy").equals(button.getTitle()))
                .findFirst().orElseThrow();
        ComponentList owner = (ComponentList) descendants(page).stream()
                .filter(node -> node instanceof ComponentList list && list.getContent().contains(row))
                .findFirst().orElseThrow();
        require(!owner.isManaged(), "GC strategy should start hidden with other advanced JVM settings");
        page.showAdvancedOptions();
        require(owner.isManaged(), "GC strategy should be visible with advanced settings enabled");
        require(row.getItems().equals(List.of(GcStrategy.TUNED_G1, GcStrategy.G1, GcStrategy.ZGC, GcStrategy.DEFAULT)),
                "Exactly four strategies must be available, with a single unified ZGC option");

        for (GcStrategy strategy : GcStrategy.values()) {
            row.setValue(strategy);
            require(preset.gcStrategyProperty().getValue() == strategy, "Selection must update the preset");
            require(row.getSubtitle().contains(i18n("settings.advanced.gc_strategy."
                    + strategy.name().toLowerCase(Locale.ROOT) + ".desc")), "Selected description must be visible");
            require(!row.getSubtitle().contains("\n"), "Descriptions must remain one paragraph");
            if (strategy == GcStrategy.ZGC) {
                require(!row.getSubtitle().contains("G1GC"), "ZGC help must omit the removed fallback/error sentence");
                require(row.getSubtitle().contains("21") && row.getSubtitle().contains("15–20"),
                        "ZGC help must explain its Java version ranges");
            }
            require(preset.maxMemoryProperty().getValue() == 6144, "Strategy selection must preserve heap size");
        }
        preset.jvmOptionsProperty().setValue("-XX:+UseParallelGC");
        require(row.getSubtitle().startsWith(i18n("settings.advanced.gc_strategy.custom")),
                "Custom GC arguments must display their priority");
        preset.jvmOptionsProperty().setValue("");
        preset.noOptimizingJVMOptionsProperty().setValue(true);
        require(row.getSubtitle().startsWith(i18n("settings.advanced.gc_strategy.disabled")),
                "Disabling automatic optimizations must display inactive status");
        preset.noOptimizingJVMOptionsProperty().setValue(false);
        preset.noJVMOptionsProperty().setValue(true);
        require(row.getSubtitle().startsWith(i18n("settings.advanced.gc_strategy.disabled")),
                "Disabling all automatic arguments must display inactive status");
        preset.noJVMOptionsProperty().setValue(false);

        owner.getContent().remove(row);
        StackPane surface = new StackPane(row);
        surface.setStyle("-fx-background-color: #fafbff; -fx-padding: 16; -fx-font-family: 'Microsoft YaHei'; -fx-font-size: 14px;");
        Scene scene = new Scene(surface);
        for (String file : List.of("root.css", "brightness-light.css", "blue.css")) {
            scene.getStylesheets().add(Objects.requireNonNull(
                    GcStrategyUiSmoke.class.getResource("/assets/css/" + file)).toExternalForm());
        }
        for (GcStrategy strategy : GcStrategy.values()) {
            row.setValue(strategy);
            for (int width : List.of(600, 960)) {
                surface.resize(width, surface.prefHeight(width));
                surface.applyCss();
                surface.resize(width, surface.prefHeight(width));
                surface.layout();
                snapshot(surface.snapshot(null, null),
                        output.resolve(strategy.name().toLowerCase(Locale.ROOT) + "-" + width + ".png"));
                require(row.getHeight() + 0.5 >= row.minHeight(row.getWidth()),
                        "Wrapped description must fit: height=" + row.getHeight()
                                + ", minimum=" + row.minHeight(row.getWidth()) + ", width=" + row.getWidth());
                Label selected = (Label) row.lookup(".trailing-label");
                require(selected.getWidth() + 0.5 >= selected.prefWidth(-1), "Selected strategy name must remain readable");
            }
        }
    }

    /// Verifies each distinct error category, the selected runtime details and both recovery actions.
    private static void verifyAndRenderErrors(Path output) throws Exception {
        for (GcStrategyException.Reason reason : GcStrategyException.Reason.values()) {
            var opened = new AtomicInteger();
            var closed = new AtomicInteger();
            var failure = new GcStrategyException(reason, "21.0.11",
                    Path.of("Java", "bin", "java.exe").toAbsolutePath(), null);
            var dialog = GcStrategyErrorDialog.create(failure, i18n("launch.failed"), opened::incrementAndGet);
            dialog.addEventHandler(DialogCloseEvent.CLOSE, event -> closed.incrementAndGet());
            @Unmodifiable List<ButtonBase> buttons = descendants(dialog).stream()
                    .filter(ButtonBase.class::isInstance).map(ButtonBase.class::cast).toList();
            require(buttons.size() == 2, "GC errors must offer settings and cancel, without automatic retry");
            String text = descendants(dialog).stream().filter(javafx.scene.text.Text.class::isInstance)
                    .map(javafx.scene.text.Text.class::cast).map(javafx.scene.text.Text::getText)
                    .collect(java.util.stream.Collectors.joining());
            require(text.contains(failure.getJavaVersion()) && text.contains(failure.getExecutable().toString()),
                    "GC errors must identify the actual selected Java");
            require(text.contains(i18n("launch.failed.gc." + reason.name().toLowerCase(Locale.ROOT))),
                    "GC errors must distinguish unsupported Java, failed checks and timeouts");
            StackPane surface = new StackPane(dialog);
            surface.setStyle("-fx-background-color: white; -fx-padding: 16; -fx-font-size: 14px;");
            new Scene(surface);
            surface.resize(680, surface.prefHeight(680));
            surface.applyCss();
            surface.resize(680, surface.prefHeight(680));
            surface.layout();
            snapshot(surface.snapshot(null, null), output.resolve("error-" + reason.name().toLowerCase(Locale.ROOT) + ".png"));
            buttons.stream().filter(button -> i18n("launch.failed.gc.open_settings").equals(button.getText()))
                    .findFirst().orElseThrow().fire();
            require(opened.get() == 1 && closed.get() == 1, "Settings action must navigate once and close the dialog");
            var cancelled = GcStrategyErrorDialog.create(failure, i18n("launch.failed"), opened::incrementAndGet);
            cancelled.getCancelButton().fire();
            require(opened.get() == 1, "Cancel must not navigate, change policy or retry launch");
        }
    }

    /// Traverses semantic component content as well as JavaFX children, without requiring popup windows.
    private static List<Node> descendants(Node node) {
        List<Node> result = new ArrayList<>();
        result.add(node);
        if (node instanceof ComponentList list) {
            for (Node child : list.getContent())
                result.addAll(descendants(child));
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable())
                result.addAll(descendants(child));
        }
        return result;
    }

    /// Writes the actual JavaFX pixel buffer to a PNG without requiring the javafx.swing module.
    private static void snapshot(WritableImage image, Path file) throws Exception {
        BufferedImage bitmap = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++)
                bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        ImageIO.write(bitmap, "png", file.toFile());
    }

    /// Fails the isolated verification with a useful message.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    /// Runs JavaFX work from the main verification thread.
    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(45, TimeUnit.SECONDS);
    }
}
