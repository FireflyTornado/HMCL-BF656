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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.BackgroundType;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.main.PersonalizationPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/// Isolated JavaFX verification of global backgrounds before selection, navigation, refresh, APIs and local fallback.
@NotNullByDefault
public final class OverallBackgroundSmoke {
    /// Prevents construction.
    private OverallBackgroundSmoke() { }

    /// Exits deterministically even if a scene assertion fails after JavaFX startup.
    public static void main(String @Unmodifiable [] args) {
        try {
            run(Path.of(args[0]).toAbsolutePath());
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Uses real PNG decoding and actual game selection while counting all global requests.
    private static void run(Path output) throws Exception {
        Path state = output.resolve("run-" + System.nanoTime());
        Files.createDirectories(state);
        System.setProperty("hmcl.home", state.resolve("user").toString());
        System.setProperty("hmcl.dir", state.resolve("local").toString());
        SettingsManager.init();
        Platform.startup(() -> Platform.setImplicitExit(false));
        var runtime = Launcher.class.getDeclaredMethod("initializeSettingsRuntime");
        runtime.setAccessible(true);
        onFx(() -> { runtime.invoke(null); return true; });
        byte[] remote = png(640, 360);
        Path local = state.resolve("local.png");
        Files.write(local, png(320, 180));
        AtomicReference<String> catalog = new AtomicReference<>(manifest(remote).toString());
        AtomicInteger manifests = new AtomicInteger();
        AtomicInteger images = new AtomicInteger();
        AtomicInteger apis = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] bytes;
            if (path.endsWith("manifest.json")) {
                manifests.incrementAndGet();
                bytes = catalog.get().getBytes(StandardCharsets.UTF_8);
            } else {
                (path.equals("/api") ? apis : images).incrementAndGet();
                bytes = remote;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        http.start();
        String base = "http://127.0.0.1:" + http.getAddress().getPort() + "/";
        Path config = state.resolve("local/overall/client/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"enabled\":true,\"serverBaseUrl\":\"" + base + "\"}");
        Region surface = onFx(() -> {
            SettingsManager.settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND);
            SettingsManager.settings().customBackgroundImagePathProperty().set(local.toString());
            SettingsManager.settings().backgroundTypeProperty().set(BackgroundType.CUSTOM);
            Region region = new Region();
            region.setPrefSize(800, 480);
            Themes.backgroundProperty().addListener((observable, oldValue, newValue) -> region.setBackground(newValue.background()));
            region.setBackground(Themes.backgroundProperty().get().background());
            require(currentImage().getWidth() == 320, "Local background must render before global sync");
            OverallBackgroundController.initialize();
            return region;
        });
        await(() -> currentImage().getWidth() == 640);
        Image selected = onFx(OverallBackgroundSmoke::currentImage);
        HMCLGameRepository repository = onFx(GameDirectoryManager::getSelectedRepository);
        GameInstanceID first = new GameInstanceID("Overall-A-" + System.nanoTime());
        GameInstanceID second = new GameInstanceID("Overall-B-" + System.nanoTime());
        instance(repository, first);
        instance(repository, second);
        repository.refresh();
        onFx(() -> {
            repository.setSelectedInstance(repository.getInstance(first));
            repository.setSelectedInstance(repository.getInstance(second));
            OverallBackgroundController.initialize();
            new PersonalizationPage();
            require(currentImage() == selected, "Game selection and appearance page construction must keep the selected image");
            require(manifests.get() == 1 && images.get() == 1, "Navigation must not initiate another global request");
            Scene scene = new Scene(surface, 800, 480);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            var snapshot = surface.snapshot(null, null);
            BufferedImage bitmap = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++)
                    bitmap.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            }
            ImageIO.write(bitmap, "png", output.resolve("overall-remote.png").toFile());
            return true;
        });
        onFx(() -> { OverallBackgroundController.refresh(); return true; });
        await(() -> manifests.get() == 2 && OverallBackgroundController.statusProperty().get().equals("使用全局背景"));
        require(images.get() == 1, "Unchanged manifest bytes must not re-download the image even without an ETag");
        catalog.set("{\"schemaVersion\":1,\"background\":{\"images\":[],\"urls\":[]}}");
        onFx(() -> { OverallBackgroundController.refresh(); return true; });
        await(() -> currentImage().getWidth() == 320);
        catalog.set("{\"schemaVersion\":1,\"background\":{\"urls\":[\"" + base + "api\"]}}");
        onFx(() -> { OverallBackgroundController.refresh(); return true; });
        await(() -> currentImage().getWidth() == 640);
        int before = manifests.get();
        onFx(() -> { OverallBackgroundController.refresh(); return true; });
        await(() -> manifests.get() == before + 1 && OverallBackgroundController.statusProperty().get().equals("使用全局背景"));
        require(apis.get() == 1, "Manual refresh must not repeat an image API request");
        Files.writeString(config, "{\"enabled\":false,\"serverBaseUrl\":\"" + base + "\"}");
        onFx(() -> { OverallBackgroundController.refresh(); return true; });
        await(() -> currentImage().getWidth() == 320);
        Files.writeString(config, "{\"enabled\":true,\"serverBaseUrl\":\"" + base + "\"}");
        onFx(() -> { OverallBackgroundController.refresh(); return true; });
        await(() -> currentImage().getWidth() == 640);
        onFx(() -> {
            // Normal launcher shutdown stops shared schedulers before closing background services.
            Schedulers.shutdown();
            OverallBackgroundController.shutdown();
            return true;
        });
        http.stop(0);
        Platform.exit();
        SettingsManager.shutdown();
        System.out.println("OVERALL_BACKGROUND_SMOKE_OK: real PNG decoding, global startup, instance-independent selection, appearance navigation, unchanged static reuse, withdrawal, API once per process, local fallback");
    }

    /// Creates minimal game metadata without any instance remote-content configuration.
    private static void instance(HMCLGameRepository repository, GameInstanceID id) throws Exception {
        Files.createDirectories(repository.getLayout().getInstanceRoot(id));
        Files.writeString(repository.getLayout().getInstanceJson(id), "{\"id\":\"" + id
                + "\",\"mainClass\":\"net.minecraft.client.main.Main\",\"type\":\"release\",\"libraries\":[]}");
    }

    /// Builds a global manifest for a real PNG resource.
    private static JsonObject manifest(byte @Unmodifiable [] png) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonObject background = new JsonObject();
        JsonArray images = new JsonArray();
        images.add("backgrounds/global.png");
        background.add("images", images);
        root.add("background", background);
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("sha256", RemoteContent.digest(png));
        JsonObject files = new JsonObject();
        files.add("backgrounds/global.png", descriptor);
        root.add("files", files);
        return root;
    }

    /// Generates deterministic raster fixtures entirely offline.
    private static byte @Unmodifiable [] png(int width, int height) throws Exception {
        BufferedImage bitmap = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++)
                bitmap.setRGB(x, y, ((40 + x % 150) << 16) | ((50 + y % 130) << 8) | 170);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(bitmap, "png", bytes);
        return bytes.toByteArray();
    }

    /// Observes the actual rendered image on the JavaFX thread.
    private static Image currentImage() {
        return Themes.backgroundProperty().get().background().getImages().get(0).getImage();
    }

    /// Waits outside the JavaFX thread while asynchronous image work completes.
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (onFx(condition::getAsBoolean))
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("Overall desktop condition timed out");
    }

    /// Runs scene operations on JavaFX without showing an interactive window.
    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    /// Fails with the concrete violated behavior.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
