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
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.StandardCharsets;

/// Small public-manifest fixtures shared by HTTP and desktop integration tests.
@NotNullByDefault
final class ContentFixtures {
    /// Logical Markdown path used by the fixtures.
    static final String BODY = "announcements/news.md";
    /// Logical image path relative to the fixture Markdown document.
    static final String IMAGE = "announcements/assets/banner.png";

    /// Prevents construction.
    private ContentFixtures() { }

    /// Builds an instance-bound manifest with no optional content.
    static JsonObject basic(String key) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("instanceKey", key);
        JsonObject agreement = new JsonObject();
        agreement.addProperty("id", "terms");
        agreement.addProperty("revision", 1);
        agreement.addProperty("title", "实例用户协议");
        agreement.addProperty("content", "阅读并同意此实例协议后启动。拒绝只取消本次启动。");
        root.add("agreement", agreement);
        root.add("notices", new JsonArray());
        root.add("announcements", new JsonArray());
        root.add("files", new JsonObject());
        return root;
    }

    /// Adds one Markdown announcement and an optional indexed image.
    static JsonObject markdown(String key, String body, byte[] image) {
        JsonObject root = basic(key);
        JsonObject item = new JsonObject();
        item.addProperty("id", "news");
        item.addProperty("revision", 1);
        item.addProperty("title", "服务器公告");
        item.addProperty("contentFile", BODY);
        JsonArray resources = new JsonArray();
        if (image.length > 0) {
            resources.add(IMAGE);
            file(root, IMAGE, image);
        }
        item.add("resources", resources);
        root.getAsJsonArray("announcements").add(item);
        file(root, BODY, body.getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /// Indexes exact fixture bytes with a SHA-256 digest.
    static void file(JsonObject root, String path, byte[] bytes) {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("sha256", RemoteContent.digest(bytes));
        root.getAsJsonObject("files").add(path, descriptor);
    }
}
