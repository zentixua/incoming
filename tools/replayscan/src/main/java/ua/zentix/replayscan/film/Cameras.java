package ua.zentix.replayscan.film;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cameras of the plan: candidates according to its description ({@link Plan.Camera}) and their assessment in the world of replay before output - the object is in the frame
 *  and not behind the house, the events are visible, the camera is not in the blocks, the smoke of previous attacks is not in the line of sight, the size in the frame is not smaller than the
 *  threshold. The output then checks the same for each frame ({@link FrameLog}) with the same rays and the same frame ({@link View},
 * {@link #open}, {@link #keySeen}): the evaluation predicts its outcome, rather than guessing about it.
 */
final class Cameras {
    private Cameras() {}

    /** Camera pose: position of the eye, rotation like the player (yaw 0 - south, pitch down - plus), vertical frame angle. */
    record Pose(Vec3 pos, float yaw, float pitch, double fov) {
        static Pose look(Vec3 eye, Vec3 at, double fov) {
            Vec3 d = at.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            return new Pose(eye, yaw, pitch, fov);
        }
    }

    /** Camera by tick replay (with share); roll - degrees, plus - left (camera key Flashback). */
    interface Path {
        Pose at(double t);

        default double roll(double t) {
            return 0;
        }
    }

    /**
     * Candidate: keyframe camera ({@code path}) or player's eyes ({@code player}, no way - camera Flashback
     *  becomes that player). {@code designed} - the camera is specified by the plan and is displayed first, if not rejected;
     * {@code roam} - the path is specified by the plan and goes where the client does not have chunks (in blocks, the camera is only where there is a chunk).
     */
    record Candidate(String name, @Nullable Path path, @Nullable String player, boolean gui, double fov, boolean designed, boolean roam) {}

    /**
     * Candidate assessment: the result (less than zero is not good), what it is from and whether the conclusion will pass the thresholds of the plan ({@code pass}: object
     *  before the event, for the last second and the event - no less than shares of {@link Plan.Need}).
     */
    record Score(double total, double approach, double near, double keys, double after, double size, double smoke, boolean pass, String why) {
        static final Score DESIGNED = new Score(1, 1, 1, 1, 1, 1, 0, true, "designed camera");
    }

    /** Thickness of the layer of clouds, blocks. */
    static final double CLOUD_DEPTH = 6;
    /** The fraction of the rendering distance beyond which an object in the fog at its edge (in the shader pack the fog is earlier and smoother). */
    static final double FOG = 0.8;
    /** Frame field in which the score holds the object (output - {@link FrameLog}: 0.92): The camera leads the path, not the entity. */
    static final double SAFE = 0.85;

    /**
     * Camera frame: eye, axes (forward, right, up) and tangents of half the angle in width and height. The output is based on the
     *  camera and the projection of the frame, the assessment is based on the pose and sides of the frame.
     */
    record View(Vec3 eye, Vec3 fwd, Vec3 right, Vec3 up, double tanX, double tanY) {
        static View of(Pose p, double aspect) {
            Vec3 fwd = Vec3.directionFromRotation(p.pitch(), p.yaw());
            Vec3 right = fwd.cross(new Vec3(0, 1, 0));
            right = right.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : right.normalize();
            double ty = Math.tan(Math.toRadians(p.fov() / 2));
            return new View(p.pos(), fwd, right, right.cross(fwd).normalize(), ty * aspect, ty);
        }

        /**
         * Frame with roll (degrees, plus - to the left, like the Flashback key): Flashback rotates the camera around its forward
         *  axis ({@code rotateZ}) after the camera has calculated its vectors - they have no roll.
         */
        View rolled(double roll) {
            if (roll == 0) return this;
            double c = Math.cos(Math.toRadians(roll)), s = Math.sin(Math.toRadians(roll));
            return new View(eye, fwd, right.scale(c).add(up.scale(s)), up.scale(c).subtract(right.scale(s)), tanX, tanY);
        }

        /** Point in the frame (half-frame fraction {@code safe}: 1 - to the edge). */
        boolean in(Vec3 at, double safe) {
            Vec3 d = at.subtract(eye);
            double z = d.dot(fwd);
            return z > 0.1 && Math.abs(d.dot(right) / z) <= tanX * safe && Math.abs(d.dot(up) / z) <= tanY * safe;
        }

        /** The fraction of the frame width occupied by an object with a diameter of {@code size} at point {@code at}. */
        double share(Vec3 at, double size) {
            double z = at.subtract(eye).dot(fwd);
            return z <= 0.1 ? 0 : size / (2 * z * tanX);
        }
    }

    /**
     * The proportion of open rays from the eye to an object with a diameter of {@code size}: to the middle and to the edges across the gaze ({@code rays}
     * — 3: middle and sides, 5: top and bottom); the last {@code max(1.5, 0.4·size)} blocks of an item do not count. Rays -
     *  on the client's blocks: where he does not have a chunk (behind the replay chunks), there is air, and the relief is then drawn by Distant Horizons.
     */
    static double open(ClientLevel level, Vec3 eye, Vec3 at, Vec3 right, Vec3 up, double size, int rays) {
        Vec3 r = right.scale(size * 0.35), u = up.scale(size * 0.35);
        Vec3[] pts = rays == 3 ? new Vec3[]{at, at.add(r), at.subtract(r)} : new Vec3[]{at, at.add(r), at.subtract(r), at.add(u), at.subtract(u)};
        double spare = Math.max(1.5, size * 0.4);
        int open = 0;
        for (Vec3 p : pts) {
            Vec3 d = p.subtract(eye);
            double len = d.length();
            if (len <= spare) {
                open++;
                continue;
            }
            var hit = level.clip(new ClipContext(eye, eye.add(d.scale((len - spare) / len)), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                    CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS) open++;
        }
        return (double) open / pts.length;
    }

    /** Then {@link #FOG} draws horizontally (the fog in vanilla is a cylinder around the camera). */
    static boolean beyondFog(Vec3 eye, Vec3 p) {
        return Math.hypot(p.x - eye.x, p.z - eye.z) > FOG * Minecraft.getInstance().gameRenderer.getRenderDistance();
    }

    /**
     * An event with a diameter of {@code size} at point {@code p} is visible in frame {@code v}: in the frame to the edge, at least 0.6
     *  rays are open to the middle and sides, not through the clouds at the bottom {@code clouds} and not in the fog at the edge of the rendering (distant - without
     * fog). This is how both the conclusion and the assessment consider it.
     */
    static boolean keySeen(ClientLevel level, double clouds, boolean far, View v, Vec3 p, double size) {
        return v.in(p, 1) && open(level, v.eye(), p, v.right(), v.up(), size, 3) >= 0.6 && !throughClouds(clouds, v.eye(), p) && (far || !beyondFog(v.eye(), p));
    }

    /** Where is the event at tick {@code t}: the fireball rises by 0.4 in diameter for 40 tick. */
    static Vec3 keyPoint(Subject.Key k, double t) {
        return k.pos().add(0, Math.min(1, (t - k.t()) / 40.0) * k.size() * 0.4, 0);
    }

    /** Bottom of clouds in the frame: from the plan (shaderpack layer), otherwise dimension clouds; clouds off - NaN. */
    static double cloudHeight(Plan plan) {
        if (!Double.isNaN(plan.clouds())) return plan.clouds();
        Minecraft mc = Minecraft.getInstance();
        return mc.options.getCloudsType() == CloudStatus.OFF ? Double.NaN : mc.level.effects().getCloudHeight();
    }

    /** The segment of view passes through a layer of clouds at the bottom of {@code clouds} (or the camera in it). */
    static boolean throughClouds(double clouds, Vec3 from, Vec3 to) {
        if (Double.isNaN(clouds)) return false;
        return Math.max(from.y, to.y) >= clouds - 1 && Math.min(from.y, to.y) <= clouds + CLOUD_DEPTH;
    }

    /** The client's world of replay: rays and heights only for ready-made chunks (the client has no chunks - no blocks), clouds. */
    static final class World {
        final ClientLevel level = Minecraft.getInstance().level;
        /** Bottom of clouds ({@link #cloudHeight}). */
        final double clouds;

        World(double clouds) {
            this.clouds = clouds;
        }

        boolean loaded(Vec3 p) {
            return level.getChunkSource().getChunkNow(Mth.floor(p.x) >> 4, Mth.floor(p.z) >> 4) != null;
        }

        /** The height of the ground (the top of the blocks that hold the movement, with foliage); NaN - no chunk. */
        double ground(double x, double z) {
            if (!loaded(new Vec3(x, 0, z))) return Double.NaN;
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
        }

        /** Camera is outside blocks with half a block of air around it; absent chunk means unknown. */
        boolean free(Vec3 p) {
            return loaded(p) && !inBlocks(level, p);
        }

        /** The proportion of open rays from the eye of the frame to the subject ({@link Cameras#open}). */
        double open(View v, Vec3 at, double size, int rays) {
            return Cameras.open(level, v.eye(), at, v.right(), v.up(), size, rays);
        }
    }

    /** Camera in a block: there is a block closer than half a block from it (according to client chunks: no chunk - no blocks). */
    static boolean inBlocks(ClientLevel level, Vec3 p) {
        for (double dx : new double[]{-0.5, 0.5}) {
            for (double dy : new double[]{-0.5, 0.5}) {
                for (double dz : new double[]{-0.5, 0.5}) {
                    BlockPos b = BlockPos.containing(p.x + dx, p.y + dy, p.z + dz);
                    if (level.getChunkSource().getChunkNow(b.getX() >> 4, b.getZ() >> 4) == null) continue;
                    if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /**
     * The vertical frame angle at which an object with a diameter of {@code size} in {@code dist} blocks occupies {@code share}
     *  of the width of the frame with sides {@code aspect} (width to height), is not narrower than 6° and not wider than 70°.
     */
    static double fovFor(double size, double dist, double share, double aspect) {
        double tanX = size / (2 * dist * share);
        return Mth.clamp(Math.toDegrees(2 * Math.atan(tanX / aspect)), 6, 70);
    }

    /** Single camera plan candidates. */
    static List<Candidate> of(Plan.Camera c, Plan.Shot shot, double aspect, World w) {
        Subject s = shot.subject();
        int a = shot.a(), b = shot.b();
        List<Candidate> out = new ArrayList<>();
        switch (c.kind()) {
            case "pov" -> out.add(new Candidate("pov-" + c.player().replaceAll("[^A-Za-z0-9_.-]", "_"), null, c.player(), c.gui(), c.fov(), true, false));
            case "fixed" -> {
                Vec3 at = c.aim() != null ? c.aim() : s.anchor(a, b);
                if (c.eye() == null || at == null && !c.follow()) return out;
                // above the eye, where it is set: the player’s place is in a house or subway, and above the roofs is the horizon
                for (double rise : c.rise()) {
                    Vec3 eye = settle(lift(c.eye(), rise, w), at, w);
                    Path p = c.follow() ? follow(t -> eye, s, a, b, 3, c.fov()) : t -> Pose.look(eye, at, c.fov());
                    String name = String.format(Locale.ROOT, "fixed-%.0f", c.fov()) + (rise > 0 ? String.format(Locale.ROOT, "-r%.0f", rise) : "");
                    out.add(new Candidate(name, p, null, false, c.fov(), true, false));
                }
            }
            case "tripod" -> {
                Vec3 k = c.around() != null ? c.around() : s.anchor(a, b);
                if (k == null) return out;
                Vec3 h = c.heading() != null ? c.heading().normalize() : s.heading();
                double size = c.size() > 0 ? c.size() : !s.keys.isEmpty() ? s.keys.get(0).size() : s.area != null ? s.areaSize : s.size * 4;
                for (double dist : c.dist()) {
                    double fov = fovFor(size, dist, c.share(), aspect);
                    for (double side = 0; side < 360; side += c.azStep()) {
                        for (double elev : c.elev()) {
                            double r = Math.toRadians(side);
                            // from the place back along the approach course, rotated to side: 0 - camera on the approach path, the projectile
                            //  flies from it to the place; 90 - across the frame; 180 - flies into the camera
                            Vec3 dir = new Vec3(-h.x * Math.cos(r) + h.z * Math.sin(r), 0, -h.z * Math.cos(r) - h.x * Math.sin(r));
                            Vec3 spot = k.add(dir.scale(dist));
                            double g = w.ground(spot.x, spot.z);
                            if (Double.isNaN(g)) continue;
                            Vec3 eye = new Vec3(spot.x, Math.max(g + 2, k.y + dist * Math.tan(Math.toRadians(elev))), spot.z);
                            // events are not visible above the clouds
                            if (!Double.isNaN(w.clouds) && eye.y >= w.clouds - 2) continue;
                            Path p = c.follow() ? follow(t -> eye, s, a, b, 6, fov) : t -> Pose.look(eye, k.add(0, size * 0.25, 0), fov);
                            out.add(new Candidate(String.format(Locale.ROOT, "tripod-f%.0f-d%.0f-a%.0f-e%.0f", fov, dist, side, elev), p, null,
                                    false, fov, false, false));
                        }
                    }
                }
            }
            case "path" -> {
                // and higher on rise, where it is specified: the path laid out on the map goes through a high-rise building
                for (double rise : c.keys().isEmpty() ? new double[0] : c.rise()) {
                    List<Plan.PathKey> keys = c.keys().stream().map(k -> new Plan.PathKey(k.t(), k.eye().add(0, rise, 0), k.aim().add(0, rise, 0), k.roll())).toList();
                    String name = String.format(Locale.ROOT, "path-%.0f", c.fov()) + (rise > 0 ? String.format(Locale.ROOT, "-r%.0f", rise) : "");
                    out.add(new Candidate(name, along(keys, c.fov()), null, false, c.fov(), true, true));
                }
            }
            case "pilot" -> out.addAll(pilot(c, shot, aspect, w));
            default -> throw new IllegalArgumentException("unknown camera " + c.kind());
        }
        return out;
    }

    /** The pilot's flights on the plan (the best are different). */
    static final int PILOT_FLIGHTS = 3;
    /** The relief for the pilot is the square of so many blocks from the impact in each direction (the passage and the rays of the bottom of the frame in it). */
    static final int PILOT_REACH = 400;

    /**
     * Pilot's camera FPV ({@link Pilot}): flights past the impact of the plan on the client's replay world and the smoke of impacts
     *  ({@link Plume}), with the operator's hands and the shocks of explosions ({@link Hands}); There is no knock on the window - there are no candidates.
     */
    static List<Candidate> pilot(Plan.Camera c, Plan.Shot shot, double aspect, World w) {
        Subject s = shot.subject();
        Subject.Key main = Pilot.mainKey(s, shot.a(), shot.b());
        if (main == null) {
            Film.LOG.warn("Film: {}: pilot: no event in the shot", shot.name());
            return List.of();
        }
        long start = System.nanoTime();
        List<Plume> plumes = new ArrayList<>();
        for (double[] row : shot.live()) {
            Plume p = Plume.of(row);
            // strike the plan - with your fireball
            Subject.Key k = s.keys.stream().filter(o -> Math.abs(o.t() - p.t()) <= 2 && Plume.beside(o.pos(), p.pos(), 4)).findFirst().orElse(null);
            plumes.add(k == null ? p : new Plume(p.t(), p.pos(), k.size(), p.look()));
        }
        if (plumes.stream().noneMatch(p -> Math.abs(p.t() - main.t()) <= 2 && Plume.beside(p.pos(), main.pos(), 4)))
            plumes.add(new Plume(main.t(), main.pos(), main.size(), Plume.Look.bySize(main.size())));
        Pilot.Grid grid = new Pilot.Grid((x, z) -> w.ground(x, z), Mth.floor(main.pos().x), Mth.floor(main.pos().z), PILOT_REACH);
        Pilot pilot = new Pilot(shot.a(), shot.b(), c.fov(), aspect, s, main, plumes, grid, shot.name().hashCode());
        List<Hands.Blast> blasts = plumes.stream().filter(p -> p.t() >= shot.a() - 80 && p.t() <= shot.b())
                .map(p -> new Hands.Blast(p.t(), p.pos(), p.size())).toList();
        List<Candidate> out = new ArrayList<>();
        for (Pilot.Flight f : pilot.plan(PILOT_FLIGHTS)) {
            Film.LOG.info("Film: {}: {}: {}", shot.name(), f.name(), f.why());
            Path p = Hands.humanize(pilot.path(f), shot.name().hashCode() * 31L + f.name().hashCode(), blasts,
                    shot.a() - Pilot.BEFORE, shot.b() + Pilot.AFTER);
            out.add(new Candidate(f.name(), p, null, false, c.fov(), true, false));
        }
        Film.LOG.info("Film: {}: pilot planned {} flights past the hit at tick {} in {} ms", shot.name(), out.size(), main.t(),
                (System.nanoTime() - start) / 1_000_000);
        return out;
    }

    /**
     * The camera flies along the keys of the plan: the eye and the point of view - in a straight line between adjacent keys (smooth course, course without
     *  jerks), before the first key - in the first, after the last - in the last.
     */
    static Path along(List<Plan.PathKey> keys, double fov) {
        return new Path() {
            @Override
            public Pose at(double t) {
                Plan.PathKey[] k = around(t);
                double u = share(k, t);
                return Pose.look(k[0].eye().lerp(k[1].eye(), u), k[0].aim().lerp(k[1].aim(), u), fov);
            }

            @Override
            public double roll(double t) {
                Plan.PathKey[] k = around(t);
                return Mth.lerp(share(k, t), k[0].roll(), k[1].roll());
            }

            private Plan.PathKey[] around(double t) {
                Plan.PathKey a = keys.get(0), b = a;
                for (Plan.PathKey k : keys) {
                    if (k.t() <= t) a = k;
                    b = k;
                    if (k.t() >= t) break;
                }
                return new Plan.PathKey[]{a, b};
            }

            private double share(Plan.PathKey[] k, double t) {
                return k[1].t() > k[0].t() ? Mth.clamp((t - k[0].t()) / (k[1].t() - k[0].t()), 0, 1) : 0;
            }
        };
    }

    /** The eye is {@code rise} blocks higher than the ground below it (not lower than the eye itself); 0 - the eye itself. */
    static Vec3 lift(Vec3 eye, double rise, World w) {
        if (rise <= 0) return eye;
        double g = w.ground(eye.x, eye.z);
        return new Vec3(eye.x, Math.max(eye.y, Double.isNaN(g) ? eye.y : g) + rise, eye.z);
    }

    /**
     * The place closest to the eye in the air ({@link World#free}): the player's eye is at the wall or under the ceiling closer than half a block -
     *  up to 2 blocks and towards the target ({@code toward}; null - only up) up to 3 horizontally; not found - the eye itself (the rating
     *  will reject it).
     */
    static Vec3 settle(Vec3 eye, @Nullable Vec3 toward, World w) {
        if (w.free(eye)) return eye;
        Vec3 d = toward == null ? Vec3.ZERO : toward.subtract(eye).multiply(1, 0, 1);
        Vec3 dir = d.lengthSqr() < 1e-6 ? Vec3.ZERO : d.normalize();
        for (double up = 0; up <= 2; up += 0.5) {
            for (double fwd = 0; fwd <= 3; fwd += 0.5) {
                Vec3 p = eye.add(dir.scale(fwd)).add(0, up, 0);
                if (w.free(p)) return p;
            }
        }
        return eye;
    }

    /**
     * The camera in {@code place(t)} follows the object like an operator: the gaze catches up with the target with a delay of {@code lag}
     *  ticks (table with a tick step from the window with a margin - the same in evaluation and output).
     */
    static Path follow(java.util.function.DoubleFunction<Vec3> place, Subject s, int a, int b, double lag, double fov) {
        int from = a - 60;
        Vec3[] look = new Vec3[b - from + 2];
        Vec3 cur = null;
        double k = 1 - Math.exp(-1 / Math.max(0.01, lag));
        for (int t = from; t <= b + 1; t++) {
            Vec3 want = s.aim(t);
            if (want == null) want = place.apply(t).add(0, 0, 1);
            cur = cur == null ? want : cur.lerp(want, k);
            look[t - from] = cur;
        }
        return t -> {
            double u = Mth.clamp(t - from, 0, look.length - 1);
            int i = (int) Math.floor(u);
            return Pose.look(place.apply(t), look[i].lerp(look[Math.min(look.length - 1, i + 1)], u - i), fov);
        };
    }

    /**
     * Evaluation of the candidate before the output with the same rays and frame as the output ({@link FrameLog}): before the event - the tick fraction, when the
     *  object in the frame (with fields {@link #SAFE}) is visible (rays to the middle and edges - no less than 0.4, not through clouds and not in
     *  fog), no smaller {@code share} frame width and not in the smoke of previous strikes - for the entire time and for the last second;
     *  each event of the plan - in the frame and visible in at least half of the first 12 tick ({@link #keySeen}); after events -
     * object visible; the camera is in the air along the entire path (the plan path, {@code roam}, is where the client has a chunk). {@code pass} -
     * these shares are not less than the plan thresholds.
     */
    static Score score(Candidate c, Subject s, Plan.Shot shot, double aspect, World w) {
        if (c.path() == null) return Score.DESIGNED;
        Plan.Need need = shot.need();
        int n = 0, ok = 0, nearN = 0, nearOk = 0, afterN = 0, afterOk = 0, samples = 0;
        double sizeSum = 0, smokeHits = 0;
        boolean free = true;
        int first = s.firstKey();
        List<double[]> live = shot.live();
        for (int t = shot.a(); t <= shot.b(); t += 2) {
            Pose p = c.path().at(t);
            if (c.roam() ? w.loaded(p.pos()) && !w.free(p.pos()) : !w.free(p.pos())) free = false;
            Vec3 aim = s.aim(t);
            if (aim == null) continue;
            Subject.Key k = s.keyAt(t);
            boolean before = first < 0 || t < first;
            // the first tick of events is in the count of events below
            if (!before && k != null && t <= k.t() + FrameLog.KEY_CHECK) continue;
            samples++;
            double size = s.aimSize(t);
            View v = View.of(p, aspect).rolled(c.path().roll(t));
            boolean seen = v.in(aim, SAFE) && w.open(v, aim, size, 5) >= 0.4 && !throughClouds(w.clouds, p.pos(), aim)
                    && (shot.far() || !beyondFog(p.pos(), aim));
            boolean smoky = smokeOnLine(p.pos(), aim, live, t);
            if (smoky) smokeHits++;
            if (before) {
                double share = v.share(aim, size);
                boolean good = seen && share >= need.share() && !smoky;
                n++;
                sizeSum += share;
                if (good) ok++;
                if (first >= 0 && t >= first - 20) {
                    nearN++;
                    if (good) nearOk++;
                }
            } else {
                afterN++;
                if (seen) afterOk++;
            }
        }
        // plan events, as the output considers them: each is in the frame and visible in at least half of its first tick
        int keysIn = 0, keysOk = 0;
        for (Subject.Key k : s.keys) {
            if (k.t() < shot.a() || k.t() > shot.b()) continue;
            keysIn++;
            int seen = 0;
            for (int t = k.t(); t <= k.t() + FrameLog.KEY_CHECK; t++) {
                if (keySeen(w.level, w.clouds, shot.far(), View.of(c.path().at(t), aspect).rolled(c.path().roll(t)), keyPoint(k, t), k.size())) seen++;
            }
            if (seen * 2 >= FrameLog.KEY_CHECK + 1) keysOk++;
        }
        double approach = n == 0 ? 1 : (double) ok / n, near = nearN == 0 ? 1 : (double) nearOk / nearN;
        double keys = keysIn == 0 ? 1 : (double) keysOk / keysIn, after = afterN == 0 ? 1 : (double) afterOk / afterN;
        double meanShare = n == 0 ? 0 : sizeSum / n, smoke = smokeHits / Math.max(1, samples);
        boolean pass = free && approach >= need.subject() && near >= need.near() && keys >= need.keys();
        String why = (free ? "" : "camera inside blocks; ")
                + (keys < need.keys() ? String.format(Locale.ROOT, "events seen: %d of %d; ", keysOk, keysIn) : "")
                + (approach < need.subject() ? String.format(Locale.ROOT, "subject shown in %.0f%% before the event; ", 100 * approach) : "")
                + (near < need.near() ? String.format(Locale.ROOT, "in the last second before the event: %.0f%%; ", 100 * near) : "");
        double total = !free ? -1 : approach * 0.45 + keys * 0.35 + after * 0.1 + Math.min(1, meanShare / 0.06) * 0.1 - smoke * 0.3;
        return new Score(total, approach, near, keys, after, meanShare, smoke, pass, why.trim());
    }

    /**
     * The smoke of the impact of the plan ({@code [t, x, y, z]} and the diameter of the ball, where known) on the line of sight - according to the model of the smoke of the impact
     *  ({@link Plume}): the impact column, which is still smoking, and not the smoke of the target.
     */
    static boolean smokeOnLine(Vec3 from, Vec3 to, List<double[]> smoke, int t) {
        for (double[] h : smoke) {
            if (h[0] <= t && Plume.of(h).blocks(from, to, t)) return true;
        }
        return false;
    }
}
