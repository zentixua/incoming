package ua.zentix.incoming.client.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.model.FuelPool;

import java.util.ArrayList;
import java.util.List;

/**
 * Burning crater fuel as known to the client: server state ({@code S2C.FuelFire}, {@code warhead/FuelFires}) —
 * cells in burnout order and those extinguished early — plus the fuel law ({@link FuelPool}). Supplies smoke source heat
 * ({@link FireSmoke#pool}), position and rumble ({@code CraterFire}): drained fuel moves the source to where it now burns; buried or
 * flooded fuel burns over a smaller area; all finished — source dies out. No Minecraft client dependency.
 */
public final class FuelBurn {
    /** Crater (server ID; −1 — serverless: old replay, sandbox — only at the impact point). */
    final int id;
    /** Fuel law on this crater's cells. */
    final FuelPool law;
    /** Law-based extinction time of each cell, s after impact. */
    private final double[] outs;
    private List<BlockPos> cells = List.of();
    private long smothered;
    private Vec3 centre = Vec3.ZERO;

    /**
     * Crater {@code id} with {@code law} on {@code lit} cells (law-based extinction at {@link FuelPool#cellSeconds}),
     * current positions {@code cells} and early-extinguished {@code smothered} (bits by cell index).
     */
    FuelBurn(int id, FuelPool law, int lit, List<BlockPos> cells, long smothered) {
        this.id = id;
        this.law = law;
        this.outs = new double[Math.max(1, lit)];
        for (int k = 0; k < outs.length; k++) outs[k] = law.cellSeconds(k, outs.length);
        update(cells, smothered);
    }

    /** Serverless fuel — {@code law} at a single point {@code pos} (old replay, sandbox, checks). */
    public static FuelBurn at(FuelPool law, BlockPos pos) {
        return new FuelBurn(-1, law, law.cells(), List.of(pos), 0);
    }

    /** New server state: cell positions and which were extinguished early. */
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

    /** Cells whose fuel did not end early (their fire is fuel, {@link FuelCells}). */
    List<BlockPos> live() {
        List<BlockPos> out = new ArrayList<>(cells.size());
        for (int k = 0; k < cells.size(); k++) if ((smothered >>> k & 1) == 0) out.add(cells.get(k));
        return out;
    }

    /** Fuel law on this crater's cells. */
    public FuelPool law() {
        return law;
    }

    /** Centre of burning cells: smoke source, light and rumble. */
    public Vec3 centre() {
        return centre;
    }

    /**
     * Law's heat release fraction at {@code t} s after impact ({@link FuelPool#share}), × fraction of cells still meant to
     * burn under the law that have not gone out early: a buried cell removes its area share.
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
