package ua.zentix.incoming.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Nbt;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Salvos: N projectiles scattered around target, one per random pause (Shahed 20–40 ticks, missiles 15–30, B-2
 * 60–80), from one launcher by cells. Target may move; each projectile aims at its own relative offset. Different
 * routes (left/right detour, approach heading ±35°), each in its own lane ({@link WeaponSpec.Lanes}: lateral
 * shift from salvo seed, converging at target), make salvo fly and arrive as a wave from different directions
 * rather than a thread. Operator waypoints ({@link Waypoints}) give one route to entire salvo, each projectile in
 * its own lane; ordered launch site uses one launcher there, fixed launcher ({@link LaunchOrigin.Fixed}) launches
 * from itself. Salvo loads and holds launch site ({@link LaunchPlaces}) while running; waits until ready, up to
 * {@link LaunchPlaces#WAIT} for ordered site, indefinitely for fixed launcher; missing launcher block ends salvo.
 * Fixed-launcher owner gets no salvo lines/bar: signal may launch while owner is anywhere. Saved in world:
 * unfinished salvo resumes after rejoining.
 * <p>
 * Munition-paid salvo ({@link Munitions}) refunds unlaunched canceled rounds (abort, error) and failed launches
 * to owner at salvo end; a one-projectile nuclear salvo (nuclear order awaiting launch site) also refunds nuclear
 * warhead.
 */
public final class SalvoData extends SavedData {
    private static final String NAME = Incoming.MOD_ID + "_salvos";
    private static final Factory<SalvoData> FACTORY = new Factory<>(SalvoData::new, SalvoData::load, null);
    private final List<Salvo> salvos = new ArrayList<>();

    public static SalvoData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public void add(Salvo s) {
        salvos.add(s);
        setDirty();
    }

    /**
     * Cancel salvos of {@code owner} (null means all): refund paid unlaunched rounds if owner is among {@code
     * online} ({@link Munitions#refund}).
     *
     * @return canceled salvo count
     */
    public int cancel(ServerLevel level, @Nullable UUID owner, Collection<? extends ServerPlayer> online) {
        return cancel(level, owner, online, true);
    }

    /**
     * Same; {@code nuclear} includes nuclear salvo ({@link Salvo#nuclear}): like nuclear projectile, only nuclear
     * abort cancels it, not player or ordinary abort.
     */
    public int cancel(ServerLevel level, @Nullable UUID owner, Collection<? extends ServerPlayer> online, boolean nuclear) {
        int n = 0;
        for (Iterator<Salvo> it = salvos.iterator(); it.hasNext(); ) {
            Salvo s = it.next();
            if (owner != null && !owner.equals(s.owner) || !nuclear && s.nuclear()) continue;
            it.remove();
            refund(s, online, "salvo cancelled");
            n++;
        }
        if (n > 0) setDirty();
        return n;
    }

    public int size() {
        return salvos.size();
    }

    /** Salvo running from {@code origin} (fixed launcher busy until completion). */
    public static boolean busy(ServerLevel level, LaunchOrigin origin) {
        for (Salvo s : get(level).salvos) {
            if (origin.equals(s.from)) return true;
        }
        return false;
    }

    /** Player's salvo targets (tests). */
    public List<Target> centers(UUID owner) {
        List<Target> out = new ArrayList<>();
        for (Salvo s : salvos) {
            if (owner.equals(s.owner)) out.add(s.center);
        }
        return out;
    }

    /** Projectiles not yet launched in this player's salvos. */
    public int remaining(UUID owner) {
        int n = 0;
        for (Salvo s : salvos) {
            if (owner.equals(s.owner)) n += s.remaining;
        }
        return n;
    }

    /**
     * Salvo leaves list: paid salvo refunds online owner ({@code online}) unlaunched rounds and failed launches
     * ({@link Munitions#refund}). Sole refund location, called by every removal path: cancel, error, completion.
     * Nuclear warhead only occurs in one-projectile salvo.
     */
    private static void refund(Salvo s, Collection<? extends ServerPlayer> online, String why) {
        if (s.paid) {
            // Carrier nuclear warhead paid per projectile, as in Munitions.Bill.of.
            int shots = s.remaining + s.failed;
            boolean carrier = s.nuclear() && !s.weapon.spec().warhead().always();
            Munitions.refund(online, s.owner, new Munitions.Bill(s.weapon, shots, carrier ? shots : 0), why);
        }
        s.failed = 0;
    }

    void tick(ServerLevel level) {
        if (salvos.isEmpty()) return;
        salvos.removeIf(s -> {
            try {
                return !s.tick(level);
            } catch (RuntimeException e) {
                Incoming.LOG.error("Salvo failed with an exception and was cancelled", e);
                refund(s, level.getServer().getPlayerList().getPlayers(), "salvo failed with an exception");
                return true;
            }
        });
        setDirty();
    }

    /**
     * Start salvo.
     *
     * @param center target (point, entity, craft)
     * @param centerPoint current target position
     * @param yaw approach heading
     * @param owner launching player (receives salvo messages), null for console/command block
     * @param via operator waypoints for entire salvo
     * @param from ordered launch site or fixed launcher for entire salvo ({@link StrikeService#launch})
     * @param paid owner munition payment: unlaunched rounds refunded ({@link Munitions})
     */
    public static void start(ServerLevel level, WeaponType weapon, int count, int radius, Target center, Vec3 centerPoint,
                             float yaw, @Nullable UUID owner, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from, boolean paid) {
        start(level, weapon, count, radius, center, centerPoint, yaw, owner, nuke, via, from, paid, level.random.nextLong());
    }

    /** Same with salvo {@code seed}: determines lanes ({@link WeaponSpec.Lanes#offset}; tests supply their own). */
    public static void start(ServerLevel level, WeaponType weapon, int count, int radius, Target center, Vec3 centerPoint,
                             float yaw, @Nullable UUID owner, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from, boolean paid,
                             long seed) {
        Salvo salvo = new Salvo(weapon, count, count, radius, center, centerPoint, yaw, owner, 1, nuke, via, from, paid, 0, seed);
        get(level).add(salvo);
        ServerPlayer player = salvo.reporter(level);
        if (player != null) {
            player.sendSystemMessage(Component.translatable("incoming.salvo.started." + weapon.getSerializedName(), count, radius)
                    .withStyle(ChatFormatting.RED));
            PacketDistributor.sendToPlayer(player, new S2C.SalvoStatus(weapon.id(), 0, count));
        }
    }

    public static final class Salvo {
        final WeaponType weapon;
        final int total;
        int remaining;
        final int radius;
        /** Salvo target; dead entity target becomes last position ({@link #watchCenter}). */
        Target center;
        Vec3 lastCenter;
        /**
         * Stationary center airborne: decide from first surrounding projectile ({@link #around}) and retain until
         * salvo end (null means undecided). Otherwise salvo would split: center chunk loads after launch, when
         * target area becomes ready; crater from first hits under ground center is also air, making remaining
         * shots burst at center height.
         */
        @Nullable
        Boolean aloft;
        /**
         * Entity followed last tick (unsaved: first found after loading). Dead/respawned player is a new {@code
         * ServerPlayer} with same UUID; otherwise salvo would follow to respawn site and strike there. Weak
         * reference like {@code TargetTracker}: do not retain unloaded/departed entity.
         */
        @Nullable
        private WeakReference<Entity> followed;
        final float yaw;
        @Nullable
        final UUID owner;
        int cooldown;
        final Loadout.Nuke nuke;
        /** Operator waypoints shared by salvo. */
        final Waypoints via;
        /** Ordered launch site or fixed launcher shared by salvo. */
        @Nullable
        final LaunchOrigin from;
        /** Paid by owner munitions ({@link Munitions}): unlaunched rounds refunded. */
        final boolean paid;
        /** Failed salvo launches: refunded at salvo end (one line, not per projectile). */
        int failed;
        /** Launch-site area key ({@link LaunchPlaces}; unsaved like tickets: reacquired after loading). */
        final UUID placeKey = UUID.randomUUID();
        /** Ticks salvo has waited for launch-site area (unsaved). */
        int waited;
        /** Salvo seed: each projectile's route lane ({@link WeaponSpec.Lanes#offset}). */
        final long seed;

        Salvo(WeaponType weapon, int total, int remaining, int radius, Target center, Vec3 lastCenter, float yaw, @Nullable UUID owner, int cooldown,
              Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from, boolean paid, int failed, long seed) {
            this.weapon = weapon;
            this.total = total;
            this.remaining = remaining;
            this.radius = radius;
            this.center = center;
            this.lastCenter = lastCenter;
            this.yaw = yaw;
            this.owner = owner;
            this.cooldown = cooldown;
            // No nuclear salvos (ServerActions.clamp): salvo projectiles carry no nuclear warhead; a one-projectile salvo, a nuclear
            // order awaiting launch site (LaunchPlaces), does carry it.
            this.nuke = total == 1 ? nuke : nuke.withOnCarrier(false);
            this.via = via;
            this.from = from;
            this.paid = paid;
            this.failed = failed;
            this.seed = seed;
        }


        /** Salvo carries nuclear warhead (one-projectile salvo: nuclear order awaiting launch site). */
        boolean nuclear() {
            return nuke.onCarrier() && Loadout.carriesNuke(weapon);
        }

        /** Salvo lines/bar recipient: online owner, unless from fixed launcher. */
        @Nullable
        ServerPlayer reporter(ServerLevel level) {
            return owner == null || from instanceof LaunchOrigin.Fixed ? null : level.getServer().getPlayerList().getPlayer(owner);
        }

        boolean tick(ServerLevel level) {
            watchCenter(level);
            if (from != null && LaunchPlaces.fromPad(weapon)) {
                // Launch site loads/ticks while salvo runs: launcher stands there and projectiles depart from it.
                LaunchPlaces.hold(level, from, placeKey);
                // Unready: wait (salvo pause stops); ordered site waits at most WAIT, then starts without launcher.
                if (!LaunchPlaces.ready(level, from)) {
                    if (from instanceof LaunchOrigin.Fixed || waited < LaunchPlaces.WAIT) {
                        waited++;
                        return true;
                    }
                    if (waited++ == LaunchPlaces.WAIT) {
                        Incoming.LOG.warn("Salvo: {} — launch place {} {} not loaded in {} ticks, launching from it without a launcher",
                                weapon.getSerializedName(), Mth.floor(from.at().x), Mth.floor(from.at().z), LaunchPlaces.WAIT);
                    }
                }
            }
            if (from instanceof LaunchOrigin.Fixed fixed) {
                // Fixed launcher: missing block ends salvo.
                if (fixed.launcher(level) == null) {
                    Incoming.LOG.info("Salvo: {} — launcher {} is gone, remaining {} cancelled", weapon.getSerializedName(),
                            fixed.pos().toShortString(), remaining);
                    refund(this, level.getServer().getPlayerList().getPlayers(), "launcher is gone");
                    return false;
                }
            }
            if (--cooldown > 0) return true;
            ServerPlayer ownerPlayer = reporter(level);
            if (remaining <= 0) {
                if (ownerPlayer != null) {
                    ownerPlayer.displayClientMessage(Component.translatable("incoming.salvo.done").withStyle(ChatFormatting.GRAY), true);
                    PacketDistributor.sendToPlayer(ownerPlayer, new S2C.SalvoStatus(weapon.id(), total, total));
                }
                if (failed > 0) refund(this, level.getServer().getPlayerList().getPlayers(), "salvo launches failed");
                return false;
            }
            if (!fire(level).ok()) failed++;
            remaining--;
            cooldown = weapon.salvoGap(level.random);
            if (ownerPlayer != null) PacketDistributor.sendToPlayer(ownerPlayer, new S2C.SalvoStatus(weapon.id(), total - remaining, total));
            return true;
        }

        /**
         * Every tick, not just launch: dead entity target makes remaining salvo strike last point rather than
         * respawned/new same-UUID entity. An entity changing dimension or disappearing from world has not died:
         * salvo still follows it (last point while absent).
         */
        private void watchCenter(ServerLevel level) {
            if (!(center instanceof Target.OfEntity target)) return;
            Entity last = followed == null ? null : followed.get();
            if (last != null && died(last)) {
                center = new Target.Point(lastCenter);
                followed = null;
                Incoming.LOG.info("Salvo: {} — target {} died, remaining {} go to its last position {}", weapon.getSerializedName(),
                        target.uuid(), remaining, BlockPos.containing(lastCenter));
                return;
            }
            Entity now = level.getEntity(target.uuid());
            if (now == null) return;
            if (last != now) followed = new WeakReference<>(now);
            target.resolve(level, now).ifPresent(p -> lastCenter = p);
        }

        private static boolean died(Entity e) {
            return e instanceof LivingEntity l ? l.isDeadOrDying() : e.getRemovalReason() == Entity.RemovalReason.KILLED;
        }

        private StrikeService.Result fire(ServerLevel level) {
            center.resolve(level).ifPresent(p -> lastCenter = p);
            int dx = 0, dz = 0;
            if (radius > 0) {
                // Uniform circle: up to 8 attempts rejecting square points.
                for (int i = 0; i < 9; i++) {
                    dx = level.random.nextIntBetweenInclusive(-radius, radius);
                    dz = level.random.nextIntBetweenInclusive(-radius, radius);
                    if (dx * dx + dz * dz <= radius * radius) break;
                }
            }
            Target shot;
            Vec3 point;
            if (!(center instanceof Target.Point) && !(center instanceof Target.Sighted)) {
                // Moving target or ground site: own relative offset (ground site has its own ground height).
                shot = center.offset(new Vec3(dx, 0, dz));
                point = shot.resolve(level).orElse(lastCenter.add(dx, 0, dz));
            } else {
                // Stationary center: point, or sighted target where seen (salvo projectile follows it only by camera).
                Target place = around(level, dx, dz);
                point = place.resolve(level).orElseThrow();
                shot = center instanceof Target.Sighted sighted ? sighted.at(point) : place;
            }
            float shotYaw = yaw + (level.random.nextInt(7001) - 3500) / 100f;
            double lane = weapon.spec().lanes().offset(seed, total - remaining, total);
            // One siren per salvo: first projectile enables it when “seen” approaching.
            return StrikeService.launch(level, weapon, shot, point, shotYaw, owner, remaining == total, nuke, via, from, lane);
        }

        /**
         * Salvo projectile target offset (dx, dz) from stationary center. Ground center uses ground site in its
         * own column ({@link Target.Ground}): projectile takes height from chunk once ready (target area loads on
         * approach); until then, estimate from center height. A point at center height for distant unready target
         * would hang above street near tower or above lowland, causing airburst.
         */
        private Target around(ServerLevel level, int dx, int dz) {
            if (aloft == null) aloft = inAir(level, lastCenter);
            // Bomb at center depth (finds cave under player); airborne center at its height.
            if (weapon.spec().penetrates() || radius == 0 || aloft) return new Target.Point(lastCenter.add(dx, 1, dz));
            return new Target.Ground(lastCenter.add(dx, 0, dz));
        }

        /**
         * Salvo center airborne (player on craft, flying): strike at center height, not ground below. Unready
         * center chunk means not airborne: dispersed salvo at distant site strikes ground around it.
         */
        private static boolean inAir(ServerLevel level, Vec3 c) {
            BlockPos p = BlockPos.containing(c);
            if (!Terrain.ready(level, p)) return false;
            for (int i = 1; i <= 3; i++) {
                if (!level.getBlockState(p.below(i)).is(ModTags.PASSABLE)) return false;
            }
            return true;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("weapon", weapon.getSerializedName());
            t.putInt("total", total);
            t.putInt("remaining", remaining);
            t.putInt("radius", radius);
            Target.CODEC.encodeStart(NbtOps.INSTANCE, center).resultOrPartial(Incoming.LOG::error).ifPresent(c -> t.put("center", c));
            Nbt.putVec(t, "", lastCenter);
            t.putFloat("yaw", yaw);
            if (owner != null) t.putUUID("owner", owner);
            t.putInt("cooldown", cooldown);
            Loadout.Nuke.CODEC.encodeStart(NbtOps.INSTANCE, nuke).resultOrPartial(Incoming.LOG::error).ifPresent(n -> t.put("nuke", n));
            if (!via.isEmpty()) Waypoints.CODEC.encodeStart(NbtOps.INSTANCE, via).resultOrPartial(Incoming.LOG::error).ifPresent(r -> t.put("route", r));
            LaunchOrigin.save(t, from);
            if (paid) t.putBoolean("paid", true);
            if (failed > 0) t.putInt("failed", failed);
            if (aloft != null) t.putBoolean("aloft", aloft);
            t.putLong("seed", seed);
            return t;
        }

        static Optional<Salvo> load(CompoundTag t) {
            WeaponType w = WeaponType.parse(t.getString("weapon"));
            if (w == null) return Optional.empty();
            Vec3 last = Nbt.getVec(t, "");
            if (last == null) last = Vec3.ZERO;
            Target c = Target.CODEC.parse(NbtOps.INSTANCE, t.get("center")).resultOrPartial(Incoming.LOG::error).orElse(new Target.Point(last));
            Loadout.Nuke nuke = Loadout.Nuke.CODEC.parse(NbtOps.INSTANCE, t.get("nuke")).result().orElse(Loadout.Nuke.DEFAULT);
            // Salvo saved before operator routes has no waypoints.
            Waypoints via = t.contains("route") ? Waypoints.CODEC.parse(NbtOps.INSTANCE, t.get("route")).resultOrPartial(Incoming.LOG::error)
                    .orElse(Waypoints.NONE) : Waypoints.NONE;
            // Salvo saved before ordered launch sites has none; before munitions is unpaid, nothing to refund; before
            // lanes has seed 0: remainder uses its lanes.
            Salvo s = new Salvo(w, t.getInt("total"), t.getInt("remaining"), t.getInt("radius"), c, last, t.getFloat("yaw"),
                    t.hasUUID("owner") ? t.getUUID("owner") : null, t.getInt("cooldown"), nuke, via, LaunchOrigin.load(t), t.getBoolean("paid"),
                    t.getInt("failed"), t.getLong("seed"));
            if (t.contains("aloft")) s.aloft = t.getBoolean("aloft");
            return Optional.of(s);
        }
    }

    private static SalvoData load(CompoundTag tag, HolderLookup.Provider registries) {
        SalvoData d = new SalvoData();
        for (Tag t : tag.getList("salvos", Tag.TAG_COMPOUND)) Salvo.load((CompoundTag) t).ifPresent(d.salvos::add);
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Salvo s : salvos) list.add(s.save());
        tag.put("salvos", list);
        return tag;
    }
}
