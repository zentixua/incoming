package ua.zentix.incoming.target;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.util.Terrain;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Strike target. Moving entity/craft resolves to world point every tick; if gone (dead, other world, craft
 * disassembled), {@link #resolve} is empty and projectile flies to last known point.
 */
public sealed interface Target permits Target.Point, Target.Ground, Target.OfEntity, Target.OfSubLevel, Target.Sighted {
    Codec<Target> CODEC = Kind.CODEC.dispatch(Target::kind, Kind::codec);

    Optional<Vec3> resolve(ServerLevel level);

    /** Same target shifted (salvo dispersion). */
    Target offset(Vec3 delta);

    Kind kind();

    /** Target identity (labels/fuze): target itself, or sighted quarry ({@link Sighted#quarry}). */
    default Target subject() {
        return this;
    }

    /** Stationary world point. */
    record Point(Vec3 pos) implements Target {
        static final MapCodec<Point> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Point::pos)
        ).apply(i, Point::new));

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            return Optional.of(pos);
        }

        @Override
        public Target offset(Vec3 delta) {
            return new Point(pos.add(delta));
        }

        @Override
        public Kind kind() {
            return Kind.POINT;
        }
    }

    /**
     * Ground site at x/z (map point): height becomes local surface once chunk ready (target area preloaded);
     * before that, {@code pos.y} estimate. Neither moves nor becomes lost.
     */
    record Ground(Vec3 pos) implements Target {
        static final MapCodec<Ground> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Ground::pos)
        ).apply(i, Ground::new));

        /**
         * Site without client map (command/server): generator terrain estimate before chunk loads ({@link
         * Terrain.Source}).
         */
        public static Ground at(ServerLevel level, double x, double z) {
            return at(level, x, z, Optional.empty());
        }

        /**
         * Map site (x, z): only server knows height. Ready chunk gives top as map renders it (canopy, roofs:
         * projectile toward ground under canopy would burst there before arrival). Otherwise estimate from client
         * {@code mapSurface} (Distant Horizons/client chunks: map used to select site); if map empty there,
         * generator terrain (sources {@link Terrain.Source}). Once target chunk loads, {@link #surface} refines.
         *
         * @param mapSurface first air above ground from client map; ignore outside world height
         */
        public static Ground at(ServerLevel level, double x, double z, Optional<Integer> mapSurface) {
            Terrain.Surface estimate = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z),
                    Terrain.Allowed.ORDER.withMap(mapSurface));
            return new Ground(new Vec3(x, estimate.y() - 0.5, z));
        }

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            return Optional.of(surface(level));
        }

        /** Top block center (including leaves, like map); unready chunk uses {@link #at} estimate. */
        public Vec3 surface(ServerLevel level) {
            Terrain.Surface top = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(pos.x), Mth.floor(pos.z), Terrain.Allowed.CHUNK);
            return top.known() ? new Vec3(pos.x, top.y() - 0.5, pos.z) : pos;
        }

        @Override
        public Target offset(Vec3 delta) {
            return new Ground(pos.add(delta.x, 0, delta.z));
        }

        @Override
        public Kind kind() {
            return Kind.GROUND;
        }
    }

    /**
     * Entity (player, mob, train, Create contraption), hit point relative to its position. {@code spread} is
     * salvo horizontal offset: ground target point lies on ground at offset (cliff-edge target gets point below
     * rather than hanging over cliff and causing endless dives); airborne target (craft, flight) keeps target
     * height, like point salvo.
     */
    record OfEntity(UUID uuid, Vec3 offset, Vec3 spread) implements Target {
        static final MapCodec<OfEntity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("uuid").forGetter(OfEntity::uuid),
                Vec3.CODEC.fieldOf("offset").forGetter(OfEntity::offset),
                Vec3.CODEC.optionalFieldOf("spread", Vec3.ZERO).forGetter(OfEntity::spread)
        ).apply(i, OfEntity::new));

        public OfEntity(UUID uuid, Vec3 offset) {
            this(uuid, offset, Vec3.ZERO);
        }

        public static OfEntity of(Entity entity, Vec3 hit) {
            return new OfEntity(entity.getUUID(), hit.subtract(entity.position()));
        }

        /** Body center for username player strikes. */
        public static OfEntity center(Entity entity) {
            return new OfEntity(entity.getUUID(), new Vec3(0, entity.getBbHeight() * 0.5, 0));
        }

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            return resolve(level, level.getEntity(uuid));
        }

        /** Target point for already found same-UUID entity ({@code null} absent from this world). */
        public Optional<Vec3> resolve(ServerLevel level, @Nullable Entity e) {
            if (e == null || !e.isAlive()) return Optional.empty();
            Vec3 at = e.position().add(offset);
            if (spread.equals(Vec3.ZERO)) return Optional.of(at);
            double x = at.x + spread.x, z = at.z + spread.z;
            // Ground height only from ready chunk (target area preloaded); otherwise target height.
            if (!e.onGround()) return Optional.of(new Vec3(x, at.y, z));
            Terrain.Surface ground = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z), Terrain.Allowed.CHUNK);
            return Optional.of(new Vec3(x, ground.known() ? ground.y() - 0.5 : at.y, z));
        }

        @Override
        public Target offset(Vec3 delta) {
            return new OfEntity(uuid, offset, spread.add(delta));
        }

        @Override
        public Kind kind() {
            return Kind.ENTITY;
        }
    }

    /** Sable aircraft point in plot coordinates: follows craft however it flies. */
    record OfSubLevel(Vec3 plotPos) implements Target {
        static final MapCodec<OfSubLevel> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Vec3.CODEC.fieldOf("plot_pos").forGetter(OfSubLevel::plotPos)
        ).apply(i, OfSubLevel::new));

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            if (!SubLevels.isInPlot(level, plotPos)) return Optional.empty();
            return Optional.of(SubLevels.toWorld(level, plotPos));
        }

        @Override
        public Target offset(Vec3 delta) {
            return new OfSubLevel(plotPos.add(delta));
        }

        @Override
        public Kind kind() {
            return Kind.SUB_LEVEL;
        }
    }

    /**
     * Moving entity/craft selected while visible: projectile strikes last-seen {@code seen}; follows {@code
     * quarry} itself only while operator holds it in this projectile camera and weapon supports it ({@code
     * WeaponSpec.Tracking#CAMERA}, {@link TargetTracker#tick}). Used by player orders; otherwise username would
     * allow anywhere-map strikes and tracking to end (hundreds of Shaheds on one player). Salvo spread shifts
     * position, not quarry: camera-guided projectile follows actual target.
     */
    record Sighted(Target quarry, Vec3 seen) implements Target {
        static final MapCodec<Sighted> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.lazyInitialized(() -> Target.CODEC).fieldOf("quarry").forGetter(Sighted::quarry),
                Vec3.CODEC.fieldOf("seen").forGetter(Sighted::seen)
        ).apply(i, Sighted::new));

        /** Where target was seen; further tracking only via camera ({@link TargetTracker}). */
        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            return Optional.of(seen);
        }

        @Override
        public Target offset(Vec3 delta) {
            return new Sighted(quarry, seen.add(delta));
        }

        /** Same target seen at {@code point} (salvo projectile site). */
        public Sighted at(Vec3 point) {
            return new Sighted(quarry, point);
        }

        @Override
        public Target subject() {
            return quarry;
        }

        @Override
        public Kind kind() {
            return Kind.SIGHTED;
        }
    }

    /**
     * Saved target kind. Kind codecs obtained at read/write, not enum creation: interface has default method
     * ({@link #subject}), so first access to any target record initializes {@code Target} first (JLS 12.4.2),
     * including {@link #CODEC} and this enum; not-yet-initialized record codec would be {@code null} here.
     */
    enum Kind implements StringRepresentable {
        POINT("point", () -> Point.CODEC),
        GROUND("ground", () -> Ground.CODEC),
        ENTITY("entity", () -> OfEntity.CODEC),
        SUB_LEVEL("sub_level", () -> OfSubLevel.CODEC),
        SIGHTED("sighted", () -> Sighted.CODEC);

        static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final String name;
        private final Supplier<MapCodec<? extends Target>> codec;

        Kind(String name, Supplier<MapCodec<? extends Target>> codec) {
            this.name = name;
            this.codec = codec;
        }

        MapCodec<? extends Target> codec() {
            return codec.get();
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
