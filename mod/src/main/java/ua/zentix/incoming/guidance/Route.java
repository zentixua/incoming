package ua.zentix.incoming.guidance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Маршрут полёта: точки поворота по горизонтали (высоту держит сам снаряд), после последней — цель.
 * Как у настоящих шахедов и крылатых ракет: не по прямой от пусковой, а петлёй в обход и заход на цель
 * с выбранного направления — отсюда полёт на десятки секунд и удар «из-за спины».
 */
public final class Route {
    private final List<Vec3> points;
    /** Откуда начат маршрут: начало первого участка (для проверки «проскочил» уже на нём). */
    @Nullable
    private final Vec3 origin;
    /** Точки поставил оператор на карте ({@code strike/Waypoints}), а не пуск: последний участок до цели бывает любым. */
    private final boolean operator;
    private int next;

    public Route(List<Vec3> points) {
        this(points, null);
    }

    public Route(List<Vec3> points, @Nullable Vec3 origin) {
        this(points, origin, false);
    }

    private Route(List<Vec3> points, @Nullable Vec3 origin, boolean operator) {
        this.points = new ArrayList<>(points);
        this.origin = origin;
        this.operator = operator;
    }

    /** Маршрут по точкам оператора. */
    public static Route operator(List<Vec3> points, Vec3 origin) {
        return new Route(points, new Vec3(origin.x, 0, origin.z), true);
    }

    public boolean byOperator() {
        return operator;
    }

    public static Route direct() {
        return new Route(List.of());
    }

    /** Следующая точка поворота или null — дальше только цель. */
    @Nullable
    public Vec3 current() {
        return next < points.size() ? points.get(next) : null;
    }

    public boolean finished() {
        return next >= points.size();
    }

    /**
     * Этот маршрут (оставшиеся точки) с точкой {@code first} перед ними; первый участок начинается в {@code origin}
     * (пуск с пусковой: сначала ровно по курсу пусковой — {@code StrikeService}).
     */
    public Route after(Vec3 first, Vec3 origin) {
        List<Vec3> pts = new ArrayList<>();
        pts.add(new Vec3(first.x, 0, first.z));
        pts.addAll(points.subList(next, points.size()));
        return new Route(pts, new Vec3(origin.x, 0, origin.z), operator);
    }

    /** Бросить оставшиеся точки: прямо на цель (перенацеливание из камеры). */
    public void skip() {
        next = points.size();
    }

    /**
     * Точка пройдена, если до неё ближе {@code capture} по горизонтали или она уже позади по ходу участка
     * (при большом радиусе разворота снаряд не кружит вокруг точки, а идёт дальше). Для первой точки участок
     * начинается в точке пуска: точка входа позади старта (короткий полёт) иначе ловила бы шахед на вечный круг.
     */
    public void update(Vec3 pos, double capture) {
        while (next < points.size()) {
            Vec3 wp = points.get(next);
            Vec3 from = next == 0 ? origin : points.get(next - 1);
            double dx = wp.x - pos.x, dz = wp.z - pos.z;
            boolean reached = dx * dx + dz * dz <= capture * capture;
            if (!reached && from != null) {
                // перешли через перпендикуляр к участку в точке поворота
                double lx = wp.x - from.x, lz = wp.z - from.z;
                reached = lx * dx + lz * dz < 0 && dx * dx + dz * dz < 4 * capture * capture;
            }
            if (!reached) return;
            next++;
        }
    }

    /** Длина оставшегося пути по горизонтали: до следующей точки, по точкам и от последней до цели. */
    public double remaining(Vec3 pos, Vec3 target) {
        double len = 0;
        Vec3 at = pos;
        for (int i = next; i < points.size(); i++) {
            len += horizontal(at, points.get(i));
            at = points.get(i);
        }
        return len + horizontal(at, target);
    }

    public int size() {
        return points.size();
    }

    public int index() {
        return next;
    }

    public List<Vec3> points() {
        return List.copyOf(points);
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Маршрут длиной около {@code length}: пуск → (точка обхода сбоку) → точка входа → цель.
     * Точка входа — в {@code entry} блоках до цели по направлению захода; если прямой путь короче нужного,
     * добавляется точка обхода сбоку от середины участка «пуск — вход», вынесенная так, чтобы путь стал нужной длины.
     *
     * @param approach горизонтальное направление захода на цель (единичный вектор, «куда летит» на последнем участке)
     * @param side     +1 — обход слева, -1 — справа
     */
    public static Route plan(Vec3 launch, Vec3 target, Vec3 approach, double length, double entry, double side) {
        Vec3 dir = new Vec3(approach.x, 0, approach.z);
        dir = dir.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : dir.normalize();
        Vec3 e = new Vec3(target.x - dir.x * entry, 0, target.z - dir.z * entry);
        Vec3 l = new Vec3(launch.x, 0, launch.z);
        double le = horizontal(l, e);
        List<Vec3> pts = new ArrayList<>();
        double need = length - entry;
        if (need > le + 1) {
            Vec3 m = l.add(e).scale(0.5);
            Vec3 along = le > 1 ? e.subtract(l).normalize() : dir;
            Vec3 perp = new Vec3(-along.z, 0, along.x).scale(side);
            double half = le / 2;
            double h = Math.sqrt(Math.max(0, need * need / 4 - half * half));
            pts.add(m.add(perp.scale(h)));
        }
        pts.add(e);
        return new Route(pts, l);
    }

    /**
     * Этот маршрут (оставшиеся точки) в полосе снаряда залпа ({@code WeaponSpec.Lanes}): путь сдвинут вбок на
     * {@code offset} блоков (знак — сторона, как {@code side} у {@link #plan}), плавно — сдвиг на пути равен offset · min(1, s / exit) · min(1, r / converge),
     * где s — путь от начала маршрута, r — путь до цели. В начале и у цели сдвига нет: снаряд выходит в полосу с
     * курса пуска и сходится к своей точке прицеливания (сама цель не сдвигается). Сдвиг ставится в точках маршрута
     * (на поворотах — по биссектрисе, чтобы оба участка шли в полосе; на крутом повороте — не больше 2·offset от точки), а
     * там, где на участке кончается выход в полосу (s = exit) или начинается схождение (r = converge), добавляется точка:
     * между ними снаряд идёт ровно параллельно пути. Нужен начальный пункт маршрута ({@link #origin}): без него — как есть.
     *
     * @param target точка прицеливания: после последней точки маршрута снаряд идёт на неё
     */
    public Route inLane(double offset, double exit, double converge, Vec3 target) {
        if (offset == 0 || origin == null) return this;
        List<Vec3> path = new ArrayList<>();
        path.add(origin);
        path.addAll(points.subList(next, points.size()));
        path.add(new Vec3(target.x, 0, target.z));
        double total = 0;
        for (int i = 1; i < path.size(); i++) total += horizontal(path.get(i - 1), path.get(i));
        // точки, где сдвиг перестаёт расти и начинает убывать: на участке, не ближе блока к его концам
        double[] breaks = {Math.min(exit, total - converge), Math.max(exit, total - converge)};
        List<Vec3> pts = new ArrayList<>();
        List<Double> along = new ArrayList<>();
        double s = 0;
        for (int i = 1; i < path.size(); i++) {
            Vec3 a = path.get(i - 1), b = path.get(i);
            double len = horizontal(a, b);
            for (double at : breaks) {
                if (at > s + 1 && at < s + len - 1 && (along.isEmpty() || at > along.getLast() + 1)) {
                    pts.add(a.add(b.subtract(a).scale((at - s) / len)));
                    along.add(at);
                }
            }
            s += len;
            if (i < path.size() - 1) {
                pts.add(b);
                along.add(s);
            }
        }
        List<Vec3> out = new ArrayList<>(pts.size());
        for (int i = 0; i < pts.size(); i++) {
            Vec3 p = pts.get(i);
            Vec3 prev = i == 0 ? origin : pts.get(i - 1);
            Vec3 after = i == pts.size() - 1 ? path.getLast() : pts.get(i + 1);
            double sAt = along.get(i);
            double k = (exit > 0 ? Math.min(1, sAt / exit) : 1) * (converge > 0 ? Math.min(1, (total - sAt) / converge) : 1);
            out.add(p.add(side(prev, p, after).scale(offset * k)));
        }
        return new Route(out, origin, operator);
    }

    /**
     * Сдвиг вбок на единицу в точке {@code p} между участками prev → p → next ({@link #across}), на повороте — по
     * биссектрисе и длиннее (1 / cos половины угла), чтобы оба участка шли на единице от пути; не больше 2.
     */
    private static Vec3 side(Vec3 prev, Vec3 p, Vec3 next) {
        Vec3 in = across(prev, p), out = across(p, next);
        if (in == null) in = out;
        if (out == null) out = in;
        if (in == null) return Vec3.ZERO;
        Vec3 sum = in.add(out);
        double len = sum.length();
        // разворот почти назад: биссектрисы нет — вбок от входящего участка
        if (len < 0.2) return in;
        return sum.scale(Math.min(2, 2 / len) / len);
    }

    /** Единица вбок от хода a → b по горизонтали, в сторону {@code side} = +1 у {@link #plan}; null — точки совпадают. */
    @Nullable
    private static Vec3 across(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z, len = Math.sqrt(dx * dx + dz * dz);
        return len < 1.0e-6 ? null : new Vec3(-dz / len, 0, dx / len);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Vec3 p : points) {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", p.x);
            t.putDouble("z", p.z);
            list.add(t);
        }
        tag.put("points", list);
        tag.putInt("next", next);
        if (origin != null) {
            tag.putDouble("origin_x", origin.x);
            tag.putDouble("origin_z", origin.z);
        }
        if (operator) tag.putBoolean("operator", true);
        return tag;
    }

    public static Route load(CompoundTag tag) {
        List<Vec3> pts = new ArrayList<>();
        for (Tag t : tag.getList("points", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            pts.add(new Vec3(c.getDouble("x"), 0, c.getDouble("z")));
        }
        Vec3 origin = tag.contains("origin_x") ? new Vec3(tag.getDouble("origin_x"), 0, tag.getDouble("origin_z")) : null;
        Route r = new Route(pts, origin, tag.getBoolean("operator"));
        r.next = Math.min(tag.getInt("next"), pts.size());
        return r;
    }
}
