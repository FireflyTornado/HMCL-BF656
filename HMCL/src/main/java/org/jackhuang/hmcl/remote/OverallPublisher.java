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
package org.jackhuang.hmcl.remote;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// Exports overall/content.json and its referenced wallpaper files to public/overall/.
@NotNullByDefault
public final class OverallPublisher {
    /// Prevents construction.
    private OverallPublisher() { }

    /// Standalone publishing entry point, also used by Publish-Content.ps1 -Overall.
    public static void main(String @Unmodifiable [] args) throws IOException {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: OverallPublisher <overall source directory> <public overall directory>");
        publish(Path.of(args[0]), Path.of(args[1]));
    }

    /// Validates the entire pool before publishing any files and replaces the manifest after all resources exist.
    public static void publish(Path sourceDirectory, Path outputDirectory) throws IOException {
        Path source = sourceDirectory.toRealPath();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (Files.size(source.resolve("content.json")) > 2_097_152)
            throw new IOException("Overall configuration exceeds 2 MiB");
        try {
            JsonObject root = JsonParser.parseString(Files.readString(source.resolve("content.json"))).getAsJsonObject();
            OverallContent.Background background = OverallContent.background(root);
            JsonObject files = new JsonObject();
            Map<String, byte[]> payloads = new LinkedHashMap<>();
            JsonObject externals = root.has("externalFiles") ? root.getAsJsonObject("externalFiles") : new JsonObject();
            for (String name : background.images()) {
                if (files.has(name))
                    continue;
                if (externals.has(name)) {
                    files.add(name, externals.getAsJsonObject(name).deepCopy());
                    continue;
                }
                Path path = source.resolve(name).normalize();
                if (!Files.isRegularFile(path) || !path.toRealPath().startsWith(source))
                    throw new IOException("Missing or escaping overall resource: " + name);
                if (Files.size(path) > 16_777_216)
                    throw new IOException("Overall resource exceeds 16 MiB: " + name);
                byte[] bytes = Files.readAllBytes(path);
                JsonObject descriptor = new JsonObject();
                descriptor.addProperty("sha256", RemoteContent.digest(bytes));
                files.add(name, descriptor);
                payloads.put(name, bytes);
            }
            root.remove("externalFiles");
            root.add("files", files);
            String body = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 2_097_152)
                throw new IOException("Overall manifest exceeds 2 MiB");
            OverallContent.parse(body);
            for (var entry : payloads.entrySet())
                OverallContent.write(output, output.resolve(entry.getKey()), entry.getValue());
            OverallContent.write(output, output.resolve("manifest.json"), bytes);
            System.out.println("Published overall: " + files.size() + " indexed files -> " + output);
        } catch (RuntimeException e) {
            throw new IOException("Invalid overall/content.json", e);
        }
    }
}
