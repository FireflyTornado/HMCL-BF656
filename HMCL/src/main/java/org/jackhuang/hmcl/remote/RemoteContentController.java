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
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.DialogUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Projects per-instance content onto the homepage and confirms agreements only for captured launch requests.
@NotNullByDefault
public final class RemoteContentController {
    /// Desktop coordinator, installed after the main scene is ready.
    private static @Nullable RemoteContentController instance;
    /// Sessions are identified by fixed version roots rather than configurable game run directories.
    private final Map<Path, Session> sessions = new HashMap<>();
    /// Explicit instance-management views keep their own context independently of homepage selection.
    private final Set<InformationPane> informationPanes = new HashSet<>();
    /// Session currently projected onto the homepage.
    private @Nullable Session current;
    /// Selection changes invalidate pending launches.
    private long epoch;
    /// One captured launch awaiting network data or user consent.
    private @Nullable LaunchRequest pending;
    /// Selection listener follows repository snapshot replacements.
    private final ChangeListener<@Nullable HMCLGameInstance> listener = (observable, oldValue, newValue) -> select(newValue);
    /// Publication/expiry boundaries cause local rendering and cleanup, never periodic HTTP polling.
    private @Nullable PauseTransition timer;
    /// Late worker callbacks are ignored after shutdown.
    private boolean closed;

    /// Binds existing HMCL instance selection without requiring global protocol acceptance.
    private RemoteContentController() {
        GameDirectoryManager.selectedInstanceProperty().addListener(listener);
        select(GameDirectoryManager.getSelectedInstance());
    }

    /// Initializes per-instance content without delaying ordinary launcher startup.
    public static void initialize() {
        if (instance == null)
            instance = new RemoteContentController();
    }

    /// Captures a game or script launch target before presenting any protocol.
    public static void beforeLaunch(HMCLGameInstance game, Runnable continuation) {
        initialize();
        Objects.requireNonNull(instance).launch(game, continuation);
    }

    /// Only enabled or invalid instance configurations expose the management entry; ordinary instances stay uncluttered.
    public static boolean hasInformation(@Nullable HMCLGameInstance game) {
        if (game == null)
            return false;
        try {
            return ContentSync.Config.load(root(game)).enabled();
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /// Opens status and documents for an explicitly captured management target without selecting it.
    public static void showInformation(HMCLGameInstance game) {
        initialize();
        Objects.requireNonNull(instance).information(game);
    }

    /// Cancels workers, dialogs and pending continuations without deleting persistent receipts.
    public static void shutdown() {
        if (instance == null)
            return;
        instance.closed = true;
        instance.cancelLaunch();
        for (InformationPane pane : Set.copyOf(instance.informationPanes))
            DialogUtils.close(pane);
        GameDirectoryManager.selectedInstanceProperty().removeListener(instance.listener);
        for (Session session : instance.sessions.values()) {
            for (DocumentPane document : Set.copyOf(session.documents))
                DialogUtils.close(document);
            if (session.sync != null)
                session.sync.close();
        }
        if (instance.timer != null)
            instance.timer.stop();
        instance = null;
    }

    /// Mutable process history for one instance and configured source.
    @NotNullByDefault
    private final class Session {
        /// Fixed version-root `.launcher` directory.
        final Path root;
        /// Explicit instance-to-server mapping, absent on validation failure.
        final @Nullable ContentSync.Config config;
        /// Configuration failure displayed only in status or when launching.
        final @Nullable String error;
        /// Source-specific receipts and local payloads.
        final @Nullable ContentStore store;
        /// Conditional synchronizer, absent for disabled or invalid configuration.
        final @Nullable ContentSync sync;
        /// Latest immutable worker result.
        @Nullable ContentSync.Result result;
        /// Notices already acknowledged or queued during this launcher process.
        final Set<String> notices = new HashSet<>();
        /// Individually collapsed revisions; new announcements and new revisions start expanded.
        final Set<String> collapsed = new HashSet<>();
        /// One queued/displayed notice at a time for this instance.
        @Nullable NoticePane notice;
        /// Read-only documents currently open or queued for this instance.
        final Set<DocumentPane> documents = new HashSet<>();
        /// Homepage scroll position retained across content refreshes and instance switches.
        double announcementPosition;
        /// Management reads/refreshes must not enqueue notices, including for the currently selected instance.
        boolean quiet;

        /// Creates an idle session; selection explicitly activates synchronization.
        Session(Path root, @Nullable ContentSync.Config config, @Nullable String error) {
            this.root = root;
            this.config = config;
            this.error = error;
            if (config != null && config.enabled()) {
                store = new ContentStore(root, config.endpoint().toString());
                sync = new ContentSync(config, store, update -> Platform.runLater(() -> receive(this, update)));
            } else {
                store = null;
                sync = null;
            }
        }
    }

    /// Captured target prevents protocol completion from accidentally launching another selected instance.
    @NotNullByDefault
    private final class LaunchRequest {
        /// Fixed target session.
        final Session session;
        /// Original launch continuation.
        final Runnable action;
        /// Selection epoch at creation.
        final long selectionEpoch;
        /// Request-owned protocol dialog, if needed.
        @Nullable AgreementPane pane;

        /// Captures target and selection identity.
        LaunchRequest(Session session, Runnable action) {
            this.session = session;
            this.action = action;
            selectionEpoch = epoch;
        }
    }

    /// Returns a session, replacing it only when its own configuration changes.
    private Session session(Path root) {
        @Nullable ContentSync.Config config = null;
        @Nullable String error = null;
        try {
            config = ContentSync.Config.load(root);
        } catch (IOException | RuntimeException e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        @Nullable Session old = sessions.get(root);
        if (old != null && Objects.equals(old.config, config) && Objects.equals(old.error, error))
            return old;
        if (old != null) {
            if (old.sync != null)
                old.sync.close();
            for (DocumentPane document : Set.copyOf(old.documents))
                DialogUtils.close(document);
        }
        Session next = new Session(root, config, error);
        sessions.put(root, next);
        return next;
    }

    /// Switches presentation and cancels obsolete network/launch work while retaining successful session state.
    private void select(@Nullable HMCLGameInstance game) {
        if (closed)
            return;
        @Nullable Session next = game == null ? null : session(root(game));
        if (next == current)
            return;
        epoch++;
        cancelLaunch();
        @Nullable Session old = current;
        current = next;
        if (old != null) {
            pauseUnused(old);
            if (old.notice != null)
                DialogUtils.close(old.notice);
        }
        render();
        if (next != null) {
            next.quiet = false;
            if (next.sync != null)
                next.sync.activate();
            enqueueNotice(next);
        }
        boundaries();
    }

    /// Returns fixed `versions/<id>/.launcher`, independent of custom run-directory settings.
    public static Path root(HMCLGameInstance game) {
        return game.getInstanceRoot().toAbsolutePath().normalize().resolve(".launcher");
    }

    /// Context-menu launches select their exact target before obtaining a source-specific protocol decision.
    private void launch(HMCLGameInstance game, Runnable action) {
        if (closed)
            return;
        GameDirectoryManager.setSelectedGameDirectory(game.getRepository().getGameDirectory());
        game.getRepository().setSelectedInstance(game);
        select(game);
        cancelLaunch();
        Session owner = Objects.requireNonNull(current);
        owner.quiet = false;
        if (owner.config != null && !owner.config.enabled()) {
            action.run();
            return;
        }
        LaunchRequest request = new LaunchRequest(owner, action);
        pending = request;
        if (owner.notice != null)
            DialogUtils.close(owner.notice);
        if (owner.sync != null)
            owner.sync.activate();
        checkLaunch(request);
    }

    /// Stores results on their owner; inactive sessions never replace another instance's homepage.
    private void receive(Session owner, ContentSync.Result update) {
        if (closed || sessions.get(owner.root) != owner)
            return;
        owner.result = update;
        if (update.error() != null)
            LOG.warning("Instance content sync failed at " + owner.root + ": " + update.error());
        if (owner.notice != null && !present(owner, owner.notice.manifest, owner.notice.item, RemoteContent.Kind.NOTICES))
            DialogUtils.close(owner.notice);
        for (DocumentPane document : Set.copyOf(owner.documents)) {
            if (!present(owner, document.manifest, document.item, document.kind))
                DialogUtils.close(document);
        }
        for (InformationPane pane : Set.copyOf(informationPanes)) {
            if (pane.owner == owner)
                pane.update();
        }
        boundaries();
        if (owner != current)
            return;
        render();
        @Nullable LaunchRequest request = pending;
        if (request != null && request.session == owner)
            checkLaunch(request);
        enqueueNotice(owner);
    }

    /// Reuses consent only for the latest known protocol, after the initial online check has completed or failed.
    private void checkLaunch(LaunchRequest request) {
        if (!valid(request))
            return;
        Session owner = request.session;
        @Nullable ContentSync.Result result = owner.result;
        @Nullable RemoteContent.Manifest manifest = result == null ? null : result.manifest();
        String problem = owner.error == null ? "正在检查此实例的用户协议…" : owner.error;
        if (owner.store != null && owner.config != null && manifest != null && !(result.cached() && result.pending())) {
            try {
                ContentDocuments.validate(owner.root, manifest, manifest.agreement(), owner.store, owner.config.endpoint(), true);
                if (owner.store.accepted(manifest)) {
                    resume(request);
                    return;
                }
                agreement(request, manifest, null);
                return;
            } catch (IOException | RuntimeException e) {
                problem = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        } else if (result != null && result.error() != null) {
            problem = result.error();
        }
        agreement(request, null, problem);
    }

    /// Updates one protocol dialog rather than stacking dialogs during download progress.
    private void agreement(LaunchRequest request, @Nullable RemoteContent.Manifest manifest, @Nullable String problem) {
        if (request.pane == null) {
            AgreementPane pane = new AgreementPane(request);
            request.pane = pane;
            pane.update(manifest, problem);
            Controllers.dialog(pane);
        } else {
            request.pane.update(manifest, problem);
        }
    }

    /// Ordinary modal protocol; every rejection/close cancels only the captured game launch.
    @NotNullByDefault
    private final class AgreementPane extends JFXDialogLayout implements DialogAware {
        /// Captured launch owner.
        private final LaunchRequest request;
        /// Prevents progress updates while the durable consent write is executing.
        private boolean saving;
        /// Inline link and persistence errors.
        private final Label error = new Label();
        /// Explicit acknowledgement remains outside the scrolling protocol body.
        private final JFXCheckBox consent = new JFXCheckBox("我已阅读并同意遵守上述协议与规定");
        /// Identity of the protocol the checkbox currently acknowledges.
        private @Nullable String agreementKey;
        /// Stable window content area determines the dialog's available height.
        private final Region availableArea = Controllers.getDecorator().getDialogContainer();
        /// Invalidates preferred size when the window changes without retaining closed dialogs.
        private final InvalidationListener availableHeightListener = observable -> requestLayout();

        /// Creates a cancellable protocol dialog without a global interaction gate.
        AgreementPane(LaunchRequest request) {
            this.request = request;
            setId("bf656-instance-agreement");
            setMaxHeight(Region.USE_PREF_SIZE);
            availableArea.heightProperty().addListener(availableHeightListener);
            consent.setId("bf656-agreement-consent");
            consent.setWrapText(true);
            error.setWrapText(true);
            addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    cancelLaunch();
                    event.consume();
                }
            });
        }

        /// Presents the protocol or an unavailable-content state with retry/cancel actions.
        void update(@Nullable RemoteContent.Manifest manifest, @Nullable String problem) {
            if (saving)
                return;
            setHeading(heading(manifest == null ? "暂时无法启动此实例" : manifest.agreement().title()
                    + " · 第 " + manifest.agreement().revision() + " 版"));
            error.setText("");
            error.setManaged(false);
            if (manifest == null) {
                agreementKey = null;
                consent.setSelected(false);
                Label message = new Label(problem == null ? "正在获取协议" : problem);
                message.setWrapText(true);
                setBody(adaptiveScroll(new VBox(12, message, new Label("取消后仍可使用启动器。"))));
                setActions(button("重试", () -> retry(request)), button("取消本次启动", RemoteContentController.this::cancelLaunch));
            } else {
                String nextKey = manifest.key(manifest.agreement());
                if (!nextKey.equals(agreementKey))
                    consent.setSelected(false);
                agreementKey = nextKey;
                VBox body = new VBox(10, view(request.session, manifest, manifest.agreement(), text -> {
                    error.setText(text);
                    error.setManaged(true);
                }), error);
                @Nullable ContentSync.Result result = request.session.result;
                if (result != null && result.cached())
                    body.getChildren().add(new Label("网络暂不可用，使用此实例缓存的协议。"));
                setBody(new VBox(16, adaptiveScroll(body), consent));
                JFXButton agree = button("同意", () -> accept(request, manifest));
                agree.setId("bf656-agreement-accept");
                agree.disableProperty().bind(consent.selectedProperty().not());
                setActions(button("不同意", RemoteContentController.this::cancelLaunch), agree);
            }
        }

        /// A stale action or unchecked acknowledgement cannot authorize any protocol revision.
        boolean confirmed(RemoteContent.Manifest manifest) {
            return !saving && consent.isSelected() && manifest.key(manifest.agreement()).equals(agreementKey);
        }

        /// Caps the whole dialog, including fixed controls, while short bodies retain their natural height.
        @Override
        protected double computePrefHeight(double width) {
            double available = availableArea.getHeight();
            double limit = available > 0 ? Math.min(720, available * 0.8) : 720;
            return Math.min(super.computePrefHeight(width), limit);
        }

        /// Delayed obsolete dialogs cannot authorize a new selection.
        @Override
        public void onDialogShown() {
            if (!valid(request))
                Platform.runLater(() -> DialogUtils.close(this));
        }

        /// External closure never grants consent.
        @Override
        public void onDialogClosed() {
            availableArea.heightProperty().removeListener(availableHeightListener);
            if (pending == request)
                pending = null;
            Platform.runLater(() -> enqueueNotice(request.session));
        }
    }

    /// Explicit retry reloads invalid configuration and otherwise refreshes the current source.
    private void retry(LaunchRequest request) {
        if (!valid(request))
            return;
        Session owner = request.session;
        if (owner.sync != null) {
            owner.sync.retryFailed();
        } else {
            Session next = session(owner.root);
            if (next != owner) {
                cancelLaunch();
                current = next;
                if (next.sync != null)
                    next.sync.activate();
                if (next.config != null && !next.config.enabled()) {
                    request.action.run();
                    return;
                }
                LaunchRequest replacement = new LaunchRequest(next, request.action);
                pending = replacement;
                checkLaunch(replacement);
            }
        }
    }

    /// Consent must be durable, and selection/protocol identity is rechecked after the background save.
    private void accept(LaunchRequest request, RemoteContent.Manifest manifest) {
        if (!valid(request) || request.session.store == null || request.session.config == null)
            return;
        Session owner = request.session;
        ContentStore store = owner.store;
        ContentSync.Config config = owner.config;
        @Nullable AgreementPane pane = request.pane;
        if (pane == null || !pane.confirmed(manifest))
            return;
        pane.saving = true;
        pane.setDisable(true);
        CompletableFuture.runAsync(() -> {
            try {
                ContentDocuments.validate(owner.root, manifest, manifest.agreement(), store, config.endpoint(), true);
                store.accept(manifest);
                Platform.runLater(() -> {
                    if (pane != null) {
                        pane.saving = false;
                        pane.setDisable(false);
                    }
                    @Nullable ContentSync.Result latest = owner.result;
                    if (valid(request) && latest != null && latest.manifest() != null
                            && latest.manifest().key(latest.manifest().agreement()).equals(manifest.key(manifest.agreement())))
                        resume(request);
                    else if (valid(request))
                        checkLaunch(request);
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    if (pane != null) {
                        pane.saving = false;
                        pane.setDisable(false);
                        pane.error.setManaged(true);
                        pane.error.setText("无法保存同意记录或协议资源不可用：" + e.getMessage());
                    }
                });
            }
        });
    }

    /// The continuation remains valid only for its original request and selection epoch.
    private boolean valid(LaunchRequest request) {
        return !closed && pending == request && current == request.session && epoch == request.selectionEpoch;
    }

    /// Resumes exactly one launch after removing its own confirmation dialog.
    private void resume(LaunchRequest request) {
        if (!valid(request))
            return;
        pending = null;
        if (request.pane != null)
            DialogUtils.close(request.pane);
        request.action.run();
    }

    /// Cancels only the game launch, leaving navigation and the application alive.
    private void cancelLaunch() {
        @Nullable LaunchRequest request = pending;
        pending = null;
        if (request != null && request.pane != null)
            DialogUtils.close(request.pane);
    }

    /// Offers notices on selection without requiring protocol acceptance or duplicating same-session messages.
    private void enqueueNotice(Session owner) {
        @Nullable ContentSync.Result result = owner.result;
        @Nullable ContentStore store = owner.store;
        if (closed || owner.quiet || current != owner || pending != null || owner.notice != null || result == null
                || result.manifest() == null || store == null)
            return;
        RemoteContent.Manifest manifest = result.manifest();
        for (RemoteContent.Item item : manifest.notices()) {
            String key = manifest.key(item);
            if (!item.active(Instant.now()) || !store.ready(manifest, item) || owner.notices.contains(key)
                    || (!item.repeat().equals("each_start") && store.remembered("notices", key)))
                continue;
            owner.notices.add(key);
            NoticePane pane = new NoticePane(owner, manifest, item);
            owner.notice = pane;
            Controllers.dialogLater(pane);
            break;
        }
    }

    /// Whether an item still belongs to the current valid server snapshot and publication window.
    private boolean present(Session owner, RemoteContent.Manifest snapshot, RemoteContent.Item item, RemoteContent.Kind kind) {
        @Nullable ContentSync.Result result = owner.result;
        return result != null && result.manifest() != null && item.active(Instant.now())
                && result.manifest().items(kind).stream().anyMatch(next -> result.manifest().key(next).equals(snapshot.key(item)));
    }

    /// A queued notice belongs to exactly one instance and one published revision.
    @NotNullByDefault
    private final class NoticePane extends JFXDialogLayout implements DialogAware {
        /// Owning instance session.
        private final Session owner;
        /// Immutable body/resource snapshot.
        private final RemoteContent.Manifest manifest;
        /// Notice represented by this dialog.
        private final RemoteContent.Item item;
        /// Explicit user confirmation, independent of close/cancel events.
        private boolean acknowledged;

        /// Creates a Markdown-capable notice in the normal dialog queue.
        NoticePane(Session owner, RemoteContent.Manifest manifest, RemoteContent.Item item) {
            this.owner = owner;
            this.manifest = manifest;
            this.item = item;
            setHeading(heading(item.title()));
            setBody(scroll(view(owner, manifest, item, text -> Controllers.dialog(text, "无法打开文档")), 280));
            setActions(button("我已知晓", () -> {
                acknowledged = true;
                remember(owner, "notices", manifest.key(item));
                fireEvent(new DialogCloseEvent());
            }));
        }

        /// Obsolete or deselected notices close normally so the global dialog queue keeps advancing.
        @Override
        public void onDialogShown() {
            @Nullable ContentSync.Result latest = owner.result;
            boolean present = latest != null && latest.manifest() != null && latest.manifest().notices().stream()
                    .anyMatch(next -> latest.manifest().key(next).equals(manifest.key(item)));
            if (owner.quiet || current != owner || !item.active(Instant.now()) || !present || pending != null)
                Platform.runLater(() -> DialogUtils.close(this));
        }

        /// Unacknowledged notices may be offered when their owning instance is selected again.
        @Override
        public void onDialogClosed() {
            if (!acknowledged)
                owner.notices.remove(manifest.key(item));
            if (owner.notice == this)
                owner.notice = null;
            Platform.runLater(() -> enqueueNotice(owner));
        }
    }

    /// Renders all ready active cards; legacy dismissal receipts no longer hide announcements.
    private void render() {
        @Nullable Session owner = current;
        @Nullable ContentSync.Result result = owner == null ? null : owner.result;
        @Nullable ContentStore store = owner == null ? null : owner.store;
        if (owner == null || result == null || result.manifest() == null || store == null) {
            Controllers.getRootPage().getMainPage().setRemoteAnnouncements(null);
            return;
        }
        RemoteContent.Manifest manifest = result.manifest();
        @Unmodifiable List<RemoteContent.Item> visible = manifest.announcements().stream()
                .filter(item -> item.active(Instant.now()) && store.ready(manifest, item)).toList();
        if (visible.isEmpty()) {
            Controllers.getRootPage().getMainPage().setRemoteAnnouncements(null);
            return;
        }
        VBox entries = new VBox(14);
        entries.setMinWidth(0);
        entries.setPadding(new Insets(4, 10, 10, 4));
        for (RemoteContent.Item item : visible) {
            String key = manifest.key(item);
            Label title = heading((item.pinned() ? "[置顶] " : "") + item.title());
            title.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(title, Priority.ALWAYS);
            Label error = new Label();
            error.setWrapText(true);
            error.setManaged(false);
            VBox body = new VBox(10);
            if (!item.publishedAt().isEmpty())
                body.getChildren().add(new Label(item.publishedAt()));
            body.getChildren().addAll(view(owner, manifest, item, text -> {
                error.setText(text);
                error.setManaged(true);
            }), error);
            body.setVisible(!owner.collapsed.contains(key));
            body.setManaged(body.isVisible());
            JFXButton toggle = new JFXButton();
            toggle.setId("bf656-announcement-toggle-" + item.id());
            toggle.getStyleClass().add("toggle-icon");
            toggle.setGraphic((body.isVisible() ? SVG.KEYBOARD_ARROW_UP : SVG.KEYBOARD_ARROW_DOWN).createIcon(20));
            toggle.setAccessibleText((body.isVisible() ? "收起公告：" : "展开公告：") + item.title());
            toggle.setOnAction(event -> {
                boolean expanded = !body.isVisible();
                if (expanded)
                    owner.collapsed.remove(key);
                else
                    owner.collapsed.add(key);
                body.setVisible(expanded);
                body.setManaged(expanded);
                toggle.setGraphic((expanded ? SVG.KEYBOARD_ARROW_UP : SVG.KEYBOARD_ARROW_DOWN).createIcon(20));
                toggle.setAccessibleText((expanded ? "收起公告：" : "展开公告：") + item.title());
            });
            title.setCursor(Cursor.HAND);
            title.setOnMouseClicked(event -> toggle.fire());
            HBox header = new HBox(10, title, toggle);
            header.setAlignment(Pos.CENTER_LEFT);
            VBox card = new VBox(10, header, body);
            card.getStyleClass().addAll("card", "announcement", "remote-announcement-card");
            card.setMinWidth(0);
            card.setMinHeight(Region.USE_PREF_SIZE);
            card.setMaxWidth(Double.MAX_VALUE);
            entries.getChildren().add(card);
        }
        if (result.cached())
            entries.getChildren().add(new Label("离线缓存 · 上次同步 " + time(result.checkedAt())));
        ScrollPane feed = new ScrollPane(entries);
        feed.setId("bf656-announcement-feed");
        feed.setFitToWidth(true);
        feed.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        feed.setMinSize(0, 0);
        feed.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        feed.setVvalue(owner.announcementPosition);
        feed.vvalueProperty().addListener((observable, oldValue, newValue) -> owner.announcementPosition = newValue.doubleValue());
        Controllers.getRootPage().getMainPage().setRemoteAnnouncements(feed);
    }

    /// Schedules only local publication/expiry updates, including cleanup for previously loaded inactive instances.
    private void boundaries() {
        if (timer != null)
            timer.stop();
        @Nullable Instant next = null;
        Instant now = Instant.now();
        for (Session owner : sessions.values()) {
            @Nullable ContentSync.Result result = owner.result;
            if (result == null || result.manifest() == null)
                continue;
            for (RemoteContent.Kind kind : RemoteContent.Kind.values()) {
                for (RemoteContent.Item item : result.manifest().items(kind)) {
                    @Nullable Instant start = item.startsAt();
                    @Nullable Instant expiry = item.expiresAt();
                    if (start != null && start.isAfter(now) && (next == null || start.isBefore(next)))
                        next = start;
                    if (expiry != null && expiry.isAfter(now) && (next == null || expiry.isBefore(next)))
                        next = expiry;
                }
            }
        }
        if (next != null) {
            PauseTransition boundary = new PauseTransition(Duration.millis(Math.max(50, java.time.Duration.between(now, next).toMillis() + 20)));
            boundary.setOnFinished(event -> {
                for (Session owner : sessions.values()) {
                    if (owner.sync != null)
                        owner.sync.prune();
                    if (owner.notice != null && !owner.notice.item.active(Instant.now()))
                        DialogUtils.close(owner.notice);
                    for (DocumentPane document : Set.copyOf(owner.documents)) {
                        if (!document.item.active(Instant.now()))
                            DialogUtils.close(document);
                    }
                }
                render();
                if (current != null)
                    enqueueNotice(current);
                boundaries();
            });
            timer = boundary;
            boundary.play();
        }
    }

    /// Resolves management context, including edited configuration, without selecting an instance or showing its notices.
    private Session informationSession(Path root) {
        Session owner = session(root);
        owner.quiet = true;
        if (current != null && current.root.equals(root) && current != owner) {
            epoch++;
            cancelLaunch();
            if (current.notice != null)
                DialogUtils.close(current.notice);
            current = owner;
            render();
        }
        for (InformationPane pane : informationPanes) {
            if (pane.owner.root.equals(root))
                pane.owner = owner;
        }
        return owner;
    }

    /// Keeps explicit management reads alive; unused inactive instances retain only their successful cached state.
    private void pauseUnused(Session owner) {
        if (owner != current && owner.sync != null && owner.documents.isEmpty()
                && informationPanes.stream().noneMatch(pane -> pane.owner == owner))
            owner.sync.pause();
    }

    /// Opens an instance-bound view and synchronizes only that target, never changing homepage selection.
    private void information(HMCLGameInstance game) {
        if (closed)
            return;
        Session owner = informationSession(root(game));
        InformationPane pane = new InformationPane(owner);
        informationPanes.add(pane);
        Controllers.dialog(pane);
        if (owner.sync != null)
            owner.sync.activate();
    }

    /// Status, protocol and refresh actions remain bound to the management target even when another instance is selected.
    @NotNullByDefault
    private final class InformationPane extends JFXDialogLayout implements DialogAware {
        /// Target session is replaced only when this target's configuration is explicitly reloaded.
        private Session owner;

        /// Creates the existing information dialog without announcement links or additional entry points.
        InformationPane(Session owner) {
            this.owner = owner;
            setId("bf656-instance-information");
            setHeading(heading("实例服务器信息"));
            update();
        }

        /// Rebuilds status from this owner's latest download result, leaving other instances' presentation untouched.
        void update() {
            VBox body = new VBox(12);
            Label path = new Label("实例配置：\n" + owner.root.resolve("client/config.json"));
            path.setWrapText(true);
            body.getChildren().add(path);
            if (owner.error != null) {
                body.getChildren().add(new Label(owner.error));
            } else if (owner.sync == null) {
                body.getChildren().add(new Label("此实例未启用远程内容。"));
            } else {
                body.getChildren().add(new Label("内容标识：" + Objects.requireNonNull(owner.config).instanceKey()));
                @Nullable ContentSync.Result result = owner.result;
                String status = result == null ? "尚未同步" : result.success() ? "同步成功"
                        : result.pending() ? "正在同步正文或图片" : "同步失败，" + (result.exhausted() ? "自动重试已结束" : "将有限重试");
                body.getChildren().add(new Label(status));
                if (result != null && result.manifest() != null) {
                    RemoteContent.Manifest manifest = result.manifest();
                    body.getChildren().add(new Label("上次检查：" + time(result.checkedAt())));
                    JFXButton protocol = button("查看用户协议", () -> document(owner, manifest, manifest.agreement()));
                    protocol.setDisable(owner.store == null || !owner.store.ready(manifest, manifest.agreement()));
                    body.getChildren().add(protocol);
                }
            }
            setBody(scroll(body, 290));
            setActions(button("刷新当前实例", this::refresh), button("关闭", () -> fireEvent(new DialogCloseEvent())));
        }

        /// Reloads and refreshes this exact instance; no global selection lookup is involved.
        private void refresh() {
            owner = informationSession(owner.root);
            update();
            if (owner.sync != null)
                owner.sync.refresh();
        }

        /// A queued management view is still independent of the selected instance.
        @Override
        public void onDialogShown() {
            if (closed)
                Platform.runLater(() -> DialogUtils.close(this));
        }

        /// Releases management-owned background work without acknowledging notices or changing consent.
        @Override
        public void onDialogClosed() {
            informationPanes.remove(this);
            pauseUnused(owner);
        }
    }

    /// Opens a read-only full document without changing receipts or dismissals.
    private void document(Session owner, RemoteContent.Manifest manifest, RemoteContent.Item item) {
        DocumentPane document = new DocumentPane(owner, manifest, item);
        owner.documents.add(document);
        Controllers.dialog(document);
    }

    /// Read-only views close on withdrawal, expiry or source/revision replacement, independently of selection.
    @NotNullByDefault
    private final class DocumentPane extends JFXDialogLayout implements DialogAware {
        /// Instance owning this view.
        private final Session owner;
        /// Immutable resource snapshot used by the renderer.
        private final RemoteContent.Manifest manifest;
        /// Document item represented by this view.
        private final RemoteContent.Item item;
        /// Public category determines whether the current snapshot still contains this revision.
        private final RemoteContent.Kind kind;

        /// Creates a read-only view without altering agreement or dismissal receipts.
        DocumentPane(Session owner, RemoteContent.Manifest manifest, RemoteContent.Item item) {
            this.owner = owner;
            this.manifest = manifest;
            this.item = item;
            setId("bf656-document");
            kind = item == manifest.agreement() ? RemoteContent.Kind.AGREEMENT : RemoteContent.Kind.ANNOUNCEMENTS;
            setHeading(heading(item.title()));
            setBody(scroll(view(owner, manifest, item, text -> Controllers.dialog(text, "无法打开文档")), 290));
            setActions(button("关闭", () -> fireEvent(new DialogCloseEvent())));
        }

        /// Read-only views stay bound to their source and revision, independently of homepage selection.
        @Override
        public void onDialogShown() {
            if (closed || sessions.get(owner.root) != owner || !present(owner, manifest, item, kind))
                Platform.runLater(() -> DialogUtils.close(this));
        }

        /// Releases the instance's reference when the view closes.
        @Override
        public void onDialogClosed() {
            owner.documents.remove(this);
            pauseUnused(owner);
        }
    }

    /// Constructs a Markdown view with explicit instance and source context.
    private MarkdownView view(Session owner, RemoteContent.Manifest manifest, RemoteContent.Item item, Consumer<String> error) {
        MarkdownView view = new MarkdownView(owner.root, manifest, item, Objects.requireNonNull(owner.store),
                Objects.requireNonNull(owner.config).endpoint(), error);
        if (item == manifest.agreement() && !item.introduction().isBlank()) {
            Label introduction = new Label(item.introduction());
            introduction.setId("bf656-agreement-introduction");
            introduction.setWrapText(true);
            introduction.setMinWidth(0);
            introduction.setMaxWidth(Double.MAX_VALUE);
            view.getChildren().add(0, introduction);
        }
        return view;
    }

    /// Saves confirmation preferences off the FX thread without opening dialogs over another selected instance.
    private void remember(Session owner, String category, String key) {
        @Nullable ContentStore store = owner.store;
        if (store == null)
            return;
        CompletableFuture.runAsync(() -> {
            try {
                store.remember(category, key);
            } catch (IOException | RuntimeException e) {
                LOG.warning("Cannot save instance content receipt", e);
                Platform.runLater(() -> {
                    if (!closed && current == owner)
                        Controllers.dialog("无法保存确认记录，下次可能再次显示。\n" + e.getMessage());
                });
            }
        });
    }

    /// Creates a wrapping heading that respects the active launcher theme.
    private static Label heading(String title) {
        Label label = new Label(title);
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        return label;
    }

    /// Keeps long documents and images inside the available card/dialog viewport.
    private static ScrollPane scroll(Node body, double height) {
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(550);
        scroll.setPrefViewportHeight(height);
        scroll.setMinHeight(80);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /// Measures protocol text and asynchronously loaded images without imposing a fixed viewport height.
    @NotNullByDefault
    private static final class NaturalScrollPane extends ScrollPane {
        /// Creates a width-fitting viewport whose minimum height allows the fixed controls to remain visible.
        NaturalScrollPane(Node body) {
            super(body);
            setId("bf656-agreement-body");
            setFitToWidth(true);
            setHbarPolicy(ScrollBarPolicy.NEVER);
            setPrefViewportWidth(550);
            setMinHeight(0);
            VBox.setVgrow(this, Priority.ALWAYS);
        }

        /// Uses the actual document's wrapping height, including viewport padding, for natural dialog sizing.
        @Override
        protected double computePrefHeight(double width) {
            @Nullable Node content = getContent();
            double availableWidth = Math.max(0, (width < 0 ? getPrefViewportWidth() : width)
                    - snappedLeftInset() - snappedRightInset());
            double height = content instanceof Region region ? region.prefHeight(availableWidth)
                    : content == null ? 0 : content.getLayoutBounds().getHeight();
            return height + snappedTopInset() + snappedBottomInset();
        }
    }

    /// Gives protocol and loading/error bodies content-driven height while retaining one internal scroll viewport.
    private static ScrollPane adaptiveScroll(Node body) {
        return new NaturalScrollPane(body);
    }

    /// Creates a normal read-only document/status pane.
    private static JFXDialogLayout pane(String title, Node body) {
        JFXDialogLayout pane = new JFXDialogLayout();
        pane.setHeading(heading(title));
        pane.setBody(scroll(body, 290));
        return pane;
    }

    /// Creates a button whose callback explicitly controls closure and continuation.
    private static JFXButton button(String title, Runnable action) {
        JFXButton button = new JFXButton(title);
        button.getStyleClass().add("dialog-accept");
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Formats HTTP validation time in the desktop timezone.
    private static String time(@Nullable Instant instant) {
        return instant == null ? "未知" : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(instant);
    }
}
