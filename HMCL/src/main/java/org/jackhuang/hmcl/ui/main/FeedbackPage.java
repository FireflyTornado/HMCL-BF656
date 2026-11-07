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

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.bf656.BF656Metadata;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.WeakListenerHolder;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Displays the BF656 community and issue tracker without upstream contact fallbacks.
public class FeedbackPage extends SpinnerPane {

    /// Retains the theme listener used to switch the GitHub icon.
    private final WeakListenerHolder holder = new WeakListenerHolder();

    /// Creates the project contacts, using a copy action when only a QQ group number is supplied.
    public FeedbackPage() {
        VBox content = new VBox();
        content.getStyleClass().add("spinner-pane-content");
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);
        setContent(scrollPane);

        ComponentList groups = new ComponentList();
        {
            boolean available = !BF656Metadata.QQ_GROUP_URL.isEmpty();
            var users = available ? LineButton.createExternalLinkButton(BF656Metadata.QQ_GROUP_URL) : new LineButton();
            var groupImage = new ImageContainer(32, 32);
            groupImage.setImage(FXUtils.newBuiltinImage("/assets/img/bf656-group.jpg"));
            groupImage.setMouseTransparent(true);
            users.setLargeTitle(true);
            users.setLeading(groupImage);
            users.setTitle(i18n("contact.chat.qq_group"));
            users.setSubtitle(i18n(available ? "contact.chat.qq_group.statement" : "bf656.contact.unavailable"));
            users.setDisable(!available);
            if (!available && !BF656Metadata.QQ_GROUP_NUMBER.isEmpty()) {
                users.setDisable(false);
                users.setSubtitle(i18n("bf656.contact.qq_group.copy"));
                users.setTrailingIcon(SVG.CONTENT_COPY);
                users.setOnAction(event -> FXUtils.copyText(BF656Metadata.QQ_GROUP_NUMBER));
            }

            groups.getContent().setAll(users);
        }

        ComponentList feedback = new ComponentList();
        {
            boolean available = !BF656Metadata.FEEDBACK_URL.isEmpty();
            var github = available ? LineButton.createExternalLinkButton(BF656Metadata.FEEDBACK_URL) : new LineButton();
            github.setLargeTitle(true);
            github.setTitle(i18n("contact.feedback.github"));
            github.setSubtitle(i18n(available ? "contact.feedback.github.statement" : "bf656.contact.unavailable"));
            github.setDisable(!available);

            holder.add(FXUtils.onWeakChangeAndOperate(Themes.darkModeProperty(), darkMode -> {
                github.setLeading(darkMode
                        ? FXUtils.newBuiltinImage("/assets/img/github-white.png")
                        : FXUtils.newBuiltinImage("/assets/img/github.png"));
            }));

            feedback.getContent().setAll(github);
        }

        content.getChildren().addAll(
                ComponentList.createComponentListTitle(i18n("contact.chat")),
                groups,
                ComponentList.createComponentListTitle(i18n("contact.feedback")),
                feedback
        );
    }
}
