package rcr;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import robocode.AdvancedRobot;
import robocode.util.Utils;

/**
 * True Surfing 走位（阶段 1.1：precise prediction + precise intersection + 两波冲浪）。
 *
 * 决策流程（每 tick）：
 *  1. 取最近的可冲浪敌波 w1，对三个选项（逆时针 / 刹停 / 顺时针）各做一次精确预测：
 *     逐 tick 按真实物理（转速上限、加减速、撞墙停止）模拟自己，直到 w1 完全越过车身；
 *  2. 模拟途中用精确交点累积「w1 每 tick 扫过的圆环带 ∩ 36×36 车身方形」的角度窗口，
 *     把窗口覆盖的全部 GF bin 统计质量求和 × 子弹伤害 = 该选项在 w1 上的危险 D1；
 *     （窗口为空 = 完全躲开，D1 = 0——这正是精确交点的价值）
 *  3. 从 w1 越过后的状态出发，对下一波 w2 的三个选项再做同样预测，取 min D2；
 *  4. 执行 total = D1 + 0.5 * min(D2) 最小的选项。
 *
 * 时序对齐（与 Robocode 引擎一致）：子弹在 tick T 扫过 [r(T-1), r(T)]，
 * 碰撞判定用我在 T-1 结束时的位置；波半径 r(T) = (T - fireTime) * speed。
 * 模拟循环里「先用当前状态判交、再走一步」正是这个顺序。
 */
final class Surfing {

    private static final int BINS = 47;
    private static final int MID = (BINS - 1) / 2;
    private static final double HALF_DIAGONAL = 18 * Math.sqrt(2); // 车身方形外接圆半径

    // 距离控制
    private static final double DESIRED_DISTANCE = 450;
    private static final double SECOND_WAVE_WEIGHT = 0.5;
    private static final double DIVE_PROTECT_DISTANCE = 360; // 低于此预测敌距开始惩罚俯冲
    private static final double DANGER_EPSILON = 0.05;       // 零窗口选项间仍按距离分优劣

    /** 命中 GF 统计，跨回合保留；在 GF0 播种，保证零数据时也躲 head-on。 */
    private static final double[] STATS = new double[BINS];
    static {
        for (int x = 0; x < BINS; x++) {
            STATS[x] = 0.1 / ((x - MID) * (x - MID) + 1);
        }
    }

    private static int lastSurfDirection = 1;
    private static int idleDirection = 1;

    private final AdvancedRobot robot;
    private final Rectangle2D.Double field;
    private final List<EnemyWave> waves = new ArrayList<EnemyWave>();

    static final class EnemyWave {
        Point2D.Double origin;
        long fireTime;
        double speed;
        double power;
        double directAngle; // 开火时 敌→我 的绝对方位（GF0）
        int direction;      // 开火时我的横向方向
        double distanceTraveled;
    }

    /** 精确预测用的自身运动状态。 */
    private static final class MoveState {
        Point2D.Double pos;
        double heading;
        double velocity;
        long time;

        MoveState(Point2D.Double pos, double heading, double velocity, long time) {
            this.pos = pos;
            this.heading = heading;
            this.velocity = velocity;
            this.time = time;
        }

        MoveState copy() {
            return new MoveState(new Point2D.Double(pos.x, pos.y), heading, velocity, time);
        }
    }

    private static final class Prediction {
        double hitMass;    // 精确覆盖窗口的统计质量（未乘伤害）
        double wallDamage; // 模拟途中的撞墙伤害
        MoveState end;     // 波完全越过车身后的状态，作为下一波预测的起点
    }

    Surfing(AdvancedRobot robot) {
        this.robot = robot;
        this.field = new Rectangle2D.Double(18, 18,
                robot.getBattleFieldWidth() - 36, robot.getBattleFieldHeight() - 36);
    }

    // ===================== 波管理 =====================

    /** 每个扫描 tick 调用：检测到开火则建波，然后推进波并执行冲浪。 */
    void onScan(Snapshot cur, Snapshot prev, double firedPower) {
        if (firedPower > 0 && prev != null) {
            EnemyWave w = new EnemyWave();
            w.origin = prev.enemyLocation;
            w.fireTime = cur.time - 1;
            w.speed = RcMath.bulletSpeed(firedPower);
            w.power = firedPower;
            w.directAngle = prev.absBearingEnemyToMe;
            w.direction = prev.myLateralDirection;
            waves.add(w);
        }
        updateWaves(cur);
        surf(cur);
    }

    private void updateWaves(Snapshot cur) {
        Iterator<EnemyWave> it = waves.iterator();
        while (it.hasNext()) {
            EnemyWave w = it.next();
            w.distanceTraveled = (cur.time - w.fireTime) * w.speed;
            if (w.distanceTraveled > w.origin.distance(cur.myLocation) + 50) {
                it.remove();
            }
        }
    }

    private EnemyWave closestSurfableWave(Point2D.Double pos, long time) {
        double closestEta = Double.POSITIVE_INFINITY;
        EnemyWave best = null;
        for (EnemyWave w : waves) {
            double remaining = w.origin.distance(pos) - (time - w.fireTime) * w.speed;
            if (remaining > w.speed) { // 至少还有 1 tick 才命中
                double eta = remaining / w.speed;
                if (eta < closestEta) {
                    closestEta = eta;
                    best = w;
                }
            }
        }
        return best;
    }

    // ===================== 统计 =====================

    private static int factorIndex(EnemyWave w, Point2D.Double target) {
        double offset = RcMath.absoluteBearing(w.origin, target) - w.directAngle;
        double factor = Utils.normalRelativeAngle(offset)
                / RcMath.maxEscapeAngle(w.speed) * w.direction;
        return (int) RcMath.limit(0, factor * MID + MID, BINS - 1);
    }

    private static void logHit(EnemyWave w, Point2D.Double hitLocation) {
        int index = factorIndex(w, hitLocation);
        for (int x = 0; x < BINS; x++) {
            STATS[x] += 1.0 / ((x - index) * (x - index) + 1);
        }
    }

    /** 被子弹命中 / 子弹对撞时调用：找到对应敌波，记录命中 GF 并移除。 */
    void onBulletContact(Point2D.Double hitLocation, double bulletVelocity, long time) {
        EnemyWave match = null;
        double bestDiff = 50;
        for (EnemyWave w : waves) {
            double traveled = (time - w.fireTime) * w.speed;
            double diff = Math.abs(traveled - w.origin.distance(hitLocation));
            if (diff < bestDiff && Math.abs(w.speed - bulletVelocity) < 1.0) {
                bestDiff = diff;
                match = w;
            }
        }
        if (match != null) {
            logHit(match, hitLocation);
            waves.remove(match);
        }
    }

    // ===================== 精确预测与精确交点 =====================

    /**
     * 绕 origin 沿 direction 环绕的期望行进角（含距离控制 + 墙壁平滑）。
     * 实际走位与预测模拟共用，保证两者一致。
     *
     * 距离控制以敌人「当前」位置为基准（波源是开火时的旧位置，用它做距离控制
     * 会被追身的敌人不断压近）；环绕基准角仍用波源（危险几何取决于波）。
     */
    private double orbitAngle(Point2D.Double origin, Point2D.Double pos, Point2D.Double enemy,
                              int direction) {
        double distToEnemy = pos.distance(enemy);
        double lean = RcMath.limit(-0.35,
                (distToEnemy - DESIRED_DISTANCE) / DESIRED_DISTANCE, 0.35);
        double angle = RcMath.absoluteBearing(origin, pos) + direction * (Math.PI / 2 + lean);
        // 探针固定偏长、只随敌距收缩：短探针会诱导低速贴墙（低速撞墙无伤害、
        // 模拟里看不出代价，但被困墙边对后续波是灾难）——实测撞墙数会暴涨
        double stick = Math.min(160, Math.max(40, 0.8 * distToEnemy));
        return RcMath.wallSmoothing(field, pos, angle, direction, stick);
    }

    /** 按真实物理推进一个 tick，返回本 tick 撞墙伤害（未撞墙 = 0）。option 0 = 刹停。 */
    private double step(MoveState s, EnemyWave w, int option, Point2D.Double enemy) {
        double maxTurning = Math.PI / 720d * (40d - 3d * Math.abs(s.velocity));
        if (option == 0) {
            double moveAngle = Utils.normalRelativeAngle(
                    orbitAngle(w.origin, s.pos, enemy, lastSurfDirection) - s.heading);
            if (Math.abs(moveAngle) > Math.PI / 2) {
                moveAngle = Utils.normalRelativeAngle(moveAngle + Math.PI);
            }
            s.heading = Utils.normalRelativeAngle(
                    s.heading + RcMath.limit(-maxTurning, moveAngle, maxTurning));
            s.velocity = s.velocity > 0 ? Math.max(0, s.velocity - 2) : Math.min(0, s.velocity + 2);
        } else {
            double moveAngle = orbitAngle(w.origin, s.pos, enemy, option) - s.heading;
            double moveDir = 1;
            if (Math.cos(moveAngle) < 0) {
                moveAngle += Math.PI;
                moveDir = -1;
            }
            moveAngle = Utils.normalRelativeAngle(moveAngle);
            s.heading = Utils.normalRelativeAngle(
                    s.heading + RcMath.limit(-maxTurning, moveAngle, maxTurning));
            s.velocity += s.velocity * moveDir < 0 ? 2 * moveDir : moveDir;
            s.velocity = RcMath.limit(-8, s.velocity, 8);
        }
        s.pos = RcMath.project(s.pos, s.heading, s.velocity);
        double wallDamage = 0;
        if (!field.contains(s.pos)) { // 撞墙：贴墙停住并计伤害（与引擎行为一致）
            s.pos = new Point2D.Double(
                    RcMath.limit(field.x, s.pos.x, field.x + field.width),
                    RcMath.limit(field.y, s.pos.y, field.y + field.height));
            wallDamage = Math.max(0, Math.abs(s.velocity) * 0.5 - 1);
            s.velocity = 0;
        }
        s.time++;
        return wallDamage;
    }

    /** 从 start 沿 option 冲浪波 w，模拟到 w 完全越过车身，收集覆盖窗口质量与撞墙伤害。 */
    private Prediction predictOption(MoveState start, EnemyWave w, int option, Point2D.Double enemy) {
        MoveState s = start.copy();
        double minOff = Double.POSITIVE_INFINITY;
        double maxOff = Double.NEGATIVE_INFINITY;
        double wallPenalty = 0;
        int guard = 0;
        while (guard++ < 400) {
            double rNow = (s.time - w.fireTime) * w.speed;
            double dist = s.pos.distance(w.origin);
            if (rNow > dist + HALF_DIAGONAL) {
                break; // 波已完全越过车身
            }
            if (rNow + w.speed >= dist - HALF_DIAGONAL) {
                // 下一 tick 子弹扫过 [rNow, rNow+speed]，碰撞用当前位置的车身
                double[] iv = annulusSquareOffsets(w, Math.max(0, rNow), rNow + w.speed, s.pos);
                if (iv != null) {
                    minOff = Math.min(minOff, iv[0]);
                    maxOff = Math.max(maxOff, iv[1]);
                }
            }
            wallPenalty += step(s, w, option, enemy);
        }
        Prediction p = new Prediction();
        p.end = s;
        p.hitMass = minOff <= maxOff ? intervalMass(w, minOff, maxOff) : 0;
        p.wallDamage = wallPenalty;
        return p;
    }

    /**
     * 单波单选项的危险值：
     *   (覆盖质量 × 子弹伤害 + ε) × 俯冲因子 + 撞墙伤害
     * ε 让「零窗口完全躲开」的选项之间仍按距离分优劣——精确交点下俯冲保护必须这样做，
     * 否则一个贴脸但恰好躲开本波的选项会拿到干净的 0 分、下一波直接送命。
     */
    private double waveDanger(Prediction p, EnemyWave w, Point2D.Double enemy) {
        double dive = RcMath.limit(1, DIVE_PROTECT_DISTANCE / p.end.pos.distance(enemy), 3);
        return (p.hitMass * bulletDamage(w.power) + DANGER_EPSILON) * dive + p.wallDamage;
    }

    /**
     * 圆环带 [rInner, rOuter]（圆心 = 波源）与以 center 为中心的 36×36 方形的角度交集。
     * 覆盖角度极值只会出现在「带内的方形角点」或「圆与方形边的交点」上。
     * 返回相对 directAngle 的 {minOffset, maxOffset}；无交集返回 null。
     *
     * 防回绕：单点角度先相对「波源→车身中心」方位归一（窗口必然很小，不会跨 ±π），
     * 再整体平移到 directAngle 基准，结果可能略超 ±π 但数值连续，GF 换算时钳位即可。
     */
    static double[] annulusSquareOffsets(EnemyWave w, double rInner, double rOuter,
                                         Point2D.Double center) {
        double ox = w.origin.x, oy = w.origin.y;
        double x0 = center.x - 18, x1 = center.x + 18;
        double y0 = center.y - 18, y1 = center.y + 18;
        double centerBearing = RcMath.absoluteBearing(w.origin, center);
        double baseOffset = Utils.normalRelativeAngle(centerBearing - w.directAngle);
        double[] acc = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                centerBearing, baseOffset};

        // 1) 落在圆环带内的方形角点
        for (int i = 0; i < 4; i++) {
            double cx = (i & 1) == 0 ? x0 : x1;
            double cy = (i & 2) == 0 ? y0 : y1;
            double d = Point2D.distance(ox, oy, cx, cy);
            if (d >= rInner && d <= rOuter) {
                include(acc, cx - ox, cy - oy);
            }
        }
        // 2) 内外两圆与方形四条边的交点
        for (int c = 0; c < 2; c++) {
            double r = c == 0 ? rInner : rOuter;
            if (r <= 0) {
                continue;
            }
            for (int side = 0; side < 2; side++) { // 竖边 x = x0/x1
                double dx = (side == 0 ? x0 : x1) - ox;
                double disc = r * r - dx * dx;
                if (disc >= 0) {
                    double s = Math.sqrt(disc);
                    if (oy + s >= y0 && oy + s <= y1) {
                        include(acc, dx, s);
                    }
                    if (oy - s >= y0 && oy - s <= y1) {
                        include(acc, dx, -s);
                    }
                }
            }
            for (int side = 0; side < 2; side++) { // 横边 y = y0/y1
                double dy = (side == 0 ? y0 : y1) - oy;
                double disc = r * r - dy * dy;
                if (disc >= 0) {
                    double s = Math.sqrt(disc);
                    if (ox + s >= x0 && ox + s <= x1) {
                        include(acc, s, dy);
                    }
                    if (ox - s >= x0 && ox - s <= x1) {
                        include(acc, -s, dy);
                    }
                }
            }
        }
        return acc[0] <= acc[1] ? new double[]{acc[0], acc[1]} : null;
    }

    /** acc = {min, max, centerBearing, baseOffset}；单点先相对 centerBearing 归一再平移。 */
    private static void include(double[] acc, double dx, double dy) {
        double off = acc[3] + Utils.normalRelativeAngle(Math.atan2(dx, dy) - acc[2]);
        acc[0] = Math.min(acc[0], off);
        acc[1] = Math.max(acc[1], off);
    }

    /** 角度窗口 → GF 窗口 → 覆盖 bin 的统计质量之和。 */
    private static double intervalMass(EnemyWave w, double minOff, double maxOff) {
        double mea = RcMath.maxEscapeAngle(w.speed);
        double gfA = RcMath.limit(-1, minOff / mea * w.direction, 1);
        double gfB = RcMath.limit(-1, maxOff / mea * w.direction, 1);
        int iLo = (int) Math.round(RcMath.limit(0, Math.min(gfA, gfB) * MID + MID, BINS - 1));
        int iHi = (int) Math.round(RcMath.limit(0, Math.max(gfA, gfB) * MID + MID, BINS - 1));
        double mass = 0;
        for (int i = iLo; i <= iHi; i++) {
            mass += STATS[i];
        }
        return mass;
    }

    private static double bulletDamage(double power) {
        return 4 * power + (power > 1 ? 2 * (power - 1) : 0);
    }

    // ===================== 决策与执行 =====================

    private void surf(Snapshot cur) {
        EnemyWave w1 = closestSurfableWave(cur.myLocation, cur.time);
        if (w1 == null) {
            idle(cur);
            return;
        }
        MoveState now = new MoveState(new Point2D.Double(cur.myLocation.x, cur.myLocation.y),
                robot.getHeadingRadians(), robot.getVelocity(), cur.time);
        Point2D.Double enemy = cur.enemyLocation;

        // 平局时偏向保持当前方向，避免无谓抖动
        int[] order = {lastSurfDirection, 0, -lastSurfDirection};
        double bestDanger = Double.POSITIVE_INFINITY;
        int bestOption = lastSurfDirection;
        for (int option : order) {
            Prediction p1 = predictOption(now, w1, option, enemy);
            double total = waveDanger(p1, w1, enemy);
            if (total < bestDanger) { // 第二波只在第一波不落败时才需要算，先粗剪枝
                EnemyWave w2 = closestSurfableWave(p1.end.pos, p1.end.time);
                if (w2 != null && w2 != w1) {
                    double best2 = Double.POSITIVE_INFINITY;
                    for (int option2 : order) {
                        Prediction p2 = predictOption(p1.end, w2, option2, enemy);
                        best2 = Math.min(best2, waveDanger(p2, w2, enemy));
                    }
                    total += SECOND_WAVE_WEIGHT * best2;
                }
            }
            if (total < bestDanger) {
                bestDanger = total;
                bestOption = option;
            }
        }

        if (bestOption == 0) {
            double turn = Utils.normalRelativeAngle(
                    orbitAngle(w1.origin, cur.myLocation, enemy, lastSurfDirection)
                            - robot.getHeadingRadians());
            if (Math.abs(turn) > Math.PI / 2) {
                turn = Utils.normalRelativeAngle(turn + Math.PI);
            }
            robot.setTurnRightRadians(turn);
            robot.setAhead(0);
        } else {
            lastSurfDirection = bestOption;
            setBackAsFront(orbitAngle(w1.origin, cur.myLocation, enemy, bestOption), 100);
        }
    }

    /** 无波可冲（对手还没开火）：绕敌环绕并保持距离，偶尔换向。 */
    private void idle(Snapshot cur) {
        if (Math.random() < 0.02) {
            idleDirection = -idleDirection;
        }
        setBackAsFront(orbitAngle(cur.enemyLocation, cur.myLocation, cur.enemyLocation,
                idleDirection), 100);
    }

    /** 朝 goAngle 方向移动 distance；夹角超过 90° 时倒车走，转向量最小。 */
    private void setBackAsFront(double goAngle, double distance) {
        double angle = Utils.normalRelativeAngle(goAngle - robot.getHeadingRadians());
        if (Math.abs(angle) > Math.PI / 2) {
            robot.setTurnRightRadians(Utils.normalRelativeAngle(angle + Math.PI));
            robot.setBack(distance);
        } else {
            robot.setTurnRightRadians(angle);
            robot.setAhead(distance);
        }
    }

    void paint(Graphics2D g) {
        g.setColor(java.awt.Color.RED);
        for (EnemyWave w : waves) {
            double r = w.distanceTraveled;
            g.drawOval((int) (w.origin.x - r), (int) (w.origin.y - r), (int) (2 * r), (int) (2 * r));
        }
    }
}
