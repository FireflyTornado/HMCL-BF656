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
package org.jackhuang.hmcl.game;

import org.jetbrains.annotations.NotNullByDefault;

/// Persisted garbage collector preferences; recommendation ranges in the UI are not compatibility limits.
@NotNullByDefault
public enum GcStrategy {
    /// Preserves HMCL's existing G1 collector and additional tuning parameters.
    TUNED_G1,
    /// Selects G1 without additional collector tuning.
    G1,
    /// Prefers generational ZGC, then runtime-default ZGC, then untuned G1.
    GENERATIONAL_ZGC,
    /// Prefers runtime-default ZGC, then untuned G1.
    ZGC,
    /// Leaves collector selection and tuning to Java or user-supplied arguments.
    DEFAULT
}
