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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Consumer;

/// Coalesces greeting refresh requests until the home page is visible, without a timer or a JavaFX dependency.
/// All methods and the supplied scheduler's callbacks must run on the same owning UI thread.
@NotNullByDefault
public final class BF656GreetingRefresh {
    /// Schedules one callback after the current batch of UI events.
    private final Consumer<Runnable> schedule;
    /// Selects a greeting using the current nickname and time when a request is actually delivered.
    private final Runnable refresh;
    /// Whether home is the current page in a showing main window; minimization does not change this state.
    private boolean visible;
    /// Requests made while hidden remain pending until the next visible entry.
    private boolean pending = true;
    /// Whether the current batch already has a queued delivery.
    private boolean queued;

    /// Creates a coordinator; construction and hidden requests never choose a greeting.
    public BF656GreetingRefresh(Consumer<Runnable> schedule, Runnable refresh) {
        this.schedule = schedule;
        this.refresh = refresh;
    }

    /// Requests one new selection whenever home becomes visible; duplicate notifications do nothing.
    public void setVisible(boolean visible) {
        if (this.visible == visible)
            return;
        this.visible = visible;
        if (visible)
            requestRefresh();
    }

    /// Marks the latest nickname/time as needing selection, merging simultaneous window and navigation events.
    public void requestRefresh() {
        pending = true;
        if (visible && !queued) {
            queued = true;
            schedule.accept(this::flush);
        }
    }

    /// Delivers once if home is still visible, otherwise retaining the request for a later return.
    private void flush() {
        queued = false;
        if (visible && pending) {
            pending = false;
            refresh.run();
        }
    }
}
