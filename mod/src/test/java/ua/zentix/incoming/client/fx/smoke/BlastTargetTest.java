package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Семейство взрыва по цели и вещество цели ({@link BlastTarget}): по материалу и опоре, а не по имени «крыша». */
class BlastTargetTest {
    private static final double SHAHED = 50, ROCKET = 6.4, MISSILE = 150;

    /** Вода — вода; выше, чем заряд роет воронку, — воздух; плита над пустотой — плита; на опоре — по связности. */
    @Test
    void familyFollowsMaterialAndSupport() {
        assertEquals(BlastTarget.Family.WATER, BlastTarget.family(GroundMaterial.WATER, false, 0, true));
        assertEquals(BlastTarget.Family.AIR, BlastTarget.family(GroundMaterial.DIRT, true, 0, false));
        assertEquals(BlastTarget.Family.SLAB, BlastTarget.family(GroundMaterial.WOOD, false, 1, true));
        assertEquals(BlastTarget.Family.SLAB, BlastTarget.family(GroundMaterial.STONE, false, 1, true), "каменный настил моста — плита");
        assertEquals(BlastTarget.Family.HARD, BlastTarget.family(GroundMaterial.STONE, false, 0, true), "тот же камень на опоре — твёрдое");
        assertEquals(BlastTarget.Family.SOIL, BlastTarget.family(GroundMaterial.DIRT, true, 0, true));
        assertEquals(BlastTarget.Family.HARD, BlastTarget.family(GroundMaterial.WOOD, false, 0, true), "доски на земле — не грунт");
        // разрыв зенитной ракеты в 30 м и шахед на земле
        assertEquals(BlastTarget.Family.AIR, BlastTarget.of(GroundMaterial.STONE, 12, 30).family());
        assertEquals(BlastTarget.Family.SOIL, BlastTarget.of(GroundMaterial.DIRT, SHAHED, 0).family());
        // без мира — по веществу материала: рыхлое — грунт, связное — твёрдое
        for (GroundMaterial m : GroundMaterial.values()) {
            BlastTarget t = BlastTarget.of(m, SHAHED, 0);
            BlastTarget.Family want = m.isWater() ? BlastTarget.Family.WATER : t.matter().loose ? BlastTarget.Family.SOIL : BlastTarget.Family.HARD;
            assertEquals(want, t.family(), m.name());
        }
    }

    /** Пролом плиты — √(R² − t²) у радиуса пробоя R (BreachGameTests); плита, что не пробита, — не плита. */
    @Test
    void slabHoleFollowsTheBreachLaw() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        BlastTarget t = BlastTarget.of(GroundMaterial.WOOD, false, 1, r);
        assertEquals(BlastTarget.Family.SLAB, t.family());
        assertEquals(Math.sqrt(r * r - 1), t.hole(), 1e-9);
        assertEquals(Math.PI * t.hole() * t.hole() * 1 * BlastTarget.Matter.WOOD.density, t.mass(SHAHED), 1e-6);
        // у «Града» метр досок не пробит (R < 1): опора — твёрдое, а не плита
        assertTrue(BreachModel.Material.POOR.radius(ROCKET, BreachModel.UNTAMPED) < 1);
        assertEquals(BlastTarget.Family.HARD, BlastTarget.of(GroundMaterial.WOOD, false, 0, 0).family());
    }

    /**
     * Масса разрушенного — по материалу и грунту воронки: бетон — чаша скалы × 2500 кг/м³, а не сухая почва × 1600 (прежде
     * у любой цели); у грунта — прежнее число; у воды и в воздухе — ноль.
     */
    @Test
    void crushedMassFollowsTheMaterial() {
        double soil = CraterModel.chargeVolume(SHAHED, CraterModel.Soil.DRY) * CloudModel.SOIL_DENSITY;
        assertEquals(soil, BlastTarget.SOIL.mass(SHAHED), 1e-9);
        assertEquals(soil, BlastTarget.of(GroundMaterial.DIRT, SHAHED, 0).mass(SHAHED), 1e-9);
        double rock = CraterModel.chargeVolume(SHAHED, CraterModel.Soil.ROCK) * BlastTarget.Matter.STONE.density;
        assertEquals(rock, BlastTarget.of(GroundMaterial.STONE, SHAHED, 0).mass(SHAHED), 1e-9);
        assertTrue(rock < 0.6 * soil, "в бетоне воронка меньше: " + rock + " против " + soil);
        assertEquals(0, BlastTarget.of(GroundMaterial.WATER, SHAHED, 0).mass(SHAHED));
        assertEquals(0, BlastTarget.of(GroundMaterial.DIRT, SHAHED, 50).mass(SHAHED));
    }

    /** Облако без сведений о цели — прежнее (голый рыхлый грунт): пыль облака и холодной пыли те же. */
    @Test
    void plainCloudIsTheSoilCloud() {
        BlastSource s = BlastSource.of(WeaponType.DRONE, false);
        CloudModel plain = new CloudModel(s, CloudModel.Air.STANDARD, false, 0, GroundDust.Ground.BARE);
        CloudModel soil = new CloudModel(s, CloudModel.Air.STANDARD, BlastTarget.of(GroundMaterial.DIRT, s.chargeKg(), 0), 0, GroundDust.Ground.BARE);
        assertEquals(plain.dust, soil.dust, 1e-9);
        assertEquals(plain.crushed.total, soil.crushed.total, 1e-9);
        assertEquals(plain.life, soil.life, 1e-9);
        BlastTarget stone = BlastTarget.of(GroundMaterial.STONE, s.chargeKg(), 0);
        CloudModel concrete = new CloudModel(s, CloudModel.Air.STANDARD, stone, 0, GroundDust.Ground.BARE);
        assertEquals(stone.dust(s.chargeKg()) * (1 - CloudModel.CRUSHED_HOT_SHARE), concrete.dust, 1e-9, "пыль дробления бетона — по его чаше, плотности и мелочи");
        assertEquals(stone.mass(s.chargeKg()), stone.dust(s.chargeKg()) + stone.ballistic(s.chargeKg()), 1e-9, "пыль + выброс — разрушенное");
    }

    /**
     * Мелочь связного — по замеру контактного взрыва по бетону (Costa 2025: PM10 0–2,4 г/кг, порядок 1 г/кг): PM10 смеси
     * корзин на килограмм разрушенного — {@link BlastTarget#PM10_CONTACT}; у воды пыли нет, у рыхлого — прежняя доля.
     */
    @Test
    void crushedMatterFinesFollowTheContactBlastMeasurement() {
        assertEquals(CloudModel.DUST_LOFTED, BlastTarget.Matter.DIRT.fines, 1e-12);
        for (BlastTarget.Matter m : BlastTarget.Matter.values()) {
            if (m == BlastTarget.Matter.WATER) assertEquals(0, m.fines);
            else if (!m.loose && m != BlastTarget.Matter.WOOD) {
                double pm10 = m.fines * DustModel.PM10_SHARE;
                assertEquals(BlastTarget.PM10_CONTACT, pm10, 1e-12, m.name());
                assertTrue(pm10 > 0 && pm10 <= 0.0024, m.name() + ": PM10 в пределах опытов, " + pm10);
            }
            assertTrue(m.fines < 0.5, m.name() + ": пыль — меньшая часть разрушенного");
        }
    }

    /**
     * Шахед в метровую бетонную крышу, ветер 6 м/с, класс C: холодная пыль пролома — не стена. Самый плотный клуб насквозь
     * через середину к 10 с — меньше τ 3 (дома за ним видны, как на записях камер после прилётов: дымка, а не завеса), и
     * на 30 с он ещё виден. С прежней долей 0,1 тот же клуб на 10 с — τ ≈ 35, с этой — ≈ 2,7.
     */
    @Test
    void roofBreachDustOfAShahedIsAHazeNotAWall() {
        var hard = new GroundDust.Ground(GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(1), GroundDust.Ground.filled(0), (x, z) -> 0);
        var air = new CloudModel.Air(CloudModel.N_STANDARD, 6, 0, 0.1, (int) Stability.C, 0.5);
        double breach = BreachModel.Material.ROCK.radius(SHAHED, BreachModel.UNTAMPED);
        var m = new CloudModel(BlastSource.of(WeaponType.DRONE, false), air, BlastTarget.of(GroundMaterial.STONE, false, 1, breach), 0, hard);
        assertTrue(m.crushed.tau(10) < 3, "τ на 10 с " + m.crushed.tau(10));
        assertTrue(m.crushed.tau(30) > CloudModel.SEEN, "τ на 30 с " + m.crushed.tau(30));
    }

    /** Цвет пыли: бетон и камень светлее почвы, сланец темнее, древесина — охристая (красный больше синего). */
    @Test
    void dustColourFollowsTheMatter() {
        BlastTarget.Matter soil = BlastTarget.Matter.DIRT, stone = BlastTarget.Matter.STONE;
        assertTrue(stone.r + stone.g + stone.b > soil.r + soil.g + soil.b, "бетон светлее почвы");
        assertTrue(BlastTarget.Matter.DEEPSLATE.g < soil.g, "сланец темнее");
        assertTrue(BlastTarget.Matter.WOOD.r > BlastTarget.Matter.WOOD.b + 0.2f, "опилки охристые");
        assertTrue(BlastTarget.Matter.SAND.extinction() > BlastTarget.Matter.DIRT.extinction(), "песок мельче — завеса гуще на кг");
        assertEquals(0, BlastTarget.Matter.STONE.extinction(), "у связного завесы нет");
    }

    /**
     * Тыльный откол пробитой плиты — открытый «сэндвич» Гёрни: у шахеда в метре досок ~29 м/с, в метре камня — медленнее;
     * у не плиты — 0.
     */
    @Test
    void spallSpeedFollowsGurney() {
        double rw = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        BlastTarget wood = BlastTarget.of(GroundMaterial.WOOD, false, 1, rw);
        assertEquals(29, wood.plugSpeed(SHAHED), 3);
        double rs = BreachModel.Material.ROCK.radius(SHAHED, BreachModel.UNTAMPED);
        BlastTarget stone = BlastTarget.of(GroundMaterial.STONE, false, 1, rs);
        assertTrue(stone.plugSpeed(SHAHED) < wood.plugSpeed(SHAHED) / 2, "тяжёлая каменная пробка медленнее");
        assertEquals(0, BlastTarget.of(GroundMaterial.DIRT, MISSILE, 0).plugSpeed(MISSILE));
    }
}
