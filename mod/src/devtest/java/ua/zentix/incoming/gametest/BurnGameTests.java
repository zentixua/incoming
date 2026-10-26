package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BurnInjury;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.radiation.BurnsEffect;
import ua.zentix.incoming.nuclear.radiation.FlashBurns;
import ua.zentix.incoming.registry.ModEffects;

import java.util.Locale;
import java.util.UUID;

/** Burns from the ball's light on the body ({@link FlashBurns}): degree, clothing, armor, shock. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BurnGameTests {
    private BurnGameTests() {}

    private static final double YIELD = 15;

    /**
     * 15 kt, an impulse that causes a 3 degree burn in anyone: a cow (skin on half the body with the side facing the ball) - shock,
     * health 5% (health limit - no less than one, like vanilla); resident in a shirt - 3-th degree on the face and hands
     *  (9,9 % of the body): 40 % of health taken away, limit - 60 % (up to a tenth), clothes did not catch fire; the same flash
     *  the second time does not take away anything. At 20 cal/cm² the resident's clothing catches fire. The robber in the iron is not burned and does not
     *  burn, in the skin he burns (thick fabric catches fire), but the skin underneath is intact. Below the lowest threshold - nothing,
     *  cod under water, not yet ticking - too. A player whom the rules do not allow to be wounded is not affected by the explosion of the player, but the other player is burned and sets his shirt on fire. The second detonation does not touch the incoming shock; wound on top of previous burns -
     *  from health without them. A new burn, weaker than the previous one, heals on its own time: the old one is over - the limit of the new one. Shock in the
     *  end of life kills and under fire resistance with the resistance of V.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_burns", skyAccess = true)
    public static void burnsByDegreeClothesAndShock(GameTestHelper h) {
        Detonation d = NuclearGameTests.detonation(h, new BlockPos(32, 12, 32), 0, YIELD, 1f, false);
        double third = BurnInjury.median(3, YIELD) * Math.exp(BurnInjury.BETA * Fragility.TAIL) * 1.01;
        Cow cow = still(h, EntityType.COW, 20, 20);
        Mob villager = still(h, EntityType.VILLAGER, 24, 20);
        Mob burning = still(h, EntityType.VILLAGER, 28, 20);
        Mob iron = dressed(still(h, EntityType.PILLAGER, 32, 20), Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
        Mob leather = dressed(still(h, EntityType.PILLAGER, 36, 20), Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
        Cow faint = still(h, EntityType.COW, 40, 20);
        Cow shocked = still(h, EntityType.COW, 44, 20);
        Cow resisting = still(h, EntityType.COW, 20, 28);
        Cow twice = still(h, EntityType.COW, 24, 28);
        Mob healing = still(h, EntityType.VILLAGER, 28, 28);
        // cod in a hole of water behind glass: the sign “under water” will appear only in its first tick
        BlockPos pond = new BlockPos(48, 12, 20);
        h.setBlock(pond, Blocks.WATER);
        for (BlockPos side : new BlockPos[]{pond.east(), pond.west(), pond.north(), pond.south()}) h.setBlock(side, Blocks.GLASS);
        Cod cod = h.spawn(EntityType.COD, pond);
        cod.setNoAi(true);
        FlashBurns.burn(h.getLevel(), d, cod, 30, null);
        h.assertTrue(level(cod) < 0 && !cod.isOnFire(), "cod underwater seared: " + about(cod));

        FlashBurns.burn(h.getLevel(), d, cow, third, null);
        FlashBurns.burn(h.getLevel(), d, villager, third, null);
        FlashBurns.burn(h.getLevel(), d, burning, 20, null);
        FlashBurns.burn(h.getLevel(), d, iron, 30, null);
        FlashBurns.burn(h.getLevel(), d, leather, 20, null);
        FlashBurns.burn(h.getLevel(), d, faint, BurnInjury.lowest(YIELD) * 0.99, null);

        h.assertTrue(level(cow) == BurnsEffect.SHOCK && cow.getMaxHealth() <= Math.max(1, 10 * BurnInjury.SHOCK_LEFT) + 1e-3 && cow.getHealth() <= 10 * BurnInjury.SHOCK_LEFT + 1e-3, "cow at " + fmt(third) + " cal/cm² without shock: " + about(cow));
        h.assertFalse(cow.isOnFire(), "the cow caught fire: only the clothes burn");
        h.assertTrue(level(villager) == 4 && Math.abs(villager.getMaxHealth() - 12) < 1e-3 && villager.getHealth() > 11.9f,
                "resident in shirt " + fmt(third) + " cal/cm²: " + about(villager));
        h.assertFalse(villager.isOnFire(), "a resident's shirt caught fire when " + fmt(third) + " cal/cm²");
        FlashBurns.burn(h.getLevel(), d, villager, third, null);
        h.assertTrue(level(villager) == 4 && villager.getHealth() > 11.9f, "the same flash injured a resident a second time: " + about(villager));
        h.assertTrue(burning.isOnFire() && level(burning) == 4, "resident at 20 cal/cm²: " + about(burning) + (burning.isOnFire() ? "" : ", clothes don't burn"));
        h.assertTrue(level(iron) < 0 && !iron.isOnFire(), "the robber is burned in iron: " + about(iron));
        h.assertTrue(level(leather) < 0 && leather.isOnFire(), "robber in leather at 20 cal/cm²: " + about(leather) + (leather.isOnFire() ? "" : ", the skin doesn't burn"));
        h.assertTrue(level(faint) < 0 && faint.getHealth() == faint.getMaxHealth(), "the cow is burned below the threshold: " + about(faint));

        twice.addEffect(new MobEffectInstance(ModEffects.BURNS, 40, BurnsEffect.SHOCK));
        FlashBurns.burn(h.getLevel(), d, twice, third, null);
        MobEffectInstance left = twice.getEffect(ModEffects.BURNS);
        h.assertTrue(left != null && left.getAmplifier() == BurnsEffect.SHOCK && left.getDuration() == 40, "the second explosion moved the shock: " + left);

        ServerLevel level = h.getLevel();
        FakePlayer launcher = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "burns_launcher"));
        Player ally = player(h, "burns_ally", false, 32.5, 36.5);
        Player enemy = player(h, "burns_enemy", true, 36.5, 36.5);
        FlashBurns.burn(level, d, ally, 30, launcher);
        FlashBurns.burn(level, d, enemy, 30, launcher);
        h.assertTrue(level(ally) < 0 && !ally.isOnFire(), "a player, whom the rules do not allow to be injured, was burned: " + about(ally) + (ally.isOnFire() ? ", lit" : ""));
        h.assertTrue(level(enemy) >= 0 && enemy.isOnFire(), "the player whom the rules allow to be wounded is not burned: " + about(enemy) + (enemy.isOnFire() ? "" : ", does not light"));

        // on top of previous burns the wound is from health without them: in a cow with a limit of 60 %, the shock leaves 5 % of 10, and in a chicken
        //  with 0,9 (the vanilla health limit does not fall below one) the shock takes away 5 % of 4 - less than half a unit, without a wound
        Cow burned = still(h, EntityType.COW, 36, 28);
        burned.addEffect(new MobEffectInstance(ModEffects.BURNS, 24_000, 4));
        burned.setHealth(6);
        FlashBurns.burn(level, d, burned, third, null);
        h.assertTrue(level(burned) == BurnsEffect.SHOCK && Math.abs(burned.getHealth() - 10 * BurnInjury.SHOCK_LEFT) < 1e-3,
                "cow with burns 40% after shock: " + about(burned));
        Chicken chicken = still(h, EntityType.CHICKEN, 32, 28);
        chicken.addEffect(new MobEffectInstance(ModEffects.BURNS, 24_000, 9));
        chicken.setHealth(0.4f);
        FlashBurns.burn(level, d, chicken, third, null);
        h.assertTrue(chicken.isAlive() && level(chicken) == BurnsEffect.SHOCK && chicken.getHealth() > 0.39f,
                "chicken with burns 90% after shock: " + about(chicken) + (chicken.isAlive() ? "" : ", died"));

        healing.addEffect(new MobEffectInstance(ModEffects.BURNS, 5, 6));
        FlashBurns.burn(level, d, healing, third, null);
        h.assertTrue(level(healing) == 6, "the burn, weaker than the previous one, replaced the previous one immediately: " + about(healing));

        shocked.addEffect(new MobEffectInstance(ModEffects.BURNS, 5, BurnsEffect.SHOCK));
        resisting.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
        resisting.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 4));
        resisting.addEffect(new MobEffectInstance(ModEffects.BURNS, 5, BurnsEffect.SHOCK));
        h.succeedWhen(() -> {
            h.assertFalse(shocked.isAlive(), "the shock didn't kill");
            h.assertFalse(resisting.isAlive(), "the shock did not kill under the fire resistance and resistance of V");
            MobEffectInstance healed = healing.getEffect(ModEffects.BURNS);
            h.assertTrue(healed != null && healed.getAmplifier() == 4 && healed.getDuration() > 24_000,
                    "the old burn has ended, but the limit is not new: " + healed);
        });
    }

    /** A player whom the rules of the game (PvP, commands) allow or prevent other players from being injured. */
    private static Player player(GameTestHelper h, String name, boolean harmable, double x, double z) {
        Player p = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), name)) {
            @Override
            public boolean canHarmPlayer(Player other) {
                return harmable;
            }
        };
        p.moveTo(h.absoluteVec(new Vec3(x, 12, z)));
        return p;
    }

    private static <T extends Mob> T still(GameTestHelper h, EntityType<T> type, int x, int z) {
        T mob = h.spawn(type, new BlockPos(x, 12, z));
        mob.setNoAi(true);
        return mob;
    }

    private static Mob dressed(Mob mob, Item head, Item chest, Item legs, Item feet) {
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(head));
        mob.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chest));
        mob.setItemSlot(EquipmentSlot.LEGS, new ItemStack(legs));
        mob.setItemSlot(EquipmentSlot.FEET, new ItemStack(feet));
        return mob;
    }

    private static int level(LivingEntity e) {
        MobEffectInstance burns = e.getEffect(ModEffects.BURNS);
        return burns == null ? -1 : burns.getAmplifier();
    }

    private static String about(LivingEntity e) {
        return String.format(Locale.ROOT, "burn level %d, health %.2f from %.2f", level(e), e.getHealth(), e.getMaxHealth());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
