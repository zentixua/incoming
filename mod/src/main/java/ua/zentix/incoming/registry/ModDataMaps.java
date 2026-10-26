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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Block data maps ({@code data/<mod>/data_maps/block/…}): law parameters mods and datapacks add to their blocks without code.
 */
public final class ModDataMaps {
    /**
     * Block material for body/charge destruction laws ({@code data/incoming/data_maps/block/penetration.json}): medium kind
     * and Young penetrability S (liquids — density, kg/m³), for bomb penetration; FM 5-250 material group for charge breaching
     * ({@link BreachModel}). Keys are blocks and tags; later file entries override earlier ones.
     */
    public static final DataMapType<Block, Penetrability> PENETRATION = DataMapType.builder(Incoming.id("penetration"), Registries.BLOCK,
            Penetrability.CODEC).build();

    /** Ordered debris slots per block: repeated slots preserve piece counts and random-number order. */
    public static final DataMapType<Block, List<Integer>> DEBRIS_DRONE = debris("debris_drone");
    public static final DataMapType<Block, List<Integer>> DEBRIS_MISSILE = debris("debris_missile");
    public static final DataMapType<Block, List<Integer>> DEBRIS_EMBERS = debris("debris_embers");
    public static final DataMapType<Block, List<Integer>> DEBRIS_ASH = debris("debris_ash");

    private static DataMapType<Block, List<Integer>> debris(String name) {
        return DataMapType.builder(Incoming.id(name), Registries.BLOCK, Codec.intRange(0, Integer.MAX_VALUE).listOf()).build();
    }

    private ModDataMaps() {}

    public static void register(RegisterDataMapTypesEvent event) {
        event.register(PENETRATION);
        event.register(DEBRIS_DRONE);
        event.register(DEBRIS_MISSILE);
        event.register(DEBRIS_EMBERS);
        event.register(DEBRIS_ASH);
    }

    /**
     * Block penetration medium ({@link Penetration}).
     *
     * @param kind   medium kind
     * @param s      Young penetrability S (liquid — density, kg/m³; impenetrable — 0)
     * @param breach charge-breaching material group (FM 5-250 table B-3); absent — from medium kind ({@link #breaching})
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
         * Solid block without entry — brick-like, S = 1 (estimate: Young does not describe other solids — metal, glass, modded
         * blocks); kind is rock: soil behind it is no stronger, unlike behind a concrete slab. For a block without entry,
         * breaching chooses its own material group ({@code SlabBreach.material}).
         */
        public static final Penetrability OTHER = new Penetrability(Penetration.Kind.ROCK, 1.0, Optional.empty());

        /** Liquid without an entry (modded): water density, kg/m³. */
        public static final Penetrability WATER = new Penetrability(Penetration.Kind.FLUID, 1000, Optional.empty());

        /**
         * Charge-breaching material group: explicit or from medium kind — soil “earth”, rock “rock”, concrete and masonry “rock”
         * (ordinary masonry, unreinforced concrete: “good masonry, concrete block”); liquid and impenetrable — null
         * (liquid is void behind a slab; charge cannot displace impenetrable material).
         */
        @org.jetbrains.annotations.Nullable
        public BreachModel.Material breaching() {
            return switch (kind) {
                case FLUID, STOP -> null;
                default -> breach.orElse(kind == Penetration.Kind.SOIL ? BreachModel.Material.EARTH : BreachModel.Material.ROCK);
            };
        }

        /** Block entry or {@link #OTHER}. */
        public static Penetrability of(BlockState state) {
            Penetrability p = find(state);
            return p != null ? p : OTHER;
        }

        /** Block entry; null — absent. */
        @org.jetbrains.annotations.Nullable
        public static Penetrability find(BlockState state) {
            return state.getBlockHolder().getData(PENETRATION);
        }
    }
}
