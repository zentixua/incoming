package ua.zentix.incoming.net;

/**
 * Мост к клиентскому коду: сервер регистрирует обработчики пакетов «сервер → клиент», но на выделенном сервере
 * они никогда не выполняются, а клиентских классов там нет. Клиентский модуль подставляет реализацию при запуске.
 */
public interface ClientHooks {
    default void blast(S2C.Blast p) {}

    default void bunkerImpact(S2C.BunkerImpact p) {}

    default void vent(S2C.Vent p) {}

    default void collapse(S2C.Collapse p) {}

    default void quake(S2C.Quake p) {}

    default void gridFailure(S2C.GridFailure p) {}

    default void gridDistrict(S2C.GridDistrict p) {}

    /**
     * Случайный тик подстанции у игрока ({@code SubstationBlock.animateTick}): работает — гул, выбита — горит масло
     * трансформатора и иногда ({@code spark}) трещит дуга.
     */
    default void substation(net.minecraft.core.BlockPos pos, boolean powered, boolean spark, float pitch) {}

    default void siren(S2C.Siren p) {}

    default void salvoStatus(S2C.SalvoStatus p) {}

    default void openRemote() {}

    default void cleared(S2C.Cleared p) {}

    default void nukeWarning(S2C.NukeWarning p) {}

    default void nukeDetonation(S2C.NukeDetonation p) {}

    default void nukeDebris(S2C.NukeDebris p) {}

    default void nukeFlashMarks(S2C.NukeFlashMarks p) {}

    default void nukeSync(S2C.NukeSync p) {}

    default void radiation(S2C.Radiation p) {}

    default void retinaScars(S2C.RetinaScars p) {}

    default void flights(S2C.Flights p) {}

    default void farFlights(S2C.FarFlights p) {}

    default void mapPlayers(S2C.MapPlayers p) {}

    default void replayTick(S2C.ReplayTick p) {}

    default void intercept(S2C.Intercept p) {}

    default void radarScope(S2C.RadarScope p) {}

    /** Чанк в LOD Voxy; поток сети. */
    default void voxyChunk(S2C.VoxyChunk p) {}

    ClientHooks NONE = new ClientHooks() {};

    final class Holder {
        static volatile ClientHooks instance = NONE;

        private Holder() {}
    }

    static ClientHooks get() {
        return Holder.instance;
    }

    static void set(ClientHooks hooks) {
        Holder.instance = hooks;
    }
}
