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
package org.jackhuang.hmcl.upgrade;

import org.jackhuang.hmcl.bf656.BF656Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/// Ensures update installation uses BF656 filenames while retaining fixed names and legacy compatibility.
@NotNullByDefault
public final class UpdateHandlerTest {
    /// A versioned BF656 EXE is renamed to the BF656 target release.
    @Test
    public void renamesVersionedBf656Launcher() {
        Path current = Path.of(BF656Metadata.NAME + "-0.3.11.exe");
        assertEquals(Optional.of(Path.of(BF656Metadata.NAME + "-0.3.12.exe")),
                UpdateHandler.tryRename(current, "0.3.12"));
    }

    /// Fixed launcher names and already-current names remain stable.
    @Test
    public void preservesFixedAndAlreadyCurrentFilenames() {
        assertTrue(UpdateHandler.tryRename(Path.of(BF656Metadata.NAME + ".exe"), "0.3.12").isEmpty());
        assertTrue(UpdateHandler.tryRename(Path.of("我的启动器.exe"), "0.3.12").isEmpty());
        assertTrue(UpdateHandler.tryRename(Path.of(BF656Metadata.NAME + "-0.3.12.jar"), "0.3.12").isEmpty());
    }

    /// Existing HMCL versioned filenames continue to work when carrying a BF656 distribution.
    @Test
    public void retainsLegacyHmclFilenameSupport() {
        assertEquals(Optional.of(Path.of("hmcl-0.3.12.jar")),
                UpdateHandler.tryRename(Path.of("hmcl-0.3.11.jar"), "0.3.12"));
    }
}
