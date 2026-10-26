package ua.zentix.incoming.registry;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;

/**
 * Effect particles. Types are only needed by {@code /particle incoming:smoke}, producing a default particle. Client
 * spawns configured particles (size, lifetime color, incandescence, buoyancy) in its own pool and renders its own layer —
 * {@code client/fx/particle/Fx}, {@code client/fx/layer/FxLayer}; textures in {@code textures/fx/particle}
 * ({@code tools/gen_particles.py}) on its own atlas.
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> REGISTER = DeferredRegister.create(Registries.PARTICLE_TYPE, Incoming.MOD_ID);

    /** Smoke, dust, steam billow (4 billows × 4 dissipation stages). */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE = register("smoke");
    /** Flame billow. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FIRE = register("fire");
    /** Spark, incandescent fragment (stretched along velocity). */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPARK = register("spark");
    /** Flash. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FLASH = register("flash");
    /** Ground shock wave ring. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> RING = register("ring");
    /**
     * Nothing: server vanilla explosion particles ({@code Level.explode} sends them in the explosion packet). Client draws
     * the explosion itself ({@code client/fx/BlastEffects}), as it does sound ({@code ModSounds.SILENT}); vanilla TNT billows
     * on top would look like white rings with dark centers. Client provider creates no particles.
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> NONE = REGISTER.register("none", () -> new SimpleParticleType(false));

    private ModParticles() {}

    private static DeferredHolder<ParticleType<?>, SimpleParticleType> register(String name) {
        return REGISTER.register(name, () -> new SimpleParticleType(true));
    }
}
