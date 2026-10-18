package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.EyeInjury;
import ua.zentix.incoming.registry.ModAttachments;

/**
 * Глаза игрока и вспышка ({@link EyeInjury}, G&amp;D §12.83–12.88). Засветку всего поля (временную) считает клиент сам
 * ({@code NukeFlash}); здесь — ожог сетчатки: постоянное слепое пятно там, где на сетчатке был шар, у того, кто
 * смотрел в его сторону (шар в поле зрения, не дальше 60° от взгляда) и видел его. Игрок, привыкший к темноте, — с
 * широким зрачком, ожог дальше.
 */
public final class FlashEyes {
    /** Шар дальше этого угла от взгляда (косинус 60°) — за краем того, что видно на экране. */
    private static final double FIELD_COS = 0.5;

    private FlashEyes() {}

    /** Насколько глаз у {@code eye} привык к темноте: 0 — день, 1 — ночь (свет неба и блоков у глаза). */
    public static double darkness(Level level, Vec3 eye) {
        return EyeInjury.darkness(level.getMaxLocalRawBrightness(BlockPos.containing(eye), skyDarken(level)));
    }

    /**
     * На сколько ступеней (0..11) небо сейчас темнее дня, как {@code Level.updateSkyBrightness}: клиент ваниль считает
     * его только при входе в мир, и ночь у клиента была бы днём.
     */
    static int skyDarken(Level level) {
        double rain = 1 - level.getRainLevel(1) * 5 / 16.0, thunder = 1 - level.getThunderLevel(1) * 5 / 16.0;
        double sun = 0.5 + 2 * Mth.clamp(Mth.cos(level.getTimeOfDay(1) * (float) (Math.PI * 2)), -0.25, 0.25);
        return (int) ((1 - sun * rain * thunder) * 11);
    }

    /** Докуда (блоки) сетчатка может обжечься у смотревшего на шар — самый тёмный глаз. */
    public static double reach(Detonation d) {
        return d.blocks(EyeInjury.retinaRange(d.yieldKt(), 1, d.visibility()));
    }

    /**
     * Игрок {@code p}, который видит шар: ожог сетчатки, если смотрел в его сторону и был в пределах ожога; у спящего
     * глаза закрыты. Игрока, которого правила не дают ранить пустившему {@code owner} (без PvP, своя команда), ожог не
     * трогает, как и ожог кожи ({@link FlashBurns}).
     */
    public static void flash(Level level, Detonation d, ServerPlayer p, @Nullable Entity owner) {
        if (p.isSleeping() || owner instanceof Player attacker && !p.canHarmPlayer(attacker)) return;
        RetinaScars.Scar scar = scar(d, p, darkness(level, p.getEyePosition()));
        if (scar == null) return;
        RetinaScars next = scars(p).with(scar);
        p.setData(ModAttachments.RETINA, next);
        PacketDistributor.sendToPlayer(p, new S2C.RetinaScars(next));
    }

    /** Ожог сетчатки у сущности {@code e}, которая видит шар, при привыкании к темноте {@code dark}; нет ожога — null. */
    @Nullable
    public static RetinaScars.Scar scar(Detonation d, LivingEntity e, double dark) {
        Vec3 eye = e.getEyePosition(), f = e.getViewVector(1), to = d.burst().subtract(eye);
        double len = to.length();
        if (len < 1e-3) return null;
        double depth = to.dot(f);
        if (depth < FIELD_COS * len) return null;
        if (d.metres(len) > EyeInjury.retinaRange(d.yieldKt(), dark, d.visibility())) return null;
        // базис взгляда без крена — тот же, что у послеобраза на экране
        double yaw = Math.toRadians(e.getViewYRot(1));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)), up = right.cross(f);
        return new RetinaScars.Scar((float) (to.dot(right) / depth), (float) (to.dot(up) / depth), (float) (d.fireballRadius() / depth));
    }

    public static RetinaScars scars(LivingEntity e) {
        return e.getData(ModAttachments.RETINA);
    }

    /** Пятна заново целиком: вход в мир, возрождение, смена измерения. */
    public static void sync(ServerPlayer p) {
        PacketDistributor.sendToPlayer(p, new S2C.RetinaScars(scars(p)));
    }
}
