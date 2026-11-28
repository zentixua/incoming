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

    /** Server-approved order snapshot. This is a review, not a claim of prepared terrain/LOD readiness. */
    public record NuclearReview(UUID id, C2S.Fire order, Vec3 target, float visibility, float scale, boolean finished) implements CustomPacketPayload {
        public NuclearReview(UUID id, C2S.Fire order, Vec3 target, float visibility, float scale) {
            this(id, order, target, visibility, scale, false);
        }
        public static final Type<NuclearReview> TYPE = new Type<>(Incoming.id("nuclear_review"));
        public static final StreamCodec<ByteBuf, NuclearReview> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, NuclearReview::id, C2S.Fire.CODEC, NuclearReview::order,
                StreamCodecs.VEC3, NuclearReview::target, ByteBufCodecs.FLOAT, NuclearReview::visibility,
                ByteBufCodecs.FLOAT, NuclearReview::scale, ByteBufCodecs.BOOL, NuclearReview::finished, NuclearReview::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

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
                        boolean fuelApart, Optional<Support> support, int show) implements CustomPacketPayload {
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
        /**
         * The strike's show, game ticks from the blast ({@code warhead.StrikeShow}: until the cloud's apex and the crater fuel's
         * burn-out): hearing it harms recovers at real rate until then. {@code StrikeShow.NONE} — none (a recording made before
         * this field, an explosion that is no strike). Written plus one: "none" is one byte, not a negative varint's five.
         */
        private static final StreamCodec<ByteBuf, Integer> SHOW = StreamCodecs.trailing(ByteBufCodecs.VAR_INT.map(v -> v - 1, v -> v + 1),
                ua.zentix.incoming.warhead.StrikeShow.NONE);
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
            SHOW.encode(b, p.show);
        }, b -> new Blast(ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                ByteBufCodecs.FLOAT.decode(b), ByteBufCodecs.VAR_LONG.decode(b), PROJECTILE.decode(b), WEAPON.decode(b), AXIS.decode(b),
                FUEL_APART.decode(b), SUPPORT.decode(b), SHOW.decode(b)));

        /** Explosion without a show ({@code StrikeShow.NONE}). */
        public Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile, int weapon, Vec3 axis,
                     boolean fuelApart, Optional<Support> support) {
            this(kind, pos, material, surfaceY, seed, projectile, weapon, axis, fuelApart, support, ua.zentix.incoming.warhead.StrikeShow.NONE);
        }

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
     * Conventional strikes still in the air around a player ({@code strike/StrikeClouds}, rule 10 of the time note): every
     * cloud whose law still shows it and every crater fuel fire still burning, each with what the client needs to rebuild it
     * at its age. Sent on login, dimension change and respawn, and after new strikes (at most once a second); the last one
     * is the whole state, so a Flashback snapshot, which keeps only the last packet of a type, holds it too.
     */
    public record StrikeClouds(List<Cloud> clouds) implements CustomPacketPayload {
        public static final Type<StrikeClouds> TYPE = new Type<>(Incoming.id("strike_clouds"));
        /** Most clouds in one packet: the client draws at most as many explosion events ({@code FarBlasts.CAP}). */
        public static final int MAX = 256;
        /** Cloud packets by kind; a kind a reader does not know is skipped. */
        public static final int BLAST = 0, ENTRY = 1, VENT = 2, COLLAPSE = 3, AIRBURST = 4, FUEL = 5;

        /**
         * One strike's cloud or crater fire.
         *
         * @param kind    {@link #BLAST} ... {@link #FUEL}
         * @param born    server game time of the event, ticks
         * @param jumps   counted jumps of its dimension at the event ({@code WorldClock.counted}), clock ticks: the
         *                motion age adds those counted since
         * @param air     world air at the event: the cloud drifts in it all its life
         * @param surface ground under the blast point as the client measures it ({@code FarBlasts.overSurface}), from
         *                the server's chunks before the crater; NaN: unknown, the client measures it
         * @param packet  the event's own packet, as sent live
         */
        public record Cloud(int kind, long born, double jumps, ua.zentix.incoming.util.Air air, float surface, CustomPacketPayload packet) {
            private static final StreamCodec<ByteBuf, ua.zentix.incoming.util.Air> AIR = StreamCodec.composite(
                    ByteBufCodecs.FLOAT, a -> (float) a.wind().direction(), ByteBufCodecs.FLOAT, a -> (float) a.wind().speed(),
                    ByteBufCodecs.FLOAT, a -> (float) a.stability(), ByteBufCodecs.FLOAT, a -> (float) a.humidity(),
                    (d, s, c, h) -> new ua.zentix.incoming.util.Air(new ua.zentix.incoming.util.Wind(d, s), c, h));
            /** Kind of {@code packet}; -1 for a packet that is no cloud. */
            public static int kindOf(CustomPacketPayload p) {
                return switch (p) {
                    case Blast b -> BLAST;
                    case BunkerImpact b -> ENTRY;
                    case Vent v -> VENT;
                    case Collapse c -> COLLAPSE;
                    case Intercept i -> AIRBURST;
                    case FuelFire f -> FUEL;
                    default -> -1;
                };
            }

            @SuppressWarnings("unchecked")
            private static StreamCodec<ByteBuf, CustomPacketPayload> codec(int kind) {
                StreamCodec<ByteBuf, ? extends CustomPacketPayload> c = switch (kind) {
                    case BLAST -> Blast.CODEC;
                    case ENTRY -> BunkerImpact.CODEC;
                    case VENT -> Vent.CODEC;
                    case COLLAPSE -> Collapse.CODEC;
                    case AIRBURST -> Intercept.CODEC;
                    case FUEL -> FuelFire.CODEC;
                    default -> null;
                };
                return (StreamCodec<ByteBuf, CustomPacketPayload>) c;
            }

            /** Each cloud and its packet in a frame: the packets' own trailing fields stay theirs, unknown kinds decode to null. */
            static final StreamCodec<ByteBuf, Cloud> CODEC = StreamCodecs.framed(StreamCodec.of((b, c) -> {
                ByteBufCodecs.VAR_INT.encode(b, c.kind);
                ByteBufCodecs.VAR_LONG.encode(b, c.born);
                ByteBufCodecs.DOUBLE.encode(b, c.jumps);
                AIR.encode(b, c.air);
                ByteBufCodecs.FLOAT.encode(b, c.surface);
                StreamCodecs.framed(codec(c.kind)).encode(b, c.packet);
            }, b -> {
                int kind = ByteBufCodecs.VAR_INT.decode(b);
                StreamCodec<ByteBuf, CustomPacketPayload> c = codec(kind);
                // a kind this reader does not know may carry anything after it: its frame is skipped whole
                if (c == null) return null;
                long born = ByteBufCodecs.VAR_LONG.decode(b);
                double jumps = ByteBufCodecs.DOUBLE.decode(b);
                ua.zentix.incoming.util.Air air = AIR.decode(b);
                float surface = ByteBufCodecs.FLOAT.decode(b);
                return new Cloud(kind, born, jumps, air, surface, StreamCodecs.framed(c).decode(b));
            }));
        }

        public static final StreamCodec<ByteBuf, StrikeClouds> CODEC = StreamCodec.of((b, p) -> {
            ByteBufCodecs.VAR_INT.encode(b, p.clouds.size());
            for (Cloud c : p.clouds) Cloud.CODEC.encode(b, c);
        }, b -> {
            int n = ByteBufCodecs.VAR_INT.decode(b);
            if (n < 0 || n > MAX) throw new DecoderException("strike clouds: " + n);
            List<Cloud> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Cloud c = Cloud.CODEC.decode(b);
                if (c != null) out.add(c);
            }
            return new StrikeClouds(List.copyOf(out));
        });

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
     * The server's world clocks ({@code util.WorldClock}) and this dimension's detonation clock anchors
     * ({@code util.StrikeClock}, {@code nuclear.NuclearEvents.Anchor}). Sent to every player once a second, also with no
     * detonations (the hearing clock follows it), when a show ends and on entry: the whole state in one packet, so the last
     * one (all a Flashback snapshot keeps) restores it after a replay seek; a lagging server pulls a client that ran ahead
     * back. {@link #ends} is the format of recordings made before {@link #clocks} (filled only there): each ended show's
     * world clock at its end; such a recording means a 20-minute show and no jumps.
     */
    public record NukeClock(double clock, List<ShowEnd> ends, Clocks clocks) implements CustomPacketPayload {
        public static final Type<NukeClock> TYPE = new Type<>(Incoming.id("nuke_clock"));
        public static final StreamCodec<ByteBuf, NukeClock> CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, NukeClock::clock,
                ShowEnd.CODEC.apply(ByteBufCodecs.list()), NukeClock::ends,
                StreamCodecs.trailing(Clocks.CODEC, Clocks.NONE), NukeClock::clocks,
                NukeClock::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** The format of recordings made before {@link Clocks}. */
        public NukeClock(double clock, List<ShowEnd> ends) {
            this(clock, ends, Clocks.NONE);
        }

        /** Detonation {@code id}'s show ended at world clock {@code at} (recordings made before {@link Clocks}). */
        public record ShowEnd(int id, double at) {
            public static final StreamCodec<ByteBuf, ShowEnd> CODEC = StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ShowEnd::id, ByteBufCodecs.DOUBLE, ShowEnd::at, ShowEnd::new);
        }

        /**
         * The clock without jumps, the world's rate k, the jumps counted and not counted in the receiver's dimension
         * (world-clock ticks), and each detonation's anchors there. {@link #NONE}: an old recording.
         */
        public record Clocks(double rate, double pace, double counted, double unseen, List<Anchor> anchors) {
            public static final Clocks NONE = new Clocks(Double.NaN, Double.NaN, 0, 0, List.of());
            public static final StreamCodec<ByteBuf, Clocks> CODEC = StreamCodec.composite(
                    ByteBufCodecs.DOUBLE, Clocks::rate, ByteBufCodecs.DOUBLE, Clocks::pace,
                    ByteBufCodecs.DOUBLE, Clocks::counted, ByteBufCodecs.DOUBLE, Clocks::unseen,
                    Anchor.CODEC.apply(ByteBufCodecs.list()), Clocks::anchors, Clocks::new);

            public boolean known() {
                return !Double.isNaN(rate);
            }
        }

        /**
         * Detonation {@code id}'s two ages ({@code util.StrikeClock}): counted jumps at the burst; real ticks from the burst
         * to the show's end (−1: running), the clock without jumps and the counted jumps there; the clock without jumps at
         * the blend's end (NaN: not yet).
         */
        public record Anchor(int id, double jumps, long endAge, double endRate, double endJumps, double rampRate) {
            public static final StreamCodec<ByteBuf, Anchor> CODEC = StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, Anchor::id, ByteBufCodecs.DOUBLE, Anchor::jumps, ByteBufCodecs.VAR_LONG, Anchor::endAge,
                    ByteBufCodecs.DOUBLE, Anchor::endRate, ByteBufCodecs.DOUBLE, Anchor::endJumps,
                    ByteBufCodecs.DOUBLE, Anchor::rampRate, Anchor::new);
        }
    }

    /**
     * The server's game time, sent every server tick while a detonation in the dimension is still in its show
     * ({@code nuclear.NukeClock.SHOW_S}): each detonation's server age (this minus its detonation time) anchors the
     * client's detonation clock ({@code ClientNuclear.AgeClock}), so the drawn burst, wave and sound never run ahead of
     * the server's wave and ruins. One varlong per tick to the dimension's players.
     */
    public record NukeTick(long gameTime) implements CustomPacketPayload {
        public static final Type<NukeTick> TYPE = new Type<>(Incoming.id("nuke_tick"));
        public static final StreamCodec<ByteBuf, NukeTick> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, NukeTick::gameTime, NukeTick::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * The sea level of the player's level ({@code util/Terrain.sea}: clients have no generator), on login, dimension change
     * and respawn; a replay snapshot keeps the last one.
     */
    public record SeaLevel(int y) implements CustomPacketPayload {
        public static final Type<SeaLevel> TYPE = new Type<>(Incoming.id("sea_level"));
        public static final StreamCodec<ByteBuf, SeaLevel> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, SeaLevel::y, SeaLevel::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Scheduled nuclear strikes cancelled one by one ({@code clear tag}): their warnings, countdowns and sirens end; other
     * strikes and live detonations stay (a full {@link NukeSync} would restart them).
     */
    public record NukeCancelled(List<Integer> strikes) implements CustomPacketPayload {
        public static final Type<NukeCancelled> TYPE = new Type<>(Incoming.id("nuke_cancelled"));
        public static final StreamCodec<ByteBuf, NukeCancelled> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), NukeCancelled::strikes, NukeCancelled::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Full terminal snapshot and server epoch. Repeated snapshots also restore Flashback seek/late join. */
    public record NukeApproach(long time, List<ua.zentix.incoming.nuclear.IcbmArrival> arrivals) implements CustomPacketPayload {
        public static final Type<NukeApproach> TYPE = new Type<>(Incoming.id("nuke_approach"));
        public static final StreamCodec<ByteBuf, NukeApproach> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, NukeApproach::time,
                ua.zentix.incoming.nuclear.IcbmArrival.STREAM_CODEC.apply(ByteBufCodecs.list()), NukeApproach::arrivals,
                NukeApproach::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Nuclear detonation: client computes everything else (flash, fireball, mushroom, wave, sound, rain) from model. Except
     * server settings: {@code blackout} — grid-outage radius ({@code Blackouts.nukeRadius}; 0 — no
     * outage), where lamps short with EMP arcs.
     * <p>
     * Format only grows at end (replay.md): 3.1.0 fields, then {@code wave}, {@code blackout}, detonation time of day
     * ({@link Detonation#dayTime}), fires ({@link Detonation#fires}), strike id, black rain ({@link Detonation#rain}), material
     * source, ground class and block fire ({@link Detonation#blockFire}), each appended to packet end; recordings predating
     * them read “as before”: wall speed {@link Detonation#OLD_WAVE}, no EMP, time of day unknown, fires enabled, no strike,
     * black rain enabled, no material source, ground unknown, block fire lit.
     */
    public record NukeDetonation(Detonation detonation, float blackout, int strikeId, @org.jetbrains.annotations.Nullable UUID id, byte ground) implements CustomPacketPayload {
        public NukeDetonation {
            if (ground < -1 || ground > 3) throw new IllegalArgumentException("Ground material class");
        }
        public NukeDetonation(Detonation d, float blackout, int strikeId, UUID id) { this(d, blackout, strikeId, id, (byte) -1); }
        // Flashback snapshots group independent instances of a payload by their UUID record component.
        public Optional<UUID> material() { return Optional.ofNullable(id); }
        public NukeDetonation(Detonation d, float blackout, int strikeId) { this(d, blackout, strikeId, null); }
        public NukeDetonation(Detonation detonation, float blackout) { this(detonation, blackout, -1); }
        public static final Type<NukeDetonation> TYPE = new Type<>(Incoming.id("nuke_detonation"));
        private static final StreamCodec<ByteBuf, NukeDetonation> BASE = NeoForgeStreamCodecs.composite(
                Detonation.STREAM_CODEC, NukeDetonation::detonation,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, Detonation.OLD_WAVE), p -> p.detonation.wave(),
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, 0f), NukeDetonation::blackout,
                StreamCodecs.trailing(ByteBufCodecs.VAR_LONG, Detonation.NO_DAY_TIME), p -> p.detonation.dayTime(),
                StreamCodecs.trailing(ByteBufCodecs.BOOL, true), p -> p.detonation.fires(),
                StreamCodecs.trailing(ByteBufCodecs.VAR_INT, -1), NukeDetonation::strikeId,
                StreamCodecs.trailing(ByteBufCodecs.BOOL, true), p -> p.detonation.rain(),
                (d, wave, blackout, dayTime, fires, strikeId, rain) -> new NukeDetonation(
                        d.withWave(wave).withDayTime(dayTime).withFires(fires).withRain(rain), blackout, strikeId));
        public static final StreamCodec<ByteBuf, NukeDetonation> CODEC = StreamCodec.composite(
                BASE, p -> p, StreamCodecs.trailing(ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Optional.empty()), NukeDetonation::material,
                StreamCodecs.trailing(ByteBufCodecs.BYTE, (byte) -1), NukeDetonation::ground,
                StreamCodecs.trailing(ByteBufCodecs.BOOL, true), p -> p.detonation.blockFire(),
                (p, material, ground, blockFire) -> new NukeDetonation(p.detonation.withBlockFire(blockFire), p.blackout, p.strikeId, material.orElse(null), ground));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Complete current source membership, one replaceable record in a Flashback snapshot. */
    public record NukeMaterialSources(List<UUID> sources) implements CustomPacketPayload {
        public static final Type<NukeMaterialSources> TYPE = new Type<>(Incoming.id("nuke_material_sources"));
        public static final StreamCodec<ByteBuf, NukeMaterialSources> CODEC = UUIDUtil.STREAM_CODEC
                .apply(ByteBufCodecs.list()).map(NukeMaterialSources::new, NukeMaterialSources::sources);
        @Override public Type<NukeMaterialSources> type() { return TYPE; }
    }

    /** Ordered revision batch. The UUID and remaining-batch count also identify complete Flashback snapshots. */
    public record NukeMaterial(UUID id, int part, int remainingBatches, byte[] data) implements CustomPacketPayload {
        public static final int PART = 12_500;
        public boolean tombstone() { return part == 0 && remainingBatches == 0 && data.length == 1 && data[0] == 0; }
        public static final Type<NukeMaterial> TYPE = new Type<>(Incoming.id("nuke_material"));
        public static final StreamCodec<ByteBuf, NukeMaterial> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, NukeMaterial::id, ByteBufCodecs.VAR_INT, NukeMaterial::part,
                ByteBufCodecs.VAR_INT, NukeMaterial::remainingBatches, ByteBufCodecs.byteArray(PART), NukeMaterial::data,
                NukeMaterial::new);
        public NukeMaterial {
            if (part < 0 || remainingBatches < 0 || (long) part + remainingBatches >= 128
                    || data.length == 0 || data.length > PART || remainingBatches > 0 && data.length != PART)
                throw new IllegalArgumentException("Dust material batch");
        }
        @Override public Type<NukeMaterial> type() { return TYPE; }
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
     * of day {@link Detonation#dayTime}, fires {@link Detonation#fires}, black rain {@link Detonation#rain}, after the
     * material map block fire {@link Detonation#blockFire}) in trailing packet lists, one entry per detonation (format grows
     * at end, replay.md): older recordings lack lists — “as before”.
     */
    public record NukeSync(List<Detonation> detonations, List<NukeWarning> warnings, java.util.Map<Integer, UUID> materials)
            implements CustomPacketPayload {
        public NukeSync(List<Detonation> detonations, List<NukeWarning> warnings) { this(detonations, warnings, java.util.Map.of()); }
        public static final Type<NukeSync> TYPE = new Type<>(Incoming.id("nuke_sync"));
        public static final StreamCodec<ByteBuf, NukeSync> CODEC = NeoForgeStreamCodecs.composite(
                Detonation.STREAM_CODEC.apply(ByteBufCodecs.list()), NukeSync::detonations,
                NukeWarning.CODEC.apply(ByteBufCodecs.list()), NukeSync::warnings,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::wave).toList(),
                StreamCodecs.trailing(ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::dayTime).toList(),
                StreamCodecs.trailing(ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::fires).toList(),
                StreamCodecs.trailing(ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::rain).toList(),
                Tail.CODEC, p -> new Tail(p.materials, p.detonations.stream().map(Detonation::blockFire).toList()),
                (detonations, warnings, waves, dayTimes, fires, rain, tail) -> new NukeSync(
                        extended(detonations, waves, dayTimes, fires, rain, tail.blockFire), warnings, tail.materials));

        /** Packet end: the material map, then block fire per detonation (one composite holds at most 7 fields). */
        private record Tail(java.util.Map<Integer, UUID> materials, List<Boolean> blockFire) {
            static final StreamCodec<ByteBuf, Tail> CODEC = StreamCodec.composite(
                    StreamCodecs.trailing(ByteBufCodecs.map(java.util.HashMap::new, ByteBufCodecs.VAR_INT, UUIDUtil.STREAM_CODEC), java.util.Map.of()), Tail::materials,
                    StreamCodecs.trailing(ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), List.of()), Tail::blockFire,
                    Tail::new);
        }

        /** Detonations with trailing-list fields (empty list: field absent from recording). */
        private static List<Detonation> extended(List<Detonation> detonations, List<Float> waves, List<Long> dayTimes,
                List<Boolean> fires, List<Boolean> rain, List<Boolean> blockFire) {
            check("waves", waves, detonations);
            check("day times", dayTimes, detonations);
            check("fires", fires, detonations);
            check("black rain", rain, detonations);
            check("block fire", blockFire, detonations);
            List<Detonation> out = new ArrayList<>(detonations.size());
            for (int i = 0; i < detonations.size(); i++) {
                Detonation d = detonations.get(i);
                if (!waves.isEmpty()) d = d.withWave(waves.get(i));
                if (!dayTimes.isEmpty()) d = d.withDayTime(dayTimes.get(i));
                if (!fires.isEmpty()) d = d.withFires(fires.get(i));
                if (!rain.isEmpty()) d = d.withRain(rain.get(i));
                if (!blockFire.isEmpty()) d = d.withBlockFire(blockFire.get(i));
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

    /** The player's ear as the server kept it ({@link C2S.Ear}), on login: hearing is kept across relogging. Empty — a healthy ear. */
    public record Ear(net.minecraft.nbt.CompoundTag state) implements CustomPacketPayload {
        public static final Type<Ear> TYPE = new Type<>(Incoming.id("ear"));
        public static final StreamCodec<ByteBuf, Ear> CODEC = ByteBufCodecs.compoundTagCodec(() -> net.minecraft.nbt.NbtAccounter.create(C2S.Ear.EAR_BYTES))
                .map(Ear::new, Ear::state);

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
    /**
     * A chunk's sections for Voxy LODs ({@code compat/LodSections} bytes): server queues and pacing count {@code sections}
     * as they are; the wire carries them deflated ({@link StreamCodecs#deflated}): a Flashback recording keeps every
     * received byte though a replay never ingests them ({@code replay.md}), and ruin sections deflate about 8× (zlib 6).
     */
    public record VoxyChunk(net.minecraft.resources.ResourceLocation dimension, int x, int z, byte[] sections) implements CustomPacketPayload {
        /** Maximum chunk size: 24 globally paletted sections and two light layers — ~250 KB. */
        static final int MAX_BYTES = 1 << 20;
        public static final Type<VoxyChunk> TYPE = new Type<>(Incoming.id("voxy_chunk_z"));
        public static final StreamCodec<ByteBuf, VoxyChunk> CODEC = StreamCodec.composite(
                net.minecraft.resources.ResourceLocation.STREAM_CODEC, VoxyChunk::dimension, ByteBufCodecs.VAR_INT, VoxyChunk::x,
                ByteBufCodecs.VAR_INT, VoxyChunk::z, StreamCodecs.deflated(MAX_BYTES), VoxyChunk::sections, VoxyChunk::new);

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

    /**
     * {@link VoxyChunk} as releases up to 3.14.0 sent it (sections as they are), kept so their Flashback replays still
     * read: a replay never ingests it ({@code client/VoxyIngest}).
     */
    public record VoxyChunkRaw(VoxyChunk chunk) implements CustomPacketPayload {
        public static final Type<VoxyChunkRaw> TYPE = new Type<>(Incoming.id("voxy_chunk"));
        public static final StreamCodec<ByteBuf, VoxyChunkRaw> CODEC = StreamCodec.composite(
                net.minecraft.resources.ResourceLocation.STREAM_CODEC, VoxyChunk::dimension, ByteBufCodecs.VAR_INT, VoxyChunk::x,
                ByteBufCodecs.VAR_INT, VoxyChunk::z, ByteBufCodecs.byteArray(VoxyChunk.MAX_BYTES), VoxyChunk::sections, VoxyChunk::new)
                .map(VoxyChunkRaw::new, VoxyChunkRaw::chunk);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    /** Marks the end of a Voxy LOD batch: the chunks since the previous mark, on the same ordered handler as them. */
    public record LodBatchFinished(int id, int chunks) implements CustomPacketPayload {
        public static final Type<LodBatchFinished> TYPE = new Type<>(Incoming.id("lod_batch_finished"));
        public static final StreamCodec<ByteBuf, LodBatchFinished> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LodBatchFinished::id, ByteBufCodecs.VAR_INT, LodBatchFinished::chunks, LodBatchFinished::new);
        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

}
