package ua.zentix.incoming.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.cam.ProjectileCamera;
import ua.zentix.incoming.client.far.FarRenderer;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.client.fx.BlastEffects;
import ua.zentix.incoming.client.fx.CameraShake;
import ua.zentix.incoming.client.fx.Concussion;
import ua.zentix.incoming.client.fx.Effects;
import ua.zentix.incoming.client.fx.Exhaust;
import ua.zentix.incoming.client.fx.Flash;
import ua.zentix.incoming.client.fx.Fragments;
import ua.zentix.incoming.client.fx.GridEffects;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.FxLayer;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxPool;
import ua.zentix.incoming.client.hud.Alerts;
import ua.zentix.incoming.client.hud.ClientFlights;
import ua.zentix.incoming.client.hud.RadarScope;
import ua.zentix.incoming.client.hud.StrikesHud;
import ua.zentix.incoming.client.map.MapPlayers;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.client.nuclear.AnimalSilence;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.client.nuclear.Geiger;
import ua.zentix.incoming.client.nuclear.NukeArming;
import ua.zentix.incoming.client.nuclear.NukeFlash;
import ua.zentix.incoming.client.nuclear.NukeHud;
import ua.zentix.incoming.client.nuclear.NukeRenderer;
import ua.zentix.incoming.client.nuclear.NukeSky;
import ua.zentix.incoming.client.render.DebrisRenderer;
import ua.zentix.incoming.client.render.LodDepth;
import ua.zentix.incoming.client.render.FarModels;
import ua.zentix.incoming.client.render.FixedLauncherRenderer;
import ua.zentix.incoming.client.render.InterceptorRenderer;
import ua.zentix.incoming.client.render.LauncherRenderer;
import ua.zentix.incoming.client.render.SamRenderer;
import ua.zentix.incoming.client.render.ScreenProjection;
import ua.zentix.incoming.client.render.SpentBoosterRenderer;
import ua.zentix.incoming.client.render.StrikeProjectileRenderer;
import ua.zentix.incoming.client.render.WeaponModels;
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.client.screen.MapScreen;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.ClientSounds;
import ua.zentix.incoming.client.sound.OwnSources;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.SpentBoosterEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.ClientHooks;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModBlockEntities;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModEntities;

/** Client half of the mod: models, sound, effects, camera, HUD, remote. Never loaded on a dedicated server. */
@Mod(value = Incoming.MOD_ID, dist = Dist.CLIENT)
public final class IncomingClient {
    /** Dimmed color of unpowered lamps. */
    private static final int UNLIT_TINT = 0x7C7C84;

    public IncomingClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(IncomingClient::renderers);
        modBus.addListener(WeaponModels::register);
        modBus.addListener(WeaponModels::baked);
        modBus.addListener(IncomingClient::keys);
        modBus.addListener(IncomingClient::layers);
        modBus.addListener(SoundFilters::onEngineLoad);
        modBus.addListener(Fx::registerProviders);
        modBus.addListener(FxLayer::registerShaders);
        modBus.addListener(FarModels::registerShaders);
        modBus.addListener(NukeRenderer::registerShaders);
        modBus.addListener(IncomingClient::reloadListeners);
        modBus.addListener(IncomingClient::blockColors);
        TerrainTiles.init(modBus);
        LodDepth.init();
        // Voxy (via Roxy) and its classes are available at client setup; LOD Server Support overwrites chunks the mod
        // sent to Voxy: detect this and request them again
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) -> {
            VoxyIngest.init();
            if (VoxyIngest.available() && net.neoforged.fml.ModList.get().isLoaded("lss")) LssVoxyWatch.subscribe();
        });

        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, IncomingClient::preTick);
        NeoForge.EVENT_BUS.addListener(IncomingClient::tick);
        NeoForge.EVENT_BUS.addListener(IncomingClient::entityTick);
        NeoForge.EVENT_BUS.addListener(CameraShake::apply);
        NeoForge.EVENT_BUS.addListener(Designator::fov);
        NeoForge.EVENT_BUS.addListener(Designator::turn);
        NeoForge.EVENT_BUS.addListener(Designator::renderWorld);
        NeoForge.EVENT_BUS.addListener(Designator::hideHand);
        NeoForge.EVENT_BUS.addListener(IncomingClient::scroll);
        NeoForge.EVENT_BUS.addListener(IncomingClient::hideCrosshair);
        NeoForge.EVENT_BUS.addListener(IncomingClient::login);
        NeoForge.EVENT_BUS.addListener(IncomingClient::logout);
        NeoForge.EVENT_BUS.addListener(ClientNuclear::respawned);
        NeoForge.EVENT_BUS.addListener(ClientFlights::onJoin);
        NeoForge.EVENT_BUS.addListener(ClientFlights::onLeave);
        NeoForge.EVENT_BUS.addListener(ClientFlights::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(ScreenProjection::capture);
        NeoForge.EVENT_BUS.addListener(NukeRenderer::render);
        NeoForge.EVENT_BUS.addListener(ua.zentix.incoming.client.nuclear.FlashMarksMesh::onSection);
        NeoForge.EVENT_BUS.addListener(ua.zentix.incoming.client.nuclear.FlashMarksClient::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(ua.zentix.incoming.client.nuclear.FlashMarksClient::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(FxLayer::render);
        NeoForge.EVENT_BUS.addListener(FxLayer::debugText);
        NeoForge.EVENT_BUS.addListener(NukeRenderer::renderRain);
        NeoForge.EVENT_BUS.addListener(NukeSky::fogColor);
        NeoForge.EVENT_BUS.addListener(NukeSky::fog);
        NeoForge.EVENT_BUS.addListener(SoundFilters::onSound);
        NeoForge.EVENT_BUS.addListener(SoundFilters::onStream);
        NeoForge.EVENT_BUS.addListener(OwnSources::frame);
        NeoForge.EVENT_BUS.addListener(OwnSources::onSource);
        NeoForge.EVENT_BUS.addListener(AnimalSilence::onPlay);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::angles);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::fov);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::movement);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::hideHand);
        NeoForge.EVENT_BUS.addListener(ProjectileCamera::click);

        ClientHooks.set(new Hooks());
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        // model from the client spec: the same one draws the distant projectile (FarModels)
        e.registerEntityRenderer(ModEntities.DRONE.get(), ctx -> new StrikeProjectileRenderer<>(ctx, ClientWeaponSpec.DRONE.airframe().model()));
        e.registerEntityRenderer(ModEntities.CRUISE_MISSILE.get(), ctx -> new StrikeProjectileRenderer<>(ctx, ClientWeaponSpec.MISSILE.airframe().model()));
        e.registerEntityRenderer(ModEntities.ROCKET.get(), ctx -> new StrikeProjectileRenderer<>(ctx, ClientWeaponSpec.ROCKET.airframe().model()));
        e.registerEntityRenderer(ModEntities.LOITER.get(), ctx -> new StrikeProjectileRenderer<>(ctx, ClientWeaponSpec.LOITER.airframe().model()));
        e.registerEntityRenderer(ModEntities.BOMBER.get(), ctx -> new StrikeProjectileRenderer<>(ctx, ClientWeaponSpec.BUNKER.airframe().model()));
        e.registerEntityRenderer(ModEntities.BUNKER_BUSTER.get(), ctx -> new StrikeProjectileRenderer<>(ctx, ClientWeaponSpec.BUNKER.airframe(true).model()));
        e.registerEntityRenderer(ModEntities.ICBM.get(), ctx -> new StrikeProjectileRenderer<>(ctx, ClientWeaponSpec.NUKE.airframe().model()));
        e.registerEntityRenderer(ModEntities.DEBRIS.get(), DebrisRenderer::new);
        e.registerEntityRenderer(ModEntities.SURFACE_DAMAGE.get(), ua.zentix.incoming.client.render.SurfaceDamageRenderer::new);
        e.registerEntityRenderer(ModEntities.LAUNCHER.get(), LauncherRenderer::new);
        e.registerEntityRenderer(ModEntities.SPENT_BOOSTER.get(), SpentBoosterRenderer::new);
        e.registerEntityRenderer(ModEntities.INTERCEPTOR.get(), InterceptorRenderer::new);
        e.registerBlockEntityRenderer(ModBlockEntities.FIXED_LAUNCHER.get(), FixedLauncherRenderer::new);
        e.registerBlockEntityRenderer(ModBlockEntities.SAM.get(), SamRenderer::new);
    }

    /**
     * Unpowered lamps (blackout twins) use the same textures, dimmed: they look unlit by day.
     * Tint applies only to models with {@code tintindex} (glowstone, lanterns, end rods…).
     */
    private static void blockColors(RegisterColorHandlersEvent.Block e) {
        e.register((state, level, pos, tint) -> UNLIT_TINT, ModBlocks.UNLIT.stream().map(p -> p.unlit().get()).toArray(Block[]::new));
    }

    /** Effects-layer atlas and explosion recording envelopes reload on every resource reload. */
    private static void reloadListeners(RegisterClientReloadListenersEvent e) {
        e.registerReloadListener(FxAtlas.INSTANCE);
        e.registerReloadListener(ua.zentix.incoming.client.nuclear.NukeBallSurface.INSTANCE);
        e.registerReloadListener(ua.zentix.incoming.client.sound.Envelopes.INSTANCE);
        e.registerReloadListener(Concussion.RELOAD);
    }

    private static void keys(RegisterKeyMappingsEvent e) {
        e.register(Keys.FIRE);
        e.register(Keys.MENU);
        e.register(Keys.MAP);
        e.register(Keys.CAMERA);
        e.register(Keys.SECOND_KEY);
    }

    private static void layers(RegisterGuiLayersEvent e) {
        // concussion below the flash: blurs the frame and camera overlays, with the white flash screen above
        e.registerBelow(VanillaGuiLayers.CROSSHAIR, Incoming.id("concussion"), Concussion::render);
        e.registerBelow(VanillaGuiLayers.CROSSHAIR, Incoming.id("flash"), Flash::render);
        e.registerBelow(VanillaGuiLayers.CROSSHAIR, Incoming.id("nuke_flash"), NukeFlash::render);
        e.registerAbove(VanillaGuiLayers.CROSSHAIR, Incoming.id("scope"), Designator::renderHud);
        e.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, Incoming.id("alerts"), Alerts::render);
        e.registerAbove(VanillaGuiLayers.SCOREBOARD_SIDEBAR, Incoming.id("strikes"), StrikesHud::render);
        e.registerBelow(VanillaGuiLayers.CROSSHAIR, Incoming.id("projectile_camera"), ProjectileCamera::render);
        e.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, Incoming.id("nuke"), NukeHud::render);
        e.registerAbove(VanillaGuiLayers.HOTBAR, Incoming.id("geiger"), Geiger::render);
        e.registerAbove(VanillaGuiLayers.SCOREBOARD_SIDEBAR, Incoming.id("radar_scope"), RadarScope::render);
    }

    /** Projectile engines (plume, trail, launch cloud), burning debris and booster smoke: after the entity's client-world tick. */
    private static void entityTick(EntityTickEvent.Post e) {
        Entity entity = e.getEntity();
        if (!entity.level().isClientSide) return;
        if (entity instanceof StrikeProjectile p) Exhaust.tick(p);
        else if (entity instanceof DebrisEntity d) Exhaust.debris(d);
        else if (entity instanceof SpentBoosterEntity b) Exhaust.spent(b);
        else if (entity instanceof InterceptorEntity i) Exhaust.interceptor(i);
    }

    private static void preTick(ClientTickEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !mc.isPaused()) ua.zentix.incoming.client.fx.BlastTime.advance();
    }

    private static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        while (Keys.MENU.consumeClick()) mc.setScreen(new RemoteScreen());
        while (Keys.MAP.consumeClick()) mc.setScreen(new MapScreen(new RemoteScreen()));
        while (Keys.FIRE.consumeClick()) Designator.fire();
        while (Keys.CAMERA.consumeClick()) ProjectileCamera.cycle();
        while (Keys.SECOND_KEY.consumeClick()) PacketDistributor.sendToServer(new C2S.NuclearKey());
        Replay.tick();
        // flash traces update while paused too: replay can seek while paused
        ua.zentix.incoming.client.nuclear.FlashMarksClient.tick();
        VoxyIngest.tick();
        if (mc.isPaused()) return;
        Designator.tick();
        ClientFlights.tick();
        TerrainTiles.tick();
        ProjectileCamera.tick();
        FlightTracks.tick();
        ClientSounds.tick();
        FarRenderer.tick();
        FxLayer.tick();
        ua.zentix.incoming.client.fx.smoke.SmokeGrid.tick();
        // local-chunk fire in the nuke's burning city supplies its roar; smoke from crater fuel and substation oil
        Vec3 fireEye = mc.gameRenderer.getMainCamera().getPosition();
        ua.zentix.incoming.client.fx.FireWatch.tick(mc.level, (int) Math.floor(fireEye.x) >> 4, (int) Math.floor(fireEye.z) >> 4,
                mc.options.getEffectiveRenderDistance());
        ua.zentix.incoming.client.fx.FireSmoke.tick(mc.level);
        // particles tick after the world, like the vanilla engine; they stand still while frozen
        if (mc.level.tickRateManager().runsNormally()) {
            FxPool.INSTANCE.tick(mc.level);
            Fragments.tick();
        }
        Effects.tick();
        CameraShake.tick(CameraShake.standing(mc.player));
        Flash.tick();
        Concussion.tick();
        Alerts.tick();
        RadarScope.tick();
        NukeArming.tick();
        ClientNuclear.tick();
    }

    /**
     * Flashback replay seek: explosions, shake, sounds and projectile tracks from before seeking belong to another
     * recording position; nuclear detonations retain their age ({@link ClientNuclear#rewound}).
     */
    private static void rewound() {
        ClientSounds.reset();
        FlightTracks.reset();
        FarRenderer.reset();
        ua.zentix.incoming.client.fx.smoke.SmokeGrid.reset();
        ua.zentix.incoming.client.fx.FireSmoke.reset();
        FxPool.INSTANCE.clear();
        Effects.clear();
        CameraShake.reset();
        Flash.reset();
        Concussion.reset();
        Alerts.reset();
        ClientNuclear.rewound();
    }

    /** Mouse wheel in binoculars selects a weapon instead of a hotbar slot. */
    private static void scroll(InputEvent.MouseScrollingEvent e) {
        if (Designator.isScoping() && e.getScrollDeltaY() != 0) {
            Designator.scroll(e.getScrollDeltaY());
            e.setCanceled(true);
        }
    }

    /** Binoculars have their own crosshair. */
    private static void hideCrosshair(RenderGuiLayerEvent.Pre e) {
        if (e.getName().equals(VanillaGuiLayers.CROSSHAIR) && (Designator.isScoping() || ProjectileCamera.isActive())) e.setCanceled(true);
    }

    /**
     * Nuclear mushroom volume noise builds in the background from world entry (~one second on one core, once per game launch):
     * the fireball is the mushroom volume from the first instant; without launch warning ({@code /incoming nuke now}),
     * it would otherwise have no body for its first second. Also once per launch, warm what detonation builds on the frame
     * thread ({@link ClientNuclear#warm}: dust wall and pall).
     */
    private static void login(ClientPlayerNetworkEvent.LoggingIn e) {
        PallPass.prepare();
        VoxyIngest.login();
        if (!nukeWarmed) {
            nukeWarmed = true;
            net.minecraft.Util.backgroundExecutor().execute(ClientNuclear::warm);
        }
    }

    /** Detonation warmed ({@link #login}). */
    private static boolean nukeWarmed;

    private static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        resetSession();
    }

    /** Everything the client holds about the world: sounds, tracks, effects, the nuke, map; world exit (and devtest effects sandbox). */
    public static void resetSession() {
        ua.zentix.incoming.client.fx.BlastTime.reset();
        ClientSounds.reset();
        FlightTracks.logout();
        FarRenderer.reset();
        ua.zentix.incoming.client.fx.smoke.SmokeGrid.reset();
        ua.zentix.incoming.client.fx.FireSmoke.reset();
        FxPool.INSTANCE.clear();
        FxLayer.reset();
        Fragments.reset();
        ua.zentix.incoming.client.fx.FrontLitter.reset();
        Effects.clear();
        CameraShake.reset();
        Flash.reset();
        Concussion.reset();
        Alerts.reset();
        ProjectileCamera.reset();
        ClientFlights.reset();
        NukeArming.cancel();
        Designator.reset();
        RadarScope.reset();
        ClientNuclear.reset();
        ua.zentix.incoming.client.nuclear.FlashMarksClient.reset();
        MapTarget.reset();
        MapPlayers.reset();
        MapScreen.reset();
        TerrainTiles.reset();
        Replay.reset();
    }

    /**
     * Server packets. One-shot events (explosion, shake, siren, sound) from Flashback replay seeking are in the past and absent
     * ({@link Replay#event}); state (own projectiles, the nuke, radiation) is always accepted, distant tracks only when live.
     */
    private static final class Hooks implements ClientHooks {
        /**
         * The mod renders and sounds impacts itself ({@code blast_effects}); when disabled, another explosion mod shows them (from
         * the server explosion with the projectile as source), without our impact visuals, sound, wave or jolt: neither explosion,
         * crater fuel (smoke, light, roar; block fire belongs to the world), nor bunker-buster bomb (ground impact, jolt,
         * gas vent, collapse). Air-defense bursts and the nuke always retain their effects.
         */
        private static boolean ownBlasts() {
            return IncomingConfig.CLIENT.blastEffects.get();
        }

        @Override
        public void blast(S2C.Blast p) {
            Replay.event("blast", live -> {
                if (!live) return;
                p.projectile().ifPresent(FlightTracks::impact);
                if (ownBlasts()) BlastEffects.blast(p);
            });
        }

        @Override
        public void bunkerImpact(S2C.BunkerImpact p) {
            Replay.event("bunker", live -> {
                if (live && ownBlasts()) BlastEffects.bunkerImpact(p);
            });
        }

        @Override
        public void vent(S2C.Vent p) {
            Replay.event("vent", live -> {
                if (live && ownBlasts()) BlastEffects.vent(p);
            });
        }

        @Override
        public void fuelFire(S2C.FuelFire p) {
            Replay.event("fuel", live -> {
                if (live && ownBlasts()) BlastEffects.fuelFire(p);
            });
        }

        @Override
        public void collapse(S2C.Collapse p) {
            Replay.event("collapse", live -> {
                if (live && ownBlasts()) BlastEffects.collapse(p);
            });
        }

        /**
         * Ground jolt: for a ground-wave source (bomb ground impact, underground explosion), arrival and strength are at this
         * player's feet ({@link ua.zentix.incoming.client.fx.GroundShake}); old packets without a source (old Flashback
         * replay recordings) shake and rumble immediately.
         */
        @Override
        public void quake(S2C.Quake p) {
            Replay.event("quake", live -> {
                if (!live || !ownBlasts()) return;
                if (p.source().isPresent()) {
                    S2C.Quake.Ground g = p.source().get();
                    ua.zentix.incoming.client.fx.GroundShake.schedule(g.pos(), g.model(), g.gameTime(), g.gameTime() ^ Double.doubleToLongBits(g.pos().x + g.pos().z));
                    return;
                }
                CameraShake.quake(p.ticks());
                if (p.rumble()) BlastSounds.quake();
            });
        }

        @Override
        public void siren(S2C.Siren p) {
            Replay.event("siren", live -> {
                if (live) Alerts.siren(p);
            });
        }

        @Override
        public void salvoStatus(S2C.SalvoStatus p) {
            Alerts.salvo(p);
        }

        @Override
        public void openRemote() {
            // the recorded player opened the remote, not the replay viewer
            if (!Replay.active()) Minecraft.getInstance().setScreen(new RemoteScreen());
        }

        @Override
        public void cleared(S2C.Cleared p) {
            Player me = Minecraft.getInstance().player;
            if (p.owner().isEmpty()) Alerts.reset();
            else if (me != null && p.owner().get().equals(me.getUUID())) Alerts.salvoCancelled();
            if (p.nuclear()) {
                ClientSounds.reset();
                FlightTracks.reset();
                ProjectileCamera.reset();
                NukeArming.cancel();
            } else {
                // ordinary cancel: nuclear projectiles keep flying; their sound, camera and two-step ICBM launch remain
                ClientSounds.cancelled(p.projectiles());
                FlightTracks.cancelled(p.projectiles());
                ProjectileCamera.cancelled(p.projectiles());
            }
        }

        @Override
        public void nukeWarning(S2C.NukeWarning p) {
            Replay.event("warning", live -> ClientNuclear.warning(p, live));
        }

        @Override
        public void nukeDetonation(S2C.NukeDetonation p) {
            Replay.event("nuke", live -> ClientNuclear.detonation(p, live));
        }

        @Override
        public void nukeDebris(S2C.NukeDebris p) {
            // debris from seeking is in the past: it landed long ago
            Replay.event("nuke_debris", live -> {
                if (live) ClientNuclear.debris(p);
            });
        }

        @Override
        public void nukeFlashMarks(S2C.NukeFlashMarks p) {
            // chunk state: always accepted, including during seeking (the layer appears when the clock reaches the flash)
            ua.zentix.incoming.client.nuclear.FlashMarksClient.receive(p);
        }

        @Override
        public void nukeSync(S2C.NukeSync p) {
            ClientNuclear.sync(p);
        }

        @Override
        public void radiation(S2C.Radiation p) {
            ClientNuclear.radiation(p);
        }

        @Override
        public void retinaScars(S2C.RetinaScars p) {
            // eye state, not an event: also accepted during replay seeking
            NukeFlash.scars(p.scars());
        }

        @Override
        public void flights(S2C.Flights p) {
            ClientFlights.update(p);
        }

        @Override
        public void farFlights(S2C.FarFlights p) {
            // seek packets would arrive all at once as one tick's track points
            Replay.event("far", live -> {
                if (live) FlightTracks.received(p);
            });
        }

        @Override
        public void mapPlayers(S2C.MapPlayers p) {
            MapPlayers.received(p);
        }

        @Override
        public void gridFailure(S2C.GridFailure p) {
            Replay.event("grid", live -> {
                if (live) GridEffects.failure(p);
            });
        }

        @Override
        public void gridDistrict(S2C.GridDistrict p) {
            Replay.event("district", live -> {
                if (live) GridEffects.district(p);
            });
        }

        @Override
        public void substation(net.minecraft.core.BlockPos pos, boolean powered, boolean spark, float pitch) {
            GridEffects.substation(pos, powered, spark, pitch);
        }

        @Override
        public void intercept(S2C.Intercept p) {
            Replay.event("intercept", live -> {
                if (!live) return;
                p.target().ifPresent(FlightTracks::impact);
                BlastEffects.intercept(p);
            });
        }

        @Override
        public void voxyChunk(S2C.VoxyChunk p) {
            VoxyIngest.accept(p);
        }

        @Override
        public void radarScope(S2C.RadarScope p) {
            // the screen is current state: absent during replay seeking
            Replay.event("radar", live -> {
                if (live) RadarScope.received(p);
            });
        }

        @Override
        public void replayTick(S2C.ReplayTick p) {
            Replay.marker(p.place(), p.seeking(), IncomingClient::rewound);
        }
    }
}
