package ua.zentix.incoming.strike;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.target.Target;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A salvo's rounds across a restart: its order, tag and the indices whose launch failed come back from the save, so the
 * rounds launched after loading take the next indices (round ids recomputed from them) and the failed ones still end once
 * at the salvo's refund. A salvo saved without an order posts no round events.
 */
class SalvoRoundsTest {
    @Test
    void orderTagAndFailedRoundsSurviveTheSave() {
        Vec3 c = new Vec3(10, 64, 20);
        SalvoData.Salvo s = new SalvoData.Salvo(WeaponType.DRONE, 5, 3, 10, new Target.Point(c), c, 0, UUID.randomUUID(), 4, Loadout.Nuke.DEFAULT,
                Waypoints.NONE, null, true, 1, 7L);
        s.order = UUID.randomUUID();
        s.tag = "wave3";
        s.failedRounds.add(1);
        CompoundTag saved = s.save();
        SalvoData.Salvo read = SalvoData.Salvo.load(saved).orElseThrow();
        assertEquals(s.order, read.order, "order");
        assertEquals("wave3", read.tag, "tag");
        assertEquals(s.failedRounds, read.failedRounds, "failed launch indices");
        assertEquals(2, read.total - read.remaining, "next launch index");
        assertEquals(RoundRef.of(s.order, 2, "wave3"), RoundRef.of(read.order, read.total - read.remaining, read.tag), "next round id");
    }

    @Test
    void salvoSavedWithoutAnOrderHasNone() {
        Vec3 c = new Vec3(0, 64, 0);
        SalvoData.Salvo s = new SalvoData.Salvo(WeaponType.ROCKET, 2, 2, 0, new Target.Point(c), c, 0, null, 1, Loadout.Nuke.DEFAULT,
                Waypoints.NONE, null, false, 0, 0L);
        SalvoData.Salvo read = SalvoData.Salvo.load(s.save()).orElseThrow();
        assertNull(read.order, "order");
        assertEquals(0, read.failedRounds.size(), "failed rounds");
    }
}
