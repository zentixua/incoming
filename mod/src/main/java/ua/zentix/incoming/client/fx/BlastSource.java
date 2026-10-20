package ua.zentix.incoming.client.fx;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

/**
 * Что взорвалось — то, что картинке и звуку нужно от взрыва, из пакета {@link S2C.Blast} и паспорта оружия
 * ({@link WeaponSpec}): заряд и топливо к удару берутся только отсюда, а не из вида пакета (по виду «Ланцет» был шахедом).
 * Без Minecraft.
 *
 * @param weapon      оружие; null — взрыв не оружия из паспорта (разрыв зенитной ракеты)
 * @param chargeKg    заряд в тротиловом эквиваленте, кг ({@link WeaponSpec#charge}); 0 — наземного взрыва нет (бомба
 *                    рвётся под землёй, у неё свои звуки)
 * @param fuelKg      топливо, что сгорает огненным шаром, кг ({@link WeaponSpec#fuelAtImpact})
 * @param surface     взрыв на земле (волна отражается от неё — давление как у заряда в 1,8 раза больше) или в воздухе
 * @param underground бомба взорвалась под землёй ({@link S2C.Blast#BUNKER})
 */
public record BlastSource(@Nullable WeaponType weapon, double chargeKg, double fuelKg, boolean surface, boolean underground) {
    /** Взрыв из пакета: оружие из пакета (в старом повторе — по виду), числа — из его паспорта. */
    public static BlastSource of(S2C.Blast p) {
        return of(p.weaponType(), p.kind() == S2C.Blast.BUNKER);
    }

    /** Взрыв оружия {@code w} на земле ({@code underground} — бомба под землёй). */
    public static BlastSource of(WeaponType w, boolean underground) {
        WeaponSpec s = w.spec();
        return new BlastSource(w, s.charge(), s.fuelAtImpact(), true, underground);
    }

    /** Разрыв в воздухе заряда {@code chargeKg} кг ТНТ без топлива. */
    public static BlastSource air(double chargeKg) {
        return new BlastSource(null, chargeKg, 0, false, false);
    }
}
