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
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.game.MemoryCleaner;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineSelectButton;
import org.jackhuang.hmcl.ui.construct.LineComponent;
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
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Isolated check of the actual combined cleanup control, legacy overrides and selected descriptions.
@NotNullByDefault
public final class MemoryCleanupUiSmoke {
    /// Utility entry point.
    private MemoryCleanupUiSmoke() {
    }

    /// Uses disposable local/user state and never launches a game, shows an interactive window or cleans memory.
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
                return true;
            });
            Files.writeString(output.resolve("ui-smoke-passed.txt"),
                    "Off/three profiles, legacy fields, partial inheritance, reset, compact menu and selected descriptions passed.\n");
            System.out.println("Memory cleanup UI smoke passed; no cleanup executed.");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Loads old settings without rewriting them, tests the actual control and renders its selected descriptions.
    @SuppressWarnings("unchecked")
    private static void verifyAndRender(Path output) throws Exception {
        GameSettings.Preset parent = SettingsManager.getDefaultGameSettingsPresetOrCreate();
        parent.memoryCleanupProperty().setValue(false);
        parent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.FULL);
        parent.maxMemoryProperty().setValue(6144);
        var page = new GameSettingsPage<>(GameSettings.Preset.class, null);
        var select = GameSettingsPage.class.getDeclaredMethod("selectPreset", GameSettings.Preset.class);
        select.setAccessible(true);
        String before = LauncherSettings.SETTINGS_GSON.toJson(parent, GameSettings.Preset.class);
        select.invoke(page, parent);
        new Scene(page, 960, 720);
        page.applyCss();
        page.layout();
        var row = findCleanupRow(page);
        require(before.equals(LauncherSettings.SETTINGS_GSON.toJson(parent, GameSettings.Preset.class)),
                "Displaying the old pair of fields must not rewrite configuration");
        require(row.getValue() == null && parent.memoryCleanupProfileProperty().getValue() == MemoryCleaner.Profile.FULL,
                "Disabled legacy settings must display Off without resetting their saved profile");
        require(row.getItems().size() == 4 && row.getItems().get(0) == null, "Menu must contain Off plus three profiles");
        require(row.getDescriptionConverter() == null, "Popup must not display per-entry descriptions");
        for (@Nullable MemoryCleaner.Profile profile : row.getItems()) {
            require(!row.getConverter().apply(profile).contains("\n"), "Menu names must be compact");
        }
        parent.memoryCleanupProperty().setValue(true);
        require(row.getValue() == MemoryCleaner.Profile.FULL, "Enabled old settings must retain their profile");
        for (MemoryCleaner.Profile profile : MemoryCleaner.Profile.values()) {
            row.setValue(profile);
            require(parent.memoryCleanupProperty().getValue() && parent.memoryCleanupProfileProperty().getValue() == profile,
                    "Selecting a profile must enable cleanup with that profile");
            require(row.getSubtitle().contains(i18n("settings.memory.cleanup.profile." + profile.argument() + ".desc")),
                    "Selected descriptions must retain the original guidance");
            require(parent.maxMemoryProperty().getValue() == 6144, "Cleanup selection must not change heap allocation");
        }
        row.setValue(null);
        require(!parent.memoryCleanupProperty().getValue() && parent.memoryCleanupProfileProperty().getValue() == MemoryCleaner.Profile.FULL,
                "Off must disable cleanup without forgetting the previous profile");
        verifyInstance(parent);

        ComponentList owner = (ComponentList) descendants(page).stream()
                .filter(node -> node instanceof ComponentList list && list.getContent().contains(row)).findFirst().orElseThrow();
        owner.getContent().remove(row);
        StackPane surface = new StackPane(row);
        surface.setStyle("-fx-background-color: #fafbff; -fx-padding: 16; -fx-font-family: 'Microsoft YaHei'; -fx-font-size: 14px;");
        Scene scene = new Scene(surface);
        for (String file : List.of("root.css", "brightness-light.css", "blue.css")) {
            scene.getStylesheets().add(Objects.requireNonNull(
                    MemoryCleanupUiSmoke.class.getResource("/assets/css/" + file)).toExternalForm());
        }
        for (@Nullable MemoryCleaner.Profile profile : row.getItems()) {
            row.setValue(profile);
            for (int width : List.of(600, 960)) {
                surface.resize(width, surface.prefHeight(width));
                surface.applyCss();
                surface.resize(width, surface.prefHeight(width));
                surface.layout();
                snapshot(surface.snapshot(null, null),
                        output.resolve((profile == null ? "off" : profile.argument()) + "-" + width + ".png"));
                require(row.getHeight() + 0.5 >= row.minHeight(row.getWidth()), "Selected guidance must not be clipped");
                Label selected = (Label) row.lookup(".trailing-label");
                require(selected.getWidth() + 0.5 >= selected.prefWidth(-1), "Selected option must remain readable");
            }
        }
    }

    /// Exercises a legacy partial override, parent updates, explicit choice and restoration of both inherited fields.
    @SuppressWarnings("unchecked")
    private static void verifyInstance(GameSettings.Preset parent) throws Exception {
        parent.memoryCleanupProperty().setValue(true);
        parent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.DEFAULT);
        var instance = new GameSettings.Instance();
        instance.memoryCleanupProperty().setValue(false);
        instance.getOverrideProperties().add(GameSettings.PROPERTY_MEMORY_CLEANUP);
        var page = new GameSettingsPage<>(GameSettings.Instance.class, new SimpleObjectProperty<>());
        var field = GameSettingsPage.class.getDeclaredField("currentSetting");
        field.setAccessible(true);
        var current = (ObjectProperty<GameSettings.Instance>) field.get(page);
        String before = LauncherSettings.SETTINGS_GSON.toJson(instance, GameSettings.Instance.class);
        current.set(instance);
        new Scene(page, 960, 720);
        page.applyCss();
        page.layout();
        var row = findCleanupRow(page);
        require(row.getValue() == null, "Legacy explicit Off must override an enabled parent");
        require(before.equals(LauncherSettings.SETTINGS_GSON.toJson(instance, GameSettings.Instance.class)),
                "Loading partial overrides must not merge or rewrite their values");
        row.setValue(MemoryCleaner.Profile.SAFE);
        require(instance.getOverrideProperties().containsAll(List.of(GameSettings.PROPERTY_MEMORY_CLEANUP,
                GameSettings.PROPERTY_MEMORY_CLEANUP_PROFILE)), "Explicit selection must override both properties");
        parent.memoryCleanupProfileProperty().setValue(MemoryCleaner.Profile.FULL);
        require(row.getValue() == MemoryCleaner.Profile.SAFE, "An explicit choice must not follow parent changes");
        var inheritField = LineComponent.class.getDeclaredField("titleTrailing");
        inheritField.setAccessible(true);
        var inherit = (Node) Objects.requireNonNull(inheritField.get(row));
        inherit.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, false, null));
        require(!instance.getOverrideProperties().contains(GameSettings.PROPERTY_MEMORY_CLEANUP)
                        && !instance.getOverrideProperties().contains(GameSettings.PROPERTY_MEMORY_CLEANUP_PROFILE),
                "Restore inheritance must clear both overrides");
        require(row.getValue() == MemoryCleaner.Profile.FULL, "Restoring inheritance must reveal the parent profile");
        parent.memoryCleanupProperty().setValue(false);
        require(row.getValue() == null, "Inherited enable changes must update Off immediately");
    }

    /// Finds the sole cleanup row, ensuring the old separate profile row and switch are absent.
    @SuppressWarnings("unchecked")
    private static LineSelectButton<MemoryCleaner.@Nullable Profile> findCleanupRow(Node page) {
        @Unmodifiable List<Node> rows = descendants(page).stream()
                .filter(node -> node instanceof LineSelectButton<?> button
                        && i18n("settings.memory.cleanup").equals(button.getTitle())).toList();
        require(rows.size() == 1, "Cleanup must have exactly one selection row");
        require(descendants(page).stream().noneMatch(node -> node instanceof LineSelectButton<?> button
                && i18n("settings.memory.cleanup.profile").equals(button.getTitle())), "Separate profile row must be removed");
        return (LineSelectButton<MemoryCleaner.@Nullable Profile>) rows.get(0);
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
