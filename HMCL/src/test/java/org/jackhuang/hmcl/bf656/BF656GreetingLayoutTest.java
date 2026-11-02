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

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies single-line priority, semantic wrapping and nickname-only ellipses independently of a UI toolkit.
@NotNullByDefault
public final class BF656GreetingLayoutTest {
    /// Uses one unit per extended grapheme for deterministic layout contract checks.
    private static double width(String value) {
        return Pattern.compile("\\X").matcher(value).results().count();
    }

    /// Exact fits remain on a single line rather than using the template's optional break.
    @Test
    public void prefersTheCompleteSingleLineIncludingAnExactFit() {
        var text = new BF656Greeting.Text("早安，\n", "Steve", "。");
        assertEquals("早安，Steve。", fit(text, 9));
        assertEquals("早安，Steve。", fit(text, 100));
    }

    /// The full name is moved to its own line before any nickname characters are removed.
    @Test
    public void wrapsAtTheTemplatePunctuationBeforeShorteningTheNickname() {
        var text = new BF656Greeting.Text("晚上好，\n", "Steve", "。");
        assertEquals("晚上好，\nSteve。", fit(text, 9));
    }

    /// A long name is ellipsized while the original period remains visible.
    @Test
    public void shortensOnlyTheNicknameOnTheSecondLine() {
        var text = new BF656Greeting.Text("晚上好，\n", "ABCDEFGHIJ", "。");
        assertEquals("晚上好，\nABCDEF…。", fit(text, 8));
    }

    /// Nickname-first deep-night copy breaks after the comma and retains its complete question.
    @Test
    public void shortensOnlyTheNicknameOnTheFirstLine() {
        var text = new BF656Greeting.Text("", "ABCDEFGHIJ", "，\n熬夜玩吗？");
        assertEquals("ABCDEF…，\n熬夜玩吗？", fit(text, 8));
    }

    /// Nickname punctuation is literal content and cannot introduce an extra line break.
    @Test
    public void nicknamePunctuationDoesNotReplaceTheSemanticBreak() {
        var text = new BF656Greeting.Text("午安，\n", "A，BC，DEFGHIJ", "。");
        assertEquals("午安，\nA，BC，D…。", fit(text, 8));
    }

    /// Combining accents and joined emoji sequences are retained or removed as whole graphemes.
    @Test
    public void ellipsisDoesNotSplitUnicodeGraphemes() {
        String prefix = "👨‍👩‍👧‍👦e\u0301🙂";
        var text = new BF656Greeting.Text("早安，\n", prefix + "ABCDE", "。");
        assertEquals("早安，\n" + prefix + "…。", fit(text, 5));
    }

    /// The fixed welcome is returned intact because there is no nickname to truncate.
    @Test
    public void welcomeCopyIsNeverEllipsized() {
        var text = new BF656Greeting.Text("欢迎游玩Battlefield 656！", "", "");
        assertEquals("欢迎游玩Battlefield 656！", fit(text, 8));
    }

    /// Narrow welcome text uses its phrase/brand boundary without losing or shortening the brand name.
    @Test
    public void welcomeUsesItsLocalizedBreakWhenNeeded() {
        var text = new BF656Greeting.Text("欢迎游玩\nBattlefield 656！", "", "");
        assertEquals("欢迎游玩\nBattlefield 656！", fit(text, 16));
        assertEquals("欢迎游玩Battlefield 656！", fit(text, 30));
    }

    /// Applies the real layout algorithm with a predictable test width measurement.
    private static String fit(BF656Greeting.Text text, double width) {
        return BF656GreetingLayout.fit(text, width, BF656GreetingLayoutTest::width);
    }
}
