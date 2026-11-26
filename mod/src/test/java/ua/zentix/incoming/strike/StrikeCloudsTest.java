package ua.zentix.incoming.strike;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.WaterBlast;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.WorldClock;
import ua.zentix.incoming.util.Wind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's record of conventional clouds (rule 10 of the time note): life bound, motion age, packet format and size. */
class StrikeCloudsTest {
    private static final Air AIR = new Air(new Wind(0.75, 9.5), 3, 0.5);

    static S2C.Blast shahed(int i) {
        return new S2C.Blast(S2C.Blast.DRONE, new Vec3(-1234.5 + i, 70.25, 98765.125), 3, 70.25f, 0x5DEECE66DL * i, Optional.of(new UUID(i, -i)),
                WeaponType.DRONE.id(), new Vec3(0.6, -0.8, 0), true, Optional.of(new S2C.Blast.Support(true, 0, 0, new Vec3(0, -1, 0), 0.5f)));
    }

    static S2C.StrikeClouds.Cloud cloud(long born, double jumps, S2C.Blast b) {
        return new S2C.StrikeClouds.Cloud(S2C.StrikeClouds.BLAST, born, jumps, AIR, 69.5f, b);
    }

    /** No cloud of any weapon outlives the record: on land, on water (spray mist), in calm night air and in wind. */
    @Test
    void everyCloudEndsWithinTheRecordsLife() {
        CloudModel.Air night = new CloudModel.Air(0.02, 0.3, 0, 0.3, 5, 1), windy = new CloudModel.Air(0.01, 12, 3, 0.1, 1, 0.5);
        for (WeaponType w : WeaponType.values()) {
            if (!(w.spec().charge() > 0)) continue;
            for (CloudModel.Air air : List.of(CloudModel.Air.STANDARD, night, windy)) {
                CloudModel land = new CloudModel(BlastSource.of(w, false), air);
                assertEquals(CloudModel.LIFE_MAX, StrikeClouds.LIFE_S, "the record lives as long as the longest cloud");
                assertTrue(land.life <= StrikeClouds.LIFE_S, w + ": cloud " + land.life + " s");
                WaterBlast water = new WaterBlast(w.spec().charge(), 0, Double.POSITIVE_INFINITY, air);
                double mist = Math.max(water.mistLife(), water.waterLife());
                assertTrue(mist <= StrikeClouds.LIFE_S, w + ": water mist " + mist + " s");
            }
        }
    }

    /** M = real age plus the counted jumps since the strike; an hour's counted jump ends every cloud the client could build. */
    @Test
    void motionAgeAddsOnlyTheJumpsSinceTheStrike() {
        S2C.StrikeClouds.Cloud c = cloud(1000, 5000, shahed(1));
        assertEquals(600, StrikeClouds.motion(c, 1000 + 12_000, 5000), 1e-9, "ten minutes of real time");
        assertEquals(0, StrikeClouds.motion(c, 900, 5000), 1e-9, "before the strike (a replay seek back)");
        // a client that has not yet heard the clocks counts no jump
        assertEquals(600, StrikeClouds.motion(c, 13_000, 0), 1e-9);
        double hour = 3600 / WorldClock.SECONDS_PER_TICK;
        assertEquals(3600, StrikeClouds.motion(c, 1000, 5000 + hour), 1e-9);
        assertTrue(StrikeClouds.motion(c, 1000, 5000 + hour) >= StrikeClouds.LIFE_S, "a counted hour ends the record");
    }

    /** Pruning drops the expired from the front only: records are in birth order and the oldest is the most aged. */
    @Test
    void pruneDropsExpiredOldestFirst() {
        StrikeClouds all = new StrikeClouds();
        long lifeTicks = Math.round(StrikeClouds.LIFE_S * 20);
        all.addForTest(cloud(0, 0, shahed(1)));
        all.addForTest(cloud(lifeTicks - 100, 0, shahed(2)));
        all.prune(lifeTicks, 0);
        assertEquals(1, all.sizeForTest(), "the strike one life old leaves, the younger stays");
        all.prune(lifeTicks + 1000, 1000);
        assertEquals(0, all.sizeForTest(), "a counted hour ends it");
    }

    /** Each cloud and its packet sit in frames: a newer reader's unknown kind is skipped, a packet's own appended field stays inside. */
    @Test
    void unknownKindsAndNewerFieldsAreSkipped() {
        S2C.StrikeClouds p = new S2C.StrikeClouds(List.of(cloud(5, 0, shahed(1)), cloud(6, 0, shahed(2))));
        // a writer of a later format: a third cloud of an unknown kind, and the first packet with one more field at its end
        ByteBuf later = Unpooled.buffer();
        VarInt.write(later, 3);
        ByteBuf first = frame(cloudBytes(cloud(5, 0, shahed(1)), new byte[]{1, 2, 3}));
        later.writeBytes(first);
        ByteBuf unknown = Unpooled.buffer();
        ByteBufCodecs.VAR_INT.encode(unknown, 99);
        unknown.writeBytes(new byte[]{9, 9, 9, 9});
        later.writeBytes(frame(unknown));
        later.writeBytes(frame(cloudBytes(cloud(6, 0, shahed(2)), new byte[0])));
        S2C.StrikeClouds read = S2C.StrikeClouds.CODEC.decode(later);
        assertFalse(later.isReadable());
        assertEquals(p, read);
    }

    /** One cloud's bytes as the codec writes them, with {@code extra} bytes appended inside its packet's frame. */
    private static ByteBuf cloudBytes(S2C.StrikeClouds.Cloud c, byte[] extra) {
        ByteBuf whole = Unpooled.buffer();
        S2C.StrikeClouds.CODEC.encode(whole, new S2C.StrikeClouds(List.of(c)));
        VarInt.read(whole);
        int n = VarInt.read(whole);
        ByteBuf inner = whole.readSlice(n);
        // kind, born, jumps, air (4 floats), surface, then the framed packet
        ByteBuf out = Unpooled.buffer();
        int start = inner.readerIndex();
        VarInt.read(inner);
        ByteBufCodecs.VAR_LONG.decode(inner);
        inner.skipBytes(8 + 16 + 4);
        out.writeBytes(inner.slice(start, inner.readerIndex() - start));
        int len = VarInt.read(inner);
        ByteBuf packet = Unpooled.buffer();
        packet.writeBytes(inner.readSlice(len));
        packet.writeBytes(extra);
        out.writeBytes(frame(packet));
        return out;
    }

    private static ByteBuf frame(ByteBuf body) {
        ByteBuf out = Unpooled.buffer();
        VarInt.write(out, body.readableBytes());
        out.writeBytes(body);
        return out;
    }

    /**
     * Cost on a war server: the packet holds at most the live clouds around a player. Sizes printed for the PR: a Shahed
     * cloud and a missile crater fire each, and a full packet of {@link S2C.StrikeClouds#MAX}.
     */
    @Test
    void packetSizeIsBounded() {
        int one = bytes(List.of(cloud(1, 0, shahed(1)))) - 1;
        List<S2C.StrikeClouds.Cloud> hour = new ArrayList<>();
        for (int i = 0; i < 60; i++) hour.add(cloud(i * 1200L, 0, shahed(i)));
        List<S2C.StrikeClouds.Cloud> full = new ArrayList<>();
        for (int i = 0; i < S2C.StrikeClouds.MAX; i++) full.add(cloud(i, 0, shahed(i)));
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) cells.add(new BlockPos(-1234 + i % 3, 70, 98765 + i / 3));
        int fuel = bytes(List.of(new S2C.StrikeClouds.Cloud(S2C.StrikeClouds.FUEL, 1, 0, Air.CALM, Float.NaN,
                new S2C.FuelFire(3, cells, 0, WeaponType.MISSILE.id(), 1)))) - 1;
        int sixty = bytes(hour), max = bytes(full);
        System.out.printf(java.util.Locale.ROOT, "STRIKECLOUDS bytes: one Shahed cloud %d, one missile crater fire %d, 60 clouds %d, %d clouds %d%n",
                one, fuel, sixty, S2C.StrikeClouds.MAX, max);
        assertTrue(one <= 140, "one cloud " + one + " bytes");
        assertTrue(fuel <= 140, "one crater fire " + fuel + " bytes");
        assertTrue(max <= 40_000, "a full packet " + max + " bytes");
    }

    /**
     * A resent cloud costs the client one model build, as a live one does; a join's full list is spread over ticks
     * ({@code ClientStrikeClouds.Ledger.BUILD_NANOS}). Printed for the PR: build time per cloud, and the first query at
     * a ten-minute age (the model advances its tables to the asked age on demand).
     */
    @Test
    void aCloudBuildsAtAnAgeCheaply() {
        // printed, not asserted: shared CI runners make wall time no test measure (gametest.md)
        CloudModel.Air air = new CloudModel.Air(0.012, 6, 2, 0.14, 3, 0.5);
        WeaponType[] weapons = {WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET};
        for (int i = 0; i < 30; i++) new CloudModel(BlastSource.of(weapons[i % 3], false), air).shear(600);
        int n = 300;
        long t0 = System.nanoTime(), built = 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            long b = System.nanoTime();
            CloudModel m = new CloudModel(BlastSource.of(weapons[i % 3], false), air);
            built += System.nanoTime() - b;
            sum += m.shear(600) + m.height(600);
        }
        double ms = (System.nanoTime() - t0) / 1e6 / n;
        System.out.printf(java.util.Locale.ROOT, "STRIKECLOUDS build: constructor %.3f ms%n", built / 1e6 / n);
        System.out.printf(java.util.Locale.ROOT, "STRIKECLOUDS build: %.3f ms per cloud built and asked at 600 s (%d clouds, check %.1f)%n", ms, n, sum);
    }

    private static int bytes(List<S2C.StrikeClouds.Cloud> clouds) {
        ByteBuf b = Unpooled.buffer();
        S2C.StrikeClouds.CODEC.encode(b, new S2C.StrikeClouds(clouds));
        return b.readableBytes();
    }
}
