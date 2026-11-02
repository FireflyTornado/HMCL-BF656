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
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.bf656.BF656Greeting;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.construct.AdvancedListBox;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// Exercises real font measurements, retained greeting state and the integrated sidebar without showing a window.
@NotNullByDefault
public final class BF656GreetingUiSmoke {
    /// Prevents construction of this standalone verification entry point.
    private BF656GreetingUiSmoke() {
    }

    /// Isolates all launcher settings and snapshots below the supplied output directory.
    public static void main(String @Unmodifiable [] args) {
        try {
            Path output = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
            Path state = output.resolve("state-" + System.nanoTime());
            System.setProperty("hmcl.home", state.resolve("user").toString());
            System.setProperty("hmcl.dir", state.resolve("local").toString());
            System.setProperty("hmcl.update_source.override", "");
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            SettingsManager.init();
            Platform.startup(() -> Platform.setImplicitExit(false));
            FutureTask<Boolean> check = new FutureTask<>(() -> {
                verifyGreetingLayout(output);
                verifyRootSidebar(output);
                return true;
            });
            Platform.runLater(check);
            require(check.get(60, TimeUnit.SECONDS), "Greeting UI verification timed out");
            System.out.println("BF656_GREETING_UI_OK: single/two lines, Unicode ellipses, fixed height, account binding, no tooltips, normal/compact sidebar.");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Verifies actual font-driven wrapping and clipping decisions without relying on character counts.
    private static void verifyGreetingLayout(Path output) throws Exception {
        ResourceBundle messages = I18n.getResourceBundle();
        AtomicInteger draws = new AtomicInteger();
        BF656GreetingPane pane = new BF656GreetingPane(name -> {
            draws.incrementAndGet();
            return new BF656Greeting.Greeting(BF656Greeting.Kind.EVENING, name);
        }, messages);
        pane.nicknameProperty().set("Steve");
        Label label = label(pane);
        StackPane surface = new StackPane(pane);
        surface.setPadding(new Insets(16));
        snapshot(surface, 232, 105, output.resolve("greeting-single-line.png"));
        require(label.getText().equals("晚上好，Steve。"), "A fitting greeting must stay on one line");
        double height = pane.getHeight();

        pane.nicknameProperty().set("VeryLongPlayerName_".repeat(12));
        snapshot(surface, 232, 105, output.resolve("greeting-long-nickname.png"));
        require(label.getText().startsWith("晚上好，\n"), "Long greetings must split after the template comma");
        require(label.getText().endsWith("…。"), "Only the name may be shortened; retain its final period");
        require(pane.getHeight() == height, "One/two-line greetings must reserve identical height");
        require(draws.get() == 1, "Changing a nickname must retain the selected personalized sentence");
        require(label.getTooltip() == null, "Greeting must not install a hover tooltip");
        requireLinesFit(label);

        snapshot(surface, 320, 105, output.resolve("greeting-wider.png"));
        require(draws.get() == 1, "Window/layout changes must not redraw a greeting");
        requireLinesFit(label);

        label.setStyle("-fx-font-size: 19px; -fx-font-weight: bold;");
        surface.requestLayout();
        snapshot(surface, 232, 130, output.resolve("greeting-larger-font.png"));
        require(label.getFont().getSize() == 19, "Larger-font fixture did not apply its font size");
        requireLinesFit(label);
        require(draws.get() == 1, "Font changes must not redraw a greeting");

        BF656GreetingPane lateNight = new BF656GreetingPane(
                name -> new BF656Greeting.Greeting(BF656Greeting.Kind.LATE_NIGHT, name), messages);
        lateNight.nicknameProperty().set("👨‍👩‍👧‍👦e\u0301_".repeat(12));
        snapshot(new StackPane(lateNight), 200, 80, output.resolve("greeting-late-night.png"));
        Label lateLabel = label(lateNight);
        require(lateLabel.getText().endsWith("…，\n熬夜玩吗？"), "Nickname-first copy must retain the comma and question");
        requireLinesFit(lateLabel);

        pane.nicknameProperty().set(null);
        snapshot(surface, 232, 130, output.resolve("greeting-welcome.png"));
        require(label.getText().replace("\n", "").equals("欢迎游玩Battlefield 656！"), "Missing accounts must show the complete agreed welcome");
        require(!label.getText().contains("…"), "Welcome copy cannot be ellipsized");
        requireLinesFit(label);
    }

    /// Checks the real RootPage places the greeting above menus and tracks selected game profile names.
    private static void verifyRootSidebar(Path output) throws Exception {
        SettingsManager.settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
        SettingsManager.settings().themeBrightnessModeProperty().set("light");
        GameDirectoryManager.init();
        RootPage root = new RootPage();
        StackPane surface = new StackPane(root);
        snapshot(surface, 920, 640, output.resolve("home-no-account.png"));
        require(root.getLeft().getChildren().get(0) instanceof BF656GreetingPane, "Greeting must be above the account menu");
        require(root.getLeft().getChildren().get(1) instanceof AdvancedListBox, "Existing sidebar menus must follow the greeting");
        BF656GreetingPane pane = (BF656GreetingPane) root.getLeft().getChildren().get(0);
        AdvancedListBox menus = (AdvancedListBox) root.getLeft().getChildren().get(1);
        require(label(pane).getText().replace("\n", "").equals("欢迎游玩Battlefield 656！"), "Root sidebar must handle a missing account");
        requireLinesFit(label(pane));
        double menuY = menus.getLayoutY();

        var steve = Accounts.FACTORY_OFFLINE.create("Steve", UUID.randomUUID());
        Accounts.selectedAccountProperty().set(steve);
        snapshot(surface, 920, 640, output.resolve("home-short-nickname.png"));
        require("Steve".equals(pane.nicknameProperty().get()), "Selected profile name must reach the greeting");
        require(label(pane).getText().contains("Steve"), "Sidebar must display the selected profile name");

        var longName = Accounts.FACTORY_OFFLINE.create("Eternity_Riguru_With_A_Very_Long_Name", UUID.randomUUID());
        Accounts.selectedAccountProperty().set(longName);
        snapshot(surface, 640, 480, output.resolve("home-long-nickname-compact.png"));
        require(label(pane).getText().contains("…"), "Long selected profile names must be shortened");
        require(menus.getLayoutY() == menuY, "Changing line count must not move the account/menu area");
        require(VBox.getVgrow(menus) == javafx.scene.layout.Priority.ALWAYS, "Menus must use the remaining sidebar height");
        requireLinesFit(label(pane));

        SettingsManager.settings().themeBrightnessModeProperty().set("dark");
        snapshot(surface, 640, 480, output.resolve("home-long-nickname-dark.png"));
        require(Themes.darkModeProperty().get(), "Dark-theme fixture did not activate the dark color scheme");
        require(label(pane).getTextFill().equals(Themes.getColorScheme().getOnSurface()), "Greeting must inherit the active theme text color");
        requireLinesFit(label(pane));

        Accounts.selectedAccountProperty().set(null);
        snapshot(surface, 640, 480, output.resolve("home-no-account-compact.png"));
        require(label(pane).getText().replace("\n", "").equals("欢迎游玩Battlefield 656！"), "Clearing an account must restore the welcome");
        requireLinesFit(label(pane));
    }

    /// Finds the pane's sole semantic text label without depending on skin internals.
    private static Label label(BF656GreetingPane pane) {
        return (Label) Objects.requireNonNull(pane.lookup("#bf656-greeting-text"));
    }

    /// Checks visible named-greeting lines against the actual label font and available content width.
    private static void requireLinesFit(Label label) {
        Text rendered = (Text) Objects.requireNonNull(label.lookup(".text"));
        require(rendered.getText().equals(label.getText()), "JavaFX clipped the displayed copy: " + rendered.getText());
        @Unmodifiable List<String> lines = label.getText().lines().toList();
        require(lines.size() <= 2, "Greeting must not exceed two lines");
        for (String line : lines) {
            Text ruler = new Text(line);
            ruler.setFont(label.getFont());
            require(Math.ceil(ruler.getLayoutBounds().getWidth()) <= label.getWidth(), "A greeting line was clipped: " + line);
        }
    }

    /// Styles and renders a detached scene without opening any interactive windows.
    private static void snapshot(StackPane surface, int width, int height, Path path) throws Exception {
        @Nullable Scene scene = surface.getScene();
        if (scene == null) {
            scene = new Scene(surface, width, height);
            StyleSheets.init(scene);
        }
        surface.setStyle("-fx-background-color: -monet-surface;");
        surface.resize(width, height);
        surface.applyCss();
        surface.layout();
        WritableImage image = surface.snapshot(null, null);
        var pixels = Objects.requireNonNull(image.getPixelReader());
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
