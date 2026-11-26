package ua.zentix.incoming.strike;

import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Rules captured with an order ({@link StrikeRules}) survive every save a round goes through: the bit encoding is one to
 * one, and a round's NBT and codec (projectiles, scheduled ICBMs, nuclear impacts) keep them; a round saved before rules
 * were captured takes the config when loaded (here unloaded: every rule on, the defaults).
 */
class StrikeRulesTest {
    @Test
    void bitsAreOneToOne() {
        Set<StrikeRules> seen = new HashSet<>();
        for (int bits = 0; bits < 64; bits++) {
            StrikeRules r = StrikeRules.of(bits);
            assertEquals(bits, r.bits(), "bits " + bits);
            seen.add(r);
        }
        assertEquals(64, seen.size());
        assertEquals(StrikeRules.ALL, StrikeRules.of(63));
        assertEquals(new StrikeRules(true, false, false, false, false, false), StrikeRules.of(1), "block_damage is bit 0 in saves");
        assertEquals(new StrikeRules(false, false, false, false, false, true), StrikeRules.of(32), "grid is bit 5 in saves");
    }

    @Test
    void roundKeepsItsRulesThroughSaves() {
        UUID order = new UUID(7, 7);
        for (int bits : new int[]{0, 1, 2, 4, 8, 16, 32, 21, 63}) {
            RoundRef r = RoundRef.of(order, 3, bits % 2 == 0 ? "arena" : null, StrikeRules.of(bits));
            assertEquals(r, RoundRef.load(r.save()), "NBT, bits " + bits);
            assertEquals(r, RoundRef.CODEC.parse(JsonOps.INSTANCE, RoundRef.CODEC.encodeStart(JsonOps.INSTANCE, r).getOrThrow()).getOrThrow(),
                    "codec, bits " + bits);
        }
    }

    @Test
    void scheduledNuclearStrikeKeepsItsOrdersRules() {
        RoundRef r = RoundRef.of(new UUID(5, 5), 0, "nuke", new StrikeRules(true, true, true, true, true, false));
        var s = new ua.zentix.incoming.nuclear.NuclearEvents.ScheduledStrike(1, new net.minecraft.world.phys.Vec3(1, 2, 3), 1, true, 10, 20,
                new net.minecraft.world.phys.Vec3(4, 5, 6), java.util.Optional.empty(), false, java.util.Optional.empty(), java.util.Optional.of(r));
        var codec = ua.zentix.incoming.nuclear.NuclearEvents.ScheduledStrike.CODEC;
        var back = codec.parse(JsonOps.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, s).getOrThrow()).getOrThrow();
        assertEquals(r, back.round().orElseThrow(), "the scheduled strike's round after a restart");
        assertEquals(false, back.round().orElseThrow().rules().grid(), "grid.enabled as the order was made");
    }

    @Test
    void sameRoundIdUnderAnyRules() {
        UUID order = new UUID(1, 2);
        assertEquals(RoundRef.of(order, 4, "t", StrikeRules.ALL).round(), RoundRef.of(order, 4, "t", StrikeRules.of(0)).round(),
                "a salvo recomputes round ids after a restart from order and index only");
    }

    @Test
    void roundSavedWithoutRulesTakesTheConfig() {
        RoundRef r = RoundRef.of(new UUID(3, 3), 0, null, StrikeRules.of(0));
        CompoundTag old = r.save();
        old.remove("rules");
        assertEquals(StrikeRules.ALL, RoundRef.load(old).rules());
    }
}
