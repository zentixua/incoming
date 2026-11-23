package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

/**
 * Probe (unified-wave mission, not for merge as is): Artem's live 1 Mt on a dedicated server, anchor arrivals per client tick
 * from his Flashback recording ({@code age-clock/artem-25567-1mt.txt}), replayed through the real {@link ClientNuclear.AgeClock}
 * at 60 frames a second. Prints holds and surges of the shown age (the drawn wave's position).
 */
class AgeClockLiveServerProbeTest {
    @Test
    void artemsLiveStream() throws java.io.IOException {
        java.util.TreeMap<Integer, java.util.List<Long>> arrivals = new java.util.TreeMap<>();
        try (var in = AgeClockLiveServerProbeTest.class.getResourceAsStream("/age-clock/artem-25567-1mt.txt")) {
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.trim().split(" ");
                arrivals.computeIfAbsent(Integer.parseInt(f[0]), k -> new java.util.ArrayList<>()).add(Long.parseLong(f[1]));
            }
        }
        ClientNuclear.AgeClock clock = new ClientNuclear.AgeClock(0, true);
        int end = arrivals.lastKey(), own = 0;
        double fps = 60, frame = 20 / fps, prev = 0;
        int frames = 0, held = 0, run = 0, longest = 0, episodes = 0, surges = 0;
        double fastest = 0;
        StringBuilder log = new StringBuilder();
        for (double t = frame; t < end; t += frame) {
            while (own + 1 <= t) {
                own++;
                for (long a : arrivals.getOrDefault(own, java.util.List.of())) clock.anchor(a);
                clock.tick();
            }
            double shown = clock.at(t - own);
            double rate = (shown - prev) / frame;
            frames++;
            if (rate < 0.1) {
                held++;
                run++;
            } else {
                if (run * 1000 / fps >= 100) episodes++;
                longest = Math.max(longest, run);
                run = 0;
            }
            if (rate > 1.5) surges++;
            fastest = Math.max(fastest, rate);
            if (frames % 60 == 0) log.append(String.format(java.util.Locale.ROOT, "%.0f:%.1f ", t / 20, t - shown));
            prev = shown;
        }
        System.out.printf(java.util.Locale.ROOT,
                "PROBE AgeClock on Artem's 25567 stream: frames %d, held %.1f %%, holds >= 100 ms %d, longest %.0f ms, frames above 1.5x %d, fastest %.2fx%n",
                frames, 100.0 * held / frames, episodes, longest * 1000 / fps, surges, fastest);
        System.out.println("PROBE real minus shown age per second (s:ticks): " + log);
    }
}
