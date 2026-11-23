package ua.zentix.incoming.client.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordedBurstsTest {
    @Test
    void coldSeekAcrossSnapshotUsesOriginalPacketPosition(@TempDir Path temp) throws IOException {
        net.minecraft.SharedConstants.tryDetectVersion();
        if (net.neoforged.fml.loading.LoadingModList.get() == null)
            net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(),
                    java.util.List.of(), java.util.List.of(), java.util.Map.of());
        net.minecraft.server.Bootstrap.bootStrap();
        // The short 3.1.0 birth packet. Detonation id and game time are the first varint and the long after geometry.
        byte[] birth = java.util.HexFormat.of().parseHex(Files.readString(
                Path.of("src/test/resources/s2c-golden/nuke_detonation.1.hex")).replaceAll("\\s+", ""));
        var bytes = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(birth));
        int id = bytes.readVarInt();
        bytes.skipBytes(5 * Double.BYTES + 1);
        long gameTime = bytes.readLong();
        var payload = new ByteArrayOutputStream();
        var packet = new DataOutputStream(payload);
        packet.writeByte(1);
        string(packet, "incoming:nuke_detonation");
        packet.write(birth);
        var time = new ByteArrayOutputStream();
        var clock = new DataOutputStream(time);
        clock.writeByte(0x64);
        clock.writeLong(gameTime - 40);
        clock.writeLong(0);

        Path archive = temp.resolve("clock.zip");
        UUID uuid = UUID.randomUUID();
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("metadata.json"));
            zip.write(("{\"uuid\":\"" + uuid + "\",\"chunks\":{\"a\":{\"duration\":4},\"b\":{\"duration\":2}}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String name : new String[]{"a", "b"}) {
                zip.putNextEntry(new ZipEntry(name));
                var out = new DataOutputStream(zip);
                out.writeInt(0xd780e884);
                out.writeByte(3);
                string(out, "flashback:action/next_tick");
                string(out, "flashback:action/game_packet");
                string(out, "flashback_neoforge_fixed:action/modded_payload_v2_optional");
                var snapshot = new ByteArrayOutputStream();
                action(new DataOutputStream(snapshot), 1, time.toByteArray());
                action(new DataOutputStream(snapshot), 2, payload.toByteArray());
                out.writeInt(snapshot.size());
                snapshot.writeTo(out);
                action(out, 0, new byte[0]);
                action(out, 0, new byte[0]);
                if (name.equals("a")) {
                    action(out, 2, payload.toByteArray());
                    action(out, 0, new byte[0]);
                    action(out, 0, new byte[0]);
                }
            }
        }
        try (var timeline = new RecordedBursts(archive, uuid)) {
            assertEquals(3, timeline.birth(id, gameTime, 999));
            assertEquals(41, timeline.birth(id + 2, gameTime + 1, 999));
            assertEquals(3, timeline.birth(id, gameTime, -100));
            assertEquals(-10, timeline.birth(id + 1, gameTime - 50, 999));
        }
    }

    private static void string(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static void action(DataOutputStream out, int kind, byte[] bytes) throws IOException {
        out.writeByte(kind);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
