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
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Regression checks for collector compatibility, fallback, custom arguments and heap preservation.
@NotNullByDefault
public final class GcStrategySupportTest {
    /// Java 21/22 need the explicit generational flag; Java 23+ must not receive the removed flag.
    @ParameterizedTest
    @ValueSource(ints = {21, 22, 23, 24, 25, 26, 27})
    public void selectsVersionAppropriateGenerationalArguments(int version) {
        var result = GcStrategySupport.resolve(GcStrategy.GENERATIONAL_ZGC, version, true, ignored -> true);
        assertEquals(GcStrategy.GENERATIONAL_ZGC, result.strategy());
        assertEquals(version < 23 ? List.of("-XX:+UseZGC", "-XX:+ZGenerational") : List.of("-XX:+UseZGC"),
                result.arguments());
    }

    /// Preference falls back by runtime version/bitness rather than the recommendation text in the UI.
    @ParameterizedTest
    @CsvSource({"21,true,GENERATIONAL_ZGC", "17,true,ZGC", "15,true,ZGC", "14,true,G1",
            "8,true,G1", "7,true,DEFAULT", "21,false,G1"})
    public void fallsBackAcrossVersionsAndBitness(int version, boolean is64bit, GcStrategy expected) {
        assertEquals(expected, GcStrategySupport.resolve(
                GcStrategy.GENERATIONAL_ZGC, version, is64bit, ignored -> true).strategy());
    }

    /// A vendor runtime may support ordinary ZGC but reject the generational flag.
    @Test
    public void probesCapabilitiesInsteadOfAssumingVendorSupport() {
        var result = GcStrategySupport.resolve(GcStrategy.GENERATIONAL_ZGC, 21, true,
                args -> !args.contains("-XX:+ZGenerational"));
        assertEquals(GcStrategy.ZGC, result.strategy());
        assertEquals(List.of("-XX:+UseZGC"), result.arguments());
        assertEquals(GcStrategy.G1, GcStrategySupport.resolve(GcStrategy.GENERATIONAL_ZGC, 21, true,
                args -> !args.contains("-XX:+UseZGC")).strategy());
        assertEquals(GcStrategy.DEFAULT, GcStrategySupport.resolve(
                GcStrategy.GENERATIONAL_ZGC, 21, true, ignored -> false).strategy());
    }

    /// ZGC uses its runtime default; Java 23+ therefore resolves to generational mode.
    @ParameterizedTest
    @CsvSource({"17,ZGC", "21,ZGC", "23,GENERATIONAL_ZGC", "25,GENERATIONAL_ZGC"})
    public void ordinaryPreferenceDoesNotForceRemovedNonGenerationalMode(int version, GcStrategy expected) {
        var result = GcStrategySupport.resolve(GcStrategy.ZGC, version, true, ignored -> true);
        assertEquals(expected, result.strategy());
        assertEquals(List.of("-XX:+UseZGC"), result.arguments());
    }

    /// Recognized user GC selections and tuning suppress the whole generated policy before probing Java.
    @ParameterizedTest
    @ValueSource(strings = {"-XX:+UseG1GC", "-XX:-UseG1GC", "-XX:+UseParallelGC", "-XX:+UseZGC",
            "-XX:+ZGenerational", "-XX:G1HeapRegionSize=8m", "-XX:MaxGCPauseMillis=100",
            "-XX:InitiatingHeapOccupancyPercent=40", "-XX:+UseShenandoahGC", "-Xmn512m"})
    public void customCollectorArgumentsWin(String argument) throws IOException {
        var command = new CommandBuilder().add(argument);
        assertEquals(GcStrategy.DEFAULT, GcStrategySupport.apply(command, new LaunchOptions.Builder().create()));
        assertEquals(List.of(argument), command.asList());
    }

    /// Heap capacity and unrelated VM/JIT parameters do not disable collector selection.
    @ParameterizedTest
    @ValueSource(strings = {"-Xmx6g", "-Xms1g", "-Xss1m", "-XX:MaxRAMPercentage=60",
            "-XX:ReservedCodeCacheSize=400M", "-Dexample=GC", "-XX:+UnlockExperimentalVMOptions"})
    public void unrelatedArgumentsDoNotOverrideStrategy(String argument) {
        assertFalse(GcStrategySupport.isGcArgument(argument));
    }

    /// GC parameters in an external argument file have the same priority as directly supplied options.
    @Test
    public void honorsExternalArgumentFiles(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("JVM options.txt");
        Files.writeString(file, "# custom collector\n-XX:+UseParallelGC\n");
        var command = new CommandBuilder().addAllWithoutParsingAndReadExternal(List.of("@" + file));
        assertEquals(GcStrategy.DEFAULT, GcStrategySupport.apply(command, new LaunchOptions.Builder().create()));
        assertEquals(List.of("@" + file), command.asList());
    }

    /// Disabling generation or choosing unspecified does not invoke a runtime probe.
    @Test
    public void inactivePoliciesDoNotProbeOrGenerateArguments() throws IOException {
        for (LaunchOptions options : List.of(
                new LaunchOptions.Builder().setNoGeneratedJVMArgs(true).create(),
                new LaunchOptions.Builder().setNoGeneratedOptimizingJVMArgs(true).create(),
                new LaunchOptions.Builder().setGcStrategy(GcStrategy.DEFAULT).create())) {
            var command = new CommandBuilder();
            assertEquals(GcStrategy.DEFAULT, GcStrategySupport.apply(command, options));
            assertTrue(command.asList().isEmpty());
        }
        var probes = new AtomicInteger();
        GcStrategySupport.resolve(GcStrategy.DEFAULT, 21, true, ignored -> {
            probes.incrementAndGet();
            return true;
        });
        assertEquals(0, probes.get());
    }

    /// Real target-runtime probing produces the original tuning bundle and preserves the configured heap.
    @ParameterizedTest
    @ValueSource(strings = {"TUNED_G1", "G1"})
    public void producesG1ArgumentsWithoutChangingMemory(GcStrategy strategy) throws IOException {
        JavaRuntime runtime = java.util.Objects.requireNonNull(JavaRuntime.CURRENT_JAVA);
        var command = new CommandBuilder().add("-Xmx6144m").add("-Xms1024m");
        var options = new LaunchOptions.Builder().setJava(runtime).setGcStrategy(strategy).create();
        GcStrategy effective = GcStrategySupport.apply(command, options);
        assertEquals(strategy, effective);
        assertTrue(command.asList().contains("-XX:+UseG1GC"));
        assertEquals(strategy == GcStrategy.TUNED_G1, command.asList().contains("-XX:MaxGCPauseMillis=50"));
        assertEquals(strategy == GcStrategy.TUNED_G1, command.asList().contains("-XX:G1HeapRegionSize=32m"));
        assertEquals(List.of("-Xmx6144m", "-Xms1024m"),
                command.asList().stream().filter(arg -> arg.startsWith("-Xm")).toList());
    }

    /// Capability detection must use the selected executable, never the launcher's own Java as a substitute.
    @Test
    public void probesSelectedExecutable(@TempDir Path directory) throws IOException {
        var options = new LaunchOptions.Builder()
                .setJava(java.util.Objects.requireNonNull(JavaRuntime.CURRENT_JAVA))
                .setJavaExecutable(directory.resolve("missing-java.exe"))
                .setGcStrategy(GcStrategy.G1).create();
        var command = new CommandBuilder();
        assertEquals(GcStrategy.DEFAULT, GcStrategySupport.apply(command, options));
        assertTrue(command.asList().isEmpty());
    }
}
