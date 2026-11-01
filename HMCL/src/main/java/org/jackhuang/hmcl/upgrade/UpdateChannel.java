/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.upgrade;

import org.jackhuang.hmcl.bf656.BF656UpdateConfig;
import org.jetbrains.annotations.NotNullByDefault;

/// Release channels for BF656 self-updates, independent of the upstream HMCL build type.
@NotNullByDefault
public enum UpdateChannel {
    /// Tested releases offered by default.
    STABLE("stable"),
    /// Development releases selected explicitly.
    DEVELOPMENT("dev"),
    /// Nightly releases selected explicitly.
    NIGHTLY("nightly");

    /// Protocol name sent to the configured update source.
    public final String channelName;

    /// Associates a release channel with its wire name.
    UpdateChannel(String channelName) {
        this.channelName = channelName;
    }

    /// Returns the channel bundled in the BF656 distribution.
    public static UpdateChannel getChannel() {
        return BF656UpdateConfig.CURRENT.channel();
    }
}
