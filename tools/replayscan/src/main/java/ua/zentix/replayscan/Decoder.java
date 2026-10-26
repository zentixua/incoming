package ua.zentix.replayscan;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import ua.zentix.replayscan.stream.ReplayFile.Bytes;
import ua.zentix.replayscan.stream.Timeline.Action;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Replay actions - into game packages and mod packages, with the same codecs as the client: game packages - with the game protocol
 *  ({@code GameProtocols}, like {@code ReplayReader}), mod packages from actions Flashback NeoForge Fixed - with the codec
 * , which the mod registered with NeoForge (action body: varint protocol, id, packet bytes as the client received them).
 * Registers - replay servers (Flashback collected them from the record). Chunks are not completely disassembled: only space is needed.
 */
final class Decoder {
    static final String GAME_PACKET = "flashback:action/game_packet";
    static final String CHUNK_CACHED = "flashback:action/level_chunk_cached";
    static final String FORWARDED_PACKET = "flashback_neoforge_fixed:action/forwarded_game_packet_optional";
    static final List<String> PAYLOADS = List.of("flashback_neoforge_fixed:action/modded_payload_optional",
            "flashback_neoforge_fixed:action/modded_payload_v2_optional",
            "flashback_neoforge_fixed:action/forwarded_modded_payload_optional",
            "flashback_neoforge_fixed:action/modded_snapshot_payload_v3_optional");

    /** What came out of the action. */
    sealed interface Item {}

    /** Game package; {@code forwarded} - Flashback NeoForge Fixed gives it to the viewer without applying replay to the world (sections Sable). */
    record GamePacket(Packet<?> packet, boolean forwarded) implements Item {}

    /** The chunk has arrived to the client (place; the chunk itself is replayed by the server into the world). */
    record Chunk(int x, int z, boolean forwarded) implements Item {}

    /** Chunk from the replay chunk cache by number. */
    record CachedChunk(int index) implements Item {}

    record Payload(ResourceLocation id, CustomPacketPayload value) implements Item {}

    /** A mod package without a codec (the mod is not worth it) or with a parsing error. */
    record RawPayload(ResourceLocation id, int bytes, String error) implements Item {}

    private final RegistryAccess access;
    private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> game;
    /** id chunk and light packages: their bodies cannot be disassembled (they are recognized by the first one that is disassembled). */
    private int chunkId = -1;
    private final IntSet lightIds = new IntOpenHashSet();
    final Map<String, long[]> actions = new TreeMap<>();
    final Map<String, long[]> packets = new TreeMap<>();
    final Map<String, long[]> payloads = new TreeMap<>();
    final Map<String, String> errors = new TreeMap<>();

    Decoder(RegistryAccess access) {
        this.access = access;
        this.game = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access, ConnectionType.NEOFORGE)).codec();
    }

    /** Analysis of the action: everything in it is in {@code out}; {@code payloads} - whether to disassemble mod packages. */
    void decode(Action a, boolean withPayloads, Consumer<Item> out) {
        count(actions, a.name());
        try {
            switch (a.name()) {
                case GAME_PACKET -> game(a, false, out);
                case FORWARDED_PACKET -> game(a, true, out);
                case CHUNK_CACHED -> out.accept(new CachedChunk(a.body().readVarInt()));
                default -> {
                    if (withPayloads && PAYLOADS.contains(a.name())) payload(a, out);
                }
            }
        } catch (RuntimeException e) {
            error(a.name(), e);
        }
    }

    private void game(Action a, boolean forwarded, Consumer<Item> out) {
        Bytes b = a.body();
        int id = b.readVarInt();
        if (id == chunkId && b.remaining() >= 8) {
            out.accept(new Chunk(b.readInt(), b.readInt(), forwarded));
            count(packets, "ClientboundLevelChunkWithLightPacket");
            return;
        }
        if (lightIds.contains(id)) {
            count(packets, "ClientboundLightUpdatePacket");
            return;
        }
        ByteBuf buf = Unpooled.wrappedBuffer(a.data(), a.off(), a.len());
        Packet<?> p;
        try {
            p = game.decode(buf);
        } catch (RuntimeException e) {
            error("packet#" + id, e);
            return;
        }
        if (p instanceof ClientboundLevelChunkWithLightPacket c) {
            chunkId = id;
            out.accept(new Chunk(c.getX(), c.getZ(), forwarded));
            count(packets, "ClientboundLevelChunkWithLightPacket");
            return;
        }
        if (p instanceof ClientboundLightUpdatePacket) {
            lightIds.add(id);
            count(packets, "ClientboundLightUpdatePacket");
            return;
        }
        unbundle(p, forwarded, out);
    }

    private void unbundle(Packet<?> p, boolean forwarded, Consumer<Item> out) {
        if (p instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) unbundle(sub, forwarded, out);
            return;
        }
        count(packets, name(p.getClass()));
        out.accept(new GamePacket(p, forwarded));
    }

    private void payload(Action a, Consumer<Item> out) {
        Bytes b = a.body();
        int protocol = b.readVarInt();
        ResourceLocation id = ResourceLocation.parse(b.readString());
        count(payloads, id.toString());
        int size = b.remaining();
        ConnectionProtocol[] protocols = ConnectionProtocol.values();
        StreamCodec<? super FriendlyByteBuf, ? extends CustomPacketPayload> codec = protocol >= 0 && protocol < protocols.length
                ? NetworkRegistry.getCodec(id, protocols[protocol], PacketFlow.CLIENTBOUND) : null;
        if (codec == null) {
            out.accept(new RawPayload(id, size, "no codec"));
            return;
        }
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(b.data, b.pos, size), access, ConnectionType.NEOFORGE);
        try {
            CustomPacketPayload value = codec.decode(buf);
            out.accept(new Payload(id, value));
        } catch (RuntimeException | StackOverflowError e) {
            error(id.toString(), e);
            out.accept(new RawPayload(id, size, e.toString()));
        }
    }

    private void error(String what, Throwable e) {
        errors.putIfAbsent(what, e.toString());
        count(actions, "error " + what);
    }

    static String name(Class<?> c) {
        String n = c.getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }

    private static void count(Map<String, long[]> m, String key) {
        m.computeIfAbsent(key, k -> new long[1])[0]++;
    }
}
