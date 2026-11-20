package ua.zentix.incoming.nuclear.world;

/**
 * Glazing along a pulse ray ({@code RuinPlanner.expose}): each window the ray crosses passes {@link #GLASS} of the pulse once,
 * however many glazing cells of it the voxel walk visits. A window ends only where the ray runs through a cell of positive
 * length that is not glazing: a cell the walk visits at a tie (the ray only touches its edge or corner) does not split one pane.
 */
final class GlassPath {
    /** Window glass passes this share of the pulse (est.: soda-lime glass transmits 0.85–0.9 from 0.35 to 2.7 µm, where a ~6000 K fireball emits nearly all its light). */
    static final double GLASS = 0.8;
    /** Ray length below which a crossed cell only touches the ray, in cell sizes. */
    static final double TOUCH = 1e-9;

    /** Share of the pulse passed so far. */
    double pass = 1;
    private boolean pane, lastGlazing;
    private double lastIn;

    /** The walk enters the next cell at ray parameter {@code t} (cell sizes from the fuel's centre). */
    void enter(double t, boolean glazing) {
        if (!lastGlazing && t - lastIn > TOUCH) pane = false;
        if (glazing && !pane) pass *= GLASS;
        if (glazing) pane = true;
        lastGlazing = glazing;
        lastIn = t;
    }
}
