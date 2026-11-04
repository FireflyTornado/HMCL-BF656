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
import org.jetbrains.annotations.Unmodifiable;

import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/// Fits greeting text to a measured width while preserving fixed copy, punctuation and whole nickname graphemes.
@NotNullByDefault
public final class BF656GreetingLayout {
    /// Extended grapheme clusters prevent ellipses from splitting surrogate pairs, combining marks or emoji sequences.
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    /// Single-glyph ellipsis used only when a nickname cannot fit on its designated line.
    private static final String ELLIPSIS = "…";

    /// Prevents construction of this stateless layout helper.
    private BF656GreetingLayout() {
    }

    /// Prefers the complete single line, then the template's punctuation break, then a shortened nickname.
    /// Width measurement is supplied by the UI's actual font. Fixed copy is never shortened; a welcome
    /// without a nickname uses its localized phrase/brand break without shortening any copy.
    public static String fit(BF656Greeting.Text text, double width, ToDoubleFunction<String> measure) {
        String singleLine = text.singleLine();
        if (measure.applyAsDouble(singleLine) <= width)
            return singleLine;
        if (text.nickname().isEmpty())
            return text.twoLines();

        String before = text.beforeNickname();
        String after = text.afterNickname();
        int leadingBreak = before.indexOf('\n');
        if (leadingBreak >= 0) {
            String first = before.substring(0, leadingBreak).stripTrailing();
            String secondPrefix = before.substring(leadingBreak + 1).stripLeading();
            return first + "\n" + fitNickname(secondPrefix, text.nickname(), after, width, measure);
        }
        int trailingBreak = after.indexOf('\n');
        if (trailingBreak >= 0) {
            String firstSuffix = after.substring(0, trailingBreak).stripTrailing();
            String second = after.substring(trailingBreak + 1).stripLeading();
            return fitNickname(before, text.nickname(), firstSuffix, width, measure) + "\n" + second;
        }
        return fitNickname(before, text.nickname(), after, width, measure);
    }

    /// Keeps the longest measured nickname prefix that fits together with the ellipsis and original punctuation.
    /// At widths smaller than the fixed copy itself, the copy is retained for the UI to clip safely.
    private static String fitNickname(String before, String nickname, String after,
                                      double width, ToDoubleFunction<String> measure) {
        String complete = before + nickname + after;
        if (measure.applyAsDouble(complete) <= width)
            return complete;

        int @Unmodifiable [] ends = GRAPHEME.matcher(nickname).results().mapToInt(result -> result.end()).toArray();
        // Shaping can make prefix widths non-monotonic; check proper prefixes from longest to shortest.
        for (int count = ends.length - 1; count > 0; count--) {
            String candidate = before + nickname.substring(0, ends[count - 1]) + ELLIPSIS + after;
            if (measure.applyAsDouble(candidate) <= width)
                return candidate;
        }
        return before + ELLIPSIS + after;
    }
}
