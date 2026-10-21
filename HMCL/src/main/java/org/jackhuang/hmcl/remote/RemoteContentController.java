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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.DragEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Coordinates mandatory agreement gating, queued notices, server cards and manual refresh on the FX thread.
@NotNullByDefault
public final class RemoteContentController {
    /// Current installation's content coordinator, created after the main scene is attached.
    private static @Nullable RemoteContentController instance;
    /// Base for configuration and relative documents, independent of the shell working directory.
    private final Path directory;
    /// Startup work held until the server agreement permits entry.
    private final Runnable continuation;
    /// Full-window gate, above ordinary dialogs and drag/drop targets.
    private final StackPane gate = new StackPane();
    /// Inline document errors remain visible even while ordinary dialogs are disabled behind the gate.
    private final Label linkError = new Label();
    /// Scene-level interception runs before the decorator's authentication drag/drop filters.
    private final EventHandler<DragEvent> gateDragHandler = event -> {
        if (!this.allowed)
            event.consume();
    };
    /// Last result for each independently synchronized endpoint.
    private final EnumMap<RemoteContent.Kind, ContentSync.Result> results = new EnumMap<>(RemoteContent.Kind.class);
    /// Notices queued during this process, avoiding duplicate dialogs across retries and refreshes.
    private final Set<String> queuedNotices = new HashSet<>();
    /// Whether one remote notice is waiting for or occupying the ordinary dialog queue.
    private boolean noticePending;
    /// Announcements hidden in this process, in addition to persisted dismissals.
    private final Set<String> dismissed = new HashSet<>();
    /// Validated source and state, unavailable when configuration fails.
    private @Nullable ContentStore store;
    /// Active finite synchronizer.
    private @Nullable ContentSync sync;
    /// Most recently fetched or cached agreement, if any.
    private @Nullable RemoteContent.Item agreement;
    /// Whether launcher actions are currently permitted.
    private boolean allowed;
    /// Whether startup continuation has already run.
    private boolean continued;
    /// Whether this controller has been shut down.
    private boolean closed;
    /// Whether the home card includes all active announcements.
    private boolean expanded;
    /// Next publication/expiry boundary; this updates local presentation without polling the server.
    private @Nullable PauseTransition boundaryTimer;

    /// Creates the coordinator and its non-dismissable interaction gate.
    private RemoteContentController(Path directory, Runnable continuation) {
        this.directory = directory;
        this.continuation = continuation;
        gate.setStyle("-fx-background-color: rgba(24, 28, 36, 0.94);");
        gate.setId("bf656-agreement-gate");
        gate.setPadding(new Insets(20));
        linkError.setWrapText(true);
        linkError.setStyle("-fx-text-fill: -monet-error;");
        gate.setFocusTraversable(true);
        gate.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE)
                event.consume();
        });
        gate.addEventFilter(DragEvent.ANY, DragEvent::consume);
    }

    /// Initializes optional server content and delays bundled installation until agreement acceptance.
    public static void initialize(Runnable continuation) {
        @Nullable Path artifact = JarUtils.thisJarPath();
        Path directory = artifact == null ? Metadata.CURRENT_DIRECTORY : artifact.getParent();
        var controller = new RemoteContentController(directory, continuation);
        instance = controller;
        controller.configure();
    }

    /// Whether central launch operations may proceed; disabled installations retain normal HMCL behavior.
    public static boolean canLaunch() {
        return instance == null || instance.allowed;
    }

    /// Opens the server information panel from the home sidebar.
    public static void showInformation() {
        if (instance != null)
            instance.information();
    }

    /// Stops retry workers and presentation timers before application shutdown.
    public static void shutdown() {
        if (instance != null) {
            instance.closed = true;
            if (instance.sync != null)
                instance.sync.close();
            if (instance.boundaryTimer != null)
                instance.boundaryTimer.stop();
            @Nullable Scene scene = Controllers.getDecorator().getRoot().getScene();
            if (scene != null)
                scene.removeEventFilter(DragEvent.ANY, instance.gateDragHandler);
        }
    }

    /// Loads explicit configuration; invalid enabled configurations cannot silently bypass the gate.
    private void configure() {
        try {
            var config = ContentSync.Config.load(directory);
            if (!config.enabled()) {
                unlock();
                return;
            }
            var contentStore = new ContentStore(directory, config.baseUrl().toString());
            store = contentStore;
            var contentSync = new ContentSync(config, contentStore, result -> Platform.runLater(() -> receive(result)));
            sync = contentSync;
            showGate("正在检查用户协议", "首次使用及协议更新后，需要同意服务器用户协议才能继续。", List.of(exitButton()));
            contentSync.start();
        } catch (IOException | RuntimeException e) {
            LOG.warning("Cannot configure BF656 remote content", e);
            showGate("远程内容配置错误", e.getMessage() == null ? e.toString() : e.getMessage(),
                    List.of(button("重新读取配置", this::configure), exitButton()));
        }
    }

    /// Applies successful data or fallback without converting a failed request into successful synchronization.
    private void receive(ContentSync.Result result) {
        if (closed)
            return;
        results.put(result.kind(), result);
        if (result.error() != null)
            LOG.warning("BF656 " + result.kind().filename + " sync failed: " + result.error());
        if (result.kind() == RemoteContent.Kind.AGREEMENT) {
            agreement = result.items().isEmpty() ? null : result.items().get(0);
            checkAgreement(result);
        }
        renderAnnouncements();
        enqueueNotices();
        scheduleBoundary();
    }

    /// Requires current consent; cached consent is usable only after a failed online check.
    private void checkAgreement(ContentSync.Result result) {
        @Nullable RemoteContent.Item current = agreement;
        @Nullable ContentStore contentStore = store;
        if (current == null || contentStore == null) {
            showGate("无法获取用户协议", "当前没有可用的协议，暂时无法继续。\n"
                            + retryDescription(result) + "\n" + result.error(),
                    List.of(button("立即重试", this::retryAgreement), exitButton()));
            return;
        }
        try {
            ContentLinks.validateAgreement(directory, current);
        } catch (IOException e) {
            showGate("协议文档不可用", e.getMessage() == null ? e.toString() : e.getMessage(),
                    List.of(button("检查文档并重试", this::retryAgreement), exitButton()));
            return;
        }
        if (contentStore.accepted(current)) {
            unlock();
            return;
        }
        String source = result.cached() ? "\n（网络暂不可用，当前显示上次缓存的协议。）" : "";
        var accept = button("同意并继续", () -> accept(current, contentStore));
        showGate(current.title() + " · 第 " + current.revision() + " 版",
                current.content() + source, List.of(accept, exitButton()));
    }

    /// Describes the finite retry policy without promising another retry after exhaustion.
    private static String retryDescription(ContentSync.Result result) {
        return result.exhausted() ? "自动重试已结束，请检查网络后手动重试。" : "将稍后自动重试，也可以立即重试。";
    }

    /// Rechecks local documents immediately and explicitly retries only the agreement endpoint.
    private void retryAgreement() {
        @Nullable ContentSync.Result previous = results.get(RemoteContent.Kind.AGREEMENT);
        if (previous != null)
            checkAgreement(previous);
        if (sync != null)
            sync.retryAgreement();
    }

    /// Saves consent off the FX thread, unlocking only after successful persistence.
    private void accept(RemoteContent.Item current, ContentStore contentStore) {
        gate.setDisable(true);
        CompletableFuture.runAsync(() -> {
            try {
                ContentLinks.validateAgreement(directory, current);
                contentStore.accept(current);
                Platform.runLater(() -> {
                    gate.setDisable(false);
                    if (!closed && agreement != null && agreement.key().equals(current.key())) {
                        unlock();
                        enqueueNotices();
                    }
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    gate.setDisable(false);
                    showGate("无法保存同意记录", "请检查客户端目录的写入权限和协议文件。\n" + e,
                            List.of(button("重试", () -> accept(current, contentStore)), exitButton()));
                });
            }
        });
    }

    /// Places an opaque interaction gate above normal dialogs; only explicit acceptance removes it.
    private void showGate(String title, String body, @Unmodifiable List<JFXButton> actions) {
        allowed = false;
        linkError.setVisible(false);
        linkError.setManaged(false);
        VBox card = new VBox(16, heading(title), scroll(richText(body), 260), linkError,
                new HBox(12, actions.toArray(Node[]::new)));
        card.getStyleClass().add("card");
        card.setPadding(new Insets(24));
        card.setMaxSize(640, Region.USE_PREF_SIZE);
        gate.getChildren().setAll(card);
        Controllers.getDecorator().setInteractionGate(gate);
        var root = Controllers.getDecorator().getRoot();
        if (root.getScene() != null) {
            root.getScene().removeEventFilter(DragEvent.ANY, gateDragHandler);
            root.getScene().addEventFilter(DragEvent.ANY, gateDragHandler);
        }
        gate.toFront();
        gate.requestFocus();
    }

    /// Releases startup once, while allowing a newly discovered protocol update to gate later launches again.
    private void unlock() {
        allowed = true;
        Controllers.getDecorator().setInteractionGate(null);
        if (!continued) {
            continued = true;
            Platform.runLater(continuation);
        }
    }

    /// Queues active unseen notices after consent, behind existing application dialogs.
    private void enqueueNotices() {
        @Nullable ContentStore contentStore = store;
        @Nullable ContentSync.Result result = results.get(RemoteContent.Kind.NOTICES);
        if (!allowed || contentStore == null || result == null || noticePending)
            return;
        for (var item : result.items()) {
            if (!item.active(Instant.now()) || queuedNotices.contains(item.key())
                    || (!item.repeat().equals("each_start") && contentStore.remembered("notices", item.key())))
                continue;
            queuedNotices.add(item.key());
            noticePending = true;
            Controllers.dialogLater(new NoticePane(item));
            break;
        }
    }

    /// A queued notification participates in normal dialog closure even if withdrawn before display.
    @NotNullByDefault
    private final class NoticePane extends JFXDialogLayout implements DialogAware {
        /// Published snapshot represented by this dialog.
        private final RemoteContent.Item item;
        /// Whether this user explicitly confirmed the notice.
        private boolean acknowledged;

        /// Creates the actual dialog while deferring publication checks until it is shown.
        private NoticePane(RemoteContent.Item item) {
            this.item = item;
            setHeading(heading(item.title()));
            setBody(scroll(richText(item.content()), 280));
            setActions(button("我已知晓", () -> {
                acknowledged = true;
                remember("notices", item.key());
                fireEvent(new DialogCloseEvent());
            }));
        }

        /// Discards expired or withdrawn queued messages using normal queue advancement.
        @Override
        public void onDialogShown() {
            if (!allowed || !item.active(Instant.now()) || !isCurrentNotice(item))
                Platform.runLater(() -> fireEvent(new DialogCloseEvent()));
        }

        /// Advances only one remote notice at a time and permits requeueing after agreement gating.
        @Override
        public void onDialogClosed() {
            if (!acknowledged)
                queuedNotices.remove(item.key());
            noticePending = false;
            Platform.runLater(RemoteContentController.this::enqueueNotices);
        }
    }

    /// Whether a queued notice still belongs to the latest complete server snapshot.
    private boolean isCurrentNotice(RemoteContent.Item item) {
        @Nullable ContentSync.Result result = results.get(RemoteContent.Kind.NOTICES);
        return result != null && result.items().stream().anyMatch(current -> current.key().equals(item.key()));
    }

    /// Builds dismissible server cards; empty successful responses also remove previous cards.
    private void renderAnnouncements() {
        @Nullable ContentSync.Result result = results.get(RemoteContent.Kind.ANNOUNCEMENTS);
        @Nullable ContentStore contentStore = store;
        if (result == null || contentStore == null)
            return;
        @Unmodifiable List<RemoteContent.Item> visible = result.items().stream().filter(item -> item.active(Instant.now()))
                .filter(item -> !dismissed.contains(item.key()) && !contentStore.remembered("announcements", item.key())).toList();
        VBox entries = new VBox(12);
        for (var item : expanded ? visible : visible.stream().limit(1).toList()) {
            Label title = heading((item.pinned() ? "[置顶] " : "") + item.title());
            HBox.setHgrow(title, Priority.ALWAYS);
            var hide = button("关闭", () -> {
                dismissed.add(item.key());
                remember("announcements", item.key());
                renderAnnouncements();
            });
            VBox entry = new VBox(8, new HBox(10, title, hide));
            if (!item.publishedAt().isEmpty())
                entry.getChildren().add(new Label(item.publishedAt()));
            entry.getChildren().add(richText(item.content()));
            entries.getChildren().add(entry);
        }
        if (visible.size() > 1)
            entries.getChildren().add(button(expanded ? "收起公告" : "展开其他 " + (visible.size() - 1) + " 条公告", () -> {
                expanded = !expanded;
                renderAnnouncements();
            }));
        if (result.cached() && !visible.isEmpty())
            entries.getChildren().add(new Label("离线缓存 · 上次同步 " + formatTime(result.fetchedAt())));
        VBox card = new VBox(10, scroll(entries, 220));
        card.getStyleClass().addAll("card", "announcement");
        card.setPadding(new Insets(16));
        card.setMaxSize(500, Region.USE_PREF_SIZE);
        Controllers.getRootPage().getMainPage().setRemoteAnnouncements(visible.isEmpty() ? null : card);
    }

    /// Refreshes local publication windows without issuing another successful HTTP request.
    private void scheduleBoundary() {
        if (boundaryTimer != null)
            boundaryTimer.stop();
        Instant now = Instant.now();
        @Nullable Instant next = null;
        for (var result : results.values()) {
            for (var item : result.items()) {
                @Nullable Instant start = item.startsAt();
                @Nullable Instant expiry = item.expiresAt();
                if (start != null && start.isAfter(now) && (next == null || start.isBefore(next)))
                    next = start;
                if (expiry != null && expiry.isAfter(now) && (next == null || expiry.isBefore(next)))
                    next = expiry;
            }
        }
        if (next != null) {
            var timer = new PauseTransition(Duration.millis(Math.max(50, java.time.Duration.between(now, next).toMillis() + 20)));
            timer.setOnFinished(event -> {
                renderAnnouncements();
                enqueueNotices();
                scheduleBoundary();
            });
            boundaryTimer = timer;
            timer.play();
        }
    }

    /// Opens status, manual refresh, agreement and all announcements including dismissed entries.
    private void information() {
        VBox body = new VBox(12);
        if (sync == null) {
            body.getChildren().add(new Label("远程内容尚未启用。配置文件：\n" + directory.resolve(".hmcl/remote-content.json")));
        } else {
            for (var kind : RemoteContent.Kind.values()) {
                @Nullable ContentSync.Result result = results.get(kind);
                body.getChildren().add(new Label(kind.filename + "：" + (result == null ? "正在同步"
                        : result.error() == null ? "同步成功 · " + formatTime(result.fetchedAt())
                        : "同步失败" + (result.cached() ? "（使用缓存）" : "") + " · " + retryDescription(result))));
            }
        }
        @Nullable RemoteContent.Item current = agreement;
        if (current != null)
            body.getChildren().add(button("查看用户协议", () -> showDocument(current)));
        @Nullable ContentSync.Result announcements = results.get(RemoteContent.Kind.ANNOUNCEMENTS);
        if (announcements != null) {
            for (var item : announcements.items()) {
                if (item.active(Instant.now()))
                    body.getChildren().add(button(item.title(), () -> showDocument(item)));
            }
        }
        JFXDialogLayout pane = contentPane("服务器信息", body);
        pane.setActions(button("刷新全部内容", () -> {
            if (sync != null)
                sync.refresh();
            pane.fireEvent(new DialogCloseEvent());
        }), button("关闭", () -> pane.fireEvent(new DialogCloseEvent())));
        Controllers.dialog(pane);
    }

    /// Displays a read-only copy of a published item.
    private void showDocument(RemoteContent.Item item) {
        JFXDialogLayout pane = contentPane(item.title() + " · 第 " + item.revision() + " 版", richText(item.content()));
        pane.setActions(button("关闭", () -> pane.fireEvent(new DialogCloseEvent())));
        Controllers.dialog(pane);
    }

    /// Persists acknowledgements without delaying FX rendering; failures remain visible to the user.
    private void remember(String category, String key) {
        @Nullable ContentStore contentStore = store;
        if (contentStore == null)
            return;
        CompletableFuture.runAsync(() -> {
            try {
                contentStore.remember(category, key);
            } catch (IOException | RuntimeException e) {
                LOG.warning("Cannot save BF656 content acknowledgement", e);
                Platform.runLater(() -> Controllers.dialog("无法保存通知记录，下次启动可能再次显示。\n" + e));
            }
        });
    }

    /// Renders restricted markup with a dedicated local-document-aware link handler.
    private TextFlow richText(String content) {
        TextFlow flow = new TextFlow();
        flow.setStyle("-fx-font-size: 14px;");
        flow.setLineSpacing(5);
        try {
            for (var run : ContentLinks.parse(content)) {
                Text text = new Text(run.text());
                text.setStyle("-fx-fill: -monet-on-surface;");
                if (run.bold())
                    text.getStyleClass().add("bold");
                if (run.href() != null) {
                    String href = run.href();
                    text.getStyleClass().add("hyperlink");
                    text.setStyle("-fx-fill: -monet-primary;");
                    text.setUnderline(true);
                    text.setCursor(Cursor.HAND);
                    text.setOnMouseClicked(event -> openLink(href));
                }
                flow.getChildren().add(text);
            }
        } catch (IOException e) {
            flow.getChildren().setAll(new Text(content));
        }
        return flow;
    }

    /// Opens a checked document URI without supporting arbitrary protocols or executable files.
    private void openLink(String href) {
        try {
            FXUtils.openLink(ContentLinks.resolve(directory, href).toASCIIString());
        } catch (IOException e) {
            if (!allowed) {
                linkError.setText("无法打开文档：" + e.getMessage());
                linkError.setVisible(true);
                linkError.setManaged(true);
            } else {
                Controllers.dialog(e.getMessage(), "无法打开文档");
            }
        }
    }

    /// Creates a wrapping heading shared by gates, cards and dialogs.
    private static Label heading(String title) {
        Label label = new Label(title);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        label.getStyleClass().add("title");
        return label;
    }

    /// Creates a bounded scroll region so long server text cannot extend beyond the window.
    private static ScrollPane scroll(Node content, double height) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(height);
        scroll.setPrefViewportWidth(540);
        scroll.setMinHeight(80);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /// Builds an ordinary queued dialog with bounded body content.
    private static JFXDialogLayout contentPane(String title, Node body) {
        JFXDialogLayout pane = new JFXDialogLayout();
        pane.setHeading(heading(title));
        pane.setBody(scroll(body, 280));
        return pane;
    }

    /// Creates a button whose action does not automatically close mandatory gates.
    private static JFXButton button(String title, Runnable action) {
        JFXButton button = new JFXButton(title);
        button.getStyleClass().add("dialog-accept");
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Creates the explicit rejection/exit action.
    private static JFXButton exitButton() {
        return button("不同意 / 退出启动器", Platform::exit);
    }

    /// Formats synchronization timestamps for the current desktop timezone.
    private static String formatTime(@Nullable Instant instant) {
        return instant == null ? "未知" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(instant);
    }
}
