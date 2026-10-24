package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Local;

/**
 * Отработавший стартовый ускоритель: отделяется от шахеда или ракеты, кувыркаясь, падает по баллистике,
 * первые секунды дымит, сопло светится по остыванию, падает с глухим стуком. Перед очисткой модель и свечение
 * вместе плавно исчезают (не по физике, {@link BoosterLifetime}).
 * Никого не калечит (корпус пустой и лёгкий) и блоков не ломает.
 */
public class SpentBoosterEntity extends Entity {
    private static final EntityDataAccessor<Byte> DATA_WEAPON = SynchedEntityData.defineId(SpentBoosterEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Long> DATA_BORN_AT = SynchedEntityData.defineId(SpentBoosterEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_REMOVE_AT = SynchedEntityData.defineId(SpentBoosterEntity.class, EntityDataSerializers.LONG);

    private int landedAt = -1;
    /** Клиент: кувыркание, градусов за тик. */
    public final float spin;

    public SpentBoosterEntity(EntityType<? extends SpentBoosterEntity> type, Level level) {
        super(type, level);
        this.spin = (level.random.nextFloat() * 0.6f + 0.7f) * (level.random.nextBoolean() ? 18f : -18f);
        if (!level.isClientSide) setLifetime(0, BoosterLifetime.MAX_AGE);
    }

    /** Отделить ускоритель от снаряда: у хвоста, со скоростью снаряда, чуть вниз и в сторону. */
    public static void spawn(ServerLevel level, StrikeProjectile from, double tail) {
        SpentBoosterEntity b = new SpentBoosterEntity(ModEntities.SPENT_BOOSTER.get(), level);
        Vec3 at = Local.at(from.position(), from.getYRot(), from.getXRot(), 0, -0.5, -tail * 0.8);
        Vec3 v = from.getDeltaMovement().scale(0.55).add((level.random.nextDouble() - 0.5) * 0.2, -0.15, (level.random.nextDouble() - 0.5) * 0.2);
        b.moveTo(at.x, at.y, at.z, from.getYRot(), from.getXRot());
        b.setDeltaMovement(v);
        b.entityData.set(DATA_WEAPON, (byte) from.weapon().id());
        level.addFreshEntity(b);
    }

    public WeaponType weapon() {
        return WeaponType.byId(entityData.get(DATA_WEAPON));
    }

    public int age() {
        return (int) Math.clamp(level().getGameTime() - entityData.get(DATA_BORN_AT), 0, Integer.MAX_VALUE);
    }

    /** Те же часы у обеих сторон: поздняя загрузка сущности не начинает нагрев и очистку заново. */
    public float visibility(float partialTick) {
        return BoosterLifetime.visibility(remainingTicks() - partialTick);
    }

    public long remainingTicks() {
        return entityData.get(DATA_REMOVE_AT) - level().getGameTime();
    }

    private void setLifetime(int age, long remaining) {
        entityData.set(DATA_BORN_AT, level().getGameTime() - age);
        entityData.set(DATA_REMOVE_AT, level().getGameTime() + remaining);
    }

    public boolean landed() {
        return onGround();
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 v = getDeltaMovement();
        if (!onGround()) {
            // тяжёлый корпус: гравитация сильнее, чем у предметов, сопротивление слабое
            v = v.scale(0.985).add(0, -0.06, 0);
        } else {
            v = v.multiply(0.5, 0, 0.5);
        }
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        // дым и горячее сопло у клиента — слой эффектов (Exhaust.spent, NozzleGlow)
        if (level().isClientSide) return;
        if (onGround() && landedAt < 0) {
            landedAt = age();
            entityData.set(DATA_REMOVE_AT, Math.min(entityData.get(DATA_REMOVE_AT), level().getGameTime() + BoosterLifetime.LIE_TICKS));
            level().playSound(null, BlockPos.containing(position()), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 1.2f, 0.5f);
        }
        // За дном мира — та же плавная очистка: наблюдатель в режиме зрителя тоже может видеть сопло.
        if (getY() < level().getMinBuildHeight() - 32) {
            entityData.set(DATA_REMOVE_AT, Math.min(entityData.get(DATA_REMOVE_AT),
                    level().getGameTime() + BoosterLifetime.FADE_TICKS + BoosterLifetime.HIDDEN_TICKS));
        }
        if (remainingTicks() <= 0) discard();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 512 * 512;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_WEAPON, (byte) 0);
        builder.define(DATA_BORN_AT, 0L);
        builder.define(DATA_REMOVE_AT, 0L);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        int age = tag.getInt("age");
        landedAt = tag.contains("landedAt") ? tag.getInt("landedAt") : -1;
        long remaining = tag.contains("remaining") ? tag.getLong("remaining") : BoosterLifetime.MAX_AGE - age;
        setLifetime(age, remaining);
        entityData.set(DATA_WEAPON, tag.getByte("weapon"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("age", age());
        tag.putInt("landedAt", landedAt);
        tag.putLong("remaining", remainingTicks());
        tag.putByte("weapon", entityData.get(DATA_WEAPON));
    }
}
