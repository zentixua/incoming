package ua.zentix.incoming.warhead;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.GroundShock;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real server dispatch and material lookup, with a recording connection instead of a client/GPU. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GroundShockGameTests {
    private GroundShockGameTests() {}

    private record Listener(ServerPlayer player, List<S2C.Quake> quakes) {}

    private static Listener listener(GameTestHelper h, Vec3 at) {
        ServerLevel level = h.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "ground_probe");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        List<S2C.Quake> quakes = new ArrayList<>();
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                player, CommonListenerCookie.createInitial(profile, false)) {
            @Override
            public void send(Packet<?> packet) {
                if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof S2C.Quake quake) {
                    var buf = Unpooled.buffer();
                    try {
                        S2C.Quake.CODEC.encode(buf, quake);
                        S2C.Quake decoded = S2C.Quake.CODEC.decode(buf);
                        h.assertTrue(!buf.isReadable() && quake.equals(decoded), "ground event did not survive its wire codec");
                        quakes.add(decoded);
                    } finally {
                        buf.release();
                    }
                }
            }
        };
        player.setPos(at);
        // Only the recipient list, not an entity or a socket: no fake-player entity tick or mod simulation hooks.
        level.players().add(player);
        StrikeGameTests.afterTest(h, () -> level.players().remove(player));
        return new Listener(player, quakes);
    }

    @GameTest(template = "range", timeoutTicks = 40, batch = "ground_shock_entry", skyAccess = true)
    public static void entrySendsKineticGroundEnergyAndContactMaterial(GameTestHelper h) {
        BlockPos block = new BlockPos(32, 4, 32);
        h.setBlock(block, Blocks.STONE);
        Vec3 at = h.absolutePos(block).getCenter();
        Listener near = listener(h, at.add(10, 0, 0));
        double hit = BunkerImpact.equivalentTnt(WeaponSpec.BUNKER.payload().article().dryMass(), 100);
        double groundKg = hit - BunkerImpact.airTnt(hit);
        double range = GroundShock.feltRangeM(new GroundShock.Source(groundKg, 0, GroundMaterial.STONE.shockMedium()));
        Listener far = listener(h, at.add(range * 1.01, 0, 0));
        long now = h.getLevel().getGameTime();
        Warheads.bunkerEntry(h.getLevel(), at, 100, null, null);
        h.assertTrue(near.quakes.size() == 1 && far.quakes.isEmpty(), "recipient selection ignored physical perception range");
        S2C.Quake event = near.quakes.getFirst();
        S2C.Quake.Ground source = event.source().orElseThrow();
        h.assertTrue(event.ticks() == 0 && !event.rumble(), "new source still requests immediate legacy shake/sound");
        h.assertTrue(source.pos().equals(at) && source.gameTime() == now, "source position/time changed in transmission");
        h.assertTrue(source.tntKg() == groundKg && source.depthM() == 0, "kinetic entry did not partition air/ground energy");
        h.assertTrue(source.material() == GroundMaterial.STONE.ordinal(), "source material was not sampled from world");
        h.assertTrue(source.model().medium() == GroundShock.Medium.ROCK, "wire material maps to wrong propagation law");
        h.succeed();
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "ground_shock_buried", skyAccess = true)
    public static void buriedSourceRetainsOriginalMaterialDepthAndTime(GameTestHelper h) {
        BlockPos block = new BlockPos(32, 8, 32);
        h.setBlock(block, Blocks.STONE);
        for (int[] offset : BunkerCover.RING) h.setBlock(new BlockPos(32 + offset[0], 28, 32 + offset[1]), Blocks.STONE);
        Vec3 at = h.absolutePos(block).getCenter();
        Listener near = listener(h, at.add(300, 0, 0));
        long now = h.getLevel().getGameTime();
        double depth = BunkerCover.depth(h.absolutePos(new BlockPos(32, 29, 32)).getY(), at.y);
        BuriedBlast law = new BuriedBlast(WeaponSpec.BUNKER.warheadTnt(), depth);
        double diameter = WeaponSpec.BUNKER.payload().article().diameter();
        double groundKg = law.tnt() - law.airCharge(Math.PI * diameter * diameter / 4);
        Warheads.bunker(h.getLevel(), at, at.add(0, depth, 0), null, null);
        // The front/ground work is queued: its source must not be resampled when it eventually runs.
        h.setBlock(block, Blocks.AIR);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(h.getLevel()).idle(), "buried impact work is still running");
            h.assertTrue(near.quakes.size() == 1, "buried source must send exactly one ground event");
            S2C.Quake.Ground source = near.quakes.getFirst().source().orElseThrow();
            h.assertTrue(source.pos().equals(at) && source.gameTime() == now, "queue delay replaced source event time");
            h.assertTrue(source.material() == GroundMaterial.STONE.ordinal(), "excavation resampled source material");
            h.assertTrue(source.depthM() == depth && source.tntKg() == groundKg, "buried depth/energy lost on server");
            h.assertTrue(GroundShock.sample(source.model(), 300).arrivalSeconds() == 0.1, "rock travel time is not 300/3000 s");
        });
    }
}
