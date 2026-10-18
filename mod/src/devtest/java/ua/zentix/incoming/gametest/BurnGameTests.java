package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

/** Ожоги светом шара по телу ({@link FlashBurns}): степень, одежда, броня, шок. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BurnGameTests {
    private BurnGameTests() {}

    private static final double YIELD = 15;

    /**
     * 15 кт, импульс, при котором ожог 3-й степени у любого: корова (кожа на половине тела стороной к шару) — шок,
     * здоровья 5 % (предел здоровья — не меньше единицы, как у ванили); житель в рубашке — 3-я степень на лице и руках (9,9 % тела): отнято 40 % здоровья, предел — 70 %,
     * одежда не загорелась. При 20 кал/см² у жителя загорается одежда. Разбойник в железе не обожжён и не горит, в
     * коже — горит (плотная ткань загорается), но кожа под ней цела. Ниже самого низкого порога — ничего. Шок в конце
     * срока убивает.
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

        FlashBurns.burn(h.getLevel(), d, cow, third, null);
        FlashBurns.burn(h.getLevel(), d, villager, third, null);
        FlashBurns.burn(h.getLevel(), d, burning, 20, null);
        FlashBurns.burn(h.getLevel(), d, iron, 30, null);
        FlashBurns.burn(h.getLevel(), d, leather, 20, null);
        FlashBurns.burn(h.getLevel(), d, faint, BurnInjury.lowest(YIELD) * 0.99, null);

        h.assertTrue(level(cow) == BurnsEffect.SHOCK && cow.getMaxHealth() <= Math.max(1, 10 * BurnsEffect.SHOCK_LEFT) + 1e-3 && cow.getHealth() <= 10 * BurnsEffect.SHOCK_LEFT + 1e-3, "корова при " + fmt(third) + " кал/см² без шока: " + about(cow));
        h.assertFalse(cow.isOnFire(), "корова загорелась: горит только одежда");
        h.assertTrue(level(villager) == 3 && Math.abs(villager.getMaxHealth() - 14) < 1e-3 && villager.getHealth() < 12.2f && villager.getHealth() > 11.9f,
                "житель в рубашке при " + fmt(third) + " кал/см²: " + about(villager));
        h.assertFalse(villager.isOnFire(), "рубашка жителя загорелась при " + fmt(third) + " кал/см²");
        h.assertTrue(burning.isOnFire() && level(burning) == 3, "житель при 20 кал/см²: " + about(burning) + (burning.isOnFire() ? "" : ", одежда не горит"));
        h.assertTrue(level(iron) < 0 && !iron.isOnFire(), "разбойник в железе обожжён: " + about(iron));
        h.assertTrue(level(leather) < 0 && leather.isOnFire(), "разбойник в коже при 20 кал/см²: " + about(leather) + (leather.isOnFire() ? "" : ", кожа не горит"));
        h.assertTrue(level(faint) < 0 && faint.getHealth() == faint.getMaxHealth(), "корова ниже порога обожжена: " + about(faint));

        shocked.addEffect(new MobEffectInstance(ModEffects.BURNS, 5, BurnsEffect.SHOCK));
        h.succeedWhen(() -> h.assertFalse(shocked.isAlive(), "шок не убил"));
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
