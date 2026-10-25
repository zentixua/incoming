package ua.zentix.incoming.client.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.model.FuelPool;

import java.util.ArrayList;
import java.util.List;

/**
 * Горящее топливо воронки, как его знает клиент: состояние с сервера ({@code S2C.FuelFire}, {@code warhead/FuelFires}) —
 * клетки в порядке выгорания и какие погасли раньше срока, — и закон топлива ({@link FuelPool}). Отсюда тепло очага дыма
 * ({@link FireSmoke#pool}), его место и гул ({@code CraterFire}): стекло — очаг там, где оно теперь горит; засыпало или
 * залило — горит меньшая площадь; кончилось всё — очаг гаснет. Без Minecraft-клиента.
 */
public final class FuelBurn {
    /** Воронка (номер сервера; −1 — без сервера: старый повтор, песочница — только у места удара). */
    final int id;
    /** Закон топлива на клетках этой воронки. */
    final FuelPool law;
    /** Когда гаснет каждая клетка по закону, с от удара. */
    private final double[] outs;
    private List<BlockPos> cells = List.of();
    private long smothered;
    private Vec3 centre = Vec3.ZERO;

    /**
     * Воронка {@code id} с законом {@code law} на {@code lit} клетках (по закону гаснут в {@link FuelPool#cellSeconds}),
     * нынешние места {@code cells} и погасшие раньше срока {@code smothered} (биты по номеру клетки).
     */
    FuelBurn(int id, FuelPool law, int lit, List<BlockPos> cells, long smothered) {
        this.id = id;
        this.law = law;
        this.outs = new double[Math.max(1, lit)];
        for (int k = 0; k < outs.length; k++) outs[k] = law.cellSeconds(k, outs.length);
        update(cells, smothered);
    }

    /** Топливо без сервера — по закону {@code law} у одной точки {@code pos} (старый повтор, песочница, проверки). */
    public static FuelBurn at(FuelPool law, BlockPos pos) {
        return new FuelBurn(-1, law, law.cells(), List.of(pos), 0);
    }

    /** Новое состояние с сервера: места клеток и какие погасли раньше срока. */
    void update(List<BlockPos> cells, long smothered) {
        this.cells = List.copyOf(cells);
        this.smothered = smothered;
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (BlockPos p : live()) {
            x += p.getX() + 0.5;
            y += p.getY();
            z += p.getZ() + 0.5;
            n++;
        }
        if (n > 0) centre = new Vec3(x / n, y / n, z / n);
    }

    /** Клетки, чьё топливо не кончилось раньше срока (огонь на них — топливо, {@link FuelCells}). */
    List<BlockPos> live() {
        List<BlockPos> out = new ArrayList<>(cells.size());
        for (int k = 0; k < cells.size(); k++) if ((smothered >>> k & 1) == 0) out.add(cells.get(k));
        return out;
    }

    /** Закон топлива на клетках этой воронки. */
    public FuelPool law() {
        return law;
    }

    /** Середина горящих клеток: очаг дыма, свет и гул. */
    public Vec3 centre() {
        return centre;
    }

    /**
     * Доля тепловыделения закона в {@code t} с от удара ({@link FuelPool#share}), × доля клеток, которым по закону ещё
     * гореть, что не погасли раньше срока: засыпанная клетка уносит свою часть площади.
     */
    public double share(double t) {
        double share = law.share(t);
        if (share <= 0 || smothered == 0) return share;
        int scheduled = 0, alive = 0;
        for (int k = 0; k < outs.length; k++) {
            if (outs[k] <= t) continue;
            scheduled++;
            if ((smothered >>> k & 1) == 0) alive++;
        }
        return scheduled == 0 ? 0 : share * alive / scheduled;
    }
}
