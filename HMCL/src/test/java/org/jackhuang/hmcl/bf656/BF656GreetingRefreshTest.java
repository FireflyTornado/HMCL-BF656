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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises batched delivery, hidden-window deferral and the real time selector without a UI toolkit.
@NotNullByDefault
public final class BF656GreetingRefreshTest {
    /// A hidden account may change repeatedly before the first home display without consuming selections.
    @Test
    public void firstDisplaySelectsOnceAfterTheCurrentBatch() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        var refresh = new BF656GreetingRefresh(queue::add, calls::incrementAndGet);
        refresh.requestRefresh();
        refresh.requestRefresh();
        assertTrue(queue.isEmpty());
        refresh.setVisible(true);
        refresh.setVisible(true);
        refresh.requestRefresh();
        assertEquals(1, queue.size());
        assertEquals(0, calls.get());
        queue.remove().run();
        assertEquals(1, calls.get());
        refresh.setVisible(true);
        assertTrue(queue.isEmpty());
    }

    /// A return and an immediate hide/show in one event batch produce one visible greeting, not two.
    @Test
    public void combinedWindowAndNavigationEventsAreCoalesced() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        var refresh = new BF656GreetingRefresh(queue::add, calls::incrementAndGet);
        refresh.setVisible(true);
        queue.remove().run();
        refresh.setVisible(false);
        refresh.setVisible(true);
        refresh.setVisible(false);
        refresh.setVisible(true);
        assertEquals(1, queue.size());
        queue.remove().run();
        assertEquals(2, calls.get());
    }

    /// A queued return that is immediately covered by another page must not choose a hidden greeting.
    @Test
    public void leavingBeforeDeliveryDefersThePendingRefresh() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        var refresh = new BF656GreetingRefresh(queue::add, calls::incrementAndGet);
        refresh.setVisible(true);
        refresh.setVisible(false);
        queue.remove().run();
        assertEquals(0, calls.get());
        refresh.requestRefresh();
        assertTrue(queue.isEmpty());
        refresh.setVisible(true);
        queue.remove().run();
        assertEquals(1, calls.get());
    }

    /// Each completed leave/return remains a new visit rather than being permanently deduplicated.
    @Test
    public void separateVisibleVisitsEachSelectOnce() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        var refresh = new BF656GreetingRefresh(queue::add, calls::incrementAndGet);
        for (int visit = 0; visit < 4; visit++) {
            refresh.setVisible(true);
            queue.remove().run();
            assertEquals(visit + 1, calls.get());
            refresh.setVisible(false);
        }
    }

    /// Time is read on actual delivery: a 22:58 visit changes to late night only after the next visible return.
    @Test
    public void crossPeriodReturnUsesDeliveryTimeRatherThanTheOldVisit() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T14:58:00Z"));
        AtomicReference<BF656Greeting.Kind> selected = new AtomicReference<>(BF656Greeting.Kind.WELCOME);
        var refresh = new BF656GreetingRefresh(queue::add, () -> selected.set(BF656Greeting.generate(
                "Steve", Clock.fixed(now.get(), ZoneId.of("Asia/Hong_Kong")), () -> 0).kind()));
        refresh.setVisible(true);
        queue.remove().run();
        assertEquals(BF656Greeting.Kind.EVENING, selected.get());
        now.set(Instant.parse("2026-11-03T15:02:00Z"));
        assertTrue(queue.isEmpty());
        assertEquals(BF656Greeting.Kind.EVENING, selected.get());
        refresh.setVisible(false);
        refresh.setVisible(true);
        queue.remove().run();
        assertEquals(BF656Greeting.Kind.LATE_NIGHT, selected.get());
    }

    /// A hidden window reopening onto settings does not select; the subsequent home entry uses its current time.
    @Test
    public void hiddenRequestsReadTheLatestStateWhenHomeActuallyAppears() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicReference<String> nickname = new AtomicReference<>("Steve");
        AtomicReference<String> rendered = new AtomicReference<>("");
        var refresh = new BF656GreetingRefresh(queue::add, () -> rendered.set(nickname.get()));
        refresh.requestRefresh();
        nickname.set("Alex");
        refresh.requestRefresh();
        assertTrue(queue.isEmpty());
        refresh.setVisible(true);
        nickname.set("LatestName");
        queue.remove().run();
        assertEquals("LatestName", rendered.get());
    }
}
