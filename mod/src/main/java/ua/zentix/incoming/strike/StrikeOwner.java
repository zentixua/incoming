package ua.zentix.incoming.strike;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Live combat credit (roadmap B3): who a strike's damage names as its attacker. The owner UUID is persistent (orders,
 * rounds, projectiles, debris, burns, events); the attacker entity is looked up from it at the moment of damage, from the
 * server's player list, so an owner in any level is credited and an offline owner is not (nothing is held for later).
 * A dead owner who has not respawned is still in the list and is credited, as vanilla credits an arrow whose shooter
 * died; after respawn the list returns the new player object. Fake players are not in the list: they are credited only
 * when their profile is an online player's, who is then the one credited.
 */
public final class StrikeOwner {
    private StrikeOwner() {}

    /** The owner as an online player in any level of this server, or null (unowned, offline). */
    @Nullable
    public static ServerPlayer online(@Nullable MinecraftServer server, @Nullable UUID owner) {
        return owner == null || server == null ? null : server.getPlayerList().getPlayer(owner);
    }

    @Nullable
    public static ServerPlayer online(ServerLevel level, @Nullable UUID owner) {
        return online(level.getServer(), owner);
    }

    /**
     * {@code victim.hurt(source, amount)} for strike damage. Vanilla makes the attacker a mob's revenge target
     * ({@code setLastHurtByMob}); an attacker in another level cannot be pursued (target goals measure distance by raw
     * coordinates across levels), so that revenge mark is dropped. Kill credit ({@code lastHurtByPlayer}), loot, experience
     * and statistics stay with the attacker.
     */
    public static boolean hurt(Entity victim, DamageSource source, float amount) {
        boolean hurt = victim.hurt(source, amount);
        if (source.getEntity() instanceof LivingEntity attacker && attacker.level() != victim.level()
                && victim instanceof LivingEntity living && living.getLastHurtByMob() == attacker) {
            living.setLastHurtByMob(null);
        }
        return hurt;
    }
}
