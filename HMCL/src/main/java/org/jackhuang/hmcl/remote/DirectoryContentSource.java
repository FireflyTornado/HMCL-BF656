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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/// Builds publishing metadata from directories and optional scalar Markdown front matter, without a content.json list.
@NotNullByDefault
final class DirectoryContentSource {
    /// Raster image extensions supported by the existing launcher image loaders.
    private static final @Unmodifiable Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "apng");
    /// String-valued document metadata; dates are validated by the public manifest parser.
    private static final @Unmodifiable Set<String> STRING_FIELDS = Set.of("id", "title", "introduction", "repeat", "publishedAt", "startsAt", "expiresAt");
    /// Integer-valued metadata, parsed exactly rather than coerced from floating point values.
    private static final @Unmodifiable Set<String> INTEGER_FIELDS = Set.of("revision", "priority");
    /// Metadata shared by all document categories.
    private static final @Unmodifiable Set<String> COMMON_FIELDS = Set.of("id", "title", "revision");
    /// Optional scalar header at the very beginning of a Markdown file.
    private static final Pattern OPENING = Pattern.compile("\\A---[ \\t]*\\r?\\n");
    /// A standalone closing delimiter, including its line ending so the body retains its original bytes.
    private static final Pattern CLOSING = Pattern.compile("(?m)^---[ \\t]*(?:\\r?\\n|$)");

    /// Prevents construction.
    private DirectoryContentSource() { }

    /// Parsed document with metadata kept out of the public Markdown payload and its SHA-256 digest.
    @NotNullByDefault
    record Document(JsonObject metadata, String body) { }

    /// Discovers one protocol and all top-level notice/announcement documents for an explicit server instance key.
    static JsonObject instance(Path source, @Nullable String requestedKey) throws IOException {
        String key;
        if (requestedKey != null) {
            key = requestedKey;
        } else {
            @Nullable Path parent = source.getParent();
            @Nullable Path version = parent == null ? null : parent.getParent();
            if (parent == null || version == null || !parent.getFileName().toString().equals(".launcher")
                    || !source.getFileName().toString().equals("server"))
                throw new IOException("Directory publishing requires an instance key as the third command-line argument");
            key = version.getFileName().toString();
        }
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IOException("Invalid publishing instance key: " + key);
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("instanceKey", key);
        @Unmodifiable List<Path> agreements = documents(source, "agreements");
        if (agreements.size() != 1)
            throw new IOException("agreements/ must contain exactly one active Markdown file; prefix drafts with _");
        root.add("agreement", item(source, agreements.get(0), "agreements"));
        for (String category : List.of("notices", "announcements")) {
            JsonArray items = new JsonArray();
            for (Path path : documents(source, category))
                items.add(item(source, path, category));
            root.add(category, items);
        }
        return root;
    }

    /// Discovers the entire background image directory and optional URL lines; global settings remain optional.
    static JsonObject overall(Path source) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        JsonObject background = new JsonObject();
        boolean enabled = true;
        Path settings = source.resolve("settings.properties");
        if (Files.exists(settings)) {
            checkFile(source, settings, 16_384);
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(settings, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            String value = properties.getProperty("background.enabled", "true").trim();
            if (!value.equals("true") && !value.equals("false"))
                throw new IOException("background.enabled must be true or false");
            enabled = Boolean.parseBoolean(value);
        }
        background.addProperty("enabled", enabled);
        JsonArray images = new JsonArray();
        for (Path path : files(source, "backgrounds", true, DirectoryContentSource::isImage))
            images.add(logical(source, path));
        background.add("images", images);
        JsonArray urls = new JsonArray();
        Path urlFile = source.resolve("backgrounds/urls.txt");
        if (Files.exists(urlFile)) {
            checkFile(source, urlFile, 65_536);
            Set<String> unique = new LinkedHashSet<>();
            for (String line : Files.readString(urlFile, StandardCharsets.UTF_8).replace("\uFEFF", "").split("\\R")) {
                String value = line.trim();
                if (!value.isEmpty() && !value.startsWith("#"))
                    unique.add(value);
            }
            unique.forEach(urls::add);
        }
        background.add("urls", urls);
        root.add("background", background);
        OverallContent.background(root);
        return root;
    }

    /// Reads bounded UTF-8 Markdown and strips a header consisting only of supported scalar fields.
    static Document markdown(Path path) throws IOException {
        if (Files.size(path) > 2_097_152)
            throw new IOException("Markdown exceeds 2 MiB: " + path);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF"))
            text = text.substring(1);
        var opening = OPENING.matcher(text);
        if (!opening.find())
            return new Document(new JsonObject(), text);
        var closing = CLOSING.matcher(text);
        if (!closing.find(opening.end()) || closing.start() - opening.end() > 16_384)
            throw new IOException("Missing or oversized Markdown metadata delimiter: " + path);
        JsonObject metadata = new JsonObject();
        for (String line : text.substring(opening.end(), closing.start()).split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#"))
                continue;
            int colon = line.indexOf(':');
            if (colon < 1 || Character.isWhitespace(line.charAt(0)))
                throw new IOException("Metadata must use one scalar key: value per line: " + path);
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (metadata.has(name))
                throw new IOException("Duplicate metadata field " + name + ": " + path);
            try {
                if (STRING_FIELDS.contains(name)) {
                    metadata.addProperty(name, stringValue(value));
                } else if (INTEGER_FIELDS.contains(name)) {
                    metadata.addProperty(name, Integer.parseInt(value));
                } else if (name.equals("pinned") && (value.equals("true") || value.equals("false"))) {
                    metadata.addProperty(name, Boolean.parseBoolean(value));
                } else {
                    throw new IllegalArgumentException("Unsupported metadata field or value: " + name);
                }
            } catch (RuntimeException e) {
                throw new IOException("Invalid metadata in " + path + ": " + e.getMessage(), e);
            }
        }
        return new Document(metadata, text.substring(closing.end()));
    }

    /// Converts one document to existing instance metadata, using path identity and body-derived title defaults.
    private static JsonObject item(Path source, Path path, String category) throws IOException {
        Document document = markdown(path);
        JsonObject item = document.metadata().deepCopy();
        for (String field : item.keySet()) {
            boolean allowed = COMMON_FIELDS.contains(field) || (category.equals("agreements")
                    ? field.equals("introduction")
                    : Set.of("priority", "pinned", "publishedAt", "startsAt", "expiresAt").contains(field)
                    || category.equals("notices") && field.equals("repeat"));
            if (!allowed)
                throw new IOException("Metadata field " + field + " is not supported in " + category + "/");
        }
        String filename = path.getFileName().toString();
        String stem = filename.substring(0, filename.length() - 3);
        if (!item.has("id"))
            item.addProperty("id", category + "/" + stem);
        if (!item.has("revision"))
            item.addProperty("revision", 1);
        if (!item.has("title"))
            item.addProperty("title", title(document.body(), stem));
        item.addProperty("contentFile", logical(source, path));
        return item;
    }

    /// Uses the first actual top-level H1 heading, including inline code/link text, then falls back to the filename.
    private static String title(String body, String fallback) {
        for (@Nullable Node node = ContentDocuments.parse(body).getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading && heading.getLevel() == 1) {
                StringBuilder text = new StringBuilder();
                plainText(heading, text);
                if (!text.toString().isBlank())
                    return text.toString().trim();
            }
        }
        return fallback;
    }

    /// Extracts display text without rendering Markdown syntax into a title label.
    private static void plainText(Node node, StringBuilder result) {
        if (node instanceof Text text) {
            result.append(text.getLiteral());
        } else if (node instanceof Code code) {
            result.append(code.getLiteral());
        } else {
            for (@Nullable Node child = node.getFirstChild(); child != null; child = child.getNext())
                plainText(child, result);
        }
    }

    /// Supports plain strings, JSON double-quoted strings with escapes and single-quoted scalar strings.
    private static String stringValue(String value) {
        if (value.startsWith("\"")) {
            var parsed = JsonParser.parseString(value);
            if (!parsed.isJsonPrimitive() || !parsed.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Expected a quoted string");
            return parsed.getAsString();
        }
        if (value.startsWith("'")) {
            if (value.length() < 2 || !value.endsWith("'"))
                throw new IllegalArgumentException("Unclosed single-quoted string");
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.equals("|") || value.equals(">") || value.startsWith("[") || value.startsWith("{"))
            throw new IllegalArgumentException("Metadata supports scalar values, not multiline blocks or collections");
        return value;
    }

    /// Lists only top-level category documents; assets/ and draft filenames cannot become independent cards.
    private static @Unmodifiable List<Path> documents(Path source, String category) throws IOException {
        return files(source, category, false, path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"));
    }

    /// Lists deterministic source files without following linked directories or publishing hidden/draft paths.
    private static @Unmodifiable List<Path> files(Path source, String directory, boolean recursive,
                                                 Predicate<Path> accept) throws IOException {
        Path folder = source.resolve(directory);
        if (!Files.exists(folder))
            return List.of();
        if (!Files.isDirectory(folder) || !folder.toRealPath().startsWith(source))
            throw new IOException("Invalid or escaping source directory: " + folder);
        List<Path> result = new ArrayList<>();
        try (var entries = Files.walk(folder, recursive ? Integer.MAX_VALUE : 1)) {
            for (Path path : entries.filter(Files::isRegularFile).filter(accept).sorted().toList()) {
                boolean draft = false;
                for (Path component : folder.relativize(path)) {
                    String name = component.toString();
                    draft |= name.startsWith(".") || name.startsWith("_");
                }
                if (!draft) {
                    checkFile(source, path, 16_777_216);
                    result.add(path);
                    if (result.size() > 100)
                        throw new IOException("Too many files in " + directory + "/ (maximum 100)");
                }
            }
        }
        return List.copyOf(result);
    }

    /// Recognizes image paths without loading JavaFX or interpreting administrator notes as images.
    private static boolean isImage(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /// Bounds source bytes and rejects file or directory links that escape the source root.
    private static void checkFile(Path source, Path path, long limit) throws IOException {
        if (!Files.isRegularFile(path) || !path.toRealPath().startsWith(source) || Files.size(path) > limit)
            throw new IOException("Missing, oversized or escaping source file: " + path);
    }

    /// Creates a portable public path checked by the existing manifest validators.
    private static String logical(Path source, Path path) {
        return RemoteContent.logicalPath(source.relativize(path).toString().replace('\\', '/'));
    }
}
