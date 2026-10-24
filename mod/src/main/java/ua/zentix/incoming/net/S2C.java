package ua.zentix.incoming.net;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Sightings;
import ua.zentix.incoming.util.StreamCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Сервер → клиент: только события; всё, что видно и слышно, клиент строит сам (частицы, звук с задержкой, тряска). */
public final class S2C {
    private S2C() {}

    /**
     * Взрыв: вид (0 — шахед, 1 — ракета, 2 — бомба под землёй, 3 — снаряд РСЗО — как взрыв происходит), грунт, высота
     * поверхности над точкой (у бомбы — верх над зарядом, {@code BunkerCover}; у остальных — высота самой точки удара: где
     * лежит поверхность вокруг, клиент видит сам), сид, снаряд, который взорвался (его путь у клиента кончается в этот тик, и мотор звучит,
     * пока до уха не дойдёт фронт взрыва, а не стихает раньше), и оружие ({@code WeaponType.id}): заряд и топливо —
     * в его паспорте, клиент берёт их оттуда ({@code client.fx.BlastSource}); ось боевой части (курс снаряда у удара,
     * единичный вектор): по ней и сиду клиент разлетает те же осколки, что сервер ({@code warhead.FragmentModel}).
     * <p>
     * Формат только растёт с конца (replay.md): оружие и ось дописаны в конец; в повторе, записанном до него, его нет
     * ({@link #NO_WEAPON}) — оно выводится из вида, как вид тогда и выбирали ({@link #weaponType}).
     */
    public record Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile, int weapon, Vec3 axis,
                        boolean fuelApart) implements CustomPacketPayload {
        public static final int DRONE = 0, MISSILE = 1, BUNKER = 2, ROCKET = 3;
        /** Оружия в пакете нет (повтор, записанный до этого поля). */
        public static final int NO_WEAPON = -1;
        public static final Type<Blast> TYPE = new Type<>(Incoming.id("blast"));
        private static final StreamCodec<ByteBuf, Optional<UUID>> PROJECTILE = ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC);
        private static final StreamCodec<ByteBuf, Integer> WEAPON = StreamCodecs.trailing(ByteBufCodecs.VAR_INT, NO_WEAPON);
        /** Ось боевой части — вниз, если её нет в пакете (повтор, записанный до этого поля). */
        public static final Vec3 DOWN = new Vec3(0, -1, 0);
        private static final StreamCodec<ByteBuf, Vec3> AXIS = StreamCodecs.trailing(StreamCodecs.VEC3, DOWN);
        /**
         * Топливо в воронке горит отдельно — огнём на блоках сервера и пакетом {@link FuelFire} ({@code warhead/FuelFires}):
         * его дым и гул клиент ведёт только по нему. В повторе, записанном до этого поля, — нет: дым и гул топлива — у
         * места удара, как тогда (огня на блоках в такой записи нет).
         */
        private static final StreamCodec<ByteBuf, Boolean> FUEL_APART = StreamCodecs.trailing(ByteBufCodecs.BOOL, false);
        public static final StreamCodec<ByteBuf, Blast> CODEC = StreamCodec.of((b, p) -> {
            ByteBufCodecs.VAR_INT.encode(b, p.kind);
            StreamCodecs.VEC3.encode(b, p.pos);
            ByteBufCodecs.VAR_INT.encode(b, p.material);
            ByteBufCodecs.FLOAT.encode(b, p.surfaceY);
            ByteBufCodecs.VAR_LONG.encode(b, p.seed);
            PROJECTILE.encode(b, p.projectile);
            WEAPON.encode(b, p.weapon);
            AXIS.encode(b, p.axis);
            FUEL_APART.encode(b, p.fuelApart);
        }, b -> new Blast(ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                ByteBufCodecs.FLOAT.decode(b), ByteBufCodecs.VAR_LONG.decode(b), PROJECTILE.decode(b), WEAPON.decode(b), AXIS.decode(b),
                FUEL_APART.decode(b)));

        /** Взрыв без оси боевой части (бомба, картинка после ядерки): ось вниз. */
        public Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile, int weapon) {
            this(kind, pos, material, surfaceY, seed, projectile, weapon, DOWN);
        }

        /** Взрыв, чьё топливо (если есть) горит у клиента у места удара: без огня сервера на блоках (картинка без сервера). */
        public Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile, int weapon, Vec3 axis) {
            this(kind, pos, material, surfaceY, seed, projectile, weapon, axis, false);
        }

        /**
         * Оружие, которое взорвалось. Нет в пакете ({@link #NO_WEAPON}) — по виду, как его выбирал сервер до этого поля
         * (шахед и «Ланцет» оба шли видом шахеда).
         */
        public WeaponType weaponType() {
            WeaponType[] all = WeaponType.values();
            if (weapon >= 0 && weapon < all.length) return all[weapon];
            return switch (kind) {
                case MISSILE -> WeaponType.MISSILE;
                case BUNKER -> WeaponType.BUNKER;
                case ROCKET -> WeaponType.ROCKET;
                default -> WeaponType.DRONE;
            };
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Подстанция выбита: хлопок, дуга и искры, затихающий гул трансформатора. */
    public record GridFailure(Vec3 pos, long seed) implements CustomPacketPayload {
        public static final Type<GridFailure> TYPE = new Type<>(Incoming.id("grid_failure"));
        public static final StreamCodec<ByteBuf, GridFailure> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, GridFailure::pos, ByteBufCodecs.VAR_LONG, GridFailure::seed, GridFailure::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Квартал рядом погас (щелчок реле, гул обрывается) или в него вернулся свет ({@code on}). */
    public record GridDistrict(Vec3 pos, boolean on) implements CustomPacketPayload {
        public static final Type<GridDistrict> TYPE = new Type<>(Incoming.id("grid_district"));
        public static final StreamCodec<ByteBuf, GridDistrict> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, GridDistrict::pos, ByteBufCodecs.BOOL, GridDistrict::on, GridDistrict::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Бомба вошла в грунт: звуковой удар, удар о землю, фонтан у входного отверстия. */
    public record BunkerImpact(Vec3 pos, int material, float speed) implements CustomPacketPayload {
        public static final Type<BunkerImpact> TYPE = new Type<>(Incoming.id("bunker_impact"));
        /** Скорость удара в повторе, записанном до этого поля, м/с: прежний предел падения, 12,5 блока/тик. */
        public static final float OLD_SPEED = 250;
        public static final StreamCodec<ByteBuf, BunkerImpact> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, BunkerImpact::pos, ByteBufCodecs.VAR_INT, BunkerImpact::material,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, OLD_SPEED), BunkerImpact::speed, BunkerImpact::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Газы подземного взрыва вырвались из скважины.
     *
     * @param airKg какая доля заряда вышла в воздух, кг ТНТ ({@code warhead.BuriedBlast#airCharge}; в повторе до этого поля —
     *              {@link #OLD_AIR_KG})
     */
    public record Vent(Vec3 pos, int material, float airKg) implements CustomPacketPayload {
        public static final Type<Vent> TYPE = new Type<>(Incoming.id("vent"));
        /** Доля заряда в повторе, записанном до этого поля: 2,4 т через ход 0,8 м из полости 3,4 м, кг ТНТ. */
        public static final float OLD_AIR_KG = 8.5f;
        public static final StreamCodec<ByteBuf, Vent> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Vent::pos, ByteBufCodecs.VAR_INT, Vent::material,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, OLD_AIR_KG), Vent::airKg, Vent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Топливо удара горит на блоках ({@code warhead/FuelFires}): состояние воронки — при появлении, при каждой перемене и
     * тому, кому уходит её чанк. Дым, свет и гул этого пожара клиент ведёт сам по закону топлива оружия на этих клетках
     * ({@code WeaponSpec.pool(lit)}), огонь — блоки мира.
     *
     * @param id        воронка (в своём мире)
     * @param cells     клетки огня в порядке выгорания (первая горит дольше всех: туда топливо стекло) — нынешние места
     * @param smothered клетки (биты по номеру), чьё топливо кончилось раньше срока: засыпало, залило, упало в воду
     * @param weapon    оружие ({@code WeaponType.id})
     * @param blastTick игровое время удара: закон горения считает время от него
     */
    public record FuelFire(int id, List<BlockPos> cells, long smothered, int weapon, long blastTick) implements CustomPacketPayload {
        public static final Type<FuelFire> TYPE = new Type<>(Incoming.id("fuel_fire"));
        /** Клеток в пакете не больше: биты {@link #smothered} — одно long (у ракеты — 9). */
        public static final int MAX_CELLS = 64;
        public static final StreamCodec<ByteBuf, FuelFire> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FuelFire::id, BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CELLS)), FuelFire::cells,
                ByteBufCodecs.VAR_LONG, FuelFire::smothered, ByteBufCodecs.VAR_INT, FuelFire::weapon, ByteBufCodecs.VAR_LONG,
                FuelFire::blastTick, FuelFire::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Свод над полостью обрушился: провал на поверхности.
     *
     * @param volume сколько грунта ушло в провал, м³ (объём полости; в повторе до этого поля — {@link #OLD_VOLUME})
     */
    public record Collapse(Vec3 pos, int material, float volume) implements CustomPacketPayload {
        public static final Type<Collapse> TYPE = new Type<>(Incoming.id("collapse"));
        /** Объём в повторе, записанном до этого поля: полость 2,4 т (радиус 3,4 м), м³. */
        public static final float OLD_VOLUME = 160;
        public static final StreamCodec<ByteBuf, Collapse> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Collapse::pos, ByteBufCodecs.VAR_INT, Collapse::material,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, OLD_VOLUME), Collapse::volume, Collapse::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Сейсмическая волна (в породе быстрее звука): толчки прямо сейчас, сила — длительность в тиках. */
    public record Quake(int ticks, boolean rumble) implements CustomPacketPayload {
        public static final Type<Quake> TYPE = new Type<>(Incoming.id("quake"));
        public static final StreamCodec<ByteBuf, Quake> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Quake::ticks, ByteBufCodecs.BOOL, Quake::rumble, Quake::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Воздушная тревога у цели: 0 — воздушная, 1 — ракетная опасность. */
    public record Siren(Vec3 pos, int kind) implements CustomPacketPayload {
        public static final int AIR_RAID = 0, MISSILE = 1;
        public static final Type<Siren> TYPE = new Type<>(Incoming.id("siren"));
        public static final StreamCodec<ByteBuf, Siren> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Siren::pos, ByteBufCodecs.VAR_INT, Siren::kind, Siren::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ход залпа у того, кто его запустил. */
    public record SalvoStatus(int weapon, int fired, int total) implements CustomPacketPayload {
        public static final Type<SalvoStatus> TYPE = new Type<>(Incoming.id("salvo_status"));
        public static final StreamCodec<ByteBuf, SalvoStatus> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SalvoStatus::weapon, ByteBufCodecs.VAR_INT, SalvoStatus::fired, ByteBufCodecs.VAR_INT, SalvoStatus::total, SalvoStatus::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Свои снаряды в полёте — тому, кто их пустил, раз в 5 тиков (и пустой список, когда все долетели): для HUD
     * с временем до удара, меток на экране и камеры снаряда. Сервер знает и тех, что летят вне загруженного мира. В конце —
     * сетевые номера сущностей цели, по снаряду ({@link #target}).
     */
    public record Flights(List<Flight> flights, List<Integer> targets) implements CustomPacketPayload {
        public static final Type<Flights> TYPE = new Type<>(Incoming.id("flights"));
        /** Сущности цели нет (цель — точка, аппарат или сущность, которой сервер не нашёл). */
        public static final int NO_ENTITY = -1;
        public static final StreamCodec<ByteBuf, Flights> CODEC = StreamCodec.composite(Flight.CODEC.apply(ByteBufCodecs.list()), Flights::flights,
                StreamCodecs.trailing(ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), List.of()), Flights::targets, Flights::new);

        public Flights(List<Flight> flights) {
            this(flights, List.of());
        }

        /**
         * Сетевой номер сущности цели снаряда i ({@link #NO_ENTITY} — нет): по нему клиент ставит метку туда, где рисует
         * цель, а не в точку, что сервер шлёт раз в 5 тиков. Список в конце пакета (формат 2): у записи повтора прежней
         * сборки его нет.
         */
        public int target(int i) {
            return i < targets.size() ? targets.get(i) : NO_ENTITY;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Один снаряд: UUID (тот же после возвращения в мир), оружие, фаза полёта, тиков до удара, где он и куда летит,
     * ядерная ли БЧ.
     */
    public record Flight(UUID id, int weapon, int phase, int eta, Vec3 pos, Vec3 target, boolean nuclear,
                         int targetKind, String targetName, boolean targetLost) {
        /** Цель — точка (подписи нет). */
        public static final int TARGET_POINT = 0;
        /** Игрок или сущность с именем: {@code targetName} — само имя. */
        public static final int TARGET_NAMED = 1;
        /** Сущность без имени: {@code targetName} — ключ перевода её типа. */
        public static final int TARGET_TYPE = 2;
        /** Аппарат Sable: {@code targetName} — его имя или пусто. */
        public static final int TARGET_AIRCRAFT = 3;
        /** Длина {@code targetName}, дальше сервер обрезает. */
        public static final int MAX_NAME = 64;
        private static final StreamCodec<ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(MAX_NAME);

        public static final StreamCodec<ByteBuf, Flight> CODEC = new StreamCodec<>() {
            @Override
            public Flight decode(ByteBuf b) {
                return new Flight(UUIDUtil.STREAM_CODEC.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), StreamCodecs.VEC3.decode(b), b.readBoolean(),
                        ByteBufCodecs.VAR_INT.decode(b), NAME.decode(b), b.readBoolean());
            }

            @Override
            public void encode(ByteBuf b, Flight f) {
                UUIDUtil.STREAM_CODEC.encode(b, f.id);
                ByteBufCodecs.VAR_INT.encode(b, f.weapon);
                ByteBufCodecs.VAR_INT.encode(b, f.phase);
                ByteBufCodecs.VAR_INT.encode(b, f.eta);
                StreamCodecs.VEC3.encode(b, f.pos);
                StreamCodecs.VEC3.encode(b, f.target);
                b.writeBoolean(f.nuclear);
                ByteBufCodecs.VAR_INT.encode(b, f.targetKind);
                NAME.encode(b, f.targetName);
                b.writeBoolean(f.targetLost);
            }
        };
    }

    /**
     * Снаряды, которых у клиента нет (дальше, чем ему выдаёт сущности ваниль, или в полёте вне мира), но которые он
     * уже может слышать ({@link ua.zentix.incoming.strike.Hearing}) или видеть ({@code visibility.far_range}): по ним
     * клиент ведёт тот же звук с задержкой и Доплером, что и по сущности, и рисует снаряд вдали. Слышимые — раз
     * в {@link #PERIOD} тика (звук тянется по прямой между пакетами), только видимые — раз в {@link #FAR_PERIOD}: вдали
     * снаряд за тик сдвигается на экране на доли пикселя, а залп в 40 снарядов — это 40 записей в каждом пакете.
     */
    public record FarFlights(List<FarFlight> flights) implements CustomPacketPayload {
        public static final int PERIOD = 2, FAR_PERIOD = 4;
        public static final Type<FarFlights> TYPE = new Type<>(Incoming.id("far_flights"));
        public static final StreamCodec<ByteBuf, FarFlights> CODEC = FarFlight.CODEC.apply(ByteBufCodecs.list()).map(FarFlights::new, FarFlights::flights);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Один снаряд вдали: UUID (тот же у сущности, когда она появится у клиента), оружие, B-2 ли это (у бомбы то же
     * оружие), бурит ли бомба, слышно ли его (тогда пакеты чаще), где он, сдвиг за последний тик
     * ({@code StrikeProjectile.velocity}; float — точнее миллиметра), куда смотрит нос и крен (модель вдали кренится
     * в развороте, как вблизи), фаза полёта и сколько она идёт,
     * куда он летит (точка цели); на старте — ещё и высота земли под ним (первый воздух) по чанку сервера
     * ({@code ground}; NaN — не на старте или чанк не готов): у клиента вдали чанка нет, а рельеф мода LOD бывает не у
     * всех — облако старта без земли не встанет.
     */
    public record FarFlight(UUID id, int weapon, boolean bomber, boolean drilling, boolean audible, Vec3 pos, Vec3 velocity, float yaw,
                            float pitch, float roll, int phase, int phaseAge, Vec3 aim, float ground, boolean ended) {
        /** Ракета ЗРК: это вид полёта, не новый вид оружия. */
        public static final int INTERCEPTOR = -1;
        private static final int BOMBER = 1, DRILLING = 2, AUDIBLE = 4, GROUND = 8, ENDED = 16;

        public FarFlight(UUID id, int weapon, boolean bomber, boolean drilling, boolean audible, Vec3 pos, Vec3 velocity, float yaw,
                         float pitch, float roll, int phase, int phaseAge, Vec3 aim, float ground) {
            this(id, weapon, bomber, drilling, audible, pos, velocity, yaw, pitch, roll, phase, phaseAge, aim, ground, false);
        }

        public boolean interceptor() {
            return weapon == INTERCEPTOR;
        }

        /** Без земли под снарядом. */
        public FarFlight(UUID id, int weapon, boolean bomber, boolean drilling, boolean audible, Vec3 pos, Vec3 velocity, float yaw,
                         float pitch, float roll, int phase, int phaseAge, Vec3 aim) {
            this(id, weapon, bomber, drilling, audible, pos, velocity, yaw, pitch, roll, phase, phaseAge, aim, Float.NaN);
        }

        public static final StreamCodec<ByteBuf, FarFlight> CODEC = new StreamCodec<>() {
            @Override
            public FarFlight decode(ByteBuf b) {
                UUID id = UUIDUtil.STREAM_CODEC.decode(b);
                int weapon = ByteBufCodecs.VAR_INT.decode(b);
                int flags = b.readByte();
                Vec3 pos = StreamCodecs.VEC3.decode(b);
                Vec3 velocity = new Vec3(b.readFloat(), b.readFloat(), b.readFloat());
                float yaw = b.readFloat(), pitch = b.readFloat(), roll = b.readFloat();
                int phase = ByteBufCodecs.VAR_INT.decode(b), phaseAge = ByteBufCodecs.VAR_INT.decode(b);
                Vec3 aim = StreamCodecs.VEC3.decode(b);
                float ground = (flags & GROUND) != 0 ? b.readFloat() : Float.NaN;
                return new FarFlight(id, weapon, (flags & BOMBER) != 0, (flags & DRILLING) != 0, (flags & AUDIBLE) != 0, pos, velocity,
                        yaw, pitch, roll, phase, phaseAge, aim, ground, (flags & ENDED) != 0);
            }

            @Override
            public void encode(ByteBuf b, FarFlight f) {
                UUIDUtil.STREAM_CODEC.encode(b, f.id);
                ByteBufCodecs.VAR_INT.encode(b, f.weapon);
                boolean ground = Float.isFinite(f.ground);
                b.writeByte((f.bomber ? BOMBER : 0) | (f.drilling ? DRILLING : 0) | (f.audible ? AUDIBLE : 0) | (ground ? GROUND : 0) | (f.ended ? ENDED : 0));
                StreamCodecs.VEC3.encode(b, f.pos);
                b.writeFloat((float) f.velocity.x);
                b.writeFloat((float) f.velocity.y);
                b.writeFloat((float) f.velocity.z);
                b.writeFloat(f.yaw);
                b.writeFloat(f.pitch);
                b.writeFloat(f.roll);
                ByteBufCodecs.VAR_INT.encode(b, f.phase);
                ByteBufCodecs.VAR_INT.encode(b, f.phaseAge);
                StreamCodecs.VEC3.encode(b, f.aim);
                if (ground) b.writeFloat(f.ground);
            }
        };

        /** Через сколько тиков придёт следующий пакет об этом снаряде. */
        public int period() {
            return audible ? FarFlights.PERIOD : FarFlights.FAR_PERIOD;
        }
    }

    /**
     * Метки для карты наведения и целей пульта — ответ на {@link C2S.MapPlayers}: свои игроки и замеченное стороной
     * спросившего в его измерении и не дальше {@code map_range} (кто попадает в список — {@code ServerActions.mapPlayers});
     * {@code shown} — карта их рисует ({@code map_players}), иначе они только цели пульта.
     */
    public record MapPlayers(List<MapPlayer> players, boolean shown) implements CustomPacketPayload {
        public static final Type<MapPlayers> TYPE = new Type<>(Incoming.id("map_players"));
        public static final StreamCodec<ByteBuf, MapPlayers> CODEC = StreamCodec.composite(
                MapPlayer.CODEC.apply(ByteBufCodecs.list()), MapPlayers::players, ByteBufCodecs.BOOL, MapPlayers::shown, MapPlayers::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Метка на карте: UUID, имя (у игрока — ник, им же пульт целится), что это, где оно (у чужого — где его видели
     * последний раз), свой ли по команде и сколько секунд назад видели (0 — видят сейчас).
     */
    public record MapPlayer(UUID id, String name, double x, double z, Sightings.Kind kind, boolean friendly, int age) {
        /** Имя игрока в Minecraft — до 16 знаков; запас на имена модов. */
        public static final int MAX_NAME = 64;
        private static final StreamCodec<ByteBuf, Sightings.Kind> KIND = ByteBufCodecs.idMapper(
                i -> Sightings.Kind.values()[Math.floorMod(i, Sightings.Kind.values().length)], Sightings.Kind::ordinal);
        public static final StreamCodec<ByteBuf, MapPlayer> CODEC = NeoForgeStreamCodecs.composite(
                UUIDUtil.STREAM_CODEC, MapPlayer::id, ByteBufCodecs.stringUtf8(MAX_NAME), MapPlayer::name,
                ByteBufCodecs.DOUBLE, MapPlayer::x, ByteBufCodecs.DOUBLE, MapPlayer::z, KIND, MapPlayer::kind,
                ByteBufCodecs.BOOL, MapPlayer::friendly, ByteBufCodecs.VAR_INT, MapPlayer::age, MapPlayer::new);
    }

    /** Открыть экран пульта (команда /incoming menu). */
    public record OpenRemote() implements CustomPacketPayload {
        public static final Type<OpenRemote> TYPE = new Type<>(Incoming.id("open_remote"));
        public static final StreamCodec<ByteBuf, OpenRemote> CODEC = StreamCodec.unit(new OpenRemote());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Отбой: заглушить моторы и камеру снарядов, которых больше нет ({@code projectiles}). {@code owner} — свой отбой этого
     * игрока (у него гаснет строка залпа, у остальных тревога остаётся: чужие удары летят дальше); пусто — отбой всего,
     * гаснет и тревога. {@code nuclear} — отменены и ядерные удары (иначе ядерные снаряды летят дальше, их звук и камера
     * остаются).
     */
    public record Cleared(Optional<UUID> owner, boolean nuclear, List<UUID> projectiles) implements CustomPacketPayload {
        public static final Type<Cleared> TYPE = new Type<>(Incoming.id("cleared"));
        public static final StreamCodec<ByteBuf, Cleared> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), Cleared::owner, ByteBufCodecs.BOOL, Cleared::nuclear,
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Cleared::projectiles, Cleared::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Запущена МБР: след разгона и вход боеголовки — клиенту для картинки.
     *
     * @param alarm игрок в радиусе тревоги (сирена гражданской обороны и отсчёт)
     * @param mine  запустил этот игрок (отсчёт до подрыва)
     * @param scale масштаб ядерных эффектов мира (effects_scale): высота подрыва и путь боеголовки в блоках
     */
    public record NukeWarning(int strikeId, Vec3 target, Vec3 launchPos, long launchTime, long detonateTime, double yieldKt,
                              boolean airBurst, boolean alarm, boolean mine, float scale) implements CustomPacketPayload {
        /** Высота подрыва над целью, блоки. */
        public double burstHeight() {
            return airBurst ? Yield.optimalBurstHeight(yieldKt) * scale : 0;
        }

        public static final Type<NukeWarning> TYPE = new Type<>(Incoming.id("nuke_warning"));
        public static final StreamCodec<ByteBuf, NukeWarning> CODEC = new StreamCodec<>() {
            @Override
            public NukeWarning decode(ByteBuf b) {
                return new NukeWarning(ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), StreamCodecs.VEC3.decode(b), b.readLong(), b.readLong(), b.readDouble(), b.readBoolean(),
                        b.readBoolean(), b.readBoolean(), b.readFloat());
            }

            @Override
            public void encode(ByteBuf b, NukeWarning w) {
                ByteBufCodecs.VAR_INT.encode(b, w.strikeId);
                StreamCodecs.VEC3.encode(b, w.target);
                StreamCodecs.VEC3.encode(b, w.launchPos);
                b.writeLong(w.launchTime);
                b.writeLong(w.detonateTime);
                b.writeDouble(w.yieldKt);
                b.writeBoolean(w.airBurst);
                b.writeBoolean(w.alarm);
                b.writeBoolean(w.mine);
                b.writeFloat(w.scale);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Ядерный подрыв: всё остальное (вспышку, шар, гриб, волну, звук, дождь) клиент считает сам по модели. Кроме
     * настроек сервера: {@code blackout} — радиус, в котором удар гасит сеть ({@code Blackouts.nukeRadius}; 0 — не
     * гасит), там лампы коротят дугой ЭМИ.
     * <p>
     * Формат только растёт с конца (replay.md): поля выпуска 3.1.0, потом {@code wave}, {@code blackout}, время суток
     * подрыва ({@link Detonation#dayTime}) и пожары ({@link Detonation#fires}) — каждое дописано в конец пакета и в
     * повторе, записанном до него, читается «как до него»: скорость стены {@link Detonation#OLD_WAVE}, без ЭМИ, время
     * суток неизвестно, пожары есть.
     */
    public record NukeDetonation(Detonation detonation, float blackout) implements CustomPacketPayload {
        public static final Type<NukeDetonation> TYPE = new Type<>(Incoming.id("nuke_detonation"));
        public static final StreamCodec<ByteBuf, NukeDetonation> CODEC = StreamCodec.composite(
                Detonation.STREAM_CODEC, NukeDetonation::detonation,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, Detonation.OLD_WAVE), p -> p.detonation.wave(),
                StreamCodecs.trailing(ByteBufCodecs.FLOAT, 0f), NukeDetonation::blackout,
                StreamCodecs.trailing(ByteBufCodecs.VAR_LONG, Detonation.NO_DAY_TIME), p -> p.detonation.dayTime(),
                StreamCodecs.trailing(ByteBufCodecs.BOOL, true), p -> p.detonation.fires(),
                (d, wave, blackout, dayTime, fires) -> new NukeDetonation(d.withWave(wave).withDayTime(dayTime).withFires(fires), blackout));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Блоки, которые снесла ядерная волна в чанке (несколько мест из руин чанка, {@code RuinPlan#thrown}), — тем, кто видит
     * чанк: клиент бросает их куски, когда до места доходит фронт, и слышит стёкла и обломки.
     *
     * @param removed сколько мест чанка волна снесла целиком (места {@code pieces} — выборка из них по хешу места); в повторе
     *                до этого поля — {@link #OLD_REMOVED}
     */
    public record NukeDebris(int detonation, List<Piece> pieces, int removed) implements CustomPacketPayload {
        public static final Type<NukeDebris> TYPE = new Type<>(Incoming.id("nuke_debris"));
        /** Повтор, записанный до поля: снесённых мест не знаем — столько, сколько в выборке. */
        public static final int OLD_REMOVED = 0;

        /** Сколько снесённых мест чанка стоит за каждым местом выборки (1 — выборка и есть все). */
        public double weight() {
            return pieces.isEmpty() ? 0 : Math.max(1.0, (double) removed / pieces.size());
        }

        /** Место и блок, который там был. */
        public record Piece(BlockPos pos, BlockState state) {
            static final StreamCodec<ByteBuf, Piece> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, Piece::pos,
                    ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), Piece::state, Piece::new);
        }

        public static final StreamCodec<ByteBuf, NukeDebris> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, NukeDebris::detonation,
                Piece.CODEC.apply(ByteBufCodecs.list(64)), NukeDebris::pieces,
                StreamCodecs.trailing(ByteBufCodecs.VAR_INT, OLD_REMOVED), NukeDebris::removed, NukeDebris::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Следы вспышки на гранях чанка ({@link ua.zentix.incoming.nuclear.flash.FlashMarks}): все, что есть у чанка, — когда
     * чанк уходит игроку и когда они меняются (руины, силуэты). Пустые — у чанка их больше нет.
     */
    public record NukeFlashMarks(long chunk, ua.zentix.incoming.nuclear.flash.FlashMarks marks) implements CustomPacketPayload {
        public static final Type<NukeFlashMarks> TYPE = new Type<>(Incoming.id("nuke_flash_marks"));
        public static final StreamCodec<ByteBuf, NukeFlashMarks> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, NukeFlashMarks::chunk,
                ua.zentix.incoming.nuclear.flash.FlashMarks.STREAM_CODEC, NukeFlashMarks::marks, NukeFlashMarks::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Вход в мир и смена измерения: действующие подрывы и летящие МБР этого измерения. Подрывы в списке — полями выпуска
     * 3.1.0 ({@link Detonation#STREAM_CODEC}); что подрыв знает сверх них (скорость стены {@link Detonation#wave}, время
     * суток {@link Detonation#dayTime}, пожары {@link Detonation#fires}), — списками в конце пакета, по подрыву на место
     * (формат растёт с конца, replay.md): в повторе, записанном до них, списков нет — «как до них».
     */
    public record NukeSync(List<Detonation> detonations, List<NukeWarning> warnings)
            implements CustomPacketPayload {
        public static final Type<NukeSync> TYPE = new Type<>(Incoming.id("nuke_sync"));
        public static final StreamCodec<ByteBuf, NukeSync> CODEC = StreamCodec.composite(
                Detonation.STREAM_CODEC.apply(ByteBufCodecs.list()), NukeSync::detonations,
                NukeWarning.CODEC.apply(ByteBufCodecs.list()), NukeSync::warnings,
                StreamCodecs.trailing(ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::wave).toList(),
                StreamCodecs.trailing(ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::dayTime).toList(),
                StreamCodecs.trailing(ByteBufCodecs.BOOL.apply(ByteBufCodecs.list()), List.of()),
                p -> p.detonations.stream().map(Detonation::fires).toList(),
                (detonations, warnings, waves, dayTimes, fires) -> new NukeSync(extended(detonations, waves, dayTimes, fires), warnings));

        /** Подрывы с полями из списков в конце пакета (пустой список — поля в повторе ещё не было). */
        private static List<Detonation> extended(List<Detonation> detonations, List<Float> waves, List<Long> dayTimes,
                List<Boolean> fires) {
            check("waves", waves, detonations);
            check("day times", dayTimes, detonations);
            check("fires", fires, detonations);
            List<Detonation> out = new ArrayList<>(detonations.size());
            for (int i = 0; i < detonations.size(); i++) {
                Detonation d = detonations.get(i);
                if (!waves.isEmpty()) d = d.withWave(waves.get(i));
                if (!dayTimes.isEmpty()) d = d.withDayTime(dayTimes.get(i));
                if (!fires.isEmpty()) d = d.withFires(fires.get(i));
                out.add(d);
            }
            return out;
        }

        private static void check(String what, List<?> values, List<Detonation> detonations) {
            if (!values.isEmpty() && values.size() != detonations.size()) {
                throw new DecoderException("nuke sync: " + values.size() + " " + what + " for " + detonations.size() + " detonations");
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Облучение игрока: доза (Гр), мощность дозы (Р/ч, для счётчика Гейгера), заражение, стадия болезни. */
    public record Radiation(float doseGy, float rate, float contamination, int stage) implements CustomPacketPayload {
        public static final Type<Radiation> TYPE = new Type<>(Incoming.id("radiation"));
        public static final StreamCodec<ByteBuf, Radiation> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Radiation::doseGy, ByteBufCodecs.FLOAT, Radiation::rate,
                ByteBufCodecs.FLOAT, Radiation::contamination, ByteBufCodecs.VAR_INT, Radiation::stage, Radiation::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ожоги сетчатки игрока — все пятна целиком ({@link ua.zentix.incoming.nuclear.radiation.FlashEyes}). */
    public record RetinaScars(ua.zentix.incoming.nuclear.radiation.RetinaScars scars) implements CustomPacketPayload {
        public static final Type<RetinaScars> TYPE = new Type<>(Incoming.id("retina_scars"));
        public static final StreamCodec<ByteBuf, RetinaScars> CODEC = ua.zentix.incoming.nuclear.radiation.RetinaScars.STREAM_CODEC
                .map(RetinaScars::new, RetinaScars::scars);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Конец тика сервера повтора Flashback ({@link ua.zentix.incoming.compat.FlashbackReplay}): место в записи и идёт ли
     * перемотка. Пакеты, пришедшие до метки, прочитаны в этом тике; только повтор, в игре её нет.
     */
    public record ReplayTick(int place, boolean seeking) implements CustomPacketPayload {
        public static final Type<ReplayTick> TYPE = new Type<>(Incoming.id("replay_tick"));
        public static final StreamCodec<ByteBuf, ReplayTick> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ReplayTick::place, ByteBufCodecs.BOOL, ReplayTick::seeking, ReplayTick::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Зенитная ракета разорвалась в воздухе ({@code defense.Interceptor}): у цели или сама (самоликвидация, земля).
     * kill — цель сбита: её путь у клиента кончается в этот тик ({@code target}), обломки летят сущностями. Наземного
     * взрыва нет — только вспышка, облачко и хлопок; сид — вариант звука.
     */
    public record Intercept(Vec3 pos, boolean kill, Optional<UUID> target, long seed) implements CustomPacketPayload {
        public static final Type<Intercept> TYPE = new Type<>(Incoming.id("intercept"));
        public static final StreamCodec<ByteBuf, Intercept> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Intercept::pos, ByteBufCodecs.BOOL, Intercept::kill, ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Intercept::target,
                ByteBufCodecs.VAR_LONG, Intercept::seed, Intercept::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Экран радара ЗРК своим рядом с ним ({@code defense.SamBlockEntity}, раз в осмотр): где антенна, дальности радара
     * и огня, ракеты на направляющих и в запасе, цели. Перестал приходить — экран гаснет.
     */
    public record RadarScope(Vec3 radar, int range, int engageRange, int ready, int stock, List<Blip> blips) implements CustomPacketPayload {
        /** Больше целей экран не покажет (ближние — первыми). */
        public static final int MAX_BLIPS = 64;
        public static final Type<RadarScope> TYPE = new Type<>(Incoming.id("radar_scope"));
        public static final StreamCodec<ByteBuf, RadarScope> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, RadarScope::radar, ByteBufCodecs.VAR_INT, RadarScope::range, ByteBufCodecs.VAR_INT, RadarScope::engageRange,
                ByteBufCodecs.VAR_INT, RadarScope::ready, ByteBufCodecs.VAR_INT, RadarScope::stock,
                Blip.CODEC.apply(ByteBufCodecs.list(MAX_BLIPS)), RadarScope::blips, RadarScope::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Цель на экране радара: сдвиг от антенны по x и z, блоков, курс (градусы, как у сущности), вид оружия
     * ({@link ua.zentix.incoming.strike.WeaponType#id()}: крупный корпус — крупнее отметка) и признаки {@link #HOSTILE},
     * {@link #ENGAGEABLE}, {@link #ENGAGED}.
     */
    public record Blip(float dx, float dz, float yaw, int weapon, int flags) {
        /** Чужая. */
        public static final int HOSTILE = 1;
        /** В дальности огня и стоит ракеты. */
        public static final int ENGAGEABLE = 2;
        /** За ней идёт ракета. */
        public static final int ENGAGED = 4;
        public static final StreamCodec<ByteBuf, Blip> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Blip::dx, ByteBufCodecs.FLOAT, Blip::dz, ByteBufCodecs.FLOAT, Blip::yaw,
                ByteBufCodecs.VAR_INT, Blip::weapon, ByteBufCodecs.VAR_INT, Blip::flags, Blip::new);

        public boolean is(int flag) {
            return (flags & flag) != 0;
        }
    }

    /**
     * Чанк, которого у клиента нет, — в LOD Voxy ({@code compat/VoxyLods}, только игрокам, у кого Voxy): руины, воронки,
     * след подрыва и лампы квартала вдали. {@code sections} — секции со светом ({@code compat/LodSections}); измерение —
     * чьё это: после смены измерения пакет старого не принимается.
     */
    public record VoxyChunk(net.minecraft.resources.ResourceLocation dimension, int x, int z, byte[] sections) implements CustomPacketPayload {
        /** Больше чанк не бывает: 24 секции глобальной палитрой и два слоя света — ~250 КБ. */
        static final int MAX_BYTES = 1 << 20;
        public static final Type<VoxyChunk> TYPE = new Type<>(Incoming.id("voxy_chunk"));
        public static final StreamCodec<ByteBuf, VoxyChunk> CODEC = StreamCodec.composite(
                net.minecraft.resources.ResourceLocation.STREAM_CODEC, VoxyChunk::dimension, ByteBufCodecs.VAR_INT, VoxyChunk::x,
                ByteBufCodecs.VAR_INT, VoxyChunk::z, ByteBufCodecs.byteArray(MAX_BYTES), VoxyChunk::sections, VoxyChunk::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VoxyChunk v && v.x == x && v.z == z && v.dimension.equals(dimension) && java.util.Arrays.equals(v.sections, sections);
        }

        @Override
        public int hashCode() {
            return (dimension.hashCode() * 31 + x) * 31 + z;
        }

        @Override
        public String toString() {
            return "VoxyChunk[" + dimension + " " + x + " " + z + ", " + sections.length + " bytes]";
        }
    }
}
