package ua.zentix.incoming.registry;

import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.defense.DefenseWorld;
import ua.zentix.incoming.grid.BlackoutWorld;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.nuclear.radiation.RadiationDose;
import ua.zentix.incoming.nuclear.radiation.RetinaScars;
import ua.zentix.incoming.nuclear.world.FarLods;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.strike.ArrivalTickets;
import ua.zentix.incoming.strike.CameraLink;
import ua.zentix.incoming.strike.PickHints;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.target.Sightings;
import ua.zentix.incoming.work.WorkScheduler;
import ua.zentix.incoming.warhead.CraterFalls;

import java.util.Optional;
import java.util.function.Supplier;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTER = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Incoming.MOD_ID);

    /** Player dose and contamination; reset on death (not copied). */
    public static final Supplier<AttachmentType<RadiationDose>> RADIATION = REGISTER.register("radiation",
            () -> AttachmentType.builder(() -> RadiationDose.NONE).serialize(RadiationDose.CODEC).build());

    /**
     * Living entity: nuclear detonations whose flash, prompt radiation and wave it has received, and the time its fallout
     * dose is accounted to ({@link ua.zentix.incoming.nuclear.world.NukeSeen}); saved with the entity.
     */
    public static final Supplier<AttachmentType<ua.zentix.incoming.nuclear.world.NukeSeen>> NUKE_SEEN = REGISTER.register("nuke_seen",
            () -> AttachmentType.builder(() -> ua.zentix.incoming.nuclear.world.NukeSeen.NONE)
                    .serialize(ua.zentix.incoming.nuclear.world.NukeSeen.CODEC, s -> !s.equals(ua.zentix.incoming.nuclear.world.NukeSeen.NONE)).build());

    /** Player retinal burns — flash blind spots; reset on death (not copied). */
    public static final Supplier<AttachmentType<RetinaScars>> RETINA = REGISTER.register("retina",
            () -> AttachmentType.builder(() -> RetinaScars.NONE).serialize(RetinaScars.CODEC, s -> !s.isEmpty()).build());

    /** Chunk: number of the last nuclear detonation whose damage has been applied to it. */
    public static final Supplier<AttachmentType<Integer>> CHUNK_SCAR = REGISTER.register("chunk_scar",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    /** Cohesive surface damage smaller than a full block; vanilla crack packets display it. */
    public static final Supplier<AttachmentType<java.util.List<ua.zentix.incoming.warhead.SurfaceDamage.Mark>>> SURFACE_DAMAGE =
            REGISTER.register("surface_damage", () -> AttachmentType.builder(() -> java.util.List.<ua.zentix.incoming.warhead.SurfaceDamage.Mark>of())
                    .serialize(ua.zentix.incoming.warhead.SurfaceDamage.CODEC, m -> !m.isEmpty()).build());

    /** World: bounded, transient surface displays; the chunk attachment above is the only saved damage. */
    public static final Supplier<AttachmentType<ua.zentix.incoming.warhead.SurfaceDamage.Displays>> SURFACE_DISPLAYS =
            REGISTER.register("surface_displays", () -> AttachmentType.builder(ua.zentix.incoming.warhead.SurfaceDamage.Displays::new).build());

    /**
     * Chunk: detonation number whose flash layer ignited fire here before ruins were applied ({@code FlashScorch}): layer fire
     * does not tick; ruins built without the layer plan (after restart) extinguish or manage it. Saved with the chunk.
     */
    public static final Supplier<AttachmentType<Integer>> FLASH_LAYER = REGISTER.register("flash_layer",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    /** Chunk: flash traces on faces — soot and silhouettes ({@link ua.zentix.incoming.nuclear.flash.FlashMarks}); saved with the chunk. */
    public static final Supplier<AttachmentType<ua.zentix.incoming.nuclear.flash.FlashMarks>> FLASH_MARKS = REGISTER.register("flash_marks",
            () -> AttachmentType.builder(() -> ua.zentix.incoming.nuclear.flash.FlashMarks.EMPTY)
                    .serialize(ua.zentix.incoming.nuclear.flash.FlashMarks.CODEC, m -> !m.isEmpty()).build());

    /** Chunks whose changed blocks removed flash traces: sent to players at world-tick end ({@code FlashSync}); not saved. */
    public static final Supplier<AttachmentType<ua.zentix.incoming.nuclear.flash.FlashSync.Pending>> FLASH_PENDING = REGISTER.register("flash_pending",
            () -> AttachmentType.builder(ua.zentix.incoming.nuclear.flash.FlashSync.Pending::new).build());

    /**
     * Chunk: contains lamps extinguished by blackout (mark used on chunk load to restore light when the
     * outage ends); no mark means no extinguished lamps.
     */
    public static final Supplier<AttachmentType<Boolean>> GRID_DARK = REGISTER.register("grid_dark",
            () -> AttachmentType.builder(() -> false).build());

    /** World: blackout cascades and chunk queue ({@link BlackoutWorld}); not saved. */
    public static final Supplier<AttachmentType<BlackoutWorld>> BLACKOUT_WORLD = REGISTER.register("blackout_world",
            () -> AttachmentType.builder(BlackoutWorld::new).build());

    /** Overworld: blackout budget clock — one per server tick ({@link Blackouts#clock}); not saved. */
    public static final Supplier<AttachmentType<WorkClock>> GRID_CLOCK = REGISTER.register("grid_clock",
            () -> AttachmentType.builder(Blackouts::newClock).build());

    /** Overworld: common-budget impact-lane clock ({@link WorkScheduler#impactClock}); not saved. */
    public static final Supplier<AttachmentType<WorkClock>> IMPACT_CLOCK = REGISTER.register("impact_clock",
            () -> AttachmentType.builder(WorkScheduler::newImpactClock).build());

    /** Overworld: measured tick reserve and the two IMPACT windows; discarded on world exit. */
    public static final Supplier<AttachmentType<ua.zentix.incoming.work.ImpactTiming>> IMPACT_TIMING = REGISTER.register("impact_timing",
            () -> AttachmentType.builder(() -> new ua.zentix.incoming.work.ImpactTiming()).build());

    /** World: explosion timelines ({@link StrikeWorld}); not saved. */
    public static final Supplier<AttachmentType<StrikeWorld>> STRIKE_WORLD = REGISTER.register("strike_world",
            () -> AttachmentType.builder(StrikeWorld::new).build());

    /** Outside-world chunks whose ruins and lamps go from disk to Distant Horizons LODs (unsaved, per world). */
    public static final Supplier<AttachmentType<FarLods>> FAR_LODS = REGISTER.register("far_lods",
            () -> AttachmentType.builder(FarLods::new).build());

    /** Mod world changes sent to Distant Horizons LODs (unsaved, per world). */
    public static final Supplier<AttachmentType<DhUpdates>> DH_UPDATES = REGISTER.register("dh_updates",
            () -> AttachmentType.builder(DhUpdates::new).build());

    /** Main world: mod world changes sent to players' Voxy LODs, their queues (unsaved). */
    public static final Supplier<AttachmentType<ua.zentix.incoming.compat.VoxyLods>> VOXY_LODS = REGISTER.register("voxy_lods",
            () -> AttachmentType.builder(ua.zentix.incoming.compat.VoxyLods::new).build());

    /** World: terrain survey without chunk loading — disk heightmaps and background generator terrain ({@link ua.zentix.incoming.util.TerrainSurvey}); not saved. */
    public static final Supplier<AttachmentType<ua.zentix.incoming.util.TerrainSurvey>> TERRAIN_SURVEY = REGISTER.register("terrain_survey",
            () -> AttachmentType.builder(ua.zentix.incoming.util.TerrainSurvey::new).build());

    /** World: ordinary explosion crater falls ({@link CraterFalls}); not saved. */
    public static final Supplier<AttachmentType<CraterFalls>> CRATER_FALLS = REGISTER.register("crater_falls",
            () -> AttachmentType.builder(CraterFalls::new).build());

    /**
     * World: strike fuel burning on blocks ({@link ua.zentix.incoming.warhead.FuelFires}); saved — cell fires remain
     * in chunks, and fuel must finish burning on schedule after restart too.
     */
    public static final Supplier<AttachmentType<ua.zentix.incoming.warhead.FuelFires>> FUEL_FIRES = REGISTER.register("fuel_fires",
            () -> AttachmentType.builder(ua.zentix.incoming.warhead.FuelFires::new)
                    .serialize(ua.zentix.incoming.warhead.FuelFires.CODEC, f -> !f.isEmpty()).build());

    /** World: nuclear queues — front, light, craters, chunks, fallout on mobs ({@link NuclearWorld}); not saved. */
    public static final Supplier<AttachmentType<NuclearWorld>> NUCLEAR_WORLD = REGISTER.register("nuclear_world",
            () -> AttachmentType.builder(NuclearWorld::new).build());

    /** Overworld: nuclear work budget clock — one per server tick ({@link NuclearWorld#clock}); not saved. */
    public static final Supplier<AttachmentType<WorkClock>> NUCLEAR_CLOCK = REGISTER.register("nuclear_clock",
            () -> AttachmentType.builder(WorkClock::new).build());

    /** Player: game time of last remote launch or cancel (debounce); not saved. */
    public static final Supplier<AttachmentType<Long>> LAST_FIRE = REGISTER.register("last_fire",
            () -> AttachmentType.builder(() -> 0L).build());

    /** Player: game time of last retarget from the projectile camera; not saved. */
    public static final Supplier<AttachmentType<Long>> LAST_RETARGET = REGISTER.register("last_retarget",
            () -> AttachmentType.builder(() -> 0L).build());

    /** Player: which owned projectile hosts their camera and where it looks ({@link CameraLink}); not saved. */
    public static final Supplier<AttachmentType<Optional<CameraLink>>> CAMERA_LINK = REGISTER.register("camera_link",
            () -> AttachmentType.builder(Optional::<CameraLink>empty).build());

    /** Player: game time of last player-list request for the targeting map; not saved. */
    public static final Supplier<AttachmentType<Long>> LAST_MAP_PLAYERS = REGISTER.register("last_map_players",
            () -> AttachmentType.builder(() -> 0L).build());

    /** Player: shown a nonempty list of flying projectiles — clear HUD once all arrive; not saved. */
    public static final Supplier<AttachmentType<Boolean>> FLIGHTS_SHOWN = REGISTER.register("flights_shown",
            () -> AttachmentType.builder(() -> false).build());

    /** Player: area loaded by a targeting-map click ({@link PickHints}); not saved. */
    public static final Supplier<AttachmentType<PickHints.Slot>> PICK_HINT = REGISTER.register("pick_hint",
            () -> AttachmentType.builder(PickHints.Slot::new).build());

    /**
     * Player: ticket loading the entry/respawn location before mod areas ({@link ArrivalTickets}); not
     * saved, transfers to the new player object in {@link ArrivalTickets#onClone}.
     */
    public static final Supplier<AttachmentType<ArrivalTickets.Slot>> ARRIVAL = REGISTER.register("arrival",
            () -> AttachmentType.builder(ArrivalTickets.Slot::new).build());

    /** World: whom and what each side saw ({@link Sightings}); not saved. */
    public static final Supplier<AttachmentType<Sightings>> SIGHTINGS = REGISTER.register("sightings",
            () -> AttachmentType.builder(Sightings::new).build());

    /** World: flying interceptors, their targets and announced alerts ({@link DefenseWorld}); not saved. */
    public static final Supplier<AttachmentType<DefenseWorld>> DEFENSE_WORLD = REGISTER.register("defense_world",
            () -> AttachmentType.builder(DefenseWorld::new).build());

    private ModAttachments() {}
}
