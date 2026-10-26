package ua.zentix.incoming.gametest;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * GameTest sharded across several CI machines ({@code ./gradlew runGameTestServer -PgametestShard=i/n},
 * {@code .github/workflows/build.yml}): each machine runs its share of batches; together they run every test exactly once.
 * A whole batch stays in one shard: its tests run concurrently and may rely on this. Shards are balanced using batch
 * times from past CI runs ({@code gametest-durations.json} in devtest resources, written by
 * {@code tools/gametest_durations.py} from GameTest logs); batches absent from the table use its median. Without
 * {@code incoming.gametest.shard}, run all tests.
 * <p>
 * The GameTest server obtains and copies the test list before any mod events ({@code GameTestRegistry.getAllTestFunctions()}
 * in {@code Main}), then assembles batches from that copy in {@code initServer} after {@link ServerAboutToStartEvent}.
 * Here the copy is narrowed to its shard (and to {@code -PgametestOnly=<regex>} batches for local runs of selected tests).
 * Not included in the mod jar (devtest).
 */
@Mod(Incoming.MOD_ID)
public final class GameTestShards {
    private static final String RESOURCE = "/gametest-durations.json";

    public GameTestShards() {
        String spec = System.getProperty("incoming.gametest.shard", ""), only = System.getProperty("incoming.gametest.only", "");
        if (!only.isBlank()) NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> only(e, only));
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, BlastLog::onStart);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartingEvent e) -> {
            if (e.getServer() instanceof GameTestServer) TestScale.realWave();
        });
        if (!spec.isBlank()) NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> select(e, spec));
    }

    /** Only batch whose name matches the regular expression ({@code -PgametestOnly=…}: run your tests locally). */
    private static void only(ServerAboutToStartEvent e, String regex) {
        if (!(e.getServer() instanceof GameTestServer server)) return;
        List<TestFunction> tests = ObfuscationReflectionHelper.getPrivateValue(GameTestServer.class, server, "testFunctions");
        int total = tests.size();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        tests.removeIf(t -> !p.matcher(t.batchName()).matches());
        Incoming.LOG.info("GameTest: only batch “{}” - tests {} from {}", regex, tests.size(), total);
    }

    private static void select(ServerAboutToStartEvent e, String spec) {
        if (!(e.getServer() instanceof GameTestServer server)) return;
        String[] parts = spec.split("/");
        int part = parts.length == 2 ? Integer.parseInt(parts[0].trim()) : 0;
        int count = parts.length == 2 ? Integer.parseInt(parts[1].trim()) : 0;
        if (count < 1 || part < 1 || part > count) throw new IllegalArgumentException("incoming.gametest.shard=" + spec + ": expected i/n, 1 ≤ i ≤ n");

        List<TestFunction> tests = ObfuscationReflectionHelper.getPrivateValue(GameTestServer.class, server, "testFunctions");
        int total = tests.size();
        Map<String, Double> weights = weights(tests.stream().map(TestFunction::batchName).toList(), durations());
        Map<String, Integer> plan = assign(weights, count);
        tests.removeIf(t -> plan.get(t.batchName()) != part - 1);

        Map<String, Double> mine = new TreeMap<>();
        for (TestFunction t : tests) mine.put(t.batchName(), weights.get(t.batchName()));
        Incoming.LOG.info("GameTest: part of {} from {} - tests {} from {}, batch {}, according to previous runs ~{} s; batch: {}",
                part, count, tests.size(), total, mine.size(),
                String.format(Locale.ROOT, "%.0f", mine.values().stream().mapToDouble(Double::doubleValue).sum()), String.join(", ", mine.keySet()));
    }

    /**
     * The time of each batch: from the table of past runs, and a batch without time is the median of the table (the new batch is usually
     *  short); empty table - by second.
     *
     * @param batches names of batch tests (with repetitions - by test per name)
     */
    static Map<String, Double> weights(Collection<String> batches, Map<String, Double> seconds) {
        double[] known = seconds.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double fallback = known.length == 0 ? 1 : known[known.length / 2];
        Map<String, Double> out = new TreeMap<>();
        for (String b : batches) out.put(b, seconds.getOrDefault(b, fallback));
        return out;
    }

    /**
     * Assign batches to shards: longest first, each to the shard with the least time so far (ties go to the
     * lower shard number). The result does not depend on input batch order: every machine computes the same result itself.
     *
     * @param weights batch → time
     * @param count number of shards
     * @return batch → shard number starting at 0
     */
    static Map<String, Integer> assign(Map<String, Double> weights, int count) {
        List<String> names = new ArrayList<>(weights.keySet());
        names.sort(Comparator.comparingDouble((String b) -> weights.get(b)).reversed().thenComparing(Comparator.naturalOrder()));
        double[] load = new double[count];
        Map<String, Integer> plan = new HashMap<>();
        for (String b : names) {
            int min = 0;
            for (int i = 1; i < count; i++) if (load[i] < load[min]) min = i;
            load[min] += weights.get(b);
            plan.put(b, min);
        }
        return plan;
    }

    private static Map<String, Double> durations() {
        try (InputStream in = GameTestShards.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Incoming.LOG.warn("GameTest: no {} - parts are equal in number batch", RESOURCE);
                return Map.of();
            }
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, Double>>() {}.getType());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
