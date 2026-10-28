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
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Starts remote-gallery work only while that background option is selected and memoizes it across navigation.
@NotNullByDefault
public final class RemoteGalleryBackgrounds {
    /// API attempts shared throughout this launcher process, including explicit gallery refreshes.
    private static final Map<String, Optional<Image>> API_IMAGES = new ConcurrentHashMap<>();
    /// Process selections indexed by manifest URL and the currently chosen common cache directory.
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    /// User-facing status shown beside the gallery option, without exposing cache implementation details.
    private static final ReadOnlyStringWrapper STATUS = new ReadOnlyStringWrapper(i18n("launcher.background.gallery.status.local"));
    /// Only this selected session may supply an image to the renderer.
    private static volatile @Nullable Session active;

    /// Prevents construction.
    private RemoteGalleryBackgrounds() { }

    /// UI-thread session state; image is published to the asynchronous theme loader through a volatile reference.
    @NotNullByDefault
    private static final class Session {
        /// Full manifest address, separate from the ordinary network-image URL.
        final URI uri;
        /// Repository snapshot used to start synchronization for this selected cache location.
        final CacheRepository repository;
        /// Latest decoded image, retained when switching away and back during this process.
        volatile @Nullable Image image;
        /// Worker currently checking metadata and resources, absent after completion or deselection.
        @Nullable RemoteGallerySync<Image> sync;
        /// A completed check, including an unavailable/empty pool, is not automatically repeated.
        boolean checked;
        /// Cancels stale callbacks after mode changes or an explicit refresh.
        long generation;
        /// Status retained alongside the process's randomly chosen image.
        String status = i18n("launcher.background.gallery.status.loading");

        /// Captures one URL and cache repository location.
        Session(URI uri, CacheRepository repository) {
            this.uri = uri;
            this.repository = repository;
        }

        /// Cancels only pending work; completed selections remain reusable without another request.
        void cancel() {
            generation++;
            if (sync != null) {
                sync.close();
                sync = null;
            }
        }
    }

    /// Returns the inline status without initiating a download.
    public static ReadOnlyStringProperty statusProperty() {
        return STATUS.getReadOnlyProperty();
    }

    /// Selects a gallery on the JavaFX thread, or stops its work when another background type is selected.
    public static void select(@Nullable String address) {
        @Nullable Session next = null;
        boolean invalid = false;
        if (address != null && !address.isBlank()) {
            try {
                URI uri = URI.create(address.trim());
                RemoteContent.validateHttp(uri);
                if (uri.getFragment() != null || uri.toString().length() > 4096)
                    throw new IllegalArgumentException("Invalid gallery URL");
                CacheRepository repository = CacheRepository.getInstance();
                String key = repository.getCacheDirectory().toAbsolutePath().normalize() + "\n" + uri;
                next = SESSIONS.computeIfAbsent(key, ignored -> new Session(uri, repository));
            } catch (RuntimeException e) {
                invalid = true;
            }
        }
        @Nullable Session previous = active;
        if (previous != next && previous != null)
            previous.cancel();
        active = next;
        if (next == null) {
            STATUS.set(i18n(invalid ? "launcher.background.gallery.status.invalid" : "launcher.background.gallery.status.local"));
        } else if (next.checked || next.sync != null) {
            STATUS.set(next.status);
        } else {
            start(next);
        }
    }

    /// Returns only the selected gallery image; an inactive gallery can never override a local background.
    public static @Nullable Image image(@Nullable String address) {
        @Nullable Session session = active;
        return session != null && address != null && session.uri.toString().equals(address.trim()) ? session.image : null;
    }

    /// Explicitly checks the selected manifest again while retaining process-wide API attempt limits.
    public static void refresh() {
        @Nullable Session session = active;
        if (session == null)
            return;
        session.cancel();
        session.checked = false;
        start(session);
    }

    /// Runs one asynchronous check; navigation and appearance changes reuse the same completed result.
    private static void start(Session session) {
        session.status = i18n("launcher.background.gallery.status.loading");
        STATUS.set(session.status);
        long token = ++session.generation;
        session.sync = new RemoteGallerySync<>(session.repository, session.uri, (bytes, name) -> {
            Image image = FXUtils.loadImage(new ByteArrayInputStream(bytes), name);
            if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0)
                throw new IOException("Invalid gallery background image");
            return image;
        }, result -> Platform.runLater(() -> {
            if (active != session || session.generation != token)
                return;
            session.image = result.background();
            session.checked = true;
            session.status = i18n(result.background() == null
                    ? result.error() == null ? "launcher.background.gallery.status.local" : "launcher.background.gallery.status.unavailable"
                    : result.cached() ? "launcher.background.gallery.status.cached" : "launcher.background.gallery.status.ready");
            STATUS.set(session.status);
            if (result.error() != null)
                LOG.warning("Remote gallery: " + result.error());
            Objects.requireNonNull(session.sync).close();
            session.sync = null;
            Themes.refreshBackground();
        }), API_IMAGES);
        session.sync.refresh();
    }

    /// Stops outstanding gallery work without scheduling a new background task during application shutdown.
    public static void shutdown() {
        for (Session session : SESSIONS.values())
            session.cancel();
        active = null;
    }
}
