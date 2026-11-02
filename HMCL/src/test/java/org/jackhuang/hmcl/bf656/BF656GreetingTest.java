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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jackhuang.hmcl.bf656.BF656Greeting.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies the agreed copy, local time boundaries, deterministic probabilities and layout fragments without JavaFX.
@NotNullByDefault
public final class BF656GreetingTest {
    /// Simplified Chinese copy approved for the BF656 home sidebar.
    private static final ResourceBundle CHINESE = messages(Locale.forLanguageTag("zh-Hans"));

    /// Missing names always use the exact generic welcome, even during late night, without drawing randomness.
    @Test
    public void missingNicknameOverridesTimeAndRandomness() {
        assertWelcome(null);
        assertWelcome("");
        assertWelcome(" \t\r\n\u3000 ");
        assertEquals("欢迎游玩Battlefield 656！", BF656Greeting.generate(null).text(CHINESE).singleLine());
    }

    /// Checks one missing-name input at both daytime and late-night times.
    private static void assertWelcome(@Nullable String nickname) {
        for (int hour : List.of(0, 4, 5, 12, 18, 23)) {
            var greeting = BF656Greeting.generate(nickname, at("2026-11-03T00:00:00", hour), () -> {
                fail("Welcome must not request a random sample");
                return 0;
            });
            assertEquals(WELCOME, greeting.kind());
            assertNull(greeting.nickname());
            var text = greeting.text(CHINESE);
            assertEquals("欢迎游玩Battlefield 656！", text.singleLine());
            assertEquals("欢迎游玩\nBattlefield 656！", text.twoLines());
            assertEquals("", text.nickname());
        }
    }

    /// Tests each inclusive boundary and the last nanosecond of the preceding period.
    @Test
    public void timePeriodsUseExactHalfOpenBoundaries() {
        assertKind("2026-11-03T00:00:00", LATE_NIGHT);
        assertKind("2026-11-03T04:59:59.999999999", LATE_NIGHT);
        assertKind("2026-11-03T05:00:00", MORNING);
        assertKind("2026-11-03T11:59:59.999999999", MORNING);
        assertKind("2026-11-03T12:00:00", AFTERNOON);
        assertKind("2026-11-03T17:59:59.999999999", AFTERNOON);
        assertKind("2026-11-03T18:00:00", EVENING);
        assertKind("2026-11-03T22:59:59.999999999", EVENING);
        assertKind("2026-11-03T23:00:00", LATE_NIGHT);
        assertKind("2026-11-03T23:59:59.999999999", LATE_NIGHT);
    }

    /// Compares a boundary's actual greeting with its agreed period using the time-greeting branch.
    private static void assertKind(String localTime, BF656Greeting.Kind expected) {
        assertEquals(expected, BF656Greeting.generate("Steve", at(localTime), () -> 0).kind(), localTime);
    }

    /// Named late-night greetings never consult the probability source.
    @Test
    public void lateNightAlwaysUsesNicknameFirstCopy() {
        for (int hour : List.of(0, 4, 23)) {
            var greeting = BF656Greeting.generate("Steve", at("2026-11-03T00:00:00", hour), () -> {
                fail("Late night must not request a random sample");
                return 0;
            });
            assertEquals(LATE_NIGHT, greeting.kind());
            var text = greeting.text(CHINESE);
            assertEquals("Steve，熬夜玩吗？", text.singleLine());
            assertEquals("Steve，\n熬夜玩吗？", text.twoLines());
            assertEquals("", text.beforeNickname());
            assertEquals("，\n熬夜玩吗？", text.afterNickname());
        }
    }

    /// Exactly 60 of 100 evenly spaced samples choose the period, including the lower endpoint.
    @Test
    public void probabilitySplitIsSixtyForty() {
        Clock clock = at("2026-11-03T09:00:00");
        int timeCount = 0;
        int weekdayCount = 0;
        for (int index = 0; index < 100; index++) {
            double sample = index / 100.0;
            var kind = BF656Greeting.generate("Steve", clock, () -> sample).kind();
            if (kind == MORNING)
                timeCount++;
            else if (kind == TUESDAY)
                weekdayCount++;
            else
                fail("Unexpected daytime greeting: " + kind);
        }
        assertEquals(60, timeCount);
        assertEquals(40, weekdayCount);
        assertEquals(MORNING, BF656Greeting.generate("Steve", clock, () -> Math.nextDown(0.6)).kind());
        assertEquals(TUESDAY, BF656Greeting.generate("Steve", clock, () -> 0.6).kind());
        assertEquals(TUESDAY, BF656Greeting.generate("Steve", clock, () -> Math.nextDown(1.0)).kind());
    }

    /// An out-of-contract sample is rejected instead of biasing the agreed probability split.
    @Test
    public void invalidRandomSamplesAreRejected() {
        for (double sample : List.of(-0.01, 1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class,
                    () -> BF656Greeting.generate("Steve", at("2026-11-03T09:00:00"), () -> sample));
        }
    }

    /// All seven weekday alternatives use the correct natural calendar date and Chinese copy.
    @Test
    public void weekdayAlternativesCoverTheWholeWeek() {
        @Unmodifiable List<BF656Greeting.Kind> weekdays = List.of(
                MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY);
        @Unmodifiable List<String> names = List.of("一", "二", "三", "四", "五", "六", "日");
        LocalDateTime monday = LocalDateTime.parse("2026-11-02T10:00:00");
        for (int index = 0; index < weekdays.size(); index++) {
            var greeting = BF656Greeting.generate("Steve", at(monday.plusDays(index).toString()), () -> 0.6);
            assertEquals(weekdays.get(index), greeting.kind());
            assertEquals("快乐星期" + names.get(index) + "，Steve！", greeting.text(CHINESE).singleLine());
        }
        assertEquals(MONDAY, BF656Greeting.generate("Steve", at("2026-11-09T05:00:00"), () -> 0.6).kind());
    }

    /// The clock's local zone controls both the period and weekday even when UTC has a different date.
    @Test
    public void clockZoneDeterminesTheLocalDateAndTime() {
        Instant instant = Instant.parse("2026-11-08T21:00:00Z");
        Clock utc = Clock.fixed(instant, ZoneOffset.UTC);
        Clock hongKong = Clock.fixed(instant, ZoneId.of("Asia/Hong_Kong"));
        assertEquals(SUNDAY, BF656Greeting.generate("Steve", utc, () -> 0.6).kind());
        assertEquals(MONDAY, BF656Greeting.generate("Steve", hongKong, () -> 0.6).kind());
        assertEquals(EVENING, BF656Greeting.generate("Steve", utc, () -> 0).kind());
        assertEquals(MORNING, BF656Greeting.generate("Steve", hongKong, () -> 0).kind());
    }

    /// Fixed daytime copy and punctuation-based break positions match the agreed wording.
    @Test
    public void daytimeCopyKeepsNicknameSeparateFromPunctuation() {
        @Unmodifiable List<BF656Greeting.Kind> periods = List.of(MORNING, AFTERNOON, EVENING);
        @Unmodifiable List<String> prefixes = List.of("早安，", "午安，", "晚上好，");
        for (int index = 0; index < periods.size(); index++) {
            var text = new BF656Greeting.Greeting(periods.get(index), "Steve").text(CHINESE);
            assertEquals(prefixes.get(index) + "Steve。", text.singleLine());
            assertEquals(prefixes.get(index) + "\nSteve。", text.twoLines());
            assertEquals(prefixes.get(index) + "\n", text.beforeNickname());
            assertEquals("Steve", text.nickname());
            assertEquals("。", text.afterNickname());
        }
    }

    /// Rendering does not redraw a greeting, and long names remain intact for later width-aware truncation.
    @Test
    public void renderingRetainsTheSelectionAndLiteralNickname() {
        AtomicInteger calls = new AtomicInteger();
        String name = "很长的玩家名_%s，🙂_".repeat(12);
        var greeting = BF656Greeting.generate("  " + name + "  ", at("2026-11-03T18:00:00"), () -> {
            calls.incrementAndGet();
            return 0.6;
        });
        assertEquals(TUESDAY, greeting.kind());
        assertEquals(name, greeting.nickname());
        assertEquals("快乐星期二，" + name + "！", greeting.text(CHINESE).singleLine());
        assertEquals("快乐星期二，\n" + name + "！", greeting.text(CHINESE).twoLines());
        assertEquals(name, greeting.text(CHINESE).nickname());
        assertEquals(1, calls.get());
    }

    /// English fallback and both Chinese bundles define one optional break and exactly one nickname marker.
    @Test
    public void localizedTemplatesRetainTheSameLayoutContract() {
        for (Locale locale : List.of(Locale.ROOT, Locale.forLanguageTag("zh-Hans"), Locale.forLanguageTag("zh-Hant"))) {
            ResourceBundle bundle = messages(locale);
            for (BF656Greeting.Kind kind : BF656Greeting.Kind.values()) {
                var greeting = new BF656Greeting.Greeting(kind, kind == WELCOME ? null : "Steve");
                var text = greeting.text(bundle);
                assertFalse(text.singleLine().contains("\n"));
                assertFalse(text.singleLine().contains("%s"));
                assertEquals(1, text.twoLines().chars().filter(c -> c == '\n').count());
                if (kind != WELCOME)
                    assertEquals("Steve", text.nickname());
            }
        }
    }

    /// Loads real resource files without initializing launcher settings or the JavaFX toolkit.
    private static ResourceBundle messages(Locale locale) {
        return ResourceBundle.getBundle("assets.lang.I18N", locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /// Creates a fixed local date and time in Hong Kong, independent of the test machine's zone.
    private static Clock at(String localTime) {
        ZoneId zone = ZoneId.of("Asia/Hong_Kong");
        return Clock.fixed(LocalDateTime.parse(localTime).atZone(zone).toInstant(), zone);
    }

    /// Creates a fixed time after replacing the hour of the specified local date.
    private static Clock at(String localTime, int hour) {
        return at(LocalDateTime.parse(localTime).withHour(hour).toString());
    }
}
