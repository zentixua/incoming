package ua.zentix.incoming.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.zentix.incoming.nuclear.radiation.Shielding;


/**
 * Block pieces torn off and carried by the shock wave: tumbling bodies with their block texture (like block-breaking
 * particles, but three-dimensional) fly, fall, hit the ground and settle. Vanilla engine particles on the block atlas:
 * world light, fog and shader pack as for everything in the world; only the body is custom. At most {@link #CAP} alive at once
 * (fewer by the Particles setting): when full, a new piece closer to the camera evicts the farthest; otherwise it is not spawned.
 * <p>
 * A piece is a body of its material ({@link Kind}): stone or wood chunk — cube, plank, glass shard, leaf. The wave does not
 * throw it; wind behind the front carries it ({@link Wind}): torn off at rest, the piece accelerates under quadratic air
 * drag dv/dt = g + (g/v_t²)·|u − v|·(u − v), where v_t is the body's terminal fall speed in still air
 * ({@link #terminal}) from its mass and area: a foliage tuft (v_t ~1.7 m/s) follows the wind immediately and glides for a long time; glass and planks
 * (14–19 m/s) accelerate over tenths of a second; stone (~100 m/s) barely moves and falls nearby. The same visible size
 * also sets the drag.
 */
public final class Fragments {
    /** Maximum live pieces with Particles: All. */
    static final int CAP = 1200;
    /** Piece lifetime: chunk, plank, glass — from {@link #LIFE_SHORT} − 40 ticks; a leaf glides up to {@link #LIFE_MAX}. */
    static final int LIFE_SHORT = 110, LIFE_MAX = 300;
    /** Maximum launch delay, ticks. */
    public static final int DELAY_MAX = 40;
    /** During this many final lifetime ticks, the piece shrinks to zero (settles and crumbles). */
    private static final int SHRINK = 15;
    /** A piece resting on the ground for this many ticks disappears. */
    private static final int REST = 20;
    /** Gravitational acceleration, blocks per tick² (9.81 m/s²; block — metre, tick — 1/20 s). */
    public static final double G = 9.81 / 400;
    /** Ground-level air density, kg/m³. */
    static final double AIR = 1.2;
    /**
     * Drag coefficient: square plate across the flow — 1.17, cube face-on — 1.05 (Hoerner, “Fluid-Dynamic
     * Drag”, 1965, ch. 3). A tumbling plate meets the flow face-on and edge-on — this is an upper drag estimate for it.
     */
    static final double CD_PLATE = 1.17, CD_CUBE = 1.05;
    /**
     * Foliage mass per area, kg/m²: dry tree foliage — 0.03–0.15 (Poorter et al., New Phytologist 182, 2009); a living
     * leaf with water is two to three times heavier (est.) (0.2). Terminal speed is ~1.7 m/s, like falling leaves.
     */
    static final double LEAF_AREAL = 0.2;
    /**
     * A canopy piece is a 0.2–0.35 m foliage tuft with a twig, rather than an individual leaf: the wave tears both leaves and branches, while the drawing
     * samples a few pieces per canopy block that contains hundreds of real leaves; a 10 cm leaf at 60 blocks is subpixel, so
     * a sample of leaves would vanish entirely. Tuft mass per area matches leaves (thin twig) (est.).
     */
    static final float SPRIG_MIN = 0.2f, SPRIG_MAX = 0.35f;
    /** Plank thickness, m (2.5 cm — a normal cladding or floor plank). */
    static final double BOARD_THICK = 0.025;
    /**
     * Upward wind as a fraction of horizontal wind at the piece (Kuhl et al. 1992, §3.4: vertical velocity in the dusty layer behind the front —
     * 0.02 of wind at the bottom and 0.04–0.12 at the top); each piece has its own fraction.
     */
    public static final double LIFT_MIN = 0.02, LIFT_MAX = 0.12;
    /**
     * Upward wind for a piece from a structure or canopy, up to tan(25°) ≈ 0.47 (est.; model separation angle).
     * Castro and Robins, “The flow around a surface-mounted cube in uniform and turbulent streams”,
     * J. Fluid Mech. 79 (1977), 307–335, doi:10.1017/S0022112077000172, studies building wakes and their
     * dependence on incoming flow; it is not a verified source for a universal 15–30° angle or canopy uplift.
     * Keeping this fraction throughout the flight, beyond the local separation region, is an approximation (est.).
     */
    public static final double LIFT_EDGE = Math.tan(Math.toRadians(25));
    /**
     * Live pieces use a separate list rather than death callbacks: the engine also discards particles without those (queue overflow, world
     * change), so a piece not ticked for more than {@link #LOST} ticks is considered gone.
     */
    private static final FarthestPool<Fragment> LIVE = new FarthestPool<>(Particle::isAlive, Particle::remove);
    private static final int LOST = 2;
    private static long tick;
    private static final RandomSource RANDOM = RandomSource.create();

    private Fragments() {}

    /** Piece material — determines body and drag. */
    public enum Kind {
        /** Stone, brick, soil, metal or log chunk — cube. */
        CHUNK(1.5),
        /** Plank (planks, doors, fences, wooden stairs) — long thin plate. */
        BOARD(2),
        /** Window glass shard — thin plate. */
        PANE(3),
        /** Canopy foliage tuft ({@link #SPRIG_MIN}–{@link #SPRIG_MAX} m) — thin plate. */
        LEAF(6);

        /**
         * Pieces drawn per destroyed block — a visual sample, rather than the fragment count (a cubic metre of canopy holds hundreds of
         * leaves): foliage tufts and glass are smaller than chunks; more are needed to be visible.
         */
        public final double perBlock;

        Kind(double perBlock) {
            this.perBlock = perBlock;
        }

        /** Material of block {@code s} (at {@code p}). */
        public static Kind of(LevelReader level, BlockPos p, BlockState s) {
            if (s.is(BlockTags.LEAVES)) return LEAF;
            if (Shielding.window(level, p, s)) return PANE;
            if (s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_FENCES)
                    || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS)) return BOARD;
            return CHUNK;
        }
    }

    /** Wind carrying a piece. */
    public interface Wind {
        /**
         * Current wind at (x, y, z): {@code into[0]}, {@code into[2]} — along the ground, blocks per tick; {@code into[1]} —
         * speed along its direction from the wave source (negative during the rarefaction phase).
         */
        void at(double x, double y, double z, double[] into);
    }

    /**
     * Terminal fall speed in still air, m/s: v_t = √(2mg / (ρ·C·A)). Plate (leaf, glass, plank) — mass per
     * face area σ: √(2σg / (ρ·C_plate)); cube of edge L and material density ρ_m: √(2ρ_m·L·g / (ρ·C_cube)).
     *
     * @param density material density, kg/m³ ({@link Shielding#material}; for glass — window mass per square metre)
     * @param size    cube edge or plate length, m
     */
    public static double terminal(Kind kind, double density, double size) {
        double g = 9.81;
        return switch (kind) {
            case CHUNK -> Math.sqrt(2 * density * size * g / (AIR * CD_CUBE));
            case BOARD -> Math.sqrt(2 * density * BOARD_THICK * g / (AIR * CD_PLATE));
            case PANE -> Math.sqrt(2 * density * g / (AIR * CD_PLATE));
            case LEAF -> Math.sqrt(2 * LEAF_AREAL * g / (AIR * CD_PLATE));
        };
    }

    /** Drag g/v_t², 1/block, for a body of terminal speed {@code terminalMps}, m/s, in still air. */
    public static double drag(double terminalMps) {
        double vt = terminalMps / 20;
        return G / (vt * vt);
    }

    /**
     * One body flight tick with velocity {@code v} (blocks per tick, mutated in place) in wind (ux, uy, uz) with drag
     * {@code drag} = g/v_t² (1/block): exact quadratic drag over the tick (|v − u| falls as 1/(1 + k·|v − u|·t):
     * for a leaf k·|v − u| is hundreds of times above one and an Euler step would diverge), gravity split around it. Returns |v − u|.
     */
    public static double step(double[] v, double ux, double uy, double uz, double drag) {
        // half gravity before drag and half after (a leaf settles in 3 ticks; a single gravity
        // step after drag would shift terminal speed by 15 %)
        double rx = v[0] - ux, ry = v[1] - 0.5 * G - uy, rz = v[2] - uz, rel = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double keep = 1 / (1 + drag * rel);
        v[0] = ux + rx * keep;
        v[1] = uy + ry * keep - 0.5 * G;
        v[2] = uz + rz * keep;
        return rel;
    }

    /**
     * Piece of block {@code state} (from {@code from}: its grass and foliage colour), material {@code kind}, centre (x, y, z) and
     * velocity (vx, vy, vz), blocks per tick (torn off in place — at rest): launches after {@code delay} ticks (invisible and stationary
     * until then), flies in {@code wind}. {@code size} — chunk edge or plank/shard length, blocks (foliage tuft has its
     * own size). {@code edge} — piece from a structure or canopy: flow at its edge lifts it ({@link #LIFT_EDGE}).
     *
     * @return false — not spawned (air, block without a model, no slots)
     */
    public static boolean torn(ClientLevel level, BlockState state, BlockPos from, Kind kind, double x, double y, double z, double vx, double vy, double vz,
                               float size, Wind wind, int delay, boolean edge) {
        return torn(level, state, from, kind, x, y, z, vx, vy, vz, size, Double.NaN, wind, delay, edge);
    }

    /**
     * Same, but the body flies with its own still-air terminal speed {@code terminalMps}, m/s, and is drawn at
     * {@code size}: enlarges matter invisible on screen at real size (camera litter — a 2 mm soil grain drawn with a 6–12 cm
     * face, presentation), while flight, settling and drift remain those of the real body. NaN — terminal speed by size and
     * material ({@link #terminal}).
     */
    public static boolean torn(ClientLevel level, BlockState state, BlockPos from, Kind kind, double x, double y, double z, double vx, double vy, double vz,
                               float size, double terminalMps, Wind wind, int delay, boolean edge) {
        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL || !state.shouldSpawnTerrainParticles()) return false;
        // no slots — evict the farthest from the camera if farther than the new piece ({@link FarthestPool}): wave travels toward the camera, and
        // nearby destroyed sites arrive last — otherwise distant chunk debris would take all slots and the camera,
        // where pieces are visible, would see none
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = eye.distanceToSqr(x, y, z);
        if (!LIVE.admit(d, cap(), tick, f -> eye.distanceToSqr(f.x(), f.y(), f.z()))) return false;
        int wait = Mth.clamp(delay, 0, DELAY_MAX);
        int life = kind == Kind.LEAF ? LIFE_MAX : LIFE_SHORT - 40 + RANDOM.nextInt(41);
        Fragment f = new Fragment(level, state, from, kind, x, y, z, vx, vy, vz, size, terminalMps, wind, wait, life, edge);
        LIVE.add(f, d, tick);
        Minecraft.getInstance().particleEngine.add(f);
        return true;
    }

    private static int cap() {
        ParticleStatus s = Minecraft.getInstance().options.particles().get();
        return s == ParticleStatus.ALL ? CAP : s == ParticleStatus.DECREASED ? CAP / 2 : CAP / 4;
    }

    /**
     * Piece allowance for a new source requesting {@code wanted}: no more than free slots, but at least one quarter of the
     * cap — the farthest will free their slots ({@link #torn}). Otherwise an eight-blast salvo in one tick spawned
     * 8 × 900 tufts for 1200 slots, almost all immediately evicted (tens of ms on the birth tick).
     */
    public static int share(int wanted) {
        return Math.min(wanted, Math.max(cap() / 4, cap() - alive()));
    }

    /** Live piece count. */
    public static int alive() {
        return LIVE.size();
    }

    /** Client tick (unpaused): remove departed pieces from the count. */
    public static void tick() {
        tick++;
        LIVE.purge(f -> f.isAlive() && tick - f.ticked <= LOST);
    }

    /** World exit: the engine removes particles itself. */
    public static void reset() {
        LIVE.clear();
    }

    /** Piece: rectangular body with half-sizes along its axes, rotated around its own axis, block texture on faces. */
    private static final class Fragment extends Particle {
        /** Body face axes: normal, first and second face axes (body axis index), normal sign; a × b = normal. */
        private static final int[][] FACES = {{0, 1, 2, 1}, {0, 2, 1, -1}, {1, 2, 0, 1}, {1, 0, 2, -1}, {2, 0, 1, 1}, {2, 1, 0, -1}};
        private static final Quaternionf TURN = new Quaternionf();
        private static final Vector3f[] AXES = {new Vector3f(), new Vector3f(), new Vector3f()};
        private static final double[] AIR_NOW = new double[3], VEL = new double[3];

        /** Half-sizes along body axes, blocks. */
        private final float[] half = new float[3];
        private final float lift, u0, v0, u1, v1;
        private final float ax, ay, az;
        /** Drag g/v_t², 1/block. */
        private final double drag;
        private final Wind wind;
        private float angle, angleO, spin;
        private int delay, rest;
        /** First flight tick passed (bounds extracted from blocks). */
        private boolean out;
        /** Hit a block other than from above (ceiling, canopy above): vanilla no longer moves it, but does not set onGround. */
        private boolean stuck;
        /** {@link Fragments#tick} tick when the piece last ticked (stops when the engine discards it). */
        private long ticked = Fragments.tick;

        Fragment(ClientLevel level, BlockState state, BlockPos from, Kind kind, double x, double y, double z, double vx, double vy, double vz,
                 float size, double terminalMps, Wind wind, int delay, int life, boolean edge) {
            super(level, x, y, z);
            this.delay = delay;
            this.wind = wind;
            float len = switch (kind) {
                case CHUNK -> size;
                case BOARD -> Math.min(1, 1.6f * size);
                case PANE -> 0.6f * size;
                // tuft of its own size (0 — canopy tuft); a plate's terminal speed is independent of size
                case LEAF -> size > 0 ? size : SPRIG_MIN + (SPRIG_MAX - SPRIG_MIN) * random.nextFloat();
            };
            half[0] = 0.5f * len;
            half[1] = 0.5f * switch (kind) {
                case CHUNK -> size;
                case BOARD -> 0.15f;
                case PANE -> (0.5f + 0.5f * random.nextFloat()) * len;
                case LEAF -> 0.8f * len;
            };
            half[2] = 0.5f * switch (kind) {
                case CHUNK -> size;
                case BOARD -> (float) BOARD_THICK;
                case PANE -> 0.01f;
                case LEAF -> 0.01f;
            };
            // particle position is the bottom of its bounds; bounds use the largest half-size (body tumbles)
            float box = 2 * Math.max(half[0], Math.max(half[1], half[2]));
            setPos(x, y - 0.5 * box, z);
            xo = this.x;
            yo = this.y;
            zo = this.z;
            setSize(box, box);
            xd = vx;
            yd = vy;
            zd = vz;
            gravity = 0;
            friction = 1;
            lifetime = life;
            drag = drag(terminalMps > 0 ? terminalMps : terminal(kind, Shielding.material(level, from, state), len));
            lift = (float) (LIFT_MIN + ((edge ? LIFT_EDGE : LIFT_MAX) - LIFT_MIN) * random.nextDouble());
            TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getTexture(state, level, from);
            // face uses a texture patch the size of the body (16 pixels per block); glass uses the edge: window is transparent except the frame
            // and edge highlights, and the particle does not draw transparent pixels
            float span = Mth.clamp(2 * Math.max(half[0], half[1]), 0.125f, 1);
            float ou = kind == Kind.PANE ? 0 : random.nextFloat() * (1 - span), ov = kind == Kind.PANE ? 0 : random.nextFloat() * (1 - span);
            u0 = sprite.getU(ou);
            u1 = sprite.getU(ou + span);
            v0 = sprite.getV(ov);
            v1 = sprite.getV(ov + span);
            if (IClientBlockExtensions.of(state).areBreakingParticlesTinted(state, level, from)) {
                int c = Minecraft.getInstance().getBlockColors().getColor(state, level, from, 0);
                rCol = (c >> 16 & 0xFF) / 255f;
                gCol = (c >> 8 & 0xFF) / 255f;
                bCol = (c & 0xFF) / 255f;
            }
            Vector3f axis = new Vector3f(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat() - 0.5f);
            if (axis.lengthSquared() < 1e-4f) axis.set(0, 1, 0);
            axis.normalize();
            ax = axis.x;
            ay = axis.y;
            az = axis.z;
            angle = angleO = random.nextFloat() * Mth.TWO_PI;
            spin = 0.1f * (random.nextBoolean() ? 1 : -1);
        }

        double x() {
            return x;
        }

        double y() {
            return y;
        }

        double z() {
            return z;
        }

        @Override
        public void tick() {
            ticked = Fragments.tick;
            if (delay > 0) {
                delay--;
                return;
            }
            if (!out) {
                // bounds use the largest body size: a long plank from a breach near the floor intersects the floor, and vanilla does not
                // push a particle out of a block it already occupies — the plank would sink into the floor; raise it to free space (ruins
                // appear by launch time, so check on the first flight tick rather than at birth)
                // only the floor below the centre: wall-breach bounds also touch neighbouring side blocks; checking them
                // would raise the piece by a block, into the wall above the breach
                out = true;
                for (int i = 0; i < 4 && !level.noCollision(new net.minecraft.world.phys.AABB(x - 0.05, getBoundingBox().minY, z - 0.05, x + 0.05,
                        getBoundingBox().minY + 0.5 * bbHeight, z + 0.05)); i++) {
                    setPos(x, y + 0.25, z);
                    yo = y;
                }
            }
            if (onGround || stuck) {
                // settled or blocked: after touching ground (or ceiling/canopy from below — then onGround stays unset), vanilla no longer
                // moves the particle (stoppedByCollision) — tumbling fades, it rests then crumbles
                xd = yd = zd = 0;
                super.tick();
                spin *= 0.5f;
                angleO = angle;
                angle += spin;
                if (++rest >= REST) lifetime = Math.min(lifetime, age + SHRINK);
                return;
            }
            // wind: horizontal from the detachment site, upward as its own fraction (dust-layer eddies)
            wind.at(x, y + bbHeight * 0.5, z, AIR_NOW);
            VEL[0] = xd;
            VEL[1] = yd;
            VEL[2] = zd;
            double rel = step(VEL, AIR_NOW[0], lift * Math.max(0, AIR_NOW[1]), AIR_NOW[2], drag);
            xd = VEL[0];
            yd = VEL[1];
            zd = VEL[2];
            double x0 = x, y0 = y, z0 = z;
            super.tick();
            // velocity exists, but position is unchanged: blocked by a block (vanilla no longer moves it)
            stuck = x == x0 && y == y0 && z == z0 && xd * xd + yd * yd + zd * zd > 1e-8;
            angleO = angle;
            // tumbles faster with faster airflow around it
            spin = Math.copySign((float) Math.min(0.8, 0.1 + 0.25 * rel), spin);
            angle += spin;
        }

        @Override
        public void render(VertexConsumer buffer, Camera camera, float partial) {
            if (delay > 0) return;
            float shrink = Mth.clamp((lifetime - age - partial) / SHRINK, 0, 1);
            if (shrink <= 0) return;
            Vec3 cam = camera.getPosition();
            float box = bbHeight * 0.5f;
            float px = (float) (Mth.lerp(partial, xo, x) - cam.x), py = (float) (Mth.lerp(partial, yo, y) - cam.y + box),
                    pz = (float) (Mth.lerp(partial, zo, z) - cam.z);
            TURN.identity().rotateAxis(Mth.lerp(partial, angleO, angle), ax, ay, az);
            for (int i = 0; i < 3; i++) TURN.transform(AXES[i].set(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0));
            int light = getLightColor(partial);
            for (int[] f : FACES) {
                Vector3f n = AXES[f[0]], a = AXES[f[1]], b = AXES[f[2]];
                float hn = half[f[0]] * shrink, ha = half[f[1]] * shrink, hb = half[f[2]] * shrink;
                float s = f[3];
                float nx = n.x * s, ny = n.y * s, nz = n.z * s;
                // face shading like blocks: lighter top, darker bottom, sides between
                float shade = nx * nx * 0.6f + nz * nz * 0.8f + ny * ny * (ny > 0 ? 1 : 0.5f);
                float r = rCol * shade, g = gCol * shade, bl = bCol * shade;
                float cx = px + nx * hn, cy = py + ny * hn, cz = pz + nz * hn;
                float ax = a.x * ha, ay = a.y * ha, az = a.z * ha, bx = b.x * hb, by = b.y * hb, bz = b.z * hb;
                vertex(buffer, cx - ax - bx, cy - ay - by, cz - az - bz, u0, v1, r, g, bl, light);
                vertex(buffer, cx + ax - bx, cy + ay - by, cz + az - bz, u1, v1, r, g, bl, light);
                vertex(buffer, cx + ax + bx, cy + ay + by, cz + az + bz, u1, v0, r, g, bl, light);
                vertex(buffer, cx - ax + bx, cy - ay + by, cz - az + bz, u0, v0, r, g, bl, light);
            }
        }

        private static void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, float r, float g, float b, int light) {
            buffer.addVertex(x, y, z).setUv(u, v).setColor(r, g, b, 1).setLight(light);
        }

        @Override
        protected int getLightColor(float partial) {
            BlockPos pos = BlockPos.containing(x, y + bbHeight * 0.5, z);
            return level.isLoaded(pos) ? LevelRenderer.getLightColor(level, pos) : 0;
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.TERRAIN_SHEET;
        }
    }
}
