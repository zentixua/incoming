package ua.zentix.incoming.rig;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Isolated loopback release matrix; inert unless explicitly enabled by the rig launcher. */
@Mod(Incoming.MOD_ID)
public final class ReleaseCheckRig {
    private static final String[] CASES = {"drone", "loiter", "missile", "rocket", "bunker", "nuke", "missile", "nuke", "missile", "missile"};
    private final Path directory;
    private int phase = -1, start, impact = -1;
    private boolean seen, ready;

    public ReleaseCheckRig() {
        String dir = System.getProperty("incoming.check.dir");
        directory = dir == null ? null : Path.of(dir);
        if (directory == null) return;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::blast);
    }

    private void blast(ExplosionEvent.Start event) {
        if (phase < 0 || impact >= 0 || !seen) return;
        Vec3 at = event.getExplosion().center();
        if (Math.hypot(at.x - phase * 800.0, at.z) < 200) {
            impact = event.getLevel().getServer().getTickCount();
            log("IMPACT " + phase + " " + at);
        }
    }

    private void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (!server.isDedicatedServer()) return;
        var alpha = server.getPlayerList().getPlayerByName("Alpha");
        var bravo = server.getPlayerList().getPlayerByName("Bravo");
        if (alpha == null || bravo == null) return;
        var level = server.overworld();
        int now = server.getTickCount();
        if (phase < 0) {
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            level.setDayTime(6000);
            IncomingConfig.SERVER.nukeFlightTime.set(400);
            IncomingConfig.SERVER.loiterTime.set(5);
            IncomingConfig.SERVER.carrierNukes.set(true);
            // The rig observes hits and presentation, not destruction throughput; use an intact repeatable flat scene.
            IncomingConfig.SERVER.blockDamage.set(false);
            IncomingConfig.SERVER.nukeBlockDamage.set(false);
            IncomingConfig.SERVER.nukeFires.set(false);
            next(alpha, bravo, now);
        }
        if (phase >= CASES.length) return;
        int age = now - start;
        if (age == 100) ready = true;
        var flights = StrikeWorld.projectiles(level);
        if (!flights.isEmpty()) seen = true;
        if (age % 20 == 0) {
            for (var p : flights) log("FLIGHT " + phase + " " + age + " " + p.getUUID() + " " + p.getClass().getSimpleName()
                    + " " + p.position() + " " + p.flightPhase() + " virtual=" + !p.isAddedToLevel()
                    + " route=" + (p.route() == null ? "none" : p.route().toString()));
            write("phase=" + phase + "\nweapon=" + CASES[phase] + "\nage=" + age + "\nready=" + ready
                    + "\nearly=" + early() + "\ncarrier=" + (phase >= 8)
                    + "\nimpact=" + impact + "\nx=" + phase * 800 + "\n", "phase.properties");
        }
        if (impact < 0 && !NuclearEvents.get(level).detonations().isEmpty()) {
            impact = now;
            log("NUCLEAR_IMPACT " + phase + " " + NuclearEvents.get(level).detonations().getLast());
        }
        if (impact >= 0 && now - impact > 100) next(alpha, bravo, now);
        else if (age > 4600) {
            log("FAIL timeout phase=" + phase + " seen=" + seen);
            write("timeout phase=" + phase, "failed");
            server.halt(false);
        }
    }

    private void next(ServerPlayer alpha, ServerPlayer bravo, int now) {
        var server = alpha.getServer();
        ServerActions.clearAll(server, true, "isolated release rig");
        phase++;
        if (phase == CASES.length) {
            write("complete\n", "done");
            log("DONE");
            return;
        }
        start = now;
        impact = -1;
        seen = false;
        ready = false;
        IncomingConfig.SERVER.earlyWarning.set(early());
        if (phase == 8) {
            IncomingConfig.SERVER.nukeOpsOnly.set(false);
            server.getPlayerList().deop(alpha.getGameProfile());
        }
        for (ServerPlayer player : new ServerPlayer[]{alpha, bravo}) {
            player.setGameMode(GameType.CREATIVE);
            player.teleportTo(server.overworld(), phase * 800 + (player == alpha ? 65 : 90), 68, -80, 130, 0);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        log("PHASE " + phase + " " + CASES[phase] + " early=" + IncomingConfig.SERVER.earlyWarning.get());
    }

    private boolean early() { return phase == 6 || phase == 7 || phase == 9; }

    private void log(String line) {
        Incoming.LOG.info("RELEASE_CHECK {}", line);
        try { Files.writeString(directory.resolve("server-matrix.log"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    private void write(String content, String name) {
        try {
            Files.createDirectories(directory);
            Path temporary = directory.resolve(name + ".tmp");
            Files.writeString(temporary, content);
            Files.move(temporary, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
