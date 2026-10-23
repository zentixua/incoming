package ua.zentix.incoming.client.fx.plume;

/**
 * Факел двигателя на одной высоте — выход {@link FlameModel}: одна запись на струю вблизи ({@code PlumeRenderer}) и свет
 * вдали ({@code FarFlightView}), поэтому на краю прорисовки факел один. Метры (= блоки) от среза сопла назад по оси.
 * Три части: ядро (сверхзвуковая середина), пламя догорания (до места, где смесь на оси становится стехиометрической) и
 * остывающий хвост (до точки Дрейпера). Ширина струи растёт линейно: у среза {@link #width}, к концу ядра — вдвое.
 * Яркость — в единицах кадра: белая стена в полдень — 1 (как у {@code BlackBody}); цвет — цветность чёрного тела в
 * sRGB, наибольший канал — 255.
 *
 * @param core            длина ядра
 * @param flame           длина пламени догорания (без догорания — ядро)
 * @param length          видимая длина струи
 * @param width           полуширина струи у среза (полностью расширенная струя, сопла вместе)
 * @param diamonds        скачки уплотнения («алмазы»): давление на срезе заметно отличается от давления воздуха
 * @param coreLuminance   яркость ядра
 * @param flameLuminance  яркость пламени догорания
 * @param tailLuminance   яркость остывающего хвоста (его середины)
 * @param coreColor       цвет ядра
 * @param flameColor      цвет пламени
 * @param tailColor       цвет хвоста
 * @param temperature     температура газа в ядре, K
 * @param flameTemperature температура пламени догорания, K
 * @param emissivity      степень черноты ядра поперёк струи
 * @param sheath          оболочка струи вдоль оси (пламя догорания и хвост): яркость и цвет в местах от среза — тот же
 *                        закон, по которому {@link FlameModel} считает средние яркости частей
 * @param middle          середина света струи позади среза сопла, м: центр её светового потока (ядро, пламя и хвост
 *                        по закону вдоль оси) — отсюда светит факел вдали, на дым и на мир
 * @param tailMiddle      центр светового потока хвоста позади среза, м: свет хвоста на мир — ровным отрезком той же
 *                        середины ({@link #tailEnd})
 */
public record Flame(double core, double flame, double length, double width, boolean diamonds, double coreLuminance, double flameLuminance,
                    double tailLuminance, int coreColor, int flameColor, int tailColor, double temperature, double flameTemperature,
                    double emissivity, Sheath sheath, double middle, double tailMiddle) {
    /**
     * Оболочка струи вдоль оси — пламя догорания вокруг ядра и остывающий хвост за ним: в местах {@code x} (метры от среза
     * сопла, по возрастанию, от 0 до {@link #length}) яркость сбоку (в единицах кадра, как у частей) и цвет (sRGB,
     * наибольший канал — 255). Между местами — по прямой. Струя на экране сужается к концу не текстурой, а светом
     * модели: белое пламя, за ним жёлтый и оранжевый хвост, который гаснет к точке Дрейпера.
     */
    public record Sheath(double[] x, double[] luminance, int[] color) {
        static final Sheath NONE = new Sheath(new double[0], new double[0], new int[0]);

        public int size() {
            return x.length;
        }
    }

    /** Тот же факел, светящий в k раз слабее (струя той же формы, частиц в ней в k раз меньше; оптически тонкая). */
    public Flame dimmed(double k) {
        double[] lum = sheath.luminance().clone();
        for (int i = 0; i < lum.length; i++) lum[i] *= k;
        return new Flame(core, flame, length, width, diamonds, coreLuminance * k, flameLuminance * k, tailLuminance * k, coreColor, flameColor,
                tailColor, temperature, flameTemperature, 1 - Math.pow(1 - emissivity, k), new Sheath(sheath.x(), lum, sheath.color()), middle,
                tailMiddle);
    }

    /** Полуширина струи в x метрах от среза. */
    public double widthAt(double x) {
        return width * (1 + x / core);
    }

    /**
     * Конец отрезка света хвоста позади среза, м: ровный отрезок от конца пламени с той же серединой потока, что у хвоста
     * ({@link #tailMiddle}), не дальше видимой струи. До точки Дрейпера свет хвоста ложился бы вчетверо дальше пламени, а
     * светит в основном его начало.
     */
    public double tailEnd() {
        double fl = Math.max(flame, core);
        return Math.min(length, Math.max(fl, 2 * tailMiddle - fl));
    }

    /** Полуширина в конце видимой струи. */
    public double spread() {
        return widthAt(length);
    }

    /** Площадь сбоку от среза до x (трапеция), м². */
    public double areaTo(double x) {
        return x * (width + widthAt(x));
    }

    /** Площадь светящейся струи сбоку, м². */
    public double area() {
        return areaTo(length);
    }

    /** Радиус круга той же площади, что струя сбоку: свет вдали и источник света для дыма, м. */
    public double lightRadius() {
        return Math.sqrt(area() / Math.PI);
    }

    /** Площади частей сбоку, м²: ядро (его конус), пламя без ядра (обнимает ядро), хвост. */
    public double[] partAreas() {
        double c = core * width, f = Math.max(0, areaTo(flame) - c), t = Math.max(0, area() - areaTo(flame));
        return new double[]{c, f, t};
    }

    /** Средняя яркость струи сбоку: каждая часть на своей площади. */
    public double brightness() {
        double[] a = partAreas();
        return (coreLuminance * a[0] + flameLuminance * a[1] + tailLuminance * a[2]) / (a[0] + a[1] + a[2]);
    }

    /** Цвет света струи: части по их потокам света (линейно), снова к наибольшему каналу 255. */
    public int lightColor() {
        double[] a = partAreas(), w = {coreLuminance * a[0], flameLuminance * a[1], tailLuminance * a[2]};
        int[] colors = {coreColor, flameColor, tailColor};
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            int shift = 16 - 8 * i;
            for (int k = 0; k < 3; k++) rgb[i] += w[k] * FlameModel.linear(colors[k] >> shift & 255);
        }
        return FlameModel.pack(rgb);
    }

}
