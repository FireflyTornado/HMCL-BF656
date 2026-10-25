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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Offline publishing tool: converts one instance's authoring directory into a verified public manifest and assets.
@NotNullByDefault
public final class ContentPublisher {
    /// Prevents construction of the command-line utility.
    private ContentPublisher() { }

    /// Publishes directory content or a legacy content.json; an optional key supports custom source locations.
    public static void main(String @Unmodifiable [] args) throws Exception {
        if (args.length < 1 || args.length > 3)
            throw new IllegalArgumentException("Usage: ContentPublisher <source> [public output directory] [instance key]");
        publish(Path.of(args[0]), args.length >= 2 ? Path.of(args[1]) : Path.of(args[0]), args.length == 3 ? args[2] : null);
    }

    /// Recomputes file digests and Markdown dependencies; the manifest is published last after all payloads exist.
    public static void publish(Path sourceDirectory, Path outputDirectory) throws IOException {
        publish(sourceDirectory, outputDirectory, null);
    }

    /// Discovers source metadata without a central list while retaining existing public manifest compatibility.
    public static void publish(Path sourceDirectory, Path outputDirectory, @Nullable String instanceKey) throws IOException {
        Path source = sourceDirectory.toAbsolutePath().normalize().toRealPath();
        Path output = outputDirectory.toAbsolutePath().normalize();
        JsonObject root;
        try {
            root = Files.exists(source.resolve("content.json"))
                    ? JsonParser.parseString(Files.readString(source.resolve("content.json"), StandardCharsets.UTF_8)).getAsJsonObject()
                    : DirectoryContentSource.instance(source, instanceKey);
        } catch (RuntimeException e) {
            throw new IOException("Invalid content.json", e);
        }
        JsonObject index = new JsonObject();
        JsonObject externals = root.has("externalFiles") ? root.getAsJsonObject("externalFiles") : new JsonObject();
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        List<JsonObject> items = new ArrayList<>();
        items.add(root.getAsJsonObject("agreement"));
        for (String category : List.of("notices", "announcements")) {
            for (var item : root.getAsJsonArray(category))
                items.add(item.getAsJsonObject());
        }
        for (JsonObject item : items) {
            String body = RemoteContent.string(item, "contentFile", "");
            Set<String> dependencies = new HashSet<>();
            if (item.has("resources")) {
                for (var resource : item.getAsJsonArray("resources"))
                    add(source, resource.getAsString(), externals, index, payloads, dependencies);
            }
            if (!body.isEmpty()) {
                add(source, body, externals, index, payloads, dependencies);
                Set<String> visited = new HashSet<>();
                collect(source, body, externals, index, payloads, dependencies, visited);
                dependencies.remove(body);
            }
            JsonArray resources = new JsonArray();
            dependencies.stream().sorted().forEach(resources::add);
            item.add("resources", resources);
        }
        root.remove("externalFiles");
        root.add("files", index);
        String key = RemoteContent.string(root, "instanceKey", "");
        String manifest = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
        if (manifest.getBytes(StandardCharsets.UTF_8).length > 2_097_152)
            throw new IOException("Manifest exceeds 2 MiB");
        RemoteContent.parse(manifest, key);
        Files.createDirectories(output);
        Path realOutput = output.toRealPath();
        for (var payload : payloads.entrySet()) {
            Path destination = output.resolve(RemoteContent.logicalPath(payload.getKey())).normalize();
            Files.createDirectories(destination.getParent());
            if (!destination.getParent().toRealPath().startsWith(realOutput))
                throw new IOException("Publishing destination escapes output directory");
            if (!Files.exists(destination) || !RemoteContent.digest(Files.readAllBytes(destination)).equals(RemoteContent.digest(payload.getValue())))
                atomic(destination, payload.getValue());
        }
        atomic(source.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
        if (!source.equals(output))
            atomic(output.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
        System.out.println("Published instance " + key + ": " + index.size() + " indexed files -> " + output);
    }

    /// Walks actual CommonMark nodes, including reference-style links and images, rather than matching Markdown by regex.
    private static void collect(Path source, String document, JsonObject externals, JsonObject index,
                                Map<String, byte[]> payloads, Set<String> dependencies, Set<String> visited) throws IOException {
        if (!visited.add(document))
            return;
        byte[] bytes = payloads.get(document);
        if (bytes == null)
            return;
        List<String> markdown = new ArrayList<>();
        try {
            ContentDocuments.parse(new String(bytes, StandardCharsets.UTF_8)).accept(new AbstractVisitor() {
                /// Images must be locally hosted or explicitly declared external resources with known hashes.
                @Override
                public void visit(Image image) {
                    reference(image.getDestination(), true);
                }

                /// Local linked documents are included; ordinary external hyperlinks need no managed download.
                @Override
                public void visit(Link link) {
                    reference(link.getDestination(), false);
                    visitChildren(link);
                }

                /// Resolves one URI against the source document's public-relative location.
                private void reference(String destination, boolean image) {
                    if (destination.startsWith("#") || destination.startsWith("client/"))
                        return;
                    try {
                        URI uri = URI.create(destination.replace(" ", "%20"));
                        if (uri.isAbsolute()) {
                            RemoteContent.validateHttp(uri);
                            if (!image)
                                return;
                            for (var descriptor : externals.entrySet()) {
                                if (destination.equals(RemoteContent.string(descriptor.getValue().getAsJsonObject(), "url", ""))) {
                                    add(source, descriptor.getKey(), externals, index, payloads, dependencies);
                                    return;
                                }
                            }
                            throw new IOException("External image requires externalFiles entry with sha256: " + destination);
                        }
                        if (uri.getAuthority() != null || uri.getQuery() != null)
                            throw new IOException("Invalid relative document URL: " + destination);
                        URI location = URI.create(document.replace(" ", "%20")).resolve(uri).normalize();
                        String path = RemoteContent.logicalPath(location.getPath());
                        add(source, path, externals, index, payloads, dependencies);
                        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
                            markdown.add(path);
                    } catch (IOException | IllegalArgumentException e) {
                        throw new PublishingException(e);
                    }
                }
            });
        } catch (PublishingException e) {
            throw new IOException(e.getMessage(), e);
        }
        for (String nested : markdown)
            collect(source, nested, externals, index, payloads, dependencies, visited);
    }

    /// Adds one exact local payload or explicit external descriptor to the public dependency index.
    private static void add(Path source, String logical, JsonObject externals, JsonObject index,
                            Map<String, byte[]> payloads, Set<String> dependencies) throws IOException {
        RemoteContent.logicalPath(logical);
        dependencies.add(logical);
        if (index.has(logical))
            return;
        if (externals.has(logical)) {
            index.add(logical, externals.getAsJsonObject(logical).deepCopy());
            return;
        }
        Path file = source.resolve(logical).normalize();
        if (!file.startsWith(source) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(source))
            throw new IOException("Missing or escaping source resource: " + logical);
        if (Files.size(file) > 16_777_216)
            throw new IOException("Resource exceeds 16 MiB: " + logical);
        if (logical.toLowerCase(java.util.Locale.ROOT).endsWith(".md") && Files.size(file) > 2_097_152)
            throw new IOException("Markdown body exceeds 2 MiB: " + logical);
        byte[] bytes = logical.toLowerCase(java.util.Locale.ROOT).endsWith(".md") && !Files.exists(source.resolve("content.json"))
                ? DirectoryContentSource.markdown(file).body().getBytes(StandardCharsets.UTF_8)
                : Files.readAllBytes(file);
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("sha256", RemoteContent.digest(bytes));
        index.add(logical, descriptor);
        payloads.put(logical, bytes);
    }

    /// Writes complete files first and atomically replaces the visible filename where supported.
    private static void atomic(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "publish-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /// Bridges CommonMark visitor errors to checked publishing failures.
    @NotNullByDefault
    private static final class PublishingException extends RuntimeException {
        /// Preserves the source validation failure.
        PublishingException(Exception cause) {
            super(cause.getMessage(), cause);
        }
    }
}
