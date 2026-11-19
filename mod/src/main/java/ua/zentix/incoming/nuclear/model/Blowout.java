package ua.zentix.incoming.nuclear.model;

/**
 * Flash-started fires the blast wave leaves burning (Martin, Backovsky, Goodale, SRI International 1980, “Blast/fire
 * interaction”; the same in DNA EM-1 1978, ch. 15: no blowout expected below 5 psi in rooms, and Operation BUSTER Proj. 2.2,
 * USFS 1952: flames of flat grass beds wiped off by the blast wind): all survive below 2 psi, a share (8 − P)/6 survives
 * between 2 and 5 psi, half above 5 psi. One law for the server's fire blocks and the client's fire model.
 */
public final class Blowout {
    /** Peak overpressure below which the wave blows out no fire, psi. */
    public static final double FROM_PSI = 2;
    /** Above this overpressure half the fires survive, psi. */
    public static final double HALF_PSI = 5;

    private Blowout() {}

    /** Share of flash fires surviving the wave at peak overpressure {@code psi}. */
    public static double keep(double psi) {
        if (!(psi > FROM_PSI)) return 1;
        return psi >= HALF_PSI ? 0.5 : (8 - psi) / 6;
    }
}
