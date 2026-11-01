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
package org.jackhuang.hmcl.ui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXSpinner;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

import org.glavo.url.WebURL;
import org.jackhuang.hmcl.bf656.BF656Metadata;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.JFXHyperlink;
import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

import static org.jackhuang.hmcl.Metadata.CHANGELOG_URL;
import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Confirms a BF656 update and optionally displays release notes from its own configured page.
@NotNullByDefault
public final class UpgradeDialog extends JFXDialogLayout {

    /// Builds a confirmation dialog without requesting or linking to unconfigured release notes.
    public UpgradeDialog(RemoteVersion remoteVersion, Runnable updateRunnable) {
        maxWidthProperty().bind(Controllers.getDecorator().contentWidthProperty().multiply(0.7));

        Label heading = new Label(i18n("bf656.update.confirm.title", remoteVersion.version()));
        heading.setPadding(new Insets(0, 0, 8, 0));
        setHeading(heading);
        Label summary = new Label(i18n("bf656.update.confirm.summary"));
        summary.setWrapText(true);
        VBox notes = new VBox(8);
        notes.setId("bf656-update-notes");
        notes.setManaged(false);
        notes.setVisible(false);
        setBody(new VBox(12, summary, notes));
        String url = CHANGELOG_URL;
        // A distribution without release notes uses a compact confirmation, with no empty log placeholder.
        setMaxHeight(Region.USE_PREF_SIZE);
        if (!url.isEmpty())
            loadChangelog(remoteVersion, url, notes);

        JFXButton updateButton = new JFXButton(i18n("bf656.update.confirm.accept"));
        updateButton.getStyleClass().add("dialog-accept");
        updateButton.setOnAction(e -> {
            fireEvent(new DialogCloseEvent());
            updateRunnable.run();
        });

        JFXButton cancelButton = new JFXButton(i18n("bf656.update.confirm.later"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));

        if (url.isEmpty()) {
            setActions(updateButton, cancelButton);
        } else {
            JFXHyperlink openInBrowser = new JFXHyperlink(i18n("web.view_in_browser"));
            openInBrowser.setExternalLink(url);
            setActions(openInBrowser, updateButton, cancelButton);
        }
        onEscPressed(this, cancelButton::fire);
    }

    /// Loads optional notes without replacing the restart explanation or exposing network failures in the dialog.
    private void loadChangelog(RemoteVersion remoteVersion, String url, VBox notes) {
        notes.getChildren().setAll(new JFXSpinner());
        notes.setManaged(true);
        notes.setVisible(true);
        Task<@Nullable TextFlow> task = Task.supplyAsync(Schedulers.io(), () -> renderChangelog(remoteVersion, url));
        task.whenComplete(Schedulers.javafx(), (@Nullable TextFlow result, @Nullable Exception exception) -> {
            notes.getChildren().clear();
            if (exception != null)
                LOG.warning("Failed to load BF656 update log", exception);
            if (result == null || exception != null) {
                notes.setManaged(false);
                notes.setVisible(false);
            } else {
                ScrollPane scrollPane = new ScrollPane(result);
                scrollPane.setFitToWidth(true);
                scrollPane.setPrefViewportHeight(180);
                scrollPane.maxHeightProperty().bind(Controllers.getDecorator().contentHeightProperty().multiply(0.35));
                FXUtils.smoothScrolling(scrollPane);
                notes.getChildren().setAll(new Label(i18n("update.changelog")), scrollPane);
            }
        }).start();
    }

    /// Renders versioned HTML notes newer than the current BF656 release; downgrades have no notes.
    private static @Nullable TextFlow renderChangelog(RemoteVersion remoteVersion, String url) throws IOException {
        VersionNumber targetVersion = VersionNumber.asVersion(remoteVersion.version());
        VersionNumber currentVersion = VersionNumber.asVersion(BF656Metadata.VERSION);
        if (targetVersion.compareTo(currentVersion) <= 0)
            // Downgrade update, no need to display changelog
            return null;

        Document document = Jsoup.parse(WebURL.toURL(url), 30 * 1000);
        @Nullable Node node = document.selectFirst("h1[data-version=\"%s\"]".formatted(targetVersion));

        HTMLRenderer renderer = new HTMLRenderer(uri -> {
            LOG.info("Open link: " + uri);
            FXUtils.openLink(uri.toString());
        });

        if (node == null || !"h1".equals(node.nodeName())) {
            LOG.warning("Changelog not found");
            return null;
        }

        do {
            if ("h1".equals(node.nodeName())) {
                String changelogVersion = node.attr("data-version");
                if (StringUtils.isBlank(changelogVersion) || currentVersion.compareTo(changelogVersion) >= 0) {
                    break;
                }
            }
            renderer.appendNode(node);
            node = node.nextSibling();
        } while (node != null);

        renderer.mergeLineBreaks();
        return renderer.render();
    }
}
