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

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;

/// Persisted garbage collector preferences; recommendation ranges in the UI are not compatibility limits.
@NotNullByDefault
public enum GcStrategy {
    /// Preserves HMCL's existing G1 collector and additional tuning parameters.
    TUNED_G1,
    /// Selects G1 without additional collector tuning.
    G1,
    /// Prefers generational ZGC, then non-generational ZGC; unsupported runtimes stop the launch.
    /// The former generational-only preference is read as the unified strategy when loading older settings.
    @SerializedName(value = "ZGC", alternate = {"GENERATIONAL_ZGC"})
    ZGC,
    /// Prefers production generational Shenandoah, then regular Shenandoah; no other collector is substituted.
    SHENANDOAH,
    /// Leaves collector selection and tuning to Java or user-supplied arguments.
    DEFAULT
}
