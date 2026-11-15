/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.main;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/// Queries Minecraft Java Edition servers using the status handshake and Ping/Pong.
@NotNullByDefault
final class MinecraftServerStatus {
    /// Default port for addresses without a port or an SRV record.
    private static final int DEFAULT_PORT = 25565;
    /// TCP connection and read timeout in milliseconds.
    private static final int TIMEOUT_MILLIS = 3000;
    /// Maximum status frame size, including the JSON response and optional server icon.
    private static final int MAX_FRAME_BYTES = 128 * 1024;
    /// Protocol version used to request Java Edition status independently of the installed version.
    private static final int STATUS_PROTOCOL = -1;

    /// Status fields returned by a responding server.
    record Result(OptionalInt online, OptionalInt capacity, OptionalLong latencyMillis) {
    }

    /// Prevents construction of this protocol utility.
    private MinecraftServerStatus() {
    }

    /// Returns a server status, including any available player counts and latency.
    static Result query(String address) throws IOException {
        ServerAddress parsed = ServerAddress.parse(address.strip());
        String host = parsed.host().indexOf(':') >= 0 ? parsed.host() : IDN.toASCII(parsed.host());
        int port = parsed.port() == -1 ? DEFAULT_PORT : parsed.port();
        if (host.isBlank() || host.length() > 255 || port == 0)
            throw new IOException("Invalid Minecraft server address");

        @Nullable ServerAddress redirected = parsed.port() == -1 && host.indexOf(':') < 0
                && !host.matches("[0-9.]+") ? resolveSrv(host) : null;
        String endpoint = redirected == null ? host : redirected.host();
        int endpointPort = redirected == null ? port : redirected.port();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint, endpointPort), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream fields = new DataOutputStream(handshake);
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(fields, 0);
            writeVarInt(fields, STATUS_PROTOCOL);
            writeVarInt(fields, hostBytes.length);
            fields.write(hostBytes);
            fields.writeShort(port);
            writeVarInt(fields, 1);
            writePacket(output, handshake.toByteArray());
            writePacket(output, new byte[]{0});

            JsonObject status = readStatus(input);
            @Nullable JsonElement players = status.get("players");
            @Nullable JsonObject counts = players instanceof JsonObject object ? object : null;
            OptionalInt online = counts == null ? OptionalInt.empty() : readCount(counts, "online");
            OptionalInt capacity = counts == null ? OptionalInt.empty() : readCount(counts, "max");
            return new Result(online, capacity, readLatency(input, output));
        }
    }

    /// Looks up an SRV endpoint for a hostname, preferring records with the lowest priority value.
    private static @Nullable ServerAddress resolveSrv(String host) {
        Hashtable<String, String> options = new Hashtable<>();
        options.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        options.put("com.sun.jndi.dns.timeout.initial", "1000");
        options.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            DirContext context = new InitialDirContext(options);
            try {
                @Nullable Attribute records = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"}).get("SRV");
                if (records == null)
                    return null;

                int bestPriority = Integer.MAX_VALUE;
                @Nullable ServerAddress best = null;
                for (int i = 0; i < records.size(); i++) {
                    String[] fields = records.get(i).toString().strip().split("\\s+");
                    if (fields.length != 4 || fields[3].equals("."))
                        continue;

                    int priority = Integer.parseInt(fields[0]);
                    int port = Integer.parseInt(fields[2]);
                    if (priority < bestPriority && port > 0 && port <= 65535) {
                        bestPriority = priority;
                        best = new ServerAddress(fields[3], port);
                    }
                }
                return best;
            } finally {
                context.close();
            }
        } catch (NamingException | NumberFormatException ignored) {
            return null;
        }
    }

    /// Reads and validates the framed status response before decoding its UTF-8 JSON.
    private static JsonObject readStatus(DataInputStream input) throws IOException {
        try (DataInputStream response = readPacket(input, MAX_FRAME_BYTES)) {
            if (readVarInt(response) != 0)
                throw new IOException("Unexpected Minecraft status packet");
            int length = readVarInt(response);
            if (length <= 0 || length != response.available())
                throw new IOException("Invalid Minecraft status JSON length");
            JsonElement json = JsonParser.parseString(new String(response.readNBytes(length), StandardCharsets.UTF_8));
            if (json instanceof JsonObject object)
                return object;
            throw new IOException("Expected a Minecraft status JSON object");
        } catch (JsonParseException e) {
            throw new IOException("Invalid Minecraft status JSON", e);
        }
    }

    /// Reads a nonnegative player count when the status response includes one.
    private static OptionalInt readCount(JsonObject players, String key) {
        @Nullable JsonElement value = players.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try {
                int count = value.getAsBigDecimal().intValueExact();
                if (count >= 0)
                    return OptionalInt.of(count);
            } catch (NumberFormatException | ArithmeticException ignored) {
                // A malformed count does not invalidate the rest of the status response.
            }
        }
        return OptionalInt.empty();
    }

    /// Measures the round trip using an echoed payload, with a missing Pong leaving latency unknown.
    private static OptionalLong readLatency(DataInputStream input, DataOutputStream output) {
        try {
            long start = System.nanoTime();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(9);
            DataOutputStream ping = new DataOutputStream(bytes);
            ping.writeByte(1);
            ping.writeLong(start);
            writePacket(output, bytes.toByteArray());
            try (DataInputStream pong = readPacket(input, 9)) {
                if (readVarInt(pong) == 1 && pong.readLong() == start && pong.available() == 0)
                    return OptionalLong.of(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        } catch (IOException ignored) {
            // The server already returned a status, so it can still be shown as online.
        }
        return OptionalLong.empty();
    }

    /// Reads one complete length-prefixed packet with a bound on allocation.
    private static DataInputStream readPacket(DataInputStream input, int limit) throws IOException {
        int length = readVarInt(input);
        if (length <= 0 || length > limit)
            throw new IOException("Invalid Minecraft packet length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length)
            throw new EOFException("Incomplete Minecraft packet");
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    /// Writes a complete Minecraft packet with its length prefix.
    private static void writePacket(DataOutputStream output, byte[] bytes) throws IOException {
        writeVarInt(output, bytes.length);
        output.write(bytes);
        output.flush();
    }

    /// Writes a Minecraft variable-length integer.
    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            output.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    /// Reads a Minecraft variable-length integer with at most five bytes.
    private static int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            int current = input.readUnsignedByte();
            if (shift == 28 && current > 0x0f)
                throw new IOException("Invalid Minecraft VarInt");
            value |= (current & 0x7f) << shift;
            if ((current & 0x80) == 0)
                return value;
        }
        throw new IOException("Invalid Minecraft VarInt");
    }
}
