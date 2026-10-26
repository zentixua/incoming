package ua.zentix.incoming.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.strike.Waypoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Site selected on the targeting map, operator-placed route waypoints ({@link Waypoints}), and launch site if chosen
 * by the operator or host (otherwise launch near the shooter): retained while the player stays in the dimension
 * and world, so repeated strikes use the same route and site. Only x and z: server finds ground height there
 * ({@code Target.Ground.at}; {@code LaunchSite} places the launcher at the launch site); map surface height
 * ({@link TerrainTiles#height}) accompanies the order only as an estimate.
 */
public final class MapTarget {
    public record Place(double x, double z) {}

    /** Dimension of selected site and waypoints; absent in another dimension. */
    @Nullable
    private static ResourceKey<Level> dimension;
    @Nullable
    private static Place point;
    /** Waypoints in flight order, at most {@link Waypoints#MAX}. */
    private static final List<Place> route = new ArrayList<>();
    /** Launch site; null means launch near the shooter. */
    @Nullable
    private static Place launch;

    private MapTarget() {}

    public static void set(ClientLevel level, Place point) {
        bind(level);
        MapTarget.point = point;
    }

    public static Optional<Place> get(@Nullable ClientLevel level) {
        return here(level) ? Optional.ofNullable(point) : Optional.empty();
    }

    /** Waypoints in this dimension (copy). */
    public static List<Place> route(@Nullable ClientLevel level) {
        return here(level) ? List.copyOf(route) : List.of();
    }

    /** Route for the order. */
    public static Waypoints waypoints(@Nullable ClientLevel level) {
        return new Waypoints(route(level).stream().map(p -> new Vec3(p.x(), 0, p.z())).toList());
    }

    /** Append a waypoint before the target; no more than {@link Waypoints#MAX}. */
    public static boolean add(ClientLevel level, Place place) {
        bind(level);
        if (route.size() >= Waypoints.MAX) return false;
        route.add(place);
        return true;
    }

    /** Move route waypoint {@code index}. */
    public static void move(ClientLevel level, int index, Place place) {
        if (here(level) && index >= 0 && index < route.size()) route.set(index, place);
    }

    /** Remove route waypoint {@code index}. */
    public static void remove(ClientLevel level, int index) {
        if (here(level) && index >= 0 && index < route.size()) route.remove(index);
    }

    /** Remove all waypoints: subsequent route is built by launch logic. */
    public static void clearRoute() {
        route.clear();
    }

    /** Launch site in this dimension; empty means launch near the shooter. */
    public static Optional<Place> launch(@Nullable ClientLevel level) {
        return here(level) ? Optional.ofNullable(launch) : Optional.empty();
    }

    /** Launch site for the order (x and z). */
    public static Optional<Vec3> launchPoint(@Nullable ClientLevel level) {
        return launch(level).map(p -> new Vec3(p.x(), 0, p.z()));
    }

    /** Launch from here (select or move the launch site). */
    public static void setLaunch(ClientLevel level, Place place) {
        bind(level);
        launch = place;
    }

    /** Remove launch site: subsequent launches near the shooter. */
    public static void clearLaunch() {
        launch = null;
    }

    public static void reset() {
        dimension = null;
        point = null;
        route.clear();
        launch = null;
    }

    private static boolean here(@Nullable ClientLevel level) {
        return level != null && level.dimension().equals(dimension);
    }

    /** Site, waypoints and launch site in another dimension are foreign coordinates: start a fresh selection here. */
    private static void bind(ClientLevel level) {
        if (here(level)) return;
        reset();
        dimension = level.dimension();
    }
}
