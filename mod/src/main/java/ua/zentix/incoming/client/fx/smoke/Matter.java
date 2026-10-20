package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.plume.Smoke;

/**
 * Конденсированные продукты двигателя ({@link Smoke}: Al₂O₃, соли, сажа, лёд) — веществами сетки дыма. У сетки их три:
 * сажа (ω 0,25), пыль (ω по цвету грунта) и капли (ω ≈ 1, не поглощают). Продукт с ослаблением σ_m и альбедо ω — смесь
 * сажи и капель с той же сырой толщей σ_m·m и тем же альбедо: доля толщи сажи (1 − ω)/(1 − ω_сажи), остальное — капли.
 * Асимметрия у капель 0,85 (у дыма двигателей 0,6–0,8, {@link Smoke#ASYMMETRY}): вперёд они рассеивают чуть больше —
 * известное отличие, не подбор.
 */
public final class Matter {
    private Matter() {}

    /** В out[0] — сажа, out[1] — капли, кг: та же толща и альбедо, что у kg продукта smoke. */
    public static void of(Smoke smoke, double kg, double[] out) {
        double e = smoke.extinction * kg;
        double soot = Math.max(0, Math.min(e, e * (1 - smoke.albedo) / (1 - CloudModel.SOOT_W)));
        out[0] = soot / CloudModel.SOOT_K;
        out[1] = (e - soot) / CloudModel.DROP_K;
    }
}
