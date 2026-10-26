package ua.zentix.incoming.client.fx;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BunkerCover;
import ua.zentix.incoming.warhead.BuriedBlast;

/**
 * What exploded — what visuals and sound need from a blast, from the {@link S2C.Blast} packet and weapon spec
 * ({@link WeaponSpec}): impact charge and fuel come only from here, rather than the packet kind (by kind, Lancet used to be Shahed).
 * No Minecraft dependencies.
 *
 * @param weapon      weapon; null — the blast is not from a weapon in the spec (anti-aircraft missile burst)
 * @param chargeKg    TNT-equivalent charge, kg ({@link WeaponSpec#charge}); for an underground bomb — the share of its
 *                    charge that entered the air ({@link BuriedBlast#airCharge})
 * @param fuelKg      fuel consumed in the fireball, kg ({@link WeaponSpec#fuelAtImpact})
 * @param surface     blast on the ground (wave reflected by it — pressure as for a charge 1.8 times larger), or in the air
 * @param underground bomb exploded underground ({@link S2C.Blast#BUNKER})
 */
public record BlastSource(@Nullable WeaponType weapon, double chargeKg, double fuelKg, boolean surface, boolean underground) {
    /**
     * Explosive — CHNO molecular formula. Free carbon in detonation products follows the Kistiakowsky–Wilson rules
     * (Akhavan, “The Chemistry of Explosives”, ch. 4): below −40 % oxygen balance (modified, Springall
     * Roberts), oxygen first forms water, then CO; above — first CO, then water, then CO₂. Carbon not turned into CO is
     * soot: TNT is oxygen-poor (balance −74 %) — products contain 18.5 % carbon by mass, black smoke; RDX (−21.6 %) —
     * no carbon, grey smoke from soil, water and nearby burning matter.
     */
    public enum Explosive {
        /** TNT C₇H₅N₃O₆. */
        TNT(7, 5, 3, 6),
        /** RDX C₃H₆N₆O₆ (basis of Tomahawk PBXN-107: 86 % RDX, the rest is binder, whose carbon is not counted). */
        RDX(3, 6, 6, 6);

        final int c, h, n, o;

        Explosive(int c, int h, int n, int o) {
            this.c = c;
            this.h = h;
            this.n = n;
            this.o = o;
        }

        /** Molar mass, g/mol. */
        double molarMass() {
            return 12.011 * c + 1.008 * h + 14.007 * n + 15.999 * o;
        }

        /** Oxygen balance to CO₂ and H₂O, mass fraction (TNT −0.74, RDX −0.216). */
        public double oxygenBalance() {
            return -1600 * (2 * c + h / 2.0 - o) / molarMass() / 100;
        }

        /** Free carbon in detonation products per kg of explosive, kg (Kistiakowsky–Wilson rules). */
        public double freeCarbon() {
            double oxygen = o, carbon;
            if (oxygenBalance() < -0.40) {
                oxygen -= Math.min(oxygen, h / 2.0);
                carbon = c - Math.min(c, oxygen);
            } else {
                carbon = c - Math.min(c, oxygen);
            }
            return carbon * 12.011 / molarMass();
        }
    }


    /** Tank fuel from the spec ({@link WeaponSpec#fuel}): Shahed — gasoline, missile — JP-10; others have none at impact. */
    public FuelPool.Fuel fuel() {
        return fuelKg <= 0 || weapon == null ? FuelPool.Fuel.NONE : weapon.spec().fuel();
    }

    /**
     * Explosive composition: Tomahawk — RDX-based PBXN-107 (ref/passport.md); for Shahed, Lancet, Grad and anti-aircraft
     * missile bursts the composition is unpublished — TNT, as in warheads of their families (estimate; smoke in strike footage is
     * black).
     */
    public Explosive explosive() {
        return weapon == WeaponType.MISSILE ? Explosive.RDX : Explosive.TNT;
    }

    /** Blast from a packet: weapon from the packet (by kind in old replays), numbers from its spec. */
    public static BlastSource of(S2C.Blast p) {
        return p.kind() == S2C.Blast.BUNKER ? buried(p.pos().y, p.surfaceY()) : of(p.weaponType(), false);
    }

    /** Blast of weapon {@code w} on the ground ({@code underground} — buried bomb). */
    public static BlastSource of(WeaponType w, boolean underground) {
        WeaponSpec s = w.spec();
        return new BlastSource(w, s.charge(), s.fuelAtImpact(), true, underground);
    }

    /**
     * Bomb exploded at {@code chargeY} below surface {@code surfaceY} ({@link BunkerCover}): above ground — the share of its
     * charge that entered the air ({@link BuriedBlast}); no breakout — underground blast.
     */
    public static BlastSource buried(double chargeY, float surfaceY) {
        BuriedBlast law = law(chargeY, surfaceY);
        return new BlastSource(WeaponType.BUNKER, law.airCharge(hole()), 0, true, !law.breaches());
    }

    /** Bomb detonation law at {@code chargeY} below surface {@code surfaceY}. */
    public static BuriedBlast law(double chargeY, float surfaceY) {
        return new BuriedBlast(WeaponType.BUNKER.spec().warheadTnt(), BunkerCover.depth((int) surfaceY, chargeY));
    }

    /** Bomb bore cross-section, m² (body from the spec). */
    public static double hole() {
        double d = WeaponType.BUNKER.spec().striker().article().diameter();
        return Math.PI * d * d / 4;
    }

    /** Airburst of {@code chargeKg} kg TNT with no fuel. */
    public static BlastSource air(double chargeKg) {
        return new BlastSource(null, chargeKg, 0, false, false);
    }
}
