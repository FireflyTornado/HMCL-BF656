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
import org.jackhuang.hmcl.util.platform.Bits;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves collector preferences against the actual game Java executable before generating arguments.
@NotNullByDefault
public final class GcStrategySupport {
    /// Utility class.
    private GcStrategySupport() {
    }

    /// Recognizes collector selection/tuning arguments, excluding heap capacity and unrelated JIT options.
    /// User GC parameters take precedence over the entire generated GC policy, including tuning.
    public static boolean isGcArgument(String argument) {
        if (argument.equals("-Xincgc") || argument.equals("-Xconcgc") || argument.startsWith("-Xmn"))
            return true;
        if (!argument.startsWith("-XX:"))
            return false;
        String key = argument.substring(4).replaceFirst("^[+-]", "").split("=", 2)[0];
        return key.contains("GC") || key.startsWith("G1") || key.startsWith("Z")
                || key.startsWith("Shenandoah") || key.startsWith("CMS")
                || key.equals("InitiatingHeapOccupancyPercent") || key.equals("NewRatio")
                || key.equals("NewSize") || key.equals("MaxNewSize") || key.contains("Survivor")
                || key.equals("MaxTenuringThreshold") || key.equals("UseStringDeduplication");
    }

    /// Adds GC parameters without changing heap capacity; custom GC arguments and disabled generation win.
    /// Returns the effective collector, or [GcStrategy#DEFAULT] when Java/user arguments remain in control.
    public static GcStrategy apply(CommandBuilder command, LaunchOptions options) throws IOException {
        if (options.isNoGeneratedJVMArgs() || options.isNoGeneratedOptimizingJVMArgs())
            return GcStrategy.DEFAULT;
        if (!command.noneMatch(GcStrategySupport::isGcArgument)) {
            LOG.info("GC strategy is controlled by custom JVM arguments");
            return GcStrategy.DEFAULT;
        }
        if (options.getGcStrategy() == GcStrategy.DEFAULT) {
            LOG.info("GC strategy leaves collector selection and tuning to Java");
            return GcStrategy.DEFAULT;
        }

        int version = options.getJava().getParsedVersion();
        Path executable = options.getJavaExecutable();
        Resolution resolution;
        try {
            resolution = resolve(options.getGcStrategy(), version, options.getJava().getBits() == Bits.BIT_64,
                    arguments -> supports(executable, arguments));
        } catch (ProbeInterruptedException e) {
            throw new IOException("Interrupted while checking garbage collector support", e.getCause());
        }
        command.addAllDefault(resolution.arguments());
        if (resolution.strategy() == GcStrategy.TUNED_G1) {
            command.addUnstableDefault("G1MixedGCCountTarget", "5");
            command.addUnstableDefault("G1NewSizePercent", "20");
            command.addUnstableDefault("G1ReservePercent", "20");
            command.addUnstableDefault("MaxGCPauseMillis", "50");
            command.addUnstableDefault("G1HeapRegionSize", "32m");
        }
        LOG.info("GC strategy: requested=" + options.getGcStrategy() + ", effective=" + resolution.strategy()
                + ", Java=" + version + ", executable=" + executable);
        return resolution.strategy();
    }

    /// Selects the first supported candidate. Version checks prevent experimental/removed flags being emitted.
    /// The support predicate tests the target runtime, since vendor/platform availability can differ.
    static Resolution resolve(GcStrategy requested, int version, boolean is64bit,
                              Predicate<List<String>> supports) {
        @Unmodifiable List<GcStrategy> candidates = switch (requested) {
            case TUNED_G1 -> List.of(GcStrategy.TUNED_G1);
            case G1 -> List.of(GcStrategy.G1);
            case GENERATIONAL_ZGC -> List.of(GcStrategy.GENERATIONAL_ZGC, GcStrategy.ZGC, GcStrategy.G1);
            case ZGC -> List.of(GcStrategy.ZGC, GcStrategy.G1);
            case DEFAULT -> List.of();
        };
        for (GcStrategy candidate : candidates) {
            @Unmodifiable List<String> arguments;
            if (candidate == GcStrategy.GENERATIONAL_ZGC) {
                if (version < 21 || !is64bit)
                    continue;
                // Java 23 defaults to generational ZGC; 24+ removes non-generational mode.
                arguments = version < 23 ? List.of("-XX:+UseZGC", "-XX:+ZGenerational") : List.of("-XX:+UseZGC");
            } else if (candidate == GcStrategy.ZGC) {
                if (version < 15 || !is64bit)
                    continue;
                arguments = List.of("-XX:+UseZGC");
            } else {
                if (version < 8)
                    continue;
                arguments = List.of("-XX:+UseG1GC");
            }
            if (supports.test(arguments)) {
                GcStrategy effective = candidate == GcStrategy.ZGC && version >= 23
                        ? GcStrategy.GENERATIONAL_ZGC : candidate;
                return new Resolution(effective, arguments);
            }
        }
        return new Resolution(GcStrategy.DEFAULT, List.of());
    }

    /// Tests collector availability with a small, bounded JVM invocation, without starting the game.
    private static boolean supports(Path executable, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("-Xms16m");
        command.add("-Xmx64m");
        command.addAll(arguments);
        command.add("-version");
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        // Test runtime capability independently of inherited JVM injection variables.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        try {
            Process process = builder.start();
            try {
                process.getOutputStream().close();
                return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
            } finally {
                if (process.isAlive())
                    process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProbeInterruptedException(e);
        } catch (IOException e) {
            LOG.warning("Could not check garbage collector support: " + executable, e);
            return false;
        }
    }

    /// Effective collector and immutable selection arguments, excluding the optional G1 tuning bundle.
    @NotNullByDefault
    record Resolution(GcStrategy strategy, @Unmodifiable List<String> arguments) {
        /// Defensively copies the arguments to keep the resolution immutable.
        Resolution {
            arguments = List.copyOf(arguments);
        }
    }

    /// Carries an interruption through the capability predicate without treating cancellation as fallback.
    @NotNullByDefault
    private static final class ProbeInterruptedException extends RuntimeException {
        /// Wraps the interrupted capability check.
        private ProbeInterruptedException(InterruptedException cause) {
            super(cause);
        }
    }
}
