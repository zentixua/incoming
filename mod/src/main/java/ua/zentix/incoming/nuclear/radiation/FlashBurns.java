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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
 * Fireball light burns across the body ({@link BurnInjury}, DESIGN-nuke §4): the side facing the ball burns — half the
 * skin (§12.60); exposed skin by its burn degree, clothing only when ignited (then the body burns; water extinguishes).
 * Body region is an armor slot: helmet — head, chestplate — torso and arms, leggings — legs, boots — feet. Empty slots
 * on humans (player, villagers, illagers, witch) mean clothing: face and hands with forearms exposed, shirt and trousers
 * thin fabric, footwear heavy fabric; animals and monsters have skin. Leather armor is heavy fabric, elytra thin
 * membrane, other armor metal. No thermal burns underwater (water absorbs light in the first centimeters); golems and
 * shulkers have no living skin.
 *
 * <p>Second- and third-degree burns remove the health share they represent of shock threshold (§12.62), keeping it from
 * returning while healing ({@link BurnsEffect}); at threshold, shock follows after 0.5–12 game hours, fatal untreated.
 * A clothed person's flash burns affect only face and arms — far below shock; ignited clothing burns like any fire
 * until extinguished (§12.60: flame burns beneath clothing).
 *
 * <p>A second burst burns the same body side: health share removed is the larger of the two, not their sum; wound is
 * only the increase. Each burn heals on its own schedule (cap set by the worst unhealed burn); ongoing shock keeps its
 * original schedule. A player protected by damage rules (no PvP, same team) is wholly unaffected by a player's flash,
 * including their own — skin and clothing, as with an arrow.
 */
public final class FlashBurns {
    /** Skin share per slot on the ball-facing side, % (half the rule of nines): head, torso with arms, legs, feet. */
    private static final double HEAD = 4.5, BODY = 27, LEGS = 16.5, FEET = 1.5;
    /** Clothed person's exposed hands and forearms (short-sleeved shirt), % of ball-facing body. */
    private static final double FOREARMS = 5.4;
    /** Ignited clothing burn duration if not extinguished, s (estimate: thin fabric burns through sooner). */
    private static final int THIN_FIRE_S = 10, HEAVY_FIRE_S = 20;
    private static final int TICKS_PER_HOUR = 1000, TICKS_PER_DAY = 24 * TICKS_PER_HOUR;
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private FlashBurns() {}

    /** Burns from fluence {@code q} cal/cm² at the eyes of an entity seeing the ball. */
    public static void burn(ServerLevel level, Detonation d, LivingEntity e, double q, @Nullable Entity owner) {
        burn(level, d, e, q, owner, true);
    }

    /** Same; {@code ignite} false — clothes do not catch fire (a body loaded long after the flash, {@code LateExposure}). */
    public static void burn(ServerLevel level, Detonation d, LivingEntity e, double q, @Nullable Entity owner, boolean ignite) {
        if (e.fireImmune() || e instanceof ArmorStand || e instanceof AbstractGolem || e.isInvulnerable() || underWater(level, e)
                || e.hasEffect(MobEffects.FIRE_RESISTANCE) || q < BurnInjury.lowest(d.yieldKt())) return;
        if (e instanceof Player victim && owner instanceof Player attacker && !victim.canHarmPlayer(attacker)) return;
        // individual skin per subject and burst, shared across all burn degrees
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
        if (fire > 0 && ignite) e.igniteForSeconds(fire);
        int degree = BurnInjury.degree(q, d.yieldKt(), z);
        if (degree == 0 || skin <= 0 || e.getType().is(EntityTypeTags.UNDEAD)) return;
        MobEffectInstance had = e.getEffect(ModEffects.BURNS);
        int before = had == null ? -1 : had.getAmplifier();
        if (before >= BurnsEffect.SHOCK) return;
        int amplifier = BurnInjury.level(BurnInjury.shock(degree == 2 ? skin : 0, degree == 3 ? skin : 0));
        int ticks = amplifier == BurnsEffect.SHOCK
                ? (int) Math.round(BurnInjury.shockHours(unit(HashCommon.mix(h))) * TICKS_PER_HOUR)
                : (int) Math.round(BurnInjury.HEAL_DAYS[degree - 1] * TICKS_PER_DAY);
        // wound first, health cap second: otherwise the cap cuts health and the wound removes the same share again.
        // Wound is down to the cap from new and previous burns together: previous burns already removed their share
        float dmg = (float) (BurnInjury.wound(before, amplifier) * unburned(e));
        if (dmg >= 0.5f && !(e instanceof Player p && p.getAbilities().invulnerable)) {
            e.hurt(ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_THERMAL, null, owner), dmg);
        }
        // each burn has its own duration: vanilla keeps a weaker, longer effect underneath, exposing it when the stronger ends.
        // No surrounding particles (obscure the eyes in first person), icon only
        if (e.isAlive()) e.addEffect(new MobEffectInstance(ModEffects.BURNS, ticks, amplifier, false, false, true));
    }

    /**
     * Health cap without burns: dividing the current cap by the share burns left would overestimate it for small mobs —
     * vanilla never lowers the cap below one (a 4-health chicken at level 9 has 1, not 0.4).
     */
    private static double unburned(LivingEntity e) {
        AttributeInstance health = e.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) return e.getMaxHealth();
        AttributeInstance bare = new AttributeInstance(health.getAttribute(), a -> {});
        bare.replaceFrom(health);
        bare.removeModifier(BurnsEffect.ID);
        return bare.getValue();
    }

    /**
     * Eyes underwater are checked in the world itself, as {@code Entity.updateFluidOnEyes}: an entity's water flag is set
     * only by its tick, whereas the pulse includes entities not yet ticked (fresh chunk, chunk without entity ticking).
     */
    private static boolean underWater(ServerLevel level, LivingEntity e) {
        double y = e.getEyeY();
        BlockPos pos = BlockPos.containing(e.getX(), y, e.getZ());
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return false;
        FluidState water = level.getFluidState(pos);
        return water.is(FluidTags.WATER) && pos.getY() + water.getHeight(level, pos) > y;
    }

    /** Whether humans wear clothing: player, villagers and trader, illagers, witch. */
    private static boolean dressed(LivingEntity e) {
        return e instanceof Player || e instanceof Npc || e instanceof AbstractIllager || e instanceof Witch;
    }

    /** Body region cover: slot item, or if empty, clothing for humans and skin for others. */
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

    /** Number 0..1 (excluding endpoints) from a hash. */
    private static double unit(long h) {
        return ((h >>> 11) + 0.5) * 0x1.0p-53;
    }
}
