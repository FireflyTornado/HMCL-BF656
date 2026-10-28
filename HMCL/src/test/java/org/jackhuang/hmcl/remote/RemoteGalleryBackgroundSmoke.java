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
import org.jackhuang.hmcl.ui.construct.RadioChoiceList;
import org.jackhuang.hmcl.setting.StyleSheets;
import com.google.gson.JsonParser;
import javafx.scene.control.ButtonBase;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/// Isolated JavaFX verification of global backgrounds before selection, navigation, refresh, APIs and local fallback.
@NotNullByDefault
public final class RemoteGalleryBackgroundSmoke {
    /// Prevents construction.
    private RemoteGalleryBackgroundSmoke() { }

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
        SettingsManager.settings().animationDisabledProperty().set(true);
        Platform.startup(() -> Platform.setImplicitExit(false));
        var runtime = Launcher.class.getDeclaredMethod("initializeSettingsRuntime");
        runtime.setAccessible(true);
        onFx(() -> {
            SettingsManager.settings().commonDirectoryProperty().set(state.resolve("common").toString());
            SettingsManager.settings().commonDirectoryTypeProperty().set(org.jackhuang.hmcl.setting.EnumCommonDirectory.CUSTOM);
            runtime.invoke(null);
            return true;
        });
        byte[] remote = png(640, 360);
        Path local = state.resolve("local/background.png");
        Files.createDirectories(local.getParent());
        Files.write(local, png(320, 180));
        AtomicReference<String> catalog = new AtomicReference<>(manifest(remote).toString());
        AtomicInteger manifests = new AtomicInteger();
        AtomicInteger images = new AtomicInteger();
        AtomicInteger apis = new AtomicInteger();
        var paths = new java.util.concurrent.CopyOnWriteArrayList<String>();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch slowFinished = new CountDownLatch(1);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(exchange.getRequestURI().toString());
            byte[] bytes;
            if (path.endsWith("manifest.json")) {
                manifests.incrementAndGet();
                if ("slow=1".equals(exchange.getRequestURI().getQuery())) {
                    slowStarted.countDown();
                    try { releaseSlow.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    slowFinished.countDown();
                }
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
        String manifestAddress = base + "overall/manifest.json";
        Region surface = onFx(() -> {
            SettingsManager.settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND);
            SettingsManager.settings().customBackgroundImagePathProperty().set(local.toString());
            SettingsManager.settings().backgroundTypeProperty().set(BackgroundType.CUSTOM);
            Region region = new Region();
            region.setPrefSize(800, 480);
            Themes.backgroundProperty().addListener((observable, oldValue, newValue) -> region.setBackground(newValue.background()));
            region.setBackground(Themes.backgroundProperty().get().background());
            require(currentImage().getWidth() == 320, "Local background must render before global sync");
            return region;
        });
        PersonalizationPage page = onFx(PersonalizationPage::new);
        Scene scene = onFx(() -> {
            Scene value = new Scene(page, 1100, 900);
            StyleSheets.init(value);
            value.getRoot().applyCss();
            value.getRoot().layout();
            boolean opened = false;
            for (var header : page.lookupAll(".options-sublist-header")) {
                boolean background = header.lookupAll(".label").stream().anyMatch(node ->
                        node instanceof javafx.scene.control.Label label
                                && label.getText().equals(org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.background")));
                if (background) {
                    header.fireEvent(new javafx.event.ActionEvent(header, header));
                    opened = true;
                    break;
                }
            }
            require(opened, "The actual background section must be expanded before editing its choices");
            page.applyCss();
            page.layout();
            return value;
        });
        RadioChoiceList<BackgroundType> options = onFx(() -> options(page));
        RadioChoiceList.TextChoice<BackgroundType> gallery = onFx(() -> galleryOption(options));
        onFx(() -> {
            page.applyCss();
            page.layout();
            verifyChoiceLayout(page);
            return true;
        });
        onFx(() -> {
            SettingsManager.settings().networkBackgroundImageUrlProperty().set("https://example.com/single-image.png");
            options.selectedValueProperty().set(BackgroundType.REMOTE_GALLERY);
            gallery.setText(manifestAddress);
            return true;
        });
        await(() -> currentImage().getWidth() == 640);
        Image selected = onFx(RemoteGalleryBackgroundSmoke::currentImage);
        require(manifests.get() == 1 && images.get() == 1, "Initial selection must make one check and one download: " + paths);
        HMCLGameRepository repository = onFx(GameDirectoryManager::getSelectedRepository);
        GameInstanceID first = new GameInstanceID("Overall-A-" + System.nanoTime());
        GameInstanceID second = new GameInstanceID("Overall-B-" + System.nanoTime());
        instance(repository, first);
        instance(repository, second);
        repository.refresh();
        onFx(() -> {
            repository.setSelectedInstance(repository.getInstance(first));
            repository.setSelectedInstance(repository.getInstance(second));
            new PersonalizationPage();
            require(currentImage() == selected, "Game selection and appearance page construction must keep the selected image");
            require(manifests.get() == 1 && images.get() == 1, "Navigation must not initiate another global request: " + paths);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            for (var node : page.lookupAll(".scroll-pane")) {
                if (node instanceof javafx.scene.control.ScrollPane scroll)
                    scroll.setVvalue(0.20);
            }
            page.applyCss();
            page.layout();
            verifyChoiceLayout(page);
            var snapshot = page.snapshot(null, null);
            BufferedImage bitmap = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++)
                    bitmap.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            }
            ImageIO.write(bitmap, "png", output.resolve("remote-gallery-settings.png").toFile());
            return true;
        });
        onFx(() -> { ((ButtonBase) page.lookup("#remote-gallery-refresh")).fire(); return true; });
        await(() -> manifests.get() == 2 && RemoteGalleryBackgrounds.statusProperty().get().equals(org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.background.gallery.status.ready")));
        require(images.get() == 1, "Unchanged manifest bytes must not re-download the image even without an ETag");
        await(() -> currentImage() == RemoteGalleryBackgrounds.image(manifestAddress));
        Image refreshed = onFx(RemoteGalleryBackgroundSmoke::currentImage);
        onFx(() -> { options.selectedValueProperty().set(BackgroundType.CUSTOM); return true; });
        await(() -> currentImage().getWidth() == 320);
        onFx(() -> { options.selectedValueProperty().set(BackgroundType.REMOTE_GALLERY); return true; });
        await(() -> currentImage() == refreshed);
        require(manifests.get() == 2, "Returning to a gallery must reuse this process's selected image");
        LauncherSettings restored = java.util.Objects.requireNonNull(LauncherSettings.fromJson(
                JsonParser.parseString(onFx(() -> SettingsManager.settings().toJson())).getAsJsonObject()));
        require(restored.backgroundTypeProperty().get() == BackgroundType.REMOTE_GALLERY, "Gallery type must persist in launcher settings");
        require(manifestAddress.equals(restored.remoteGalleryManifestUrlProperty().get()), "Full manifest URL must persist");
        require("https://example.com/single-image.png".equals(restored.networkBackgroundImageUrlProperty().get()),
                "Switching remote modes must preserve the separate single-image address");
        catalog.set("{\"schemaVersion\":1,\"background\":{\"images\":[],\"urls\":[]}}");
        onFx(() -> { ((ButtonBase) page.lookup("#remote-gallery-refresh")).fire(); return true; });
        await(() -> currentImage().getWidth() == 320);
        onFx(() -> {
            page.applyCss();
            page.layout();
            verifyChoiceLayout(page);
            return true;
        });
        catalog.set("{\"schemaVersion\":1,\"background\":{\"urls\":[\"" + base + "api\"]}}");
        onFx(() -> { ((ButtonBase) page.lookup("#remote-gallery-refresh")).fire(); return true; });
        await(() -> currentImage().getWidth() == 640);
        int before = manifests.get();
        onFx(() -> { ((ButtonBase) page.lookup("#remote-gallery-refresh")).fire(); return true; });
        await(() -> manifests.get() == before + 1 && RemoteGalleryBackgrounds.statusProperty().get().equals(org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.background.gallery.status.ready")));
        require(apis.get() == 1, "Manual refresh must not repeat an image API request");
        onFx(() -> { options.selectedValueProperty().set(BackgroundType.CUSTOM); return true; });
        await(() -> currentImage().getWidth() == 320);
        onFx(() -> { gallery.setText(manifestAddress + "?slow=1"); return true; });
        require(slowStarted.await(10, TimeUnit.SECONDS), "The delayed gallery request must start");
        onFx(() -> { options.selectedValueProperty().set(BackgroundType.CUSTOM); return true; });
        await(() -> currentImage().getWidth() == 320);
        releaseSlow.countDown();
        require(slowFinished.await(10, TimeUnit.SECONDS), "The delayed request must be released");
        onFx(() -> { require(currentImage().getWidth() == 320, "A late gallery result must not override the newly selected local source"); return true; });
        require(!Files.exists(state.resolve("local/overall")), "The removed standalone background directory must never be created");
        require(Files.isDirectory(org.jackhuang.hmcl.util.CacheRepository.getInstance().getCacheDirectory().resolve("SHA-256")),
                "Gallery images must use the normal shared cache repository");
        onFx(() -> {
            // Normal launcher shutdown stops shared schedulers before closing background services.
            Schedulers.shutdown();
            RemoteGalleryBackgrounds.shutdown();
            return true;
        });
        http.stop(0);
        Platform.exit();
        SettingsManager.shutdown();
        System.out.println("REMOTE_GALLERY_SMOKE_OK: uniform background row heights and spacing, no gallery status row, real PNG decoding, UI selection, separate persistent addresses, mode switching, shared cache, instance-independent selection, appearance navigation, unchanged static reuse, withdrawal, API once per process, local fallback");
    }

    /// Verifies rendered rows keep consistent heights and gaps before sync, after sync and during local fallback.
    private static void verifyChoiceLayout(PersonalizationPage page) {
        require(page.lookup("#remote-gallery-status") == null, "Gallery status must not occupy a settings row");
        var rows = options(page).getChildren();
        require(rows.size() >= 3, "Background choices must be mounted for geometry checks");
        // Radio-button ripple effects extend beyond row bounds; measure allocated layout space instead.
        double height = ((Region) rows.get(0)).getHeight();
        double gap = rows.get(1).getLayoutY() - rows.get(0).getLayoutY() - height;
        require(height > 0 && gap >= 0, "Background choices must have visible, non-overlapping rows: height=" + height + ", gap=" + gap);
        for (int i = 1; i < rows.size(); i++) {
            var previous = (Region) rows.get(i - 1);
            var current = (Region) rows.get(i);
            require(Math.abs(current.getHeight() - height) < 0.1, "Every background choice must have the same rendered row height");
            require(Math.abs(current.getLayoutY() - previous.getLayoutY() - previous.getHeight() - gap) < 0.1,
                    "Every background choice must have the same vertical gap");
        }
    }

    /// Reads the actual radio list rendered by the appearance settings page.
    @SuppressWarnings("unchecked")
    private static RadioChoiceList<BackgroundType> options(PersonalizationPage page) {
        return (RadioChoiceList<BackgroundType>) java.util.Objects.requireNonNull(page.lookup("#launcher-background-options"));
    }

    /// Finds the remote-gallery editor using its background source type, without depending on translated labels.
    @SuppressWarnings("unchecked")
    private static RadioChoiceList.TextChoice<BackgroundType> galleryOption(RadioChoiceList<BackgroundType> options) {
        return (RadioChoiceList.TextChoice<BackgroundType>) options.getChoices().stream()
                .filter(choice -> choice.getValue() == BackgroundType.REMOTE_GALLERY).findFirst().orElseThrow();
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
