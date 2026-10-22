package ua.zentix.incoming.client.far;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свет открытого неба на дыме ({@link FarView#sky}): яркость — карты освещения Minecraft, оттенок — облаков Minecraft.
 * Карта и облака — формулы ванили 1.21.1 ({@code LightTexture.updateLightTexture} у неба 15, блока 0, гамма по
 * умолчанию 0,5; {@code ClientLevel.getCloudColor}, {@code getSkyDarken}, {@code DimensionType.timeOfDay}) без дождя.
 */
class FarViewSkyTest {
    /** Доля дня ванили по времени суток. */
    private static float timeOfDay(long time) {
        double d0 = frac(time / 24000.0 - 0.25), d1 = 0.5 - Math.cos(d0 * Math.PI) / 2.0;
        return (float) (d0 * 2.0 + d1) / 3.0F;
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static float skyDarken(long time) {
        float f1 = 1 - ((float) Math.cos(timeOfDay(time) * Math.PI * 2) * 2 + 0.2F);
        f1 = 1 - Math.clamp(f1, 0, 1);
        return f1 * 0.8F + 0.2F;
    }

    /** Карта освещения у открытого неба (небо 15, блок 0), гамма 0,5. */
    private static Vector3f lightmap(long time) {
        float f = skyDarken(time), f1 = f * 0.95F + 0.05F;
        Vector3f c = new Vector3f(f, f, 1).lerp(new Vector3f(1, 1, 1), 0.35F).mul(f1);
        c.lerp(new Vector3f(0.75F), 0.04F);
        clamp(c);
        Vector3f bright = new Vector3f(notGamma(c.x), notGamma(c.y), notGamma(c.z));
        c.lerp(bright, 0.5F).lerp(new Vector3f(0.75F), 0.04F);
        clamp(c);
        // байт текстуры
        return new Vector3f((int) (c.x * 255) / 255f, (int) (c.y * 255) / 255f, (int) (c.z * 255) / 255f);
    }

    private static float notGamma(float v) {
        float f = 1 - v;
        return 1 - f * f * f * f;
    }

    private static void clamp(Vector3f c) {
        c.set(Math.clamp(c.x, 0, 1), Math.clamp(c.y, 0, 1), Math.clamp(c.z, 0, 1));
    }

    private static Vector3f cloud(long time) {
        float f1 = Math.clamp((float) Math.cos(timeOfDay(time) * Math.PI * 2) * 2 + 0.5F, 0, 1);
        return new Vector3f(f1 * 0.9F + 0.1F, f1 * 0.9F + 0.1F, f1 * 0.85F + 0.15F);
    }

    private static Vector3f open(long time) {
        Vector3f c = cloud(time);
        return FarView.open(lightmap(time), c.x, c.y, c.z, new Vector3f());
    }

    private static double luma(Vector3f c) {
        return 0.2126 * c.x + 0.7152 * c.y + 0.0722 * c.z;
    }

    /**
     * Закат (кадры VXC4: время 13000, солнце на 3,5° под горизонтом): карта освещения у неба уже синяя, почти как ночью, —
     * след по ней выходил тёмно-синим, а облака шейдерпака рядом серые. Свет на дыме — серый, как у облаков, той же
     * яркости, что у блоков; ночью — сиреневый свет луны, как был; днём — белый.
     */
    @Test
    void duskSmokeIsGreyLikeTheCloudsNotBlue() {
        for (long time : new long[]{6000, 11000, 12000, 12500, 13000, 13500, 14000, 18000}) {
            Vector3f map = lightmap(time), sky = open(time);
            System.out.printf(Locale.ROOT, "%5d: map %.2f %.2f %.2f (b/r %.2f), cloud b/r %.2f, smoke %.2f %.2f %.2f (b/r %.2f)%n", time, map.x, map.y,
                    map.z, map.z / map.x, cloud(time).z / cloud(time).x, sky.x, sky.y, sky.z, sky.z / sky.x);
            assertEquals(luma(map), luma(sky), 0.01, "яркость — карты освещения: " + time);
        }
        assertTrue(lightmap(13000).z / lightmap(13000).x > 1.4, "карта на закате синяя");
        assertTrue(open(13000).z / open(13000).x < 1.1, "дым на закате — серый");
        assertTrue(open(12000).z / open(12000).x < 1.02, "до заката — белый");
        assertTrue(open(18000).z / open(18000).x > 1.4, "ночью — свет луны, сиреневый");
        assertEquals(lightmap(6000).x, open(6000).x, 1e-3, "днём — как карта, белый");
        assertEquals(open(6000).x, open(6000).z, 1e-3, "днём — как карта, белый");
    }

    /** Поправка карты (FxSkyFix): днём — ничего, на закате — меньше синего; карта × поправка — свет дыма. */
    @Test
    void lightmapFixGivesTheSmokeLight() {
        Vector3f fix = FarView.fix(open(6000), lightmap(6000), new Vector3f());
        assertEquals(1, fix.x, 0.01);
        assertEquals(1, fix.z, 0.01);
        Vector3f map = lightmap(13000), sky = open(13000);
        fix = FarView.fix(sky, map, new Vector3f());
        assertTrue(fix.z < 1 && fix.x > 1, "закат: синего меньше, красного больше");
        assertEquals(sky.z, map.z * fix.z, 1e-5);
        assertEquals(sky.x, map.x * fix.x, 1e-5);
    }
}
