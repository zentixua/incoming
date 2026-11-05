package ua.zentix.incoming.nuclear.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Model-only experiment, excluded from the normal jar; not connected to a renderer or detonation consumer.
 * A finite, initially cap-borne passive inventory is detrained as the existing cap grows. It is not a new
 * terrestrial source, an opacity prescription, or a calibrated nuclear-cloud model.
 *
 * <p>Lecoanet & Jeevanjee (2019), JAS 76, section 4 / equation 12, define net entrainment as d ln(V)/dz =
 * epsilon - delta and find detrainment roughly an order of magnitude weaker for dry, neutral thermals:
 * https://nadirjeevanjee.com/papers/17thermals.pdf . Assuming delta = beta * epsilon_net and uniform tracer
 * mixing gives dM/M = -beta d ln(V). Here beta is an explicit estimate transferred from that idealized system;
 * it is not a nuclear measurement. A beta of 0.1 is a comparison case, not a production default.
 *
 * <p>Release starts after the existing roll-up clock, when a developed thermal is a less poor analogy to the
 * hot initial fireball, and ends at the existing stabilization clock. Starting then is an estimate; formation
 * during roll-up and stable/moist detrainment are not resolved. Released material inherits the historical
 * cap-bottom position. Its later vertical displacement is a chosen fraction of cap-bottom displacement
 * (est., separate from ground-dust flow). Zero is the neutral passive-wake limit, not a measured velocity.
 * The same prescribed height-dependent wind as the mushroom is integrated along each released parcel's path.
 *
 * <p>The passive inventory is normalized to one. A second, explicitly estimated droplet budget inherits the
 * existing cap's fixed liquid-water-content closure: Qref = DROP_SIGMA * V. Only its growth before stabilization
 * supplies fresh droplets. Solving dC/dq = 1 - beta*C/q, q = V/Vstart, gives C = (q + beta*q^-beta)/(1+beta).
 * Cap plus wake then equal Qref, rather than assigning the full reference density to both. This transfers the
 * existing uncalibrated moisture assumption; it does not predict condensation, evaporation or observed opacity.
 */
public final class CapWakePrototype {
    public record Closure(double detrainmentToNetEntrainment, double retainedRise) {
        public Closure {
            if (!Double.isFinite(detrainmentToNetEntrainment) || detrainmentToNetEntrainment < 0
                    || !Double.isFinite(retainedRise) || retainedRise < 0 || retainedRise >= 1)
                throw new IllegalArgumentException("Nonnegative detrainment and a rise fraction in [0, 1) required");
        }
    }

    /** A release interval, observed at one later time. Lengths are model meters; mass is a fraction of initial inventory. */
    public record Slab(double from, double to, double mass, double droplets, double bottom, double top, double radius,
                       double x, double z) {
        public double linearDensity() { return mass / (top - bottom); }

        /** Center-crossing depth divided by total initial extinction area; this is not an absolute visible opacity. */
        public double crossDepthPerInventory() { return 2 * linearDensity() / (Math.PI * radius); }
    }

    public record Snapshot(double seconds, double capInventory, double capVolume, double capRadius,
                           double capDroplets, double dropletBudget, double initialVolume, List<Slab> slabs) {
        public Snapshot { slabs = List.copyOf(slabs); }
        public double wakeInventory() { return slabs.stream().mapToDouble(Slab::mass).sum(); }
        public double wakeDroplets() { return slabs.stream().mapToDouble(Slab::droplets).sum(); }
        public double bottom() { return slabs.isEmpty() ? Double.NaN : slabs.getFirst().bottom(); }
        public double top() { return slabs.isEmpty() ? Double.NaN : slabs.getLast().top(); }
        public double meanHeight() {
            double mass = wakeInventory();
            return mass == 0 ? Double.NaN : slabs.stream().mapToDouble(s -> s.mass * (s.bottom + s.top) / 2).sum() / mass;
        }
        public double peakDepthRelativeToCap() {
            double capDepth = capInventory * 2 * capRadius / capVolume;
            return capDepth > 0 ? slabs.stream().mapToDouble(Slab::crossDepthPerInventory).max().orElse(0) / capDepth : 0;
        }
        /** Uniform-cylinder diagnostic using the inherited cap droplet law; not a calibrated shader opacity. */
        public double peakDropletDepth() {
            return MushroomModel.DROP_SIGMA * initialVolume * slabs.stream()
                    .mapToDouble(s -> 2 * s.droplets / (Math.PI * s.radius * (s.top - s.bottom))).max().orElse(0);
        }
    }

    private record Cap(double seconds, double bottom, double volume, double radius, double wakeRadius) {}

    private final double yieldKt, hob;
    private final boolean surface;
    private final Closure closure;
    private final Cap[] edges;
    private final double start, stop, step, endTop, bottomInfinity, tau;

    public CapWakePrototype(double yieldKt, double hobMetres, boolean surface, Closure closure, int bins) {
        if (!Double.isFinite(yieldKt) || yieldKt <= 0 || !Double.isFinite(hobMetres) || hobMetres < 0
                || bins < 1 || bins > 1024 || closure == null)
            throw new IllegalArgumentException("Positive yield, nonnegative height and 1..1024 history bins required");
        this.yieldKt = yieldKt;
        this.hob = hobMetres;
        this.surface = surface;
        this.closure = closure;
        start = MushroomModel.rollUpSeconds(yieldKt, surface);
        stop = CloudModel.stabilizationSeconds(yieldKt);
        step = (stop - start) / bins;
        if (step <= 0) throw new IllegalArgumentException("Roll-up must precede stabilization");
        endTop = CloudModel.end(hob, yieldKt, surface);
        bottomInfinity = CloudModel.capBottom(Double.POSITIVE_INFINITY, hob, yieldKt, surface);
        tau = CloudModel.riseTau(yieldKt);
        edges = new Cap[bins + 1];
        for (int i = 0; i <= bins; i++) {
            edges[i] = cap(start + i * step);
            if (i > 0 && edges[i].volume < edges[i - 1].volume)
                throw new IllegalArgumentException("This closure requires monotone cap growth before stabilization");
        }
    }

    public double startsAt() { return start; }
    public double stopsAt() { return stop; }

    private Cap cap(double t) {
        // The null dust argument avoids a terrestrial table: no ground-wave clock or land share enters this source.
        MushroomModel.Shape shape = MushroomModel.shape(t, yieldKt, hob, surface, 0, null);
        return new Cap(t, shape.capBottom(), MushroomModel.capVolume(shape), shape.capR(), shape.stemR());
    }

    private double retained(Cap at) {
        return Math.exp(-closure.detrainmentToNetEntrainment * Math.log(at.volume / edges[0].volume));
    }

    private double capDroplets(Cap at) {
        double beta = closure.detrainmentToNetEntrainment;
        return (at.volume / edges[0].volume + beta * retained(at)) / (1 + beta);
    }

    private double wakeDroplets(Cap at) {
        return at.volume / edges[0].volume - capDroplets(at);
    }

    /** Seconds since the physical event; wind components are model m/s. Pure queries also support replay/seek. */
    public Snapshot at(double seconds, double windX, double windZ) {
        if (!Double.isFinite(seconds) || !Double.isFinite(windX) || !Double.isFinite(windZ))
            throw new IllegalArgumentException("Finite time and wind required");
        if (seconds < start) return new Snapshot(seconds, seconds < 0 ? 0 : 1,
                edges[0].volume, edges[0].radius, 0, 0, edges[0].volume, List.of());
        double until = Math.min(seconds, stop);
        Cap current = cap(until);
        var slabs = new ArrayList<Slab>(edges.length - 1);
        for (int i = 0; i < edges.length - 1 && edges[i].seconds < until; i++) {
            Cap a = edges[i], b = Math.min(edges[i + 1].seconds, stop) <= until ? edges[i + 1] : current;
            double mass = retained(a) - retained(b);
            if (mass <= 0) continue;
            Cap mid = cap((a.seconds + b.seconds) / 2);
            double bottom = height(seconds, a), top = height(seconds, b);
            double driftSeconds = windIntegral(seconds, mid);
            slabs.add(new Slab(a.seconds, b.seconds, mass, wakeDroplets(b) - wakeDroplets(a), bottom, top, mid.wakeRadius,
                    windX * driftSeconds, windZ * driftSeconds));
        }
        return new Snapshot(seconds, retained(current), current.volume, current.radius, capDroplets(current),
                current.volume / edges[0].volume, edges[0].volume, slabs);
    }

    private double height(double now, Cap birth) {
        return birth.bottom + closure.retainedRise * (CloudModel.capBottom(now, hob, yieldKt, surface) - birth.bottom);
    }

    /** Wind at birth follows the existing cloud axis; thereafter integrate u(z) = wind * z / endTop causally. */
    private double windIntegral(double now, Cap birth) {
        double dt = now - birth.seconds;
        double integratedCapBottom = bottomInfinity * dt + (birth.bottom - bottomInfinity)
                * tau * -Math.expm1(-dt / tau);
        double integratedHeight = (1 - closure.retainedRise) * birth.bottom * dt
                + closure.retainedRise * integratedCapBottom;
        return (birth.seconds * birth.bottom + integratedHeight) / endTop;
    }
}
