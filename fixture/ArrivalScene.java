package ua.zentix.incoming.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.nuclear.ClientNuclear;
import ua.zentix.incoming.net.ClientHooks;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.StemDust;
import ua.zentix.incoming.nuclear.model.Yield;

/** AIR8 PRIVATE capture fixture. Never ship. Existing arrival command supplies yield and AIR via flash flag. */
final class ArrivalScene {
 private static int lastSecond = -1;
 private ArrivalScene() {}
 static void start(Vec3 at, int yield, boolean air) {
  reset();
  var mc = Minecraft.getInstance();
  Properties props = new Properties();
  Path settings = Path.of("../air8/capture.properties");
  if (Files.exists(settings)) {
   try (var in = Files.newInputStream(settings)) { props.load(in); }
   catch (IOException e) { throw new IllegalStateException(e); }
  }
  var quality = IncomingConfig.Client.DustQuality.valueOf(props.getProperty("quality", "MEDIUM"));
  IncomingConfig.CLIENT.nukeDustQuality.set(quality); // isolated sandbox RAM only; never save config
  boolean fires = Boolean.parseBoolean(props.getProperty("fires", "false"));
  double initial = Double.parseDouble(props.getProperty("initialAge", "0"));
  float wave = Float.parseFloat(props.getProperty("wave", "0.25"));
  yield = Integer.parseInt(props.getProperty("yield", Integer.toString(yield)));
  double hob = Double.parseDouble(props.getProperty("hob", Double.toString(air ? Yield.optimalBurstHeight(yield) : 0)));
  long now = mc.level.getGameTime();
  Detonation d = new Detonation(9000, at.add(0, hob, 0), at.y, yield, !air,
    now - Math.round(initial * 20), 0, 0, 20_000, 1234, 1, false, wave, mc.level.getDayTime(), fires);
  S2C.NukeDetonation packet = new S2C.NukeDetonation(d, 0, 9000);
  if (initial == 0) ClientHooks.get().nukeDetonation(packet);
  else ClientNuclear.detonation(packet, false); // explicit replay-age diagnostic, never used for main time sequences
  Incoming.LOG.info("AIR8 fixture yield={} air={} hob={} ground={} initialAge={} wave={} seed=1234 wind=0 visibility=20000 scale=1 fires={} quality={} time={} rain={} thunder={} eye={} fov={}",
    yield, air, hob, at, initial, wave, fires, quality, mc.level.getDayTime(), mc.level.getRainLevel(1), mc.level.getThunderLevel(1),
    mc.gameRenderer.getMainCamera().getPosition(), mc.options.fov().get());
 }
 static void tick() {
  var all = ClientNuclear.detonations();
  if (all.isEmpty()) return;
  var a = all.getFirst();
  int sec = (int) a.seconds(0);
  if (sec == lastSecond) return;
  lastSecond = sec;
  var d = a.d;
  var s = ua.zentix.incoming.client.nuclear.Air8Capture.shape(a);
  if (s == null) return;
  Incoming.LOG.info("AIR8 model age={} positiveStem={} capBottom={} capTop={} stemTop={} stemTau={} eye={}",
    a.seconds(0), s.stemTau() > 0, s.capBottom(), s.top(), s.stemTop(), s.stemTau(), Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
 }
 static void reset() { lastSecond = -1; }
}
