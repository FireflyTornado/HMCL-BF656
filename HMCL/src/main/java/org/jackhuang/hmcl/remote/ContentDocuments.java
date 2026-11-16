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

import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.jackhuang.hmcl.ui.image.ImageLoader;
import org.jackhuang.hmcl.ui.image.ImageUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Shared Markdown parsing and deterministic link-to-index resolution for rendering and prelaunch checks.
@NotNullByDefault
public final class ContentDocuments {
    /// Parser supports CommonMark plus the explicitly documented table, autolink and strikethrough extensions.
    private static final Parser PARSER = Parser.builder().extensions(List.of(AutolinkExtension.create(),
            TablesExtension.create(), StrikethroughExtension.create())).build();

    /// Prevents construction of this utility.
    private ContentDocuments() { }

    /// Parses Markdown without executing embedded HTML or scripts.
    public static Node parse(String markdown) {
        return PARSER.parse(markdown);
    }

    /// Resolves a Markdown-relative resource or an explicitly indexed absolute image URL.
    public static @Nullable RemoteContent.Resource resource(RemoteContent.Manifest manifest, RemoteContent.Item item,
                                                            URI endpoint, String destination) throws IOException {
        return resource(manifest.files(), item, endpoint, destination);
    }

    /// Resolves a reference using the index for the document revision being rendered.
    static @Nullable RemoteContent.Resource resource(@Unmodifiable Map<String, RemoteContent.Resource> files,
                                                     RemoteContent.Item item, URI endpoint, String destination) throws IOException {
        try {
            URI reference = URI.create(destination.replace(" ", "%20"));
            URI document = endpoint.resolve(new URI(null, null, item.contentFile(), null));
            URI absolute = document.resolve(reference).normalize();
            for (var resource : files.values()) {
                URI target = resource.url().isEmpty()
                        ? endpoint.resolve(new URI(null, null, resource.path(), null)) : URI.create(resource.url());
                if (withoutFragment(target).equals(withoutFragment(absolute)))
                    return resource;
            }
            return null;
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw new IOException("无效的 Markdown 资源链接：" + destination, e);
        }
    }

    /// Converts links into cached public documents, explicit instance-local documents or external web pages.
    public static URI link(Path root, RemoteContent.Manifest manifest, RemoteContent.Item item,
                           ContentStore store, URI endpoint, String destination) throws IOException {
        return link(root, manifest.files(), item, store, endpoint, destination);
    }

    /// Resolves a clickable link and verifies the bytes of any cached document before opening it.
    static URI link(Path root, @Unmodifiable Map<String, RemoteContent.Resource> files, RemoteContent.Item item,
                    ContentStore store, URI endpoint, String destination) throws IOException {
        if (item.contentFile().isEmpty() || destination.startsWith("client/"))
            return ContentLinks.resolve(root, destination);
        @Nullable RemoteContent.Resource resource = resource(files, item, endpoint, destination);
        if (resource != null) {
            Path file = store.file(resource);
            if (!Files.isRegularFile(file))
                throw new IOException("文档尚未下载完成：" + destination);
            String extension = resource.path().substring(resource.path().lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
            if (!List.of("md", "html", "htm", "pdf", "txt", "png", "jpg", "jpeg", "gif", "webp", "bmp", "apng").contains(extension))
                throw new IOException("不支持打开此文档类型");
            if (!store.verified(resource))
                throw new IOException("缓存文档校验失败，暂不可用：" + destination);
            return file.toUri();
        }
        try {
            URI uri = URI.create(destination.replace(" ", "%20"));
            if (uri.isAbsolute()) {
                RemoteContent.validateHttp(uri);
                return uri;
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("不支持的文档链接：" + destination, e);
        }
        throw new IOException("文档未列入此实例的资源清单：" + destination);
    }

    /// Validates agreement links and all Markdown images against this instance's complete downloaded resources.
    public static void validate(Path root, RemoteContent.Manifest manifest, RemoteContent.Item item,
                                ContentStore store, URI endpoint, boolean agreement) throws IOException {
        if (!store.ready(manifest, item))
            throw new IOException("正文或关联资源尚未下载完成");
        if (agreement) {
            for (String path : item.files()) {
                RemoteContent.Resource resource = java.util.Objects.requireNonNull(manifest.files().get(path));
                if (!store.verified(resource))
                    throw new IOException("协议缓存文件校验失败，暂不可用：" + path);
            }
        }
        validateDocument(manifest.files(), item, store, endpoint, agreement ? root : null, false);
    }

    /// Validates an announcement's document after its exact payloads have passed digest checks.
    static void validateAnnouncement(RemoteContent.Announcement announcement, ContentStore store, URI endpoint) throws IOException {
        validateDocument(announcement.files(), announcement.item(), store, endpoint, null, true);
    }

    /// Loads an indexed Markdown image using the same format rules for verification and rendering.
    static javafx.scene.image.Image loadImage(RemoteContent.Resource resource, ContentStore store) throws IOException {
        String path = resource.path();
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        ImageLoader loader = switch (extension) {
            case "webp" -> ImageUtils.WEBP;
            case "apng" -> ImageUtils.APNG;
            case "png", "jpg", "jpeg", "gif", "bmp" -> ImageUtils.DEFAULT;
            default -> throw new IOException("不支持的图片格式：" + path);
        };
        try (var input = Files.newInputStream(store.file(resource))) {
            return loader.load(input, 1400, 0, true, true);
        } catch (Exception e) {
            throw new IOException("图片无法解码：" + path, e);
        }
    }

    /// Captures the extra indexed documents that this Markdown revision actually links to.
    static @Unmodifiable Map<String, RemoteContent.Resource> linkedFiles(RemoteContent.Announcement announcement,
            RemoteContent.Manifest manifest, ContentStore store, URI endpoint) throws IOException {
        RemoteContent.Item item = announcement.item();
        if (item.contentFile().isEmpty())
            return Map.of();
        Map<String, RemoteContent.Resource> linked = new HashMap<>();
        parse(store.body(item, announcement.files())).accept(new AbstractVisitor() {
            /// Records optional cached-document targets without affecting announcement readiness.
            @Override
            public void visit(Link node) {
                String destination = node.getDestination();
                if (!destination.startsWith("client/")) {
                    try {
                        @Nullable RemoteContent.Resource target = resource(manifest, item, endpoint, destination);
                        if (target != null && !announcement.files().containsKey(target.path()))
                            linked.put(target.path(), target);
                    } catch (IOException ignored) {
                        // The renderer reports invalid optional links when followed.
                    }
                }
                visitChildren(node);
            }
        });
        return Map.copyOf(linked);
    }

    /// Validates a revision's Markdown images and any required agreement links.
    private static void validateDocument(@Unmodifiable Map<String, RemoteContent.Resource> files, RemoteContent.Item item,
                                         ContentStore store, URI endpoint, @Nullable Path agreementRoot,
                                         boolean decodeImages) throws IOException {
        if (item.contentFile().isEmpty()) {
            if (agreementRoot != null)
                ContentLinks.validateAgreement(agreementRoot, item);
            return;
        }
        Node document = parse(store.body(item, files));
        try {
            document.accept(new AbstractVisitor() {
                /// Requires all images to have indexed, hash-verified local payloads.
                @Override
                public void visit(Image image) {
                    try {
                        @Nullable RemoteContent.Resource resource = resource(files, item, endpoint, image.getDestination());
                        if (resource == null || !item.files().contains(resource.path()) || !Files.isRegularFile(store.file(resource)))
                            throw new IOException("图片未列入 resources 或尚未下载：" + image.getDestination());
                        if (decodeImages)
                            loadImage(resource, store);
                    } catch (IOException e) {
                        throw new DocumentException(e);
                    }
                }

                /// Required agreement links must resolve before consent can be recorded.
                @Override
                public void visit(Link node) {
                    if (agreementRoot != null) {
                        try {
                            link(agreementRoot, files, item, store, endpoint, node.getDestination());
                        } catch (IOException e) {
                            throw new DocumentException(e);
                        }
                    }
                    visitChildren(node);
                }
            });
        } catch (DocumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /// Removes fragments when matching a document or image to an indexed resource.
    private static URI withoutFragment(URI uri) throws URISyntaxException {
        return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
    }

    /// Bridges visitor callbacks to checked document-validation failures.
    @NotNullByDefault
    private static final class DocumentException extends RuntimeException {
        /// Creates a visitor failure retaining the original validation cause.
        private DocumentException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
