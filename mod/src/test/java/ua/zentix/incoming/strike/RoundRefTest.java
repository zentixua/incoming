package ua.zentix.incoming.strike;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round identity ({@link RoundRef}): a salvo recomputes its rounds' ids from the order id and launch index after a restart,
 * so they must be the same function of both and distinct within and across orders; the saved forms (projectile NBT,
 * scheduled-strike codec) read back the same round, with or without a tag, and a save without one reads as no round.
 */
class RoundRefTest {
    @Test
    void roundIdIsAFunctionOfOrderAndIndex() {
        UUID order = UUID.fromString("0f3c3a2e-6d0b-4a8e-9a43-1f0e2d3c4b5a");
        assertEquals(RoundRef.of(order, 7, null).round(), RoundRef.of(order, 7, "wave").round(), "the tag does not change the id");
        assertEquals(RoundRef.of(order, 7, null), RoundRef.of(new UUID(order.getMostSignificantBits(), order.getLeastSignificantBits()), 7, null));
        Set<UUID> ids = new HashSet<>();
        for (int o = 0; o < 50; o++) {
            UUID other = new UUID(o, o * 31L);
            for (int i = 0; i < 100; i++) ids.add(RoundRef.of(other, i, null).round());
        }
        assertEquals(5000, ids.size(), "round ids collide");
        assertNotEquals(order, RoundRef.of(order, 0, null).round(), "the first round is not the order");
    }

    @Test
    void savedFormsReadBackTheSameRound() {
        UUID order = UUID.randomUUID();
        for (String tag : new String[]{null, "wave3"}) {
            RoundRef r = RoundRef.of(order, 3, tag);
            assertEquals(r, RoundRef.load(r.save()), "NBT, tag " + tag);
            Tag encoded = RoundRef.CODEC.encodeStart(NbtOps.INSTANCE, r).getOrThrow();
            assertEquals(r, RoundRef.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow(), "codec, tag " + tag);
        }
        assertNull(RoundRef.load(new CompoundTag()), "a save without a round");
    }
}
