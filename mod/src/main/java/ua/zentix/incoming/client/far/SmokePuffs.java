package ua.zentix.incoming.client.far;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.plume.Smoke;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

/**
 * Client clouds as squares of the effects layer, at any distance, near and far alike: a model puff (the Gaussian of
 * substance mass of the substance from the source {@link SmokeGrid.Source}: a blast cloud, a launch cloud, fire smoke)
 * — a cluster of baked puffs ({@link FxAtlas#puff6}: gas simulation in Blender, six-sided lighting, motion vectors
 * between frames).
 * <ul>
 * <li><b>Size.</b> The visible edge of the Gaussian — where the thickness falls to {@link #EDGE_TAU}: r =
 * σ·√(2·ln(τ₀/{@value #EDGE_TAU})) (τ₀ — thickness across the middle, Σκ'm/(2πσhσv)); for a dense cloud this is ~3.7σ,
 * for a thin one — nearer the middle. The puff's square — this edge (the puff's map is cropped to the substance's edge,
 * {@code tools/bake_fx/pack.py}).</li>
 * <li><b>Density.</b> The middle's opacity is 1 − e^(−τ₀); a Gaussian large on screen — a cluster of up to
 * {@value #CLUSTER} puffs around the middle (the larger, the more of them and the smaller they are); several overlap in
 * the middle: each takes its share, so that together they give the same transmittance.</li>
 * <li><b>Color</b> — reflection of the thick layer of a mix of soot, dust and droplets (two streams, {@link #thick})
 * under sky light as the lightmap lights blocks under open sky ({@link FarView#sky}); light from the sides (sun, moon,
 * sky, the frame's fireballs) — from the frame's maps in the shader ({@code fx.vsh}, {@code fx.fsh}).</li>
 * <li><b>Frame</b> — the one where the simulation has grown as many times as the source's substance since the emission
 * ({@link SmokeGrid.Source#puffGrowth}, {@link FxAtlas#puffFrame}); each puff of a cluster has its own offset.</li>
 * </ul>
 * Glowing gas (hotter than the Draper point) at the source, which the fire body draws
 * ({@link SmokeGrid.Source#drawsGlow}: a blast cloud as a fireball), is not drawn here: smoke shows through in the same
 * share as the body melts. A cloud segment ({@link SmokeGrid.Sink#segment}) — puffs along it every ~σ; a piece of tube
 * ({@link SmokeGrid.Sink#tube}: the fire's trail) — like a projectile's trail ({@link FarFlightView#line},
 * {@link #tube}).
 */
final class SmokePuffs {
    /**
     * Where puffs, veils and ribbons go as squares (frame of the layer — {@link FarSprites}; checks — their own sink).
     */
    interface Out {
        void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                   float b, float a);

        void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, double angle, FxAtlas.Sprite sprite, float mix, float shade,
                       float r, float g, float b, float a);

        /** Veil of the substance at the eye on screen ({@link Veil}) with world light `light` (packed). */
        void veil(Veil veil, int light);

        boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                       float alphaB, int lightB, float r, float g, float b, float[] joint);
    }

    /**
     * Numbers per waiting puff: place, σh, σv, soot, dust, droplets, dust albedo (3), number, source, smoke share
     * (opacity of the puff that is not the fire body, {@link #smokeShare}), whether this is a segment puff (1 — its own
     * frame, reflection and wander, as with tube puffs), the puff's look ({@link SmokeGrid.Look}, number) and its frame
     * (for a look that is not {@code SMOKE}).
     */
    static final int FIELDS = 17;
    /**
     * Puffs of one source whose centers are closer than this share of the smaller σ (horizontally and vertically) — one
     * Gaussian with the same mass and moments: their sum from one Gaussian barely differs (for two equal ones at
     * distance σ — 1.3 % of the peak), and they are drawn two to ten times fewer squares. So puff models keep the puff
     * count down (SCIPUFF: Sykes et al., “PC-SCIPUFF Version 1.2PD Technical Documentation”, ARAP Report 718, 1998,
     * merging of overlapping puffs). The blast wave's dust — 32 puffs (4 rings × 8 sectors), and when the spreading is
     * wider than the distance between them, they lie on top of each other: in the smoke of a raid of 16 strikes the
     * frame was flooded ~17 screens (measured with {@code SmokeFillTest}).
     */
    static final double MERGE = 1;

    /**
     * Merge close puffs ({@link #MERGE}) from {@code start} to {@code n} into {@code held} ({@link #FIELDS} numbers per
     * puff): weight — attenuation (what is visible), center — average, σ² — average σ² plus the spread of centers
     * (horizontally — half: σh shared by both axes), masses — sums; the merged one is marked {@code gone}. Only
     * substance of one albedo.
     */
    static void merge(double[] held, int start, int n, boolean[] gone, double sootK, double dropK) {
        merge(held, start, n, gone, sootK, dropK, MERGE);
    }

    /**
     * The same, with a σ share {@code reach}; the merged puff's look (puff frame, grain) — that of the one with more
     * substance.
     */
    static void merge(double[] held, int start, int n, boolean[] gone, double sootK, double dropK, double reach) {
        for (int i = start; i < n; i++) {
            if (gone[i]) continue;
            int a = i * FIELDS;
            boolean again = true;
            while (again) {
                again = false;
                for (int j = i + 1; j < n; j++) {
                    if (gone[j]) continue;
                    int b = j * FIELDS;
                    if (held[a + 8] != held[b + 8] || held[a + 9] != held[b + 9] || held[a + 10] != held[b + 10]) continue;
                    // puffs of different looks (smoke, emission curtain, crushing dust) — different frames: they do not
                    // merge into one Gaussian
                    if (held[a + 15] != held[b + 15]) continue;
                    // puffs of one segment are laid out along it deliberately: merged, they would gather into one
                    // Gaussian, and a flat row (launch dust: step by the smaller σ, neighbors closer than σh) would
                    // lose its length
                    if (held[a + 14] > 0.5 && held[b + 14] > 0.5 && (long) held[a + 11] / SEGMENT_CLUBS == (long) held[b + 11] / SEGMENT_CLUBS) continue;
                    double dx = held[b] - held[a], dy = held[b + 1] - held[a + 1], dz = held[b + 2] - held[a + 2];
                    double h = reach * Math.min(held[a + 3], held[b + 3]), v = reach * Math.min(held[a + 4], held[b + 4]);
                    if (dx * dx + dz * dz > h * h || Math.abs(dy) > v) continue;
                    absorb(held, a, b, sootK, dropK);
                    gone[j] = true;
                    again = true;
                }
            }
        }
    }

    /**
     * Puff with b ({@link #FIELDS} numbers) — into the puff with a: weight — attenuation (what is visible), center —
     * average, σ² — average σ² plus the spread of centers (horizontally — half: σh shared by both axes), masses — sums;
     * for dust of different color albedo — the average weighted by its attenuation (this is how the mix's ω' is
     * computed — exactly), mass — by the attenuation of the mix. Smoke share — average by attenuation; look (puff
     * frame, grain, source) — that of the one with more substance.
     */
    static void absorb(double[] held, int a, int b, double sootK, double dropK) {
        boolean same = held[a + 8] == held[b + 8] && held[a + 9] == held[b + 9] && held[a + 10] == held[b + 10];
        double dk = SmokeGrid.dustK((float) held[a + 8], (float) held[a + 9], (float) held[a + 10]), ka = dk * held[a + 6];
        double kb = (same ? dk : SmokeGrid.dustK((float) held[b + 8], (float) held[b + 9], (float) held[b + 10])) * held[b + 6];
        double wa = sootK * held[a + 5] + ka + dropK * held[a + 7], wb = sootK * held[b + 5] + kb + dropK * held[b + 7];
        double w = wa + wb;
        if (!(w > 0)) return;
        double fa = wa / w, fb = wb / w;
        // smoke share — average by what is visible (attenuation)
        held[a + 13] = fa * held[a + 13] + fb * held[b + 13];
        // source look (puff frame, grain, visibility, frame of its own look) — that of the one with more substance
        if (wb > wa) {
            held[a + 11] = held[b + 11];
            held[a + 12] = held[b + 12];
            held[a + 14] = held[b + 14];
            held[a + 16] = held[b + 16];
        }
        double dx = held[b] - held[a], dy = held[b + 1] - held[a + 1], dz = held[b + 2] - held[a + 2];
        double mx = held[a] + fb * dx, my = held[a + 1] + fb * dy, mz = held[a + 2] + fb * dz;
        // second moment around the new center
        double ax = held[a] - mx, ay = held[a + 1] - my, az = held[a + 2] - mz, bx = held[b] - mx, by = held[b + 1] - my, bz = held[b + 2] - mz;
        double sh2 = fa * (held[a + 3] * held[a + 3] + 0.5 * (ax * ax + az * az)) + fb * (held[b + 3] * held[b + 3] + 0.5 * (bx * bx + bz * bz));
        double sv2 = fa * (held[a + 4] * held[a + 4] + ay * ay) + fb * (held[b + 4] * held[b + 4] + by * by);
        held[a] = mx;
        held[a + 1] = my;
        held[a + 2] = mz;
        held[a + 3] = Math.sqrt(sh2);
        held[a + 4] = Math.sqrt(sv2);
        held[a + 5] += held[b + 5];
        held[a + 7] += held[b + 7];
        double kd = ka + kb;
        if (same || !(kd > 0)) held[a + 6] += held[b + 6];
        else {
            for (int c = 8; c <= 10; c++) held[a + c] = (float) ((ka * held[a + c] + kb * held[b + c]) / kd);
            held[a + 6] = kd / SmokeGrid.dustK((float) held[a + 8], (float) held[a + 9], (float) held[a + 10]);
        }
    }

    /**
     * How many screens the puff squares and tube spots can cover per frame (the sum of their screen areas, with
     * overlap) for any number of strikes: what is seen through smoke is the thickness along the view ray, Στ, not the
     * number of layers. Over budget, clusters take fewer puffs (larger) with the same middle opacity; not even one puff
     * per Gaussian (the floor) is enough — a large one on screen melts into the veil, and puffs lying on top of each
     * other and spots of different sources merge ({@link Draw#flush}). Complaint 23.10: in the smoke of a raid, 8 fps;
     * bench 25.10, a raid of 100 Shaheds: the floor was 41/104/175 screens outside/from the edge/inside (the budget did
     * not work), ~0.3 ms of GPU time per screen (RTX 4060, 2560×1600). ⚠ The number is an estimate: 16 screens ≈ 4.8
     * ms; a raid of 16 strikes lands in them without merging ({@code SmokeFillTest}), from 100 — with it
     * ({@code SmokeRaidTest}). At 8, a raid of 40 in the sandbox from the edge and from inside already lost the dark
     * spreading mass and the trails in a flat haze of veil (frames of PR #460).
     */
    static final double FILL_BUDGET = 16;
    /** Budget the frame is built to ({@link #FILL_BUDGET}; checks compare with the frame without a budget). */
    static double budget = FILL_BUDGET;
    /**
     * Cross-source merge levels ({@link Draw#mergeLevel}): a level-k cell spans {@link #LOD_CELL}·2^k puff edges
     * (two edges at the last level). Merging farther would turn separate clouds into one spanning the entire raid.
     */
    static final int LEVELS = 3;
    static final double LOD_CELL = 0.5;
    /**
     * The merge level of the previous frame is kept while the floor one level lower is not less than this share of the
     * budget.
     */
    static final double HYSTERESIS = 0.8;
    /**
     * Quads selected by {@code Draw.fit} fade into the veil from this fraction of cumulative budget fill; ribbons fade
     * out. Presentation: a 15% band keeps the transition continuous without turning an under-budget water salvo (floor
     * 13.16) into haze.
     */
    static final double FIT_FADE = 0.85;
    /** Groups in a tube-spot merge cell: spots of one tube — in different groups. */
    static final int SLOTS = 8;
    /**
     * Veil Gaussians — no more than (thickness along each ray from each of the 13×13 vertices through each: ~0.3 ms of
     * CPU for 48); more — nearby ones merge.
     */
    static final int VEIL_MAX = 48;
    /** Softness of the veil to the world — no less than, blocks ({@link Veil#soft}). */
    static final double VEIL_SOFT_MIN = 1;
    /**
     * Numbers per waiting tube spot: center from the eye (3), σ, thickness of puffs and of the whole tube across the
     * axis, piece of axis, color (3), shadow level, puff frame, visible share, puffs in the cluster, whether its own
     * frame, axis (3), look, substance, haze of air before it.
     */
    static final int PLACE_FIELDS = 21, P_CONTEXT = 18, P_MATERIAL = 19, P_AIR = 20;
    /** Numbers per waiting ribbon segment ({@code Draw.holdRibbon}). */
    static final int RIBBON_FIELDS = 21;
    /** Substances of spots in the frame — no more than (more — spots do not merge). */
    static final int MATERIALS = 32;
    /**
     * Puffs of clouds in the frame — no more than (plan, merge and squares — CPU per each): a raid of 100 strikes —
     * ~800 after merging inside sources; beyond that they merge before the plan ({@code Draw.thin}).
     */
    static final int BLOBS = 2000;
    /**
     * Tube spots in the frame — no more than (plan, merge and squares — CPU per each): beyond that the segment has no
     * spots in this frame ({@link #tube}); on average they are kept at {@code FarFlightView.CLUB_BUDGET} by a step of
     * spots from past frames, and this is in each frame, and in the first frame of a raid too.
     */
    static final int PLACES = FarFlightView.CLUB_BUDGET;
    /** Cloud tube numbers in the frame start from this (for trails — the projectile's number). */
    static final long TUBES = 1L << 40;

    /**
     * Screen share under a square with half-sizes {@code radius} across and {@code tall} up (m, facing the camera) with
     * a center (px, py, pz) from the eye, cropped by the frame: the frame is 16:9 with half-diagonal
     * {@link FarView#edge} (⚠ aspect ratio — estimate; for the budget).
     */
    static double cover(FarView view, double px, double py, double pz, double radius, double tall) {
        org.joml.Vector3f f = view.forward(), l = view.left(), u = view.up();
        double depth = px * f.x + py * f.y + pz * f.z;
        double diag = Math.tan(Math.min(view.edge(), 1.5)), w = diag * 16 / Math.sqrt(337), h = diag * 9 / Math.sqrt(337);
        // behind the eye — not on screen; at the eye plane — projection from depth to centimeters (cropped by the
        // frame)
        if (depth <= 0) return 0;
        double r = radius / Math.max(depth, 0.05), t = tall / Math.max(depth, 0.05);
        double cu = (px * l.x + py * l.y + pz * l.z) / depth, cv = (px * u.x + py * u.y + pz * u.z) / depth;
        double x0 = Math.max(-w, cu - r), x1 = Math.min(w, cu + r), y0 = Math.max(-h, cv - t), y1 = Math.min(h, cv + t);
        if (x1 <= x0 || y1 <= y0) return 0;
        // the GPU fills the whole square of the puff (transparent corners too), not the circle inside it
        return Math.min(1, (x1 - x0) * (y1 - y0) / (4 * w * h));
    }

    /**
     * Screen share under a flat polygon with vertices xyz (from the eye, in order, convex): clipped by the near plane
     * and by the frame ({@link #cover}: 16:9 with a half-diagonal {@link FarView#edge}); the projected area — from the
     * vertices themselves. A square facing the camera — the same rectangle as for {@link #cover}; a lying puff and a
     * ribbon strip on screen — a trapezoid; the near edge which for dust at an angle is several times larger than at
     * the depth of the middle.
     */
    static double polygonFill(FarView view, double[] xyz) {
        org.joml.Vector3f f = view.forward(), l = view.left(), u = view.up();
        double diag = Math.tan(Math.min(view.edge(), 1.5)), w = diag * 16 / Math.sqrt(337), h = diag * 9 / Math.sqrt(337);
        int n = xyz.length / 3;
        PolygonScratch scratch = POLYGONS.get();
        scratch.reserve(n + 5);
        double[] a = scratch.a, b = scratch.b;
        // in eye axes: (left, up, depth); clipping by the near plane
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ax = xyz[3 * i], ay = xyz[3 * i + 1], az = xyz[3 * i + 2], bx = xyz[3 * j], by = xyz[3 * j + 1], bz = xyz[3 * j + 2];
            double ua = ax * l.x + ay * l.y + az * l.z, va = ax * u.x + ay * u.y + az * u.z, za = ax * f.x + ay * f.y + az * f.z;
            double ub = bx * l.x + by * l.y + bz * l.z, vb = bx * u.x + by * u.y + bz * u.z, zb = bx * f.x + by * f.y + bz * f.z;
            if (za >= NEAR_CLIP) {
                a[3 * m] = ua / za;
                a[3 * m + 1] = va / za;
                m++;
            }
            if (za >= NEAR_CLIP != zb >= NEAR_CLIP) {
                double t = (NEAR_CLIP - za) / (zb - za);
                a[3 * m] = (ua + (ub - ua) * t) / NEAR_CLIP;
                a[3 * m + 1] = (va + (vb - va) * t) / NEAR_CLIP;
                m++;
            }
        }
        // clipping by the frame: four half-planes
        for (int edge = 0; edge < 4 && m > 0; edge++) {
            int k = 0;
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                double pa = inside(edge, a[3 * i], a[3 * i + 1], w, h), pb = inside(edge, a[3 * j], a[3 * j + 1], w, h);
                if (pa >= 0) {
                    b[3 * k] = a[3 * i];
                    b[3 * k + 1] = a[3 * i + 1];
                    k++;
                }
                if (pa >= 0 != pb >= 0) {
                    double t = pa / (pa - pb);
                    b[3 * k] = a[3 * i] + (a[3 * j] - a[3 * i]) * t;
                    b[3 * k + 1] = a[3 * i + 1] + (a[3 * j + 1] - a[3 * i + 1]) * t;
                    k++;
                }
            }
            double[] swap = a;
            a = b;
            b = swap;
            m = k;
        }
        double area = 0;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            area += a[3 * i] * a[3 * j + 1] - a[3 * j] * a[3 * i + 1];
        }
        return Math.min(1, Math.abs(area) * 0.5 / (4 * w * h));
    }

    /** A convex polygon gains at most one vertex at each of the five clipping planes. */
    private static final class PolygonScratch {
        double[] a = new double[48], b = new double[48];

        void reserve(int vertices) {
            if (3 * vertices > a.length) {
                a = new double[3 * vertices];
                b = new double[3 * vertices];
            }
        }
    }

    private static final ThreadLocal<PolygonScratch> POLYGONS = ThreadLocal.withInitial(PolygonScratch::new);

    /** Near clipping plane of the polygon, blocks (for layer squares — 0.05 blocks, {@link #cover}). */
    private static final double NEAR_CLIP = 0.05;

    /**
     * Signed: whether point (u, v) is inside the frame's half-plane edge ±w, ±h (0 — right, 1 — left, 2 — top, 3 —
     * bottom).
     */
    private static double inside(int edge, double u, double v, double w, double h) {
        return switch (edge) {
            case 0 -> w - u;
            case 1 -> u + w;
            case 2 -> h - v;
            default -> v + h;
        };
    }

    /** Thickness of the Gaussian's visible edge (contrast threshold — 0.02; the square's edge slightly inside). */
    static final double EDGE_TAU = 0.05;
    /**
     * Puffs per Gaussian — no more than one per this many pixels of visible radius on screen (the puff frame is 128
     * pixels: larger, it blurs), smaller — one puff.
     */
    static final int CLUSTER = 12;
    static final double CLUSTER_PIXELS = 40;
    /** A cluster's puff — no more than this share of the Gaussian's visible radius. */
    static final double MAX_SHARE = 0.8;

    /**
     * Transition from one puff to a cluster: the first puff stays in place and gradually gains the cluster's size,
     * spread and frame. ceil(many) determines only the number of draw calls, not the appearance of an existing puff.
     */
    static double clusterBlend(double many) {
        return smoothstep(1, 2, many);
    }

    static double clusterSize(double many) {
        return 1 + (Math.min(MAX_SHARE, 1.6 / Math.sqrt(many)) - 1) * clusterBlend(many);
    }

    static double clubScale(double many, long seed, boolean vary) {
        double blend = vary ? 1 : clusterBlend(many);
        return clusterSize(many) * (1 + (SIZE_LOW + 2 * (1 - SIZE_LOW) * unit(seed >>> 21) - 1) * blend);
    }

    static double clusterFrame(double age, long seed, double many, boolean vary) {
        double plain = clubFrame(age, 0.5, false);
        return plain + (clubFrame(age, unit(seed >>> 28), true) - plain) * (vary ? 1 : clusterBlend(many));
    }

    /** A horizontal puff owns its axes; they do not belong to the camera. */
    static double clubAngle(long seed) {
        return 2 * Math.PI * unit(seed >>> 35);
    }

    /** The first puff sits at the material center; child positions form fixed pairs on opposite sides. */
    static double clubOffset(long seed, int child, int shift) {
        if (child == 0) return 0;
        long pair = mix(seed + ((child + 1L) / 2) * 0x632BE59BD9B4E019L);
        return ((child & 1) == 1 ? 1 : -1) * gauss(pair >>> shift);
    }
    /** Puffs of a segment — every this many σ (the smaller of the two). */
    static final double SEGMENT_STEP = 1.2;
    /** Puffs per segment — no more than (a spray column's jet — up to ~17 σ of its section). */
    static final int SEGMENT_CLUBS = 16;
    /**
     * Frames — from this one. The former puff (its own Mantaflow) started with a dense round emission and ran from the
     * third frame; the frames of the blast cloud's cap from EmberGen above ({@code tools/bake_fx}, puff-vdb) billow
     * from the first.
     */
    static final int FIRST_FRAME = 0;
    /**
     * Scatter of the frame of cluster and tube puffs around the source's frame, frames in both directions: neighboring
     * frames are the same cap at another moment, the puffs are different. At the end of the sprite sheet the scatter
     * goes backward: for an old cloud the source's frame is the last, and all puffs would be one picture (only
     * mirrored) — a “stamp”.
     */
    static final double FRAME_SPREAD = 1;

    /**
     * Smoke share in the opacity of the source's substance, which draws the fire body (glow,
     * {@link SmokeGrid.Source#drawsGlow}): the body melts, smoke shows through in the same share
     * ({@link CloudModel#glowShare}), sky — the sky light. The share is of opacity, not of mass: a fireball has
     * hundreds of optical thicknesses, and mass × share gave opaque soot already at a share of 1 % — a jump over ~0.1 s
     * at night at about 1200 K and by day as the explosive's ball cools, a dark halo up to ~2 radii in front of the
     * missile's ball by day (live 24.10).
     */
    static double smokeShare(boolean glow, double kelvin, double sky) {
        return glow ? 1 - CloudModel.glowShare(kelvin, sky) : 1;
    }

    /**
     * How many puffs of the tube's cluster ({@link #club}) cover its middle — the tube's axis (with the last one's
     * share, not less than 1): puff j — with the offset and size from the same grain as its square, the disc —
     * {@link #puffDisc} of its frame; the middle is under it if the offset across the view (for a lying one —
     * horizontally) is less than the disc (smoothly within ±20 %). For a tube the law is by the axis (thickness across
     * the axis, {@code PlumeContinuityTest}); before, a puff's share came from the layers on average over the cluster's
     * area (many × size²), but almost all of the cluster's puffs cover the axis (their offset is ~0.1 of the edge): a
     * thin trail nearby overshot the law at the axis by up to 1.4× (review #443). A cloud's cluster ({@code blob})
     * keeps the average over the area (SmokeFillTest: the budget does not change the transmittance on average).
     */
    static double centreCover(double px, double py, double pz, double d, long h, int count, double many, double size, double spread, double rh,
                              double rv, double rw, double grow, boolean lying, double age, boolean vary) {
        if (count <= 1) return 1;
        double vx = px / d, vy = py / d, vz = pz / d;
        // axes of the square facing the camera: across — the horizontal perpendicular to the view, up — across both
        double hx = -vz, hz = vx, hn = Math.hypot(hx, hz);
        if (hn < 1e-6) {
            hx = 1;
            hz = 0;
            hn = 1;
        }
        hx /= hn;
        hz /= hn;
        double ux = -vy * hz, uy = vz * hx - vx * hz, uz = vy * hx;
        double sum = 0;
        for (int j = 0; j < count; j++) {
            long hj = mix(h + j * 0x632BE59BD9B4E019L);
            double ox = clubOffset(h, j, 0) * spread * rh * 0.5, oy = lying ? 0 : clubOffset(h, j, 7) * spread * rw * 0.5,
                    oz = clubOffset(h, j, 14) * spread * rh * 0.5;
            double k = clubScale(many, hj, true) * grow;
            double disc = puffDisc(clusterFrame(age, hj, many, vary));
            double rho;
            if (lying) rho = Math.hypot(ox, oz) / (disc * rh * k);
            else rho = Math.hypot((ox * hx + oz * hz) / (disc * rh * k), (ox * ux + oy * uy + oz * uz) / (disc * rv * k));
            double t = Math.clamp((1.2 - rho) / 0.4, 0, 1);
            sum += t * t * (3 - 2 * t) * (j == count - 1 ? many - (count - 1) : 1);
        }
        return Math.max(1, sum);
    }

    /** Puff frame (with a fraction) at the source with frame age; u — puff grain 0..1; vary — with scatter. */
    static double clubFrame(double age, double u, boolean vary) {
        double last = FxAtlas.PUFF6_FRAMES - 1.001;
        if (!vary) return Math.clamp(age, FIRST_FRAME, last);
        return Math.clamp(age, FIRST_FRAME + FRAME_SPREAD, last - FRAME_SPREAD) + FRAME_SPREAD * (2 * u - 1);
    }

    /**
     * The render thread fills one instance with the frame's puffs, places and ribbons ({@link #sink}, {@link #tube},
     * {@link #ribbon}). With a {@link Planner} the other instance meanwhile plans the previous frame off the client
     * thread, and each frame draws the last completed plan at its own eye ({@link #flush}); without one the filled
     * instance plans and draws at once.
     */
    private static Draw fill = new Draw(), job = new Draw();
    /** Client thread only: the replayed plan's veil, merged and laid at the current eye. */
    private static final Draw VEIL = new Draw();
    /**
     * The plan being made off the client thread, and the last completed one (drawn every frame until the next
     * completes).
     */
    private static Recording planned = new Recording(), shown = new Recording();
    @Nullable
    private static Planner planner;
    private static boolean running;
    /** Where the last frame drew: a frame without smoke inputs still draws the last plan. */
    @Nullable
    private static FarView lastView;
    @Nullable
    private static Out lastOut;
    /** Longest wait for the previous plan; past it the frame draws the plan before it and skips its own. */
    static final long WAIT_NANOS = 3_000_000;
    private static final Runnable JOB = () -> {
        long start = System.nanoTime();
        job.flush();
        job.record.nanos = System.nanoTime() - start;
        job.record.complete = true;
    };
    private static final SmokeGrid.Look[] LOOKS = SmokeGrid.Look.values();

    private SmokePuffs() {}

    /** Clouds whose volume does not draw — into the frame. */
    static void collect(FarView view, FarSprites out) {
        // a frame without smoke inputs still draws the last plan at its own eye
        lastView = view;
        lastOut = out;
        double seconds = SmokeGrid.seconds(view.partial());
        SmokeGrid.quads(seconds, view.camera().x, view.camera().y, view.camera().z,
                b -> SmokeVisibility.visible(view, b), (s, sampledAt) -> sink(view, out, s, sampledAt));
    }

    /**
     * Plan the frame off the client thread (null: plan and draw on the caller's thread, as unit tests do). Waits for
     * the running plan before switching.
     */
    static void planner(@Nullable Planner next) {
        if (planner == next) return;
        stop();
        planner = next;
    }

    /** Planner of the client: one named daemon thread, started with its first plan. */
    static Planner worker() {
        return new Worker("Incoming Smoke");
    }

    /**
     * Waiting puffs of all sources of the frame (merge {@link #merge}, budget {@link #FILL_BUDGET}) and the veil — into
     * the frame. Called by the one who took {@link #sink} when all the frame's sources were handed out (and the
     * projectiles' trails, {@code FarFlightView}: their clusters and veils are in the same frame).
     * <p>
     * With a planner, the frame draws the last completed plan at its own eye: billboards, ribbons and the veil are
     * built here, on the client thread, from the plan's world-anchored quads; then this frame's inputs go to the
     * planner. The plan lags one frame (snapshots of clouds are refreshed every few frames anyway). If the previous
     * plan is not done within {@link #WAIT_NANOS}, the frame draws the plan before it again and drops its own inputs.
     */
    static void flush() {
        Planner p = planner;
        if (p == null) {
            fill.flush();
            report(fill, fill);
            return;
        }
        FarView view = fill.frameView();
        Out out = fill.frameOut();
        if (view != null) {
            lastView = view;
            lastOut = out;
        }
        boolean ready = !running || p.await(WAIT_NANOS);
        if (running && ready) collect();
        if (lastView != null && !(shown.fits(lastView) && shown.trim(lastView, budget))) {
            // The last plan was made for another projection (zoom), far from this eye, or a fast turn brings more of it
            // onto the screen than the budget's fading share: this frame is planned now and drawn at once, as inline
            // planning would.
            boolean drained = !running || p.await(DRAIN_NANOS);
            if (running && drained) collect();
            shown.clear();
            if (view == null || !drained) {
                fill.forget();
                fill.veilClear();
                return;
            }
            start(p);
            if (p.await(DRAIN_NANOS)) {
                collect();
                shown.trim(lastView, budget);
                shown.replay(lastView, lastOut, VEIL);
            }
            return;
        }
        if (lastView != null && lastOut != null) shown.replay(lastView, lastOut, VEIL);
        if (!ready) {
            fill.forget();
            fill.veilClear();
            return;
        }
        start(p);
    }

    /**
     * Longest wait for a plan the frame cannot do without (projection change, world exit): a plan's own bounded cost.
     */
    static final long DRAIN_NANOS = 10_000_000_000L;

    /** The finished plan becomes the drawn one. */
    private static void collect() {
        running = false;
        Recording made = job.record;
        job.record = null;
        if (made != null && made.complete) {
            planned = shown;
            shown = made;
            report(job, VEIL);
            lastPlanNanos = made.nanos;
        }
    }

    /**
     * This frame's inputs go to the planner, continuing the last plan's history (merge level, cluster share, occlusion
     * grid).
     */
    private static void start(Planner p) {
        fill.level = job.level;
        fill.lastShare = job.lastShare;
        fill.shareReady = job.shareReady;
        fill.adoptOcclusion(job);
        fill.seal(planned);
        Draw idle = job;
        job = fill;
        fill = idle;
        fill.forget();
        fill.veilClear();
        running = true;
        p.start(JOB);
    }

    /**
     * Storage for this frame's camera vectors ({@link FarRenderer#view}): the filling Draw's own, which no plan reads
     * until it is sealed; the Camera's vectors are live until the next frame, and a fresh copy every frame would be
     * garbage.
     */
    static org.joml.Vector3f[] cameraBasis() {
        return fill.basis;
    }

    /** Statistics of the last plan (description lines, tests). */
    private static double lastFill, lastShare = 1, lastFloor, lastRaw, lastPlaceMatter;
    private static int lastLevel, lastVeils, lastItems, lastBlobs, lastPlaces, lastRefused, lastReused;
    private static long lastPlanNanos;

    /** Time of the last completed plan on the planner's thread, ms; 0 — planned inline. */
    static double lastPlanMillis() {
        return planner == null ? 0 : lastPlanNanos / 1e6;
    }

    private static void report(Draw d, Draw veil) {
        lastFill = d.lastFill;
        lastShare = d.lastShare;
        lastFloor = d.lastFloor;
        lastRaw = d.lastRaw;
        lastLevel = d.level;
        lastItems = d.lastItems;
        lastBlobs = d.lastBlobs;
        lastPlaces = d.lastPlaces;
        lastRefused = d.lastRefused;
        lastReused = d.lastReused;
        lastPlaceMatter = d.lastPlaceMatter;
        lastVeils = veil.lastVeils;
    }

    /**
     * Fill of the last frame by cloud squares, in screens, and the share of a cluster the budget gave it (measured;
     * description lines).
     */
    static double lastFill() {
        return lastFill;
    }

    static double lastShare() {
        return lastShare;
    }

    /** Frame floor — one puff per Gaussian and tube spot that stayed as squares, in screens: not above the budget. */
    static double lastFloor() {
        return lastFloor;
    }

    /** Floor before merging of different sources and the veil ({@link #lastFloor} — after), in screens. */
    static double lastRaw() {
        return lastRaw;
    }

    /**
     * Merge level of different sources ({@link #LEVELS}; 0 — no merge), veil Gaussians, frame's cloud squares
     * (Gaussians and spots).
     */
    static int lastLevel() {
        return lastLevel;
    }

    static int lastVeils() {
        return lastVeils;
    }

    static int lastItems() {
        return lastItems;
    }

    /**
     * Cloud puffs in the plan of the last frame (after merging inside sources and {@link #BLOBS}) and tube spots
     * ({@link #PLACES}).
     */
    static int lastBlobs() {
        return lastBlobs;
    }

    static int lastPlaces() {
        return lastPlaces;
    }

    /** Tube spots that the last frame did not give ({@link #PLACES}): their segments had no puff share in the frame. */
    static int lastRefused() {
        return lastRefused;
    }

    /** Sources whose puffs in the last frame were the same as at their previous merge and took its result. */
    static int lastReused() {
        return lastReused;
    }

    /**
     * Substance of the last frame's tube spots: attenuation of all the puffs' mass, m² (thickness across the axis ×
     * √(2π)σ × piece of axis).
     */
    static double lastPlaceMatter() {
        return lastPlaceMatter;
    }

    /** Budget anew: plan and merge level (checks that build frames with different budgets in a row). */
    static void resetBudget() {
        for (Draw d : new Draw[]{fill, job}) {
            d.plans = 0;
            d.level = 0;
            d.veilLevel = 0;
            d.shareReady = false;
        }
        VEIL.veilLevel = 0;
    }

    /**
     * Leaving the world, change of dimension: waiting puffs and spots, plan, veil — anew; the running plan is drained
     * first.
     */
    static void reset() {
        stop();
        for (Draw d : new Draw[]{fill, job}) {
            d.merges.clear();
            d.forget();
            d.veilClear();
        }
        VEIL.veilClear();
        planned.clear();
        shown.clear();
        lastView = null;
        lastOut = null;
        resetBudget();
    }

    /** Waits for the running plan and drops it: its sources and views may belong to a world being left. */
    private static void stop() {
        if (planner != null) planner.stop();
        if (running) {
            running = false;
            job.record = null;
            job.forget();
            job.veilClear();
        }
    }

    /**
     * Runs a frame's plan off the caller's thread, one at a time ({@link #flush}). {@link #start} and {@link #await}
     * order memory: the plan sees everything the client thread wrote before starting it, and the client thread sees the
     * whole plan after {@link #await} returns true.
     */
    interface Planner {
        /** Start the plan (the previous one is done). */
        void start(Runnable plan);

        /** Wait at most nanos for the started plan; true when it is done. */
        boolean await(long nanos);

        /** Wait for the started plan, then release the thread (a later {@link #start} starts it again). */
        void stop();
    }

    /**
     * One named daemon thread, started by the first plan and released by {@link #stop}; a failing plan is dropped, not
     * drawn.
     */
    static final class Worker implements Planner {
        private final String name;
        private final Object lock = new Object();
        @Nullable
        private Thread thread;
        @Nullable
        private Runnable task;
        private boolean busy;
        private boolean warned;

        Worker(String name) {
            this.name = name;
        }

        @Override
        public void start(Runnable plan) {
            synchronized (lock) {
                task = plan;
                busy = true;
                if (thread == null) {
                    Thread t = new Thread(this::loop, name);
                    t.setDaemon(true);
                    thread = t;
                    t.start();
                }
                lock.notifyAll();
            }
        }

        private void loop() {
            Thread self = Thread.currentThread();
            while (true) {
                Runnable plan;
                synchronized (lock) {
                    while (task == null && thread == self) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (thread != self) return;
                    plan = task;
                    task = null;
                }
                try {
                    plan.run();
                } catch (Throwable t) {
                    if (!warned) {
                        warned = true;
                        ua.zentix.incoming.Incoming.LOG.error("Smoke plan failed; the frame keeps the previous plan", t);
                    }
                } finally {
                    synchronized (lock) {
                        busy = false;
                        lock.notifyAll();
                    }
                }
            }
        }

        @Override
        public boolean await(long nanos) {
            synchronized (lock) {
                long end = System.nanoTime() + nanos;
                while (busy) {
                    long left = end - System.nanoTime();
                    if (left <= 0) return false;
                    try {
                        lock.wait(left / 1_000_000, (int) (left % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
        }

        @Override
        public void stop() {
            Thread t;
            boolean interrupted = false;
            synchronized (lock) {
                while (busy) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                t = thread;
                thread = null;
                task = null;
                lock.notifyAll();
            }
            // idle and told to leave: it ends at once
            if (t != null) {
                try {
                    t.join(1000);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * A plan made off the client thread: its quads and ribbons as calls of {@link Out} relative to the planning eye,
     * and its veil Gaussians. {@link #replay} anchors them at the current eye, so billboards, ribbons and the veil face
     * the current camera; the plan's choices (merges, clusters, concealment, budget) are the previous frame's.
     */
    static final class Recording implements Out {
        private static final int PUFF = 0, FLAT = 1, RIBBON = 2, STRIDE = 20;
        /**
         * Largest relative change of the pixel angle and frame half-diagonal, and eye step (m), a plan is drawn across.
         */
        static final double PROJECTION = 0.03, STEP = 1;
        private double[] calls = new double[64 * STRIDE];
        private FxAtlas.Sprite[] sprites = new FxAtlas.Sprite[64];
        private int count;
        private double[] veils = new double[8 * VEIL_FIELDS];
        private int veilCount;
        private double ex, ey, ez, pixel, edge;
        private float fx, fy, fz;
        private boolean anchored;
        /**
         * Visible opacity of the plan entry whose quads are recorded next, the order {@code Draw.fit} keeps entries in.
         */
        float rank;
        /** Per call: fill at the drawn view, the keep order, and whether this frame leaves it out ({@link #trim}). */
        private double[] fills = new double[64];
        private long[] order = new long[64];
        private boolean[] left = new boolean[64];
        /** Calls the next replay leaves out ({@link #trim}). */
        int trimmed;
        private final double[] corners = new double[12];
        private final FarSprites.RibbonShape shape = new FarSprites.RibbonShape();
        /**
         * Set by the plan when it finished; an unfinished or failed plan is not drawn. Its time on the planner's
         * thread.
         */
        volatile boolean complete;
        long nanos;
        private final float[] joint = new float[3];

        void clear() {
            count = veilCount = trimmed = 0;
            anchored = complete = false;
            java.util.Arrays.fill(sprites, null);
        }

        void anchor(FarView view) {
            Vec3 eye = view.camera();
            ex = eye.x;
            ey = eye.y;
            ez = eye.z;
            pixel = view.pixel();
            edge = view.edge();
            org.joml.Vector3f f = view.forward();
            fx = f.x;
            fy = f.y;
            fz = f.z;
            anchored = true;
        }

        /**
         * Chooses what of the plan the next {@link #replay} at view leaves out so that it fills at most budget screens;
         * false if that would be more than the budget's fading share. At the view it was planned for the plan keeps the
         * budget ({@code Draw.drawnFill}); at another one a turn brings quads planned beside the old frame onto the
         * screen (review of #511: a 30° turn showed 20.2 screens of a 13.3-screen plan, a 5° one 16.9 of 16.0). Each
         * recorded quad and ribbon is measured on this screen by the plan's law; in the order {@code Draw.fit} keeps
         * entries (most visible first) each stays if its fill still fits. Up to 1 − {@link #FIT_FADE} of the budget
         * over, what goes is what {@code fit} fades into the veil anyway, for one frame; more, and the frame is planned
         * for this view.
         */
        boolean trim(FarView view, double budget) {
            trimmed = 0;
            if (!anchored) return true;
            Vec3 eye = view.camera();
            org.joml.Vector3f f = view.forward();
            if (eye.x == ex && eye.y == ey && eye.z == ez && f.x == fx && f.y == fy && f.z == fz && view.pixel() == pixel && view.edge() == edge)
                return true;
            double ox = ex - eye.x, oy = ey - eye.y, oz = ez - eye.z, total = 0;
            double[] c = calls;
            for (int k = 0; k < count; k++) {
                int o = k * STRIDE;
                int kind = (int) c[o];
                double fill = 0;
                if (kind == RIBBON) {
                    joint[0] = (float) c[o + 16];
                    joint[1] = (float) c[o + 17];
                    joint[2] = (float) c[o + 18];
                    boolean strip = shape.set(view.far(), f.x, f.y, f.z, c[o + 1] + ox, c[o + 2] + oy, c[o + 3] + oz, c[o + 4], (float) c[o + 5],
                            c[o + 7] + ox, c[o + 8] + oy, c[o + 9] + oz, c[o + 10], (float) c[o + 11], joint);
                    fill = shape.fill(view, strip);
                } else if ((float) c[o + 11] >= 0.002f) {
                    // drawn quads only (the layer skips fainter)
                    if (kind == PUFF) ua.zentix.incoming.client.fx.layer.PuffGeometry.face(c[o + 1] + ox, c[o + 2] + oy, c[o + 3] + oz, c[o + 4], c[o + 5],
                            corners);
                    else ua.zentix.incoming.client.fx.layer.PuffGeometry.flat(c[o + 1] + ox, c[o + 2] + oy, c[o + 3] + oz, c[o + 4], c[o + 5], c[o + 13],
                            corners);
                    fill = polygonFill(view, corners);
                }
                fills[k] = fill;
                total += fill;
            }
            if (total <= budget) return true;
            if (total > (2 - FIT_FADE) * budget) return false;
            for (int k = 0; k < count; k++) order[k] = (long) Float.floatToIntBits(2 - (float) c[k * STRIDE + 19]) << 32 | k;
            java.util.Arrays.sort(order, 0, count);
            double sum = 0;
            for (int k = 0; k < count; k++) {
                int i = (int) order[k];
                left[i] = sum + fills[i] > budget;
                if (left[i]) trimmed++;
                else sum += fills[i];
            }
            return true;
        }

        /**
         * Whether this plan may be drawn at view: the same projection within {@link #PROJECTION} and an eye within
         * {@link #STEP}. Fill on screen grows as the inverse square of the pixel angle (a 4× zoom: 13 → 121 screens)
         * and as the eye nears a puff; beyond these limits {@link #trim} would leave out much of the frame, and the
         * plan's clusters and merges were chosen for another pixel size.
         */
        boolean fits(FarView view) {
            if (!anchored) return true;
            Vec3 eye = view.camera();
            double dx = eye.x - ex, dy = eye.y - ey, dz = eye.z - ez;
            return Math.abs(view.pixel() / pixel - 1) <= PROJECTION && Math.abs(view.edge() / edge - 1) <= PROJECTION
                    && dx * dx + dy * dy + dz * dz <= STEP * STEP;
        }

        private int next(FxAtlas.Sprite sprite) {
            if (count == sprites.length) {
                sprites = java.util.Arrays.copyOf(sprites, 2 * count);
                calls = java.util.Arrays.copyOf(calls, 2 * count * STRIDE);
                fills = new double[2 * count];
                order = new long[2 * count];
                left = new boolean[2 * count];
            }
            sprites[count] = sprite;
            calls[count * STRIDE + 19] = rank;
            return count++ * STRIDE;
        }

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r,
                          float g, float b, float a) {
            int o = next(sprite);
            double[] c = calls;
            c[o] = PUFF;
            c[o + 1] = dx;
            c[o + 2] = dy;
            c[o + 3] = dz;
            c[o + 4] = halfA;
            c[o + 5] = halfB;
            c[o + 6] = mix;
            c[o + 7] = shade;
            c[o + 8] = r;
            c[o + 9] = g;
            c[o + 10] = b;
            c[o + 11] = a;
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, double angle, FxAtlas.Sprite sprite,
                              float mix, float shade, float r, float g, float b, float a) {
            int o = next(sprite);
            double[] c = calls;
            c[o] = FLAT;
            c[o + 1] = dx;
            c[o + 2] = dy;
            c[o + 3] = dz;
            c[o + 4] = halfA;
            c[o + 5] = halfB;
            c[o + 6] = mix;
            c[o + 7] = shade;
            c[o + 8] = r;
            c[o + 9] = g;
            c[o + 10] = b;
            c[o + 11] = a;
            c[o + 12] = soft;
            c[o + 13] = angle;
        }

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz,
                              double halfB, float alphaB, int lightB, float r, float g, float b, float[] joint) {
            int o = next(null);
            double[] c = calls;
            c[o] = RIBBON;
            c[o + 1] = ax;
            c[o + 2] = ay;
            c[o + 3] = az;
            c[o + 4] = halfA;
            c[o + 5] = alphaA;
            c[o + 6] = lightA;
            c[o + 7] = bx;
            c[o + 8] = by;
            c[o + 9] = bz;
            c[o + 10] = halfB;
            c[o + 11] = alphaB;
            c[o + 12] = lightB;
            c[o + 13] = r;
            c[o + 14] = g;
            c[o + 15] = b;
            c[o + 16] = joint[0];
            c[o + 17] = joint[1];
            c[o + 18] = joint[2];
            return true;
        }

        /** The plan's veil is laid on the client thread ({@link #replay}). */
        @Override
        public void veil(Veil veil, int light) {
            throw new IllegalStateException("veil is laid at replay");
        }

        /** The plan's veil Gaussians, relative to the planning eye. */
        void veil(double[] gaussians, int n) {
            if (veils.length < n * VEIL_FIELDS) veils = new double[Math.max(n, 2 * veils.length / VEIL_FIELDS) * VEIL_FIELDS];
            System.arraycopy(gaussians, 0, veils, 0, n * VEIL_FIELDS);
            veilCount = n;
        }

        /**
         * Draw at the eye of view into out: positions move by the eye's step since planning, geometry faces this
         * camera, and what {@link #trim} chose for this view is left out.
         */
        void replay(FarView view, Out out, Draw veil) {
            if (!anchored) return;
            Vec3 cam = view.camera();
            double ox = ex - cam.x, oy = ey - cam.y, oz = ez - cam.z;
            double[] c = calls;
            for (int k = 0; k < count; k++) {
                if (trimmed > 0 && left[k]) continue;
                int o = k * STRIDE;
                int kind = (int) c[o];
                if (kind == PUFF) out.puff6(c[o + 1] + ox, c[o + 2] + oy, c[o + 3] + oz, c[o + 4], c[o + 5], sprites[k], (float) c[o + 6],
                        (float) c[o + 7], (float) c[o + 8], (float) c[o + 9], (float) c[o + 10], (float) c[o + 11]);
                else if (kind == FLAT) out.puff6Flat(c[o + 1] + ox, c[o + 2] + oy, c[o + 3] + oz, c[o + 4], c[o + 5], c[o + 12], c[o + 13],
                        sprites[k], (float) c[o + 6], (float) c[o + 7], (float) c[o + 8], (float) c[o + 9], (float) c[o + 10], (float) c[o + 11]);
                else {
                    joint[0] = (float) c[o + 16];
                    joint[1] = (float) c[o + 17];
                    joint[2] = (float) c[o + 18];
                    out.ribbon(c[o + 1] + ox, c[o + 2] + oy, c[o + 3] + oz, c[o + 4], (float) c[o + 5], (int) c[o + 6], c[o + 7] + ox, c[o + 8] + oy,
                            c[o + 9] + oz, c[o + 10], (float) c[o + 11], (int) c[o + 12], (float) c[o + 13], (float) c[o + 14], (float) c[o + 15], joint);
                }
            }
            veil.lay(view, out, veils, veilCount, ox, oy, oz);
        }
    }

    /**
     * Estimate of how many screens a cluster with the full number of puffs {@code most} will cover at share {@code s}
     * (the cluster — between one puff and the full one): squares — ⌈1 + (most − 1)·s⌉ (the last, with the alpha share,
     * fills its whole area), each has a share of the size (a single one — the whole edge) × share of the screen under
     * the cluster's edge with its scatter ({@link #cover}). For fitting the share; what the real squares fill —
     * {@code Draw.drawnFill}.
     */
    static double clusterFill(FarView view, double px, double py, double pz, double ra, double rb, double most, double s) {
        double many = 1 + (most - 1) * s;
        int count = (int) Math.ceil(many - 1e-9);
        double size = clusterSize(many), reach = 1 + (1 - size) * 0.5;
        return Math.max(1, count * size * size) * cover(view, px, py, pz, ra * reach, rb * reach);
    }

    /**
     * A segment of a trail or plume ({@code FarFlightView.line}) — into the wait for the frame, like tube spots: the
     * ribbon also fills the screen ({@link FarSprites.RibbonShape#fill}: a strip by its own vertices, the disc of the
     * end, a veil “eye in the tube”), and its fill — in the plan and the budget ({@link Draw#fit}: does not fit — not
     * drawn, the least visible — first). Before, ribbons went past the plan, and 300 tubes beyond the frame's spots
     * filled 29.5 screens with a budget of 16. Returns whether the strip lies; joint — as with {@link Out#ribbon}
     * (across at b).
     */
    static boolean ribbon(FarView view, Out out, double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by,
                          double bz, double halfB, float alphaB, int lightB, float r, float g, float b, float[] joint) {
        return fill.holdRibbon(view, out, ax, ay, az, halfA, alphaA, lightA, bx, by, bz, halfB, alphaB, lightB, r, g, b, joint);
    }

    /**
     * Sink of the source's puffs source in frame view at moment seconds: puffs — as squares into out (one per frame
     * stream).
     */
    static SmokeGrid.Sink sink(FarView view, Out out, SmokeGrid.Source source, double seconds) {
        fill.begin(view, out, source, seconds);
        return fill;
    }

    /** Sink of one source's puffs: puffs — as squares. */
    private static final class Draw implements SmokeGrid.Sink {
        private final double[] point = new double[2];
        FarView view;
        Out out;
        private SmokeGrid.Source source;
        /** Puff frame by the substance's growth (with a fraction): smoke and spray. */
        private double age, sprayAge;
        private long seed;
        private boolean glow, sprays;
        private int index;
        /** Look of the source's next puffs and its frame ({@link SmokeGrid.Sink#look}). */
        private SmokeGrid.Look look = SmokeGrid.Look.SMOKE;
        private double lookFrame;

        void begin(FarView view, Out out, SmokeGrid.Source source, double seconds) {
            // the past source's puffs merge only among themselves
            close();
            this.view = view;
            this.out = out;
            this.source = source;
            double growth = source.puffGrowth(seconds);
            this.age = FxAtlas.puffFrame(growth);
            this.sprayAge = FxAtlas.sprayFrame(growth);
            this.seed = source.seed();
            this.glow = source.drawsGlow();
            this.sprays = source.sprayClubs(seconds);
            this.index = 0;
            this.look = SmokeGrid.Look.SMOKE;
            this.lookFrame = 0;
            // source look for the second pass ({@link #flush})
            if (contexts == ctxSource.length) {
                int n = 2 * contexts;
                ctxSource = java.util.Arrays.copyOf(ctxSource, n);
                ctxOut = java.util.Arrays.copyOf(ctxOut, n);
                ctxView = java.util.Arrays.copyOf(ctxView, n);
                ctxAge = java.util.Arrays.copyOf(ctxAge, n);
                ctxSprayAge = java.util.Arrays.copyOf(ctxSprayAge, n);
                ctxSeed = java.util.Arrays.copyOf(ctxSeed, n);
                ctxSprays = java.util.Arrays.copyOf(ctxSprays, n);
                ctxVis = java.util.Arrays.copyOf(ctxVis, n);
            }
            ctxSource[contexts] = source;
            // what visible() reads that the client tick changes: a plan made off the client thread reads it as of now
            ctxVis[contexts] = 0;
            source.visibility(ctxVis, contexts);
            ctxOut[contexts] = out;
            ctxView[contexts] = view;
            ctxAge[contexts] = age;
            ctxSprayAge[contexts] = sprayAge;
            ctxSeed[contexts] = seed;
            ctxSprays[contexts] = sprays;
            contexts++;
            from = holding;
            joint[0] = Float.NaN;
        }

        @Override
        public void look(SmokeGrid.Look look, double frame) {
            this.look = look;
            this.lookFrame = frame;
        }

        /**
         * Ends of a tube piece, and across the ribbon at the end of this source's previous piece (NaN — there was no
         * ribbon).
         */
        private final FarFlightView.Tube a = new FarFlightView.Tube(), b = new FarFlightView.Tube();
        private final float[] joint = new float[3];

        /**
         * Trail — a tube, like a projectile's trail ({@link FarFlightView#line}): a Gaussian section σ = √(σh·σv)
         * (average geometric mean, as for the plume's thickness {@code FirePlume.tau}; ⚠ the plume's section in σh/σv
         * is up to 2 times wider than tall), soot attenuation per meter at each end; narrow on screen — as a ribbon,
         * large — as puffs every ≤ 1.2σ. Pieces of one source go in a row — the ribbon without a gap at the joint.
         */
        @Override
        public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                     double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin, double rise,
                     double sigma0) {
            int at = index++;
            // glowing gas — the fire body at the source; as it cools, it shows through as smoke in the same share that
            // the body melts
            double smoke = smokeShare(glow, kelvin, view.skyLuminance());
            if (smoke <= 0) {
                joint[0] = Float.NaN;
                return;
            }
            // above the relief that covers the source's place far away (as for a puff,
            // {@link SmokeGrid.Source#visible}): at each end by its height and span of the section, along the piece —
            // between them
            double visA = source.visible(ay, 3 * Math.max(sha, sva)), visB = source.visible(by, 3 * Math.max(shb, svb));
            sootA *= visA;
            dustA *= visA;
            sootB *= visB;
            dustB *= visB;
            if (sootA + sootB + dustA + dustB <= 0 || !(sha > 0) || !(sva > 0) || !(shb > 0) || !(svb > 0)) {
                joint[0] = Float.NaN;
                return;
            }
            // attenuation per meter: soot — full (as the plume's thickness and visibility threshold, FirePlume.tau),
            // dust — as for the puffs of clouds (SmokeGrid.dustK by its albedo)
            double kd = SmokeGrid.dustK(albedoR, albedoG, albedoB);
            double ea = CloudModel.SOOT_K * sootA + kd * dustA, eb = CloudModel.SOOT_K * sootB + kd * dustB;
            double ks = CloudModel.SOOT_K * (sootA + sootB), kdust = kd * (dustA + dustB);
            Vec3 cam = view.camera();
            end(a, ax - cam.x, ay - cam.y, az - cam.z, Math.sqrt(sha * sva), ea, smoke);
            end(b, bx - cam.x, by - cam.y, bz - cam.z, Math.sqrt(shb * svb), eb, smoke);
            // color and shade — of the thick layer of the mix, as for cloud puffs ({@link #thick}); the ribbon — in the
            // same color as the puff's tone
            float cr = thick(ks, kdust, 0, albedoR), cg = thick(ks, kdust, 0, albedoG), cb = thick(ks, kdust, 0, albedoB);
            float tone = FarFlightView.TONE;
            double shade = shade(ks, kdust, 0, albedoG) / BAKED_K;
            int drawn = FarFlightView.line(view, out, a, b, cr * tone, cg * tone, cb * tone, cr, cg, cb, shade, sigma0,
                    mix(seed * 0x9E3779B97F4A7C15L + at), TUBES + contexts - 1, joint);
            if (drawn != FarFlightView.RIBBON) joint[0] = Float.NaN;
        }

        /**
         * The end of the tube (from the camera): width at half of the middle's opacity, and the ribbon — as for a trail
         * ({@code TrailModel.width}); smoke — smoke share in the opacity ({@link #smokeShare}).
         */
        private void end(FarFlightView.Tube t, double dx, double dy, double dz, double sigma, double e, double smoke) {
            double tau = e / (Math.sqrt(2 * Math.PI) * sigma), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double width = ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma);
            FarFlightView.ribbon(width, d, view.pixel(), point);
            float alpha = width > 0 ? (float) (smoke * (1 - Math.exp(-tau)) * point[1] * view.air(d, dy)) : 0;
            t.set(dx, dy, dz, sigma, e, width, point[0], alpha, OPEN_SKY).shown = smoke;
        }

        @Override
        public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                            double water, float dustR, float dustG, float dustB, double kelvin, double rise) {
            int at = index++;
            // glowing gas — the fire body at the source; as it cools, it shows through as smoke in the same share that
            // the body melts
            double smoke = smokeShare(glow, kelvin, view.skyLuminance());
            if (smoke <= 0 || soot + dust + water <= 0 || !(sh > 0) || !(sv > 0)) return;
            double len = 2 * Math.sqrt(ax * ax + ay * ay + az * az);
            // the segment's puffs wait, like point puffs: merge and the frame fill budget — for all ({@link #flush});
            // they go side by side another, so each — with its own frame, reflection and wander, as the tube's puffs
            // (identical — a stack of coins)
            int n = (int) Math.min(SEGMENT_CLUBS, Math.max(1, Math.ceil(len / (SEGMENT_STEP * Math.min(sh, sv)))));
            for (int k = 0; k < n; k++) {
                double f = n == 1 ? 0 : (k + 0.5) / n * 2 - 1;
                hold(x + ax * f, y + ay * f, z + az * f, sh, sv, soot / n, dust / n, water / n, dustR, dustG, dustB, at * SEGMENT_CLUBS + k, smoke,
                        n > 1);
            }
        }

        /** Puffs of the frame waiting for merging and budget: {@link #FIELDS} numbers per puff. */
        private double[] held = new double[16 * FIELDS];
        private int holding, from;
        private boolean[] merged = new boolean[16];
        /** Sources of the frame: look, sink, puff frame, grain, spray. */
        private SmokeGrid.Source[] ctxSource = new SmokeGrid.Source[8];
        private Out[] ctxOut = new Out[8];
        private FarView[] ctxView = new FarView[8];
        private double[] ctxAge = new double[8], ctxSprayAge = new double[8];
        private long[] ctxSeed = new long[8];
        private boolean[] ctxSprays = new boolean[8];
        /**
         * Frozen visibility state of each source ({@link SmokeGrid.Source#visibility}), and the context of the puff
         * being planned.
         */
        private double[] ctxVis = new double[8];
        private int contexts, ctx;
        /** Non-null while this instance plans a frame for the planner: its draw calls and veil go there. */
        @Nullable
        Recording record;
        /** Camera vectors of the frame this instance fills ({@link #cameraBasis}). */
        final org.joml.Vector3f[] basis = {new org.joml.Vector3f(), new org.joml.Vector3f(), new org.joml.Vector3f()};
        /**
         * Tube spots of the frame waiting for the plan: {@link #PLACE_FIELDS} numbers each; puff grain and tube —
         * separately.
         */
        private double[] places = new double[64 * PLACE_FIELDS];
        private long[] placeSeed = new long[64], placeTube = new long[64];
        private boolean[] placeGone = new boolean[64];
        private int placeCount;
        /** Looks and sinks of spots (in the game all of the frame's spots are the same). */
        private FarView[] placeView = new FarView[4];
        private Out[] placeOut = new Out[4];
        private int placeContexts;
        /**
         * Ribbons of the frame waiting for the plan: ends (6), half-widths (2), opacities (2), world light (2), color
         * (3), across at a (3), look, fill, visibility.
         */
        private double[] ribbons = new double[16 * RIBBON_FIELDS];
        private int ribbonCount;
        private int[] ribbonEntry = new int[16];
        private final FarSprites.RibbonShape shape = new FarSprites.RibbonShape();
        private final float[] ribbonJoint = new float[3];
        /** Substances of the frame's spots — color and shade level ({@link #material}). */
        private final float[] materials = new float[4 * MATERIALS];
        private int materialCount;
        /** Plan record of a cloud puff and of a spot (−1 — no squares). */
        private int[] blobEntry = new int[16], placeEntry = new int[64];
        /** Survivors of the level's merge — their plan anew. */
        private int[] dirty = new int[64];
        private boolean[] isDirty = new boolean[64];
        private final Cells cells = new Cells();
        /** Tubes in the groups of the frame's merged spots ({@link #mergeLevel}). */
        private final TubeGroups groups = new TubeGroups();
        /**
         * Veil of the frame: substance Gaussians at the eye ({@link #VEIL_FIELDS} numbers per Gaussian); the thickness
         * along rays is computed by {@link #veilFlush}.
         */
        private double[] veils = new double[8 * VEIL_FIELDS];
        private boolean[] veilGone = new boolean[8];
        private int veilCount, veilLevel;
        @Nullable
        private Out veilOut;
        private FarView veilView;
        private final Veil veil = new Veil();
        /**
         * Measurement of the last frame: fill by squares, in screens; floor — one per Gaussian and spot that stayed as
         * squares (not above the budget), and the floor before merging and veil; share of clusters; merge level; veil
         * Gaussians; cluster squares.
         */
        double lastFill, lastFloor, lastRaw, lastShare = 1;
        int level, lastVeils, lastItems, lastBlobs, lastPlaces, lastRefused, refused, lastReused;
        double lastPlaceMatter, placeMatter;
        /**
         * Mode of the {@link #blob} and {@link #club} pass: plan (cost only) or squares with the cluster share
         * {@link #share}.
         */
        private boolean planning;
        private double share = 1;
        private boolean shareReady;
        /** Whose plan is written ({@link #planCluster}): cloud cluster i — i, spot p — ~p. */
        private int current;
        /** Floor of the frame — sum of plan records that stayed as squares (one per Gaussian and spot), in screens. */
        private double floor;

        /** Source's point puff — into waiting: merge with neighbors and budget — in {@link #flush}. */
        private void hold(double x, double y, double z, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                          int id, double smoke, boolean vary) {
            if (holding * FIELDS + FIELDS > held.length) held = java.util.Arrays.copyOf(held, 2 * held.length);
            int o = holding++ * FIELDS;
            held[o] = x;
            held[o + 1] = y;
            held[o + 2] = z;
            held[o + 3] = sh;
            held[o + 4] = sv;
            held[o + 5] = soot;
            held[o + 6] = dust;
            held[o + 7] = water;
            held[o + 8] = dr;
            held[o + 9] = dg;
            held[o + 10] = db;
            held[o + 11] = id;
            held[o + 12] = contexts - 1;
            held[o + 13] = smoke;
            held[o + 14] = vary ? 1 : 0;
            held[o + 15] = look.ordinal();
            held[o + 16] = lookFrame;
        }

        /**
         * Last merge of a source ({@link #close}): its pending puffs and the result. A snapshot replayed unchanged
         * gives the same puffs, and merging them pairwise again every frame took 0.5–0.7 ms of the 100-strike raid
         * frame.
         */
        private static final class Merged {
            double[] in = new double[0], out = new double[0];
            boolean[] gone = new boolean[0];
            int count;
            long frame;
        }

        private final java.util.IdentityHashMap<SmokeGrid.Source, Merged> merges = new java.util.IdentityHashMap<>();
        private long frame;
        private int reused;

        /**
         * Puffs of a source that has ended — merged among themselves ({@link #merge}). Merging is a function of the
         * pending puffs alone: the same puffs as the source's last merge take its result.
         */
        private void close() {
            int n = holding - from;
            if (n <= 0) return;
            if (merged.length < holding) merged = java.util.Arrays.copyOf(merged, 2 * holding);
            int start = from * FIELDS, length = n * FIELDS, end = start + length;
            // The frame's source number (the same for all its puffs) is not merge input; merging only carries it along.
            double context = held[start + 12];
            for (int o = start + 12; o < end; o += FIELDS) held[o] = -1;
            Merged m = merges.computeIfAbsent(ctxSource[(int) context], k -> new Merged());
            if (m.count == n && java.util.Arrays.equals(held, start, end, m.in, 0, length)) {
                System.arraycopy(m.out, 0, held, start, length);
                System.arraycopy(m.gone, 0, merged, from, n);
                reused++;
            } else {
                if (m.in.length < length) {
                    m.in = new double[length];
                    m.out = new double[length];
                    m.gone = new boolean[n];
                }
                System.arraycopy(held, start, m.in, 0, length);
                java.util.Arrays.fill(merged, from, holding, false);
                merge(held, from, holding, merged, CloudModel.SOOT_K, SmokeGrid.DROP_K);
                System.arraycopy(held, start, m.out, 0, length);
                System.arraycopy(merged, from, m.gone, 0, n);
                m.count = n;
            }
            m.frame = frame;
            for (int o = start + 12; o < end; o += FIELDS) held[o] = context;
            from = holding;
        }

        /**
         * Plan of the frame: pending puffs of the last source merged, the cloud puff limit, and each puff and place's
         * quads.
         */
        private void plan() {
            close();
            lastReused = reused;
            reused = 0;
            // Merges of sources not drawn this frame are not kept.
            merges.values().removeIf(m -> m.frame != frame);
            frame++;
            thin();
            if (blobEntry.length < holding) blobEntry = new int[2 * holding];
            if (placeEntry.length < placeCount) placeEntry = new int[2 * placeCount];
            java.util.Arrays.fill(blobEntry, 0, holding, -1);
            java.util.Arrays.fill(placeEntry, 0, placeCount, -1);
            groups.reset();
            for (int p = 0; p < placeCount; p++) groups.add(p, placeTube[p]);
            // plan: for each puff and spot — its squares on screen and the full number of puffs in the cluster
            planning = true;
            floor = 0;
            plans = 0;
            for (int i = 0; i < holding; i++) if (!merged[i]) held(i);
            for (int p = 0; p < placeCount; p++) place(p);
            planRibbons();
            lastRaw = floor;
            lastBlobs = holding;
            lastPlaces = placeCount;
            lastRefused = refused;
            lastPlaceMatter = placeMatter;
        }

        /**
         * Frame: pending cloud puffs and tube places draw as quads within {@link #FILL_BUDGET}, regardless of their
         * count. The plan estimates how many screens each puff or place covers with one quad (the floor) and with a
         * cluster. If the floor exceeds the budget:
         * <ol>
         * <li>overlapping puffs and places from different sources merge in levels using the same mass-and-moment law as
         * {@link #merge}, until the floor fits ({@link #mergeLevel});</li>
         * <li>if it still does not fit, less opaque puffs fade into the near-eye veil with the same optical depth along
         * rays ({@link Veil}), just enough for the rest to fit ({@link #fit}); the whole veil costs one screen.</li>
         * </ol>
         * Clusters then take enough puffs to fit the fill limit, each retaining its center opacity. All draw as quads;
         * the veil uses one grid.
         */
        void flush() {
            plan();
            double budget = SmokePuffs.budget;
            if (lastRaw > budget) conceal(budget);
            int was = level;
            level = 0;
            // the past frame's level is kept also when the floor is a bit under the budget (down to {@link #HYSTERESIS}
            // of it): otherwise at the budget boundary, from frame to frame, merging went on and off, and the clusters
            // flickered
            if (floor > budget || was > 0 && floor > HYSTERESIS * budget) {
                // the past frame's level is kept while the floor does not fall noticeably below the budget: otherwise
                // at the budget boundary merged puffs would separate and reconverge frame by frame
                while (level < LEVELS && (floor > budget || level < was && floor > HYSTERESIS * budget)) mergeLevel(level++);
            }
            // The last 15% of the budget fades before overflow; otherwise crossing the boundary
            // would enable or remove the entire veil of these entries at once.
            if (floor > FIT_FADE * budget) fit(budget);
            // Detail stays between two fill thresholds. Turning within the remaining capacity does not rebuild
            // clusters; after overflow, leave headroom to the next threshold. The limit is still checked against actual
            // quads in this frame, including transitions between horizontal and upright puffs.
            double previousFill = shareReady ? drawn(lastShare) : 0;
            double target = shareReady && previousFill > budget ? Math.max(floor, HYSTERESIS * budget) : budget;
            double s = 1;
            if (planned(1) > target) {
                double lo = 0, hi = 1;
                for (int it = 0; it < 16; it++) {
                    double mid = 0.5 * (lo + hi);
                    if (planned(mid) > target) hi = mid;
                    else lo = mid;
                }
                s = lo;
            }
            // estimate — by the cluster's edge; real squares (their size and spot scatter) — check: above the budget —
            // the share smaller
            for (int it = 0; it < 24 && s > 0 && drawn(s) > target; it++) s = it < 23 ? 0.8 * s : 0;
            if (shareReady && previousFill <= budget && (s <= lastShare || drawn(s) > HYSTERESIS * budget)) s = lastShare;
            planning = false;
            share = s;
            for (int i = 0; i < holding; i++) if (!merged[i]) held(i);
            for (int p = 0; p < placeCount; p++) if (!placeGone[p]) place(p);
            drawRibbons();
            lastFill = drawn(s);
            lastFloor = floor;
            lastShare = s;
            shareReady = true;
            lastItems = 0;
            for (int e = 0; e < plans; e++) if (!planDead[e] && planVeil[e] < 1) lastItems++;
            share = 1;
            forget();
            veilFlush();
        }

        /**
         * Cloud puffs more than {@link #BLOBS} (about 8 per strike: a raid of hundreds of strikes) — before the plan:
         * plan, merge and squares cost CPU per puff, and the frame's work would grow with the raid. First the ones
         * lying on top of each other merge by the same law ({@link #absorb}) in world cells of 0.5/1/2 of the edge (≈
         * 3σ), as {@link #mergeLevel}; further merging would smear the substance over the clear sky; if that was not
         * enough — the {@link #BLOBS} most visible remain (opacity of the middle × solid angle), the others are not
         * drawn (selection by contribution: they are the least visible on screen). Pending — in a row, without merged
         * ones.
         */
        private void thin() {
            int alive = compact();
            for (int level = 0; alive > BLOBS && level < LEVELS; level++) {
                double cell = LOD_CELL * (1 << level);
                cells.clear();
                for (int i = 0; i < holding; i++) {
                    if (merged[i]) continue;
                    int o = i * FIELDS;
                    boolean wet = wet(o);
                    long key = cell(held[o], held[o + 1], held[o + 2], 3 * Math.max(held[o + 3], held[o + 4]), cell, kind(o, wet));
                    int first = cells.get(key);
                    if (first < 0) {
                        cells.put(key, i);
                        continue;
                    }
                    absorb(held, first * FIELDS, o, CloudModel.SOOT_K, SmokeGrid.DROP_K);
                    if (wet) round(first * FIELDS);
                    merged[i] = true;
                    alive--;
                }
            }
            if (alive > BLOBS) {
                int n = 0;
                for (int i = 0; i < holding; i++) {
                    if (merged[i]) continue;
                    int o = i * FIELDS;
                    Vec3 eye = ctxView[(int) held[o + 12]].camera();
                    double ext = CloudModel.SOOT_K * held[o + 5] + SmokeGrid.dustK((float) held[o + 8], (float) held[o + 9], (float) held[o + 10]) * held[o + 6]
                            + SmokeGrid.DROP_K * held[o + 7], sh = held[o + 3], area = sh * Math.max(sh, held[o + 4]);
                    double dx = held[o] - eye.x, dy = held[o + 1] - eye.y, dz = held[o + 2] - eye.z;
                    double seen = held[o + 13] * -Math.expm1(-ext / (2 * Math.PI * area)) * area / Math.max(dx * dx + dy * dy + dz * dz, area);
                    if (n == order.length) order = java.util.Arrays.copyOf(order, 2 * n);
                    // from the most visible to the least: key — 2 − visibility (positive floats sort as integers)
                    order[n++] = (long) Float.floatToIntBits((float) (2 - Math.min(1, seen))) << 32 | i;
                }
                java.util.Arrays.sort(order, 0, n);
                for (int k = BLOBS; k < n; k++) merged[(int) order[k]] = true;
            }
            compact();
        }

        /** Pending cloud puffs — in a row, without merged ones (plan passes go over live ones); how many of them. */
        private int compact() {
            int k = 0;
            for (int i = 0; i < holding; i++) {
                if (merged[i]) continue;
                if (k != i) System.arraycopy(held, i * FIELDS, held, k * FIELDS, FIELDS);
                merged[k++] = false;
            }
            holding = from = k;
            return k;
        }

        /** Pending puffs, spots, sources and plan — anew (end of frame, leaving the world). */
        void forget() {
            holding = from = contexts = placeCount = placeContexts = materialCount = plans = ribbonCount = refused = 0;
            placeMatter = 0;
            java.util.Arrays.fill(ctxSource, null);
            java.util.Arrays.fill(ctxOut, null);
            java.util.Arrays.fill(ctxView, null);
            java.util.Arrays.fill(planView, null);
            java.util.Arrays.fill(placeView, null);
            java.util.Arrays.fill(placeOut, null);
        }

        /**
         * Plan entries: kind, position from the eye, edge half-axes (m), full puff count; entry floor, opacity
         * (for a tube place, the entire tube on its axis, used by {@link #fit}), veil share, near-eye and merged flags.
         */
        private FarView[] planView = new FarView[64];
        private double[] plan = new double[64 * 6], planCover0 = new double[64], planShown = new double[64],
                planVeil = new double[64], planOpacity = new double[64], planSeen = new double[64];
        private int[] planOwner = new int[64];
        private boolean[] planNear = new boolean[64], planDead = new boolean[64];
        /**
         * How the cluster's squares of a record will land on screen ({@link #drawnFill}): puff grain, growth of the
         * square by pixel (half-size on screen / edge in world), scatter of the middle vertically (m, for a cloud puff
         * — by the layer's thickness), whether the puff lies, whether a tube spot (and a single puff — with its own
         * size 0.8–1.2).
         */
        private long[] planSeed = new long[64];
        private double[] planGrow = new double[64], planOffV = new double[64], planFlat = new double[64];
        private boolean[] planPlace = new boolean[64];
        private int plans;

        /**
         * Add the cluster of puff or place {@link #current} to the frame plan: position from the eye, edge half-axes
         * (m), full puff count. near means the eye is within its edge (some material is in the veil; these entries do
         * not merge); shown is opacity (for a tube place, the entire tube on its axis).
         */
        private void planCluster(FarView view, double px, double py, double pz, double ra, double rb, double most, boolean near,
                                 double opacity, double shown, long seed, double grow, double offV, double lying, boolean place) {
            if (plans == planView.length) {
                int n = 2 * plans;
                planView = java.util.Arrays.copyOf(planView, n);
                plan = java.util.Arrays.copyOf(plan, 6 * n);
                planCover0 = java.util.Arrays.copyOf(planCover0, n);
                planShown = java.util.Arrays.copyOf(planShown, n);
                planVeil = java.util.Arrays.copyOf(planVeil, n);
                planOpacity = java.util.Arrays.copyOf(planOpacity, n);
                planSeen = java.util.Arrays.copyOf(planSeen, n);
                planOwner = java.util.Arrays.copyOf(planOwner, n);
                planNear = java.util.Arrays.copyOf(planNear, n);
                planDead = java.util.Arrays.copyOf(planDead, n);
                planSeed = java.util.Arrays.copyOf(planSeed, n);
                planGrow = java.util.Arrays.copyOf(planGrow, n);
                planOffV = java.util.Arrays.copyOf(planOffV, n);
                planFlat = java.util.Arrays.copyOf(planFlat, n);
                planPlace = java.util.Arrays.copyOf(planPlace, n);
            }
            planView[plans] = view;
            int o = 6 * plans;
            plan[o] = px;
            plan[o + 1] = py;
            plan[o + 2] = pz;
            plan[o + 3] = ra;
            plan[o + 4] = rb;
            plan[o + 5] = most;
            planSeed[plans] = seed;
            planGrow[plans] = grow;
            planOffV[plans] = offV;
            planFlat[plans] = lying;
            planPlace[plans] = place;
            double c = drawnFill(plans, 0);
            planCover0[plans] = c;
            planShown[plans] = shown;
            planOpacity[plans] = opacity;
            planSeen[plans] = 1;
            planOwner[plans] = current;
            planVeil[plans] = 0;
            planNear[plans] = near;
            planDead[plans] = false;
            if (current >= 0) blobEntry[current] = plans;
            else if (current != Integer.MIN_VALUE) placeEntry[~current] = plans;
            floor += c;
            plans++;
        }

        /** Record e is no longer drawn (its puff or spot is merged; the survivor's plan — anew). */
        private void drop(int e) {
            if (e < 0 || planDead[e]) return;
            planDead[e] = true;
            if (planVeil[e] < 1) floor -= planCover0[e];
        }

        /** Fill of all plan records that stayed as squares, at cluster share s, in screens. */
        private double planned(double s) {
            double f = 0;
            for (int e = 0; e < plans; e++) {
                if (planDead[e] || planVeil[e] >= 1) continue;
                int o = 6 * e;
                f += plan[o + 5] <= 1 || 1 + (plan[o + 5] - 1) * s <= 1 + 1e-9 ? planCover0[e]
                        : clusterFill(planView[e], plan[o], plan[o + 1], plan[o + 2], plan[o + 3], plan[o + 4], plan[o + 5], s);
            }
            return f;
        }

        /**
         * How many screens the squares of the cluster of record e will fill at share s — the same squares that
         * {@link #blob} or {@link #club} will draw: their number ⌈1 + (most − 1)·s⌉, size with scatter 0.8–1.2 by
         * grain, middle offset by grain, for a lying one — height on screen by its own spot ({@link #cover} of each,
         * cut by the frame).
         */
        private double drawnFill(int e, double s) {
            int o = 6 * e;
            FarView v = planView[e];
            double px = plan[o], py = plan[o + 1], pz = plan[o + 2], ra = plan[o + 3], rb = plan[o + 4], most = plan[o + 5], grow = planGrow[e];
            double many = 1 + (most - 1) * s;
            int count = (int) Math.ceil(many - 1e-9);
            double size = clusterSize(many), spread = 1 - size;
            double rh = ra / grow, f = 0;
            for (int j = 0; j < count; j++) {
                long hj = mix(planSeed[e] + j * 0x632BE59BD9B4E019L);
                double k = clubScale(many, hj, planPlace[e]);
                double ox = clubOffset(planSeed[e], j, 0) * spread * rh * 0.5,
                        oy = (1 - planFlat[e]) * clubOffset(planSeed[e], j, 7) * spread * planOffV[e] * 0.5,
                        oz = clubOffset(planSeed[e], j, 14) * spread * rh * 0.5;
                double qx = px + ox, qy = py + oy, qz = pz + oz;
                if (planFlat[e] > 0) f += flatFill(v, qx, qy, qz, ra * k, ra * k, clubAngle(hj));
                if (planFlat[e] < 1) {
                    ua.zentix.incoming.client.fx.layer.PuffGeometry.face(qx, qy, qz, ra * k, rb * k, flat);
                    f += polygonFill(v, flat);
                }
            }
            return f;
        }

        /**
         * Horizontal puff ({@code PuffGeometry.flat}: a horizontal quad at a seeded angle, half-sizes a, b),
         * centered at (x, y, z) from the eye: screen fraction from its four vertices ({@link #polygonFill}).
         */
        private final double[] flat = new double[12];

        private double flatFill(FarView v, double x, double y, double z, double a, double b, double angle) {
            ua.zentix.incoming.client.fx.layer.PuffGeometry.flat(x, y, z, a, b, angle, flat);
            return polygonFill(v, flat);
        }

        /**
         * What the squares of all plan records that stayed as squares will fill, at share s ({@link #drawnFill}), in
         * screens.
         */
        private double drawn(double s) {
            double f = 0;
            for (int e = 0; e < plans; e++) if (!planDead[e] && planVeil[e] < 1)
                f += s == 0 || plan[6 * e + 5] <= 1 ? planCover0[e] : drawnFill(e, s);
            return f;
        }

        /** Budget selection order. Priority belongs to the puff, not a screen cell. */
        private long[] order = new long[64];

        /**
         * World-direction grid. Narrower projections refine it without rotating its rays with the camera.
         * Nodes coincide at face boundaries; conservative bilinear sampling is continuous there too.
         */
        private static final int OCCLUSION_BASE_CELLS = 48, OCCLUSION_VIEW_CELLS = 24, OCCLUSION_MAX_CELLS = 256;
        private int occlusionCells = OCCLUSION_BASE_CELLS, occlusionSide = occlusionCells + 1,
                occlusionFace = occlusionSide * occlusionSide;
        /**
         * Presentation: map core and smooth edge band, like the previous 0.6 half-size used for screen-space occlusion.
         */
        private static final double OCCLUSION_CORE = 0.6, OCCLUSION_INNER = 0.8;
        private double[] occlusion = new double[6 * occlusionFace];
        private double[] occlusionRays = occlusionRays();
        /** Nodes any core query reads ({@link #reserveOcclusion}), a bit per node: the only ones deposited into. */
        private long[] occlusionNodes = new long[words(6 * occlusionFace)];
        /** In-cell offsets of the last {@link #occlusionCell}. */
        private double cellU, cellV;
        /**
         * Per entry in distance order ({@link #conceal}): position from the eye, distance, view frame (forward,
         * horizontal across, up) and core half-axes, computed once for its queries and its deposit.
         */
        private static final int CONCEAL_FIELDS = 14;
        private double[] concealFrame = new double[64 * CONCEAL_FIELDS];
        private static final double[] RING_X = {1, 0.7071067811865476, 0, -0.7071067811865476, -1, -0.7071067811865476, 0, 0.7071067811865476},
                RING_Y = {0, 0.7071067811865476, 1, 0.7071067811865476, 0, -0.7071067811865476, -1, -0.7071067811865476};

        /** The next plan continues from's occlusion grid tier (its hysteresis), with storage of that size. */
        void adoptOcclusion(Draw from) {
            if (from.occlusionCells == occlusionCells) return;
            occlusionCells = from.occlusionCells;
            occlusionSide = occlusionCells + 1;
            occlusionFace = occlusionSide * occlusionSide;
            if (occlusion.length < 6 * occlusionFace) {
                occlusion = new double[6 * occlusionFace];
                occlusionNodes = new long[words(6 * occlusionFace)];
            }
            occlusionRays = occlusionRays();
        }

        private void configureOcclusion() {
            // Target 24 cells across the projected diagonal, with a 48-cell floor for conservative core coverage.
            // Only queried nodes are deposited into; the cap also bounds storage at extreme zoom.
            double needed = OCCLUSION_VIEW_CELLS / Math.tan(view.edge());
            int cells = OCCLUSION_BASE_CELLS;
            while (cells < OCCLUSION_MAX_CELLS && cells < needed) cells = Math.min(OCCLUSION_MAX_CELLS, 2 * cells);
            // Animated FOV must not rebuild arrays every frame or alternate tiers near their boundary.
            if (cells < occlusionCells && needed > HYSTERESIS * cells) return;
            if (cells == occlusionCells) return;
            occlusionCells = cells;
            occlusionSide = cells + 1;
            occlusionFace = occlusionSide * occlusionSide;
            if (occlusion.length < 6 * occlusionFace) {
                occlusion = new double[6 * occlusionFace];
                occlusionNodes = new long[words(6 * occlusionFace)];
            }
            occlusionRays = occlusionRays();
        }

        private static int words(int bits) {
            return (bits + 63) >>> 6;
        }

        /** First reserved node in [from, last], or −1. */
        private int nextNode(int from, int last) {
            if (from > last) return -1;
            int w = from >>> 6, end = last >>> 6;
            long word = occlusionNodes[w] & -1L << from;
            while (word == 0) {
                if (++w > end) return -1;
                word = occlusionNodes[w];
            }
            int q = w << 6 | Long.numberOfTrailingZeros(word);
            return q <= last ? q : -1;
        }

        private double[] occlusionRays() {
            double[] rays = occlusionRays;
            if (rays == null || rays.length < 18 * occlusionFace) rays = new double[18 * occlusionFace];
            for (int face = 0; face < 6; face++) for (int j = 0; j < occlusionSide; j++) for (int i = 0; i < occlusionSide; i++) {
                double u = 2.0 * i / occlusionCells - 1, v = 2.0 * j / occlusionCells - 1;
                double sign = (face & 1) == 0 ? 1 : -1;
                int axis = face / 2, q = 3 * (face * occlusionFace + j * occlusionSide + i);
                rays[q] = axis == 0 ? sign : u;
                rays[q + 1] = axis == 0 ? u : axis == 1 ? sign : v;
                rays[q + 2] = axis == 2 ? sign : v;
            }
            return rays;
        }

        /**
         * Bilinear cell of a direction: its base node q (the cell's nodes are q, q + 1, q + side, q + side + 1), with
         * the in-cell offsets in {@link #cellU}, {@link #cellV}; −1 for a zero direction.
         */
        private int occlusionCell(double x, double y, double z) {
            double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z), m = Math.max(ax, Math.max(ay, az));
            if (!(m > 0)) return -1;
            int face = m == ax ? (x > 0 ? 0 : 1) : m == ay ? (y > 0 ? 2 : 3) : (z > 0 ? 4 : 5);
            double u = (face < 2 ? y : x) / m, v = (face < 4 ? z : y) / m;
            double s = Math.clamp((u + 1) * 0.5 * occlusionCells, 0, occlusionCells),
                    t = Math.clamp((v + 1) * 0.5 * occlusionCells, 0, occlusionCells);
            int i = Math.min((int) s, occlusionCells - 1), j = Math.min((int) t, occlusionCells - 1);
            cellU = s - i;
            cellV = t - j;
            return face * occlusionFace + j * occlusionSide + i;
        }

        /** Optical depth in a direction, including between nodes and across cube-face boundaries. */
        private double occlusion(double x, double y, double z) {
            int q = occlusionCell(x, y, z);
            if (q < 0) return 0;
            double du = cellU, dv = cellV;
            return Math.min(12, (1 - dv) * ((1 - du) * occlusion[q] + du * occlusion[q + 1])
                    + dv * ((1 - du) * occlusion[q + occlusionSide] + du * occlusion[q + occlusionSide + 1]));
        }

        /**
         * The entry e at distance rank k: its view frame and core, and the four nodes of each of its possible queries
         * (at most 36 at any zoom).
         */
        private void reserveOcclusion(int e, int k) {
            int o = 6 * e, g = CONCEAL_FIELDS * k;
            double x = plan[o], y = plan[o + 1], z = plan[o + 2], h = Math.hypot(x, z), d = Math.hypot(h, y);
            double fx = x / d, fy = y / d, fz = z / d;
            double hx = h > 1e-9 ? z / h : 1, hz = h > 1e-9 ? -x / h : 0;
            double ux = fy * hz, uy = fz * hx - fx * hz, uz = -fy * hx;
            double size = planPlace[e] ? clubScale(1, mix(planSeed[e]), true) : 1;
            double ra = OCCLUSION_CORE * plan[o + 3] * size, rb = OCCLUSION_CORE * plan[o + 4] * size;
            double[] f = concealFrame;
            f[g] = x;
            f[g + 1] = y;
            f[g + 2] = z;
            f[g + 3] = d;
            f[g + 4] = fx;
            f[g + 5] = fy;
            f[g + 6] = fz;
            f[g + 7] = hx;
            f[g + 8] = hz;
            f[g + 9] = ux;
            f[g + 10] = uy;
            f[g + 11] = uz;
            f[g + 12] = ra;
            f[g + 13] = rb;
            reserve(x, y, z);
            if (planNear[e] || planFlat[e] > 0) return;
            for (int j = 0; j < 8; j++) {
                double a = ra * RING_X[j], b = rb * RING_Y[j];
                reserve(x + a * hx + b * ux, y + b * uy, z + a * hz + b * uz);
            }
        }

        private void reserve(double x, double y, double z) {
            int q = occlusionCell(x, y, z);
            if (q < 0) return;
            mark(q);
            mark(q + 1);
            mark(q + occlusionSide);
            mark(q + occlusionSide + 1);
        }

        private void mark(int q) {
            occlusionNodes[q >>> 6] |= 1L << q;
        }

        /**
         * Puffs run in distance order from the eye. A distant puff has nine core rays fixed by its geometry; optical
         * depth accumulated by nearer puffs on all nine rays hides it smoothly between 2 and 3 (14% to 5% light). The
         * grid stores total overlap from any sources, with no "first N puffs" limit. Horizontal puffs and material near
         * the eye are not discarded. Cost is covered angular cells times a bounded number of plan entries.
         */
        private void conceal(double budget) {
            configureOcclusion();
            java.util.Arrays.fill(occlusionNodes, 0, words(6 * occlusionFace), 0);
            // Occlusion is unnecessary with spare budget; beyond the limit it ramps in smoothly instead of switching
            // the whole frame.
            double strength = smoothstep(budget, 1.25 * budget, lastRaw);
            int n = 0;
            for (int e = 0; e < plans; e++) {
                if (planDead[e] || planOwner[e] == Integer.MIN_VALUE) continue;
                int o = 6 * e;
                double d2 = plan[o] * plan[o] + plan[o + 1] * plan[o + 1] + plan[o + 2] * plan[o + 2];
                if (!(d2 > 0)) continue;
                if (n == order.length) order = java.util.Arrays.copyOf(order, 2 * n);
                order[n++] = (long) Float.floatToIntBits((float) d2) << 32 | e;
            }
            java.util.Arrays.sort(order, 0, n);
            if (concealFrame.length < CONCEAL_FIELDS * n) concealFrame = new double[CONCEAL_FIELDS * Math.max(n, 2 * concealFrame.length / CONCEAL_FIELDS)];
            for (int k = 0; k < n; k++) reserveOcclusion((int) order[k], k);
            java.util.Arrays.fill(occlusion, 0, 6 * occlusionFace, 0);
            double inner = OCCLUSION_INNER * OCCLUSION_INNER;
            for (int k = 0; k < n; k++) {
                int e = (int) order[k], g = CONCEAL_FIELDS * k;
                double[] f = concealFrame;
                double x = f[g], y = f[g + 1], z = f[g + 2], d = f[g + 3], fx = f[g + 4], fy = f[g + 5], fz = f[g + 6];
                double hx = f[g + 7], hz = f[g + 8], ux = f[g + 9], uy = f[g + 10], uz = f[g + 11], ra = f[g + 12], rb = f[g + 13];
                double least = occlusion(x, y, z);
                planSeen[e] = 1 - strength * (1 - Math.exp(-least));
                double hidden = 0;
                if (!planNear[e] && planFlat[e] == 0) {
                    for (int j = 0; j < 8 && least > 2; j++) {
                        double a = ra * RING_X[j], b = rb * RING_Y[j];
                        least = Math.min(least, occlusion(x + a * hx + b * ux, y + b * uy, z + a * hz + b * uz));
                    }
                    hidden = strength * smoothstep(2, 3, least);
                }
                if (hidden >= 1) {
                    drop(e);
                    int owner = planOwner[e];
                    if (owner >= 0) merged[owner] = true;
                    else placeGone[~owner] = true;
                    continue;
                }
                if (hidden > 0) {
                    // Visibility belongs to the draw entry, so both merging and budget selection account for it.
                    // Otherwise nearly hidden places kept their full budget priority and prevented their own merging.
                    double keep = 1 - hidden;
                    int owner = planOwner[e];
                    if (owner >= 0) held[owner * FIELDS + 13] *= keep;
                    else places[~owner * PLACE_FIELDS + 12] *= keep;
                    planShown[e] *= keep;
                }
                if (!(ra > 0) || !(rb > 0) || planFlat[e] > 0) continue;
                // Ray length cancels in the plane ratios. Scale the axes once and reject outside nodes before dividing.
                double sa = d / ra, sb = d / rb;
                double ax = sa * hx, az = sa * hz, bx = sb * ux, by = sb * uy, bz = sb * uz;
                double radius = Math.max(ra, rb);
                // The normalized ellipse radius changes by at most (d + radius^2/d)/min(ra,rb) per radian
                // while inside the core (ray/plane Jacobian d*sec(theta)^2). A node influences directions at
                // most 2*sqrt(2)/cells radians away, on either side of a cube seam. Erode by that footprint:
                // every deposited value then bounds the original kernel from below throughout its bilinear support.
                double margin = (d + radius * radius / d) / Math.min(ra, rb) * (2 * Math.sqrt(2) / occlusionCells);
                if (margin >= 1) continue;
                double tau = -Math.log1p(-Math.min(planOpacity[e], 0.999)) * (1 - hidden);
                if (!(tau > 0)) continue;
                double remaining2 = (1 - margin) * (1 - margin);
                radius *= 1 - margin;
                // Core-sphere bounds on each face only limit traversal; the actual plane determines the contribution.
                for (int face = 0; face < 6; face++) {
                    int axis = face / 2;
                    double sign = (face & 1) == 0 ? 1 : -1;
                    double c = sign * (axis == 0 ? x : axis == 1 ? y : z);
                    if (c + radius <= 0) continue;
                    double u = axis == 0 ? y : x, v = axis == 2 ? y : z;
                    // A cube face is the pyramid |u|≤c, |v|≤c. Distance to its side plane is (|u|−c)/√2. Without this
                    // check a puff near the horizon traversed both entire ±Y faces, even though it touched neither.
                    if (Math.abs(u) - c > Math.sqrt(2) * radius || Math.abs(v) - c > Math.sqrt(2) * radius) continue;
                    double u0 = -1, u1 = 1, v0 = -1, v1 = 1;
                    if (c > radius) {
                        double near = c - radius, far = c + radius;
                        u0 = Math.min((u - radius) / near, (u - radius) / far);
                        u1 = Math.max((u + radius) / near, (u + radius) / far);
                        v0 = Math.min((v - radius) / near, (v - radius) / far);
                        v1 = Math.max((v + radius) / near, (v + radius) / far);
                    }
                    int i0 = Math.max(0, (int) Math.floor((u0 + 1) * 0.5 * occlusionCells)),
                            i1 = Math.min(occlusionCells, (int) Math.ceil((u1 + 1) * 0.5 * occlusionCells)),
                            j0 = Math.max(0, (int) Math.floor((v0 + 1) * 0.5 * occlusionCells)),
                            j1 = Math.min(occlusionCells, (int) Math.ceil((v1 + 1) * 0.5 * occlusionCells));
                    // Along a row the node ray is (row constant) + u·(face axis), so den, a and b are affine in u and
                    // the core is the interval where a² + b² − remaining²·den² < 0. Scanning only that interval,
                    // widened by a node on each side (far beyond rounding), visits the same deposits as the whole box.
                    double fc, fu, fv, ac, au, av, bc, bu, bv;
                    if (axis == 0) {
                        fc = fx * sign; fu = fy; fv = fz; ac = ax * sign; au = 0; av = az; bc = bx * sign; bu = by; bv = bz;
                    } else if (axis == 1) {
                        fc = fy * sign; fu = fx; fv = fz; ac = 0; au = ax; av = az; bc = by * sign; bu = bx; bv = bz;
                    } else {
                        fc = fz * sign; fu = fx; fv = fy; ac = az * sign; au = ax; av = 0; bc = bz * sign; bu = bx; bv = by;
                    }
                    double qa = au * au + bu * bu - remaining2 * fu * fu;
                    double scale = au * au + bu * bu + remaining2 * fu * fu + ac * ac + bc * bc + remaining2 * fc * fc + av * av + bv * bv
                            + remaining2 * fv * fv;
                    // A row along the cone's side has no bounded interval: scan its whole span.
                    boolean bounded = qa > 1e-6 * scale && i1 - i0 > 3;
                    for (int j = j0; j <= j1; j++) {
                        int lo = i0, hi = i1;
                        if (bounded) {
                            double w = 2.0 * j / occlusionCells - 1;
                            double d0 = fc + fv * w, a0 = ac + av * w, b0 = bc + bv * w;
                            double qb = 2 * (a0 * au + b0 * bu - remaining2 * d0 * fu), qc = a0 * a0 + b0 * b0 - remaining2 * d0 * d0;
                            double disc = qb * qb - 4 * qa * qc, mid = -qb / (2 * qa), half = disc > 0 ? Math.sqrt(disc) / (2 * qa) : 0;
                            // Clamped as doubles: a far root must not overflow an int; NaN keeps the whole span.
                            double from = Math.floor((mid - half + 1) * 0.5 * occlusionCells) - 1, to = Math.ceil((mid + half + 1) * 0.5 * occlusionCells) + 1;
                            if (from > i0) lo = from > i1 ? i1 + 1 : (int) from;
                            if (to < i1) hi = to < i0 ? i0 - 1 : (int) to;
                        }
                        int row = face * occlusionFace + j * occlusionSide, last = row + hi;
                        for (int q = nextNode(row + lo, last); q >= 0; q = nextNode(q + 1, last)) {
                            int r = 3 * q;
                            double rx = occlusionRays[r], ry = occlusionRays[r + 1], rz = occlusionRays[r + 2];
                            double den = fx * rx + fy * ry + fz * rz;
                            if (!(den > 0)) continue;
                            double a = ax * rx + az * rz, b = bx * rx + by * ry + bz * rz;
                            double numerator = a * a + b * b, denominator = den * den;
                            if (numerator >= denominator * remaining2) continue;
                            double rho = Math.sqrt(numerator / denominator) + margin, rho2 = rho * rho;
                            // Inside the inner core the edge weight is exactly 1.
                            occlusion[q] += rho2 <= inner ? tau : tau * (1 - smoothstep(inner, 1, rho2));
                        }
                    }
                }
            }
        }

        /**
         * If the floor remains above budget after merging, keep the most opaque entries as quads ({@link #planShown};
         * for a tube place, the entire tube on its axis: a thin dark trail is visible even when each place contributes
         * little) while their floor fits. The rest enters the near-eye veil with the same optical depth along rays;
         * ribbons are not drawn. Entries selected in the last 15% of the budget ({@link #FIT_FADE}) fade into the veil
         * smoothly, without a jump at the threshold. Previously the band spanned twice the visibility threshold, so a
         * uniform scene (1000 identical tubes) faded every selected entry and spent its budget on invisible geometry.
         * Dense foreground puffs retain the cloud's appearance; previously the largest entries entered the veil,
         * turning the edge of a smoke wall into uniform haze. The fill limit therefore holds by construction.
         */
        private void fit(double budget) {
            int n = 0;
            for (int e = 0; e < plans; e++) {
                if (planDead[e] || planVeil[e] >= 1) continue;
                if (n == order.length) order = java.util.Arrays.copyOf(order, 2 * n);
                // by visible opacity from larger to smaller: key — 2 − visible (positive floats sort as integers)
                order[n++] = (long) Float.floatToIntBits((float) (2 - planShown[e] * planSeen[e])) << 32 | e;
            }
            java.util.Arrays.sort(order, 0, n);
            double sum = 0;
            int cut = 0;
            while (cut < n && sum + planCover0[(int) order[cut]] <= budget) sum += planCover0[(int) order[cut++]];
            double acc = 0;
            for (int k = 0; k < n; k++) {
                int e = (int) order[k];
                if (k >= cut) {
                    planVeil[e] = 1;
                    continue;
                }
                // The last 15% of the budget (the least visible selected entries) fades smoothly, so puffs do not pop
                // at the threshold.
                acc += planCover0[e];
                planVeil[e] = smoothstep(FIT_FADE * budget, budget, acc);
            }
            floor = sum;
        }

        /**
         * Share of the substance of cluster or spot {@link #current} that goes into the veil, not as squares
         * ({@link #fit}).
         */
        private double veilShare() {
            int e = current >= 0 ? blobEntry[current] : placeEntry[~current];
            return e < 0 ? 0 : planVeil[e];
        }

        /** Whether to merge the puff or spot into record e: a square exists, not merged, the eye not at its edge. */
        private boolean mergeable(int e) {
            return e >= 0 && !planDead[e] && !planNear[e] && planVeil[e] == 0;
        }

        /**
         * Kind of puff with o for merge cells: look ({@link SmokeGrid.Look}: own frames) and whether spray — different
         * ones do not merge.
         */
        private int kind(int o, boolean wet) {
            return 2 * (int) held[o + 15] + (wet ? 1 : 0);
        }

        /**
         * Whether the puff with o is spray: a source with spray has more droplets than smoke and dust — own frames,
         * does not merge with smoke.
         */
        private boolean wet(int o) {
            return ctxSprays[(int) held[o + 12]] && SmokeGrid.DROP_K * held[o + 7] > CloudModel.SOOT_K * held[o + 5]
                    + SmokeGrid.dustK((float) held[o + 8], (float) held[o + 9], (float) held[o + 10]) * held[o + 6];
        }

        /**
         * A merged spray puff with o — round (a jet of one direction is of round section): a stretched Gaussian layer
         * draws with a stretched frame — a strip with straight sides, a “sheet” ({@code WaterSalvoTest}); σ — average
         * over the axes.
         */
        private void round(int o) {
            double r = Math.sqrt((2 * held[o + 3] * held[o + 3] + held[o + 4] * held[o + 4]) / 3);
            held[o + 3] = r;
            held[o + 4] = r;
        }

        /** Survivor of merge i — into the list for the plan anew (n — how many are already in it). */
        private int dirty(int i, int n) {
            if (isDirty.length <= i) isDirty = java.util.Arrays.copyOf(isDirty, 2 * (i + 1));
            if (isDirty[i]) return n;
            if (n == dirty.length) dirty = java.util.Arrays.copyOf(dirty, 2 * n);
            isDirty[i] = true;
            dirty[n] = i;
            return n + 1;
        }

        /**
         * Merge level of different sources: cloud puffs (and spots of one substance), whose centers are in one cell of
         * the world at {@link #LOD_CELL}·2^level of their edge (cells — by octaves of the edge: size — to within a
         * factor of two), merge by the same law as {@link #merge} (mass, center and moments; spots —
         * {@link #absorbPlace}). Cells — in the world, not on screen: the view's turn does not touch them, and merged
         * ones do not flicker.
         */
        private void mergeLevel(int level) {
            double cell = LOD_CELL * (1 << level);
            int dirt = 0;
            cells.clear();
            for (int i = 0; i < holding; i++) {
                if (merged[i]) continue;
                int e = blobEntry[i];
                if (!mergeable(e)) continue;
                int o = i * FIELDS;
                boolean wet = wet(o);
                long key = cell(held[o], held[o + 1], held[o + 2], Math.max(plan[6 * e + 3], plan[6 * e + 4]), cell, kind(o, wet));
                int first = cells.get(key);
                if (first < 0) {
                    cells.put(key, i);
                    continue;
                }
                absorb(held, first * FIELDS, o, CloudModel.SOOT_K, SmokeGrid.DROP_K);
                if (wet) round(first * FIELDS);
                merged[i] = true;
                drop(e);
                dirt = dirty(first, dirt);
            }
            for (int k = 0; k < dirt; k++) {
                int i = dirty[k];
                isDirty[i] = false;
                drop(blobEntry[i]);
                blobEntry[i] = -1;
                held(i);
            }
            dirt = 0;
            cells.clear();
            for (int p = 0; p < placeCount; p++) {
                if (placeGone[p]) continue;
                int e = placeEntry[p], o = p * PLACE_FIELDS;
                if (!mergeable(e) || places[o + P_MATERIAL] < 0) continue;
                Vec3 cam = placeView[(int) places[o + P_CONTEXT]].camera();
                long base = cell(cam.x + places[o], cam.y + places[o + 1], cam.z + places[o + 2], Math.max(plan[6 * e + 3], plan[6 * e + 4]), cell,
                        2 + (int) places[o + P_MATERIAL]);
                for (int slot = 0; slot < SLOTS; slot++) {
                    long key = mix(base + slot);
                    int first = cells.get(key);
                    if (first < 0) {
                        cells.put(key, p);
                        break;
                    }
                    // a tube spot already in the group (and after past levels) — goes to the next: spots of one tube do
                    // not merge
                    if (!groups.disjoint(first, p)) continue;
                    absorbPlace(first, p);
                    groups.join(first, p);
                    placeGone[p] = true;
                    drop(e);
                    dirt = dirty(first, dirt);
                    break;
                }
            }
            for (int k = 0; k < dirt; k++) {
                int p = dirty[k];
                isDirty[p] = false;
                drop(placeEntry[p]);
                placeEntry[p] = -1;
                place(p);
            }
        }

        /**
         * Spot b — into spot a: neighboring tubes of one substance (trails of neighboring fires, a salvo's trails) —
         * one tube: attenuation — by sum, center — average over it, axis — average; σ² — average σ² plus half of the
         * spread of centers across the axis (the section is round), piece of axis — from the larger of the pieces to
         * their sum by the offset along the axis; thickness across — by them; frame of the puff and visible share —
         * averages by attenuation; grain, look and tube — that of the one with more substance.
         */
        private void absorbPlace(int pa, int pb) {
            int a = pa * PLACE_FIELDS, b = pb * PLACE_FIELDS;
            double ea = placeExt(a), eb = placeExt(b), w = ea + eb;
            if (!(w > 0)) return;
            double fa = ea / w, fb = eb / w;
            double turn = places[a + 15] * places[b + 15] + places[a + 16] * places[b + 16] + places[a + 17] * places[b + 17] < 0 ? -fb : fb;
            double ax = fa * places[a + 15] + turn * places[b + 15], ay = fa * places[a + 16] + turn * places[b + 16],
                    az = fa * places[a + 17] + turn * places[b + 17], an = Math.sqrt(ax * ax + ay * ay + az * az);
            if (an > 0) {
                ax /= an;
                ay /= an;
                az /= an;
            }
            double dx = places[b] - places[a], dy = places[b + 1] - places[a + 1], dz = places[b + 2] - places[a + 2];
            double d2 = dx * dx + dy * dy + dz * dz, par = Math.abs(dx * ax + dy * ay + dz * az), perp2 = Math.max(0, d2 - par * par);
            double sa = places[a + 3], sb = places[b + 3], la = places[a + 6], lb = places[b + 6];
            // across the axis, the middles are offset from the common one by fb·d and fa·d
            double sigma = Math.sqrt(fa * (sa * sa + 0.5 * fb * fb * perp2) + fb * (sb * sb + 0.5 * fa * fa * perp2));
            double len = Math.clamp(par + 0.5 * (la + lb), Math.max(la, lb), la + lb);
            // the share of puffs in the whole tube thickness (the rest — the ribbon) — average: the edge — by the
            // thickness of the whole tube
            double clubs = fa * places[a + 4] / places[a + 5] + fb * places[b + 4] / places[b + 5];
            double tau = w / (Math.sqrt(2 * Math.PI) * sigma * len);
            if (eb > ea) {
                placeSeed[pa] = placeSeed[pb];
                places[a + P_CONTEXT] = places[b + P_CONTEXT];
            }
            places[a] += fb * dx;
            places[a + 1] += fb * dy;
            places[a + 2] += fb * dz;
            places[a + 3] = sigma;
            places[a + 4] = tau;
            places[a + 5] = tau / clubs;
            places[a + 6] = len;
            places[a + 11] = fa * places[a + 11] + fb * places[b + 11];
            places[a + 12] = fa * places[a + 12] + fb * places[b + 12];
            places[a + 13] = Math.max(places[a + 13], places[b + 13]);
            places[a + 14] = Math.max(places[a + 14], places[b + 14]);
            places[a + 15] = ax;
            places[a + 16] = ay;
            places[a + 17] = az;
            double px = places[a], py = places[a + 1], pz = places[a + 2];
            places[a + P_AIR] = placeView[(int) places[a + P_CONTEXT]].air(Math.sqrt(px * px + py * py + pz * pz), py);
        }

        /** Attenuation of the substance of spot with o, m²: thickness across the axis × √(2π)σ × its piece of axis. */
        private double placeExt(int o) {
            return places[o + 4] * Math.sqrt(2 * Math.PI) * places[o + 3] * places[o + 6];
        }

        /** Pending puff i — into {@link #blob} with the look of its source. */
        private void held(int i) {
            int o = i * FIELDS, c = (int) held[o + 12];
            ctx = c;
            source = ctxSource[c];
            out = ctxOut[c];
            view = ctxView[c];
            age = ctxAge[c];
            sprayAge = ctxSprayAge[c];
            seed = ctxSeed[c];
            sprays = ctxSprays[c];
            look = LOOKS[(int) held[o + 15]];
            lookFrame = held[o + 16];
            current = i;
            blob(held[o], held[o + 1], held[o + 2], held[o + 3], held[o + 4], held[o + 5], held[o + 6], held[o + 7], (float) held[o + 8],
                    (float) held[o + 9], (float) held[o + 10], (int) held[o + 11], held[o + 13], held[o + 14] > 0.5);
        }

        /**
         * Tube spot — into the wait for the frame: middle (px, py, pz) from the eye, section σ, thickness across the
         * axis that the puffs draw (tau), and of the whole tube (edgeTau), piece of axis along, color of the thick
         * layer, shade level, puff frame, grain h, visible share vis, puffs in the cluster — not more than most, vary —
         * own frame and reflection for a single one; axis (ax, ay, az); tube — the tube (its spots do not merge among
         * themselves). 0 — spot not visible (pale, in haze, out of frame and not at the eye), otherwise 1 (and one
         * more, if the eye is at its edge: veil) — for the budget of tube puffs ({@code FarFlightView.CLUB_BUDGET}).
         */
        int holdPlace(FarView view, Out out, double px, double py, double pz, double sigma, double tau, double edgeTau, double along, float cr,
                      float cg, float cb, double shade, double age, long h, double vis, int most, boolean vary, double ax, double ay, double az,
                      long tube) {
            if (1 - Math.exp(-tau) < 0.004) return 0;
            double r = edge(edgeTau) * sigma, d = Math.sqrt(px * px + py * py + pz * pz), air = view.air(d, py);
            if (air < Sight.THRESHOLD) return 0;
            boolean near = d < r;
            if (!near && !FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return 0;
            int ctx = context(view, out);
            if (placeCount == placeSeed.length) {
                int n = 2 * placeCount;
                places = java.util.Arrays.copyOf(places, n * PLACE_FIELDS);
                placeSeed = java.util.Arrays.copyOf(placeSeed, n);
                placeTube = java.util.Arrays.copyOf(placeTube, n);
                placeGone = java.util.Arrays.copyOf(placeGone, n);
            }
            int o = placeCount * PLACE_FIELDS;
            places[o] = px;
            places[o + 1] = py;
            places[o + 2] = pz;
            places[o + 3] = sigma;
            places[o + 4] = tau;
            places[o + 5] = edgeTau;
            places[o + 6] = along;
            places[o + 7] = cr;
            places[o + 8] = cg;
            places[o + 9] = cb;
            places[o + 10] = shade;
            places[o + 11] = age;
            places[o + 12] = vis;
            places[o + 13] = most;
            places[o + 14] = vary ? 1 : 0;
            places[o + 15] = ax;
            places[o + 16] = ay;
            places[o + 17] = az;
            places[o + P_CONTEXT] = ctx;
            places[o + P_MATERIAL] = material(cr, cg, cb, shade);
            places[o + P_AIR] = air;
            placeSeed[placeCount] = h;
            placeTube[placeCount] = tube;
            placeGone[placeCount] = false;
            placeCount++;
            placeMatter += tau * Math.sqrt(2 * Math.PI) * sigma * along;
            return near ? 2 : 1;
        }

        /**
         * Index of the frame's spot substance with color (cr, cg, cb) and shade level shade; −1 — more substances than
         * {@link #MATERIALS} (does not merge).
         */
        private int material(float cr, float cg, float cb, double shade) {
            float s = (float) shade;
            for (int m = 0; m < materialCount; m++) {
                int o = 4 * m;
                if (materials[o] == cr && materials[o + 1] == cg && materials[o + 2] == cb && materials[o + 3] == s) return m;
            }
            if (materialCount == MATERIALS) return -1;
            int o = 4 * materialCount;
            materials[o] = cr;
            materials[o + 1] = cg;
            materials[o + 2] = cb;
            materials[o + 3] = s;
            return materialCount++;
        }

        /** Look and sink of the frame's spots and ribbons (in the game all are the same): number. */
        private int context(FarView view, Out out) {
            if (placeContexts == 0 || placeView[placeContexts - 1] != view || placeOut[placeContexts - 1] != out) {
                if (placeContexts == placeView.length) {
                    placeView = java.util.Arrays.copyOf(placeView, 2 * placeContexts);
                    placeOut = java.util.Arrays.copyOf(placeOut, 2 * placeContexts);
                }
                placeView[placeContexts] = view;
                placeOut[placeContexts] = out;
                placeContexts++;
            }
            return placeContexts - 1;
        }

        /** Segment of a ribbon — into the wait for the frame ({@link SmokePuffs#ribbon}). */
        boolean holdRibbon(FarView view, Out out, double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by,
                           double bz, double halfB, float alphaB, int lightB, float r, float g, float b, float[] joint) {
            FarSprites.RibbonShape q = shape;
            org.joml.Vector3f f = view.forward();
            boolean strip = q.set(view.far(), f.x, f.y, f.z, ax, ay, az, halfA, alphaA, bx, by, bz, halfB, alphaB, joint);
            double fill = q.fill(view, strip);
            if (fill > 0) {
                if ((ribbonCount + 1) * RIBBON_FIELDS > ribbons.length) ribbons = java.util.Arrays.copyOf(ribbons, 2 * ribbons.length);
                int o = ribbonCount++ * RIBBON_FIELDS;
                double[] v = {ax, ay, az, bx, by, bz, halfA, halfB, alphaA, alphaB, lightA, lightB, r, g, b, joint[0], joint[1], joint[2],
                        context(view, out), fill, Math.max(Math.max(q.alphaA, q.alphaB), Math.max(q.capAlpha, q.veil))};
                System.arraycopy(v, 0, ribbons, o, RIBBON_FIELDS);
            }
            if (strip) {
                joint[0] = q.nx;
                joint[1] = q.ny;
                joint[2] = q.nz;
            } else if (q.cap) joint[0] = Float.NaN;
            return strip;
        }

        /**
         * Frame's ribbons — as plan records: their own fill, they do not merge or hide; those that did not fit the
         * budget are not drawn ({@link #fit}).
         */
        private void planRibbons() {
            if (ribbonEntry.length < ribbonCount) ribbonEntry = new int[2 * ribbonCount];
            for (int k = 0; k < ribbonCount; k++) {
                int o = k * RIBBON_FIELDS;
                FarView v = placeView[(int) ribbons[o + 18]];
                current = Integer.MIN_VALUE;
                double mx = 0.5 * (ribbons[o] + ribbons[o + 3]), my = 0.5 * (ribbons[o + 1] + ribbons[o + 4]), mz = 0.5 * (ribbons[o + 2] + ribbons[o + 5]);
                ribbonEntry[k] = plans;
                planCluster(v, mx, my, mz, 0, 0, 1, true, 0, ribbons[o + 20], 0, 1, 0, 0, false);
                floor -= planCover0[ribbonEntry[k]];
                planCover0[ribbonEntry[k]] = ribbons[o + 19];
                floor += ribbons[o + 19];
            }
        }

        /**
         * Ribbons that stayed within the budget — into the frame in order (across at a — as the previous segment of the
         * same ribbon gave it).
         */
        private void drawRibbons() {
            for (int k = 0; k < ribbonCount; k++) {
                int e = ribbonEntry[k], o = k * RIBBON_FIELDS;
                if (planVeil[e] >= 1) continue;
                float keep = (float) (1 - planVeil[e]);
                if (record != null) record.rank = (float) (planShown[e] * planSeen[e]);
                ribbonJoint[0] = (float) ribbons[o + 15];
                ribbonJoint[1] = (float) ribbons[o + 16];
                ribbonJoint[2] = (float) ribbons[o + 17];
                placeOut[(int) ribbons[o + 18]].ribbon(ribbons[o], ribbons[o + 1], ribbons[o + 2], ribbons[o + 6], (float) ribbons[o + 8] * keep,
                        (int) ribbons[o + 10], ribbons[o + 3], ribbons[o + 4], ribbons[o + 5], ribbons[o + 7], (float) ribbons[o + 9] * keep,
                        (int) ribbons[o + 11], (float) ribbons[o + 12], (float) ribbons[o + 13], (float) ribbons[o + 14], ribbonJoint);
            }
        }

        /**
         * Tags the recorded quads of the current puff or place with its entry's visible opacity
         * ({@link Recording#rank}).
         */
        private void rankRecord() {
            if (record == null) return;
            int e = current >= 0 ? blobEntry[current] : placeEntry[~current];
            record.rank = e < 0 ? 0 : (float) (planShown[e] * planSeen[e]);
        }

        /** Waiting spot p — into {@link #club}. */
        private void place(int p) {
            int o = p * PLACE_FIELDS, c = (int) places[o + P_CONTEXT];
            current = ~p;
            club(placeView[c], placeOut[c], places[o], places[o + 1], places[o + 2], places[o + 3], places[o + 4], places[o + 5], places[o + 6],
                    (float) places[o + 7], (float) places[o + 8], (float) places[o + 9], places[o + 10], places[o + 11], placeSeed[p], places[o + 12],
                    (int) places[o + 13], places[o + 14] > 0.5, places[o + P_AIR]);
        }

        /**
         * Tube puff with center (px, py, pz) from the eye — a spot on the axis that stands along meters of axis:
         * section σ, tube thickness across the axis tau, which the puffs draw (not one Gaussian: opacity at the axis —
         * of the tube), visible edge — by the thickness of the whole tube edgeTau (part of its substance may be drawn
         * by a ribbon) — as a cluster of baked puffs: color of the thick layer (cr, cg, cb; before sky light at the
         * eye), shade level of the shade maps, puff frame age (with a fraction), grain h, visible share vis, puffs in
         * the cluster — not more than most; vary — a single puff also takes its own frame and reflection by grain (the
         * tube's puffs lie next to each other: identical ones would lie like a stack of coins). A puff on screen — an
         * almost flat disc with radius {@link #puffDisc} of the square's half-size (not a Gaussian): at the axis its
         * neighbors overlap it (2·disc / along), and each takes its share of the tube's opacity — together at the axis
         * they give 1 − e^(−τ). The eye inside the tube — a veil ({@link #veil}). air — air haze up to the spot
         * ({@link FarView#air}). In the plan pass — only the plan record.
         */
        private void club(FarView view, Out out, double px, double py, double pz, double sigma, double tau, double edgeTau, double along,
                          float cr, float cg, float cb, double shade, double age, long h, double vis, int most, boolean vary, double air) {
            double sh = sigma, sv = sigma;
            double a = 1 - Math.exp(-tau);
            if (a < 0.004) return;
            double edge = edge(edgeTau);
            double rh = edge * sh, rv = edge * sv, r = Math.max(rh, rv);
            double d = Math.sqrt(px * px + py * py + pz * pz);
            if (air < Sight.THRESHOLD) return;
            // the eye enters the puff — the puff's squares melt (a square tens of blocks wide right at the camera would
            // fill the screen with its edge), except the puff whose center is ahead ({@link #ahead}); the substance
            // that the square does not show (behind the eye, to the side, right at the eye) — a veil ({@link Veil}:
            // thickness along rays from the eye through the Gaussian); outside the edge at the eye it is absent (for a
            // Gaussian behind the eye, the thickness along the ray forward past the edge is thousandths)
            double gate = smoothstep(0.5 * r, r, d), outside = gate + (1 - gate) * ahead(view, px, py, pz, sh, sv);
            double veiled = gate < 1 ? 1 - front(view, px, py, pz) * outside : 0;
            // frame floor above the budget — the barely visible goes into the veil at the share the plan gave it
            // ({@link #fit})
            double whole = planning ? 0 : veilShare();
            if (!planning && (veiled > 0 || whole > 0)) {
                // substance of the spot — the tube's thickness across the axis × √(2π)σ × its piece of axis
                double ext = tau * Math.sqrt(2 * Math.PI * sh * sv) * along;
                veilAdd(out, view, px, py, pz, sh, sv, ext, cr, cg, cb, (veiled + (1 - veiled) * whole) * vis * air);
            }
            if (outside <= 0 || whole >= 1) return;
            // out of frame — not needed: the ball of the whole puff square (a puff — up to 1.2 radius, its spot — up to
            // ~0.9 from the middle)
            if (!FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return;
            Sight.point(rh, 1, 1, d, view.pixel(), point);
            double grow = point[0] / rh;
            // cluster: the larger the Gaussian on screen, the more puffs and the smaller they are (the 128-pixel frame
            // does not stretch to half the screen); the fractional part of the number — the share of the last one,
            // without a jump
            double pixels = point[0] / Math.max(d * view.pixel(), 1e-12);
            // larger than the screen — detail beyond its edge is not visible, and fill is costly: puffs fewer by as
            // many times as the Gaussian is larger than the screen in area (the frame's half-diagonal, pixels)
            double screen = view.edge() / Math.max(view.pixel(), 1e-12);
            double full = Math.clamp(Math.min(pixels / CLUSTER_PIXELS, most * Math.min(1, screen * screen / (pixels * pixels))), 1, most);
            if (planning) {
                double shown = a * vis * point[1] * air * outside;
                double along3 = Math.max(1, 2 * puffDisc(clubFrame(age, 0.5, vary)) * point[0] / along);
                planCluster(view, px, py, pz, rh * grow, rv * grow, full, veiled > 0,
                        1 - Math.pow(1 - Math.min(shown, 0.999), 1 / along3), shown, h,
                        grow, rv, 0, true);
                return;
            }
            // sky light — brightness of FarView.sky with the puff's scatter; the tint — in the shader for sky light
            // only (FxSkyTint)
            float lit = (float) ((0.88 + 0.24 * unit(h)) * view.skyLight());
            float sr = cr * lit, sg = cg * lit, sb = cb * lit;
            float shown = (float) (a * vis * point[1] * air * outside * (1 - whole));
            // frame fill budget ({@link #FILL_BUDGET}): a cluster — between one puff and the full one
            double many = 1 + (full - 1) * share;
            int count = (int) Math.ceil(many - 1e-9);
            double size = clusterSize(many);
            // at the middle of the cluster this many puffs overlap, and at the axis — also neighboring spots of the
            // tube (the puff's disc on screen — with the floor at a pixel, as it is drawn): each takes its share,
            // together — the same transmittance
            double disc = puffDisc(clusterFrame(age, h, many, vary));
            double spread = 1 - size;
            double overlap = centreCover(px, py, pz, d, h, count, many, size, spread, rh, rv, rv, grow, false, age, vary)
                    * Math.max(1, 2 * disc * point[0] / along);
            float sub = (float) (1 - Math.pow(1 - Math.min(shown, 0.999), 1 / overlap));
            rankRecord();
            for (int j = 0; j < count; j++) {
                long hj = mix(h + j * 0x632BE59BD9B4E019L);
                double ox = clubOffset(h, j, 0) * spread * rh * 0.5, oy = clubOffset(h, j, 7) * spread * rv * 0.5,
                        oz = clubOffset(h, j, 14) * spread * rh * 0.5;
                double k = clubScale(many, hj, true);
                double frame = clusterFrame(age, hj, many, vary);
                int f0 = (int) frame;
                float last = j == count - 1 ? (float) (many - (count - 1)) : 1;
                boolean mirror = vary ? (hj >>> 40 & 1) == 1 : (j & 1) == 1;
                out.puff6(px + ox, py + oy, pz + oz, rh * grow * k, rv * grow * k, FxAtlas.puff6(f0, mirror), (float) (frame - f0), (float) shade, sr,
                        sg, sb, sub * last);
            }
        }

        /**
         * Substance Gaussian at the eye — into the frame's veil: center (px, py, pz) from the eye, σ horizontally and
         * vertically, attenuation of the whole mass ext (m²), color of the thick layer (r, g, b), share share (how many
         * of its puffs melted). A Gaussian that along no ray reaches {@link #VEIL_MIN} is not used: thickness along a
         * ray from the eye — not more than the thickness through the middle along the thinnest axis, ext/(2π·σh·min(σh,
         * σv)).
         */
        boolean veilAdd(Out out, FarView view, double px, double py, double pz, double sh, double sv, double ext, float r, float g, float b,
                        double share) {
            if (!(share > 0) || !(ext > 0) || !(sh > 0) || !(sv > 0)) return false;
            if ((1 - Math.exp(-ext / (2 * Math.PI * sh * Math.min(sh, sv)))) * share < VEIL_MIN) return false;
            if ((veilCount + 1) * VEIL_FIELDS > veils.length) veils = java.util.Arrays.copyOf(veils, 2 * veils.length);
            int o = veilCount++ * VEIL_FIELDS;
            veils[o] = px;
            veils[o + 1] = py;
            veils[o + 2] = pz;
            veils[o + 3] = sh;
            veils[o + 4] = sv;
            veils[o + 5] = ext;
            veils[o + 6] = r;
            veils[o + 7] = g;
            veils[o + 8] = b;
            veils[o + 9] = Math.min(share, 1);
            veilOut = out;
            veilView = view;
            return true;
        }

        void veilClear() {
            veilCount = 0;
            veilOut = null;
            veilView = null;
        }

        /** View and receiver of this frame's inputs (all contexts share them in the game); null — no inputs. */
        @Nullable
        FarView frameView() {
            return contexts > 0 ? ctxView[0] : placeContexts > 0 ? placeView[0] : null;
        }

        @Nullable
        Out frameOut() {
            return contexts > 0 ? ctxOut[0] : placeContexts > 0 ? placeOut[0] : null;
        }

        /**
         * The frame's inputs are complete: the last source's puffs merge here, on the client thread, and the plan's
         * draw calls go to rec, anchored at this frame's eye.
         */
        void seal(Recording rec) {
            close();
            rec.clear();
            FarView v = frameView();
            if (v != null) rec.anchor(v);
            record = rec;
            for (int c = 0; c < contexts; c++) ctxOut[c] = rec;
            for (int c = 0; c < placeContexts; c++) placeOut[c] = rec;
        }

        /**
         * Client thread: a replayed plan's veil Gaussians, moved by (ox, oy, oz) to the eye of view, merged and laid
         * into out.
         */
        void lay(FarView view, Out out, double[] gaussians, int n, double ox, double oy, double oz) {
            if (veils.length < n * VEIL_FIELDS) veils = new double[Math.max(n, 2 * veils.length / VEIL_FIELDS) * VEIL_FIELDS];
            System.arraycopy(gaussians, 0, veils, 0, n * VEIL_FIELDS);
            for (int o = 0; o < n * VEIL_FIELDS; o += VEIL_FIELDS) {
                veils[o] += ox;
                veils[o + 1] += oy;
                veils[o + 2] += oz;
            }
            veilCount = n;
            veilOut = n > 0 ? out : null;
            veilView = view;
            veilFlush();
        }

        /**
         * Frame veil — one grid over the screen ({@link Veil}): at each vertex the thickness along its ray through all
         * the veil's Gaussians; if there are more than {@link #VEIL_MAX} — the close ones merge ({@link #veilMerge}).
         */
        private void veilFlush() {
            if (record != null) {
                // the veil is laid at the eye of the frame that draws the plan (Recording.replay)
                int n = veilOut != null ? veilCount : 0;
                record.veil(veils, n);
                lastVeils = n;
                veilClear();
                return;
            }
            if (veilOut != null && veilCount > 0) {
                if (veilCount > VEIL_MAX || veilLevel > 0) veilMerge();
                veil.fill(veilView, veils, veilCount);
                if (veil.any()) veilOut.veil(veil, OPEN_SKY);
            } else veilLevel = 0;
            lastVeils = veilOut != null ? veilCount : 0;
            veilClear();
        }

        /**
         * Veil Gaussians more than {@link #VEIL_MAX} (thickness along the ray of each vertex through each: CPU cost) —
         * the close ones merge by the same law: attenuation — by sum, center and moments — by visible (attenuation ×
         * share), color — average by it (so the veil and the colors mix by thickness). Cell — a direction from the eye
         * (a cube face, n × n per face), an octave of distance and an octave of size; each level is larger, the last
         * one — by the face: there are no more than seven.
         */
        private void veilMerge() {
            // Keep the previous coarsening until the finer level has headroom, just like the quad budget.
            // Otherwise 50 -> 48 finer groups removed a whole merge pass, changing 24 Gaussians to 48 at once.
            int was = veilLevel;
            veilLevel = 0;
            for (int level = 0; veilCount > VEIL_MAX || level < was && veilCount > HYSTERESIS * VEIL_MAX; level++) {
                int n = Math.max(1, 8 >> level), coarse = Math.max(0, level - 1);
                boolean last = level >= 5;
                if (veilGone.length < veilCount) veilGone = new boolean[2 * veilCount];
                java.util.Arrays.fill(veilGone, 0, veilCount, false);
                cells.clear();
                for (int c = 0; c < veilCount; c++) {
                    int o = c * VEIL_FIELDS;
                    double px = veils[o], py = veils[o + 1], pz = veils[o + 2], s = Math.max(veils[o + 3], veils[o + 4]);
                    double ax = Math.abs(px), ay = Math.abs(py), az = Math.abs(pz), m = Math.max(ax, Math.max(ay, az));
                    int face;
                    double u, v;
                    if (!(m > 0)) {
                        face = 6;
                        u = v = 0;
                    } else if (m == ax) {
                        face = px > 0 ? 0 : 1;
                        u = py / m;
                        v = pz / m;
                    } else if (m == ay) {
                        face = py > 0 ? 2 : 3;
                        u = px / m;
                        v = pz / m;
                    } else {
                        face = pz > 0 ? 4 : 5;
                        u = px / m;
                        v = py / m;
                    }
                    long key = face;
                    if (!last) {
                        long iu = (long) Math.min(n - 1, Math.floor((u + 1) * 0.5 * n)), iv = (long) Math.min(n - 1, Math.floor((v + 1) * 0.5 * n));
                        int far = Math.getExponent(Math.max(Math.sqrt(px * px + py * py + pz * pz), Math.max(s, 1e-3))) >> coarse;
                        int size = Math.getExponent(Math.max(s, 1e-3)) >> coarse;
                        key = mix(mix(mix(mix(face) + iu) + iv) + far) + size;
                    }
                    int first = cells.get(key);
                    if (first < 0) {
                        cells.put(key, c);
                        continue;
                    }
                    absorbVeil(first * VEIL_FIELDS, o);
                    veilGone[c] = true;
                }
                int k = 0;
                for (int c = 0; c < veilCount; c++) {
                    if (veilGone[c]) continue;
                    if (k != c) System.arraycopy(veils, c * VEIL_FIELDS, veils, k * VEIL_FIELDS, VEIL_FIELDS);
                    k++;
                }
                veilCount = k;
                veilLevel = level + 1;
            }
        }

        /** Veil Gaussian with b — into the Gaussian with a ({@link #veilMerge}). */
        private void absorbVeil(int a, int b) {
            double wa = veils[a + 5] * veils[a + 9], wb = veils[b + 5] * veils[b + 9], w = wa + wb, ext = veils[a + 5] + veils[b + 5];
            if (!(w > 0)) return;
            double fa = wa / w, fb = wb / w;
            double mx = fa * veils[a] + fb * veils[b], my = fa * veils[a + 1] + fb * veils[b + 1], mz = fa * veils[a + 2] + fb * veils[b + 2];
            double ax = veils[a] - mx, ay = veils[a + 1] - my, az = veils[a + 2] - mz, bx = veils[b] - mx, by = veils[b + 1] - my, bz = veils[b + 2] - mz;
            double sh2 = fa * (veils[a + 3] * veils[a + 3] + 0.5 * (ax * ax + az * az)) + fb * (veils[b + 3] * veils[b + 3] + 0.5 * (bx * bx + bz * bz));
            double sv2 = fa * (veils[a + 4] * veils[a + 4] + ay * ay) + fb * (veils[b + 4] * veils[b + 4] + by * by);
            veils[a] = mx;
            veils[a + 1] = my;
            veils[a + 2] = mz;
            veils[a + 3] = Math.sqrt(sh2);
            veils[a + 4] = Math.sqrt(sv2);
            veils[a + 5] = ext;
            for (int c = 6; c <= 8; c++) veils[a + c] = fa * veils[a + c] + fb * veils[b + c];
            veils[a + 9] = Math.min(1, w / ext);
        }

        /**
         * Puff: in the plan pass ({@link #planning}) — only how many screens the cluster will close at share
         * {@link #share}; otherwise — squares and veil. vary — a segment's puff: its own frame, reflection and wander
         * of the center, as with tube puffs ({@link Draw#club}). Returns the fill, in screens.
         */
        private double blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, float dr, float dg, float db,
                            int id, double smoke, boolean vary) {
            Vec3 eye = view.camera();
            // on screen — projection of the Gaussian by the view angle (thickness along the ray and the square's
            // height); in the world — its σ (scatter of puffs of the cluster, edge for terrain and for the eye inside)
            double across = projected(sh, sv, x - eye.x, y - eye.y, z - eye.z);
            double ks = SmokeGrid.SOOT_K * soot, kd = SmokeGrid.dustK(dr, dg, db) * dust, kw = SmokeGrid.DROP_K * water;
            double tau = (ks + kd + kw) / (2 * Math.PI * sh * across);
            // smoke share — in the opacity, not in the mass: a fireball has hundreds of thicknesses, and soot at a
            // share of 1 % would already be opaque
            double a = smoke * (1 - Math.exp(-tau));
            if (a < 0.004) return 0;
            double edge = edge(tau);
            double rh = edge * sh, rv = edge * across, rw = edge * sv, r = Math.max(rh, rw);
            double vis = source.visible(y, r, ctxVis, ctx);
            if (vis <= 0) return 0;
            Vec3 cam = view.camera();
            long h = mix(seed * 0x9E3779B97F4A7C15L + id);
            double px = x - cam.x, py = y - cam.y, pz = z - cam.z;
            if (vary) {
                // a segment's puffs go side by side: the middle wanders ({@link #meander}, ⚠ estimate), otherwise — an
                // even row
                double m = meander(Math.min(sh, sv), tau);
                px += m * gauss(h >>> 3);
                py += m * gauss(h >>> 11);
                pz += m * gauss(h >>> 19);
            }
            double d = Math.sqrt(px * px + py * py + pz * pz);
            double air = view.air(d, py);
            if (air < Sight.THRESHOLD) return 0;
            // the eye enters the puff — the puff's squares melt (a square tens of blocks wide right at the camera would
            // fill the screen with its edge), and the substance around the eye — by a veil over the screen: thickness
            // along the rays from the eye through the Gaussian ({@link Veil}). “Inside” — by the ellipsoid of the edge
            // (for a flat dust layer a hundred meters wide and a meter thick, the eye above it is outside; by the
            // largest radius it was “inside”, and the layer's veil covered the whole frame)
            double q = Math.sqrt((px * px + pz * pz) / (rh * rh) + py * py / (rw * rw));
            // a Gaussian whose center is ahead along the view — still puffs ({@link #ahead}); a lying puff — is not a
            // projection along the view: its square lies on the horizontal and ahead of the eye, even when the center
            // is behind the eye
            double lying = lyingShare(sh, sv, py, d);
            double gate = smoothstep(0.5, 1, q);
            double facing = gate + (1 - gate) * ahead(view, px, py, pz, sh, sv);
            double outside = lying * gate + (1 - lying) * facing;
            double veiled = lying * (1 - gate) + (1 - lying) * (gate < 1 ? 1 - front(view, px, py, pz) * facing : 0);
            // frame floor above the budget — the barely visible goes into the veil at the share the plan gave it
            // ({@link #fit})
            double whole = planning ? 0 : veilShare();
            if (!planning && (veiled > 0 || whole > 0))
                veil(this, px, py, pz, sh, sv, ks + kd + kw, ks, kd, kw, dr, dg, db, (veiled + (1 - veiled) * whole) * vis * air * smoke);
            if (outside <= 0 || whole >= 1) return 0;
            // out of frame — not needed: the ball of the whole puff square (a puff — up to 1.2 radius, its spot — up to
            // ~0.9 from the middle)
            if (!FarFlightView.inFrame(view, px, py, pz, d, 2.2 * r)) return 0;
            Sight.point(rh, 1, 1, d, view.pixel(), point);
            double grow = point[0] / rh;
            // sky light — brightness of what the lightmap lights blocks and particles with under open sky
            // (FarView.sky), with the puff's scatter; its tint (at night — lilac) — in the shader only for sky light,
            // not for fire (FxSkyTint)
            float lit = (float) ((0.88 + 0.24 * unit(h)) * view.skyLight());
            float cr = thick(ks, kd, kw, dr) * lit, cg = thick(ks, kd, kw, dg) * lit, cb = thick(ks, kd, kw, db) * lit;
            double light = shade(ks, kd, kw, dg);
            float shade = (float) (light / BAKED_K), sprayShade = (float) (light / SPRAY_K);
            float shown = (float) (a * vis * point[1] * air * outside * (1 - whole));
            // cluster: the larger the Gaussian on screen, the more puffs and the smaller they are (the 128-pixel frame
            // does not stretch to half the screen); the fractional part of the number — the share of the last one,
            // without a jump
            double pixels = point[0] / Math.max(d * view.pixel(), 1e-12);
            // larger than the screen — detail beyond its edge is not visible, and fill is costly: puffs fewer by as
            // many times as the Gaussian is larger than the screen in area (the frame's half-diagonal, pixels)
            double screen = view.edge() / Math.max(view.pixel(), 1e-12);
            double most = Math.clamp(Math.min(pixels / CLUSTER_PIXELS, CLUSTER * Math.min(1, screen * screen / (pixels * pixels))), 1, CLUSTER);
            // frame fill budget ({@link #flush}): a cluster — between one puff and the full one
            double many = 1 + (most - 1) * share;
            int count = (int) Math.ceil(many - 1e-9);
            double size = clusterSize(many);
            // layers of the cluster on average over its area (cost: fill)
            double overlap = Math.max(1, many * size * size);
            // fill: share of the screen under the disc of the cluster's edge (cut by the frame) × overlap
            // a flat puff lies on top: its height on screen — the projection of the disc
            double reach = 1 + (1 - size) * 0.5;
            double ra = rh * grow, rb = rv * grow;
            if (planning) {
                planCluster(view, px, py, pz, ra, rb, most, veiled > 0, shown, shown, h, grow, rw, lying, false);
                return 0;
            }
            double fill = overlap * cover(view, px, py, pz, ra * reach, rb * reach);
            double spread = 1 - size;
            // at a source with spray (a burst on water) the substance is more droplet than smoke and dust: spray puffs;
            // a puff of its own look (emission curtain, crushing dust, a cloud burst in the air) — with its own frames
            // and its own source frame
            boolean own = look != SmokeGrid.Look.SMOKE, spray = !own && sprays && kw > ks + kd;
            double clubAge = own ? lookFrame : spray ? sprayAge : age;
            // each puff takes its share: by the cluster's area on average this many layers overlap — together the same
            // transmittance on average (SmokeFillTest; the cluster covers the middle of a cloud more densely — the
            // former approximation, review #443)
            float sub = (float) (1 - Math.pow(1 - Math.min(shown, 0.999), 1 / overlap));
            rankRecord();
            // a flat puff seen from above (the disc's projection is wider than its thickness) lies on the horizontal
            // ({@code lying}): a square facing the camera of a dust layer at the ground would stand as a wall above it
            // (frames F2a: a “slab” under the ball in 0.1 s)
            for (int j = 0; j < count; j++) {
                long hj = mix(h + j * 0x632BE59BD9B4E019L);
                // a lying puff — at the height of the layer's middle: lower it would sink under the terrain
                double ox = clubOffset(h, j, 0) * spread * rh * 0.5, oy = (1 - lying) * clubOffset(h, j, 7) * spread * rw * 0.5,
                        oz = clubOffset(h, j, 14) * spread * rh * 0.5;
                // size scatter — for cluster puffs (variety); a single puff — the whole Gaussian, without scatter:
                // otherwise its area, and with it the substance on screen, would wander by ±44 %
                double k = clubScale(many, hj, false);
                // Frame and mirroring belong to each puff, including distant single puffs: in a raid at 3 km, all 335
                // old single puffs used frame 14 without mirroring, and their identical outlines combined into a wall.
                double frame = clubFrame(clubAge, unit(hj >>> 28), true);
                int f0 = (int) frame;
                float last = j == count - 1 ? (float) (many - (count - 1)) : 1;
                boolean mirror = (hj >>> 40 & 1) == 1;
                FxAtlas.Sprite sprite = spray ? FxAtlas.spray6(f0, mirror) : FxAtlas.club(look, f0, mirror);
                // a lying puff from above — the lit top of the layer: no shadows of the maps (they are baked for a puff
                // facing the camera, light from the side and behind), the light of sun, sky and fire is full (shade
                // level 0; the balls' light is per pixel, fx.fsh); from below — as usual. Softness at the terrain — the
                // ray's path in the layer down to the ground (rw / sin e), not the half-size
                if (lying > 0) out.puff6Flat(px + ox, py + oy, pz + oz, rh * grow * k, rh * grow * k,
                        Math.min(rh, rw * d / Math.max(Math.abs(py), 1e-3)) * ua.zentix.incoming.client.fx.layer.FxQuads.SOFT, clubAngle(hj), sprite,
                        (float) (frame - f0), py < 0 ? 0 : (spray ? sprayShade : shade), cr, cg, cb, opticalShare(sub * last, lying));
                if (lying < 1) out.puff6(px + ox, py + oy, pz + oz, rh * grow * k, rv * grow * k, sprite, (float) (frame - f0), spray ? sprayShade : shade,
                        cr, cg, cb, opticalShare(sub * last, 1 - lying));
            }
            return fill;
        }
    }

    /**
     * The two layer views share optical depth across an angular band, so raising the eye no longer switches the whole
     * quad in one frame.
     */
    static double lyingShare(double sh, double sv, double dy, double d) {
        return smoothstep(2.5, 3.5, sh / sv) * smoothstep(0.7, 1.3, sh * Math.abs(dy) / Math.max(sv * d, 1e-12));
    }

    static float opticalShare(float alpha, double share) {
        return (float) -Math.expm1(share * Math.log1p(-Math.min(alpha, 0.999999f)));
    }

    /**
     * σ along the screen's vertical for a Gaussian with axes (σh, σh, σv), seen from (dx, dy, dz) from the eye: the
     * projection of the Gaussian — a Gaussian with axes σh (across, horizontally) and √(σv²·cos²e + σh²·sin²e) (e — the
     * view angle to the horizon), thickness along the ray through the middle — mass / (2π·σ₁·σ₂). A flat puff (a dust
     * layer at the ground: σh tens of times larger than σv) from above — a disc on the ground with the layer's
     * thickness along the ray, from the side — a strip; a square facing the camera with height σv would lie as a thin
     * line across the frame (frames of gate F2a: dark strips above the ground around the blast).
     */
    static double projected(double sh, double sv, double dx, double dy, double dz) {
        double d2 = dx * dx + dy * dy + dz * dz;
        if (!(d2 > 0)) return sv;
        double sin2 = dy * dy / d2;
        return Math.sqrt(sv * sv * (1 - sin2) + sh * sh * sin2);
    }

    /**
     * Subdivision level of a segment of length len with the smallest section σ sigma: 2^level puffs, laid every
     * {@link #SEGMENT_STEP}·σ.
     */
    static double tubeLevel(double len, double sigma) {
        return Math.clamp(Math.log(Math.max(1, len / (SEGMENT_STEP * sigma))) / Math.log(2), 0, TUBE_LEVELS);
    }

    /** Puffs on a segment of level level: 2 to this power. */
    static int tubeDepth(double level) {
        return (int) Math.ceil(level - 1e-9);
    }

    /**
     * Share of spot j (of 2^{@link #tubeDepth}) at level level: even ones are whole; odd ones of the top level appear
     * with the level's fraction.
     */
    static double tubeWeight(int j, double level) {
        int k = tubeDepth(level);
        return k > 0 && (j & 1) == 1 ? level - (k - 1) : 1;
    }

    /** Sum of the shares of spots of level level: the spot's substance — its share of it. */
    static double tubeTotal(double level) {
        int k = tubeDepth(level);
        return k == 0 ? 1 : (1 << (k - 1)) * (1 + level - (k - 1));
    }

    /** Tube puffs per segment — no more than 2 to this power. */
    static final int TUBE_LEVELS = 7;
    /**
     * A baked puff on screen — an almost flat disc: the radius of a flat disc with the same coverage along the chord
     * through the middle (∫ opacity over the radius / opacity of the middle, in fractions of the square's half-size),
     * by frames ({@code PlumeContinuityTest} measures by pictures): a cloud's cap seen from above — 0.71–0.80. Puff
     * overlap at the tube's axis is the coverage along the chord, not the radius of half-opacity: for frames with a
     * soft tail the latter is 5–8 % smaller, and at the axis of a thin trail nearby it reached 1.4× the law. Not a
     * Gaussian: Gaussians 1.2σ apart merge into an even tube, while discs of radius 0.5–0.9σ (the edge of a thin tube's
     * puff — 1.2σ) — into beads.
     */
    static final double[] PUFF_DISCS = {0.71, 0.72, 0.72, 0.74, 0.75, 0.76, 0.76, 0.76, 0.77, 0.77, 0.77, 0.78, 0.78, 0.79, 0.79, 0.80};
    /** Disc of the narrowest frame. */
    static final double PUFF_DISC_MIN = 0.71;

    /** Disc of a puff (fraction of the half-size) for frame frame, with fraction. */
    static double puffDisc(double frame) {
        double f = Math.clamp(frame, 0, PUFF_DISCS.length - 1);
        int i = Math.min((int) f, PUFF_DISCS.length - 2);
        return PUFF_DISCS[i] + (PUFF_DISCS[i + 1] - PUFF_DISCS[i]) * (f - i);
    }
    /** Range of the size of cluster and tube puffs: half-size × 0.8–1.2. */
    static final double SIZE_LOW = 0.8;

    /**
     * Tube spot step σ with thickness across the axis tau: no wider than {@link #SEGMENT_STEP}·σ (the substance's
     * Gaussians merge) and no wider than the disc of the smallest puff — each spot of the axis under two discs
     * ({@link #PUFF_DISC_MIN} × {@link #SIZE_LOW} × edge).
     */
    static double tubeStep(double sigma, double tau) {
        double disc = PUFF_DISC_MIN * SIZE_LOW * edge(tau) * sigma, off = MEANDER_MAX * meander(sigma, tau);
        // the axis crosses the disc of a puff shifted by the wander by off, with the chord 2√(disc² − off²); the step
        // is half of it
        return Math.min(SEGMENT_STEP * sigma, Math.sqrt(disc * disc - off * off));
    }

    /**
     * Wander of the middle of a tube's puff, m: {@link #TUBE_MEANDER}·σ, but no farther than half the disc of the
     * smallest puff from the axis (across the screen — up to {@link #MEANDER_MAX} wanders): puffs wander each on their
     * own, and one shifted farther would open the axis — a thin tube would break into beads. ⚠ The wander is an
     * estimate (trail eddies of the order of its width).
     */
    static double meander(double sigma, double tau) {
        return Math.min(TUBE_MEANDER * sigma, 0.5 * PUFF_DISC_MIN * SIZE_LOW * edge(tau) * sigma / MEANDER_MAX);
    }

    /**
     * Shift of a puff across the axis on screen — no more than this many wanders: the bell {@link #gauss} up to 1.725
     * along both axes.
     */
    static final double MEANDER_MAX = 1.725 * Math.sqrt(2);
    /**
     * The middle of a tube's puff wanders this many σ: large trail eddies are of the order of its width, and the trail
     * itself winds by fractions of its width (⚠ estimate).
     */
    static final double TUBE_MEANDER = 0.3;
    /** Puffs in a cluster of a tube's puff — no more than: detail along the tube comes from neighboring puffs. */
    static final int TUBE_CLUSTER = 3;

    /**
     * A segment of trail tube ({@code TrailModel}: Gaussian section σ, attenuation per meter e — at ends a and b, from
     * the eye) — with puffs along the axis, like clouds: a tube of Gaussians of the same section through ≤
     * {@link #SEGMENT_STEP}·σ (up to {@code 2^}{@link #TUBE_LEVELS} per segment, beyond that the step is wider), each
     * with the substance of its own piece of the axis (the sum of the Gaussians along the axis gives the tube's
     * thickness across the middle e/(√(2π)·σ)). Puffs per segment — 2^k ({@link #TUBE_LEVELS}): spots of level k are
     * part of the spots of level k + 1, and new (odd) ones appear with a share while the tube narrows on screen — no
     * jumps as σ grows.
     *
     * @param wa     tube width at end a ({@code FarFlightView.puffShare}: at half opacity), at b — wb: the share
     *               of the substance that the puffs draw — each puff by its width and distance (the rest is the
     *               ribbon, whose share at the ends is the same); the spot budget does not change the share
     * @param cr     color of the thick layer of the substance on screen by channel (cr, cg, cb; {@link #thick};
     *               for a trail — gray {@link #tubeColour})
     * @param shade  shade level of the puff maps ({@link #shade}; for a trail — {@link #tubeShade})
     * @param sigma0 σ at the trail's birth: the puff frame — by the growth of σ from it ({@link FxAtlas#puffFrame})
     * @param stride spot step — as many times wider than the law ({@link #tubeStep}; ≥ 1, spot budget
     *               {@code FarFlightView.CLUB_BUDGET}): fewer spots per segment; the segment's substance is the same
     *               at them — each stands for its own, longer piece of the axis
     * @param tube   tube (a projectile's trail, a source's plume): its spots wait for the frame's plan
     *               ({@link Draw#holdPlace}) and among themselves do not merge
     * @return spots of puffs and veils (the puff spot budget); the frame's spots are already gone
     *         ({@link #PLACES}) — −(segment spots + 1): no spots taken, the puffs' share of the segment is absent
     *         in this frame (the budget in the next frames grows the step)
     */
    static int tube(FarView view, Out out, double ax, double ay, double az, double sa, double ea, double wa, double bx, double by, double bz,
                    double sb, double eb, double wb, float cr, float cg, float cb, double shade, double sigma0, long seed, double stride, double shown,
                    long tube) {
        if (ea + eb <= 0 || !(sa > 0) || !(sb > 0)) return 0;
        double tx = bx - ax, ty = by - ay, tz = bz - az, len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (!(len > 0)) return 0;
        // spots — no sparser than the puff discs overlapping the axis twice at each end ({@link #tubeStep}); with the
        // spot budget — stride times sparser; the edge of a puff — of the whole tube (ribbon and puffs draw one
        // substance; the visible width is its), the share of puffs — only in opacity
        double step = Math.min(tubeStep(sa, ea / (Math.sqrt(2 * Math.PI) * sa)), tubeStep(sb, eb / (Math.sqrt(2 * Math.PI) * sb))) * stride;
        double level = tubeLevel(len, step / SEGMENT_STEP);
        int k = tubeDepth(level), n = 1 << k;
        // no more frame spots can be taken ({@link #PLACES}): the segment has no spots, and its spots go to the tube
        // puff budget, and in the next frames the spot step grows
        if (fill.placeCount + n > PLACES) {
            fill.refused += n;
            return -(n + 1);
        }
        double total = tubeTotal(level);
        int drawn = 0;
        for (int j = 0; j < n; j++) {
            double weight = tubeWeight(j, level);
            if (weight <= 0) continue;
            double u = (double) j / n, sigma = sa + (sb - sa) * u, e = ea + (eb - ea) * u;
            double cx = ax + tx * u, cy = ay + ty * u, cz = az + tz * u;
            double share = FarFlightView.puffShare(wa + (wb - wa) * u, Math.sqrt(cx * cx + cy * cy + cz * cz), view.pixel());
            if (share <= 0) continue;
            // thickness of the tube across the axis — e/(√(2π)σ) (the share of puffs — share); a spot stands for its
            // piece of the axis len/total (with the spot's share)
            double full = e / (Math.sqrt(2 * Math.PI) * sigma), tau = share * full, along = len * weight / total;
            if (!(tau > 0) || !(along > 0)) continue;
            double age = FxAtlas.puffFrame(sigma / Math.max(sigma0, 1e-6));
            // grain — by the spot at the finest level: the puff stays the same when the level changes
            long h = mix(seed * 0x9E3779B97F4A7C15L + ((long) j << (TUBE_LEVELS - k)));
            // trail eddies — of the order of σ: the middle of the puff wanders ({@link #meander}, ⚠ estimate),
            // otherwise — an even row
            double m = meander(sigma, tau);
            drawn += fill.holdPlace(view, out, cx + m * gauss(h >>> 3), cy + m * gauss(h >>> 11), cz + m * gauss(h >>> 19), sigma, tau, full, along,
                    cr, cg, cb, shade, age, h, shown, TUBE_CLUSTER, true, tx / len, ty / len, tz / len, tube);
        }
        return drawn;
    }

    /**
     * Color of the substance of a trail with single-scattering albedo albedo (up to delta-Eddington, asymmetry
     * {@link Smoke#ASYMMETRY}): by two streams after delta-Eddington (f = g²), as for clouds ({@link SmokeGrid}). The
     * tube's thickness — the full attenuation, as for the ribbon and the trail's visibility threshold (Koschmieder):
     * forward-scattered by small (~µm) particles it leaves at tens of degrees and does not carry the picture behind the
     * smoke.
     */
    static float tubeColour(double albedo) {
        double g = Smoke.ASYMMETRY, f = g * g;
        return thick((1 - f) * albedo / (1 - f * albedo), g / (1 + g));
    }

    /** Shade level of the puff maps for the trail's substance with albedo albedo ({@link #tubeColour}). */
    static double tubeShade(double albedo) {
        double g = Smoke.ASYMMETRY, f = g * g;
        return twoStream((1 - f) * albedo / (1 - f * albedo), g / (1 + g)) / BAKED_K;
    }

    /**
     * Middle of a Gaussian ahead along the view, in σ along the view: from this point puffs begin to replace the veil,
     * and past that point they have replaced it.
     */
    static final double AHEAD_FROM = 1, AHEAD_FULL = 2.5;

    /**
     * How long the puffs of a Gaussian with center (px, py, pz) from the eye and σ (σh, σh, σv) still draw it when the
     * eye is inside its edge: the puff's square — substance projected along the rays from the eye, and it is accurate
     * while the substance is in front of the eye. The middle ahead by {@link #AHEAD_FULL} σ along the view — 99 % of
     * the substance in front of the eye: puffs; closer than {@link #AHEAD_FROM} σ (to the side or behind) — the veil
     * ({@link Veil}). Without this the puffs melted as soon as the eye entered the Gaussian's edge (~3.7σ for a dense
     * one) — for a trail of a SAM at 18 m: a billowing tube that the camera is carried toward turned into a veil long
     * before reaching it.
     */
    static double ahead(FarView view, double px, double py, double pz, double sh, double sv) {
        org.joml.Vector3f f = view.forward();
        double z = px * f.x + py * f.y + pz * f.z, sf = Math.sqrt(sh * sh * (1 - f.y * f.y) + sv * sv * f.y * f.y);
        return smoothstep(AHEAD_FROM * sf, AHEAD_FULL * sf, z);
    }

    /**
     * How much of the square of a puff with center (px, py, pz) from the eye is visible: it faces the camera, all at
     * the depth of its middle — behind the eye it is absent altogether, right at the eye it melts, as in {@code fx.fsh}
     * (from {@link #NEAR_FROM} blocks to {@link #NEAR_FADE}). What the square does not show, when the eye is at the
     * puff's edge, is a veil, not nothing: a puff to the side and behind otherwise lost the share of substance it held
     * behind the square.
     */
    static double front(FarView view, double px, double py, double pz) {
        org.joml.Vector3f f = view.forward();
        return Math.clamp((px * f.x + py * f.y + pz * f.z - NEAR_FROM) / NEAR_FADE, 0, 1);
    }

    /**
     * The layer's square right at the camera melts from this distance, in blocks, over this much (as {@code fx.fsh}:
     * the near plane).
     */
    static final double NEAR_FROM = 0.3, NEAR_FADE = 1.2;

    /** World light under open sky without block light (packed, for the lightmap). */
    private static final int OPEN_SKY = LightTexture.pack(0, 15);

    /**
     * Veil of a cloud around the eye — this many blocks in front of it (closer, the layer's squares melt at the near
     * plane).
     */
    static final double VEIL_AT = 2;
    /** A Gaussian that along no veil ray covers more than this share is not put into the veil. */
    static final double VEIL_MIN = 0.004;
    /** Numbers per veil Gaussian: center from the eye (3), σh, σv, mass attenuation, color (3), share. */
    static final int VEIL_FIELDS = 10;
    /**
     * Veil cells along each screen axis. The substance's edge at the eye on screen — ~σ/distance; for a SAM trail
     * passing through the camera (σ ~5 m) — tens of degrees: a cell of ~13° (field of view 70°) holds it, and linear
     * blending across a cell is smooth.
     */
    static final int VEIL_CELLS = 12;

    /**
     * The substance around the eye — a veil over the screen (its Gaussians' puffs melted: a square right up to the eye
     * would fill the screen with its edge). Thickness — along each grid vertex's ray over the whole screen
     * ({@link #VEIL_CELLS}², a square in tangents ±tg of the angle to the screen's edge, {@link #VEIL_AT} blocks in
     * front of the eye) through each Gaussian ({@link #sightDepth}); vertex opacity — 1 − e^(−Στ), color — average by
     * thickness (scattering of the mix of substances at the eye — shares by thickness, as for {@link #thick}); between
     * vertices — linear. Before, the veil was one square with the thickness along one ray — the screen's middle: a
     * trail passing through the camera lay as an even white pall over the whole frame (brightness SD 1.5–3 of 255),
     * although the substance at the eye is a band of the tube, denser along it and clear above and below it (complaint
     * 24.10, final pass 3.7.0, scene sam-burst).
     */
    static final class Veil {
        /** Vertices along an axis. */
        static final int N = VEIL_CELLS + 1;
        /** Half-size of the grid in tangents from the screen's middle. */
        double extent;
        final double[] tau = new double[N * N];
        final float[] r = new float[N * N], g = new float[N * N], b = new float[N * N];
        /**
         * Softness of a vertex to the world, blocks: the substance along its ray is not all in front of the world. The
         * vertex melts like a layer's square at the world ({@code fx.fsh}: × (world − vertex place) / softness), and
         * where along the ray half its opacity accumulates ({@link #half}), the world takes half of the veil: a house
         * in front of a distant cloud does not sink into the veil, while in thick smoke around the eye a wall a few
         * meters away is already almost hidden. ⚠ A linear falloff instead of a share of thickness before the world is
         * an approximation.
         */
        final float[] soft = new float[N * N];
        /**
         * Distance to half opacity: the material location used for sorting, independent of the nearby grid geometry.
         */
        final float[] depth = new float[N * N];

        /**
         * Where along the ray half the opacity of the substance of thickness tau, m, accumulates: the substance is
         * exactly along a segment with the average distance mean over the thickness and spread spread (SD, m; the
         * segment is ±√3·SD, no closer than the eye), opacity on the segment — 1 − e^(−tau·x): half of the final one —
         * at x = −ln(1 − (1 − e^(−tau))/2)/tau (for a thin one — the middle; for a dense one — nearer the start).
         */
        static double half(double tau, double mean, double spread) {
            double from = Math.max(0, mean - Math.sqrt(3) * spread), to = Math.max(from, mean + Math.sqrt(3) * spread);
            double x = tau > 1e-6 ? -Math.log(1 - 0.5 * -Math.expm1(-tau)) / tau : 0.5;
            return from + x * (to - from);
        }
        final org.joml.Vector3f forward = new org.joml.Vector3f(), left = new org.joml.Vector3f(), up = new org.joml.Vector3f();
        private final double[] moments = new double[2];

        /** Tangent of vertex i along an axis (left on screen — by {@code left}, up — by {@code up}). */
        double at(int i) {
            return extent * (2.0 * i / (N - 1) - 1);
        }

        /** Vertex number (i — by {@code left}, j — by {@code up}). */
        static int index(int i, int j) {
            return j * N + i;
        }

        /** Opacity of vertex k. */
        float alpha(int k) {
            return (float) (1 - Math.exp(-tau[k]));
        }

        /** Copy (checks: the grid at the sink survives the next frame). */
        Veil copy() {
            Veil c = new Veil();
            c.extent = extent;
            System.arraycopy(tau, 0, c.tau, 0, tau.length);
            System.arraycopy(r, 0, c.r, 0, r.length);
            System.arraycopy(g, 0, c.g, 0, g.length);
            System.arraycopy(b, 0, c.b, 0, b.length);
            System.arraycopy(soft, 0, c.soft, 0, soft.length);
            System.arraycopy(depth, 0, c.depth, 0, depth.length);
            c.forward.set(forward);
            c.left.set(left);
            c.up.set(up);
            return c;
        }

        /** Whether there is anything to draw. */
        boolean any() {
            for (double t : tau) if (-Math.expm1(-t) >= 0.002) return true;
            return false;
        }

        /**
         * The mesh blends at its material depth (opacity-weighted over all nodes; transparent ones do not pull it
         * toward the eye), one key for every cell: with a key per cell, neighbouring cells sorted to opposite sides of
         * a club between their depths, and the lighter veil over the club on one side and under it on the other drew a
         * hard screen-aligned rectangle into the smoke (0.1 of the frame diagonal per cell; RECT, 27.10).
         */
        float depth() {
            double sum = 0, distance = 0;
            for (int k = 0; k < depth.length; k++) {
                double a = alpha(k);
                sum += a;
                distance += a * depth[k];
            }
            return sum > 0 ? (float) (distance / sum) : (float) VEIL_AT;
        }

        /**
         * Place of vertex (i, j) from the eye, axis {@code axis} (0 — x, 1 — y, 2 — z): at {@link #VEIL_AT} blocks
         * along the view.
         */
        double place(int i, int j, int axis) {
            double u = at(i), v = at(j);
            return switch (axis) {
                case 0 -> VEIL_AT * (forward.x + u * left.x + v * up.x);
                case 1 -> VEIL_AT * (forward.y + u * left.y + v * up.y);
                default -> VEIL_AT * (forward.z + u * left.z + v * up.z);
            };
        }

        /**
         * Transmittance of the veil at a screen point (u, v) in tangents — as the GPU draws it: opacity linear between
         * the vertices of the cell (checks).
         */
        double through(double u, double v) {
            double fu = Math.clamp((u + extent) / (2 * extent) * (N - 1), 0, N - 1 - 1e-9), fv = Math.clamp((v + extent) / (2 * extent) * (N - 1), 0, N - 1 - 1e-9);
            int i = (int) fu, j = (int) fv;
            double du = fu - i, dv = fv - j;
            double a = du >= dv
                    ? (1 - du) * alpha(index(i, j)) + (du - dv) * alpha(index(i + 1, j)) + dv * alpha(index(i + 1, j + 1))
                    : (1 - dv) * alpha(index(i, j)) + (dv - du) * alpha(index(i, j + 1)) + du * alpha(index(i + 1, j + 1));
            return 1 - a;
        }

        /** Grid of frame view over the veil's Gaussians (count of them, {@link #VEIL_FIELDS} numbers each in g). */
        void fill(FarView view, double[] gs, int count) {
            forward.set(view.forward());
            left.set(view.left());
            up.set(view.up());
            // the whole frame at any aspect ratio (and for a portrait window): a circle to the screen's corner, with a
            // margin for an output frame of a different size than the window (Flashback)
            extent = Math.min(Math.tan(Math.min(view.edge(), 1.45)) * 1.2, 8);
            java.util.Arrays.fill(tau, 0);
            java.util.Arrays.fill(r, 0);
            java.util.Arrays.fill(g, 0);
            java.util.Arrays.fill(b, 0);
            java.util.Arrays.fill(soft, 0);
            java.util.Arrays.fill(depth, 0);
            for (int j = 0; j < N; j++) {
                for (int i = 0; i < N; i++) {
                    double u = at(i), v = at(j);
                    double rx = forward.x + u * left.x + v * up.x, ry = forward.y + u * left.y + v * up.y, rz = forward.z + u * left.z + v * up.z;
                    double n = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    rx /= n;
                    ry /= n;
                    rz /= n;
                    int k = index(i, j);
                    double sr = 0, sg = 0, sb = 0, sum = 0, far = 0, far2 = 0;
                    for (int c = 0; c < count; c++) {
                        int o = c * VEIL_FIELDS;
                        double along = sightDepth(gs[o], gs[o + 1], gs[o + 2], gs[o + 3], gs[o + 4], gs[o + 5], rx, ry, rz);
                        // the Gaussian's share — of its substance's opacity along the ray, as for melted puffs
                        double a = -Math.expm1(-along) * gs[o + 9];
                        if (!(a > 1e-6)) continue;
                        double t = -Math.log1p(-Math.min(a, 0.999));
                        sum += t;
                        sr += t * gs[o + 6];
                        sg += t * gs[o + 7];
                        sb += t * gs[o + 8];
                        sightMoments(gs[o], gs[o + 1], gs[o + 2], gs[o + 3], gs[o + 4], rx, ry, rz, moments);
                        far += t * moments[0];
                        far2 += t * (moments[1] + moments[0] * moments[0]);
                    }
                    tau[k] = sum;
                    if (sum > 0) {
                        r[k] = (float) (sr / sum);
                        g[k] = (float) (sg / sum);
                        b[k] = (float) (sb / sum);
                        double mean = far / sum, spread = Math.sqrt(Math.max(0, far2 / sum - mean * mean));
                        double half = half(sum, mean, spread);
                        depth[k] = (float) Math.max(VEIL_AT, half);
                        soft[k] = (float) Math.max(VEIL_SOFT_MIN, 2 * (half - VEIL_AT * n));
                    }
                }
            }
        }
    }

    /**
     * The substance of a Gaussian around the eye (center (px, py, pz) from the eye, σ horizontally and vertically,
     * attenuation of the whole mass ext, m²): its puffs melted — the substance goes into the frame's veil
     * ({@link Veil}: thickness along the ray of each of its vertices, {@link #sightDepth}). The same color of the
     * substance as for the puffs; light — as for the substance under open sky (lightmap: sky 15) and the frame's fire
     * at the eye (FxLights), as for the puffs of this same substance outside. Share share — how many of its puffs
     * melted.
     */
    private static void veil(Draw d, double px, double py, double pz, double sh, double sv, double ext, double ks, double kd, double kw, float dr,
                             float dg, float db, double share) {
        float cr = thick(ks, kd, kw, dr), cg = thick(ks, kd, kw, dg), cb = thick(ks, kd, kw, db);
        d.veilAdd(d.out, d.view, px, py, pz, sh, sv, ext, cr, cg, cb, share);
    }

    /**
     * Thickness along the view ray from the eye (direction f, unit) through a Gaussian with axes (σh, σh, σv), center —
     * (px, py, pz) from the eye, attenuation of the whole mass ext (m²): in the space where the Gaussian is a sphere of
     * unit σ, the ray steps by |f'| = √((fx² + fz²)/σh² + fy²/σv²), and the thickness is
     * ext/((2π)^{3/2}σh²σv)·√(π/2)/|f'|·e^(−b'²/2)·(1 + erf(a'/√2)), a' — the path to the point of the ray nearest the
     * middle, b' — its distance from the middle (both in σ). For a sphere — the former veil formula.
     */
    static double sightDepth(double px, double py, double pz, double sh, double sv, double ext, double fx, double fy, double fz) {
        // the veil calls this for each grid vertex and for the Gaussian at the eye: constants — once each
        double ux = fx / sh, uy = fy / sv, uz = fz / sh, len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (!(len > 0)) return 0;
        ux /= len;
        uy /= len;
        uz /= len;
        // middle from the eye in σ: the ray from the eye — t·u, the point nearest the middle — t = a'
        double cx = px / sh, cy = py / sv, cz = pz / sh;
        double a = cx * ux + cy * uy + cz * uz, b2 = Math.max(0, cx * cx + cy * cy + cz * cz - a * a);
        // a ray farther than 10σ from the middle — e^(−50): for the densest puff the thickness is billionths, erf is
        // not needed
        if (b2 > 100) return 0;
        return ext * SIGHT / (sh * sh * sv * len) * Math.exp(-0.5 * b2) * (1 + SmokeGrid.erf(a * SQRT_HALF));
    }

    /**
     * Where along the ray f from the eye the substance of a Gaussian is (as for {@link #sightDepth}), m: along the ray
     * it is a Gaussian with its middle at the point of the ray nearest the Gaussian's middle a' (in the ray's σ) and
     * unit σ, from the eye — truncated: the mean — a' + λ, the variance — 1 − λ(λ + a'), λ = φ(a')/Φ(a') (in meters —
     * divided by |f'| and |f'|²). Into out — the mean and the variance.
     */
    static void sightMoments(double px, double py, double pz, double sh, double sv, double fx, double fy, double fz, double[] out) {
        double ux = fx / sh, uy = fy / sv, uz = fz / sh, len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        out[0] = out[1] = 0;
        if (!(len > 0)) return;
        double a = (px / sh * ux + py / sv * uy + pz / sh * uz) / len;
        double below = 0.5 * (1 + SmokeGrid.erf(a * SQRT_HALF));
        // far behind the eye: λ → −a − 1/a, the substance is right at the eye
        double lambda = below > 1e-12 ? Math.exp(-0.5 * a * a) / Math.sqrt(2 * Math.PI) / below : -a - 1 / a;
        out[0] = Math.max(0, a + lambda) / len;
        out[1] = Math.max(0, 1 - lambda * (lambda + a)) / (len * len);
    }

    /** Constant of thickness along the ray: √(π/2)/(2π)^{3/2}; √½. */
    private static final double SIGHT = Math.sqrt(Math.PI / 2) / Math.pow(2 * Math.PI, 1.5), SQRT_HALF = Math.sqrt(0.5);

    /**
     * Visible edge of a Gaussian of thickness τ₀ across the middle, in σ: where the thickness falls to
     * {@link #EDGE_TAU}.
     */
    static double edge(double tau) {
        return Math.clamp(Math.sqrt(2 * Math.max(0, Math.log(Math.max(tau, 1e-6) / EDGE_TAU))), 1.2, 4.0);
    }

    /**
     * Color of the thick layer of the mix on screen (one channel): albedo ω' and asymmetry g' — by the shares of the
     * attenuation of soot, dust (albedo {@code dustW}) and droplets; reflection of a semi-infinite two-stream layer R∞
     * = (1 − s)/(1 + s), s = √((1 − ω')/(1 − ω'g')) (closure by the hemisphere averages; for Eddington s is 2/√3 times
     * larger, and for soot — ω' ≈ 0.2 — the reflection would come out negative, and soot would be a black hole); to the
     * screen — power 1/2.2. Inverse — {@link SmokeGrid#dustAlbedo}.
     */
    static float thick(double soot, double dust, double water, float dustW) {
        double sum = soot + dust + water;
        double w = (soot * SmokeGrid.SOOT_W + dust * dustW + water * SmokeGrid.DROP_W) / sum;
        double g = (soot * SmokeGrid.SOOT_G + dust * SmokeGrid.DUST_G + water * SmokeGrid.DROP_G) / sum;
        return thick(w, g);
    }

    /** Color of the thick layer of a substance with albedo ω' and asymmetry g' (after delta-Eddington), one channel. */
    static float thick(double w, double g) {
        double u = Math.sqrt(Math.max((1 - w) / (1 - w * g), 0));
        return (float) Math.pow(Math.max(0, (1 - u) / (1 + u)), 1 / 2.2);
    }

    /**
     * Albedo and asymmetry of the substance with which the smoke puff's and the spray puff's maps are baked
     * ({@code tools/bake_fx/bake.py}: {@code PUFF_ALBEDO}, {@code PUFF_G}, {@code SPRAY_ALBEDO}, {@code SPRAY_G}).
     */
    static final double BAKED_W = 0.55, BAKED_G = 0.55, SPRAY_W = 0.99, SPRAY_G = 0.85;
    /**
     * Light attenuation in the thickness k of two streams for the substance of smoke and spray baking (after
     * delta-Eddington, as for {@link SmokeGrid}).
     */
    static final double BAKED_K = deltaK(BAKED_W, BAKED_G), SPRAY_K = deltaK(SPRAY_W, SPRAY_G);

    /** k of two streams of a substance with albedo w and asymmetry g after delta-Eddington (f = g²). */
    static double deltaK(double w, double g) {
        double f = g * g;
        return twoStream((1 - f) * w / (1 - f * w), g / (1 + g));
    }

    /**
     * Attenuation of scattered light in the thickness, per unit of thickness: k = √(3(1 − ω')(1 − ω'g')) (Eddington two
     * streams).
     */
    static double twoStream(double w, double g) {
        return Math.sqrt(Math.max(3 * (1 - w) * (1 - w * g), 0));
    }

    /**
     * Light attenuation in the thickness of the mix, k of two streams; shade level of the maps — k of the mix / k of
     * the baked substance of this puff ({@link #BAKED_K} for smoke, {@link #SPRAY_K} for spray). The puff's maps are
     * the light that passed its thickness τ (≈ e^(−kτ) against the average over the puff), so for a mix with a
     * different albedo the map is taken to this power ({@code fx.fsh}): white aluminum-oxide smoke and droplets (ω' ≈
     * 0.999) give almost no shadow — clear through, like a cloud; soot (ω' ≈ 0.2) — darker than the baked puff. ⚠ An
     * approximation: the light's path in the puff is the same, only its attenuation on it changes. Shares — by
     * attenuation, as for {@link #thick}.
     */
    static double shade(double soot, double dust, double water, float dustW) {
        double sum = soot + dust + water;
        if (!(sum > 0)) return BAKED_K;
        double w = (soot * SmokeGrid.SOOT_W + dust * dustW + water * SmokeGrid.DROP_W) / sum;
        double g = (soot * SmokeGrid.SOOT_G + dust * SmokeGrid.DUST_G + water * SmokeGrid.DROP_G) / sum;
        return twoStream(w, g);
    }

    /**
     * World cell of the merge at a puff at (x, y, z) with edge radius r (m): size — cell·2^octave of the edge; kind —
     * substance (not mixed).
     */
    static long cell(double x, double y, double z, double r, double cell, int kind) {
        int octave = Math.getExponent(Math.max(r, 1e-3));
        double size = Math.scalb(cell, octave);
        return mix(mix(mix(mix(kind * 4096L + octave) + (long) Math.floor(x / size)) + (long) Math.floor(y / size)) + (long) Math.floor(z / size));
    }

    /**
     * Groups of merged tube places: a group (number — the place that holds it) — all the tubes of its places, and
     * between merge levels too.
     * Two groups merge only if they share no tube: the group's holder keeps one tube, and without the group's
     * membership A+B and
     * group B+A of the next level would merge, placing one tube's places in one puff.
     */
    static final class TubeGroups {
        private long[] tube = new long[64];
        private int[] next = new int[64], tail = new int[64];
        private final Cells owners = new Cells();

        void reset() {
            owners.clear();
        }

        /** Place p of tube t — a group of one. */
        void add(int p, long t) {
            if (p >= tube.length) {
                int n = Math.max(2 * tube.length, p + 1);
                tube = java.util.Arrays.copyOf(tube, n);
                next = java.util.Arrays.copyOf(next, n);
                tail = java.util.Arrays.copyOf(tail, n);
            }
            tube[p] = t;
            next[p] = -1;
            tail[p] = p;
            owners.put(key(p, t), p);
        }

        /** Whether groups a and b share a tube. */
        boolean disjoint(int a, int b) {
            for (int m = b; m >= 0; m = next[m]) if (owners.get(key(a, tube[m])) >= 0) return false;
            return true;
        }

        /** Group b — into group a. */
        void join(int a, int b) {
            for (int m = b; m >= 0; m = next[m]) owners.put(key(a, tube[m]), a);
            next[tail[a]] = b;
            tail[a] = tail[b];
        }

        private static long key(int group, long t) {
            return mix(mix(group + 0x5851F42D4C957F2DL) ^ t);
        }
    }

    /** Key → number, by open addressing (merge cells, no objects per frame). Numbers are not less than zero. */
    private static final class Cells {
        private long[] keys = new long[1024];
        private int[] values = new int[1024];
        private int size;

        Cells() {
            java.util.Arrays.fill(values, -1);
        }

        void clear() {
            if (size == 0) return;
            java.util.Arrays.fill(values, -1);
            size = 0;
        }

        /** Number of the key; −1 — none. */
        int get(long key) {
            int mask = keys.length - 1;
            for (int i = (int) mix(key) & mask; ; i = i + 1 & mask) {
                if (values[i] < 0) return -1;
                if (keys[i] == key) return values[i];
            }
        }

        void put(long key, int value) {
            if (2 * (size + 1) > keys.length) grow();
            int mask = keys.length - 1;
            for (int i = (int) mix(key) & mask; ; i = i + 1 & mask) {
                if (values[i] < 0) {
                    keys[i] = key;
                    values[i] = value;
                    size++;
                    return;
                }
                if (keys[i] == key) {
                    values[i] = value;
                    return;
                }
            }
        }

        private void grow() {
            long[] k = keys;
            int[] v = values;
            keys = new long[2 * k.length];
            values = new int[2 * k.length];
            java.util.Arrays.fill(values, -1);
            size = 0;
            for (int i = 0; i < k.length; i++) if (v[i] >= 0) put(k[i], v[i]);
        }
    }

    /** Bit mixing (splitmix64): its own random value for each puff without objects per frame. */
    private static long mix(long z) {
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        return z ^ z >>> 31;
    }

    /** Fraction 0..1 from the low bits. */
    private static double unit(long h) {
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    /** Bell curve without tails from three fractions (a puff does not fly away from the middle). */
    private static double gauss(long h) {
        return (unit(h) + unit(h >>> 24) + unit(h >>> 40) - 1.5) * 1.15;
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
