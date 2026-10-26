package ua.zentix.incoming.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Mobile launcher near the shooter: trailer with an inclined rail. Shaheds use five cells (as on Iranian
 * launchers; loitering munitions also launch from it by catapult); cruise missiles use two inclined canisters.
 * Appears near the player on first launch, deploys the rack (raises to elevation), then serves all nearby
 * launches: the salvo uses one launcher cell by cell. A hit breaks it (no drops); “Abort” removes all.
 * <p>
 * A launcher at an ordered launch site ({@code /incoming salvo … from x z}, {@link #ordered}) stays where placed
 * until “Abort” or a hit: it does not count as an extra owner launcher ({@code LaunchSite.deploy}).
 */
public class LauncherEntity extends Entity implements Launcher {
    private static final EntityDataAccessor<Byte> DATA_WEAPON = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Long> DATA_DEPLOYED = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** Ticks for the rack to rise to elevation. */
    public static final int DEPLOY_TICKS = 40;

    private final LaunchQueue queue = new LaunchQueue();
    /** At the ordered launch site rather than near the shooter (server only: client does not need it). */
    private boolean ordered;
    /**
     * Ordered launch-site mark used for placement (horizontal; actual placement is the nearest suitable site in
     * its area, {@code LaunchSite.PLACE_AREA}); null if near the shooter or placed before marks were remembered.
     */
    @Nullable
    private Vec3 mark;

    public LauncherEntity(EntityType<? extends LauncherEntity> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
    }

    /** @param ordered at the ordered launch site ({@link #ordered}) */
    public static LauncherEntity create(ServerLevel level, Vec3 pos, float yaw, WeaponType weapon, @Nullable UUID owner, boolean ordered) {
        return create(level, pos, yaw, weapon, owner, ordered, null);
    }

    /** @param mark ordered launch-site mark ({@link #mark}) */
    public static LauncherEntity create(ServerLevel level, Vec3 pos, float yaw, WeaponType weapon, @Nullable UUID owner, boolean ordered,
                                        @Nullable Vec3 mark) {
        LauncherEntity l = new LauncherEntity(ModEntities.LAUNCHER.get(), level);
        l.ordered = ordered;
        l.mark = mark == null ? null : new Vec3(mark.x, 0, mark.z);
        l.moveTo(pos.x, pos.y, pos.z, yaw, 0);
        l.yRotO = yaw;
        l.entityData.set(DATA_WEAPON, (byte) weapon.id());
        l.entityData.set(DATA_DEPLOYED, level.getGameTime());
        l.entityData.set(DATA_OWNER, Optional.ofNullable(owner));
        return l;
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.byId(entityData.get(DATA_WEAPON));
    }

    @Nullable
    public UUID ownerId() {
        return entityData.get(DATA_OWNER).orElse(null);
    }

    /** At the ordered launch site until “Abort” or a hit, rather than one of the launchers following the shooter. */
    public boolean ordered() {
        return ordered;
    }

    /** Ordered launch-site mark ({@link #mark}); null if absent. */
    @Nullable
    public Vec3 mark() {
        return mark;
    }

    /** Projectile of this launcher: same weapon and owner (one nearby launcher per player and weapon). */
    public boolean serves(StrikeProjectile p) {
        return p.weapon() == weapon() && Objects.equals(p.ownerId(), ownerId());
    }

    @Override
    public float yaw() {
        return getYRot();
    }

    @Override
    public LauncherMount mount() {
        return LauncherMount.TRAILER;
    }

    @Override
    public long deployedAt() {
        return entityData.get(DATA_DEPLOYED);
    }

    @Override
    public LaunchQueue queue() {
        return queue;
    }

    /** Weapon launcher rack ({@link LauncherRack#of}). */
    public static LauncherRack rack(WeaponType weapon) {
        return LauncherRack.of(weapon);
    }

    /** Rail elevation (spec): Shahed 15°, loitering catapult 20°, missiles 40°, MLRS tubes 50°. */
    public static float elevation(WeaponType weapon) {
        return rack(weapon).elevation();
    }

    public static int slots(WeaponType weapon) {
        return rack(weapon).slots();
    }

    /** MLRS rack: 4 rows of 10 tubes, tube spacing and length (blocks). */
    public static final int ROCKET_COLUMNS = 10, ROCKET_ROWS = 4;
    public static final float TUBE_PITCH = 0.3f, TUBE_LENGTH = 3.2f;

    /**
     * Projectile center on {@code slot}'s rail for a {@code weapon} trailer at {@code pos} with heading {@code
     * yaw}.
     */
    public static Vec3 railPoint(Vec3 pos, float yaw, WeaponType weapon, int slot) {
        return LauncherMount.TRAILER.railPoint(pos, yaw, weapon, slot);
    }

    /** Loitering munition rails: three below, two above (0.83 m between axes). */
    public static float loiterLeft(int slot) {
        return slot < 3 ? 0.83f - 0.83f * slot : slot == 3 ? 0.415f : -0.415f;
    }

    public static float loiterUp(int slot) {
        return slot < 3 ? 0.5f : 1.35f;
    }

    /** Minimum launch interval from one launcher, ticks: MLRS half a second, others 0.8 s. */
    public static int spacing(WeaponType weapon) {
        return rack(weapon).spacing();
    }

    /** The entire trailer turns. */
    @Override
    public void turnTo(float yaw, long now) {
        if (turnedYaw(yaw, now) == getYRot()) return;
        setYRot(yaw);
        yRotO = yaw;
        entityData.set(DATA_DEPLOYED, now);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide && level().getGameTime() - deployedAt() < DEPLOY_TICKS) {
            // Hydraulics raise the rack, puffing bluish vapor at the pivot.
            Vec3 pivot = mount().pivot(position(), getYRot()).subtract(0, 0.6, 0);
            if (random.nextInt(3) == 0) level().addParticle(ParticleTypes.CLOUD, pivot.x, pivot.y, pivot.z, 0, 0.02, 0);
        }
    }

    // ---------------------------------------------------------------- a hit breaks it

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isAttackable() {
        return true;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isRemoved() || isInvulnerableTo(source) || source.is(DamageTypes.IN_WALL)) return false;
        if (level() instanceof ServerLevel level && source.getEntity() != null) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.2, getZ(), 20, 1.5, 0.8, 1.5, 0.02);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.5f, 0.6f);
            discard();
        }
        return true;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 256 * 256;
    }

    // ---------------------------------------------------------------- data

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_WEAPON, (byte) WeaponType.DRONE.id());
        builder.define(DATA_DEPLOYED, 0L);
        builder.define(DATA_OWNER, Optional.empty());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        WeaponType w = WeaponType.parse(tag.getString("weapon"));
        entityData.set(DATA_WEAPON, (byte) (w == null ? WeaponType.DRONE : w).id());
        entityData.set(DATA_DEPLOYED, tag.getLong("deployed"));
        entityData.set(DATA_OWNER, tag.hasUUID("owner") ? Optional.of(tag.getUUID("owner")) : Optional.empty());
        ordered = tag.getBoolean("ordered");
        mark = tag.contains("mark_x") ? new Vec3(tag.getDouble("mark_x"), 0, tag.getDouble("mark_z")) : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("weapon", weapon().getSerializedName());
        tag.putLong("deployed", deployedAt());
        UUID owner = ownerId();
        if (owner != null) tag.putUUID("owner", owner);
        if (ordered) tag.putBoolean("ordered", true);
        if (mark != null) {
            tag.putDouble("mark_x", mark.x);
            tag.putDouble("mark_z", mark.z);
        }
    }
}
