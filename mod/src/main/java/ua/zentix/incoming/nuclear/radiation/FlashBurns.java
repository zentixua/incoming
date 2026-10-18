package ua.zentix.incoming.nuclear.radiation;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BurnInjury;
import ua.zentix.incoming.nuclear.model.BurnInjury.Cover;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModEffects;

/**
 * Ожоги светом шара по телу ({@link BurnInjury}, DESIGN-nuke §4): обжигается сторона тела к шару — половина его кожи
 * (§12.60), открытая кожа — по своей степени, одежда — только когда загорится (тогда тело горит, вода тушит). Место
 * тела — слот брони: шлем — голова, нагрудник — туловище и руки, поножи — ноги, ботинки — ступни. Пустой слот у людей
 * (игрок, жители, разбойники, ведьма) — одежда: лицо и кисти с предплечьями открыты, рубашка и брюки — тонкая ткань,
 * обувь — плотная; у животных и чудищ — кожа. Кожаная броня — плотная ткань, остальная — металл.
 *
 * <p>Ожоги 2-й и 3-й степени отнимают ту долю здоровья, какую составляют от порога шока (§12.62), и не дают ей
 * вернуться, пока заживают ({@link BurnsEffect}); от порога — шок через 0,5–12 игровых часов, без лечения смертельный.
 */
public final class FlashBurns {
    /** Доля кожи по слотам стороной к шару, % (правило девяток, половина): голова, туловище с руками, ноги, ступни. */
    private static final double HEAD = 4.5, BODY = 27, LEGS = 16.5, FEET = 1.5;
    /** Открытые у одетого кисти и предплечья (рубашка с коротким рукавом), % тела стороной к шару. */
    private static final double FOREARMS = 5.4;
    /** Сколько горит загоревшаяся одежда, если её не потушить, с (оценка: тонкая ткань прогорает быстрее). */
    private static final int THIN_FIRE_S = 10, HEAVY_FIRE_S = 20;
    private static final int TICKS_PER_HOUR = 1000, TICKS_PER_DAY = 24 * TICKS_PER_HOUR;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private FlashBurns() {}

    /** Ожоги от импульса {@code q} кал/см² у глаз сущности, которая видит шар. */
    public static void burn(ServerLevel level, Detonation d, LivingEntity e, double q, @Nullable Entity owner) {
        if (e.fireImmune() || e instanceof ArmorStand || e.hasEffect(MobEffects.FIRE_RESISTANCE) || q < BurnInjury.lowest(d.yieldKt())) return;
        // своя кожа у каждого и у каждого подрыва, одна на все степени
        long h = HashCommon.mix(e.getUUID().getMostSignificantBits() ^ HashCommon.mix(e.getUUID().getLeastSignificantBits() ^ d.id() * 0x9E3779B97F4A7C15L));
        double z = Fragility.inverse(unit(h));
        boolean dressed = dressed(e);
        double skin = 0;
        int fire = 0;
        for (EquipmentSlot slot : SLOTS) {
            Cover cover = cover(e.getItemBySlot(slot), slot, dressed);
            double area = area(slot);
            if (slot == EquipmentSlot.CHEST && cover == Cover.THIN) {
                skin += FOREARMS;
                area -= FOREARMS;
            }
            if (cover == Cover.SKIN) skin += area;
            else if (BurnInjury.ignites(cover, q, d.yieldKt())) fire = Math.max(fire, cover == Cover.THIN ? THIN_FIRE_S : HEAVY_FIRE_S);
        }
        if (fire > 0) e.igniteForSeconds(fire);
        int degree = BurnInjury.degree(q, d.yieldKt(), z);
        if (degree == 0 || skin <= 0 || e.getType().is(EntityTypeTags.UNDEAD)) return;
        double share = BurnInjury.shock(degree == 2 ? skin : 0, degree == 3 ? skin : 0);
        int amplifier = BurnsEffect.level(share);
        int ticks = amplifier == BurnsEffect.SHOCK
                ? (int) Math.round(BurnInjury.shockHours(unit(HashCommon.mix(h))) * TICKS_PER_HOUR)
                : (int) Math.round(BurnInjury.HEAL_DAYS[degree - 1] * TICKS_PER_DAY);
        // сперва рана, потом предел здоровья: иначе предел срезал бы здоровье, и рана отняла бы ту же долю второй раз
        float dmg = (float) (Math.min(share, 1 - BurnsEffect.SHOCK_LEFT) * e.getMaxHealth());
        if (dmg >= 0.5f && !(e instanceof Player p && p.getAbilities().invulnerable)) {
            e.hurt(ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_THERMAL, null, owner), dmg);
        }
        // без частиц вокруг (в первом лице они лезут в глаза), только значок
        if (e.isAlive()) e.addEffect(new MobEffectInstance(ModEffects.BURNS, ticks, amplifier, false, false, true));
    }

    /** Одеты ли люди: игрок, жители и торговец, разбойники, ведьма. */
    private static boolean dressed(LivingEntity e) {
        return e instanceof Player || e instanceof Npc || e instanceof AbstractIllager || e instanceof Witch;
    }

    /** Чем закрыто место тела: предмет в слоте или, если слот пуст, одежда у людей и кожа у остальных. */
    static Cover cover(ItemStack stack, EquipmentSlot slot, boolean dressed) {
        if (stack.isEmpty()) {
            if (!dressed) return Cover.SKIN;
            return switch (slot) {
                case HEAD -> Cover.SKIN;
                case FEET -> Cover.HEAVY;
                default -> Cover.THIN;
            };
        }
        if (stack.getItem() instanceof ArmorItem armor && armor.getMaterial().is(ArmorMaterials.LEATHER)) return Cover.HEAVY;
        return Cover.SOLID;
    }

    private static double area(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> HEAD;
            case CHEST -> BODY;
            case LEGS -> LEGS;
            default -> FEET;
        };
    }

    /** Число 0..1 (без краёв) из хеша. */
    private static double unit(long h) {
        return ((h >>> 11) + 0.5) * 0x1.0p-53;
    }
}
