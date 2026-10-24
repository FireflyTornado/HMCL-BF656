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

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Applies a launcher-wide background independently of the selected game, protocol checks and navigation.
@NotNullByDefault
public final class OverallBackgroundController {
    /// Successful and failed API attempts retained for this launcher process, including explicit refreshes.
    private static final Map<String, Optional<Image>> API_IMAGES = new ConcurrentHashMap<>();
    /// Concise user-facing state for the appearance page.
    private static final ReadOnlyStringWrapper STATUS = new ReadOnlyStringWrapper("使用本地背景");
    /// Current global synchronizer; no instance session owns it.
    private static @Nullable OverallSync<Image> sync;
    /// Startup is idempotent and page construction must not initiate another manifest check.
    private static boolean initialized;
    /// Prevents worker callbacks from a replaced source or a previous shutdown from reaching the scene.
    private static long generation;

    /// Prevents construction.
    private OverallBackgroundController() { }

    /// Global client and server state, alongside other launcher-local data.
    public static Path root() {
        return Metadata.HMCL_LOCAL_HOME.resolve("overall");
    }

    /// Starts one check after the main scene is ready; missing configuration preserves existing behavior.
    public static void initialize() {
        if (!initialized)
            refresh();
    }

    /// Returns status without initializing the synchronizer or making requests.
    public static ReadOnlyStringProperty statusProperty() {
        return STATUS.getReadOnlyProperty();
    }

    /// Explicitly reloads only the global configuration and checks its manifest once.
    public static void refresh() {
        initialized = true;
        long token = ++generation;
        if (sync != null) {
            sync.close();
            sync = null;
        }
        try {
            OverallSync.Config config = OverallSync.Config.load(root());
            if (!config.enabled()) {
                Themes.setOverallBackground(null);
                STATUS.set("使用本地背景");
                return;
            }
            STATUS.set("正在同步全局背景…");
            sync = new OverallSync<>(root(), config, (bytes, name) -> {
                Image image = FXUtils.loadImage(new ByteArrayInputStream(bytes), name);
                if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0)
                    throw new IOException("Invalid overall background image");
                return image;
            }, result -> Platform.runLater(() -> {
                if (generation != token || !initialized)
                    return;
                Themes.setOverallBackground(result.background());
                STATUS.set(result.background() == null ? "使用本地背景"
                        : result.cached() ? "使用已缓存的全局背景" : "使用全局背景");
                if (result.error() != null)
                    LOG.warning("Overall background: " + result.error());
            }), API_IMAGES);
            sync.refresh();
        } catch (IOException | RuntimeException e) {
            Themes.setOverallBackground(null);
            STATUS.set("全局背景暂不可用，使用本地背景");
            LOG.warning("Couldn't configure overall backgrounds", e);
        }
    }

    /// Cancels global work; callbacks cannot replace the background after shutdown.
    public static void shutdown() {
        initialized = false;
        generation++;
        if (sync != null) {
            sync.close();
            sync = null;
        }
    }
}
