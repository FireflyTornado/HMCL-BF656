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

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/// Selects local BF656 greetings without creating UI controls or scheduling refreshes.
@NotNullByDefault
public final class BF656Greeting {
    /// Probability of choosing the current time period outside the fixed late-night hours.
    private static final double TIME_GREETING_PROBABILITY = 0.6;

    /// Prevents instantiation of this stateless selector.
    private BF656Greeting() {
    }

    /// Selects once using the device's current local time and an independent random sample.
    /// Callers supply the selected account's profile name, or null when none is available.
    public static Greeting generate(@Nullable String nickname) {
        return generate(nickname, Clock.systemDefaultZone(), ThreadLocalRandom.current()::nextDouble);
    }

    /// Selects once using the clock's zone and a sample in [0, 1); exactly 0.6 selects the weekday.
    /// Missing names take precedence over every time rule. Late night is [23:00, 05:00).
    /// The random source is consumed exactly once for named daytime greetings and never otherwise.
    public static Greeting generate(@Nullable String nickname, Clock clock, DoubleSupplier random) {
        @Nullable String name = normalizeNickname(nickname);
        if (name == null)
            return new Greeting(Kind.WELCOME, null);

        LocalDateTime now = LocalDateTime.now(clock);
        int hour = now.getHour();
        if (hour < 5 || hour >= 23)
            return new Greeting(Kind.LATE_NIGHT, name);

        double sample = random.getAsDouble();
        if (!(sample >= 0 && sample < 1))
            throw new IllegalArgumentException("Greeting random sample must be in [0, 1)");

        Kind kind;
        if (sample < TIME_GREETING_PROBABILITY) {
            kind = hour < 12 ? Kind.MORNING : hour < 18 ? Kind.AFTERNOON : Kind.EVENING;
        } else {
            kind = switch (now.getDayOfWeek()) {
                case MONDAY -> Kind.MONDAY;
                case TUESDAY -> Kind.TUESDAY;
                case WEDNESDAY -> Kind.WEDNESDAY;
                case THURSDAY -> Kind.THURSDAY;
                case FRIDAY -> Kind.FRIDAY;
                case SATURDAY -> Kind.SATURDAY;
                case SUNDAY -> Kind.SUNDAY;
            };
        }
        return new Greeting(kind, name);
    }

    /// Removes surrounding whitespace and represents missing or whitespace-only names as null.
    private static @Nullable String normalizeNickname(@Nullable String nickname) {
        if (nickname == null)
            return null;
        String stripped = nickname.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /// Stable message identities retained between selection and rendering, including nickname changes.
    @NotNullByDefault
    public enum Kind {
        /// Generic welcome shown whenever no usable profile name exists.
        WELCOME("bf656.greeting.welcome"),
        /// Morning greeting, available from 05:00 inclusive to 12:00 exclusive.
        MORNING("bf656.greeting.morning"),
        /// Afternoon greeting, available from 12:00 inclusive to 18:00 exclusive.
        AFTERNOON("bf656.greeting.afternoon"),
        /// Evening greeting, available from 18:00 inclusive to 23:00 exclusive.
        EVENING("bf656.greeting.evening"),
        /// Fixed greeting from 23:00 inclusive to 05:00 exclusive the following day.
        LATE_NIGHT("bf656.greeting.late_night"),
        /// Monday alternative during non-late-night hours.
        MONDAY("bf656.greeting.monday"),
        /// Tuesday alternative during non-late-night hours.
        TUESDAY("bf656.greeting.tuesday"),
        /// Wednesday alternative during non-late-night hours.
        WEDNESDAY("bf656.greeting.wednesday"),
        /// Thursday alternative during non-late-night hours.
        THURSDAY("bf656.greeting.thursday"),
        /// Friday alternative during non-late-night hours.
        FRIDAY("bf656.greeting.friday"),
        /// Saturday alternative during non-late-night hours.
        SATURDAY("bf656.greeting.saturday"),
        /// Sunday alternative during non-late-night hours.
        SUNDAY("bf656.greeting.sunday");

        /// Resource key for the template containing an optional preferred line break and a name marker.
        private final String key;

        /// Associates a stable identity with its localized template.
        Kind(String key) {
            this.key = key;
        }
    }

    /// One selected greeting; neither rendering nor re-rendering performs another random draw.
    ///
    /// @param kind selected message identity
    /// @param nickname normalized profile name, null only for the generic welcome
    @NotNullByDefault
    public record Greeting(Kind kind, @Nullable String nickname) {
        /// Enforces the welcome/name invariant for callers retaining a selected kind.
        public Greeting {
            Objects.requireNonNull(kind);
            nickname = normalizeNickname(nickname);
            if ((kind == Kind.WELCOME) != (nickname == null))
                throw new IllegalArgumentException("Only welcome greetings may omit the profile name");
        }

        /// Renders using the supplied launcher language bundle, preserving nickname boundaries.
        /// Personalized templates use one literal %s marker and one optional newline after punctuation.
        /// The nickname is inserted as plain text, never interpreted as a format string or a template.
        public Text text(ResourceBundle messages) {
            String template = messages.getString(kind.key);
            if (kind == Kind.WELCOME)
                return new Text(template, "", "");

            int marker = template.indexOf("%s");
            if (marker < 0 || template.indexOf("%s", marker + 2) >= 0)
                throw new IllegalArgumentException("Greeting template must contain exactly one %s: " + kind.key);
            return new Text(template.substring(0, marker), Objects.requireNonNull(nickname),
                    template.substring(marker + 2));
        }
    }

    /// Text fragments for width-aware UI layout; only the nickname should be shortened with an ellipsis.
    ///
    /// @param beforeNickname fixed leading copy, possibly including the preferred newline
    /// @param nickname full unshortened profile name, empty for the generic welcome
    /// @param afterNickname fixed trailing copy and punctuation, possibly including the preferred newline
    @NotNullByDefault
    public record Text(String beforeNickname, String nickname, String afterNickname) {
        /// Returns the complete single-line candidate to measure before considering a line break.
        public String singleLine() {
            return beforeNickname.replace("\n", "") + nickname + afterNickname.replace("\n", "");
        }

        /// Returns the localized two-line candidate, using punctuation or the welcome's phrase/brand boundary.
        /// This is an optional layout hint; the UI decides whether wrapping or nickname truncation is needed.
        public String twoLines() {
            return beforeNickname + nickname + afterNickname;
        }
    }
}
