/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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
 */
package org.jackhuang.hmcl.ui.main;

import net.jpountz.lz4.LZ4BlockOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Checks the resource limits and compatibility of the homepage server list reader.
@NotNullByDefault
final class SavedServerListTest {
    /// Preserves order and exact names, and applies the cap after filtering hidden entries.
    @Test
    void readsRawAndCompressedListsWithTheSameVisiblePrefix() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream nbt = new DataOutputStream(raw)) {
            writeRoot(nbt);
            writeTag(nbt, 9, "servers");
            nbt.writeByte(10);
            nbt.writeInt(102);
            for (int i = 0; i < 102; i++) {
                writeTag(nbt, 8, "name");
                nbt.writeUTF("服务器 " + i + " 🏠");
                writeTag(nbt, 8, "ip");
                nbt.writeUTF(" mc.example.org:" + (25565 + i) + " ");
                writeTag(nbt, 1, "hidden");
                nbt.writeByte(i == 0 ? 1 : 0);
                nbt.writeByte(0);
            }
            nbt.writeByte(0);
        }

        var contents = SavedServerList.parse(raw.toByteArray());
        assertEquals(100, contents.entries().size());
        assertEquals("服务器 1 🏠", contents.entries().get(0).name());
        assertEquals("mc.example.org:25665", contents.entries().get(99).address());
        assertTrue(contents.truncated());

        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (var output = new GZIPOutputStream(gzip)) {
            raw.writeTo(output);
        }
        assertEquals(contents, SavedServerList.parse(gzip.toByteArray()));

        ByteArrayOutputStream lz4 = new ByteArrayOutputStream();
        try (var output = new LZ4BlockOutputStream(lz4)) {
            raw.writeTo(output);
        }
        assertEquals(contents, SavedServerList.parse(lz4.toByteArray()));
    }

    /// Stops reading once the on-disk limit is exceeded.
    @Test
    void rejectsAnOversizedFile(@TempDir Path directory) throws IOException {
        Path path = directory.resolve("servers.dat");
        Files.write(path, new byte[SavedServerList.MAX_FILE_BYTES + 1]);
        assertThrows(SavedServerList.LimitExceededException.class, () -> SavedServerList.readBytes(path));
    }

    /// Rejects both supported compression formats if their decoded data exceeds the budget.
    @Test
    void rejectsOversizedDecompressedData() throws IOException {
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        ByteArrayOutputStream lz4 = new ByteArrayOutputStream();
        try (var gzipOutput = new GZIPOutputStream(gzip);
             var lz4Output = new LZ4BlockOutputStream(lz4)) {
            byte[] block = new byte[64 * 1024];
            for (int i = 0; i < SavedServerList.MAX_NBT_BYTES / block.length; i++) {
                gzipOutput.write(block);
                lz4Output.write(block);
            }
            gzipOutput.write(0);
            lz4Output.write(0);
        }
        assertThrows(SavedServerList.LimitExceededException.class, () -> SavedServerList.parse(gzip.toByteArray()));
        assertThrows(SavedServerList.LimitExceededException.class, () -> SavedServerList.parse(lz4.toByteArray()));
    }

    /// Rejects implausible length fields in a small input before reading or allocating their contents.
    @Test
    void rejectsOversizedDeclarationsAndDeepNesting() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream nbt = new DataOutputStream(raw)) {
            writeRoot(nbt);
            writeTag(nbt, 12, "ignored");
            nbt.writeInt(Integer.MAX_VALUE);
            assertThrows(SavedServerList.LimitExceededException.class, () -> SavedServerList.parse(raw.toByteArray()));

            raw.reset();
            writeRoot(nbt);
            writeTag(nbt, 9, "servers");
            nbt.writeByte(10);
            nbt.writeInt(Integer.MAX_VALUE);
            assertThrows(SavedServerList.LimitExceededException.class, () -> SavedServerList.parse(raw.toByteArray()));

            raw.reset();
            writeRoot(nbt);
            for (int i = 0; i < 40; i++)
                writeTag(nbt, 10, "nested");
            assertThrows(SavedServerList.LimitExceededException.class, () -> SavedServerList.parse(raw.toByteArray()));
        }
    }

    /// A partial save is a read error, so the card can retry it without reporting a resource limit.
    @Test
    void distinguishesTruncatedInputFromAResourceLimit() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream nbt = new DataOutputStream(raw)) {
            writeRoot(nbt);
            writeTag(nbt, 9, "servers");
            nbt.writeByte(10);
            nbt.writeInt(1);
        }
        IOException error = assertThrows(IOException.class, () -> SavedServerList.parse(raw.toByteArray()));
        assertFalse(error instanceof SavedServerList.LimitExceededException);
    }

    /// Starts a standard Java Edition NBT root with an empty name.
    private static void writeRoot(DataOutputStream nbt) throws IOException {
        writeTag(nbt, 10, "");
    }

    /// Writes a Java Edition named tag header.
    private static void writeTag(DataOutputStream nbt, int type, String name) throws IOException {
        nbt.writeByte(type);
        nbt.writeUTF(name);
    }
}
