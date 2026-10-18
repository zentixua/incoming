package ua.zentix.incoming.nuclear.radiation;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FluidState;
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
 * обувь — плотная; у животных и чудищ — кожа. Кожаная броня — плотная ткань, элитры — тонкая перепонка, остальная —
 * металл. Под водой свет не обжигает (вода поглощает его в первых сантиметрах), у големов и шулкеров нет живой кожи.
 *
 * <p>Ожоги 2-й и 3-й степени отнимают ту долю здоровья, какую составляют от порога шока (§12.62), и не дают ей
 * вернуться, пока заживают ({@link BurnsEffect}); от порога — шок через 0,5–12 игровых часов, без лечения смертельный.
 * Одетого человека вспышка обжигает только по лицу и рукам — до шока далеко; загоревшаяся одежда жжёт, как всякий
 * огонь, пока её не потушить (§12.60: ожоги пламенем под одеждой).
 *
 * <p>Второй подрыв обжигает ту же сторону тела: отнятая доля — большая из двух, а не сумма, рана — только прибавка;
 * каждый ожог заживает своим сроком (предел — от самого тяжёлого из незаживших), шок, который уже идёт, — прежним.
 * Игрока, которого правила не дают ранить (без PvP, своя команда), вспышка подрыва другого игрока не трогает вовсе —
 * ни кожу, ни одежду, как стрела.
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
        if (e.fireImmune() || e instanceof ArmorStand || e instanceof AbstractGolem || e.isInvulnerable() || underWater(level, e)
                || e.hasEffect(MobEffects.FIRE_RESISTANCE) || q < BurnInjury.lowest(d.yieldKt())) return;
        if (e instanceof Player victim && owner instanceof Player attacker && !victim.canHarmPlayer(attacker)) return;
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
        MobEffectInstance had = e.getEffect(ModEffects.BURNS);
        int before = had == null ? -1 : had.getAmplifier();
        if (before >= BurnsEffect.SHOCK) return;
        double lost = before < 0 ? 0 : -BurnsEffect.lost(before);
        int amplifier = BurnsEffect.level(BurnInjury.shock(degree == 2 ? skin : 0, degree == 3 ? skin : 0));
        int ticks = amplifier == BurnsEffect.SHOCK
                ? (int) Math.round(BurnInjury.shockHours(unit(HashCommon.mix(h))) * TICKS_PER_HOUR)
                : (int) Math.round(BurnInjury.HEAL_DAYS[degree - 1] * TICKS_PER_DAY);
        // сперва рана, потом предел здоровья: иначе предел срезал бы здоровье, и рана отняла бы ту же долю второй раз.
        // Рана — до предела, который ожоги оставят вместе с прежними (те уже отняли свою долю: здоровье без них — до их
        // предела)
        double whole = e.getMaxHealth() / (1 - lost);
        float dmg = (float) ((-BurnsEffect.lost(Math.max(amplifier, before)) - lost) * whole);
        if (dmg >= 0.5f && !(e instanceof Player p && p.getAbilities().invulnerable)) {
            e.hurt(ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_THERMAL, null, owner), dmg);
        }
        // свой срок у каждого ожога: эффект слабее прежнего и дольше ваниль держит под ним и открывает, когда тот кончится.
        // Без частиц вокруг (в первом лице они лезут в глаза), только значок
        if (e.isAlive()) e.addEffect(new MobEffectInstance(ModEffects.BURNS, ticks, amplifier, false, false, true));
    }

    /**
     * Глаза под водой — по самому миру, как {@code Entity.updateFluidOnEyes}: признак воды у сущности ставит только её
     * тик, а импульс берёт и тех, кто ещё не тикал (свежий чанк, чанк без тика сущностей).
     */
    private static boolean underWater(ServerLevel level, LivingEntity e) {
        double y = e.getEyeY() - 0.11111111;
        BlockPos pos = BlockPos.containing(e.getX(), y, e.getZ());
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return false;
        FluidState water = level.getFluidState(pos);
        return water.is(FluidTags.WATER) && pos.getY() + water.getHeight(level, pos) > y;
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
        if (stack.getItem() instanceof ArmorItem armor && armor.getMaterial().value() == ArmorMaterials.LEATHER.value()) return Cover.HEAVY;
        if (stack.getItem() instanceof ElytraItem) return Cover.THIN;
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
