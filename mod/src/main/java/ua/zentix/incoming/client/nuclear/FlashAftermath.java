package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.Effects;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.fx.smoke.Puffs;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.world.ThermalShadow;
import ua.zentix.incoming.util.Palettes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the flash does to its surroundings in the first seconds, before the wave (live only — never spawned from a replay seek):
 * <ul>
 *     <li>EMP — grid streetlights and lamps (those affected by blackout, {@link GridLights}) in districts blacked out by the strike (radius
 *     from the server, from grid settings) short out: blue arc, jagged discharge and sparks, staggered, mostly at once, until wave
 *     arrival at the lamp and no later than {@link #ARC_WINDOW} ticks; the arc crackles — 2–4 discharges spaced 1–3 ticks, with some lamps
 *     repeating later. The client finds them in local chunks through section palettes, up to {@link #SECTIONS_PER_TICK} sections per tick,
 *     nearest first; arcs capped at {@link #MAX_ARCS}. Substations are excluded: the strike disables them on the server
 *     ({@code Blackouts.nuke}), and each has its own failure arc ({@code GridEffects.failure}); a second would be
 *     redundant;</li>
 *     <li>smoke wisps from flash-heated surfaces: roofs, grassy ground, foliage and wooden walls facing the ball emit pyrolysis
 *     smoke until the wave reaches them ({@link Smoke}). Locations are points in front of the camera (out to {@link #SMOKE_RANGE}
 *     blocks); light follows char ({@link FlashChar}): pulse × angle × shadow from client heightmaps; smoke uses cloud-model emissions
 *     ({@link Puffs}).</li>
 * </ul>
 */
public final class FlashAftermath {
    /** Maximum EMP arcs (nearest to the camera). */
    static final int MAX_ARCS = 48;
    /** Sections inspected per tick for lamps. */
    static final int SECTIONS_PER_TICK = 48;
    /** Lamps no longer short out after this many ticks from the flash. */
    static final int ARC_WINDOW = 50;
    /** Bluish-white arc light and blue edge — as for the substation ({@code GridEffects}). */
    private static final int ARC = 0xDCE8FF, ARC_EDGE = 0x5A7CFF;
    /** Jagged discharge segments and points per segment. */
    private static final int BOLT_SEGMENTS = 5, BOLT_DOTS = 3;
    /** Camera search range for smoke wisps, blocks. */
    static final double SMOKE_RANGE = 128;
    /** Median and spread of light exposure that makes a surface smoke, cal/cm². */
    static final double SMOKE_Q = 5, SMOKE_BETA = 0.6;

    /** Arcs in the latest detonation and pyrolysis smoke emissions (client scenario line). */
    private static volatile int lastArcs = -1;
    private static volatile int puffs;

    private FlashAftermath() {}

    /** {@code SCENARIO} line: EMP arcs found for the latest detonation and smoke-wisp locations. */
    public static String describe() {
        return "emp arcs " + lastArcs + ", scorch smoke " + puffs + " puffs";
    }

    /**
     * Live detonation: EMP and smoke wisps. {@code blackout} is the radius in which the server blacks out the grid ({@code Blackouts.nukeRadius}); 0 —
     * no blackout (disabled in settings), so lamps do not short out: otherwise they would "burn out" yet stay lit.
     */
    static void detonation(Detonation d, double blackout) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        lastArcs = 0;
        puffs = 0;
        if (blackout > 0) Effects.add(new Emp(d, blackout, level));
        Effects.add(new Smoke(d));
    }

    // ---------------------------------------------------------------- EMP

    /**
     * Search sections near the camera for lamps, nearest first, and arcs: each found lamp gets its discharges immediately. Searching the
     * entire render distance takes longer than the wave (a global section palette always answers "maybe" to "is there a lamp"), so
     * a lamp found after its wave has passed or {@link #ARC_WINDOW} has elapsed does not short out.
     */
    private static final class Emp implements Effects.Effect {
        private final Detonation d;
        private final RandomSource rnd;
        private final List<long[]> sections = new ArrayList<>();
        private final List<Arc> arcs = new ArrayList<>();
        private int next;

        Emp(Detonation d, double radius, ClientLevel level) {
            this.d = d;
            this.rnd = RandomSource.create(d.seed() ^ 0x5EED);
            // sections near the camera in districts blacked out by the server (one district per chunk, as for blackout): nearest first
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            int view = Minecraft.getInstance().options.getEffectiveRenderDistance();
            int ccx = Mth.floor(cam.x) >> 4, ccz = Mth.floor(cam.z) >> 4;
            for (int cz = ccz - view; cz <= ccz + view; cz++) {
                for (int cx = ccx - view; cx <= ccx + view; cx++) {
                    if (!Outage.covers(d.burst().x, d.burst().z, radius, Districts.of(cx, cz))) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    LevelChunkSection[] secs = chunk.getSections();
                    for (int i = 0; i < secs.length; i++) {
                        if (secs[i].hasOnlyAir() || !secs[i].getStates().maybeHas(FlashAftermath::lit)) continue;
                        sections.add(new long[]{cx, i, cz});
                    }
                }
            }
            sections.sort(Comparator.comparingDouble(s -> Math.hypot((s[0] << 4) + 8 - cam.x, (s[2] << 4) + 8 - cam.z)));
        }

        @Override
        public boolean tick(ClientLevel level, int t) {
            boolean searching = next < sections.size() && arcs.size() < MAX_ARCS && t < ARC_WINDOW;
            if (searching) scan(level, t);
            boolean left = searching;
            for (Arc a : arcs) {
                for (int i = 0; i < a.strikes.length; i++) {
                    // lamp still present (lit or already blacked out — that is what disabled it), not demolished
                    if (a.strikes[i] == t && lamp(level.getBlockState(a.pos))) arc(level, a, a.strength[i]);
                    if (a.strikes[i] > t) left = true;
                }
            }
            return left;
        }

        /** Sections per tick: arcs at their lit lamps, nearest to the camera first, up to {@link #MAX_ARCS} total. */
        private void scan(ClientLevel level, int t) {
            List<BlockPos> found = new ArrayList<>();
            for (int n = 0; n < SECTIONS_PER_TICK && next < sections.size(); n++) {
                long[] s = sections.get(next++);
                LevelChunk chunk = level.getChunkSource().getChunkNow((int) s[0], (int) s[2]);
                if (chunk == null) continue;
                LevelChunkSection sec = chunk.getSections()[(int) s[1]];
                // exact palette count is cheaper than scanning positions: a lampless section is skipped
                if (!Palettes.contains(sec.getStates(), FlashAftermath::lit)) continue;
                int y0 = chunk.getMinBuildHeight() + ((int) s[1] << 4);
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (lit(sec.getBlockState(x, y, z))) found.add(new BlockPos(((int) s[0] << 4) + x, y0 + y, ((int) s[2] << 4) + z));
                        }
                    }
                }
            }
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            found.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(cam)));
            for (BlockPos pos : found) {
                if (arcs.size() >= MAX_ARCS) break;
                Arc a = schedule(pos, t);
                if (a != null) arcs.add(a);
            }
            lastArcs = arcs.size();
        }

        /**
         * Discharges of a lamp found at tick {@code t}. Shorts are staggered, mostly at once, until wave arrival at the lamp
         * (afterward it is in dust and debris) and no later than {@link #ARC_WINDOW}; at small strike scale the wave arrives in ticks, so
         * the lamp gets fewer discharges or none. The arc does not stop immediately: discharge repeats until the circuit
         * opens (2–4 times spaced 1–3 ticks); some lamps short again later, more weakly. Null — already too late.
         */
        @Nullable
        private Arc schedule(BlockPos pos, int t) {
            int wave = (int) d.arrivalTicks(Math.hypot(pos.getX() + 0.5 - d.burst().x, pos.getZ() + 0.5 - d.burst().z));
            int last = Math.min(wave, ARC_WINDOW);
            if (t >= last) return null;
            float r = rnd.nextFloat();
            int at = t + (int) (r * r * (last - t));
            int again = rnd.nextFloat() < 0.4f ? at + 8 + rnd.nextInt(16) : -1;
            int first = 2 + rnd.nextInt(3), second = again < 0 ? 0 : 1 + rnd.nextInt(2);
            IntArrayList strikes = new IntArrayList(first + second);
            FloatArrayList strength = new FloatArrayList(first + second);
            for (int i = 0, when = at; i < first + second; i++) {
                boolean late = i >= first;
                when = i == 0 ? at : i == first ? again : when + 1 + rnd.nextInt(3);
                float s = (late ? 0.6f : 1) * (i == 0 || i == first ? 1 : 0.55f + 0.35f * rnd.nextFloat());
                if (when >= wave) continue;
                strikes.add(when);
                strength.add(s);
            }
            return new Arc(pos, strikes.toIntArray(), strength.toFloatArray());
        }

        private void arc(ClientLevel level, Arc a, float strength) {
            Vec3 at = Vec3.atCenterOf(a.pos).add(rnd.nextGaussian() * 0.15, 0.2 + rnd.nextGaussian() * 0.1, rnd.nextGaussian() * 0.15);
            // smooth glow: nearest-pixel sampling breaks a large flash into texture-pixel squares
            Fx.flash().smooth().color(ARC, ARC_EDGE).size(1.4f * strength, 2.6f * strength).life(2 + rnd.nextInt(2)).spawn(level, at);
            Fx.flash().smooth().color(0xFFFFFF, ARC).size(0.5f, 0.9f).life(2).spawn(level, at);
            // the discharge itself is a jagged line of star-shaped sparks out and down from the lamp: a light patch on ground already lit by the flash
            // is barely visible by day, as are soft dots; star-shaped sparks remain visible. Directly down the
            // pole, the lamp and pole would hide the jagged line
            double dir = rnd.nextDouble() * Math.PI * 2, ox = Math.cos(dir) * 0.24, oz = Math.sin(dir) * 0.24;
            Vec3 p = at;
            for (int seg = 0; seg < BOLT_SEGMENTS; seg++) {
                Vec3 q = p.add(ox + rnd.nextGaussian() * 0.09, -0.14 + rnd.nextGaussian() * 0.09, oz + rnd.nextGaussian() * 0.09);
                for (int k = 0; k < BOLT_DOTS; k++) {
                    Fx.spark().color(0xFFFFFF, 0x9FB4FF).size(0.11f * strength, 0.07f).life(2).spawn(level, p.lerp(q, (double) k / BOLT_DOTS));
                }
                p = q;
            }
            int n = (int) (6 * strength);
            for (int i = 0; i < n; i++) {
                Fx.spark().color(0xFFFFFF, 0x80A0FF).vel(rnd.nextGaussian() * 0.18, 0.05 + rnd.nextDouble() * 0.25, rnd.nextGaussian() * 0.18)
                        .life(8 + rnd.nextInt(14)).gravity(0.05f).spawn(level, at);
            }
        }
    }

    /** Grid lamp: arc discharge ticks and strengths. */
    private static final class Arc {
        final BlockPos pos;
        final int[] strikes;
        final float[] strength;

        Arc(BlockPos pos, int[] strikes, float[] strength) {
            this.pos = pos;
            this.strikes = strikes;
            this.strength = strength;
        }
    }

    /** Lit visible grid lamp: EMP shorts it out. */
    private static boolean lit(BlockState st) {
        return GridLights.isLit(st) && st.getRenderShape() == RenderShape.MODEL;
    }

    /** Visible grid lamp, lit or blacked out (its twin): still present, not demolished. */
    private static boolean lamp(BlockState st) {
        return (GridLights.isLit(st) || GridLights.isUnlit(st)) && st.getRenderShape() == RenderShape.MODEL;
    }

    // ---------------------------------------------------------------- smoke wisps

    /**
     * Flash smoke wisps come from pyrolysis, not combustion: light above the charring threshold ({@link #SMOKE_Q}, spread {@link #SMOKE_BETA}
     * per {@link Fragility}) heats wood, foliage and grass surfaces, releasing volatiles — whitish tar smoke
     * seen rising from houses in Nevada tests before wave arrival (Glasstone and Dolan 1977, §7.40–7.45). No flame: the server
     * places fire (flash layer). Probes — {@link #SMOKE_PROBES} equal-area locations in a sector ahead of the camera out to
     * {@link #SMOKE_RANGE} blocks; smoking locations emit through the cloud model ({@link Puffs}):
     * <ul>
     * <li>volatiles — α·(q − q₀)·S / L<sub>g</sub>: absorption α = {@value #ABSORB} (estimate: wood and foliage 0.6–0.9), light above
     * threshold q₀ = {@link #SMOKE_Q} cal/cm², S — area represented by the probe, heat of wood gasification
     * L<sub>g</sub> = {@value #GASIFY} J/kg (Tewarson, SFPE Handbook, table 3-4.x: 1.8 MJ/kg for Douglas fir; ⚠ from memory);</li>
     * <li>smoke from them — {@value #SMOKE_YIELD} kg per kg (estimate: smoldering wood aerosol yield 0.01–0.1), extinction
     * {@value #SMOKE_K} m²/kg (~4.4 m²/g for smoldering smoke, Mulholland, SFPE Handbook; ⚠ from memory), gray-brown color;</li>
     * <li>heat into air — {@value #TO_AIR} of absorbed heat (estimate), gas ~{@value #SMOKE_K_GAS} K (wood pyrolysis 500–700 K).</li>
     * </ul>
     * A wisp remains visible until the wave reaches the location (its dust wall sweeps it away).
     */
    static final int SMOKE_PROBES = 384;
    /** Probes per tick: the full distribution is covered in the first {@link #SMOKE_PROBES} / this many flash ticks. */
    static final int SMOKE_PER_TICK = 24;
    static final double ABSORB = 0.7, GASIFY = 1.8e6, SMOKE_YIELD = 0.05, SMOKE_K = 4.4e3, TO_AIR = 0.1, SMOKE_K_GAS = 600;
    /** Joules per square meter in one calorie per square centimeter. */
    static final double CAL_CM2 = 41_868;

    /** Probes ahead of the camera: emit pyrolysis smoke where light heated a surface above threshold and the wave has not arrived. */
    private static final class Smoke implements Effects.Effect {
        private final Detonation d;
        private final RandomSource rnd;
        private final double reach;
        private Puffs smoke;
        private int probed;

        Smoke(Detonation d) {
            this.d = d;
            this.rnd = RandomSource.create(d.seed() ^ 0x5307E);
            double lo = 0, hi = Math.max(1, d.ruinRadius());
            for (int i = 0; i < 30; i++) {
                double r = (lo + hi) / 2;
                if (Fragility.possible(d.fluence(d.burst().add(r, 0, 0)), SMOKE_Q, SMOKE_BETA)) lo = r;
                else hi = r;
            }
            this.reach = lo;
        }

        @Override
        public boolean tick(ClientLevel level, int t) {
            if (reach <= 0 || probed >= SMOKE_PROBES) {
                if (smoke != null) smoke.close();
                return false;
            }
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 cam = camera.getPosition();
            if (smoke == null) {
                smoke = new Puffs(FarBlasts.air(level, cam), 0.71f, 0.68f, 0.65f, d.seed());
                SmokeGrid.add(smoke);
            }
            double yaw = Math.toRadians(camera.getYRot()), sector = Math.toRadians(120), r0 = 4, r1 = r0 + SMOKE_RANGE;
            // probe represents an equal share of sector area
            double area = 0.5 * sector * (r1 * r1 - r0 * r0) / SMOKE_PROBES;
            double now = SmokeGrid.seconds(0);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int k = 0; k < SMOKE_PER_TICK && probed < SMOKE_PROBES; k++, probed++) {
                double a = yaw + (rnd.nextDouble() - 0.5) * sector, r = Math.sqrt(r0 * r0 + rnd.nextDouble() * (r1 * r1 - r0 * r0));
                int x = Mth.floor(cam.x - Math.sin(a) * r), z = Mth.floor(cam.z + Math.cos(a) * r);
                double dist = Math.hypot(x + 0.5 - d.burst().x, z + 0.5 - d.burst().z);
                double wave = d.arrivalTicks(dist);
                if (dist > reach || t >= wave) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (top <= level.getMinBuildHeight()) continue;
                Direction face = Direction.UP;
                int y = top;
                // half the probes: wall facing the ball under a roof
                if (rnd.nextBoolean()) {
                    face = Direction.getNearest(d.burst().x - x - 0.5, 0, d.burst().z - z - 0.5);
                    y = top - rnd.nextInt(8);
                }
                BlockState st = level.getBlockState(m.set(x, y, z));
                if (!burns(st, face)) continue;
                BlockState ahead = level.getBlockState(m.move(face));
                if (!ahead.isAir() && !ahead.canBeReplaced()) continue;
                double cos = FlashChar.incidence(d.burst(), x, y, z, face);
                if (cos <= 0) continue;
                Vec3 at = FlashChar.front(x, y, z, face);
                double q = d.fluence(at) * cos;
                if (!Fragility.possible(q, SMOKE_Q, SMOKE_BETA) || !ThermalShadow.visible(level, d.burst(), at)) continue;
                if (!Fragility.hit(q, SMOKE_Q, SMOKE_BETA, rnd.nextGaussian())) continue;
                double absorbed = ABSORB * q * CAL_CM2 * area, volatiles = ABSORB * Math.max(0, q - SMOKE_Q) * CAL_CM2 * area / GASIFY;
                smoke.add(at.x, at.y, at.z, now, TO_AIR * absorbed, SMOKE_K_GAS, 0.5 * Math.sqrt(area), 0, SMOKE_YIELD * volatiles, SMOKE_K,
                        now + (wave - t) / 20);
                puffs++;
            }
            return true;
        }

        private static boolean burns(BlockState st, Direction face) {
            if (st.isAir()) return false;
            if (FlashChar.material(st) == FlashChar.CHARS || st.is(BlockTags.LEAVES)) return true;
            return face == Direction.UP && (st.is(Blocks.GRASS_BLOCK) || st.is(BlockTags.REPLACEABLE_BY_TREES) || st.is(Blocks.HAY_BLOCK));
        }
    }
}
