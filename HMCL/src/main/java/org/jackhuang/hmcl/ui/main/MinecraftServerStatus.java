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
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

/// Queries Minecraft Java Edition servers using the status handshake and Ping/Pong.
@NotNullByDefault
final class MinecraftServerStatus {
    /// Default port for addresses without a port or an SRV record.
    private static final int DEFAULT_PORT = 25565;
    /// TCP connection and read timeout in milliseconds.
    private static final int TIMEOUT_MILLIS = 3000;
    /// Total time available for DNS lookup, the status exchange and Ping/Pong.
    private static final int QUERY_TIMEOUT_MILLIS = 8000;
    /// How long the first SRV endpoint gets before a backup can start in parallel.
    private static final int BACKUP_DELAY_MILLIS = 300;
    /// Bounds concurrent probes even when the system resolver cannot be interrupted.
    private static final ThreadPoolExecutor QUERY_EXECUTOR = new ThreadPoolExecutor(
            0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), Lang.counterThreadFactory("Server status", true));
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

    /// Queries a server off the UI thread and completes within the total time budget.
    static CompletableFuture<@Nullable Result> query(String address) {
        return query(address, QUERY_TIMEOUT_MILLIS, InetSocketAddress::new);
    }

    /// Starts a bounded probe with the given address resolver and time budget.
    static CompletableFuture<@Nullable Result> query(String address, int timeoutMillis,
                                                     BiFunction<String, Integer, InetSocketAddress> resolveAddress) {
        return query(address, timeoutMillis, resolveAddress, MinecraftServerStatus::lookupSrv);
    }

    /// Allows both DNS steps to be supplied independently while keeping the normal query path.
    static CompletableFuture<@Nullable Result> query(String address, int timeoutMillis,
                                                     BiFunction<String, Integer, InetSocketAddress> resolveAddress,
                                                     Function<String, @Nullable Attribute> lookupRecords) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var race = new EndpointRace(deadline, resolveAddress);
        race.completion.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        race.completion.whenComplete((result, error) -> race.closeConnections(null));
        try {
            QUERY_EXECUTOR.execute(() -> race.run(address, lookupRecords));
        } catch (RejectedExecutionException e) {
            race.completion.complete(null);
        }
        return race.completion.exceptionally(error -> error instanceof TimeoutException ? race.getWinningStatus() : null);
    }

    /// Coordinates up to two workers over the same group of SRV endpoints.
    @NotNullByDefault
    private static final class EndpointRace {
        /// Shared deadline for DNS, connecting, status and Pong.
        private final long deadline;
        /// Looks up each endpoint's IP before attempting a TCP connection.
        private final BiFunction<String, Integer, InetSocketAddress> resolveAddress;
        /// Completes after the winning attempt or when all attempts fail or time out.
        private final CompletableFuture<@Nullable Result> completion = new CompletableFuture<>();
        /// Open sockets to close when another endpoint wins or the query expires.
        private final Set<Socket> connections = new HashSet<>(2);
        /// The first valid status, retained if the deadline expires during its Ping/Pong.
        private @Nullable Result winningStatus;
        /// Index of the next endpoint that neither worker has attempted.
        private int nextEndpoint;
        /// Number of endpoints that failed before returning a valid status.
        private int failedEndpoints;

        /// Initializes a single query with its deadline and address resolver.
        EndpointRace(long deadline, BiFunction<String, Integer, InetSocketAddress> resolveAddress) {
            this.deadline = deadline;
            this.resolveAddress = resolveAddress;
        }

        /// Parses the saved address, looks up its SRV group and starts the first worker.
        void run(String address, Function<String, @Nullable Attribute> lookupRecords) {
            try {
                remainingMillis(deadline);
                ServerAddress parsed = ServerAddress.parse(address.strip());
                String host = parsed.host().indexOf(':') >= 0 ? parsed.host() : IDN.toASCII(parsed.host());
                int port = parsed.port() == -1 ? DEFAULT_PORT : parsed.port();
                if (host.isBlank() || host.length() > 255 || port == 0)
                    throw new IOException("Invalid Minecraft server address");

                ServerAddress original = new ServerAddress(host, port);
                @Unmodifiable List<ServerAddress> redirected = parsed.port() == -1 && host.indexOf(':') < 0
                        && !host.matches("[0-9.]+") ? selectSrvEndpoints(lookupRecords.apply(host)) : List.of();
                @Unmodifiable List<ServerAddress> endpoints = redirected.isEmpty() ? List.of(original) : redirected;
                if (endpoints.size() > 1) {
                    CompletableFuture.delayedExecutor(BACKUP_DELAY_MILLIS, TimeUnit.MILLISECONDS, Runnable::run)
                            .execute(() -> startBackup(original, endpoints));
                }
                tryEndpoints(original, endpoints);
            } catch (IOException | RuntimeException e) {
                completion.completeExceptionally(e);
            }
        }

        /// Starts a second worker only if no valid status has arrived and an endpoint remains untried.
        private void startBackup(ServerAddress original, @Unmodifiable List<ServerAddress> endpoints) {
            synchronized (this) {
                if (!canTry() || nextEndpoint >= endpoints.size())
                    return;
            }
            try {
                QUERY_EXECUTOR.execute(() -> tryEndpoints(original, endpoints));
            } catch (RejectedExecutionException ignored) {
                // The first worker can still advance through the remaining endpoints.
            }
        }

        /// Takes a new endpoint immediately after a failure, sharing progress with the other worker.
        private void tryEndpoints(ServerAddress original, @Unmodifiable List<ServerAddress> endpoints) {
            @Nullable ServerAddress endpoint;
            while ((endpoint = takeEndpoint(endpoints)) != null) {
                try {
                    @Nullable Result result = probe(original, endpoint);
                    if (result != null)
                        completion.complete(result);
                    return; // This attempt won, or another worker already won or timed out.
                } catch (IOException | RuntimeException e) {
                    if (recordFailure(endpoints.size()))
                        completion.complete(null);
                }
            }
        }

        /// Checks for a win or expiry while holding the race monitor.
        private boolean canTry() {
            return winningStatus == null && !completion.isDone() && deadline - System.nanoTime() > 0;
        }

        /// Assigns each endpoint to only one worker.
        private synchronized @Nullable ServerAddress takeEndpoint(@Unmodifiable List<ServerAddress> endpoints) {
            return canTry() && nextEndpoint < endpoints.size() ? endpoints.get(nextEndpoint++) : null;
        }

        /// Reports exhaustion only once every endpoint has failed.
        private synchronized boolean recordFailure(int total) {
            return ++failedEndpoints == total;
        }

        /// Rejects sockets whose address resolution returned after another endpoint won or time expired.
        private synchronized boolean register(Socket socket) {
            return canTry() && connections.add(socket);
        }

        /// Removes a socket after its attempt finishes.
        private synchronized void unregister(Socket socket) {
            connections.remove(socket);
        }

        /// Claims the first valid status before waiting for that same connection's Pong.
        private synchronized boolean claimStatus(Result status) {
            if (!canTry())
                return false;
            winningStatus = status;
            return true;
        }

        /// Returns the status to show if Pong could not be measured before the deadline.
        private synchronized @Nullable Result getWinningStatus() {
            return winningStatus;
        }

        /// Closes all registered connections other than the winner, if one is still measuring latency.
        private void closeConnections(@Nullable Socket keep) {
            @Unmodifiable List<Socket> open;
            synchronized (this) {
                open = List.copyOf(connections);
            }
            for (Socket socket : open) {
                if (socket != keep) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // The attempt also closes its socket on exit.
                    }
                }
            }
        }

        /// Probes the endpoint using the saved address in the handshake.
        private @Nullable Result probe(ServerAddress original, ServerAddress endpoint) throws IOException {
            InetSocketAddress socketAddress = resolveAddress.apply(endpoint.host(), endpoint.port());
            Socket socket = new Socket();
            try (socket) {
                if (!register(socket))
                    return null;
                socket.connect(socketAddress, Math.min(TIMEOUT_MILLIS, remainingMillis(deadline)));
                socket.setSoTimeout(TIMEOUT_MILLIS);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                DataInputStream input = new DataInputStream(socket.getInputStream());
                writeStatusRequest(output, original);

                JsonObject status = readStatus(input);
                @Nullable JsonElement players = status.get("players");
                @Nullable JsonObject counts = players instanceof JsonObject object ? object : null;
                OptionalInt online = counts == null ? OptionalInt.empty() : readCount(counts, "online");
                OptionalInt capacity = counts == null ? OptionalInt.empty() : readCount(counts, "max");
                if (!claimStatus(new Result(online, capacity, OptionalLong.empty())))
                    return null;
                closeConnections(socket);
                return new Result(online, capacity, readLatency(input, output));
            } finally {
                unregister(socket);
            }
        }
    }

    /// Sends the handshake and status request, preserving the original virtual host across SRV redirects.
    private static void writeStatusRequest(DataOutputStream output, ServerAddress original) throws IOException {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        DataOutputStream fields = new DataOutputStream(handshake);
        byte[] hostBytes = original.host().getBytes(StandardCharsets.UTF_8);
        writeVarInt(fields, 0);
        writeVarInt(fields, STATUS_PROTOCOL);
        writeVarInt(fields, hostBytes.length);
        fields.write(hostBytes);
        fields.writeShort(original.port());
        writeVarInt(fields, 1);
        writePacket(output, handshake.toByteArray());
        writePacket(output, new byte[]{0});
    }

    /// Checks the deadline after blocking operations and prevents a zero-millisecond socket timeout.
    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
            throw new SocketTimeoutException("Minecraft server status query timed out");
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    /// Retrieves the Minecraft service records for a hostname.
    private static @Nullable Attribute lookupSrv(String host) {
        Hashtable<String, String> options = new Hashtable<>();
        options.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        options.put("com.sun.jndi.dns.timeout.initial", "1000");
        options.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            DirContext context = new InitialDirContext(options);
            try {
                return context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"}).get("SRV");
            } finally {
                context.close();
            }
        } catch (NamingException ignored) {
            return null;
        }
    }

    /// Keeps unique endpoints in the best priority group, preserving DNS answer order within it.
    private static @Unmodifiable List<ServerAddress> selectSrvEndpoints(@Nullable Attribute records) {
        if (records == null)
            return List.of();

        int bestPriority = Integer.MAX_VALUE;
        Set<ServerAddress> endpoints = new LinkedHashSet<>();
        for (int i = 0; i < records.size(); i++) {
            try {
                String[] fields = records.get(i).toString().strip().split("\\s+");
                if (fields.length != 4 || fields[3].equals("."))
                    continue;
                int priority = Integer.parseInt(fields[0]);
                int port = Integer.parseInt(fields[2]);
                if (priority < 0 || priority > 65535 || priority > bestPriority || port <= 0 || port > 65535)
                    continue;
                if (priority < bestPriority) {
                    bestPriority = priority;
                    endpoints.clear();
                }
                endpoints.add(new ServerAddress(fields[3], port));
            } catch (NamingException | NumberFormatException ignored) {
                // A malformed entry does not hide other advertised endpoints.
            }
        }
        return List.copyOf(endpoints);
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
