package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.EffectCure;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.model.BurnInjury;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.util.WorldClock;

import java.util.Set;

/**
 * Fireball light burns ({@link FlashBurns}): effect lasts while burns heal, capping health at what they left throughout.
 * Level is the health-loss step ({@link BurnInjury#level}, tenths); {@link #SHOCK} means burn shock: health
 * {@link BurnInjury#SHOCK_LEFT}, death at expiry ({@code burn_shock} damage: unaffected by armor, fire resistance,
 * resistance or enchantments). Milk and other remedies do not heal burns; a totem of undying saves as from any death.
 * <p>
 * Healing and shock run on the world clock ({@link WorldClock}), not game ticks: once a second the remaining duration
 * (and that of weaker burns kept beneath) is corrected by how far the world clock moved — a slow or stopped day stretches
 * them to real hours, a night's sleep heals a night's worth.
 */
public class BurnsEffect extends MobEffect {
    /** Effect level for a burned subject facing shock. */
    public static final int SHOCK = BurnInjury.SHOCK_LEVEL;
    /** Health cap modifier. */
    static final ResourceLocation ID = Incoming.id("burns");

    public BurnsEffect() {
        super(MobEffectCategory.HARMFUL, 0xB5461E);
        addAttributeModifier(Attributes.MAX_HEALTH, ID, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, amplifier -> -BurnInjury.lost(amplifier));
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return amplifier >= SHOCK && duration == 1 || duration % WorldClock.PACE_TICKS == 0;
    }

    @Override
    public boolean applyEffectTick(LivingEntity e, int amplifier) {
        MobEffectInstance burns = e.getEffect(ua.zentix.incoming.registry.ModEffects.BURNS);
        if (amplifier >= SHOCK && (burns == null || burns.getDuration() == 1)) {
            e.hurt(ModDamageTypes.source(e.level(), ModDamageTypes.BURN_SHOCK, null, null), Float.MAX_VALUE);
            return true;
        }
        if (burns != null && !e.level().isClientSide()) pace(e, burns, WorldClock.PACE_TICKS - WorldClock.lastSecond(e.level()));
        return true;
    }

    /**
     * Burns took {@link WorldClock#PACE_TICKS} game ticks of healing; the world clock moved by a different amount: add the
     * difference ({@code ticks}, positive — slower day) to the remaining time of this burn and those beneath it. Never past
     * the end (shock fires on the tick after).
     */
    static void pace(LivingEntity e, MobEffectInstance burns, double ticks) {
        int add = (int) Math.round(ticks);
        if (add == 0) return;
        for (MobEffectInstance b = burns; b != null; b = b.hiddenEffect) {
            if (!b.isInfiniteDuration()) b.duration = (int) Math.max(2, Math.min(Integer.MAX_VALUE / 2, (long) b.duration + add));
        }
        // the client counts the icon's time down itself: tell it after a large change (sleep), not every second
        if (Math.abs(add) >= WorldClock.PACE_TICKS * 10 && e instanceof net.minecraft.server.level.ServerPlayer p) {
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket(p.getId(), burns, false));
        }
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // burns heal only with time
    }
}
