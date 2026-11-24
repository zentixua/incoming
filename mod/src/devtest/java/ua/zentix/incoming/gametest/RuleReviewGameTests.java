package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.StrikeRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Review r1 regression for B4 ({@code /incoming rule}): a B-2 hands its round to the bomb at release
 * ({@code BomberEntity.release}) and keeps flying on egress, where a SAM can still engage it (B-2 {@code Signature(0.15,
 * true)}). Its wreck must land under its order's rules ({@code StrikeProjectile.orderRules}), also after a save, not
 * under the config switched back on after the wave, as the guide says is safe.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuleReviewGameTests {
    private static final BlockPos DROP = new BlockPos(32, 24, 32);

    private RuleReviewGameTests() {}

    @GameTest(template = "range", timeoutTicks = 600, batch = "rule_review_bomber_wreck", skyAccess = true)
    public static void bomberWreckAfterReleaseKeepsTheOrderRules(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<ModConfigSpec.BooleanValue> arena = List.of(IncomingConfig.SERVER.blockDamage, IncomingConfig.SERVER.shatterGlass,
                IncomingConfig.SERVER.fire, IncomingConfig.SERVER.debrisStay, IncomingConfig.SERVER.collapse, IncomingConfig.SERVER.gridEnabled);
        Map<ModConfigSpec.BooleanValue, Boolean> was = new HashMap<>();
        for (var v : arena) was.put(v, v.get());
        StrikeGameTests.afterTest(h, () -> was.forEach(ModConfigSpec.BooleanValue::set));

        // the order is made with the arena rules off: its round carries them
        arena.forEach(v -> v.set(false));
        RoundRef round = RoundRef.of(UUID.randomUUID(), 0, "b4review");
        h.assertValueEqual(round.rules(), StrikeRules.of(0), "round made with the rules off");
        // the wave is over for the game master: the rules go back on
        arena.forEach(v -> v.set(true));

        BomberEntity b2 = ModEntities.BOMBER.get().create(level);
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        h.assertTrue(b2 != null && bomb != null, "entities not created");
        b2.attachRound(round);
        // BomberEntity.release: the round goes on in the bomb, the B-2 flies on (EGRESS) and stays a SAM target
        bomb.attachRound(b2.detachRound());
        h.assertValueEqual(bomb.rules(), round.rules(), "the bomb's rules");
        h.assertValueEqual(b2.rules(), round.rules(), "the departing B-2's rules");
        // its save keeps them (no round left to carry them)
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        b2.saveWithoutId(saved);
        BomberEntity back = ModEntities.BOMBER.get().create(level);
        back.load(saved);
        h.assertValueEqual(back.rules(), round.rules(), "the departing B-2's rules after a restart");

        // a SAM downs the departing B-2: the real interception path throws the wreck
        b2.moveTo(Vec3.atCenterOf(h.absolutePos(DROP)));
        level.addFreshEntity(b2);
        Map<BlockPos, BlockState> before = snapshot(h);
        b2.intercepted(level, "rule_review", null, null);
        h.assertFalse(level.getEntitiesOfClass(DebrisEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(96)).isEmpty(), "no wreck spawned");
        StrikeRules wreckRules = round.rules();
        h.succeedWhen(() -> {
            h.assertTrue(level.getEntitiesOfClass(DebrisEntity.class, RuleGameTests.overPlatform(h)).isEmpty(), "wreck still falling");
            List<String> changed = new ArrayList<>();
            before.forEach((p, s) -> {
                BlockState now = h.getBlockState(p);
                if (!now.equals(s)) changed.add(p.toShortString() + " " + s + " -> " + now);
            });
            h.assertTrue(changed.isEmpty(), "B-2 wreck of an order made with the arena rules off landed under " + wreckRules + ": "
                    + changed.size() + " blocks changed, first " + changed.subList(0, Math.min(6, changed.size())));
        });
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper h) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int x = 0; x < 64; x++) for (int y = 0; y < 40; y++) for (int z = 0; z < 64; z++) {
            BlockPos p = new BlockPos(x, y, z);
            out.put(p, h.getBlockState(p));
        }
        return out;
    }
}
