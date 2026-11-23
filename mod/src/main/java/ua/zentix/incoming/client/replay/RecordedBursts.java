package ua.zentix.incoming.client.replay;

import com.google.common.io.ByteStreams;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipFile;

/** Recording positions of original packets. A snapshot can repeat a birth thousands of ticks after it happened. */
final class RecordedBursts implements AutoCloseable {
    private record Key(int id, long gameTime) {}
    private static final Map<Integer, PacketType<?>> CLOCK_PACKETS = new HashMap<>();
    static {
        GameProtocols.CLIENTBOUND_TEMPLATE.listPackets((type, index) -> {
            if (type.equals(CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD) || type.equals(GamePacketTypes.CLIENTBOUND_SET_TIME))
                CLOCK_PACKETS.put(index, type);
        });
    }
    private final Map<Key, Integer> births = new HashMap<>();
    private long initialTime = Long.MIN_VALUE;
    private boolean snapshotBaseline;
    private int requests;
    private boolean finished;
    private final Thread reader;

    RecordedBursts(Path archive, UUID recording) {
        reader = Thread.ofVirtual().name("Incoming replay clock index").start(() -> {
            try (var zip = new ZipFile(archive.toFile())) {
                var meta = zip.getEntry("metadata.json");
                if (meta == null) throw new IOException("missing metadata");
                try (var json = new InputStreamReader(zip.getInputStream(meta), StandardCharsets.UTF_8)) {
                    var root = JsonParser.parseReader(json).getAsJsonObject();
                    if (!recording.toString().equals(root.get("uuid").getAsString())) throw new IOException("recording UUID mismatch");
                    int start = 0;
                    for (var segment : root.getAsJsonObject("chunks").entrySet()) {
                        synchronized (this) {
                            while (start > 0 && requests == 0 && !finished) wait();
                            if (finished) break;
                        }
                        var entry = zip.getEntry(segment.getKey());
                        if (entry == null) throw new IOException("missing segment " + segment.getKey());
                        try (var in = new DataInputStream(new BufferedInputStream(zip.getInputStream(entry), 64 * 1024))) {
                            scan(in, start);
                        }
                        start += segment.getValue().getAsJsonObject().get("duration").getAsInt();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                if (!Thread.currentThread().isInterrupted()) Incoming.LOG.warn("Cannot index replay clock in {}", archive, e);
            } finally {
                synchronized (this) {
                    finished = true;
                    notifyAll();
                }
            }
        });
    }

    /** An identified birth is available immediately. The worker parks between segments when there is no demand. */
    synchronized double birth(int id, long gameTime, double fallback) {
        Key key = new Key(id, gameTime);
        requests++;
        notifyAll();
        try {
            while (!finished && !births.containsKey(key) && !(snapshotBaseline && gameTime < initialTime)) wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            requests--;
        }
        Integer tick = births.get(key);
        if (tick != null) return tick;
        return initialTime == Long.MIN_VALUE ? fallback : gameTime - initialTime;
    }

    @Override
    public synchronized void close() {
        reader.interrupt();
        finished = true;
        notifyAll();
    }

    private void scan(DataInputStream in, int start) throws IOException {
        if (in.readInt() != 0xd780e884) throw new IOException("unknown Flashback segment format");
        int count = varInt(in, in.readUnsignedByte());
        if (count < 0 || count > 4096) throw new IOException("invalid action count");
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            int size = varInt(in, in.readUnsignedByte());
            if (size < 0 || size > 32767) throw new IOException("invalid action name");
            names[i] = new String(in.readNBytes(size), StandardCharsets.UTF_8);
        }
        int snapshot = in.readInt();
        if (snapshot < 0) throw new IOException("invalid snapshot size");
        if (start == 0) actions(new DataInputStream(ByteStreams.limit(in, snapshot)), names, 0, true);
        else in.skipNBytes(snapshot);
        actions(in, names, start, false);
    }

    // A recording tick reads actions up to and including next_tick. Chunk caches and block data are never decoded.
    private void actions(DataInputStream in, String[] names, int tick, boolean snapshot) throws IOException {
        byte[] prefix = new byte[256];
        var bytes = new FriendlyByteBuf(Unpooled.wrappedBuffer(prefix));
        for (int first; !Thread.currentThread().isInterrupted() && (first = in.read()) >= 0;) {
            int id = varInt(in, first), size = in.readInt();
            if (id < 0 || id >= names.length || size < 0) throw new IOException("invalid recording action");
            String kind = names[id];
            if (!snapshot && kind.equals("flashback:action/next_tick")) {
                tick++;
            } else if (kind.equals("flashback:action/game_packet")
                    || kind.equals("flashback_neoforge_fixed:action/forwarded_game_packet_optional")
                    || kind.equals("flashback_neoforge_fixed:action/modded_payload_optional")
                    || kind.equals("flashback_neoforge_fixed:action/modded_payload_v2_optional")
                    || kind.equals("flashback_neoforge_fixed:action/forwarded_modded_payload_optional")) {
                int n = Math.min(prefix.length, size);
                in.readFully(prefix, 0, n);
                bytes.setIndex(0, n);
                readPrefix(kind, bytes, snapshot ? 0 : tick + 1, snapshot);
                size -= n;
            }
            in.skipNBytes(size);
        }
    }

    private void readPrefix(String kind, FriendlyByteBuf bytes, int tick, boolean snapshot) {
        if (kind.endsWith("game_packet") || kind.endsWith("game_packet_optional")) {
            PacketType<?> type = CLOCK_PACKETS.get(bytes.readVarInt());
            if (GamePacketTypes.CLIENTBOUND_SET_TIME.equals(type) && initialTime == Long.MIN_VALUE) setInitialTime(bytes.readLong() - tick, snapshot);
            if (!CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD.equals(type)) return;
        } else {
            bytes.readVarInt(); // Flashback NeoForge Fixed payload wrapper: protocol, id, payload
        }
        int nameSize = bytes.readVarInt();
        if (nameSize < 0 || nameSize > bytes.readableBytes()) return;
        String name = bytes.readCharSequence(nameSize, StandardCharsets.UTF_8).toString();
        if (name.equals("incoming:nuke_tick") && initialTime == Long.MIN_VALUE) {
            setInitialTime(bytes.readVarLong() - tick, snapshot);
        } else if (!snapshot && name.equals("incoming:nuke_detonation")) {
            var d = S2C.NukeDetonation.CODEC.decode(bytes).detonation();
            synchronized (this) {
                births.putIfAbsent(new Key(d.id(), d.gameTime()), tick);
                notifyAll();
            }
        }
    }

    private synchronized void setInitialTime(long time, boolean snapshot) {
        if (initialTime == Long.MIN_VALUE) {
            initialTime = time;
            snapshotBaseline = snapshot;
            notifyAll();
        }
    }

    private static int varInt(DataInputStream in, int first) throws IOException {
        int value = first & 127;
        for (int shift = 7; (first & 128) != 0; shift += 7) {
            if (shift >= 32) throw new IOException("invalid varint");
            first = in.readUnsignedByte();
            value |= (first & 127) << shift;
        }
        return value;
    }
}
