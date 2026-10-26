package ua.zentix.incoming.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.LoiterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.SpentBoosterEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.strike.WeaponType;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> REGISTER = DeferredRegister.create(Registries.ENTITY_TYPE, Incoming.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> DRONE =
            projectile("drone", EntityType.Builder.of(DroneEntity::new, MobCategory.MISC).sized(1.6f, 0.6f), 20);
    public static final DeferredHolder<EntityType<?>, EntityType<CruiseMissileEntity>> CRUISE_MISSILE =
            projectile("cruise_missile", EntityType.Builder.of(CruiseMissileEntity::new, MobCategory.MISC).sized(0.8f, 0.8f), 24);
    public static final DeferredHolder<EntityType<?>, EntityType<RocketEntity>> ROCKET =
            projectile("rocket", EntityType.Builder.of(RocketEntity::new, MobCategory.MISC).sized(0.5f, 0.5f), 20);
    public static final DeferredHolder<EntityType<?>, EntityType<LoiterEntity>> LOITER =
            projectile("loiter", EntityType.Builder.of(LoiterEntity::new, MobCategory.MISC).sized(0.67f, 0.33f), 20);
    public static final DeferredHolder<EntityType<?>, EntityType<BomberEntity>> BOMBER =
            projectile("bomber", EntityType.Builder.of(BomberEntity::new, MobCategory.MISC).sized(6.0f, 1.5f), 32);
    public static final DeferredHolder<EntityType<?>, EntityType<BunkerBusterEntity>> BUNKER_BUSTER =
            projectile("bunker_buster", EntityType.Builder.of(BunkerBusterEntity::new, MobCategory.MISC).sized(0.8f, 0.8f), 24);

    public static final DeferredHolder<EntityType<?>, EntityType<IcbmEntity>> ICBM =
            projectile("icbm", EntityType.Builder.of(IcbmEntity::new, MobCategory.MISC).sized(2.0f, 2.0f), 32);

    /** Mobile launcher near the shooter: stands on ground, position unchanged. */
    public static final DeferredHolder<EntityType<?>, EntityType<LauncherEntity>> LAUNCHER = REGISTER.register("launcher",
            () -> EntityType.Builder.<LauncherEntity>of(LauncherEntity::new, MobCategory.MISC)
                    .sized(3.2f, 2.4f)
                    .clientTrackingRange(16)
                    .updateInterval(20)
                    .fireImmune()
                    .noSummon()
                    .build(Incoming.MOD_ID + ":launcher"));

    /**
     * Spent booster: falls ballistically like debris. Lives seconds, not world-saved ({@code noSave}): a chunk unloaded
     * mid-fall does not retain it until next load.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<SpentBoosterEntity>> SPENT_BOOSTER = REGISTER.register("spent_booster",
            () -> EntityType.Builder.<SpentBoosterEntity>of(SpentBoosterEntity::new, MobCategory.MISC)
                    .sized(0.6f, 0.6f)
                    .clientTrackingRange(16)
                    .updateInterval(2)
                    .fireImmune()
                    .noSummon()
                    .noSave()
                    .build(Incoming.MOD_ID + ":spent_booster"));

    /**
     * Debris: flies ballistically, lands as a block or breaks apart. Like falling_block, position every few ticks.
     * Not world-saved ({@code noSave}): target area releases shortly after explosion; debris still flying in its chunks
     * would otherwise save with them and hang until next load (one save had 10 427 debris entities).
     */
    public static final DeferredHolder<EntityType<?>, EntityType<DebrisEntity>> DEBRIS = REGISTER.register("debris",
            () -> EntityType.Builder.<DebrisEntity>of(DebrisEntity::new, MobCategory.MISC)
                    .sized(0.98f, 0.98f)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .fireImmune()
                    .noSummon()
                    .noSave()
                    .build(Incoming.MOD_ID + ":debris"));

    /**
     * Surface-to-air missile — flight tracked by {@code defense.DefenseWorld}: position every tick (14 blocks/tick),
     * not saved ({@code noSave}): flight lasts seconds, absent after restart.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<InterceptorEntity>> INTERCEPTOR = REGISTER.register("interceptor",
            () -> EntityType.Builder.<InterceptorEntity>of(InterceptorEntity::new, MobCategory.MISC)
                    .sized(0.4f, 0.4f)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .fireImmune()
                    .noSummon()
                    .noSave()
                    .build(Incoming.MOD_ID + ":interceptor"));

    private ModEntities() {}

    /** Rebuilt from chunk surface marks; never save a second copy of the damage as entities. */
    public static final DeferredHolder<EntityType<?>, EntityType<Display.BlockDisplay>> SURFACE_DAMAGE = REGISTER.register("surface_damage",
            () -> EntityType.Builder.<Display.BlockDisplay>of(Display.BlockDisplay::new, MobCategory.MISC)
                    .sized(1, 0.2f).clientTrackingRange(8).updateInterval(20)
                    .fireImmune().noSummon().noSave().build(Incoming.MOD_ID + ":surface_damage"));

    /** Entity carrying a weapon (B-2 — bomber itself until release): from the weapon spec. */
    public static EntityType<? extends StrikeProjectile> of(WeaponType weapon) {
        return weapon.spec().airframe().entity().get();
    }

    /**
     * Fast projectiles (B-2 up to 12 blocks/tick), so clients get position every tick and tracking range exceeds vanilla:
     * they must be visible and audible from afar.
     */
    private static <T extends Entity> DeferredHolder<EntityType<?>, EntityType<T>> projectile(String name, EntityType.Builder<T> builder, int trackingChunks) {
        return REGISTER.register(name, () -> builder
                .clientTrackingRange(trackingChunks)
                .updateInterval(1)
                .fireImmune()
                .noSummon()
                .build(Incoming.MOD_ID + ":" + name));
    }
}
