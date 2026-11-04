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
package org.jackhuang.hmcl.ui.main;

import javafx.animation.Interpolator;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.jackhuang.hmcl.bf656.BF656Greeting;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.LauncherSettings;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.setting.StyleSheets;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.construct.PageCloseEvent;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/// Checks actual navigation and Stage hide/show events using transparent windows and isolated launcher settings.
@NotNullByDefault
public final class BF656GreetingLifecycleSmoke {
    /// Prevents construction of this standalone verifier.
    private BF656GreetingLifecycleSmoke() {
    }

    /// Runs native lifecycle checks without any visible test window, game process, or external service request.
    public static void main(String @Unmodifiable [] args) {
        try {
            Path state = Files.createDirectories(Path.of(args[0]).toAbsolutePath()).resolve("state-" + System.nanoTime());
            System.setProperty("hmcl.home", state.resolve("user").toString());
            System.setProperty("hmcl.dir", state.resolve("local").toString());
            System.setProperty("hmcl.update_source.override", "");
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            SettingsManager.init();
            Platform.startup(() -> Platform.setImplicitExit(false));
            Fixture fixture = Objects.requireNonNull(onFx(Fixture::new));
            try {
                verifyLifecycle(fixture);
            } finally {
                onFx(() -> {
                    fixture.stage().hide();
                    fixture.decorator.detachStage();
                    return null;
                });
            }
            System.out.println("BF656_GREETING_LIFECYCLE_OK: first show, cross-period return, hide/reopen, covered-page deferral, coalescing, stage replacement, minimization and account changes.");
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /// Verifies the agreed triggers through real navigation and window events, draining one UI batch at a time.
    private static void verifyLifecycle(Fixture fixture) throws Exception {
        require(fixture.selections.get() == 0, "Account binding before first show must not select prematurely");
        onFx(() -> { fixture.stage().show(); return null; });
        drain();
        require(fixture.selections.get() == 1, "First show must select exactly once");
        require(fixture.pane.getAccessibleText().equals("晚上好，Steve。"), "Initial clock must select evening");

        fixture.now.set(Instant.parse("2026-11-03T15:02:00Z"));
        onFx(() -> { fixture.openSettings(); return null; });
        drain();
        require(fixture.selections.get() == 1, "Opening another page must not refresh home");
        onFx(() -> { fixture.settings.fireEvent(new PageCloseEvent()); return null; });
        drain();
        require(fixture.selections.get() == 2, "Returning through Navigator.close must refresh once");
        require(fixture.pane.getAccessibleText().equals("Steve，熬夜玩吗？"), "Cross-period return must use the new time");

        onFx(() -> { fixture.stage().hide(); return null; });
        fixture.now.set(Instant.parse("2026-11-03T21:00:00Z"));
        drain();
        require(fixture.selections.get() == 2, "Hidden home must not refresh");
        onFx(() -> { fixture.stage().show(); return null; });
        drain();
        require(fixture.selections.get() == 3, "Game-style hide/reopen must refresh once");
        require(fixture.pane.getAccessibleText().equals("早安，Steve。"), "Reopen after 05:00 must use morning copy");

        onFx(() -> {
            fixture.openSettings();
            fixture.stage().hide();
            fixture.stage().show();
            return null;
        });
        drain();
        require(fixture.selections.get() == 3, "Reopening onto another page must defer home refresh");
        onFx(() -> {
            fixture.settings.fireEvent(new PageCloseEvent());
            fixture.stage().hide();
            fixture.stage().show();
            return null;
        });
        drain();
        require(fixture.selections.get() == 4, "Combined navigation/window events must select only once");

        onFx(() -> {
            fixture.stage().setIconified(true);
            fixture.stage().setIconified(false);
            fixture.stage().setWidth(fixture.stage().getWidth() + 24);
            fixture.stage().setHeight(fixture.stage().getHeight() + 24);
            fixture.stage().requestFocus();
            SettingsManager.settings().getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
            SettingsManager.settings().themeBrightnessModeProperty().set("dark");
            return null;
        });
        drain();
        require(fixture.selections.get() == 4, "Minimization, focus, resizing and theme changes must retain the greeting");

        onFx(() -> {
            fixture.replaceWindow();
            return null;
        });
        drain();
        require(fixture.selections.get() == 4, "Appearance-only stage replacement must preserve logical visibility");
        onFx(() -> { fixture.stage().hide(); fixture.stage().show(); return null; });
        drain();
        require(fixture.selections.get() == 5, "The replacement stage must still deliver real hide/reopen events");

        onFx(() -> { fixture.stage().hide(); fixture.replaceWindow(); return null; });
        drain();
        require(fixture.selections.get() == 5, "Replacing a hidden stage must keep it hidden and defer refresh");
        onFx(() -> { fixture.stage().show(); return null; });
        drain();
        require(fixture.selections.get() == 6, "Showing a replacement that was hidden must select once");

        onFx(() -> {
            fixture.openSettings();
            fixture.settings.fireEvent(new PageCloseEvent());
            fixture.openSettings();
            return null;
        });
        drain();
        require(fixture.selections.get() == 6, "A return immediately covered by another page must not select");
        onFx(() -> { fixture.settings.fireEvent(new PageCloseEvent()); return null; });
        drain();
        require(fixture.selections.get() == 7, "The eventual visible return must deliver the pending refresh");

        onFx(() -> {
            Accounts.selectedAccountProperty().set(Accounts.FACTORY_OFFLINE.create("Alex", UUID.randomUUID()));
            return null;
        });
        drain();
        require(fixture.selections.get() == 7, "Switching named accounts must keep the selected sentence");
        require(fixture.pane.getAccessibleText().equals("早安，Alex。"), "Selected nickname must update immediately");
        onFx(() -> {
            fixture.openSettings();
            Accounts.selectedAccountProperty().set(null);
            Accounts.selectedAccountProperty().set(Accounts.FACTORY_OFFLINE.create("LatestName", UUID.randomUUID()));
            return null;
        });
        drain();
        require(fixture.selections.get() == 7, "A new account while home is hidden must defer selection");
        onFx(() -> { fixture.settings.fireEvent(new PageCloseEvent()); return null; });
        drain();
        require(fixture.selections.get() == 8, "Account and return requests must coalesce");
        require(fixture.pane.getAccessibleText().equals("早安，LatestName。"), "Deferred selection must use the latest nickname");
    }

    /// Waits behind lifecycle callbacks already queued by the preceding UI action.
    private static void drain() throws Exception {
        onFx(() -> null);
    }

    /// Performs one event batch on the owning UI thread with a bounded wait.
    private static <T> @Nullable T onFx(Callable<@Nullable T> work) throws Exception {
        FutureTask<@Nullable T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    /// Fails independently of JVM assertion flags.
    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    /// Retains one real decorator and clock-controlled home page across all window/page transitions.
    @NotNullByDefault
    private static final class Fixture {
        /// Clock begins at 22:58 Hong Kong time and advances only when a case explicitly changes it.
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T14:58:00Z"));
        /// Counts delivered selections, independently of whether a random choice repeats the same sentence.
        private final AtomicInteger selections = new AtomicInteger();
        /// Greeting control using the production selector with a deterministic time-period branch.
        private final BF656GreetingPane pane = new BF656GreetingPane(name -> {
            selections.incrementAndGet();
            return BF656Greeting.generate(name, Clock.fixed(now.get(), ZoneId.of("Asia/Hong_Kong")), () -> 0);
        }, I18n.getResourceBundle());
        /// Retained home page whose normal skin binds real launcher account selection.
        private final RootPage home;
        /// Secondary page used to exercise the normal navigator return path.
        private final StackPane settings = new StackPane();
        /// Actual window/navigation owner used by production.
        private final Decorator decorator;
        /// Initial window retained for cleanup after the decorator detaches.
        private final Stage initialStage;

        /// Creates the isolated account, page and transparent native window without showing it.
        private Fixture() {
            GameDirectoryManager.init();
            Accounts.selectedAccountProperty().set(Accounts.FACTORY_OFFLINE.create("Steve", UUID.randomUUID()));
            home = new RootPage(pane);
            decorator = new Decorator(home, stage -> stage.setOpacity(0));
            initialStage = new Stage();
            initialStage.setOpacity(0);
            StyleSheets.init(decorator.attachStage(initialStage));
            decorator.getRoot().applyCss();
            decorator.getRoot().layout();
        }

        /// Returns the current attached stage, including replacements created by appearance changes.
        private Stage stage() {
            @Nullable Stage current = decorator.getStage();
            return current != null ? current : initialStage;
        }

        /// Navigates using the production stack with animation disabled to isolate lifecycle ordering.
        private void openSettings() {
            decorator.navigate(settings, ContainerAnimations.NONE, Duration.ZERO, Interpolator.LINEAR);
        }

        /// Runs the exact atomic stage-swap implementation used by decoration changes, even on older JavaFX.
        private void replaceWindow() throws Exception {
            Stage previous = stage();
            var replace = Decorator.class.getDeclaredMethod("replaceStage", Stage.class);
            replace.setAccessible(true);
            replace.invoke(decorator, new Stage());
            require(!Objects.equals(previous, stage()), "The stage-replacement fixture did not replace its window");
            require(stage().getOpacity() == 0, "Replacement test windows must remain transparent");
        }
    }
}
