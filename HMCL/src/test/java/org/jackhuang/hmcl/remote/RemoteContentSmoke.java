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
package org.jackhuang.hmcl.remote;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// Runs isolated offscreen desktop smoke checks and writes screenshots without opening a visible window.
@NotNullByDefault
public final class RemoteContentSmoke {
    /// Prevents construction of the standalone verification entry point.
    private RemoteContentSmoke() { }

    /// Exercises actual agreement controls, keyboard routing, persistence and home-card rendering.
    public static void main(String @Unmodifiable [] args) {
        try {
            run(args);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Runs in its own JVM so actual settings and JavaFX lifecycle cannot affect the regular test suite.
    private static void run(String @Unmodifiable [] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty("hmcl.home", output.resolve("user").toString());
        System.setProperty("hmcl.dir", output.resolve("local").toString());
        SettingsManager.init();
        SettingsManager.settings().animationDisabledProperty().set(true);
        Platform.startup(() -> Platform.setImplicitExit(false));
        var runtime = Launcher.class.getDeclaredMethod("initializeSettingsRuntime");
        runtime.setAccessible(true);
        onFx(() -> {
            runtime.invoke(null);
            return true;
        });
        Path document = output.resolve("documents/用户协议.html");
        Files.createDirectories(document.getParent());
        Files.writeString(document, "<html><meta charset='utf-8'>测试协议正文</html>");
        var continuation = new CountDownLatch(1);
        var store = new ContentStore(output, "smoke-" + System.nanoTime());
        var constructor = RemoteContentController.class.getDeclaredConstructor(Path.class, Runnable.class);
        constructor.setAccessible(true);
        var controller = onFx(() -> constructor.newInstance(output, (Runnable) continuation::countDown));
        var decorator = onFx(() -> new Decorator(Controllers.getRootPage(), stage -> { }));
        var decoratorField = Controllers.class.getDeclaredField("decorator");
        decoratorField.setAccessible(true);
        decoratorField.set(null, decorator);
        var instanceField = RemoteContentController.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, controller);
        var storeField = RemoteContentController.class.getDeclaredField("store");
        storeField.setAccessible(true);
        storeField.set(controller, store);
        var receive = RemoteContentController.class.getDeclaredMethod("receive", ContentSync.Result.class);
        receive.setAccessible(true);
        var scene = onFx(() -> {
            Scene result = new Scene(decorator.getRoot(), 1000, 620);
            StyleSheets.init(result);
            return result;
        });
        String agreementText = RemoteContentTest.AGREEMENT.replace("正文",
                "欢迎使用六五六大战场。<br/><br/>请阅读<a href='documents/用户协议.html'>《用户协议》</a>。<br/><br/>同意后继续，不同意则退出。");
        var agreement = RemoteContent.parse(RemoteContent.Kind.AGREEMENT, agreementText).get(0);
        onFx(() -> {
            receive.invoke(controller, result(RemoteContent.Kind.AGREEMENT, List.of(agreement), false, null));
            require(!RemoteContentController.canLaunch(), "New installation must remain gated");
            var root = (StackPane) decorator.getRoot();
            require(root.getChildren().get(0).isDisabled(), "Background controls must be disabled");
            var gate = root.lookup("#bf656-agreement-gate");
            gate.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            gate.fireEvent(new DialogCloseEvent());
            require(!RemoteContentController.canLaunch(), "Escape and ordinary dialog-close cannot grant consent");
            AtomicInteger keys = new AtomicInteger();
            gate.addEventHandler(KeyEvent.KEY_PRESSED, event -> keys.incrementAndGet());
            ButtonBase accept = findButton(gate, "同意并继续");
            accept.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "a", "a", KeyCode.A, false, false, false, false));
            require(keys.get() == 1, "Decorator must route keyboard input to the mandatory gate");
            snapshot(scene, output.resolve("agreement.png"));
            SettingsManager.settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
            SettingsManager.settings().themeBrightnessModeProperty().set("dark");
            snapshot(scene, output.resolve("agreement-dark.png"));
            SettingsManager.settings().themeBrightnessModeProperty().set("light");
            accept.fire();
            return true;
        });
        require(continuation.await(10, TimeUnit.SECONDS), "Consent write must release startup continuation");
        require(store.accepted(agreement), "Acceptance must survive a new state read");
        var announcements = RemoteContent.parse(RemoteContent.Kind.ANNOUNCEMENTS, """
                {"schemaVersion":1,"announcements":[
                  {"id":"news","revision":1,"title":"六五六大战场服务器公告","content":"欢迎来到六五六大战场！<br/><br/>活动安排、维护通知和服务器更新将显示在这里。<br/><a href='documents/用户协议.html'>查看本地用户协议</a>","pinned":true},
                  {"id":"older","revision":1,"title":"往期活动","content":"这是一条较早的公告。"}
                ]}
                """);
        onFx(() -> {
            require(RemoteContentController.canLaunch(), "Consent must unlock launcher actions");
            receive.invoke(controller, result(RemoteContent.Kind.ANNOUNCEMENTS, announcements, false, null));
            snapshot(scene, output.resolve("announcements.png"));
            findButton(decorator.getRoot(), "关闭").fire();
            snapshot(scene, output.resolve("announcement-dismissed.png"));
            var notices = RemoteContent.parse(RemoteContent.Kind.NOTICES, """
                    {"schemaVersion":1,"notices":[{"id":"smoke-notice","revision":1,
                    "title":"临时通知","content":"这是一条临时通知。确认后，同一修订版本不会重复显示。"}]}
                    """);
            receive.invoke(controller, result(RemoteContent.Kind.NOTICES, notices, false, null));
            snapshot(scene, output.resolve("notice.png"));
            findButton(decorator.getRoot(), "我已知晓").fire();
            var updated = RemoteContent.parse(RemoteContent.Kind.AGREEMENT, agreementText.replace("\"revision\":1", "\"revision\":2")).get(0);
            receive.invoke(controller, result(RemoteContent.Kind.AGREEMENT, List.of(updated), false, null));
            require(!RemoteContentController.canLaunch(), "Updated revision must require fresh consent");
            Files.delete(document);
            receive.invoke(controller, result(RemoteContent.Kind.AGREEMENT, List.of(updated), false, null));
            require(!hasButton(decorator.getRoot(), "同意并继续"), "Missing local agreement document must disable acceptance");
            snapshot(scene, output.resolve("missing-document.png"));
            Files.writeString(document, "restored");
            receive.invoke(controller, result(RemoteContent.Kind.AGREEMENT, List.of(agreement), true, "offline"));
            require(RemoteContentController.canLaunch(), "Previously accepted cache must work offline");
            receive.invoke(controller, result(RemoteContent.Kind.AGREEMENT, List.of(), false, "offline"));
            require(!RemoteContentController.canLaunch(), "An installation without an available agreement must stay gated");
            RemoteContentController.shutdown();
            return true;
        });
        Platform.exit();
        SettingsManager.shutdown();
        System.out.println("REMOTE_CONTENT_SMOKE_OK: consent, persistence, keyboard gate, revision, local documents, offline fallback and screenshots");
        System.exit(0);
    }

    /// Creates a synthetic completed transport event for UI tests; HTTP behavior is covered separately.
    private static ContentSync.Result result(RemoteContent.Kind kind, @Unmodifiable List<RemoteContent.Item> items,
                                             boolean cached, @Nullable String error) {
        return new ContentSync.Result(kind, items, cached, Instant.now(), error, false);
    }

    /// Executes a desktop action without blocking the JavaFX thread itself.
    private static <T> T onFx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    /// Requires a named actual button rather than invoking controller internals for acceptance.
    private static ButtonBase findButton(Node root, String label) {
        if (root instanceof ButtonBase button && label.equals(button.getText()))
            return button;
        if (root instanceof Parent parent) {
            for (Node node : parent.getChildrenUnmodifiable()) {
                if (hasButton(node, label))
                    return findButton(node, label);
            }
        }
        throw new AssertionError("Missing button: " + label);
    }

    /// Tests whether a semantic control is present before and after skin creation.
    private static boolean hasButton(Node root, String label) {
        if (root instanceof ButtonBase button && label.equals(button.getText()))
            return true;
        return root instanceof Parent parent && parent.getChildrenUnmodifiable().stream().anyMatch(node -> hasButton(node, label));
    }

    /// Renders a real themed scene to PNG for visual review without showing a desktop window.
    private static void snapshot(Scene scene, Path path) throws Exception {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        var image = scene.snapshot(null);
        var bitmap = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++)
                bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        ImageIO.write(bitmap, "png", path.toFile());
    }

    /// Fails the standalone process on a behavioral regression.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
