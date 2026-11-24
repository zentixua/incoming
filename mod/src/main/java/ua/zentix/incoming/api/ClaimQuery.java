package ua.zentix.incoming.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModParticles;
import ua.zentix.incoming.registry.ModSounds;

/**
 * Not a physical explosion: Incoming's question to claim protection. Before Incoming changes blocks outside a real
 * charge's own vanilla explosion (a crater, broken glass, fire, fragment dents, nuclear ruins, the flash, distant LOD
 * copies), it posts {@code ExplosionEvent.Detonate} on {@code NeoForge.EVENT_BUS} with an explosion of this class: zero
 * radius, no affected entities, its block list the positions about to change. A handler removes the positions it
 * protects, as it does for any explosion, and Incoming leaves those positions as they are. The event is posted on the
 * server thread, only for positions in chunks already in memory. Nothing else follows: the query is never finalized and
 * sends no explosion packet. Listeners that react to explosions themselves (effects, sounds, power grids) should skip it
 * ({@code event.getExplosion() instanceof ClaimQuery}).
 * <p>
 * Attribution follows the cause: a conventional strike's query carries its projectile as the direct source and the
 * strike damage source, as the strike's own explosion does; a nuclear detonation's query has no source entity (one
 * detonation is one cause whatever brought it).
 */
public final class ClaimQuery extends Explosion {
    public ClaimQuery(ServerLevel level, @Nullable Entity direct, @Nullable DamageSource source, Vec3 at) {
        super(level, direct, source, null, at.x, at.y, at.z, 0f, false, BlockInteraction.DESTROY_WITH_DECAY, ModParticles.NONE.get(),
                ModParticles.NONE.get(), ModSounds.SILENT);
    }
}
