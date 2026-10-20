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
package org.jackhuang.hmcl.ui.game;

import com.jfoenix.controls.JFXCheckBox;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises actual checkbox and button events; no launch or real user settings are involved.
@NotNullByDefault
@EnabledIf("org.jackhuang.hmcl.JavaFXLauncher#isStarted")
public final class JavaCompatibilityWarningDialogTest {
    /// Remember defaults on, but only a checked Continue may change the current instance's preference.
    @ParameterizedTest
    @CsvSource({"true,true,true", "true,false,true", "false,true,true", "false,false,true", "true,true,false"})
    public void savesOnlyRememberedContinuation(boolean proceed, boolean remember, boolean writable) throws Exception {
        var task = new FutureTask<>(() -> {
            var instance = new GameSettings.Instance();
            var accepted = new AtomicInteger();
            var cancelled = new AtomicInteger();
            var closed = new AtomicInteger();
            var dialog = JavaCompatibilityWarningDialog.create("Java version recommendation", instance, writable,
                    accepted::incrementAndGet, cancelled::incrementAndGet);
            dialog.addEventHandler(DialogCloseEvent.CLOSE, event -> closed.incrementAndGet());
            var boxes = descendants(dialog, JFXCheckBox.class);
            assertEquals(1, boxes.size());
            var checkbox = boxes.get(0);
            assertTrue(checkbox.isSelected());
            assertEquals(!writable, checkbox.isDisabled());
            if (!remember) {
                checkbox.fire();
                assertFalse(checkbox.isSelected());
                assertEquals(0, closed.get(), "Changing Remember must not close the warning");
            }
            assertFalse(instance.ignoreJavaCompatibilityWarningsProperty().getValue());
            if (proceed) {
                descendants(dialog, ButtonBase.class).stream()
                        .filter(button -> button != dialog.getCancelButton() && button != checkbox)
                        .findFirst().orElseThrow().fire();
            } else {
                dialog.getCancelButton().fire();
            }
            assertEquals(proceed ? 1 : 0, accepted.get());
            assertEquals(proceed ? 0 : 1, cancelled.get());
            assertEquals(1, closed.get());
            assertEquals(proceed && remember && writable, instance.ignoreJavaCompatibilityWarningsProperty().getValue());
            assertFalse(instance.notCheckJVMProperty().getValue());
            return true;
        });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }

    /// Finds semantic controls before CSS skins are created, including nested optional body controls.
    private static <T extends Node> List<T> descendants(Node node, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(node)) {
            result.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                result.addAll(descendants(child, type));
            }
        }
        return result;
    }
}
