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

import javafx.application.Platform;
import javafx.stage.Stage;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks packaged custom images and their integration into real, unshown JavaFX windows.
@NotNullByDefault
public final class BF656IconsTest {
    /// Ensures custom PNGs and the retained original PNGs remain decodable at their intended sizes.
    @Test
    public void customAndOriginalResourcesRemainAvailable() throws Exception {
        @Unmodifiable Map<String, Integer> sizes = Map.of("icon.png", 32, "icon@2x.png", 64,
                "icon@4x.png", 128, "icon@8x.png", 256, "icon-title.png", 24,
                "icon-title@2x.png", 48, "icon-mac.png", 512);
        for (var entry : sizes.entrySet()) {
            checkImage("/assets/img/" + entry.getKey(), entry.getValue());
            checkImage("/assets/img/bf656-" + entry.getKey(), entry.getValue());
        }
    }

    /// Verifies the shared window entry point loads only the custom brand's resources.
    @Test
    @EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
    public void launcherWindowsUseCustomResources() throws Exception {
        FutureTask<Boolean> check = new FutureTask<>(() -> {
            Stage stage = new Stage();
            try {
                FXUtils.setIcon(stage);
                assertFalse(stage.getIcons().isEmpty());
                for (var image : stage.getIcons()) {
                    assertFalse(image.isError());
                    @Nullable String url = image.getUrl();
                    assertNotNull(url);
                    assertTrue(url.contains("/bf656-icon"), url);
                }
                return true;
            } finally {
                stage.close();
            }
        });
        Platform.runLater(check);
        assertTrue(check.get(15, TimeUnit.SECONDS));
    }

    /// Decodes one resource and validates its dimensions without relying on placeholder pixel content.
    private static void checkImage(String resource, int size) throws Exception {
        @Nullable InputStream input = BF656IconsTest.class.getResourceAsStream(resource);
        assertNotNull(input, resource);
        try (input) {
            @Nullable BufferedImage image = ImageIO.read(input);
            assertNotNull(image, resource);
            assertEquals(size, image.getWidth(), resource);
            assertEquals(size, image.getHeight(), resource);
        }
    }
}
