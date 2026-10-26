package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

/**
 * What the charge hit: explosion family and target matter. Chosen from client knowledge of the site: material under
 * point ({@code S2C.Blast.material}), impact-face support from packet ({@code S2C.Blast.Support}: server breach finds
 * a slab above a void, {@link BreachModel}: path through slab / material breach radius ≤ 1, with a void beneath the
 * slab beyond it), and height above surface ({@code FarBlasts.overSurface}), rather than roof, road or ground labels
 * (Artem's decision, 23.10). No Minecraft: {@code FarBlasts.target} takes support from packet.
 * <ul>
 * <li>{@link Family#SOIL}: supported loose matter (soil, sand, gravel, snow, clay); crater throws a soil curtain
 * ({@link Ejecta}).</li>
 * <li>{@link Family#HARD}: supported cohesive matter (rock, concrete, masonry, boards on ground); no soil cone, only
 * chips and crushing dust in the material's colour.</li>
 * <li>{@link Family#SLAB}: slab above a void that the charge breaches (roof, decking, floor); slab fragments upward
 * and downward, breach dust; no soil.</li>
 * <li>{@link Family#WATER}: water ({@link WaterBlast}).</li>
 * <li>{@link Family#AIR}: burst above cratering height ({@link CloudModel#digs}); detonation-product ball without soil.</li>
 * </ul>
 * Matter values ({@link Matter}) are estimates, each with its reason: no tests for every target with 6.4–150 kg charges
 * (front F physics.md, 25.10). Law is invalid indoors (products venting through openings); no indoor model here.
 *
 * @param family   family
 * @param material material under point (blast packet)
 * @param slab     breached slab thickness along face normal, m (0 if not a slab)
 * @param hole     slab hole radius, m: √(R² − t²) for breach radius R (BreachGameTests: hole √(R² − t²)); 0 if not a slab
 * @param face     charge location on slab face (null if not a slab or location unknown)
 * @param normal   breached face normal into slab, hole axis (downward for non-slab)
 * @param atFace   charge at server-found face (packet support): destroyed matter at explosion point, rather than
 *                 underlying terrain height, which client knows coarsely at distance (front F review 3)
 */
public record BlastTarget(Family family, GroundMaterial material, double slab, double hole, @Nullable Vec3 face, Vec3 normal,
                          boolean atFace) {
    /** World down: target axis without a slab. */
    static final Vec3 DOWN = new Vec3(0, -1, 0);

    /** Explosion family by target. */
    public enum Family { SOIL, HARD, SLAB, WATER, AIR }

    /**
     * Target matter for visuals: bulk density (destroyed volume mass), ejecta grain size seen by light, and dry fine
     * dust colour. ⚠ All estimated from material composition, not measured ejecta of these charges:
     * <ul>
     * <li>density: reference bulk/apparent values: soil and sand 1600 (as {@link CloudModel#SOIL_DENSITY}), gravel 1700,
     * snow 400 (compacted 300–500), concrete and stone 2500 (concrete 2400, granite 2650, one value per class),
     * deepslate 2800, masonry 1900 (brick 1800–2000), wood 500, terracotta 1900;</li>
     * <li>grain size: diameter whose area makes ballistic ejecta visible to light (extinction 3Q/(2ρd), Q = 2, as
     * {@code DustModel}): sand medium 0.25–0.5 mm (USDA, physics.md [S21]) → 0.35 mm; soil 1 mm (sand and small
     * aggregates; 2–30 cm clods hardly attenuate light, with their own {@code clods} share); snow grain ~1 mm; clay
     * lumps ~5 mm; gravel 10 mm (USDA gravel 2–75 mm);</li>
     * <li>clod share ({@code clods}): pieces seen as nearby particles rather than curtain: soil and clay ½
     * (cohesion holds clods, physics.md), gravel ⅘ (already pieces), sand and snow 0 (granular, "no basis for making
     * all ejecta into cubes");</li>
     * <li>dust colour: dry fines (on screen, thick layer): soil as nuclear dust wall, sand and snow their own colours,
     * concrete and stone light grey (cement and limestone light), slate dark grey, masonry a mortar/brick mixture,
     * wood ochre sawdust, terracotta lighter than block (fine dust lighter than solid material: finer grains scatter more).</li>
     * </ul>
     */
    public enum Matter {
        DIRT(1600, 1e-3, 2650, 0.5, FINES_LOOSE, true, 0.60f, 0.53f, 0.45f),
        SAND(1600, 3.5e-4, 2650, 0, FINES_LOOSE, true, 0.85f, 0.78f, 0.58f),
        SNOW(400, 1e-3, 917, 0, FINES_LOOSE, true, 0.95f, 0.95f, 0.97f),
        GRAVEL(1700, 1e-2, 2650, 0.8, FINES_LOOSE, true, 0.58f, 0.55f, 0.50f),
        CLAY(1900, 5e-3, 2600, 0.5, FINES_LOOSE, true, 0.70f, 0.52f, 0.42f),
        STONE(2500, 0, 2650, 1, FINES_CRUSHED, false, 0.72f, 0.71f, 0.68f),
        DEEPSLATE(2800, 0, 2800, 1, FINES_CRUSHED, false, 0.42f, 0.42f, 0.44f),
        BRICK(1900, 0, 2600, 1, FINES_CRUSHED, false, 0.66f, 0.60f, 0.55f),
        WOOD(500, 0, 500, 1, FINES_LOOSE, false, 0.70f, 0.58f, 0.40f),
        WATER(1000, 0, 1000, 0, 0, false, 0.80f, 0.85f, 0.90f);

        /** Bulk density, kg/m³. */
        public final double density;
        /** Ejecta diameter whose area is seen by light, m (0 means no curtain: cohesive matter). */
        public final double grain;
        /** Grain density, kg/m³. */
        public final double grainDensity;
        /** Ballistic ejecta share travelling as clods (nearby particles), rather than curtain grains. */
        public final double clods;
        /** Destroyed share for DustModel bin mixture (size distribution estimated) ({@link #FINES_LOOSE}, {@link #FINES_CRUSHED}). */
        public final double fines;
        /** Loose matter: crater throws it as a curtain. */
        public final boolean loose;
        /** Dry fine dust colour on screen. */
        public final float r, g, b;

        Matter(double density, double grain, double grainDensity, double clods, double fines, boolean loose, float r, float g, float b) {
            this.fines = fines;
            this.density = density;
            this.grain = grain;
            this.grainDensity = grainDensity;
            this.clods = clods;
            this.loose = loose;
            this.r = r;
            this.g = g;
            this.b = b;
        }

        /** Ballistic ejecta extinction per kg, m²/kg: 3Q/(2ρd), Q = 2 (geometrical optics, as {@code DustModel}). */
        public double extinction() {
            return grain > 0 ? 3 * 2 / (2 * grainDensity * grain) : 0;
        }

        /** Matter for the packet material. */
        public static Matter of(GroundMaterial m) {
            return switch (m) {
                case DIRT -> DIRT;
                case SAND -> SAND;
                case SNOW -> SNOW;
                case GRAVEL -> GRAVEL;
                case TERRACOTTA -> CLAY;
                case STONE -> STONE;
                case DEEPSLATE -> DEEPSLATE;
                case BRICK -> BRICK;
                case WOOD -> WOOD;
                case WATER -> WATER;
            };
        }
    }

    /**
     * Share of destroyed loose matter (and boards) becoming airborne dust below ~50 μm: previous estimate
     * {@link CloudModel#DUST_LOFTED}; soil fines mostly stay in clods and fall with them.
     */
    static final double FINES_LOOSE = CloudModel.DUST_LOFTED;
    /**
     * PM10 fines from contact explosion on concrete per specimen mass: Costa, Bodiot, Lecysyn et al., "Analyse des
     * particules produites par explosion au contact d'éprouvettes en béton", 38e Congrès Français sur les Aérosols
     * (2025), pp. 116–119, figs. 5–6, DOI 10.25576/ASFERA-CFA2025-43915: filter 0–2 g/kg (upper uncertainty ~2.4),
     * order 1 g/kg. ⚠ Estimate: experiment denominator is entire specimen, not destroyed mass; collected material
     * includes settled dust; assume all airborne.
     */
    static final double PM10_CONTACT = 0.001;
    /**
     * Same for cohesive matter (rock, concrete, masonry): full {@link ua.zentix.incoming.nuclear.model.DustModel} bin
     * mixture with PM10 {@link #PM10_CONTACT}, namely PM10 / mixture share ({@code DustModel.PM10_SHARE}). ⚠ Estimate:
     * experiments on concrete, stone and masonry by analogy; prior 0.1 unsupported by measurements (20 times more,
     * a wall at the eye in the street).
     */
    static final double FINES_CRUSHED = PM10_CONTACT / ua.zentix.incoming.nuclear.model.DustModel.PM10_SHARE;

    /** Impact on bare loose soil, as for an explosion without target information (tests, legacy calls). */
    public static final BlastTarget SOIL = new BlastTarget(Family.SOIL, GroundMaterial.DIRT, 0, 0, null, DOWN, false);

    /** Target matter. */
    public Matter matter() {
        return Matter.of(material);
    }

    /**
     * Family: water is water; above cratering height is airburst; a breached slab over a void ({@code slab} > 0) is
     * slab; otherwise by target cohesion: loose is soil, cohesive is hard.
     *
     * @param loose whether target is loose (earth breach group, FM 5-250 table B-3)
     * @param slab  breached slab thickness above void along face normal, m (0 means continuous support)
     * @param digs  charge craters at its height ({@link CloudModel#digs})
     */
    public static Family family(GroundMaterial material, boolean loose, double slab, boolean digs) {
        if (material.isWater()) return Family.WATER;
        if (!digs) return Family.AIR;
        if (slab > 0) return Family.SLAB;
        return loose ? Family.SOIL : Family.HARD;
    }

    /** Target by material, without world (no client chunk): continuous support, cohesion from material matter. */
    public static BlastTarget of(GroundMaterial material, double tntKg, double hobM) {
        return new BlastTarget(family(material, Matter.of(material).loose, 0, CloudModel.digs(tntKg, hobM)), material, 0, 0, null, DOWN, false);
    }

    /**
     * Target with world support: {@code loose} is target cohesion; {@code slab} thickness to void along axis (0 if
     * no void reached within breach range), {@code breach} material breach radius for this charge, m.
     */
    public static BlastTarget of(GroundMaterial material, boolean loose, double slab, double breach) {
        return of(material, loose, slab, breach, null, DOWN);
    }

    /**
     * Same with charge location {@code face} and inward slab face normal {@code normal} (packet support). Support
     * means server-found contact (charge at face, {@code SlabBreach.locate}): surrounding terrain height does not
     * decide here, since client knows it coarsely at distance (a {@code FarTerrain} tile without the house puts roof
     * 5 m above ground, making breach an airburst without mass or dust; front F review 2). Airburst only without support.
     */
    public static BlastTarget of(GroundMaterial material, boolean loose, double slab, double breach, @Nullable Vec3 face, Vec3 normal) {
        Family f = family(material, loose, slab, true);
        boolean plate = f == Family.SLAB;
        double hole = plate ? Math.sqrt(Math.max(0, breach * breach - slab * slab)) : 0;
        return new BlastTarget(f, material, plate ? slab : 0, hole, plate ? face : null, plate ? normal.normalize() : DOWN, true);
    }

    /** Hole centre on rear slab face (null if not a slab or charge location unknown). */
    @Nullable
    public Vec3 exit() {
        return face == null ? null : face.add(normal.scale(slab));
    }

    /** Rear spall spread about hole axis, degrees (⚠ estimate). */
    public static final double SPALL_CONE_DEG = 30;

    /**
     * {@code n} rear spall pieces: from rear-face hole ({@link #exit}: disc of radius {@link #hole} across hole axis,
     * piece 0.3 m beyond face) along breached face normal {@link #normal}, rather than world down or warhead heading:
     * product pressure pushes slab along normal (Gurney sandwich is one-dimensional; warhead momentum, Shahed
     * ~200 kg × 50 m/s versus plug ~3.5 t × 29 m/s in a metre of boards, is an order smaller, ⚠ neglected), and server
     * hole lies about normal. Speed is Gurney plug speed ({@link #plugSpeed}) ±30 % (⚠ presentation: spread), direction
     * in a ±{@value #SPALL_CONE_DEG}° cone, outward from axis on piece's side of hole. Particle handles gravity.
     * Non-slab emits nothing.
     */
    public void spall(double tntKg, int n, java.util.function.DoubleSupplier random, Ejecta.Pieces out) {
        Vec3 exit = exit();
        if (exit == null || n <= 0) return;
        double v = plugSpeed(tntKg), cone = Math.toRadians(SPALL_CONE_DEG);
        Vec3[] across = Ejecta.across(normal);
        for (int i = 0; i < n; i++) {
            double a = random.getAsDouble() * Math.PI * 2, tilt = cone * Math.sqrt(random.getAsDouble()), r = hole * Math.sqrt(random.getAsDouble());
            Vec3 side = across[0].scale(Math.cos(a)).add(across[1].scale(Math.sin(a)));
            double speed = v * (0.7 + 0.6 * random.getAsDouble());
            out.piece(exit.add(side.scale(r)).add(normal.scale(0.3)), normal.scale(Math.cos(tilt) * speed).add(side.scale(Math.sin(tilt) * speed)));
        }
    }

    /**
     * Destroyed target volume, m³: slab breach (disc radius {@link #hole}, thickness {@link #slab}); otherwise crater-law
     * bowl for material soil ({@link CraterModel#chargeVolume}: same law server digs with; ⚠ for cohesive matter server
     * removes metres and retains depth remainder, {@code SurfaceDamage}, same volume); water and air 0.
     */
    public double volume(double tntKg) {
        return switch (family) {
            case SLAB -> Math.PI * hole * hole * slab;
            case SOIL, HARD -> CraterModel.chargeVolume(tntKg, material.soil());
            case WATER, AIR -> 0;
        };
    }

    /**
     * TNT Gurney constant √(2E), m/s: 2.44 km/s (Cooper P. W., "Explosives Engineering", Wiley-VCH 1996, table 27.1;
     * Kennedy J. E., "Gurney energy of explosives", Sandia SC-RR-70-790, 1970).
     */
    static final double GURNEY_TNT = 2440;

    /**
     * Rear spall speed of breached slab, m/s: breach plug mass M ({@link #mass}) under charge C, open Gurney sandwich
     * (Kennedy 1970): v = √(2E)·[((1 + 2M/C)³ + 1)/(6(1 + M/C)) + M/C]^(−½). ⚠ Formula assumes an explosive layer covering
     * the whole slab; a charge smaller than breach pushes only part downward, and real spall is slower (sources do
     * not quantify). Shahed (50 kg) in a metre of boards ~29 m/s, in a metre of stone ~6 m/s. Non-slab 0.
     */
    public double plugSpeed(double tntKg) {
        double m = mass(tntKg);
        if (family != Family.SLAB || m <= 0 || tntKg <= 0) return 0;
        double k = m / tntKg, a = 1 + 2 * k;
        return GURNEY_TNT / Math.sqrt((a * a * a + 1) / (6 * (1 + k)) + k);
    }

    /**
     * Destroyed breached slab share in crater on charge side; rest is rear spall entering void behind slab.
     * Contact explosion tests on 0.3 m ordinary reinforced concrete slab breached through (Zhang W. et al., "The impacts
     * of contact explosions on ultra-high performance reinforced concrete slabs", Sci. Rep. 14:25700 (2024), table 3,
     * NRCS-3…5: 2.2–5 kg TNT): crater volume / crater plus spall volume (diameter² × depth, same shape) 0.17, 0.21, 0.46,
     * increasing with breach strength. ⚠ Estimate 0.3: experiments smaller than our charges and slabs, same shape assumed.
     */
    public static final double SLAB_FRONT = 0.3;

    /**
     * Destroyed dust becoming airborne on charge side, kg: {@link #mass} × matter fines share ({@link Matter#fines});
     * breached slab only from crater on impact face ({@link #SLAB_FRONT}): spall fines enter the void behind slab with
     * it (indoors under a roof; indoor air not modelled here).
     */
    public double dust(double tntKg) {
        return mass(tntKg) * matter().fines * (family == Family.SLAB ? SLAB_FRONT : 1);
    }

    /** Ballistic destroyed ejecta, kg: {@link #mass} minus airborne dust. */
    public double ballistic(double tntKg) {
        return mass(tntKg) * (1 - matter().fines);
    }

    /**
     * Ballistic ejecta pieces as nearby particles, kg: soil uses matter clod share ({@link Matter#clods}), remainder
     * is curtain grains ({@link Ejecta#grains}); hard and slab have no curtain, so all pieces. Pieces + curtain grains
     * + dust = {@link #mass}; breached slabs also include rear fines entering room behind ({@link #SLAB_FRONT}).
     */
    public double clods(double tntKg) {
        return family == Family.SOIL ? ballistic(tntKg) * matter().clods : ballistic(tntKg);
    }

    /** Same as in-place volume (bulk density), m³: bowl or breach volume travelling as pieces. */
    public double clodVolume(double tntKg) {
        return clods(tntKg) / matter().density;
    }

    /** Destroyed target matter mass, kg: {@link #volume} × bulk density. */
    public double mass(double tntKg) {
        return volume(tntKg) * matter().density;
    }
}
