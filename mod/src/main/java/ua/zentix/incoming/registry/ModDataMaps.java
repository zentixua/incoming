package ua.zentix.incoming.registry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.Penetration;

import java.util.Locale;
import java.util.Optional;

/**
 * Карты данных блоков ({@code data/<мод>/data_maps/block/…}): числа законов, которые моды и наборы данных добавляют
 * своим блокам без кода.
 */
public final class ModDataMaps {
    /**
     * Из чего блок для законов, которые его ломают телом и зарядом ({@code data/incoming/data_maps/block/penetration.json}):
     * вид среды и проницаемость S по Янгу (у жидкости — плотность, кг/м³) — пробитие бомбой; группа материала FM 5-250 —
     * пробой заряда ({@link BreachModel}). Ключи — блоки и теги; поздняя запись в файле перекрывает раннюю.
     */
    public static final DataMapType<Block, Penetrability> PENETRATION = DataMapType.builder(Incoming.id("penetration"), Registries.BLOCK,
            Penetrability.CODEC).build();

    private ModDataMaps() {}

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(PENETRATION);
    }

    /**
     * Среда блока для пробития ({@link Penetration}).
     *
     * @param kind   вид среды
     * @param s      проницаемость S по Янгу (у жидкости — плотность, кг/м³; у непробиваемого — 0)
     * @param breach группа материала для пробоя зарядом (табл. B-3 FM 5-250); нет — по виду среды ({@link #breaching})
     */
    public record Penetrability(Penetration.Kind kind, double s, Optional<BreachModel.Material> breach) {
        private static final Codec<Penetration.Kind> KIND = Codec.STRING.xmap(n -> Penetration.Kind.valueOf(n.toUpperCase(Locale.ROOT)),
                k -> k.name().toLowerCase(Locale.ROOT));
        private static final Codec<BreachModel.Material> BREACH = Codec.STRING.xmap(n -> BreachModel.Material.valueOf(n.toUpperCase(Locale.ROOT)),
                m -> m.name().toLowerCase(Locale.ROOT));
        public static final Codec<Penetrability> CODEC = RecordCodecBuilder.create(i -> i.group(
                KIND.fieldOf("kind").forGetter(Penetrability::kind),
                Codec.DOUBLE.optionalFieldOf("s", 0.0).forGetter(Penetrability::s),
                BREACH.optionalFieldOf("breach").forGetter(Penetrability::breach)).apply(i, Penetrability::new));
        /**
         * Твёрдый блок без записи — как кирпич, S = 1 (оценка: прочее твёрдое — металл, стекло, блоки модов — Янгом не
         * описано); вид — скала: за ним грунт не твёрже, как за бетонной плитой. Группу для пробоя зарядом у блока без
         * записи выбирает пробой сам ({@code SlabBreach.material}).
         */
        public static final Penetrability OTHER = new Penetrability(Penetration.Kind.ROCK, 1.0, Optional.empty());

        /** Жидкость без записи (мода): плотность воды, кг/м³. */
        public static final Penetrability WATER = new Penetrability(Penetration.Kind.FLUID, 1000, Optional.empty());

        /**
         * Группа материала для пробоя зарядом: записанная или по виду среды — грунт — «earth», скала — «rock», бетон и
         * кладка — «rock» (обычная кладка, бетон без арматуры: «good masonry, concrete block»); у жидкости и
         * непробиваемого — null (жидкость — пустота за плитой, непробиваемое заряд не смещает).
         */
        @org.jetbrains.annotations.Nullable
        public BreachModel.Material breaching() {
            return switch (kind) {
                case FLUID, STOP -> null;
                default -> breach.orElse(kind == Penetration.Kind.SOIL ? BreachModel.Material.EARTH : BreachModel.Material.ROCK);
            };
        }

        /** Запись блока или {@link #OTHER}. */
        public static Penetrability of(BlockState state) {
            Penetrability p = find(state);
            return p != null ? p : OTHER;
        }

        /** Запись блока; null — её нет. */
        @org.jetbrains.annotations.Nullable
        public static Penetrability find(BlockState state) {
            return state.getBlockHolder().getData(PENETRATION);
        }
    }
}
