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
package org.jackhuang.hmcl.ui.account;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.auth.OAuth;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jackhuang.hmcl.upgrade.IntegrityChecker;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/// Checks signed-package login configuration, the original dialog states and the local OAuth callback without signing in.
@NotNullByDefault
public final class MicrosoftAccountLoginSmoke {
    /// Prevents instantiation of the standalone verifier.
    private MicrosoftAccountLoginSmoke() {
    }

    /// Runs with isolated settings and production classes loaded from the actual signed release JAR.
    public static void main(String @Unmodifiable [] args) {
        try {
            Path output = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
            Path state = output.resolve("state-" + System.nanoTime());
            System.setProperty("hmcl.home", state.resolve("user").toString());
            System.setProperty("hmcl.dir", state.resolve("local").toString());
            System.setProperty("hmcl.update_source.override", "");
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            SettingsManager.init();
            require(IntegrityChecker.isSelfVerified(), "The test must use the actual signed release");
            require(!Accounts.OAUTH_CALLBACK.getClientId().isBlank(), "The release must contain Microsoft sign-in configuration");
            try (OAuth.Session session = Accounts.OAUTH_CALLBACK.startServer()) {
                require(URI.create(session.getRedirectURI()).getHost().equals("localhost"), "OAuth callback must use localhost");
            }
            Platform.startup(() -> Platform.setImplicitExit(false));
            FutureTask<Boolean> check = new FutureTask<>(() -> {
                verifyDialog(output);
                return true;
            });
            Platform.runLater(check);
            require(check.get(60, TimeUnit.SECONDS), "Login dialog checks did not complete");
            System.out.println("BF656_MICROSOFT_LOGIN_OK: signed configuration, visible missing-ID warning, original browser/device layouts and localhost callback.");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Exercises rendering states directly so no browser, external login task or user account is touched.
    private static void verifyDialog(Path output) throws Exception {
        @Nullable String previousId = System.getProperty("hmcl.microsoft.auth.id");
        try {
            System.setProperty("hmcl.microsoft.auth.id", "");
            MicrosoftAccountLoginPane pane = new MicrosoftAccountLoginPane();
            StackPane surface = new StackPane(pane);
            var loginField = MicrosoftAccountLoginPane.class.getDeclaredField("btnLogin");
            loginField.setAccessible(true);
            JFXButton login = (JFXButton) loginField.get(pane);
            snapshot(surface, output.resolve("microsoft-missing-id.png"));
            require(login.isDisable(), "Missing-ID login must remain disabled");
            require(containsWarning(pane), "Missing-ID warning must be attached to the displayed dialog");

            System.clearProperty("hmcl.microsoft.auth.id");
            Class<?> stepType = Class.forName(MicrosoftAccountLoginPane.class.getName() + "$Step");
            Method render = MicrosoftAccountLoginPane.class.getDeclaredMethod("onStep", stepType);
            render.setAccessible(true);
            // The fixture was deliberately constructed without an ID to avoid automatic network authentication.
            login.setDisable(false);
            render(pane, render, "Init", new Class<?>[]{}, new Object[]{});
            snapshot(surface, output.resolve("microsoft-initial.png"));
            require(!login.isDisable() && pane.lookup(".hint.info") != null, "Configured initial dialog must show its original instructions");
            render(pane, render, "WaitForOpenBrowser", new Class<?>[]{String.class}, new Object[]{"https://login.live.com/"});
            snapshot(surface, output.resolve("microsoft-browser.png"));
            require(pane.lookup(".hint.info") != null, "Browser instructions must be visible");
            render(pane, render, "WaitForScanQrCode", new Class<?>[]{String.class, String.class}, new Object[]{"TEST1234", "https://www.microsoft.com/link"});
            snapshot(surface, output.resolve("microsoft-device.png"));
            require(pane.lookup(".code-label") != null, "Original device-code layout must remain available");
        } finally {
            if (previousId == null)
                System.clearProperty("hmcl.microsoft.auth.id");
            else
                System.setProperty("hmcl.microsoft.auth.id", previousId);
        }
    }

    /// Invokes an existing private rendering state without starting either authentication flow.
    private static void render(MicrosoftAccountLoginPane pane, Method render, String name,
                               Class<?> @Unmodifiable [] parameters, Object @Unmodifiable [] arguments) throws Exception {
        Class<?> type = Class.forName(MicrosoftAccountLoginPane.class.getName() + "$Step$" + name);
        var constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        render.invoke(pane, constructor.newInstance(arguments));
    }

    /// Searches the attached scene graph rather than merely checking whether a warning object was allocated.
    private static boolean containsWarning(Node node) {
        if (node instanceof HintPane && node.getStyleClass().contains("warning"))
            return true;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (containsWarning(child))
                    return true;
            }
        }
        return false;
    }

    /// Renders the unmodified launcher dialog styling to a detached screenshot.
    private static void snapshot(StackPane surface, Path path) throws Exception {
        if (surface.getScene() == null) {
            Scene scene = new Scene(surface, 700, 420);
            StyleSheets.init(scene);
        }
        surface.resize(700, 420);
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

    /// Fails validation independently of JVM assertion settings.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
