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

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.bf656.BF656Metadata;
import org.jackhuang.hmcl.bf656.BF656UpdateConfig;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.LinkedHashMap;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.Lang.*;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Checks only the configured BF656 source and compares the independent BF656 release version.
@NotNullByDefault
public final class UpdateChecker {
    /// Prevents construction of this shared update service.
    private UpdateChecker() {
    }

    /// Most recent successful response, absent before a check or after an error.
    private static final ObjectProperty<@Nullable RemoteVersion> latestVersion = new SimpleObjectProperty<>();
    /// Whether the latest response offers a different eligible BF656 release.
    private static final BooleanBinding outdated = Bindings.createBooleanBinding(
            () -> isOutdated(BF656Metadata.VERSION, UpdateChannel.getChannel(), latestVersion.get()),
            latestVersion);
    /// Whether a configured source is currently being queried.
    private static final ReadOnlyBooleanWrapper checkingUpdate = new ReadOnlyBooleanWrapper(false);
    /// Failure from the last attempted check; disabled checks never produce an error.
    private static final ReadOnlyObjectWrapper<@Nullable Throwable> error = new ReadOnlyObjectWrapper<>();

    /// Starts the usual startup check; distributions without a source perform no work.
    public static void init() {
        if (isEnabled()) {
            requestCheckUpdate(UpdateChannel.getChannel(), settings().acceptPreviewUpdateProperty().get());
        }
    }

    /// Reports whether a BF656 update source is configured.
    public static boolean isEnabled() {
        return BF656UpdateConfig.CURRENT.isEnabled();
    }

    /// Returns the most recent response, if any.
    public static @Nullable RemoteVersion getLatestVersion() {
        return latestVersion.get();
    }

    /// Exposes update responses to the settings page and update prompt.
    public static ReadOnlyObjectProperty<@Nullable RemoteVersion> latestVersionProperty() {
        return latestVersion;
    }

    /// Returns whether an eligible BF656 release is available.
    public static boolean isOutdated() {
        return outdated.get();
    }

    /// Compares BF656 releases while preserving explicit forced and cross-channel updates.
    static boolean isOutdated(String currentVersion, UpdateChannel currentChannel, @Nullable RemoteVersion latest) {
        if (latest == null || isDevelopmentVersion(currentVersion)) {
            return false;
        } else if (latest.force() || currentChannel == UpdateChannel.NIGHTLY
                || latest.channel() == UpdateChannel.NIGHTLY || latest.channel() != currentChannel) {
            return !latest.version().equals(currentVersion);
        } else {
            return VersionNumber.compare(currentVersion, latest.version()) < 0;
        }
    }

    /// Exposes whether a BF656 update prompt should be shown.
    public static ObservableBooleanValue outdatedProperty() {
        return outdated;
    }

    /// Returns whether a network check is running.
    public static boolean isCheckingUpdate() {
        return checkingUpdate.get();
    }

    /// Exposes checking state to the launcher UI.
    public static ReadOnlyBooleanProperty checkingUpdateProperty() {
        return checkingUpdate.getReadOnlyProperty();
    }

    /// Exposes the last check failure without treating disabled updates as an error.
    public static ReadOnlyObjectProperty<@Nullable Throwable> errorProperty() {
        return error.getReadOnlyProperty();
    }

    /// Verifies this launcher before querying its configured source; signature requirements are retained.
    private static RemoteVersion checkUpdate(UpdateChannel channel, boolean preview) throws IOException {
        if (!IntegrityChecker.DISABLE_SELF_INTEGRITY_CHECK && !IntegrityChecker.isSelfVerified()) {
            throw new SelfVerificationException();
        }

        String url = buildUpdateUrl(Metadata.HMCL_UPDATE_URL, channel, preview);
        return RemoteVersion.fetch(channel, preview, url);
    }

    /// Adds the BF656 release version and chosen channel to an explicit update endpoint.
    static String buildUpdateUrl(String sourceUrl, UpdateChannel channel, boolean preview) {
        if (sourceUrl.isBlank()) {
            throw new IllegalArgumentException("BF656 update source is not configured");
        }
        var query = new LinkedHashMap<String, String>();
        query.put("version", BF656Metadata.VERSION);
        query.put("channel", preview ? channel.channelName + "-preview" : channel.channelName);
        return NetworkUtils.withQuery(sourceUrl, query);
    }

    /// Identifies BF656 development versions that should not offer release upgrades.
    private static boolean isDevelopmentVersion(String version) {
        return version.contains("@") || // eg. @develop@
                version.contains("SNAPSHOT"); // eg. 3.5.SNAPSHOT
    }

    /// Schedules a check on JavaFX; a missing source returns before verification, UI work, or networking.
    public static void requestCheckUpdate(UpdateChannel channel, boolean preview) {
        if (!isEnabled()) {
            return;
        }
        Platform.runLater(() -> {
            if (isCheckingUpdate())
                return;
            checkingUpdate.set(true);

            thread(() -> {
                @Nullable RemoteVersion result = null;
                @Nullable Throwable t = null;
                try {
                    result = checkUpdate(channel, preview);
                    LOG.info("Latest version (" + channel + ", preview=" + preview + ") is " + result);
                } catch (Throwable e) {
                    t = e;
                    LOG.warning("Failed to check for update", e);
                }
                @Nullable Throwable throwable = t;

                @Nullable RemoteVersion finalResult = result;
                Platform.runLater(() -> {
                    if (throwable != null) {
                        latestVersion.set(null);
                        error.set(throwable);
                    } else {
                        error.set(null);
                        latestVersion.set(finalResult);
                    }
                    checkingUpdate.set(false);
                });
            }, "Update Checker", true);
        });
    }
}
