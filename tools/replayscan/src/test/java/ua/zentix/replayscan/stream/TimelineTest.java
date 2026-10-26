package ua.zentix.replayscan.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineTest {
    private static final List<String> NAMES = List.of(Timeline.NEXT_TICK, "flashback:action/game_packet", "x:action/unknown_optional");

    @TempDir
    Path dir;

    /** Action tick matches the replay server: after the (t−1)-th next_tick is tick t; first chunk snapshot is tick 0. */
    @Test
    void ticksFollowReplayServer() throws IOException {
        Path zip = replay(false);
        List<String> seen = new ArrayList<>();
        try (ReplayFile f = ReplayFile.open(zip)) {
            assertEquals(5, f.totalTicks());
            Timeline t = new Timeline(f);
            for (Timeline.Action a; (a = t.next()) != null; ) {
                if (!a.name().equals(NAMES.get(1))) continue;
                seen.add(a.tick() + (a.snapshot() ? "s" : "") + ":" + new String(a.data(), a.off(), a.len(), StandardCharsets.UTF_8));
            }
            assertTrue(t.warnings().isEmpty(), t.warnings().toString());
            assertEquals(5, t.ticks());
        }
        // second chunk without forcePlaySnapshot: its snapshot is not read
        assertEquals(List.of("0s:snap0", "1:a", "1:b", "2:c", "4:d", "5:e"), seen);
    }

    @Test
    void forcedSnapshotJoinsTheTickOfTheSegmentStart() throws IOException {
        Path zip = replay(true);
        List<String> seen = new ArrayList<>();
        try (ReplayFile f = ReplayFile.open(zip)) {
            Timeline t = new Timeline(f);
            for (Timeline.Action a; (a = t.next()) != null; ) {
                if (a.name().equals(NAMES.get(1))) seen.add(a.tick() + (a.snapshot() ? "s" : "") + ":" + new String(a.data(), a.off(), a.len(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(List.of("0s:snap0", "1:a", "1:b", "2:c", "4s:snap1", "4:d", "5:e"), seen);
    }

    @Test
    void chunkCachePlaces() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int[] xz : new int[][]{{3, -7}, {-100000, 42}}) {
            byte[] packet = new byte[1 + 8 + 5];
            packet[0] = 0x27;
            putInt(packet, 1, xz[0]);
            putInt(packet, 5, xz[1]);
            byte[] size = new byte[4];
            putInt(size, 0, packet.length);
            out.writeBytes(size);
            out.writeBytes(packet);
        }
        long[] places = ReplayFile.chunkPlaces(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(2, places.length);
        assertEquals(ReplayFile.pack(3, -7), places[0]);
        assertEquals(ReplayFile.pack(-100000, 42), places[1]);
    }

    /** Two chunks of 3 and 2 ticks (next_tick at tick end): c0 is snapshot and a b | c | |, c1 is snapshot and d | e |. */
    private Path replay(boolean forceSecond) throws IOException {
        Path zip = dir.resolve("r.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("metadata.json"));
            z.write(("{\"name\":\"t\",\"total_ticks\":5,\"chunks\":{\"c0.flashback\":{\"duration\":3,\"forcePlaySnapshot\":false},"
                    + "\"c1.flashback\":{\"duration\":2,\"forcePlaySnapshot\":" + forceSecond + "}}}").getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("c0.flashback"));
            segment(z, List.of("snap0"), List.of("a", "b", "|", "c", "|", "|"));
            z.putNextEntry(new ZipEntry("c1.flashback"));
            segment(z, List.of("snap1"), List.of("d", "|", "e", "|"));
        }
        return zip;
    }

    private static void segment(OutputStream z, List<String> snapshot, List<String> actions) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] magic = new byte[4];
        putInt(magic, 0, ReplayFile.MAGIC);
        out.writeBytes(magic);
        varInt(out, NAMES.size());
        for (String n : NAMES) {
            byte[] b = n.getBytes(StandardCharsets.UTF_8);
            varInt(out, b.length);
            out.writeBytes(b);
        }
        ByteArrayOutputStream snap = new ByteArrayOutputStream();
        for (String s : snapshot) action(snap, s);
        // an unknown optional action can also occur in a snapshot
        action(snap, null);
        byte[] size = new byte[4];
        putInt(size, 0, snap.size());
        out.writeBytes(size);
        out.writeBytes(snap.toByteArray());
        for (String a : actions) action(out, a);
        z.write(out.toByteArray());
    }

    /** {@code "|"} means next_tick, {@code null} means unknown optional; otherwise game_packet with a string body. */
    private static void action(ByteArrayOutputStream out, String a) {
        byte[] body = a == null || a.equals("|") ? new byte[0] : a.getBytes(StandardCharsets.UTF_8);
        varInt(out, a == null ? 2 : a.equals("|") ? 0 : 1);
        byte[] size = new byte[4];
        putInt(size, 0, body.length);
        out.writeBytes(size);
        out.writeBytes(body);
    }

    private static void varInt(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
