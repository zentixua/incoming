package ua.zentix.incoming.sound;

/**
 * Октавные спектры петель моторов (63 Гц – 8 кГц, дБ без взвешивания, сумма — 0 дБ) — то, что звучит в игре.
 * Файл пишет {@code tools/build_sounds.py} (функция {@code spectra}), руками не править: собранная заново петля
 * меняет и свой спектр, а с ним — слышимость на сервере и громкость на клиенте.
 */
public final class Spectra {
    // @spectra-begin
    public static final Spectrum DRONE_ENGINE = Spectrum.of(-19.6, 0.0, -5.1, -9.6, -14.3, -19.2, -21.1, -37.7);
    public static final Spectrum LOITER_ENGINE = Spectrum.of(-53.3, -42.9, 0.0, -2.3, -3.7, -4.2, -2.1, -11.4);
    public static final Spectrum LOITER_DIVE = Spectrum.of(-27.4, -13.9, -4.9, 0.0, -0.2, -6.2, -13.6, -16.5);
    public static final Spectrum MISSILE_ENGINE = Spectrum.of(-19.6, -9.5, -8.5, -9.4, -7.1, -3.9, 0.0, -9.9);
    public static final Spectrum MISSILE_ENGINE_REAR = Spectrum.of(-10.8, -0.4, 0.0, -5.4, -9.0, -11.8, -10.9, -20.6);
    public static final Spectrum BOMBER_ENGINE = Spectrum.of(-4.9, -1.0, 0.0, -1.2, -2.7, -3.5, -3.4, -7.7);
    public static final Spectrum BOMB_FALL = Spectrum.of(-24.2, -17.9, -9.7, -3.0, 0.0, -5.4, -12.2, -16.3);
    public static final Spectrum BOOSTER_ENGINE = Spectrum.of(0.0, -1.5, -6.4, -10.4, -11.1, -8.5, -15.8, -33.0);
    public static final Spectrum ROCKET_INCOMING = Spectrum.of(-18.8, -8.5, -0.9, 0.0, -0.0, -4.8, -15.7, -23.6);
    public static final Spectrum BOMB_DRILL = Spectrum.of(0.0, -5.0, -3.7, -3.9, -7.6, -11.8, -23.7, -47.6);
    public static final Spectrum LAUNCH_BOOSTER = Spectrum.of(-7.3, -1.9, -1.2, -0.5, 0.0, -1.5, -4.6, -12.2);
    public static final Spectrum ROCKET_LAUNCH = Spectrum.of(-7.9, -3.0, -0.5, -1.3, 0.0, -6.3, -10.2, -23.1);
    public static final Spectrum LOITER_LAUNCH = Spectrum.of(-7.7, 0.0, -2.4, -3.5, -8.8, -7.4, -3.4, -15.7);
    public static final Spectrum BOOSTER_SEPARATE = Spectrum.of(-3.7, -2.4, -2.8, -1.9, 0.0, -8.2, -11.9, -16.2);
    // @spectra-end

    private Spectra() {}
}
