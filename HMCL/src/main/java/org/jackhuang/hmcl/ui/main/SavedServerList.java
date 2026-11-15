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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.main;

import net.jpountz.lz4.LZ4BlockInputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/// Reads just the fields needed by the homepage without allocating a complete NBT tree.
@NotNullByDefault
final class SavedServerList {
    /// Maximum on-disk bytes read even if the file grows after its attributes were checked.
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    /// Maximum total NBT bytes after decompression.
    static final int MAX_NBT_BYTES = 8 * 1024 * 1024;
    /// Maximum number of visible entries passed to JavaFX.
    static final int MAX_VISIBLE_SERVERS = 100;
    /// Maximum number of elements allowed in any NBT list.
    private static final int MAX_LIST_ENTRIES = 10_000;
    /// Maximum number of compound/list levels visited recursively.
    private static final int MAX_DEPTH = 32;
    /// Maximum text length retained for either field rendered in a menu row.
    private static final int MAX_FIELD_CHARS = 1024;
    /// NBT compound tag identifier.
    private static final int COMPOUND = 10;
    /// NBT list tag identifier.
    private static final int LIST = 9;
    /// NBT string tag identifier.
    private static final int STRING = 8;
    /// LZ4 block stream magic interpreted as a big-endian long.
    private static final long LZ4_MAGIC = ByteBuffer.wrap("LZ4Block".getBytes(StandardCharsets.US_ASCII)).getLong();
    /// LZ4 block header size including magic, token, two lengths and checksum.
    private static final int LZ4_HEADER_BYTES = 21;

    /// A file with no visible saved servers.
    static final Contents EMPTY = new Contents(List.of(), false);

    /// The name and address used by the selector and the status request.
    @NotNullByDefault
    record SavedServer(String name, String address) {
    }

    /// The displayed prefix of the file and whether more visible entries followed it.
    @NotNullByDefault
    record Contents(@Unmodifiable List<SavedServer> entries, boolean truncated) {
    }

    /// A resource limit was exceeded rather than encountering a temporary read error.
    @NotNullByDefault
    static final class LimitExceededException extends IOException {
        /// Records which resource limit was reached.
        LimitExceededException(String message) {
            super(message);
        }
    }

    /// Prevents construction of this parser utility.
    private SavedServerList() {
    }

    /// Reads a bounded snapshot of the file without relying on the earlier size check.
    static byte[] readBytes(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES)
                throw new LimitExceededException("Server list file is too large");
            return bytes;
        }
    }

    /// Parses a raw, GZIP, or LZ4 block NBT snapshot and validates the whole root.
    static Contents parse(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(decode(bytes))) {
            if (input.readUnsignedByte() != COMPOUND)
                throw new IOException("Expected a compound NBT root");
            skipString(input);
            Contents result = EMPTY;
            for (int type; (type = input.readUnsignedByte()) != 0; ) {
                if (input.readUTF().equals("servers")) {
                    if (type != LIST)
                        throw new IOException("Expected an NBT server list");
                    result = readServers(input);
                } else {
                    skipPayload(input, type, 2);
                }
            }
            return result;
        }
    }

    /// Fully decodes compressed data within the byte budget so the parser can check remaining bytes.
    private static ByteArrayInputStream decode(byte[] bytes) throws IOException {
        ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
        if (bytes.length == 0 || (bytes[0] != 0x1f && bytes[0] != 'L'))
            return raw;

        if (bytes[0] == 'L')
            checkLz4Blocks(bytes);
        try (InputStream decoded = bytes[0] == 'L'
                ? LZ4BlockInputStream.newBuilder().withStopOnEmptyBlock(true).build(raw)
                : new GZIPInputStream(raw)) {
            byte[] data = decoded.readNBytes(MAX_NBT_BYTES + 1);
            if (data.length > MAX_NBT_BYTES)
                throw new LimitExceededException("Decompressed server list is too large");
            return new ByteArrayInputStream(data);
        }
    }

    /// Checks declared LZ4 sizes before the decoder uses them to allocate block buffers.
    private static void checkLz4Blocks(byte[] bytes) throws IOException {
        ByteBuffer blocks = ByteBuffer.wrap(bytes);
        int total = 0;
        while (blocks.remaining() >= LZ4_HEADER_BYTES) {
            if (blocks.getLong() != LZ4_MAGIC)
                throw new IOException("Invalid LZ4 block header");
            blocks.get(); // Compression method and level are validated by the decoder.
            blocks.order(ByteOrder.LITTLE_ENDIAN);
            int compressed = blocks.getInt();
            int decompressed = blocks.getInt();
            blocks.getInt(); // Checksum.
            blocks.order(ByteOrder.BIG_ENDIAN);
            if (compressed < 0 || decompressed < 0 || compressed > blocks.remaining())
                throw new IOException("Invalid LZ4 block length");
            if (compressed == 0 && decompressed == 0)
                return;
            if (compressed == 0 || decompressed == 0)
                throw new IOException("Invalid empty LZ4 block");
            if (decompressed > MAX_NBT_BYTES - total)
                throw new LimitExceededException("Decompressed server list is too large");
            total += decompressed;
            blocks.position(blocks.position() + compressed);
        }
        throw new IOException("Missing LZ4 end marker");
    }

    /// Keeps at most the first 100 visible servers while still scanning the entire list for validity.
    private static Contents readServers(DataInputStream input) throws IOException {
        int type = input.readUnsignedByte();
        int length = readListLength(input, type);
        if (length != 0 && type != COMPOUND)
            throw new IOException("Expected server entries to be compounds");
        List<SavedServer> entries = new ArrayList<>();
        boolean truncated = false;
        for (int i = 0; i < length; i++) {
            @Nullable SavedServer server = readServer(input);
            if (server == null)
                continue;
            if (entries.size() < MAX_VISIBLE_SERVERS)
                entries.add(server);
            else
                truncated = true;
        }
        return new Contents(List.copyOf(entries), truncated);
    }

    /// Reads the three server fields and skips other tags without allocating their payloads.
    private static @Nullable SavedServer readServer(DataInputStream input) throws IOException {
        @Nullable String name = null;
        @Nullable String address = null;
        boolean hidden = false;
        for (int type; (type = input.readUnsignedByte()) != 0; ) {
            String key = input.readUTF();
            if (type == STRING && key.equals("name"))
                name = input.readUTF();
            else if (type == STRING && key.equals("ip"))
                address = input.readUTF().strip();
            else if (type == 1 && key.equals("hidden"))
                hidden = input.readByte() != 0;
            else
                skipPayload(input, type, 4);
        }
        if (hidden || name == null || address == null || address.isBlank())
            return null;
        if (name.length() > MAX_FIELD_CHARS || address.length() > MAX_FIELD_CHARS)
            throw new LimitExceededException("Server name or address is too long");
        return new SavedServer(name, address);
    }

    /// Skips a payload after checking bounds on nested tags and lengths.
    private static void skipPayload(DataInputStream input, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH)
            throw new LimitExceededException("Server list NBT is nested too deeply");
        switch (type) {
            case 1 -> input.skipNBytes(1); // Byte
            case 2 -> input.skipNBytes(2); // Short
            case 3, 5 -> input.skipNBytes(4); // Int, float
            case 4, 6 -> input.skipNBytes(8); // Long, double
            case 7 -> skipArray(input, 1);
            case STRING -> skipString(input);
            case LIST -> {
                int elementType = input.readUnsignedByte();
                int length = readListLength(input, elementType);
                for (int i = 0; i < length; i++)
                    skipPayload(input, elementType, depth + 1);
            }
            case COMPOUND -> {
                for (int child; (child = input.readUnsignedByte()) != 0; ) {
                    skipString(input);
                    skipPayload(input, child, depth + 1);
                }
            }
            case 11 -> skipArray(input, 4);
            case 12 -> skipArray(input, 8);
            default -> throw new IOException("Invalid NBT tag type");
        }
    }

    /// Checks a list header before visiting any elements.
    private static int readListLength(DataInputStream input, int elementType) throws IOException {
        int length = input.readInt();
        if (length < 0 || elementType > 12 || (length != 0 && elementType == 0))
            throw new IOException("Invalid NBT list header");
        if (length > MAX_LIST_ENTRIES)
            throw new LimitExceededException("NBT list has too many entries");
        if (length > input.available())
            throw new IOException("Truncated NBT list");
        return length;
    }

    /// Validates an array's declared size using long arithmetic before skipping its bytes.
    private static void skipArray(DataInputStream input, int elementBytes) throws IOException {
        long bytes = (long) input.readInt() * elementBytes;
        if (bytes < 0)
            throw new IOException("Invalid NBT array length");
        if (bytes > MAX_NBT_BYTES)
            throw new LimitExceededException("NBT array is too large");
        input.skipNBytes(bytes);
    }

    /// Skips the byte payload of an NBT modified UTF-8 string.
    private static void skipString(DataInputStream input) throws IOException {
        input.skipNBytes(input.readUnsignedShort());
    }
}
