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
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.game.GcStrategy;
import org.jackhuang.hmcl.game.LaunchOptions;
import org.jackhuang.hmcl.java.JavaInfo;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Checks Shenandoah version boundaries, production modes, explicit failures and user argument priority.
@NotNullByDefault
public final class ShenandoahStrategyTest {
    /// Java 25+ prefers production generational mode; earlier versions never opt into its experimental form.
    @ParameterizedTest
    @CsvSource({"15,false", "17,false", "21,false", "24,false", "25,true", "26,true", "27,true"})
    public void selectsProductionModeForVersion(int version, boolean generational) {
        var result = GcStrategySupport.resolve(GcStrategy.SHENANDOAH, version, true, args -> {
            assertTrue(args.contains("-XX:+UseShenandoahGC"));
            assertFalse(args.contains("-XX:+UnlockExperimentalVMOptions"));
            return true;
        });
        assertEquals(GcStrategy.SHENANDOAH, result.strategy());
        assertEquals(generational, result.generational());
        assertEquals(generational ? List.of("-XX:+UseShenandoahGC", "-XX:ShenandoahGCMode=generational")
                : List.of("-XX:+UseShenandoahGC"), result.arguments());
    }

    /// Vendor builds may include regular Shenandoah without its newer generational mode.
    @Test
    public void fallsBackOnlyWithinShenandoah() {
        var result = GcStrategySupport.resolve(GcStrategy.SHENANDOAH, 25, true,
                args -> !args.contains("-XX:ShenandoahGCMode=generational"));
        assertEquals(GcStrategy.SHENANDOAH, result.strategy());
        assertFalse(result.generational());
        assertEquals(List.of("-XX:+UseShenandoahGC"), result.arguments());
    }

    /// Unavailable Shenandoah never probes or substitutes G1, ZGC, or an experimental Java 24 mode.
    @ParameterizedTest
    @CsvSource({"17,1", "24,1", "25,2", "27,2"})
    public void unsupportedPreferenceNeverSelectsAnotherCollector(int version, int attempts) {
        var count = new AtomicInteger();
        var result = GcStrategySupport.resolve(GcStrategy.SHENANDOAH, version, true, args -> {
            count.incrementAndGet();
            assertTrue(args.contains("-XX:+UseShenandoahGC"));
            assertFalse(args.contains("-XX:+UseG1GC"));
            assertFalse(args.contains("-XX:+UseZGC"));
            return false;
        });
        assertEquals(attempts, count.get());
        assertEquals(GcStrategy.DEFAULT, result.strategy());
        assertTrue(result.arguments().isEmpty());
    }

    /// Unsupported versions and 32-bit runtimes do not receive production-only Shenandoah options.
    @ParameterizedTest
    @CsvSource({"8,true", "14,true", "21,false", "25,false"})
    public void rejectsUnsupportedVersionOrBitnessBeforeProbing(int version, boolean is64bit) {
        var result = GcStrategySupport.resolve(GcStrategy.SHENANDOAH, version, is64bit, args -> {
            fail("Unsupported runtime should not be probed");
            return true;
        });
        assertEquals(GcStrategy.DEFAULT, result.strategy());
        assertTrue(result.arguments().isEmpty());
    }

    /// Failed resolution stops launch with the selected collector retained in the error and no heap changes.
    @Test
    public void unsupportedRuntimeStopsLaunchAndNamesShenandoah() {
        JavaRuntime current = Objects.requireNonNull(JavaRuntime.CURRENT_JAVA);
        var oldJava = new JavaRuntime(current.getBinary(),
                new JavaInfo(current.getPlatform(), "1.8.0_442", current.getVendor()), false, true);
        var options = new LaunchOptions.Builder().setJava(oldJava).setGcStrategy(GcStrategy.SHENANDOAH).create();
        var command = new CommandBuilder().add("-Xmx6144m");
        var failure = assertThrows(GcStrategyException.class, () -> GcStrategySupport.apply(command, options));
        assertEquals(GcStrategyException.Reason.UNSUPPORTED, failure.getReason());
        assertEquals(GcStrategy.SHENANDOAH, failure.getGcStrategy());
        assertEquals(oldJava.getBinary(), failure.getExecutable());
        assertEquals(List.of("-Xmx6144m"), command.asList());
    }

    /// Manual collector selection and Shenandoah tuning take precedence over the dropdown preference.
    @ParameterizedTest
    @ValueSource(strings = {"-XX:+UseG1GC", "-XX:+UseShenandoahGC", "-XX:ShenandoahGCMode=generational",
            "-XX:ShenandoahGCHeuristics=adaptive", "-XX:ShenandoahAllocationThreshold=10"})
    public void customArgumentsKeepPriority(String argument) throws IOException {
        var command = new CommandBuilder().add(argument);
        var options = new LaunchOptions.Builder().setGcStrategy(GcStrategy.SHENANDOAH).create();
        assertEquals(GcStrategy.DEFAULT, GcStrategySupport.apply(command, options));
        assertEquals(List.of(argument), command.asList());
    }

    /// Disabling generated arguments or optimizations prevents both probing and generated collector arguments.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void disabledGenerationLeavesStrategyInactive(boolean allArguments) throws IOException {
        var options = new LaunchOptions.Builder().setGcStrategy(GcStrategy.SHENANDOAH)
                .setNoGeneratedJVMArgs(allArguments).setNoGeneratedOptimizingJVMArgs(!allArguments).create();
        var command = new CommandBuilder().add("-Xmx6144m");
        assertEquals(GcStrategy.DEFAULT, GcStrategySupport.apply(command, options));
        assertEquals(List.of("-Xmx6144m"), command.asList());
    }
}
