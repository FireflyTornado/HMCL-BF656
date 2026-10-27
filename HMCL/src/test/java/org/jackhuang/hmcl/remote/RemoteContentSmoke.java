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

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.event.ActionEvent;
import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.DialogUtils;
import org.jackhuang.hmcl.ui.construct.AdvancedListItem;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/// Isolated offscreen desktop verification using real instance selection and a real loopback content server.
@NotNullByDefault
public final class RemoteContentSmoke {
    /// Editable remote introduction displayed before the protocol body in the scene fixtures.
    private static final String INTRODUCTION = "游玩本服务器前，请仔细阅读下方的用户协议。";
    /// Prevents construction of this standalone verifier.
    private RemoteContentSmoke() { }

    /// Exits deterministically even if a JavaFX assertion fails after toolkit startup.
    public static void main(String @Unmodifiable [] args) {
        try {
            run(Path.of(args[0]).toAbsolutePath());
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Exercises selection notices, Markdown images, per-instance consent, cancellation and expiry cleanup.
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
        onFx(() -> { runtime.invoke(null); return true; });
        byte[] image = image();
        String markdown = "# Markdown 图文公告\n\n欢迎 **正式服玩家**，支持 *斜体* 与 ~~删除线~~。\n\n"
                + "![下载并缓存的示例图片](assets/banner.png)\n\n"
                + "## 活动安排\n\n| 时间 | 活动 |\n| --- | --- |\n| 20:00 | 开始活动 |\n\n"
                + "> 通知与公告在选中实例时加载。\n\n1. 更新客户端\n2. 进入服务器\n\n```text\n这是代码块\n```";
        JsonObject a = catalog("a", markdown, image);
        JsonObject b = catalog("b", markdown.replace("正式服玩家", "测试服玩家"), image);
        AtomicInteger aChecks = new AtomicInteger();
        AtomicInteger bChecks = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] bytes;
            if (path.endsWith("manifest.json")) {
                boolean first = path.contains("/a/");
                (first ? aChecks : bChecks).incrementAndGet();
                bytes = (first ? a : b).toString().getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("news.md")) {
                bytes = (path.contains("/a/") ? markdown : markdown.replace("正式服玩家", "测试服玩家")).getBytes(StandardCharsets.UTF_8);
            } else {
                bytes = image;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        http.start();
        String base = "http://127.0.0.1:" + http.getAddress().getPort() + "/";
        HMCLGameRepository repo = onFx(GameDirectoryManager::getSelectedRepository);
        String suffix = Long.toString(System.nanoTime());
        GameInstanceID aId = new GameInstanceID("Content-A-" + suffix);
        GameInstanceID bId = new GameInstanceID("Content-B-" + suffix);
        createInstance(repo, aId, base, "a");
        createInstance(repo, bId, base, "b");
        repo.refresh();
        HMCLGameInstance gameA = onFx(() -> repo.getInstance(aId));
        HMCLGameInstance gameB = onFx(() -> repo.getInstance(bId));
        var initial = RemoteContent.parse(a.toString(), "a");
        new ContentStore(RemoteContentController.root(gameA), base + "instances/a/")
                .remember("announcements", initial.key(initial.announcements().get(0)));
        Decorator decorator = onFx(() -> new Decorator(Controllers.getRootPage(), stage -> { }));
        var field = Controllers.class.getDeclaredField("decorator");
        field.setAccessible(true);
        field.set(null, decorator);
        Scene scene = onFx(() -> {
            Scene value = new Scene(decorator.getRoot(), 1000, 650);
            StyleSheets.init(value);
            repo.setSelectedInstance(gameA);
            RemoteContentController.initialize();
            value.getRoot().applyCss();
            value.getRoot().layout();
            return value;
        });
        await(() -> hasButton(decorator.getRoot(), "我已知晓"));
        onFx(() -> { snapshot(scene, output.resolve("instance-debug.png")); return true; });
        await(() -> imageReady(decorator.getRoot()));
        onFx(() -> {
            require(decorator.getRoot().lookup("#bf656-instance-agreement") == null, "Selecting must not present a protocol");
            snapshot(scene, output.resolve("instance-notice.png"));
            findButton(decorator.getRoot(), "我已知晓").fire();
            verifyAnnouncements(scene, output);
            return true;
        });
        AtomicInteger launchedA = new AtomicInteger();
        AtomicInteger launchedB = new AtomicInteger();
        double imageProtocolHeight = onFx(() -> {
            RemoteContentController.beforeLaunch(gameA, launchedA::incrementAndGet);
            require(launchedA.get() == 0, "First launch must await consent");
            require(findButton(decorator.getRoot(), "同意").isDisabled(), "Agreement must start unchecked");
            findButton(decorator.getRoot(), "同意").fire();
            require(launchedA.get() == 0, "Unchecked consent must not authorize launch");
            snapshot(scene, output.resolve("instance-agreement.png"));
            SettingsManager.settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
            SettingsManager.settings().themeBrightnessModeProperty().set("dark");
            snapshot(scene, output.resolve("instance-agreement-dark.png"));
            SettingsManager.settings().themeBrightnessModeProperty().set("light");
            verifyAgreementSizes(scene, output, a);
            return showImageProtocol(scene, gameA, base, a);
        });
        await(() -> imageReady(agreementPane(scene), "协议动态尺寸图片"));
        onFx(() -> {
            snapshot(scene, output.resolve("agreement-image-loaded.png"));
            require(agreementPane(scene).getHeight() > imageProtocolHeight + 20,
                    "Decoded protocol images must increase natural dialog height");
            verifyAgreementBounds(scene);
            updateAgreement(scene, a);
            findButton(decorator.getRoot(), "不同意").fire();
            require(launchedA.get() == 0, "Refusal must cancel without launching");
            RemoteContentController.beforeLaunch(gameA, launchedA::incrementAndGet);
            consent(decorator.getRoot()).setSelected(true);
            JsonObject updatedIntroduction = a.deepCopy();
            updatedIntroduction.getAsJsonObject("agreement").addProperty("introduction", "请阅读协议全文，再确认是否同意。");
            updateAgreement(scene, updatedIntroduction);
            require(consent(decorator.getRoot()).isSelected(), "Introduction-only edits must preserve the current acknowledgement");
            // Re-render the same revision while checked, as file-download progress does in production.
            refreshAgreement();
            require(consent(decorator.getRoot()).isSelected(), "Progress must preserve acknowledgement of the same protocol");
            findButton(decorator.getRoot(), "同意").fire();
            return true;
        });
        await(() -> launchedA.get() == 1);
        onFx(() -> {
            RemoteContentController.beforeLaunch(gameA, launchedA::incrementAndGet);
            require(launchedA.get() == 2, "Accepted protocol must continue immediately");
            snapshot(scene, output.resolve("instance-markdown.png"));
            a.getAsJsonObject("agreement").addProperty("revision", 2);
            RemoteContentController.showInformation(gameA);
            findButton(decorator.getRoot(), "刷新当前实例").fire();
            DialogUtils.close(java.util.Objects.requireNonNull(decorator.getRoot().lookup("#bf656-instance-information")));
            return true;
        });
        await(() -> currentRevision() == 2);
        onFx(() -> {
            require(decorator.getRoot().lookup("#bf656-instance-agreement") == null, "A protocol update must wait for the next game launch");
            RemoteContentController.beforeLaunch(gameA, launchedA::incrementAndGet);
            require(hasButton(decorator.getRoot(), "同意"), "Updated protocol must require fresh consent");
            require(!consent(decorator.getRoot()).isSelected(), "Updated protocol must reset acknowledgement");
            snapshot(scene, output.resolve("instance-updated-agreement.png"));
            for (var entry : b.getAsJsonArray("announcements"))
                entry.getAsJsonObject().addProperty("expiresAt", Instant.now().plusSeconds(4).toString());
            RemoteContentController.beforeLaunch(gameB, launchedB::incrementAndGet);
            return true;
        });
        await(() -> hasButton(decorator.getRoot(), "同意") && bChecks.get() == 1);
        onFx(() -> {
            require(launchedB.get() == 0, "A's consent must not authorize B");
            repo.setSelectedInstance(gameA);
            require(launchedB.get() == 0, "Switching must cancel B's pending launch");
            require(decorator.getRoot().lookup("#bf656-instance-agreement") == null, "Old confirmation must be removed");
            repo.setSelectedInstance(gameB);
            RemoteContentController.beforeLaunch(gameB, launchedB::incrementAndGet);
            require(!consent(decorator.getRoot()).isSelected(), "Another instance must start with unchecked acknowledgement");
            consent(decorator.getRoot()).setSelected(true);
            findButton(decorator.getRoot(), "同意").fire();
            return true;
        });
        await(() -> launchedB.get() == 1);
        onFx(() -> {
            repo.setSelectedInstance(gameA);
            repo.setSelectedInstance(gameB);
            require(aChecks.get() == 2 && bChecks.get() == 1, "Successful selections must not recheck manifests after the explicit refresh");
            snapshot(scene, output.resolve("instance-B.png"));
            return true;
        });
        var bManifest = RemoteContent.parse(b.toString(), "b");
        var bStore = new ContentStore(RemoteContentController.root(gameB), base + "instances/b/");
        await(() -> !Files.exists(bStore.file(bManifest.files().get(ContentFixtures.BODY)))
                && java.util.Objects.requireNonNull(bStore.cache("b")).body().contains("\"announcements\":[]"));
        onFx(() -> {
            require(aChecks.get() == 2 && bChecks.get() == 1, "Local expiry must not make HTTP requests");
            snapshot(scene, output.resolve("instance-expired.png"));
            if (hasButton(scene.getRoot(), "我已知晓"))
                findButton(scene.getRoot(), "我已知晓").fire();
            return true;
        });
        verifyManagementContext(scene, output, repo, gameA, gameB, b, aChecks, bChecks, base);
        onFx(() -> { RemoteContentController.shutdown(); return true; });
        http.stop(0);
        Platform.exit();
        SettingsManager.shutdown();
        System.out.println("INSTANCE_CONTENT_SMOKE_OK: selection notices, Markdown tables/images, cancel-only rejection, durable per-instance consent, updated protocol timing, stale-launch cancellation, local expiry cleanup, light/dark themes, no duplicate manifest requests");
    }

    /// Creates a public fixture catalog with one notice and one local-document protocol.
    private static JsonObject catalog(String key, String markdown, byte[] image) {
        JsonObject root = ContentFixtures.markdown(key, markdown, image);
        JsonObject second = root.getAsJsonArray("announcements").get(0).getAsJsonObject().deepCopy();
        second.addProperty("id", "second-news");
        second.addProperty("title", "第二条独立公告");
        root.getAsJsonArray("announcements").add(second);
        root.getAsJsonObject("agreement").addProperty("content", "请阅读<a href='client/documents/用户协议.html'>本实例协议</a>。不同意只取消本次启动。");
        root.getAsJsonObject("agreement").addProperty("introduction", INTRODUCTION);
        JsonObject notice = new JsonObject();
        notice.addProperty("id", "notice-" + key);
        notice.addProperty("revision", 1);
        notice.addProperty("title", "实例 " + key + " 的通知");
        notice.addProperty("content", "选中实例即可查看通知，无需先同意游戏协议。");
        root.getAsJsonArray("notices").add(notice);
        return root;
    }

    /// Creates valid repository metadata and the explicit instance-key mapping beneath its own version root.
    private static void createInstance(HMCLGameRepository repo, GameInstanceID id, String base, String key) throws Exception {
        Path folder = repo.getLayout().getInstanceRoot(id);
        Files.createDirectories(folder.resolve(".launcher/client/documents"));
        Files.writeString(repo.getLayout().getInstanceJson(id), "{\"id\":\"" + id + "\",\"mainClass\":\"net.minecraft.client.main.Main\",\"type\":\"release\",\"libraries\":[]}");
        Files.writeString(folder.resolve(".launcher/client/config.json"), "{\"enabled\":true,\"serverBaseUrl\":\"" + base + "\",\"instanceKey\":\"" + key + "\"}");
        Files.writeString(folder.resolve(".launcher/client/documents/用户协议.html"), "<html><meta charset='utf-8'>Instance " + key + " terms</html>");
    }

    /// Creates an image decoding fixture rather than relying on network-hosted media.
    private static byte[] image() throws Exception {
        BufferedImage bitmap = new BufferedImage(420, 110, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++)
                bitmap.setRGB(x, y, ((45 + x / 4) << 16) | ((80 + y / 2) << 8) | 180);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(bitmap, "png", output);
        return output.toByteArray();
    }

    /// Waits outside the FX thread while asynchronous synchronization and image decoding complete.
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (onFx(() -> {
                // No stage is shown, so explicitly request pulses for time-based production transitions.
                Platform.requestNextPulse();
                return condition.getAsBoolean();
            }))
                return;
            Thread.sleep(25);
        }
        throw new AssertionError("Desktop condition timed out");
    }

    /// Observes completed model state without adding production-only test hooks.
    private static int currentRevision() {
        try {
            var manager = RemoteContentController.class.getDeclaredField("instance");
            manager.setAccessible(true);
            var current = RemoteContentController.class.getDeclaredField("current");
            current.setAccessible(true);
            Object session = current.get(manager.get(null));
            var result = session.getClass().getDeclaredField("result");
            result.setAccessible(true);
            @Nullable ContentSync.Result snapshot = (ContentSync.Result) result.get(session);
            return snapshot == null || snapshot.manifest() == null ? 0 : snapshot.manifest().agreement().revision();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /// Replays a normal sync callback without a network request or a new launch request.
    private static void refreshAgreement() throws Exception {
        var field = RemoteContentController.class.getDeclaredField("instance");
        field.setAccessible(true);
        Object controller = field.get(null);
        var current = RemoteContentController.class.getDeclaredField("current");
        current.setAccessible(true);
        Object session = current.get(controller);
        var result = session.getClass().getDeclaredField("result");
        result.setAccessible(true);
        var receive = RemoteContentController.class.getDeclaredMethod("receive", session.getClass(), ContentSync.Result.class);
        receive.setAccessible(true);
        receive.invoke(controller, session, result.get(session));
    }

    /// Finds the actual acknowledgement control and checks the requested wording.
    private static CheckBox consent(Node root) {
        @Nullable CheckBox box = (CheckBox) root.lookup("#bf656-agreement-consent");
        require(box != null && "我已阅读并同意遵守上述协议与规定".equals(box.getText()), "Missing acknowledgement checkbox");
        return java.util.Objects.requireNonNull(box);
    }

    /// Tests short, medium and long text, compact failure states, proportional resizing and the absolute ceiling.
    private static void verifyAgreementSizes(Scene scene, Path output, JsonObject catalog) throws Exception {
        Region pane = agreementPane(scene);
        snapshot(scene, output.resolve("agreement-short.png"));
        double shortHeight = pane.getHeight();
        require(hasLabel(pane, INTRODUCTION), "Remote introduction must appear before the protocol");
        ScrollPane viewport = (ScrollPane) java.util.Objects.requireNonNull(pane.lookup("#bf656-agreement-body"));
        VBox document = (VBox) ((VBox) viewport.getContent()).getChildren().get(0);
        require("bf656-agreement-introduction".equals(document.getChildren().get(0).getId()),
                "Introduction must precede the Markdown document");
        require(shortHeight < 300, "Short protocol must not retain the old fixed-height blank space");
        require(!hasLabel(pane, "不同意将取消本次游戏启动。"), "Removed footer text must not remain");
        verifyAgreementBounds(scene);
        JsonObject noIntroduction = catalog.deepCopy();
        noIntroduction.getAsJsonObject("agreement").remove("introduction");
        updateAgreement(scene, noIntroduction);
        snapshot(scene, output.resolve("agreement-introduction-empty.png"));
        require(pane.lookup("#bf656-agreement-introduction") == null && pane.getHeight() < shortHeight,
                "Missing introduction must not leave an empty row or extra spacing");
        JsonObject longIntroduction = catalog.deepCopy();
        longIntroduction.getAsJsonObject("agreement").addProperty("introduction", "请仔细阅读下方协议。\n".repeat(80));
        updateAgreement(scene, longIntroduction);
        snapshot(scene, output.resolve("agreement-introduction-long.png"));
        verifyAgreementBounds(scene);
        JsonObject medium = catalog.deepCopy();
        medium.getAsJsonObject("agreement").addProperty("content", "协议说明\n" + "请遵守服务器规则并尊重其他玩家。\n".repeat(10));
        updateAgreement(scene, medium);
        snapshot(scene, output.resolve("agreement-medium.png"));
        require(pane.getHeight() > shortHeight + 60, "Medium protocol must grow with the text");
        verifyAgreementBounds(scene);
        JsonObject longText = catalog.deepCopy();
        longText.getAsJsonObject("agreement").addProperty("content", "完整服务器协议\n" + "请遵守服务器规则并尊重其他玩家。\n".repeat(100));
        updateAgreement(scene, longText);
        snapshot(scene, output.resolve("agreement-long.png"));
        verifyAgreementBounds(scene);
        ScrollPane body = (ScrollPane) java.util.Objects.requireNonNull(pane.lookup("#bf656-agreement-body"));
        require(body.getContent().getLayoutBounds().getHeight() > body.getViewportBounds().getHeight() + 100,
                "Long protocols must scroll only within their body");
        double beforeResize = pane.getHeight();
        scene.getRoot().resize(820, 490);
        scene.getRoot().layout();
        verifyAgreementBounds(scene);
        require(pane.getHeight() < beforeResize - 80, "Dialog maximum height must follow a smaller window");
        scene.getRoot().resize(1300, 1100);
        scene.getRoot().layout();
        verifyAgreementBounds(scene);
        require(Math.abs(pane.getHeight() - 720) < 2, "Large windows must retain the 720-unit ceiling");
        scene.getRoot().resize(1000, 650);
        scene.getRoot().layout();
        var update = pane.getClass().getDeclaredMethod("update", RemoteContent.Manifest.class, String.class);
        update.setAccessible(true);
        update.invoke(pane, null, "暂时无法获取协议，请稍后重试。");
        snapshot(scene, output.resolve("agreement-unavailable.png"));
        require(pane.getHeight() < 300, "Loading and error states must also use compact natural height");
        updateAgreement(scene, catalog);
    }

    /// Uses an actual cached Markdown body to verify asynchronous image-dependent layout.
    private static double showImageProtocol(Scene scene, HMCLGameInstance game, String base, JsonObject catalog) throws Exception {
        JsonObject illustrated = catalog.deepCopy();
        String markdown = "# 图文协议\n\n![协议动态尺寸图片](" + ContentFixtures.IMAGE + ")";
        ContentFixtures.file(illustrated, "protocol-layout.md", markdown.getBytes(StandardCharsets.UTF_8));
        JsonObject protocol = illustrated.getAsJsonObject("agreement");
        protocol.remove("content");
        protocol.addProperty("contentFile", "protocol-layout.md");
        protocol.add("resources", catalog.getAsJsonArray("announcements").get(0).getAsJsonObject().getAsJsonArray("resources").deepCopy());
        var manifest = RemoteContent.parse(illustrated.toString(), catalog.get("instanceKey").getAsString());
        var store = new ContentStore(RemoteContentController.root(game), base + "instances/a/");
        store.install(manifest.files().get("protocol-layout.md"), markdown.getBytes(StandardCharsets.UTF_8));
        updateAgreement(scene, illustrated);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return agreementPane(scene).getHeight();
    }

    /// Presents another protocol payload through the normal dialog update path without changing launch ownership.
    private static void updateAgreement(Scene scene, JsonObject catalog) throws Exception {
        Region pane = agreementPane(scene);
        var update = pane.getClass().getDeclaredMethod("update", RemoteContent.Manifest.class, String.class);
        update.setAccessible(true);
        update.invoke(pane, RemoteContent.parse(catalog.toString(), catalog.get("instanceKey").getAsString()), null);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    /// Returns the currently displayed protocol dialog from the real skinned scene.
    private static Region agreementPane(Scene scene) {
        return (Region) java.util.Objects.requireNonNull(scene.getRoot().lookup("#bf656-instance-agreement"));
    }

    /// Requires the entire dialog and all fixed controls to remain inside the proportional height limit.
    private static void verifyAgreementBounds(Scene scene) {
        Region pane = agreementPane(scene);
        double limit = Math.min(720, Controllers.getDecorator().getDialogContainer().getHeight() * 0.8);
        require(pane.getHeight() <= limit + 1, "Whole dialog must obey the window-dependent height limit");
        for (Node control : java.util.List.of(consent(pane), findButton(pane, "同意"), findButton(pane, "不同意"))) {
            var bounds = pane.sceneToLocal(control.localToScene(control.getLayoutBounds()));
            require(bounds.getMinY() >= 0 && bounds.getMaxY() <= pane.getHeight() + 1,
                    "Acknowledgement and actions must stay visible outside the scrolling body");
        }
    }

    /// Finds unwanted footer labels without matching words inside a Markdown text flow.
    private static boolean hasLabel(Node root, String text) {
        return root instanceof javafx.scene.control.Label label && text.equals(label.getText())
                || root instanceof Parent parent && parent.getChildrenUnmodifiable().stream().anyMatch(child -> hasLabel(child, text));
    }

    /// Exercises the actual management entry, disabled/broken configurations, and refreshes while another instance stays selected.
    private static void verifyManagementContext(Scene scene, Path output, HMCLGameRepository repo,
                                               HMCLGameInstance gameA, HMCLGameInstance gameB, JsonObject catalog,
                                               AtomicInteger aChecks, AtomicInteger bChecks, String base) throws Exception {
        Path config = RemoteContentController.root(gameB).resolve("client/config.json");
        String originalConfig = Files.readString(config);
        var accepted = RemoteContent.parse(catalog.toString(), "b");
        var store = new ContentStore(RemoteContentController.root(gameB), base + "instances/b/");
        Node homepage = onFx(() -> {
            repo.setSelectedInstance(gameA);
            require(!hasNavigationItem(Controllers.getRootPage(), "服务器信息"), "Global sidebar must not contain instance information");
            var page = Controllers.getGameInstancePage();
            page.setInstance(gameB.getId(), repo);
            Controllers.navigate(page);
            snapshot(scene, output.resolve("server-information-entry.png"));
            require(informationEntry(scene).isVisible(), "Configured instances must expose their management entry");
            Files.delete(config);
            page.setInstance(gameB.getId(), repo);
            require(!informationEntry(scene).isVisible() && !informationEntry(scene).isManaged(), "Ordinary instances must hide the entry without taking space");
            var disabled = com.google.gson.JsonParser.parseString(originalConfig).getAsJsonObject();
            disabled.addProperty("enabled", false);
            Files.writeString(config, disabled.toString());
            page.setInstance(gameB.getId(), repo);
            require(!informationEntry(scene).isVisible(), "Disabled remote content must hide the entry");
            Files.writeString(config, "{broken-json");
            page.setInstance(gameB.getId(), repo);
            require(informationEntry(scene).isVisible(), "Invalid configurations must keep the repair entry");
            informationEntry(scene).fireEvent(new ActionEvent());
            snapshot(scene, output.resolve("server-information-invalid.png"));
            require(!hasButton(informationPane(scene), "查看用户协议"), "Broken B must never expose A's cached protocol");
            return java.util.Objects.requireNonNull(Controllers.getRootPage().getMainPage().lookup("#bf656-announcement-feed"));
        });
        require(aChecks.get() == 2 && bChecks.get() == 1, "Reading configuration errors must not refresh another instance");
        onFx(() -> {
            Files.writeString(config, originalConfig);
            catalog.getAsJsonObject("agreement").addProperty("title", "实例 B 的独立协议");
            catalog.getAsJsonObject("agreement").addProperty("introduction", "这是 B 的协议说明。");
            catalog.getAsJsonArray("notices").get(0).getAsJsonObject().addProperty("revision", 2);
            for (var item : catalog.getAsJsonArray("announcements"))
                item.getAsJsonObject().remove("expiresAt");
            findButton(informationPane(scene), "刷新当前实例").fire();
            return true;
        });
        await(() -> bChecks.get() == 2 && hasLabel(informationPane(scene), "同步成功"));
        onFx(() -> {
            require(aChecks.get() == 2 && GameDirectoryManager.getSelectedInstance().getId().equals(gameA.getId()), "Repairing B must leave A selected and untouched");
            require(Controllers.getRootPage().getMainPage().lookup("#bf656-announcement-feed") == homepage, "B results must not replace A's homepage cards");
            require(!hasButton(scene.getRoot(), "我已知晓"), "Management downloads must not show notices");
            require(!hasButton(informationPane(scene), "服务器公告") && !hasButton(informationPane(scene), "第二条独立公告"), "Announcement links must be removed");
            snapshot(scene, output.resolve("server-information-B.png"));
            findButton(informationPane(scene), "查看用户协议").fire();
            snapshot(scene, output.resolve("server-information-B-protocol.png"));
            Node document = java.util.Objects.requireNonNull(scene.getRoot().lookup("#bf656-document"));
            require(hasLabel(document, "实例 B 的独立协议") && hasLabel(document, "这是 B 的协议说明。"), "Read-only protocol must belong to B while A stays selected");
            require(!hasButton(document, "同意"), "Read-only viewing must not grant consent");
            DialogUtils.close(document);
            findButton(informationPane(scene), "刷新当前实例").fire();
            return true;
        });
        await(() -> bChecks.get() == 3 && hasLabel(informationPane(scene), "同步成功"));
        require(store.accepted(accepted) && !store.accepted(RemoteContent.parse(catalog.toString(), "b")), "Viewing must preserve old receipts without accepting a changed protocol");
        onFx(() -> {
            require(aChecks.get() == 2 && !hasButton(scene.getRoot(), "我已知晓"), "Explicit refresh must stay quiet and target B");
            DialogUtils.close(informationPane(scene));
            repo.setSelectedInstance(gameB);
            return true;
        });
        await(() -> hasButton(scene.getRoot(), "我已知晓"));
        onFx(() -> {
            require(bChecks.get() == 3, "Selecting successfully inspected B must reuse synchronized data");
            findButton(scene.getRoot(), "我已知晓").fire();
            catalog.getAsJsonArray("notices").get(0).getAsJsonObject().addProperty("revision", 3);
            RemoteContentController.showInformation(gameB);
            findButton(informationPane(scene), "刷新当前实例").fire();
            return true;
        });
        await(() -> bChecks.get() == 4 && hasLabel(informationPane(scene), "同步成功"));
        onFx(() -> {
            require(!hasButton(scene.getRoot(), "我已知晓"), "Management refresh must also suppress notices for the selected target");
            DialogUtils.close(informationPane(scene));
            repo.setSelectedInstance(gameA);
            repo.setSelectedInstance(gameB);
            return true;
        });
        await(() -> hasButton(scene.getRoot(), "我已知晓"));
        onFx(() -> {
            findButton(scene.getRoot(), "我已知晓").fire();
            require(aChecks.get() == 2 && bChecks.get() == 4, "Management must not introduce automatic duplicate requests");
            return true;
        });
    }

    /// Finds the sole information entry inside the instance management page.
    private static AdvancedListItem informationEntry(Scene scene) {
        return (AdvancedListItem) java.util.Objects.requireNonNull(scene.getRoot().lookup("#bf656-instance-information-entry"));
    }

    /// Applies skins to asynchronously replaced status content in the offscreen scene before querying it.
    private static Region informationPane(Scene scene) {
        Region pane = (Region) java.util.Objects.requireNonNull(scene.getRoot().lookup("#bf656-instance-information"));
        pane.applyCss();
        pane.layout();
        return pane;
    }

    /// Checks actual sidebar controls rather than plain text appearing inside dialogs.
    private static boolean hasNavigationItem(Node root, String title) {
        return root instanceof AdvancedListItem item && title.equals(item.getTitle())
                || root instanceof Parent parent && parent.getChildrenUnmodifiable().stream().anyMatch(child -> hasNavigationItem(child, title));
    }

    /// Checks full-width cards, independent collapse, legacy receipts and resize behavior in a real scene.
    private static void verifyAnnouncements(Scene scene, Path output) throws Exception {
        snapshot(scene, output.resolve("announcement-expanded.png"));
        ScrollPane feed = (ScrollPane) java.util.Objects.requireNonNull(scene.getRoot().lookup("#bf656-announcement-feed"));
        VBox entries = (VBox) feed.getContent();
        require(entries.getChildren().size() == 2, "All active announcements must be shown");
        VBox first = (VBox) entries.getChildren().get(0);
        VBox second = (VBox) entries.getChildren().get(1);
        Region firstBody = (Region) first.getChildren().get(1);
        Region secondBody = (Region) second.getChildren().get(1);
        require(firstBody.isManaged() && secondBody.isManaged(), "Every card must start expanded");
        require(first.lookupAll(".scroll-pane").isEmpty(), "Cards must not contain nested scrollbars");
        require(first.getWidth() >= feed.getViewportBounds().getWidth() - 20, "Cards must fill the viewport width");
        require(!hasButton(first, "关闭") && !hasButton(first, "阅读全文"), "Cards must have no dismissal or full-text actions");
        double height = first.getHeight();
        ButtonBase toggle = (ButtonBase) java.util.Objects.requireNonNull(first.lookup(".toggle-icon"));
        toggle.fire();
        snapshot(scene, output.resolve("announcement-collapsed.png"));
        require(!firstBody.isManaged() && secondBody.isManaged(), "Collapse must affect only its own card");
        require(first.getHeight() < height, "Collapsed cards must release their body height");
        toggle.fire();
        double width = first.getWidth();
        scene.getRoot().resize(1300, 850);
        scene.getRoot().layout();
        require(first.getWidth() > width + 200, "Card width must grow with the window");
        scene.getRoot().resize(1000, 650);
        scene.getRoot().layout();
    }

    /// Runs one action on the FX thread with a bounded wait.
    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    /// Locates a semantic button in the actual skinned scene graph.
    private static ButtonBase findButton(Node root, String label) {
        if (root instanceof ButtonBase button && label.equals(button.getText()))
            return button;
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (hasButton(child, label))
                    return findButton(child, label);
            }
        }
        throw new AssertionError("Missing button: " + label);
    }

    /// Checks button presence without relying on coordinates or visible desktop automation.
    private static boolean hasButton(Node root, String label) {
        return root instanceof ButtonBase button && label.equals(button.getText())
                || root instanceof Parent parent && parent.getChildrenUnmodifiable().stream().anyMatch(child -> hasButton(child, label));
    }

    /// Requires the remote image fixture to have actually decoded into an image view.
    private static boolean imageReady(Node root) {
        return imageReady(root, "下载并缓存的示例图片");
    }

    /// Identifies a completed image in one document without confusing it with images in background cards.
    private static boolean imageReady(Node root, String description) {
        return root instanceof ImageView image && image.getImage() != null && description.equals(image.getAccessibleText())
                || root instanceof Parent parent && parent.getChildrenUnmodifiable().stream().anyMatch(child -> imageReady(child, description));
    }

    /// Writes a real themed scene snapshot without showing a desktop window.
    private static void snapshot(Scene scene, Path path) throws Exception {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        var image = scene.snapshot(null);
        BufferedImage bitmap = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++)
                bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        ImageIO.write(bitmap, "png", path.toFile());
    }

    /// Fails the process when a desktop behavior regresses.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
