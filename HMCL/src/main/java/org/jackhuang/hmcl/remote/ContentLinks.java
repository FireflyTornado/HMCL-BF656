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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Parses restricted rich text and resolves document links within the launcher installation.
@NotNullByDefault
public final class ContentLinks {
    /// Document types that can be opened by remotely supplied relative links.
    private static final @Unmodifiable Set<String> EXTENSIONS = Set.of("html", "htm", "pdf", "txt", "md");

    /// Prevents construction of this utility.
    private ContentLinks() { }

    /// A text run with optional hyperlink or bold styling.
    @NotNullByDefault
    public record Segment(String text, @Nullable String href, boolean bold) { }

    /// Parses plain text or XML-compatible a/b/br markup, with external entities disabled.
    public static @Unmodifiable List<Segment> parse(String content) throws IOException {
        if (!content.contains("<"))
            return List.of(new Segment(content, null, false));
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            var body = builder.parse(new InputSource(new StringReader("<body>" + content + "</body>")))
                    .getDocumentElement();
            var runs = new ArrayList<Segment>();
            var children = body.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                var node = children.item(i);
                if (node instanceof Element element) {
                    switch (element.getTagName()) {
                        case "a" -> {
                            String href = element.getAttribute("href");
                            if (href.isBlank())
                                throw new IOException("Empty link");
                            runs.add(new Segment(element.getTextContent(), href, false));
                        }
                        case "b" -> runs.add(new Segment(element.getTextContent(), null, true));
                        case "br" -> runs.add(new Segment("\n", null, false));
                        default -> throw new IOException("Unsupported markup: " + element.getTagName());
                    }
                } else {
                    runs.add(new Segment(node.getTextContent(), null, false));
                }
            }
            return List.copyOf(runs);
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException e) {
            throw new IOException("Invalid rich text", e);
        }
    }

    /// Resolves HTTP(S) links or existing local documents, rejecting absolute paths and directory escapes.
    public static URI resolve(Path directory, String href) throws IOException {
        try {
            URI uri = URI.create(href.replace(" ", "%20"));
            if (uri.isAbsolute()) {
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null)
                    throw new IOException("不支持的链接类型");
                return uri;
            }
            if (uri.getAuthority() != null || uri.getQuery() != null || uri.getPath() == null)
                throw new IOException("本地文件必须使用相对路径");
            Path relative = Path.of(uri.getPath());
            if (relative.isAbsolute() || href.contains("\\") || uri.getPath().contains(":"))
                throw new IOException("本地文件必须使用相对路径");
            Path base = directory.toRealPath();
            Path file = base.resolve(relative).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file))
                throw new IOException("本地文档不存在或超出客户端目录：" + href);
            file = file.toRealPath();
            if (!file.startsWith(base))
                throw new IOException("本地文档不能指向客户端目录以外");
            String name = file.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!EXTENSIONS.contains(extension))
                throw new IOException("本地链接仅支持 HTML、PDF、TXT、MD 文档");
            return file.toUri();
        } catch (IllegalArgumentException e) {
            throw new IOException("无效链接：" + href, e);
        }
    }

    /// Checks agreement links before enabling acceptance, including existence of every local document.
    public static void validateAgreement(Path directory, RemoteContent.Item item) throws IOException {
        for (Segment segment : parse(item.content())) {
            if (segment.href() != null)
                resolve(directory, segment.href());
        }
    }
}
