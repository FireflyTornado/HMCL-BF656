/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.jfoenix.controls.JFXButton;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.main.MinecraftServerStatus.Result;
import org.jackhuang.hmcl.ui.main.SavedServerList.Contents;
import org.jackhuang.hmcl.ui.main.SavedServerList.LimitExceededException;
import org.jackhuang.hmcl.ui.main.SavedServerList.SavedServer;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Shows the current instance's saved multiplayer servers and refreshes the selected server while home is visible.
@NotNullByDefault
final class MultiplayerServerStatusPane extends HBox {
    /// Highlights the dot after a valid response is received.
    private static final PseudoClass ONLINE = PseudoClass.getPseudoClass("online");
    /// Highlights the dot after the selected server fails to respond.
    private static final PseudoClass UNREACHABLE = PseudoClass.getPseudoClass("unreachable");
    /// Lights a signal bar when the measured latency falls within its tier.
    private static final PseudoClass LIT = PseudoClass.getPseudoClass("lit");
    /// Colors the lit bars yellow at two or three bars.
    private static final PseudoClass MEDIUM_LATENCY = PseudoClass.getPseudoClass("medium-latency");
    /// Colors the lit bar red at one bar.
    private static final PseudoClass HIGH_LATENCY = PseudoClass.getPseudoClass("high-latency");

    /// Shows the saved name when there is only one server.
    private final Label name = new Label();
    /// Selects a saved server when multiple entries are present.
    private final MenuButton selector = new MenuButton();
    /// Displays the latest connection state beside the status dot.
    private final Label status = new Label();
    /// Displays the reported player counts in compact server-list format.
    private final Label players = new Label();
    /// Holds the latency bars and the connection-failure overlay in the same position.
    private final StackPane signal = new StackPane();
    /// Shows five ascending latency bars.
    private final HBox signalBars = new HBox(1);
    /// Marks the signal indicator when the server cannot be reached.
    private final Node signalCross = SVG.CLOSE.createIcon(14);
    /// Shows the round-trip time or current signal state under the pointer.
    private final Tooltip signalTooltip = new Tooltip();
    /// Indicates query state independently of the theme's text color.
    private final Region dot = new Region();
    /// Immediately refreshes the selected server with keyboard and mouse activation.
    private final JFXButton refreshButton = new JFXButton();
    /// Indicates an in-progress query in the refresh button.
    private final ProgressIndicator spinner = new ProgressIndicator();
    /// Default graphic restored when the current query finishes.
    private final Node refreshIcon = SVG.REFRESH.createIcon(18);
    /// Holds the title control and the state line.
    private final VBox details = new VBox(1);
    /// Re-reads the file after a save without depending on directory-watch support.
    private final Timeline filePoll = new Timeline(new KeyFrame(Duration.seconds(2), event -> readList(false)));
    /// Schedules the next query relative to the last completion.
    private final PauseTransition autoRefresh = new PauseTransition(Duration.seconds(30));
    /// Coalesces concurrent requests to the same server address.
    private final Map<String, CompletableFuture<@Nullable Result>> requests = new HashMap<>();
    /// Follows the launcher window through stage replacement and minimization.
    private final ObservableValue<Boolean> iconified = BindingMapping.of(sceneProperty())
            .flatMap((@Nullable Scene scene) -> scene == null ? null : scene.windowProperty(), () -> null)
            .flatMap(window -> window instanceof Stage stage ? stage.iconifiedProperty() : null, () -> false);

    /// Instance whose run directory provides the server list.
    private @Nullable HMCLGameInstance game;
    /// File in the current run directory, which may change with instance settings.
    private @Nullable Path file;
    /// Version last accepted or rejected after reading the file.
    private @Nullable FileVersion fileVersion;
    /// Error currently displayed on the card, if any.
    private @Nullable ListError listError;
    /// Allows one unsuccessful read before displaying an error for a possible in-progress save.
    private boolean retryingList;
    /// Guards against applying an obsolete file read after an instance or visibility change.
    private int listRevision;
    /// Whether a file check is already running for the active list revision.
    private boolean readingList;
    /// Visible list contents from the last successful read.
    private Contents serverList = SavedServerList.EMPTY;
    /// Currently selected entry from the file.
    private final ObjectProperty<@Nullable SavedServer> selected = new SimpleObjectProperty<>();
    /// Guards the status and loading state against results for a previous selection.
    private int targetRevision;
    /// Whether the status card is following the logical homepage.
    private boolean homeVisible;
    /// Whether the homepage is visible and the stage is not minimized.
    private boolean active;
    /// Whether a query completion is already registered for the selected target.
    private boolean refreshing;

    /// Builds the card and binds its activity to the homepage and stage.
    MultiplayerServerStatusPane() {
        super(8);
        getStyleClass().add("multiplayer-server-status");
        setAlignment(Pos.CENTER_LEFT);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setAccessibleText(i18n("bf656.server_status.title"));
        setVisible(false);
        managedProperty().bind(visibleProperty());

        name.getStyleClass().add("multiplayer-server-name");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setMinWidth(0);
        selector.getStyleClass().add("multiplayer-server-selector");
        selector.setMaxWidth(Double.MAX_VALUE);
        selector.setMinWidth(0);
        selector.setPopupSide(Side.TOP);

        dot.getStyleClass().add("multiplayer-server-dot");
        dot.setMinSize(7, 7);
        dot.setMaxSize(7, 7);
        status.getStyleClass().add("multiplayer-server-message");
        status.setMinWidth(0);

        players.getStyleClass().addAll("multiplayer-server-message", "multiplayer-server-players");
        players.visibleProperty().bind(players.textProperty().isNotEmpty());
        players.managedProperty().bind(players.visibleProperty());
        signal.getStyleClass().add("multiplayer-server-signal");
        signal.setMaxHeight(USE_PREF_SIZE);
        signal.setPickOnBounds(true);
        signal.setVisible(false);
        signal.managedProperty().bind(signal.visibleProperty());
        signalBars.setAlignment(Pos.BOTTOM_LEFT);
        for (int height : List.of(3, 5, 7, 9, 11)) {
            Region bar = new Region();
            bar.getStyleClass().add("multiplayer-server-signal-bar");
            bar.setMinSize(3, height);
            bar.setMaxSize(3, height);
            signalBars.getChildren().add(bar);
        }
        signalCross.getStyleClass().add("multiplayer-server-signal-cross");
        signalCross.setMouseTransparent(true);
        signalCross.setVisible(false);
        signal.getChildren().setAll(signalBars, signalCross);
        FXUtils.installFastTooltip(signal, signalTooltip);

        Region statusSpacer = new Region();
        statusSpacer.managedProperty().bind(signal.visibleProperty());
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusLine = new HBox(6, dot, status, statusSpacer, players, signal);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        details.setAlignment(Pos.CENTER_LEFT);
        details.setMinWidth(0);
        details.getChildren().setAll(name, statusLine);
        HBox.setHgrow(details, Priority.ALWAYS);

        spinner.setMinSize(16, 16);
        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);
        refreshButton.getStyleClass().add("multiplayer-server-refresh");
        refreshButton.setAccessibleText(i18n("button.refresh"));
        refreshButton.setGraphic(refreshIcon);
        refreshButton.setOnAction(event -> refresh());
        refreshButton.managedProperty().bind(refreshButton.visibleProperty());
        getChildren().setAll(details, refreshButton);

        filePoll.setCycleCount(Animation.INDEFINITE);
        autoRefresh.setOnFinished(event -> refresh());
        iconified.addListener((observable, previous, current) -> updateActivity());
    }

    /// Switches to a newly selected game and forgets the previous game's selection.
    void setGame(@Nullable HMCLGameInstance game) {
        if (this.game == game)
            return;
        this.game = game;
        resetList(null);
        readList(false);
    }

    /// Starts or pauses work as the launcher navigates to or away from home.
    void setHomeVisible(boolean visible) {
        homeVisible = visible;
        updateActivity();
    }

    /// Runs the file check and status polling only while the homepage is visible.
    private void updateActivity() {
        boolean shouldRun = homeVisible && !Boolean.TRUE.equals(iconified.getValue());
        if (shouldRun == active)
            return;
        active = shouldRun;
        if (active) {
            readList(true);
            filePoll.playFromStart();
        } else {
            filePoll.stop();
            autoRefresh.stop();
            selector.hide();
            listRevision++;
            readingList = false;
        }
    }

    /// Clears list state and invalidates any outstanding read when its source changes.
    private void resetList(@Nullable Path nextFile) {
        file = nextFile;
        fileVersion = null;
        listRevision++;
        readingList = false;
        retryingList = false;
        selector.hide();
        serverList = SavedServerList.EMPTY;
        setListError(null);
        rebuildMenu();
        select(null);
    }

    /// Checks the selected instance's file off the FX thread, applying only the current source's result.
    private void readList(boolean refreshOnReturn) {
        @Nullable HMCLGameInstance currentGame = game;
        if (!active || currentGame == null)
            return;

        Path resolved = currentGame.getRunDirectory().resolve("servers.dat");
        if (!resolved.equals(file))
            resetList(resolved);
        if (readingList)
            return;

        readingList = true;
        int revision = listRevision;
        @Nullable FileVersion previous = refreshOnReturn && listError == null ? null : fileVersion;
        Schedulers.io().execute(() -> {
            @Nullable ServerList updated = readFile(resolved, previous);
            Platform.runLater(() -> {
                if (revision != listRevision)
                    return;
                readingList = false;
                if (updated == null)
                    retryingList = false;
                else
                    applyRead(updated);
                if (refreshOnReturn)
                    refresh();
            });
        });
    }

    /// Applies a stable result, allowing one failed read while Minecraft may be saving the file.
    private void applyRead(ServerList updated) {
        if (updated.error() == ListError.UNREADABLE && !retryingList && listError == null) {
            retryingList = true;
            return;
        }
        retryingList = false;
        fileVersion = updated.version();
        if (updated.error() != null) {
            if (listError != updated.error()) {
                selector.hide();
                serverList = SavedServerList.EMPTY;
                rebuildMenu();
                select(null);
                setListError(updated.error());
            }
            return;
        }

        boolean recovering = listError != null;
        setListError(null);
        if (!recovering && serverList.equals(updated.contents()))
            return;

        applyList(updated.contents());
    }

    /// Applies saved entries and retains the current selection if it can be matched after a save.
    private void applyList(Contents contents) {
        serverList = contents;
        rebuildMenu();
        @Unmodifiable List<SavedServer> servers = contents.entries();
        @Nullable SavedServer previous = selected.get();
        @Nullable SavedServer next = null;
        if (previous != null) {
            int exactIndex = servers.indexOf(previous);
            if (exactIndex >= 0) {
                next = servers.get(exactIndex);
            } else {
                List<SavedServer> byAddress = servers.stream()
                        .filter(server -> server.address().equals(previous.address())).toList();
                if (byAddress.size() == 1)
                    next = byAddress.get(0);
                else if (byAddress.isEmpty()) {
                    List<SavedServer> byName = servers.stream()
                            .filter(server -> server.name().equals(previous.name())).toList();
                    if (byName.size() == 1)
                        next = byName.get(0);
                }
            }
        }
        if (next == null && !servers.isEmpty())
            next = servers.get(0);
        select(next);
    }

    /// Shows a file error separately from the connection status and restores the controls on recovery.
    private void setListError(@Nullable ListError error) {
        listError = error;
        refreshButton.setVisible(error == null);
        status.setWrapText(error != null);
        if (error != null) {
            name.setText(i18n("bf656.server_status.title"));
            status.setText(i18n(error.messageKey));
            setVisible(true);
        }
    }

    /// Rebuilds the popup in the same order as the saved multiplayer list.
    private void rebuildMenu() {
        selector.getItems().clear();
        @Unmodifiable List<SavedServer> servers = serverList.entries();
        for (SavedServer server : servers)
            selector.getItems().add(createServerMenuItem(server));
        if (serverList.truncated()) {
            MenuItem notice = new MenuItem(i18n("bf656.server_status.list_truncated", SavedServerList.MAX_VISIBLE_SERVERS));
            notice.setDisable(true);
            selector.getItems().add(notice);
        }
        Node title = servers.size() > 1 ? selector : name;
        if (details.getChildren().get(0) != title)
            details.getChildren().set(0, title);
    }

    /// Builds a two-line menu item with a check mark reserved for the exact selected entry.
    private CustomMenuItem createServerMenuItem(SavedServer server) {
        Label serverName = new Label(server.name());
        serverName.getStyleClass().add("multiplayer-server-menu-name");
        serverName.setMinWidth(0);
        Label address = new Label(server.address());
        address.getStyleClass().add("multiplayer-server-menu-address");
        address.setMinWidth(0);
        VBox text = new VBox(2, serverName, address);
        text.getStyleClass().add("multiplayer-server-menu-text");
        HBox.setHgrow(text, Priority.ALWAYS);

        Node check = SVG.CHECK.createIcon(16);
        check.getStyleClass().add("multiplayer-server-menu-check");
        check.visibleProperty().bind(Bindings.createBooleanBinding(() -> selected.get() == server, selected));
        HBox row = new HBox(12, text, check);
        row.setAlignment(Pos.CENTER_LEFT);
        row.minWidthProperty().bind(widthProperty());
        row.getStyleClass().add("multiplayer-server-menu-row");

        // The menu sizes custom content by its preferred width; the wrapper accounts for the row's minimum.
        CustomMenuItem item = new CustomMenuItem(new StackPane(row), true);
        item.getStyleClass().add("multiplayer-server-menu-item");
        item.setText(server.name() + " " + server.address());
        item.setOnAction(event -> select(server));
        return item;
    }

    /// Updates the displayed name and resets the status only when the selected endpoint changes.
    private void select(@Nullable SavedServer server) {
        @Nullable SavedServer previous = selected.get();
        @Nullable String previousAddress = previous == null ? null : previous.address();
        @Nullable String nextAddress = server == null ? null : server.address();
        selected.set(server);
        setVisible(server != null);
        if (server != null) {
            name.setText(server.name());
            selector.setText(server.name());
        }
        if (Objects.equals(previousAddress, nextAddress))
            return;

        targetRevision++;
        autoRefresh.stop();
        setRefreshing(false);
        status.setText(i18n("bf656.server_status.checking"));
        dot.pseudoClassStateChanged(ONLINE, false);
        dot.pseudoClassStateChanged(UNREACHABLE, false);
        clearServerDetails();
        refresh();
    }

    /// Starts or joins the query for the selected address and schedules the next refresh after completion.
    private void refresh() {
        @Nullable SavedServer target = selected.get();
        if (!active || target == null || refreshing)
            return;
        autoRefresh.stop();
        setRefreshing(true);
        int revision = targetRevision;
        @Nullable CompletableFuture<@Nullable Result> request = requests.get(target.address());
        if (request == null || request.isDone()) {
            request = MinecraftServerStatus.query(target.address());
            requests.put(target.address(), request);
        }
        var started = request;
        started.whenCompleteAsync((result, error) -> {
            requests.remove(target.address(), started);
            if (targetRevision != revision)
                return;
            setRefreshing(false);
            applyStatus(result);
            if (active)
                autoRefresh.playFromStart();
        }, Platform::runLater);
    }

    /// Updates the refresh button while preserving the previous status during a same-target refresh.
    private void setRefreshing(boolean value) {
        refreshing = value;
        refreshButton.setGraphic(value ? spinner : refreshIcon);
        refreshButton.setDisable(value);
    }

    /// Updates the connection, compact player count and latency bars from the latest response.
    private void applyStatus(@Nullable Result result) {
        dot.pseudoClassStateChanged(ONLINE, result != null);
        dot.pseudoClassStateChanged(UNREACHABLE, result == null);
        if (result == null) {
            String message = i18n("bf656.server_status.unreachable");
            status.setText(message);
            players.setText("");
            showSignal(0, true, message);
            return;
        }

        status.setText(i18n("bf656.server_status.online"));
        if (result.online().isPresent()) {
            int online = result.online().getAsInt();
            if (result.capacity().isPresent()) {
                int capacity = result.capacity().getAsInt();
                players.setText(online + "/" + capacity);
                players.setAccessibleText(i18n("bf656.server_status.players", online, capacity));
            } else {
                players.setText(Integer.toString(online));
                players.setAccessibleText(i18n("bf656.server_status.player_count", online));
            }
        } else {
            players.setText("");
        }

        long latency = result.latencyMillis().orElse(-1);
        int lit = signalStrength(latency);
        String hint = lit == 0 ? i18n("bf656.server_status.latency_unknown")
                : i18n("bf656.server_status.latency", latency);
        showSignal(lit, false, hint);
    }

    /// Applies the tier color and shows the cross over inactive bars when the connection fails.
    private void showSignal(int lit, boolean unreachable, String hint) {
        signal.pseudoClassStateChanged(MEDIUM_LATENCY, lit == 2 || lit == 3);
        signal.pseudoClassStateChanged(HIGH_LATENCY, lit == 1);
        for (int i = 0; i < signalBars.getChildren().size(); i++)
            signalBars.getChildren().get(i).pseudoClassStateChanged(LIT, i < lit);
        signalCross.setVisible(unreachable);
        signalTooltip.setText(hint);
        signal.setAccessibleText(hint);
        signal.setVisible(true);
    }

    /// Hides the previous server's metrics while checking a new target.
    private void clearServerDetails() {
        players.setText("");
        signalTooltip.hide();
        signal.setVisible(false);
    }

    /// Maps a round-trip time in milliseconds to BF656's five gameplay-oriented signal tiers.
    private static int signalStrength(long latency) {
        if (latency < 0)
            return 0;
        if (latency < 50)
            return 5;
        if (latency < 100)
            return 4;
        if (latency < 150)
            return 3;
        if (latency < 250)
            return 2;
        return 1;
    }

    /// Reads a changed file within its size budget and distinguishes limit failures from other read errors.
    private static @Nullable ServerList readFile(Path file, @Nullable FileVersion previous) {
        @Nullable FileVersion version = null;
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            version = new FileVersion(attributes.lastModifiedTime(), attributes.creationTime(),
                    attributes.size(), attributes.fileKey());
            if (version.equals(previous))
                return null;
            if (attributes.size() > SavedServerList.MAX_FILE_BYTES)
                return new ServerList(version, SavedServerList.EMPTY, ListError.TOO_LARGE);
            if (!attributes.isRegularFile())
                return new ServerList(version, SavedServerList.EMPTY, ListError.UNREADABLE);
            byte[] bytes = SavedServerList.readBytes(file);
            try {
                return new ServerList(version, SavedServerList.parse(bytes), null);
            } catch (LimitExceededException e) {
                throw e;
            } catch (IOException e) {
                return new ServerList(version, SavedServerList.EMPTY, ListError.UNREADABLE);
            }
        } catch (NoSuchFileException e) {
            return new ServerList(null, SavedServerList.EMPTY, null);
        } catch (LimitExceededException e) {
            return new ServerList(version, SavedServerList.EMPTY, ListError.TOO_LARGE);
        } catch (IOException | SecurityException e) {
            // An I/O failure can disappear without a change to the file's contents.
            return new ServerList(null, SavedServerList.EMPTY, ListError.UNREADABLE);
        }
    }

    /// File errors displayed separately from the server's own status.
    private enum ListError {
        /// The list exceeded a parser or display resource budget.
        TOO_LARGE("bf656.server_status.list_too_large"),
        /// The list could not be read after allowing time for an in-progress save.
        UNREADABLE("bf656.server_status.list_unreadable");

        /// Localized status message for this failure.
        private final String messageKey;

        /// Associates a file error with a localized message.
        ListError(String messageKey) {
            this.messageKey = messageKey;
        }
    }

    /// File attributes used to detect both in-place writes and replacements.
    private record FileVersion(FileTime modified, FileTime created, long size, @Nullable Object key) {
    }

    /// Result of loading a file version, including an empty list when the file is absent.
    private record ServerList(@Nullable FileVersion version, Contents contents, @Nullable ListError error) {
    }
}
