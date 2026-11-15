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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.naming.directory.BasicAttribute;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies deadlines and SRV fallback without relying on external DNS or servers.
@NotNullByDefault
final class MinecraftServerStatusTest {
    /// Completes while DNS is still pending and discards a late resolution before it can connect.
    @Test
    void timesOutDuringResolutionAndSkipsLateConnection() throws Exception {
        try (ServerSocket server = listen()) {
            InetSocketAddress endpoint = new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
            CountDownLatch resolving = new CountDownLatch(1);
            CountDownLatch releaseResolution = new CountDownLatch(1);
            var result = MinecraftServerStatus.query("example.invalid:" + server.getLocalPort(), 1000, (host, port) -> {
                resolving.countDown();
                try {
                    releaseResolution.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return endpoint;
            });
            try {
                assertTrue(resolving.await(3, TimeUnit.SECONDS));
                assertNull(result.get(3, TimeUnit.SECONDS));
            } finally {
                releaseResolution.countDown();
            }

            server.setSoTimeout(500);
            assertThrows(SocketTimeoutException.class, () -> {
                try (Socket accepted = server.accept()) {
                    fail("A query must not connect after its DNS deadline");
                }
            });
        }
    }

    /// Stops an outstanding socket read at the deadline while retaining the status already received.
    @Test
    void keepsPlayerCountsWhenTheDeadlineExpiresWaitingForPong() throws Exception {
        try (ServerSocket server = listen()) {
            int port = server.getLocalPort();
            var result = MinecraftServerStatus.query("127.0.0.1:" + port, 1500, InetSocketAddress::new);
            try (Socket client = server.accept()) {
                client.setSoTimeout(4000);
                var input = new DataInputStream(client.getInputStream());
                var output = new DataOutputStream(client.getOutputStream());
                readRequest(input);
                sendStatus(output, 7);
                input.skipNBytes(input.readUnsignedByte()); // Ping. Leave it unanswered.

                @Nullable MinecraftServerStatus.Result status = result.get(3, TimeUnit.SECONDS);
                assertNotNull(status);
                assertEquals(7, status.online().orElseThrow());
                assertEquals(32, status.capacity().orElseThrow());
                assertTrue(status.latencyMillis().isEmpty());
                assertEquals(-1, input.read(), "Expired query must close its socket");
            }
        }
    }

    /// Starts the backup while the primary is still waiting for status, then closes the loser before measuring Pong.
    @Test
    void usesAResponsiveSrvBackupAndClosesTheStalledPrimary() throws Exception {
        try (ServerSocket primary = listen(); ServerSocket backup = listen()) {
            var records = new BasicAttribute("SRV", true);
            records.add("10 0 " + primary.getLocalPort() + " primary.example.invalid.");
            records.add("10 0 " + backup.getLocalPort() + " backup.example.invalid.");
            var query = MinecraftServerStatus.query("play.example.invalid", 5000,
                    (host, port) -> new InetSocketAddress("127.0.0.1", port), host -> records);
            try (Socket primaryClient = primary.accept()) {
                primaryClient.setSoTimeout(1500);
                var primaryInput = new DataInputStream(primaryClient.getInputStream());
                readRequest(primaryInput);
                // The primary gives no response, but the backup must connect well before its 3 s read timeout.
                backup.setSoTimeout(1800);
                try (Socket backupClient = backup.accept()) {
                    backupClient.setSoTimeout(2500);
                    var backupInput = new DataInputStream(backupClient.getInputStream());
                    var backupOutput = new DataOutputStream(backupClient.getOutputStream());
                    readRequest(backupInput);
                    sendStatus(backupOutput, 12);
                    assertEquals(-1, primaryInput.read());
                    echoPing(backupInput, backupOutput);
                }
            }
            @Nullable MinecraftServerStatus.Result result = query.get(3, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(12, result.online().orElseThrow());
            assertTrue(result.latencyMillis().isPresent());
        }
    }

    /// A valid primary status suppresses backups even when the winning connection has not returned Pong yet.
    @Test
    void doesNotStartBackupAfterReceivingPrimaryStatus() throws Exception {
        try (ServerSocket primary = listen(); ServerSocket backup = listen()) {
            var records = new BasicAttribute("SRV", true);
            records.add("20 0 " + backup.getLocalPort() + " lower-priority.example.invalid.");
            records.add("10 0 " + primary.getLocalPort() + " primary.example.invalid.");
            records.add("10 0 " + backup.getLocalPort() + " backup.example.invalid.");
            var query = MinecraftServerStatus.query("play.example.invalid", 5000,
                    (host, port) -> new InetSocketAddress("127.0.0.1", port), host -> records);
            try (Socket client = primary.accept()) {
                client.setSoTimeout(2500);
                var input = new DataInputStream(client.getInputStream());
                var output = new DataOutputStream(client.getOutputStream());
                readRequest(input);
                sendStatus(output, 5);
                // Hold Pong past the hedge delay; the primary status must already have won.
                backup.setSoTimeout(650);
                assertThrows(SocketTimeoutException.class, () -> {
                    try (Socket accepted = backup.accept()) {
                        fail("The backup must not be contacted after the primary sends status");
                    }
                });
                echoPing(input, output);
            }
            @Nullable MinecraftServerStatus.Result result = query.get(3, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(5, result.online().orElseThrow());
            assertTrue(result.latencyMillis().isPresent());
        }
    }

    /// Worker failures leave other same-priority candidates eligible and keep lower-priority groups out of the race.
    @Test
    void triesTheNextSrvCandidateAfterLookupFailure() throws Exception {
        try (ServerSocket backup = listen()) {
            var records = new BasicAttribute("SRV", true);
            records.add("20 0 " + backup.getLocalPort() + " lower-priority.example.invalid.");
            records.add("10 0 25565 unavailable.example.invalid.");
            records.add("10 0 " + backup.getLocalPort() + " backup.example.invalid.");
            var query = MinecraftServerStatus.query("play.example.invalid", 5000, (host, port) -> {
                if (host.equals("unavailable.example.invalid."))
                    return InetSocketAddress.createUnresolved(host, port);
                assertEquals("backup.example.invalid.", host);
                return new InetSocketAddress("127.0.0.1", port);
            }, host -> records);
            try (Socket client = backup.accept()) {
                client.setSoTimeout(2500);
                var input = new DataInputStream(client.getInputStream());
                var output = new DataOutputStream(client.getOutputStream());
                readRequest(input);
                sendStatus(output, 8);
                echoPing(input, output);
            }
            @Nullable MinecraftServerStatus.Result result = query.get(3, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(8, result.online().orElseThrow());
        }
    }

    /// Reads the two short frames the probes send to the fixture before a status response.
    private static void readRequest(DataInputStream input) throws IOException {
        input.skipNBytes(input.readUnsignedByte()); // Handshake.
        input.skipNBytes(input.readUnsignedByte()); // Status request.
    }

    /// Sends a valid, single-byte-length Minecraft status frame for a distinguishable backend.
    private static void sendStatus(DataOutputStream output, int online) throws IOException {
        byte[] json = ("{\"players\":{\"online\":" + online + ",\"max\":32}}").getBytes(StandardCharsets.UTF_8);
        output.writeByte(json.length + 2);
        output.writeByte(0);
        output.writeByte(json.length);
        output.write(json);
        output.flush();
    }

    /// Returns Pong on the same connection with the original Ping payload.
    private static void echoPing(DataInputStream input, DataOutputStream output) throws IOException {
        int length = input.readUnsignedByte();
        assertEquals(9, length);
        byte[] ping = new byte[length];
        input.readFully(ping);
        output.writeByte(length);
        output.write(ping);
        output.flush();
    }

    /// Opens a loopback-only server with a bounded wait for the test client.
    private static ServerSocket listen() throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(4000);
        return server;
    }
}
