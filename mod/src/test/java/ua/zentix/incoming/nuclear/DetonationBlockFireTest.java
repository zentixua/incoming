package ua.zentix.incoming.nuclear;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saved {@code block_fire} ({@code doFireTick} at detonation, rule 11 of docs/design/time.md §8.3): it round-trips, and a
 * detonation saved without it (older versions) loads with block fire lit, as it was.
 */
class DetonationBlockFireTest {
    private static final Detonation D = new Detonation(3, new Vec3(10.5, 700, -20), 64, 15, false, 1234, 0.5f, 4f, 20_000, 9, 1f,
            true, 0.35f, 6000L, true, true);

    @Test
    void savedFlagRoundTrips() {
        for (boolean lit : new boolean[]{true, false}) {
            Detonation d = D.withBlockFire(lit);
            JsonElement json = Detonation.CODEC.encodeStart(JsonOps.INSTANCE, d).getOrThrow();
            Detonation back = Detonation.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(d, back);
            assertEquals(lit, back.blockFire());
            assertTrue(back.fires(), "the fires rule is saved apart");
        }
    }

    @Test
    void olderSaveLoadsWithBlockFire() {
        JsonElement json = Detonation.CODEC.encodeStart(JsonOps.INSTANCE, D.withBlockFire(false).withFires(false)).getOrThrow();
        json.getAsJsonObject().remove("block_fire");
        Detonation back = Detonation.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertTrue(back.blockFire());
        assertFalse(back.fires());
    }
}
