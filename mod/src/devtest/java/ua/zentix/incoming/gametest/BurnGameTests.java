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
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
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

/** Ожоги светом шара по телу ({@link FlashBurns}): степень, одежда, броня, шок. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BurnGameTests {
    private BurnGameTests() {}

    private static final double YIELD = 15;

    /**
     * 15 кт, импульс, при котором ожог 3-й степени у любого: корова (кожа на половине тела стороной к шару) — шок,
     * здоровья 5 % (предел здоровья — не меньше единицы, как у ванили); житель в рубашке — 3-я степень на лице и руках
     * (9,9 % тела): отнято 40 % здоровья, предел — 60 % (вверх до десятой доли), одежда не загорелась; та же вспышка
     * второй раз ничего не отнимает. При 20 кал/см² у жителя загорается одежда. Разбойник в железе не обожжён и не
     * горит, в коже — горит (плотная ткань загорается), но кожа под ней цела. Ниже самого низкого порога — ничего,
     * треска под водой, ещё не тикавшая, — тоже. Игрока своей команды без огня по своим вспышка его подрыва не трогает.
     * Второй подрыв не трогает идущий шок. Новый ожог слабее прежнего заживает своим сроком: прежний кончился — предел
     * нового. Шок в конце срока убивает и под огнестойкостью с сопротивлением V.
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
        // треска в лунке воды за стеклом: признак «под водой» у неё встанет только в её первом тике
        BlockPos pond = new BlockPos(48, 12, 20);
        h.setBlock(pond, Blocks.WATER);
        for (BlockPos side : new BlockPos[]{pond.east(), pond.west(), pond.north(), pond.south()}) h.setBlock(side, Blocks.GLASS);
        Cod cod = h.spawn(EntityType.COD, pond);
        cod.setNoAi(true);
        FlashBurns.burn(h.getLevel(), d, cod, 30, null);
        h.assertTrue(level(cod) < 0 && !cod.isOnFire(), "треска под водой обожжена: " + about(cod));

        FlashBurns.burn(h.getLevel(), d, cow, third, null);
        FlashBurns.burn(h.getLevel(), d, villager, third, null);
        FlashBurns.burn(h.getLevel(), d, burning, 20, null);
        FlashBurns.burn(h.getLevel(), d, iron, 30, null);
        FlashBurns.burn(h.getLevel(), d, leather, 20, null);
        FlashBurns.burn(h.getLevel(), d, faint, BurnInjury.lowest(YIELD) * 0.99, null);

        h.assertTrue(level(cow) == BurnsEffect.SHOCK && cow.getMaxHealth() <= Math.max(1, 10 * BurnsEffect.SHOCK_LEFT) + 1e-3 && cow.getHealth() <= 10 * BurnsEffect.SHOCK_LEFT + 1e-3, "корова при " + fmt(third) + " кал/см² без шока: " + about(cow));
        h.assertFalse(cow.isOnFire(), "корова загорелась: горит только одежда");
        h.assertTrue(level(villager) == 4 && Math.abs(villager.getMaxHealth() - 12) < 1e-3 && villager.getHealth() > 11.9f,
                "житель в рубашке при " + fmt(third) + " кал/см²: " + about(villager));
        h.assertFalse(villager.isOnFire(), "рубашка жителя загорелась при " + fmt(third) + " кал/см²");
        FlashBurns.burn(h.getLevel(), d, villager, third, null);
        h.assertTrue(level(villager) == 4 && villager.getHealth() > 11.9f, "та же вспышка второй раз ранила жителя: " + about(villager));
        h.assertTrue(burning.isOnFire() && level(burning) == 4, "житель при 20 кал/см²: " + about(burning) + (burning.isOnFire() ? "" : ", одежда не горит"));
        h.assertTrue(level(iron) < 0 && !iron.isOnFire(), "разбойник в железе обожжён: " + about(iron));
        h.assertTrue(level(leather) < 0 && leather.isOnFire(), "разбойник в коже при 20 кал/см²: " + about(leather) + (leather.isOnFire() ? "" : ", кожа не горит"));
        h.assertTrue(level(faint) < 0 && faint.getHealth() == faint.getMaxHealth(), "корова ниже порога обожжена: " + about(faint));

        twice.addEffect(new MobEffectInstance(ModEffects.BURNS, 40, BurnsEffect.SHOCK));
        FlashBurns.burn(h.getLevel(), d, twice, third, null);
        MobEffectInstance left = twice.getEffect(ModEffects.BURNS);
        h.assertTrue(left != null && left.getAmplifier() == BurnsEffect.SHOCK && left.getDuration() == 40, "второй подрыв сдвинул шок: " + left);

        ServerLevel level = h.getLevel();
        FakePlayer victim = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "burns_ally"));
        FakePlayer launcher = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "burns_launcher"));
        victim.moveTo(h.absoluteVec(new Vec3(32.5, 12, 36.5)));
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.addPlayerTeam("incoming_burns");
        try {
            team.setAllowFriendlyFire(false);
            board.addPlayerToTeam(victim.getScoreboardName(), team);
            board.addPlayerToTeam(launcher.getScoreboardName(), team);
            FlashBurns.burn(level, d, victim, 30, launcher);
            h.assertTrue(level(victim) < 0 && !victim.isOnFire(), "вспышка своего обожгла игрока: " + about(victim) + (victim.isOnFire() ? ", горит" : ""));
        } finally {
            board.removePlayerTeam(team);
        }

        healing.addEffect(new MobEffectInstance(ModEffects.BURNS, 5, 6));
        FlashBurns.burn(level, d, healing, third, null);
        h.assertTrue(level(healing) == 6, "ожог слабее прежнего сменил прежний сразу: " + about(healing));

        shocked.addEffect(new MobEffectInstance(ModEffects.BURNS, 5, BurnsEffect.SHOCK));
        resisting.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
        resisting.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 200, 4));
        resisting.addEffect(new MobEffectInstance(ModEffects.BURNS, 5, BurnsEffect.SHOCK));
        h.succeedWhen(() -> {
            h.assertFalse(shocked.isAlive(), "шок не убил");
            h.assertFalse(resisting.isAlive(), "шок не убил под огнестойкостью и сопротивлением V");
            MobEffectInstance healed = healing.getEffect(ModEffects.BURNS);
            h.assertTrue(healed != null && healed.getAmplifier() == 4 && healed.getDuration() > 24_000,
                    "прежний ожог кончился, а предел не новый: " + healed);
        });
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
        return String.format(Locale.ROOT, "уровень ожогов %d, здоровье %.2f из %.2f", level(e), e.getHealth(), e.getMaxHealth());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
