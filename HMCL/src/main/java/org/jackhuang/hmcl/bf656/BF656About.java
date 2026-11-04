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
package org.jackhuang.hmcl.bf656;

import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jetbrains.annotations.NotNullByDefault;

/// Creates BF656-specific about-page content separately from HMCL's original entries.
@NotNullByDefault
public final class BF656About {
    /// Prevents construction of this about-page utility.
    private BF656About() {
    }

    /// Creates the project section, including the configured author when a name is supplied.
    public static ComponentList createProjectSection() {
        var project = new ComponentList();
        project.getContent().add(createLauncherEntry());
        if (!BF656Metadata.AUTHOR_NAME.isEmpty()) {
            project.getContent().add(createAuthorEntry());
        }
        return project;
    }

    /// Creates the custom launcher entry with its independent version and optional repository link.
    public static LineButton createLauncherEntry() {
        var launcher = BF656Metadata.PROJECT_URL.isEmpty()
                ? new LineButton()
                : LineButton.createExternalLinkButton(BF656Metadata.PROJECT_URL);
        launcher.setLargeTitle(true);
        launcher.setLeading(FXUtils.newBuiltinImage(BF656Icons.ICON));
        launcher.setTitle(BF656Metadata.FULL_NAME);
        launcher.setSubtitle(BF656Metadata.VERSION);
        return launcher;
    }

    /// Creates the project author entry with a bundled avatar and optional personal profile link.
    private static LineButton createAuthorEntry() {
        var author = BF656Metadata.AUTHOR_URL.isEmpty()
                ? new LineButton()
                : LineButton.createExternalLinkButton(BF656Metadata.AUTHOR_URL);
        var avatar = new ImageContainer(32, 32);
        avatar.setImage(FXUtils.newBuiltinImage("/assets/img/bf656-author.png"));
        avatar.setMouseTransparent(true);
        author.setLargeTitle(true);
        author.setLeading(avatar);
        author.setTitle(BF656Metadata.AUTHOR_NAME);
        author.setSubtitle(BF656Metadata.AUTHOR_DESCRIPTION);
        return author;
    }
}
