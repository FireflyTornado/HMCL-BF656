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

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.*;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.image.ImageLoader;
import org.jackhuang.hmcl.ui.image.ImageUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/// Native JavaFX Markdown view with themed typography, tables, links and cached responsive images.
@NotNullByDefault
public final class MarkdownView extends VBox {
    /// Instance root for explicitly local links such as `client/documents/terms.html`.
    private final Path root;
    /// Current item and indexed resources used for relative Markdown URL resolution.
    private final RemoteContent.Manifest manifest;
    /// Item whose body is displayed.
    private final RemoteContent.Item item;
    /// Content-addressed local downloads.
    private final ContentStore store;
    /// Public instance directory corresponding to Markdown-relative links.
    private final URI endpoint;
    /// User-visible error callback owned by the containing card or dialog.
    private final Consumer<String> error;
    /// Visible recovery action when a local document has no working default application.
    private final Hyperlink documentFolder = new Hyperlink("打开文档所在文件夹");

    /// Builds a complete document; parsing and rendering never execute embedded HTML.
    public MarkdownView(Path root, RemoteContent.Manifest manifest, RemoteContent.Item item,
                        ContentStore store, URI endpoint, Consumer<String> error) {
        super(10);
        this.root = root;
        this.manifest = manifest;
        this.item = item;
        this.store = store;
        this.endpoint = endpoint;
        this.error = error;
        documentFolder.setVisible(false);
        documentFolder.setManaged(false);
        setFillWidth(true);
        setMinWidth(0);
        try {
            String body = store.body(manifest, item);
            if (item.contentFile().isEmpty())
                getChildren().add(richText(body));
            else
                blocks(ContentDocuments.parse(body), this);
        } catch (IOException e) {
            getChildren().add(new Label("正文暂不可用：" + e.getMessage()));
        }
        getChildren().add(documentFolder);
    }

    /// Renders legacy inline rich text using the same instance-local link policy.
    private TextFlow richText(String body) throws IOException {
        TextFlow flow = flow();
        for (var run : ContentLinks.parse(body)) {
            if (run.href() != null) {
                flow.getChildren().add(link(run.text(), run.href()));
            } else {
                Text text = text(run.text(), 14, run.bold(), false, false, false);
                flow.getChildren().add(text);
            }
        }
        return flow;
    }

    /// Renders block-level AST nodes without a WebView or additional JavaFX runtime modules.
    private void blocks(Node parent, VBox target) {
        for (@Nullable Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                TextFlow flow = flow();
                inline(child, flow, Math.max(15, 27 - heading.getLevel() * 2), true, false, false);
                target.getChildren().add(flow);
            } else if (child instanceof Paragraph) {
                TextFlow flow = flow();
                inline(child, flow, 14, false, false, false);
                target.getChildren().add(flow);
            } else if (child instanceof BulletList || child instanceof OrderedList) {
                int number = child instanceof OrderedList ordered ? ordered.getMarkerStartNumber() : 0;
                for (@Nullable Node entry = child.getFirstChild(); entry != null; entry = entry.getNext()) {
                    Label marker = new Label(child instanceof OrderedList ? number++ + "." : "•");
                    marker.setMinWidth(25);
                    VBox body = new VBox(6);
                    body.setMinWidth(0);
                    blocks(entry, body);
                    HBox.setHgrow(body, Priority.ALWAYS);
                    HBox row = new HBox(5, marker, body);
                    row.setPadding(new Insets(0, 0, 0, 8));
                    target.getChildren().add(row);
                }
            } else if (child instanceof BlockQuote) {
                VBox quote = new VBox(8);
                quote.setPadding(new Insets(8, 12, 8, 12));
                quote.setStyle("-fx-border-color: -monet-primary; -fx-border-width: 0 0 0 3;");
                blocks(child, quote);
                target.getChildren().add(quote);
            } else if (child instanceof FencedCodeBlock code) {
                target.getChildren().add(code(code.getLiteral()));
            } else if (child instanceof IndentedCodeBlock code) {
                target.getChildren().add(code(code.getLiteral()));
            } else if (child instanceof ThematicBreak) {
                target.getChildren().add(new Separator());
            } else if (child instanceof TableBlock table) {
                target.getChildren().add(table(table));
            } else if (child instanceof HtmlBlock html) {
                TextFlow flow = flow();
                flow.getChildren().add(text(html.getLiteral(), 14, false, false, false, true));
                target.getChildren().add(flow);
            } else {
                blocks(child, target);
            }
        }
    }

    /// Renders nested inline formatting and images while retaining real keyboard-accessible links.
    private void inline(Node parent, TextFlow target, int size, boolean bold, boolean italic, boolean strike) {
        for (@Nullable Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text literal) {
                target.getChildren().add(text(literal.getLiteral(), size, bold, italic, strike, false));
            } else if (child instanceof StrongEmphasis) {
                inline(child, target, size, true, italic, strike);
            } else if (child instanceof Emphasis) {
                inline(child, target, size, bold, true, strike);
            } else if (child instanceof Strikethrough) {
                inline(child, target, size, bold, italic, true);
            } else if (child instanceof Code code) {
                target.getChildren().add(text(code.getLiteral(), size, bold, italic, strike, true));
            } else if (child instanceof Link anchor) {
                target.getChildren().add(link(plain(child), anchor.getDestination()));
            } else if (child instanceof org.commonmark.node.Image image) {
                target.getChildren().add(new Text("\n"));
                target.getChildren().add(image(image, target));
                target.getChildren().add(new Text("\n"));
            } else if (child instanceof HardLineBreak) {
                target.getChildren().add(new Text("\n"));
            } else if (child instanceof SoftLineBreak) {
                target.getChildren().add(new Text(" "));
            } else if (child instanceof HtmlInline html) {
                target.getChildren().add(text(html.getLiteral(), size, bold, italic, strike, true));
            } else {
                inline(child, target, size, bold, italic, strike);
            }
        }
    }

    /// Creates a responsive local image; image decoding runs in the background rather than on the FX thread.
    private VBox image(org.commonmark.node.Image node, TextFlow parent) {
        String alt = plain(node);
        Label status = new Label("正在加载图片" + (alt.isEmpty() ? "…" : "：" + alt));
        status.setWrapText(true);
        VBox box = new VBox(4, status);
        box.setMinWidth(0);
        box.prefWidthProperty().bind(Bindings.max(80, parent.widthProperty().subtract(10)));
        try {
            @Nullable RemoteContent.Resource resource = ContentDocuments.resource(manifest, item, endpoint, node.getDestination());
            if (resource == null || !item.files().contains(resource.path()))
                throw new IOException("图片未列入此条内容的 resources");
            Path path = store.file(resource);
            CompletableFuture.supplyAsync(() -> {
                try (var input = Files.newInputStream(path)) {
                    String extension = resource.path().substring(resource.path().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    ImageLoader loader = switch (extension) {
                        case "webp" -> ImageUtils.WEBP;
                        case "apng" -> ImageUtils.APNG;
                        case "png", "jpg", "jpeg", "gif", "bmp" -> ImageUtils.DEFAULT;
                        default -> throw new IOException("不支持的图片格式：" + extension);
                    };
                    return loader.load(input, 1400, 0, true, true);
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }).whenComplete((@Nullable Image loaded, @Nullable Throwable failure) -> Platform.runLater(() -> {
                if (failure != null || loaded == null) {
                    status.setText("图片加载失败：" + (alt.isEmpty() ? node.getDestination() : alt));
                    return;
                }
                ImageView view = new ImageView(loaded);
                view.setPreserveRatio(true);
                view.setSmooth(true);
                view.setAccessibleText(alt);
                view.fitWidthProperty().bind(Bindings.min(loaded.getWidth(), Bindings.max(50, box.widthProperty().subtract(4))));
                box.getChildren().setAll(view);
                if (!alt.isBlank()) {
                    Label caption = new Label(alt);
                    caption.setWrapText(true);
                    box.getChildren().add(caption);
                }
            }));
        } catch (IOException e) {
            status.setText("图片不可用：" + e.getMessage());
        }
        return box;
    }

    /// Shows the entire code block without adding a nested document scrollbar.
    private static Label code(String literal) {
        Label code = new Label(literal.stripTrailing());
        code.setWrapText(true);
        code.setMinWidth(0);
        code.setMinHeight(Region.USE_PREF_SIZE);
        code.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-padding: 10;"
                + " -fx-background-color: -monet-surface-container-high; -fx-background-radius: 4;");
        code.setMaxWidth(Double.MAX_VALUE);
        return code;
    }

    /// Displays GFM table cells with wrapping, equal-width columns and themed borders.
    private GridPane table(TableBlock block) {
        List<TableRow> rows = new ArrayList<>();
        collectRows(block, rows);
        GridPane grid = new GridPane();
        grid.setMaxWidth(Double.MAX_VALUE);
        int rowIndex = 0;
        int columns = 0;
        for (TableRow row : rows) {
            int column = 0;
            for (@Nullable Node child = row.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof TableCell cell) {
                    TextFlow text = flow();
                    text.setPadding(new Insets(7));
                    text.setStyle("-fx-border-color: -monet-outline-variant; -fx-border-width: 0.5;");
                    inline(cell, text, 14, cell.isHeader(), false, false);
                    grid.add(text, column++, rowIndex);
                }
            }
            columns = Math.max(columns, column);
            rowIndex++;
        }
        for (int i = 0; i < columns; i++) {
            ColumnConstraints constraint = new ColumnConstraints();
            constraint.setPercentWidth(100.0 / columns);
            grid.getColumnConstraints().add(constraint);
        }
        return grid;
    }

    /// Collects table rows under header and body groups without assuming a fixed child depth.
    private static void collectRows(Node node, List<TableRow> rows) {
        if (node instanceof TableRow row) {
            rows.add(row);
            return;
        }
        for (@Nullable Node child = node.getFirstChild(); child != null; child = child.getNext())
            collectRows(child, rows);
    }

    /// Opens cached documents or web links using the explicit per-instance policy.
    private Hyperlink link(String label, String destination) {
        Hyperlink link = new Hyperlink(label);
        link.setWrapText(true);
        link.setStyle("-fx-font-size: 14px; -fx-padding: 0;");
        link.setOnAction(event -> {
            try {
                URI target = ContentDocuments.link(root, manifest, item, store, endpoint, destination);
                if (!"file".equalsIgnoreCase(target.getScheme())) {
                    FXUtils.openLink(target.toASCIIString());
                    return;
                }
                // Pass the decoded Unicode file path to the file-opening API, never to a URL command line.
                Path file = Path.of(target);
                link.setDisable(true);
                CompletableFuture.runAsync(() -> {
                    try {
                        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
                            throw new IOException("系统不支持打开本地文档，请检查默认应用");
                        Desktop.getDesktop().open(file.toFile());
                    } catch (IOException | RuntimeException e) {
                        throw new CompletionException(e);
                    }
                }).whenComplete((@Nullable Void ignored, @Nullable Throwable failure) -> Platform.runLater(() -> {
                    link.setDisable(false);
                    if (failure != null) {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot open instance document " + file, cause);
                        error.accept("无法打开本地文档：" + cause.getMessage());
                        @Nullable Path folder = file.getParent();
                        if (folder != null) {
                            documentFolder.setOnAction(action -> FXUtils.openFolder(folder));
                            documentFolder.setVisible(true);
                            documentFolder.setManaged(true);
                        }
                    }
                }));
            } catch (IOException | RuntimeException e) {
                error.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
        return link;
    }

    /// Creates a flow that can shrink within a card or dialog.
    private static TextFlow flow() {
        TextFlow flow = new TextFlow();
        flow.setMinWidth(0);
        flow.setLineSpacing(4);
        return flow;
    }

    /// Creates theme-aware literal text with nested inline font attributes.
    private static Text text(String value, int size, boolean bold, boolean italic, boolean strike, boolean code) {
        Text text = new Text(value);
        text.setStyle("-fx-fill: -monet-on-surface; -fx-font-size: " + size + "px;"
                + (bold ? "-fx-font-weight: bold;" : "") + (italic ? "-fx-font-style: italic;" : "")
                + (code ? "-fx-font-family: monospace;" : ""));
        text.setStrikethrough(strike);
        return text;
    }

    /// Extracts readable labels from nested link and image description nodes.
    private static String plain(Node parent) {
        StringBuilder text = new StringBuilder();
        for (@Nullable Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text literal)
                text.append(literal.getLiteral());
            else if (child instanceof Code code)
                text.append(code.getLiteral());
            else
                text.append(plain(child));
        }
        return text.toString();
    }
}
