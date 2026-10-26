package ua.zentix.incoming.net;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Sightings;
import ua.zentix.incoming.util.StreamCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server → client: events only; client builds everything visible/audible (particles, delayed sound, shake). */
public final class S2C {
    private S2C() {}

    /**
     * Explosion: kind (0 — Shahed, 1 — missile, 2 — buried bomb, 3 — MLRS projectile — how explosion occurs), ground, surface
     * height above point (bomb: top above charge, {@code BunkerCover}; others: impact-point height — client sees
     * surrounding surface itself), seed, exploded projectile (client path ends this tick; engine sounds
     * until explosion front reaches the ear rather than fading early), and weapon ({@code WeaponType.id}): charge/fuel
     * are in its spec, read by client ({@code client.fx.BlastSource}); warhead axis (projectile heading at impact,
     * unit vector): axis and seed produce same client/server fragments ({@code warhead.FragmentModel}).
     * <p>
     * Support at impact face ({@link Support}): server breach knowledge of barrier — material hit and whether a slab over
     * void was breached. Client chooses target presentation from it ({@code client.fx.smoke.BlastTarget}).
     * <p>
     * Format only grows at end (replay.md): weapon/axis appended; older recordings lack weapon
     * ({@link #NO_WEAPON}) — infer from kind as kind was chosen then ({@link #weaponType}).
     */
    public record Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile, int weapon, Vec3 axis,
                        boolean fuelApart, Optional<Support> support) implements CustomPacketPayload {
        public static final int DRONE = 0, MISSILE = 1, BUNKER = 2, ROCKET = 3;
        /** No weapon in packet (recording predates this field). */
        public static final int NO_WEAPON = -1;
        public static final Type<Blast> TYPE = new Type<>(Incoming.id("blast"));
        private static final StreamCodec<ByteBuf, Optional<UUID>> PROJECTILE = ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC);
        private static final StreamCodec<ByteBuf, Integer> WEAPON = StreamCodecs.trailing(ByteBufCodecs.VAR_INT, NO_WEAPON);
        /** Warhead axis downward when absent from packet (recording predates field). */
        public static final Vec3 DOWN = new Vec3(0, -1, 0);
        private static final StreamCodec<ByteBuf, Vec3> AXIS = StreamCodecs.trailing(StreamCodecs.VEC3, DOWN);
        /**
         * Crater fuel burns separately — server fire blocks and {@link FuelFire} packet ({@code warhead/FuelFires}):
         * client drives its smoke/rumble only from that packet. Recordings predating this field use impact-site
         * fuel smoke/rumble as before (no fire blocks in such recordings).
         */
        private static final StreamCodec<ByteBuf, Boolean> FUEL_APART = StreamCodecs.trailing(ByteBufCodecs.BOOL, false);
        /** No packet support (recording predates field; serverless explosion; charge not against barrier). */
        private static final StreamCodec<ByteBuf, Optional<Support>> SUPPORT = StreamCodecs.trailing(ByteBufCodecs.optional(Support.CODEC), Optional.empty());
        public static final StreamCodec<ByteBuf, Blast> CODEC = StreamCodec.of((b, p) -> {
            ByteBufCodecs.VAR_INT.encode(b, p.kind);
            StreamCodecs.VEC3.encode(b, p.pos);
            ByteBufCodecs.VAR_INT.encode(b, p.material);
            ByteBufCodecs.FLOAT.encode(b, p.surfaceY);
            ByteBufCodecs.VAR_LONG.encode(b, p.seed);
            PROJECTILE.encode(b, p.projectile);
            WEAPON.encode(b, p.weapon);
            AXIS.encode(b, p.axis);
            FUEL_APART.encode(b, p.fuelApart);
            SUPPORT.encode(b, p.support);
        }, b -> new Blast(ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                ByteBufCodecs.FLOAT.decode(b), ByteBufCodecs.VAR_LONG.decode(b), PROJECTILE.decode(b), WEAPON.decode(b), AXIS.decode(b),
                FUEL_APART.decode(b), SUPPORT.decode(b)));

        /** Explosion without warhead axis (bomb, post-nuclear presentation): downward axis. */
        public Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile, int weapon) {
            this(kind, pos, material, surfaceY, seed, projectile, weapon, DOWN);
        }

        /** Explosion whose fuel (if any) burns client-side at impact: no server fire blocks (serverless presentation). */
        public Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile, int weapon, Vec3 axis) {
            this(kind, pos, material, surfaceY, seed, projectile, weapon, axis, false);
        }

        /** Explosion without server support: target by material ({@link Support}). */
        public Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile, int weapon, Vec3 axis,
                     boolean fuelApart) {
            this(kind, pos, material, surfaceY, seed, projectile, weapon, axis, fuelApart, Optional.empty());
        }

        /**
         * Impact-face support: server breach result along one ray normal inward into barrier ({@code warhead.SlabBreach#support}):
         * same law, blocks and “void under cover” rule as slab-removal rays.
         *
         * @param loose  charge hit loose material (earth breach group, FM 5-250 table B-3)
         * @param slab   slab thickness to covered void along normal, m; 0 — not breached (bulk, ground, open-sky void)
         * @param breach slab breach radius for this charge through its blocks (path / sum of paths divided by each radius), m
         * @param normal inward face normal (unit): hole and rear-spall axis
         * @param lift   distance explosion point moved past face along normal, m (aim point half a block inside): charge rests at
         *               {@code pos − normal·lift}
         */
        public record Support(boolean loose, float slab, float breach, Vec3 normal, float lift) {
            private static final StreamCodec<ByteBuf, Vec3> NORMAL = StreamCodec.composite(ByteBufCodecs.FLOAT, v -> (float) v.x,
                    ByteBufCodecs.FLOAT, v -> (float) v.y, ByteBufCodecs.FLOAT, v -> (float) v.z, Vec3::new);
            public static final StreamCodec<ByteBuf, Support> CODEC = StreamCodec.composite(
                    ByteBufCodecs.BOOL, Support::loose, ByteBufCodecs.FLOAT, Support::slab, ByteBufCodecs.FLOAT, Support::breach,
                    NORMAL, Support::normal, ByteBufCodecs.FLOAT, Support::lift, Support::new);

            /** Support on upper face (downward normal, charge on face). */
            public Support(boolean loose, float slab, float breach) {
                this(loose, slab, breach, DOWN, 0);
            }
        }

        /**
         * Exploded weapon. Absent from packet ({@link #NO_WEAPON}): infer from kind as server chose it before field
         * (Shahed and Lancet both used Shahed kind).
         */
        public WeaponType weaponType() {
            WeaponType[] all = WeaponType.values();
            if (weapon >= 0 && weapon < all.length) return all[weapon];
            return switch (kind) {
                case MISSILE -> WeaponType.MISSILE;
                case BUNKER -> WeaponType.BUNKER;
                case ROCKET -> WeaponType.ROCKET;
                default -> WeaponType.DRONE;
            };
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Substation disabled: bang, arc/sparks, fading transformer hum. */
    public record GridFailure(Vec3 pos, long seed) implements CustomPacketPayload {
        public static final Type<GridFailure> TYPE = new Type<>(Incoming.id("grid_failure"));
        public static final StreamCodec<ByteBuf, GridFailure> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, GridFailure::pos, ByteBufCodecs.VAR_LONG, GridFailure::seed, GridFailure::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Nearby district went dark (relay click, hum stops) or power returned ({@code on}). */
    public record GridDistrict(Vec3 pos, boolean on) implements CustomPacketPayload {
        public static final Type<GridDistrict> TYPE = new Type<>(Incoming.id("grid_district"));
        public static final StreamCodec<ByteBuf, GridDistrict> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, GridDistrict::pos, ByteBufCodecs.BOOL, GridDistrict::on, GridDistrict::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Bomb entered ground: sonic boom, ground impact, fountain at entry hole. */
    public record BunkerImpact(Vec3 pos, int material, float speed) implements CustomPacketPayload {
        public static final Type<BunkerImpact> TYPE = new Type<>(Incoming.id("bunker_impact"));
        /** Impact speed for recordings predating field, m/s: former fall limit, 12.5 blocks/tick. */
        public static final float OLD_SPEED = 250;
        public static final StreamCodec<ByteBuf, BunkerImpact> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, BunkerImpact::pos, ByteBufCodecs.VAR_INT, BunkerImpact::material,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, OLD_SPEED), BunkerImpact::speed, BunkerImpact::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Buried explosion gases vented through borehole.
     *
     * @param airKg charge fraction vented into air, kg TNT ({@code warhead.BuriedBlast#airCharge}; recordings predating field:
     *              {@link #OLD_AIR_KG})
     */
    public record Vent(Vec3 pos, int material, float airKg) implements CustomPacketPayload {
        public static final Type<Vent> TYPE = new Type<>(Incoming.id("vent"));
        /** Charge fraction for recordings predating field: 2.4 t through a 0.8 m borehole from a 3.4 m cavity, kg TNT. */
        public static final float OLD_AIR_KG = 8.5f;
        public static final StreamCodec<ByteBuf, Vent> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Vent::pos, ByteBufCodecs.VAR_INT, Vent::material,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, OLD_AIR_KG), Vent::airKg, Vent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Impact fuel burns on blocks ({@code warhead/FuelFires}): crater state on creation, every change and
     * chunk delivery. Client drives fire smoke/light/rumble from weapon fuel law over these cells
     * ({@code WeaponSpec.pool(lit)}); fire itself is world blocks.
     *
     * @param id        crater (in its world)
     * @param cells     fire cells in burnout order (first burns longest: fuel pooled there) — current locations
     * @param smothered cells (bits by index) whose fuel ended early: buried, flooded, fell into water
     * @param weapon    weapon ({@code WeaponType.id})
     * @param blastTick impact game time: burning law counts from it
     */
    public record FuelFire(int id, List<BlockPos> cells, long smothered, int weapon, long blastTick) implements CustomPacketPayload {
        public static final Type<FuelFire> TYPE = new Type<>(Incoming.id("fuel_fire"));
        /** Maximum packet cells: {@link #smothered} fits one long (missile: 9). */
        public static final int MAX_CELLS = 64;
        public static final StreamCodec<ByteBuf, FuelFire> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FuelFire::id, BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CELLS)), FuelFire::cells,
                ByteBufCodecs.VAR_LONG, FuelFire::smothered, ByteBufCodecs.VAR_INT, FuelFire::weapon, ByteBufCodecs.VAR_LONG,
                FuelFire::blastTick, FuelFire::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Roof above cavity collapsed: surface subsidence.
     *
     * @param volume ground volume lost into subsidence, m³ (cavity volume; recordings predating field: {@link #OLD_VOLUME})
     */
    public record Collapse(Vec3 pos, int material, float volume) implements CustomPacketPayload {
        public static final Type<Collapse> TYPE = new Type<>(Incoming.id("collapse"));
        /** Volume for recordings predating field: 2.4 t cavity (3.4 m radius), m³. */
        public static final float OLD_VOLUME = 160;
        public static final StreamCodec<ByteBuf, Collapse> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Collapse::pos, ByteBufCodecs.VAR_INT, Collapse::material,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, OLD_VOLUME), Collapse::volume, Collapse::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Conventional ground source: the client evaluates R/c and PPV at its body, with fractional ticks.
     * The old ticks/rumble prefix remains for existing replays. The nuclear ground path is separate.
     * A source event has ticks=0, rumble=false: the legacy handler must not shake/play sound immediately.
     */
    public record Quake(int ticks, boolean rumble, Optional<Ground> source) implements CustomPacketPayload {
        public static final Type<Quake> TYPE = new Type<>(Incoming.id("quake"));
        public static final StreamCodec<ByteBuf, Quake> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Quake::ticks, ByteBufCodecs.BOOL, Quake::rumble,
                StreamCodecs.trailing(ByteBufCodecs.optional(Ground.CODEC), Optional.empty()), Quake::source, Quake::new);

        public Quake(int ticks, boolean rumble) {
            this(ticks, rumble, Optional.empty());
        }

        public Quake(Vec3 pos, double tntKg, double depthM, int material, long gameTime) {
            this(0, false, Optional.of(new Ground(pos, tntKg, depthM, material, gameTime)));
        }

        /**
         * @param pos source position, blocks = metres
         * @param tntKg full TNT-equivalent charge/source energy, kg; GroundShock applies depth coupling once
         * @param depthM burial below the local surface, metres; 0 = contact
         * @param material existing GroundMaterial wire id (not GroundShock.Medium.ordinal)
         * @param gameTime server game tick at source event, not packet delivery or end of excavation work
         */
        public record Ground(Vec3 pos, double tntKg, double depthM, int material, long gameTime) {
            public static final StreamCodec<ByteBuf, Ground> CODEC = StreamCodec.composite(
                    StreamCodecs.VEC3, Ground::pos, ByteBufCodecs.DOUBLE, Ground::tntKg,
                    ByteBufCodecs.DOUBLE, Ground::depthM, ByteBufCodecs.VAR_INT, Ground::material,
                    ByteBufCodecs.VAR_LONG, Ground::gameTime, Ground::new);

            /** The same pure law for recipient selection and local sampling. */
            public ua.zentix.incoming.nuclear.model.GroundShock.Source model() {
                return new ua.zentix.incoming.nuclear.model.GroundShock.Source(tntKg, depthM,
                        ua.zentix.incoming.warhead.GroundMaterial.byId(material).shockMedium());
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Air-raid alert at target: 0 — air raid, 1 — missile warning. */
    public record Siren(Vec3 pos, int kind) implements CustomPacketPayload {
        public static final int AIR_RAID = 0, MISSILE = 1;
        public static final Type<Siren> TYPE = new Type<>(Incoming.id("siren"));
        public static final StreamCodec<ByteBuf, Siren> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Siren::pos, ByteBufCodecs.VAR_INT, Siren::kind, Siren::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Salvo progress for its launcher. */
    public record SalvoStatus(int weapon, int fired, int total) implements CustomPacketPayload {
        public static final Type<SalvoStatus> TYPE = new Type<>(Incoming.id("salvo_status"));
        public static final StreamCodec<ByteBuf, SalvoStatus> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SalvoStatus::weapon, ByteBufCodecs.VAR_INT, SalvoStatus::fired, ByteBufCodecs.VAR_INT, SalvoStatus::total, SalvoStatus::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Owned projectiles in flight, sent to owner every 5 ticks (empty list once all arrived): HUD
     * impact ETA, screen markers and projectile camera. Server also knows outside-world flights. Trailing list:
     * target entity network IDs per projectile ({@link #target}).
     */
    public record Flights(List<Flight> flights, List<Integer> targets) implements CustomPacketPayload {
        public static final Type<Flights> TYPE = new Type<>(Incoming.id("flights"));
        /** No target entity (point, craft, or entity server could not find). */
        public static final int NO_ENTITY = -1;
        public static final StreamCodec<ByteBuf, Flights> CODEC = StreamCodec.composite(Flight.CODEC.apply(ByteBufCodecs.list()), Flights::flights,
                StreamCodecs.trailing(ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), List.of()), Flights::targets, Flights::new);

        public Flights(List<Flight> flights) {
            this(flights, List.of());
        }

        /**
         * Target entity network ID for projectile i ({@link #NO_ENTITY} — none): client places marker where it renders
         * target rather than at the server's once-per-5-tick point. List at packet end (format 2): recordings from previous
         * builds lack it.
         */
        public int target(int i) {
            return i < targets.size() ? targets.get(i) : NO_ENTITY;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One projectile: UUID (same after world reentry), weapon, flight phase, impact ETA ticks, position/destination,
     * nuclear warhead flag.
     */
    public record Flight(UUID id, int weapon, int phase, int eta, Vec3 pos, Vec3 target, boolean nuclear,
                         int targetKind, String targetName, boolean targetLost) {
        /** Point target (no label). */
        public static final int TARGET_POINT = 0;
        /** Player or named entity: {@code targetName} is the name itself. */
        public static final int TARGET_NAMED = 1;
        /** Unnamed entity: {@code targetName} is entity-type translation key. */
        public static final int TARGET_TYPE = 2;
        /** Sable craft: {@code targetName} is its name or empty. */
        public static final int TARGET_AIRCRAFT = 3;
        /** {@code targetName} length limit; server truncates beyond it. */
        public static final int MAX_NAME = 64;
        private static final StreamCodec<ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(MAX_NAME);

        public static final StreamCodec<ByteBuf, Flight> CODEC = new StreamCodec<>() {
            @Override
            public Flight decode(ByteBuf b) {
                return new Flight(UUIDUtil.STREAM_CODEC.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), StreamCodecs.VEC3.decode(b), b.readBoolean(),
                        ByteBufCodecs.VAR_INT.decode(b), NAME.decode(b), b.readBoolean());
            }

            @Override
            public void encode(ByteBuf b, Flight f) {
                UUIDUtil.STREAM_CODEC.encode(b, f.id);
                ByteBufCodecs.VAR_INT.encode(b, f.weapon);
                ByteBufCodecs.VAR_INT.encode(b, f.phase);
                ByteBufCodecs.VAR_INT.encode(b, f.eta);
                StreamCodecs.VEC3.encode(b, f.pos);
                StreamCodecs.VEC3.encode(b, f.target);
                b.writeBoolean(f.nuclear);
                ByteBufCodecs.VAR_INT.encode(b, f.targetKind);
                NAME.encode(b, f.targetName);
                b.writeBoolean(f.targetLost);
            }
        };
    }

    /**
     * Projectiles absent from client (beyond vanilla entity delivery, or outside-world flight) but already audible
     * ({@link ua.zentix.incoming.strike.Hearing}) or visible ({@code visibility.far_range}): client
     * drives same delayed Doppler sound as for entities, and draws distant projectile. Audible ones every
     * {@link #PERIOD} ticks (sound follows straight line between packets), visible-only every {@link #FAR_PERIOD}: distant
     * projectile moves a fraction of a screen pixel per tick, and a 40-projectile salvo means 40 records in each packet.
     */
    public record FarFlights(List<FarFlight> flights) implements CustomPacketPayload {
        public static final int PERIOD = 2, FAR_PERIOD = 4;
        public static final Type<FarFlights> TYPE = new Type<>(Incoming.id("far_flights"));
        public static final StreamCodec<ByteBuf, FarFlights> CODEC = FarFlight.CODEC.apply(ByteBufCodecs.list()).map(FarFlights::new, FarFlights::flights);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * One distant projectile: UUID (same as entity when client receives it), weapon, B-2 flag (bomb has same
     * weapon), bomb-drilling flag, audibility (more frequent packets), position, last-tick displacement
     * ({@code StrikeProjectile.velocity}; float: better than millimeter precision), nose direction and bank (distant model banks
     * in turns like nearby), flight phase and phase age,
     * destination (target point); at launch also underlying ground height (first air) from server chunk
     * ({@code ground}; NaN — not launching or chunk unready): client lacks distant chunk, and LOD terrain mods are not
     * installed for everyone — launch cloud needs ground to stand on.
     */
    public record FarFlight(UUID id, int weapon, boolean bomber, boolean drilling, boolean audible, Vec3 pos, Vec3 velocity, float yaw,
                            float pitch, float roll, int phase, int phaseAge, Vec3 aim, float ground, boolean ended) {
        /** SAM missile: flight kind, not a new weapon kind. */
        public static final int INTERCEPTOR = -1;
        private static final int BOMBER = 1, DRILLING = 2, AUDIBLE = 4, GROUND = 8, ENDED = 16;

        public FarFlight(UUID id, int weapon, boolean bomber, boolean drilling, boolean audible, Vec3 pos, Vec3 velocity, float yaw,
                         float pitch, float roll, int phase, int phaseAge, Vec3 aim, float ground) {
            this(id, weapon, bomber, drilling, audible, pos, velocity, yaw, pitch, roll, phase, phaseAge, aim, ground, false);
        }

        public boolean interceptor() {
            return weapon == INTERCEPTOR;
        }

        /** No ground beneath projectile. */
        public FarFlight(UUID id, int weapon, boolean bomber, boolean drilling, boolean audible, Vec3 pos, Vec3 velocity, float yaw,
                         float pitch, float roll, int phase, int phaseAge, Vec3 aim) {
            this(id, weapon, bomber, drilling, audible, pos, velocity, yaw, pitch, roll, phase, phaseAge, aim, Float.NaN);
        }

        public static final StreamCodec<ByteBuf, FarFlight> CODEC = new StreamCodec<>() {
            @Override
            public FarFlight decode(ByteBuf b) {
                UUID id = UUIDUtil.STREAM_CODEC.decode(b);
                int weapon = ByteBufCodecs.VAR_INT.decode(b);
                int flags = b.readByte();
                Vec3 pos = StreamCodecs.VEC3.decode(b);
                Vec3 velocity = new Vec3(b.readFloat(), b.readFloat(), b.readFloat());
                float yaw = b.readFloat(), pitch = b.readFloat(), roll = b.readFloat();
                int phase = ByteBufCodecs.VAR_INT.decode(b), phaseAge = ByteBufCodecs.VAR_INT.decode(b);
                Vec3 aim = StreamCodecs.VEC3.decode(b);
                float ground = (flags & GROUND) != 0 ? b.readFloat() : Float.NaN;
                return new FarFlight(id, weapon, (flags & BOMBER) != 0, (flags & DRILLING) != 0, (flags & AUDIBLE) != 0, pos, velocity,
                        yaw, pitch, roll, phase, phaseAge, aim, ground, (flags & ENDED) != 0);
            }

            @Override
            public void encode(ByteBuf b, FarFlight f) {
                UUIDUtil.STREAM_CODEC.encode(b, f.id);
                ByteBufCodecs.VAR_INT.encode(b, f.weapon);
                boolean ground = Float.isFinite(f.ground);
                b.writeByte((f.bomber ? BOMBER : 0) | (f.drilling ? DRILLING : 0) | (f.audible ? AUDIBLE : 0) | (ground ? GROUND : 0) | (f.ended ? ENDED : 0));
                StreamCodecs.VEC3.encode(b, f.pos);
                b.writeFloat((float) f.velocity.x);
                b.writeFloat((float) f.velocity.y);
                b.writeFloat((float) f.velocity.z);
                b.writeFloat(f.yaw);
                b.writeFloat(f.pitch);
                b.writeFloat(f.roll);
                ByteBufCodecs.VAR_INT.encode(b, f.phase);
                ByteBufCodecs.VAR_INT.encode(b, f.phaseAge);
                StreamCodecs.VEC3.encode(b, f.aim);
                if (ground) b.writeFloat(f.ground);
            }
        };

        /** Ticks until next packet for this projectile. */
        public int period() {
            return audible ? FarFlights.PERIOD : FarFlights.FAR_PERIOD;
        }
    }

    /**
     * Targeting-map and remote-target markers — reply to {@link C2S.MapPlayers}: own players and sightings by requester's
     * side in their dimension within {@code map_range} (selection: {@code ServerActions.mapPlayers});
     * {@code shown}: map renders them ({@code map_players}); otherwise only remote targets.
     */
    public record MapPlayers(List<MapPlayer> players, boolean shown) implements CustomPacketPayload {
        public static final Type<MapPlayers> TYPE = new Type<>(Incoming.id("map_players"));
        public static final StreamCodec<ByteBuf, MapPlayers> CODEC = StreamCodec.composite(
                MapPlayer.CODEC.apply(ByteBufCodecs.list()), MapPlayers::players, ByteBufCodecs.BOOL, MapPlayers::shown, MapPlayers::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Map marker: UUID, name (player nickname also used for remote targeting), kind, position (hostile: last seen
     * position), same-team flag and seconds since sighting (0 — currently seen).
     */
    public record MapPlayer(UUID id, String name, double x, double z, Sightings.Kind kind, boolean friendly, int age) {
        /** Minecraft player name: up to 16 characters; reserve for mod names. */
        public static final int MAX_NAME = 64;
        private static final StreamCodec<ByteBuf, Sightings.Kind> KIND = ByteBufCodecs.idMapper(
                i -> Sightings.Kind.values()[Math.floorMod(i, Sightings.Kind.values().length)], Sightings.Kind::ordinal);
        public static final StreamCodec<ByteBuf, MapPlayer> CODEC = NeoForgeStreamCodecs.composite(
                UUIDUtil.STREAM_CODEC, MapPlayer::id, ByteBufCodecs.stringUtf8(MAX_NAME), MapPlayer::name,
                ByteBufCodecs.DOUBLE, MapPlayer::x, ByteBufCodecs.DOUBLE, MapPlayer::z, KIND, MapPlayer::kind,
                ByteBufCodecs.BOOL, MapPlayer::friendly, ByteBufCodecs.VAR_INT, MapPlayer::age, MapPlayer::new);
    }

    /** Open remote screen (/incoming menu command). */
    public record OpenRemote() implements CustomPacketPayload {
        public static final Type<OpenRemote> TYPE = new Type<>(Incoming.id("open_remote"));
        public static final StreamCodec<ByteBuf, OpenRemote> CODEC = StreamCodec.unit(new OpenRemote());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Cancel: silence engines/cameras of removed {@code projectiles}. {@code owner}: this player's own cancel
     * (their salvo line clears, others keep alert: other players' strikes continue); empty: cancel all,
     * clearing alert too. {@code nuclear}: nuclear strikes canceled too (otherwise nuclear projectiles continue,
     * retaining sound/camera).
     */
    public record Cleared(Optional<UUID> owner, boolean nuclear, List<UUID> projectiles) implements CustomPacketPayload {
        public static final Type<Cleared> TYPE = new Type<>(Incoming.id("cleared"));
        public static final StreamCodec<ByteBuf, Cleared> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), Cleared::owner, ByteBufCodecs.BOOL, Cleared::nuclear,
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Cleared::projectiles, Cleared::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * ICBM launched: boost trail and warhead reentry for client presentation.
     *
     * @param alarm player within alert radius (civil-defense siren/countdown)
     * @param mine  this player launched it (detonation countdown)
     * @param scale world nuclear-effects scale (effects_scale): burst height and warhead path in blocks
     */
    public record NukeWarning(int strikeId, Vec3 target, Vec3 launchPos, long launchTime, long detonateTime, double yieldKt,
                              boolean airBurst, boolean alarm, boolean mine, float scale) implements CustomPacketPayload {
        /** Burst height above target, blocks. */
        public double burstHeight() {
            return airBurst ? Yield.optimalBurstHeight(yieldKt) * scale : 0;
        }

        public static final Type<NukeWarning> TYPE = new Type<>(Incoming.id("nuke_warning"));
        public static final StreamCodec<ByteBuf, NukeWarning> CODEC = new StreamCodec<>() {
            @Override
            public NukeWarning decode(ByteBuf b) {
                return new NukeWarning(ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), StreamCodecs.VEC3.decode(b), b.readLong(), b.readLong(), b.readDouble(), b.readBoolean(),
                        b.readBoolean(), b.readBoolean(), b.readFloat());
            }

            @Override
            public void encode(ByteBuf b, NukeWarning w) {
                ByteBufCodecs.VAR_INT.encode(b, w.strikeId);
                StreamCodecs.VEC3.encode(b, w.target);
                StreamCodecs.VEC3.encode(b, w.launchPos);
                b.writeLong(w.launchTime);
                b.writeLong(w.detonateTime);
                b.writeDouble(w.yieldKt);
                b.writeBoolean(w.airBurst);
                b.writeBoolean(w.alarm);
                b.writeBoolean(w.mine);
                b.writeFloat(w.scale);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Nuclear detonation: client computes everything else (flash, fireball, mushroom, wave, sound, rain) from model. Except
     * server settings: {@code blackout} — grid-outage radius ({@code Blackouts.nukeRadius}; 0 — no
     * outage), where lamps short with EMP arcs.
     * <p>
     * Format only grows at end (replay.md): 3.1.0 fields, then {@code wave}, {@code blackout}, detonation time of day
     * ({@link Detonation#dayTime}) and fires ({@link Detonation#fires}), each appended to packet end; recordings
     * predating them read “as before”: wall speed {@link Detonation#OLD_WAVE}, no EMP, time of day
     * unknown, fires enabled.
     */
    public record NukeDetonation(Detonation detonation, float blackout) implements CustomPacketPayload {
        public static final Type<NukeDetonation> TYPE = new Type<>(Incoming.id("nuke_detonation"));
        public static final StreamCodec<ByteBuf, NukeDetonation> CODEC = StreamCodec.composite(
                Detonation.STREAM_CODEC, NukeDetonation::detonation,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, Detonation.OLD_WAVE), p -> p.detonation.wave(),
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, 0f), NukeDetonation::blackout,
                StreamCodecs.trailing(ByteBufCodecs.VAR_LONG, Detonation.NO_DAY_TIME), p -> p.detonation.dayTime(),
                StreamCodecs.trailing(ByteBufCodecs.BOOL, true), p -> p.detonation.fires(),
                (d, wave, blackout, dayTime, fires) -> new NukeDetonation(d.withWave(wave).withDayTime(dayTime).withFires(fires), blackout));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Blocks removed by nuclear wave in chunk (sampled chunk-ruin locations, {@code RuinPlan#thrown}), sent to chunk
     * viewers: client throws pieces when front arrives and plays glass/debris sounds.
     *
     * @param removed chunk locations fully removed by wave ({@code pieces} locations sampled by position hash); recordings
     *                predating field: {@link #OLD_REMOVED}
     */
    public record NukeDebris(int detonation, List<Piece> pieces, int removed) implements CustomPacketPayload {
        public static final Type<NukeDebris> TYPE = new Type<>(Incoming.id("nuke_debris"));
        /** Recording predates field: removed count unknown — use sample size. */
        public static final int OLD_REMOVED = 0;

        /** Removed chunk locations represented by each sampled location (1 — sample includes all). */
        public double weight() {
            return pieces.isEmpty() ? 0 : Math.max(1.0, (double) removed / pieces.size());
        }

        /** Position and former block. */
        public record Piece(BlockPos pos, BlockState state) {
            static final StreamCodec<ByteBuf, Piece> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Piece::pos,
                    ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), Piece::state, Piece::new);
        }

        public static final StreamCodec<ByteBuf, NukeDebris> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, NukeDebris::detonation,
                Piece.CODEC.apply(ByteBufCodecs.list(64)), NukeDebris::pieces,
                StreamCodecs.trailing(ByteBufCodecs.VAR_INT, OLD_REMOVED), NukeDebris::removed, NukeDebris::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Chunk-face flash traces ({@link ua.zentix.incoming.nuclear.flash.FlashMarks}): all traces in chunk, sent on
     * chunk delivery and trace changes (ruins, silhouettes). Empty means no traces remain.
     */
    public record NukeFlashMarks(long chunk, ua.zentix.incoming.nuclear.flash.FlashMarks marks) implements CustomPacketPayload {
        public static final Type<NukeFlashMarks> TYPE = new Type<>(Incoming.id("nuke_flash_marks"));
        public static final StreamCodec<ByteBuf, NukeFlashMarks> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, NukeFlashMarks::chunk,
                ua.zentix.incoming.nuclear.flash.FlashMarks.STREAM_CODEC, NukeFlashMarks::marks, NukeFlashMarks::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * World entry/dimension change: active detonations and flying ICBMs in this dimension. Listed detonations use 3.1.0
     * fields ({@link Detonation#STREAM_CODEC}); additional data (wall speed {@link Detonation#wave}, time
     * of day {@link Detonation#dayTime}, fires {@link Detonation#fires}) in trailing packet lists, one entry per detonation
     * (format grows at end, replay.md): older recordings lack lists — “as before”.
     */
    public record NukeSync(List<Detonation> detonations, List<NukeWarning> warnings)
            implements CustomPacketPayload {
        public static final Type<NukeSync> TYPE = new Type<>(Incoming.id("nuke_sync"));
        public static final StreamCodec<ByteBuf, NukeSync> CODEC = StreamCodec.composite(
                Detonation.STREAM_CODEC.apply(ByteBufCodecs.list()), NukeSync::detonations,
                NukeWarning.CODEC.apply(ByteBufCodecs.list()), NukeSync::warnings,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::wave).toList(),
                StreamCodecs.trailing(ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::dayTime).toList(),
                StreamCodecs.trailing(ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::fires).toList(),
                (detonations, warnings, waves, dayTimes, fires) -> new NukeSync(extended(detonations, waves, dayTimes, fires), warnings));

        /** Detonations with trailing-list fields (empty list: field absent from recording). */
        private static List<Detonation> extended(List<Detonation> detonations, List<Float> waves, List<Long> dayTimes,
                List<Boolean> fires) {
            check("waves", waves, detonations);
            check("day times", dayTimes, detonations);
            check("fires", fires, detonations);
            List<Detonation> out = new ArrayList<>(detonations.size());
            for (int i = 0; i < detonations.size(); i++) {
                Detonation d = detonations.get(i);
                if (!waves.isEmpty()) d = d.withWave(waves.get(i));
                if (!dayTimes.isEmpty()) d = d.withDayTime(dayTimes.get(i));
                if (!fires.isEmpty()) d = d.withFires(fires.get(i));
                out.add(d);
            }
            return out;
        }

        private static void check(String what, List<?> values, List<Detonation> detonations) {
            if (!values.isEmpty() && values.size() != detonations.size()) {
                throw new DecoderException("nuke sync: " + values.size() + " " + what + " for " + detonations.size() + " detonations");
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Player irradiation: dose (Gy), dose rate (R/h, for Geiger counter), contamination, illness stage. */
    public record Radiation(float doseGy, float rate, float contamination, int stage) implements CustomPacketPayload {
        public static final Type<Radiation> TYPE = new Type<>(Incoming.id("radiation"));
        public static final StreamCodec<ByteBuf, Radiation> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Radiation::doseGy, ByteBufCodecs.FLOAT, Radiation::rate,
                ByteBufCodecs.FLOAT, Radiation::contamination, ByteBufCodecs.VAR_INT, Radiation::stage, Radiation::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Player retinal burns — all spots in full ({@link ua.zentix.incoming.nuclear.radiation.FlashEyes}). */
    public record RetinaScars(ua.zentix.incoming.nuclear.radiation.RetinaScars scars) implements CustomPacketPayload {
        public static final Type<RetinaScars> TYPE = new Type<>(Incoming.id("retina_scars"));
        public static final StreamCodec<ByteBuf, RetinaScars> CODEC = ua.zentix.incoming.nuclear.radiation.RetinaScars.STREAM_CODEC
                .map(RetinaScars::new, RetinaScars::scars);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Flashback replay-server tick end ({@link ua.zentix.incoming.compat.FlashbackReplay}): recording position and whether
     * seeking. Packets before marker were read this tick; replay only, absent during gameplay.
     */
    public record ReplayTick(int place, boolean seeking) implements CustomPacketPayload {
        public static final Type<ReplayTick> TYPE = new Type<>(Incoming.id("replay_tick"));
        public static final StreamCodec<ByteBuf, ReplayTick> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ReplayTick::place, ByteBufCodecs.BOOL, ReplayTick::seeking, ReplayTick::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * SAM missile airburst ({@code defense.Interceptor}): near target or self-destruction (timeout, ground).
     * kill — target downed: client path ends this tick ({@code target}), debris flies as entities. No ground
     * explosion — only flash, puff and bang; seed selects sound variant.
     */
    public record Intercept(Vec3 pos, boolean kill, Optional<UUID> target, long seed) implements CustomPacketPayload {
        public static final Type<Intercept> TYPE = new Type<>(Incoming.id("intercept"));
        public static final StreamCodec<ByteBuf, Intercept> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Intercept::pos, ByteBufCodecs.BOOL, Intercept::kill, ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Intercept::target,
                ByteBufCodecs.VAR_LONG, Intercept::seed, Intercept::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * SAM radar screen for nearby friendlies ({@code defense.SamBlockEntity}, once per scan): antenna location, radar/fire
     * ranges, missiles on rails/in reserve, targets. Stops arriving: screen fades.
     */
    public record RadarScope(Vec3 radar, int range, int engageRange, int ready, int stock, List<Blip> blips) implements CustomPacketPayload {
        /** Maximum screen targets (nearest first). */
        public static final int MAX_BLIPS = 64;
        public static final Type<RadarScope> TYPE = new Type<>(Incoming.id("radar_scope"));
        public static final StreamCodec<ByteBuf, RadarScope> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, RadarScope::radar, ByteBufCodecs.VAR_INT, RadarScope::range, ByteBufCodecs.VAR_INT, RadarScope::engageRange,
                ByteBufCodecs.VAR_INT, RadarScope::ready, ByteBufCodecs.VAR_INT, RadarScope::stock,
                Blip.CODEC.apply(ByteBufCodecs.list(MAX_BLIPS)), RadarScope::blips, RadarScope::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Radar target: x/z offset from antenna, blocks, heading (degrees, same as entity), weapon kind
     * ({@link ua.zentix.incoming.strike.WeaponType#id()}: larger body — larger blip), and {@link #HOSTILE},
     * {@link #ENGAGEABLE}, {@link #ENGAGED}.
     */
    public record Blip(float dx, float dz, float yaw, int weapon, int flags) {
        /** Hostile. */
        public static final int HOSTILE = 1;
        /** Within firing range and worth a missile. */
        public static final int ENGAGEABLE = 2;
        /** A missile is pursuing it. */
        public static final int ENGAGED = 4;
        public static final StreamCodec<ByteBuf, Blip> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Blip::dx, ByteBufCodecs.FLOAT, Blip::dz, ByteBufCodecs.FLOAT, Blip::yaw,
                ByteBufCodecs.VAR_INT, Blip::weapon, ByteBufCodecs.VAR_INT, Blip::flags, Blip::new);

        public boolean is(int flag) {
            return (flags & flag) != 0;
        }
    }

    /**
     * Chunk absent from client, sent to Voxy LOD ({@code compat/VoxyLods}, Voxy players only): distant ruins, craters,
     * detonation traces and district lamps. {@code sections}: sections with light ({@code compat/LodSections}); dimension:
     * whose chunk it is — packets from old dimension rejected after dimension change.
     */
    public record VoxyChunk(net.minecraft.resources.ResourceLocation dimension, int x, int z, byte[] sections) implements CustomPacketPayload {
        /** Maximum chunk size: 24 globally paletted sections and two light layers — ~250 KB. */
        static final int MAX_BYTES = 1 << 20;
        public static final Type<VoxyChunk> TYPE = new Type<>(Incoming.id("voxy_chunk"));
        public static final StreamCodec<ByteBuf, VoxyChunk> CODEC = StreamCodec.composite(
                net.minecraft.resources.ResourceLocation.STREAM_CODEC, VoxyChunk::dimension, ByteBufCodecs.VAR_INT, VoxyChunk::x,
                ByteBufCodecs.VAR_INT, VoxyChunk::z, ByteBufCodecs.byteArray(MAX_BYTES), VoxyChunk::sections, VoxyChunk::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VoxyChunk v && v.x == x && v.z == z && v.dimension.equals(dimension) && java.util.Arrays.equals(v.sections, sections);
        }

        @Override
        public int hashCode() {
            return (dimension.hashCode() * 31 + x) * 31 + z;
        }

        @Override
        public String toString() {
            return "VoxyChunk[" + dimension + " " + x + " " + z + ", " + sections.length + " bytes]";
        }
    }
}
