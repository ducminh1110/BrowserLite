package com.browserlite.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/** Read-ahead against a local server that refuses its first link past 2 MB and drops one connection midway. */
public class ChunkCacheTest {
    private static final int SIZE = 5 * ChunkCache.CHUNK + 123_457;
    private final byte[] data = new byte[SIZE];
    private ServerSocket server;
    private String base;
    private final AtomicInteger requests = new AtomicInteger(), drops = new AtomicInteger(1);
    private File dir;

    @Before
    public void setUp() throws IOException {
        new Random(7).nextBytes(data);
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    final Socket s = server.accept();
                    new Thread(() -> handle(s)).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        base = "http://127.0.0.1:" + server.getLocalPort();
        dir = Files.createTempDirectory("chunks").toFile();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    /** One request per connection: enough of HTTP/1.1 for ranges, refusals and a dropped connection. */
    private void handle(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"));
            String first = in.readLine();
            if (first == null) return;
            String path = first.split(" ")[1];
            String range = null, line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
            }
            requests.incrementAndGet();
            OutputStream out = s.getOutputStream();
            long start = 0, end = SIZE - 1;
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-");
                start = Long.parseLong(p[0]);
                if (p.length > 1 && !p[1].isEmpty()) end = Math.min(end, Long.parseLong(p[1]));
            }
            // "/a" is a link the server stops honouring past 2 MB, "/gone" is refused outright.
            if (path.equals("/gone") || (path.equals("/a") && start >= 2 * ChunkCache.CHUNK)) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                return;
            }
            int len = (int) (end - start + 1);
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: bytes " + start + "-"
                    + end + "/" + SIZE + "\r\nContent-Length: " + len + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            if (start == 3L * ChunkCache.CHUNK && drops.getAndDecrement() > 0) {
                out.write(data, (int) start, len / 3); // connection lost halfway through this piece
                return;
            }
            out.write(data, (int) start, len);
            out.flush();
        } catch (IOException ignored) {
            // client went away
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    @Test
    public void renewsRefusedLinkAndSurvivesDrops() throws IOException {
        final AtomicInteger renewals = new AtomicInteger();
        ChunkCache c = new ChunkCache(new OkHttpClient(), base + "/a", "test", 0, dir, () -> {
            renewals.incrementAndGet();
            return new ChunkCache.Link(base + "/b", "test");
        }, 8, 16);
        try {
            assertEquals(SIZE, c.length(10_000));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            c.serve(0, SIZE - 1, out, 20_000);
            assertArrayEquals(data, out.toByteArray());
            assertEquals(1, renewals.get());
            assertTrue(c.bufferedUntil() >= SIZE - ChunkCache.CHUNK);

            ByteArrayOutputStream part = new ByteArrayOutputStream();
            c.serve(1_500_000, 4_200_000, part, 20_000);
            byte[] want = new byte[4_200_000 - 1_500_000 + 1];
            System.arraycopy(data, 1_500_000, want, 0, want.length);
            assertArrayEquals(want, part.toByteArray());
        } finally {
            c.close();
        }
        assertTrue(!dir.exists());
    }

    @Test
    public void keepsDiskUseBounded() throws IOException {
        ChunkCache c = new ChunkCache(new OkHttpClient(), base + "/b", "test", SIZE, dir, null, 2, 4);
        try {
            c.serve(0, SIZE - 1, new ByteArrayOutputStream(), 20_000);
            String[] left = dir.list();
            assertTrue(left != null && left.length <= 4);
        } finally {
            c.close();
        }
    }

    @Test
    public void failsWhenNoNewLink() {
        ChunkCache c = new ChunkCache(new OkHttpClient(), base + "/gone", "test", SIZE, dir, () -> null, 4, 8);
        try {
            c.serve(0, 1000, new ByteArrayOutputStream(), 20_000);
            fail("served a refused file");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("403"));
        } finally {
            c.close();
        }
    }
}
