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

/// Verifies the end-to-end status deadline without relying on an external DNS resolver or server.
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
                input.skipNBytes(input.readUnsignedByte()); // Handshake.
                input.skipNBytes(input.readUnsignedByte()); // Status request.
                byte[] json = "{\"players\":{\"online\":7,\"max\":32}}".getBytes(StandardCharsets.UTF_8);
                output.writeByte(json.length + 2);
                output.writeByte(0);
                output.writeByte(json.length);
                output.write(json);
                output.flush();
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

    /// Opens a loopback-only server with a bounded wait for the test client.
    private static ServerSocket listen() throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(4000);
        return server;
    }
}
