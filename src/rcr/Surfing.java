package rcr;

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import robocode.AdvancedRobot;
import robocode.Bullet;
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

    /**
     * 命中 GF 统计，按「我的横向速度 × 敌我距离」分 3×3 段（对手的分段 GF 枪按局面
     * 打我们，躲避也必须按局面记）。跨回合保留；每段在 GF0 播种，零数据时也躲 head-on。
     */
    private static final int LAT_SEGS = 3;
    private static final int DIST_SEGS = 3;
    private static final double[][][] STATS = new double[LAT_SEGS][DIST_SEGS][BINS];
    static {
        for (double[][] lat : STATS) {
            for (double[] seg : lat) {
                for (int x = 0; x < BINS; x++) {
                    seg[x] = 0.1 / ((x - MID) * (x - MID) + 1);
                }
            }
        }
    }

    private static double[] segmentFor(double absLatVelocity, double distance) {
        int li = absLatVelocity < 2 ? 0 : absLatVelocity < 6 ? 1 : 2;
        int di = distance < 350 ? 0 : distance < 550 ? 1 : 2;
        return STATS[li][di];
    }

    private static int lastSurfDirection = 1;
    private static int idleDirection = 1;
    private static int shadowPieces;  // 诊断：本场生成的阴影片段数
    private static int gunheatWaves;  // 诊断：本场生成的 gunheat 虚波数

    private final AdvancedRobot robot;
    private final Rectangle2D.Double field;
    private final double fieldW;
    private final double fieldH;
    private final List<EnemyWave> waves = new ArrayList<EnemyWave>();
    private final List<MyBullet> myBullets = new ArrayList<MyBullet>();

    // 敌人枪热模型（gunheat waves 用）：回合开始 3.0，每 tick 冷却 coolingRate，开火 +1+p/5
    private final double coolingRate;
    private double enemyGunHeat = 3.0;
    private long heatRefTime;
    private double lastEnemyPower = 1.9;
    private EnemyWave pendingImaginary;

    static final class EnemyWave {
        Point2D.Double origin;
        long fireTime;
        double speed;
        double power;
        double directAngle; // 开火时 敌→我 的绝对方位（GF0）
        int direction;      // 开火时我的横向方向
        double distanceTraveled;
        boolean imaginary;  // gunheat 预测波：还没真开火，仅用于提前冲浪
        double[] stats;     // 开火局面对应的统计段
        final List<Shadow> shadows = new ArrayList<Shadow>();
    }

    /** 我方飞行中的子弹（阴影计算用）。 */
    private static final class MyBullet {
        Bullet ref;
        Point2D.Double origin;
        double angle;
        double speed;
        long fireTime;
    }

    /** 我方子弹在敌波上挡出的 GF 安全区间；crossTime 前子弹若死亡则阴影不成立。 */
    static final class Shadow {
        final double gfLow;
        final double gfHigh;
        final long crossTime;
        final Object bullet;

        Shadow(double gfLow, double gfHigh, long crossTime, Object bullet) {
            this.gfLow = gfLow;
            this.gfHigh = gfHigh;
            this.crossTime = crossTime;
            this.bullet = bullet;
        }
    }

    /**
     * 本 tick 冲浪评估的缓存（主动阴影假设检验用）：
     * 单个 (选项, 波) 的覆盖窗口 + 撞墙伤害 + 俯冲因子。质量可在之后带假想阴影重算。
     */
    private static final class WaveWindow {
        EnemyWave wave;
        double minOff = Double.POSITIVE_INFINITY;
        double maxOff = Double.NEGATIVE_INFINITY;
        double wallDamage;
        double dive;
        Point2D.Double crossPos; // 波前扫到车身时的预测位置（主动阴影拦截点用）
    }

    private static final class OptionEval {
        WaveWindow first;               // 第一波窗口
        WaveWindow[] second;            // 该选项终点对应第二波的三个选项窗口（可为 null）
    }

    private final OptionEval[] lastEvals = new OptionEval[3];
    private long lastEvalTime = -1;
    // 当前最优走位方案在各波上的预测被扫位置（helpful shadow GF 用）
    private EnemyWave targetWave1;
    private Point2D.Double targetEnd1;
    private EnemyWave targetWave2;
    private Point2D.Double targetEnd2;
    private static int activeShadowShots; // 诊断：主动阴影改变开火角的次数

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
        WaveWindow window; // 覆盖窗口 + 撞墙伤害 + 俯冲因子（质量可带假想阴影重算）
        MoveState end;     // 波完全越过车身后的状态，作为下一波预测的起点
    }

    Surfing(AdvancedRobot robot) {
        this.robot = robot;
        this.fieldW = robot.getBattleFieldWidth();
        this.fieldH = robot.getBattleFieldHeight();
        this.field = new Rectangle2D.Double(18, 18, fieldW - 36, fieldH - 36);
        this.coolingRate = robot.getGunCoolingRate();
    }

    static String surfStats() {
        return "shadowPieces=" + shadowPieces + " gunheatWaves=" + gunheatWaves
                + " activeShadowShots=" + activeShadowShots;
    }

    /** 敌人最近一次开火功率（未观测到开火时为默认 1.9），PowerSelector 建模用。 */
    double lastEnemyPower() {
        return lastEnemyPower;
    }

    // ===================== 波管理 =====================

    /** 每个扫描 tick 调用：检测到开火则建波，然后推进波并执行冲浪。 */
    void onScan(Snapshot cur, Snapshot prev, double firedPower, double enemyEnergy) {
        if (firedPower > 0 && prev != null) {
            EnemyWave w = new EnemyWave();
            w.origin = prev.enemyLocation;
            w.fireTime = cur.time - 1;
            w.speed = RcMath.bulletSpeed(firedPower);
            w.power = firedPower;
            w.directAngle = prev.absBearingEnemyToMe;
            w.direction = prev.myLateralDirection;
            w.stats = segmentFor(prev.myAbsLateralVelocity,
                    prev.myLocation.distance(prev.enemyLocation));
            waves.add(w);
            for (MyBullet b : myBullets) {
                computeShadows(b, w);
            }
            // 真波到了，撤掉对应的预测波；枪热重置（开火发生在 cur.time-1）
            if (pendingImaginary != null) {
                waves.remove(pendingImaginary);
                pendingImaginary = null;
            }
            lastEnemyPower = firedPower;
            enemyGunHeat = 1 + firedPower / 5 - coolingRate; // 已冷却到 cur.time
            heatRefTime = cur.time;
        }
        updateGunheatWave(cur, enemyEnergy);
        updateWaves(cur);
        surf(cur);
    }

    /**
     * gunheat wave：敌人枪热 ≤2 tick 归零时，用它当前位置/上次功率立一个预测波提前冲浪，
     * 消掉「开火检测滞后 1 tick + 反应 1 tick」的裸奔窗口。真波到达即替换；对手憋枪
     * 超过预计 2 tick 就撤销重建（跟着它的新位置走）。
     */
    private void updateGunheatWave(Snapshot cur, double enemyEnergy) {
        if (pendingImaginary != null && cur.time > pendingImaginary.fireTime + 2) {
            waves.remove(pendingImaginary);
            pendingImaginary = null;
        }
        double heatNow = Math.max(0, enemyGunHeat - coolingRate * (cur.time - heatRefTime));
        int eta = (int) Math.ceil(heatNow / coolingRate);
        if (pendingImaginary == null && eta <= 2 && enemyEnergy > 0.2) {
            EnemyWave w = new EnemyWave();
            double pEst = RcMath.limit(0.1, lastEnemyPower, enemyEnergy);
            w.origin = cur.enemyLocation;
            w.fireTime = cur.time + eta;
            w.speed = RcMath.bulletSpeed(pEst);
            w.power = pEst;
            w.directAngle = cur.absBearingEnemyToMe;
            w.direction = cur.myLateralDirection;
            w.stats = segmentFor(cur.myAbsLateralVelocity,
                    cur.myLocation.distance(cur.enemyLocation));
            w.imaginary = true;
            waves.add(w);
            pendingImaginary = w;
            gunheatWaves++;
        }
    }

    private void updateWaves(Snapshot cur) {
        Iterator<EnemyWave> it = waves.iterator();
        while (it.hasNext()) {
            EnemyWave w = it.next();
            w.distanceTraveled = (cur.time - w.fireTime) * w.speed;
            if (w.distanceTraveled > w.origin.distance(cur.myLocation) + 50) {
                if (!w.imaginary) {
                    PowerSelector.ENEMY.shotPassed(w.power, false); // 飞过 = 没打中我
                }
                it.remove();
            }
        }
        Iterator<MyBullet> bt = myBullets.iterator();
        while (bt.hasNext()) { // 兜底清理（正常路径由死亡事件移除）
            if ((cur.time - bt.next().fireTime) * 20 > 1400) {
                bt.remove();
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

    /**
     * 记录命中并做滚动衰减（×0.75）：对手的枪在学习/切换瞄准解，太老的命中样本
     * 会让我们一直躲它早已不用的角度。深度 ≈ 1/(1-0.75) = 4 次命中的记忆（按段独立）。
     */
    private static void logHit(EnemyWave w, Point2D.Double hitLocation) {
        int index = factorIndex(w, hitLocation);
        for (int x = 0; x < BINS; x++) {
            w.stats[x] = w.stats[x] * 0.75 + 1.0 / ((x - index) * (x - index) + 1);
        }
    }

    /** 被子弹命中 / 子弹对撞时调用：找到对应敌波，记录命中 GF 并移除。 */
    void onBulletContact(Point2D.Double hitLocation, double bulletVelocity,
                         double bulletPower, boolean hitMe, long time) {
        EnemyWave match = null;
        double bestDiff = 50;
        for (EnemyWave w : waves) {
            if (w.imaginary) {
                continue;
            }
            double traveled = (time - w.fireTime) * w.speed;
            double diff = Math.abs(traveled - w.origin.distance(hitLocation));
            if (diff < bestDiff && Math.abs(w.speed - bulletVelocity) < 1.0) {
                bestDiff = diff;
                match = w;
            }
        }
        PowerSelector.ENEMY.shotPassed(match != null ? match.power : bulletPower, hitMe);
        if (match != null) {
            logHit(match, hitLocation);
            waves.remove(match);
        }
    }

    // ===================== 我方子弹与 bullet shadows =====================

    /** 我方开火（KnnGun 调用）：登记子弹并在所有真实敌波上铺阴影。 */
    void onMyBulletFired(Bullet ref, Point2D.Double origin, double angle, double speed, long time) {
        MyBullet b = new MyBullet();
        b.ref = ref;
        b.origin = origin;
        b.angle = angle;
        b.speed = speed;
        b.fireTime = time;
        myBullets.add(b);
        for (EnemyWave w : waves) {
            computeShadows(b, w);
        }
    }

    /** 我方子弹死亡（命中敌人 / 对撞 / 撞墙）：撤销它尚未成立的阴影。 */
    void onMyBulletDeath(Bullet ref, long time) {
        Iterator<MyBullet> it = myBullets.iterator();
        while (it.hasNext()) {
            MyBullet b = it.next();
            if (b.ref.equals(ref)) {
                for (EnemyWave w : waves) {
                    Iterator<Shadow> si = w.shadows.iterator();
                    while (si.hasNext()) {
                        Shadow s = si.next();
                        if (s.bullet == b && s.crossTime >= time) {
                            si.remove();
                        }
                    }
                }
                it.remove();
                return;
            }
        }
    }

    private void computeShadows(MyBullet b, EnemyWave w) {
        if (w.imaginary) {
            return; // 预测波被真波替换时会重算，这里不用铺
        }
        List<double[]> pieces = shadowIntervals(w, b.origin, b.angle, b.speed, b.fireTime,
                fieldW, fieldH);
        for (double[] p : pieces) {
            w.shadows.add(new Shadow(p[0], p[1], (long) p[2], b));
            shadowPieces++;
        }
    }

    /**
     * 我方子弹在敌波上的阴影区间（引擎语义级）：
     * 引擎每 tick 先动子弹再做「本 tick 两条位移线段相交」判定，因此
     * tick U 上，敌方位于波上角度 φ 的子弹段 = 波源沿 φ 的 [r(U-1), r(U)] 径向段，
     * 我方子弹段 = 弦 [p(U-1), p(U)]。两段相交 ⟺ 弦上存在点落在环带 [r(U-1), r(U)] 内
     * 且方位为 φ。所以「弦 ∩ 环带」每个连通片的方位角范围就是该 tick 挡出的阴影。
     * 弦被内圆截断时可能有两个连通片，逐片输出。子弹出界（撞墙死亡）即停止。
     *
     * @return 每片 {gfLow, gfHigh, crossTick}
     */
    static List<double[]> shadowIntervals(EnemyWave w, Point2D.Double bOrigin, double bAngle,
                                          double bSpeed, long bFireTime, double fieldW, double fieldH) {
        List<double[]> out = new ArrayList<double[]>();
        double mea = RcMath.maxEscapeAngle(w.speed);
        long u = Math.max(bFireTime, w.fireTime);
        for (int guard = 0; guard < 150; guard++) {
            u++;
            Point2D.Double p1 = RcMath.project(bOrigin, bAngle, (u - 1 - bFireTime) * bSpeed);
            Point2D.Double p2 = RcMath.project(bOrigin, bAngle, (u - bFireTime) * bSpeed);
            double rOuter = (u - w.fireTime) * w.speed;
            if (rOuter > 0) {
                double rInner = Math.max(0, rOuter - w.speed);
                collectChordAnnulusPieces(out, w, mea, p1, p2, rInner, rOuter, u);
            }
            if (p2.x < 0 || p2.x > fieldW || p2.y < 0 || p2.y > fieldH) {
                break; // 子弹本 tick 出界死亡，之后不再产生阴影
            }
        }
        return out;
    }

    /** 弦 [p1,p2] 与环带 [rInner,rOuter]（圆心 = 波源）各连通片的 GF 区间加入 out。 */
    private static void collectChordAnnulusPieces(List<double[]> out, EnemyWave w, double mea,
                                                  Point2D.Double p1, Point2D.Double p2,
                                                  double rInner, double rOuter, long crossTick) {
        double dx = p2.x - p1.x, dy = p2.y - p1.y;
        double fx = p1.x - w.origin.x, fy = p1.y - w.origin.y;
        double a = dx * dx + dy * dy;
        if (a < 1e-12) {
            return;
        }
        // 候选参数点：端点 + 弦与内外圆交点
        double[] ts = new double[6];
        int n = 0;
        ts[n++] = 0;
        ts[n++] = 1;
        for (int c = 0; c < 2; c++) {
            double r = c == 0 ? rInner : rOuter;
            if (r <= 0) {
                continue;
            }
            double bq = 2 * (fx * dx + fy * dy);
            double cq = fx * fx + fy * fy - r * r;
            double disc = bq * bq - 4 * a * cq;
            if (disc > 0) {
                double s = Math.sqrt(disc);
                double t1 = (-bq - s) / (2 * a);
                double t2 = (-bq + s) / (2 * a);
                if (t1 > 0 && t1 < 1) {
                    ts[n++] = t1;
                }
                if (t2 > 0 && t2 < 1) {
                    ts[n++] = t2;
                }
            }
        }
        double[] sorted = new double[n];
        System.arraycopy(ts, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted);
        for (int i = 0; i + 1 < n; i++) {
            double ta = sorted[i], tb = sorted[i + 1];
            if (tb - ta < 1e-12) {
                continue;
            }
            double tm = (ta + tb) / 2;
            double mx = p1.x + tm * dx - w.origin.x;
            double my = p1.y + tm * dy - w.origin.y;
            double dm = Math.sqrt(mx * mx + my * my);
            if (dm < rInner - 1e-9 || dm > rOuter + 1e-9) {
                continue; // 该子段不在环带内
            }
            if (dm < 1e-9) {
                continue; // 恰好过波源，方位退化（物理上等价于开火瞬间对撞，可忽略）
            }
            // 端点方位 → GF（相对子段中点方位归一防回绕）
            double aMid = Math.atan2(mx, my);
            double baseOff = Utils.normalRelativeAngle(aMid - w.directAngle);
            double offA = baseOff + Utils.normalRelativeAngle(
                    Math.atan2(p1.x + ta * dx - w.origin.x, p1.y + ta * dy - w.origin.y) - aMid);
            double offB = baseOff + Utils.normalRelativeAngle(
                    Math.atan2(p1.x + tb * dx - w.origin.x, p1.y + tb * dy - w.origin.y) - aMid);
            double gfA = RcMath.limit(-1, offA / mea * w.direction, 1);
            double gfB = RcMath.limit(-1, offB / mea * w.direction, 1);
            double lo = Math.min(gfA, gfB), hi = Math.max(gfA, gfB);
            if (hi - lo > 1e-9) {
                out.add(new double[]{lo, hi, crossTick});
            }
        }
    }

    /** [lo,hi] GF 区间被「该波阴影 ∪ extra 假想阴影」并集覆盖的比例。 */
    private static double shadowedFraction(EnemyWave w, double lo, double hi,
                                           List<double[]> extra) {
        if ((w.shadows.isEmpty() && (extra == null || extra.isEmpty())) || hi - lo < 1e-12) {
            return 0;
        }
        List<double[]> xs = new ArrayList<double[]>();
        for (Shadow s : w.shadows) {
            double a = Math.max(lo, s.gfLow);
            double b = Math.min(hi, s.gfHigh);
            if (b > a) {
                xs.add(new double[]{a, b});
            }
        }
        if (extra != null) {
            for (double[] s : extra) {
                double a = Math.max(lo, s[0]);
                double b = Math.min(hi, s[1]);
                if (b > a) {
                    xs.add(new double[]{a, b});
                }
            }
        }
        if (xs.isEmpty()) {
            return 0;
        }
        Collections.sort(xs, new Comparator<double[]>() {
            @Override
            public int compare(double[] x, double[] y) {
                return Double.compare(x[0], y[0]);
            }
        });
        double covered = 0, curLo = xs.get(0)[0], curHi = xs.get(0)[1];
        for (int i = 1; i < xs.size(); i++) {
            double[] iv = xs.get(i);
            if (iv[0] <= curHi) {
                curHi = Math.max(curHi, iv[1]);
            } else {
                covered += curHi - curLo;
                curLo = iv[0];
                curHi = iv[1];
            }
        }
        covered += curHi - curLo;
        return Math.min(1, covered / (hi - lo));
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

    /** 从 start 沿 option 冲浪波 w，模拟到 w 完全越过车身，收集覆盖窗口与撞墙伤害。 */
    private Prediction predictOption(MoveState start, EnemyWave w, int option, Point2D.Double enemy) {
        MoveState s = start.copy();
        WaveWindow ww = new WaveWindow();
        ww.wave = w;
        int guard = 0;
        while (guard++ < 400) {
            double rNow = (s.time - w.fireTime) * w.speed;
            double dist = s.pos.distance(w.origin);
            if (rNow > dist + HALF_DIAGONAL) {
                break; // 波已完全越过车身
            }
            if (rNow + w.speed >= dist - HALF_DIAGONAL) {
                if (ww.crossPos == null) {
                    ww.crossPos = new Point2D.Double(s.pos.x, s.pos.y);
                }
                // 下一 tick 子弹扫过 [rNow, rNow+speed]，碰撞用当前位置的车身
                double[] iv = annulusSquareOffsets(w, Math.max(0, rNow), rNow + w.speed, s.pos);
                if (iv != null) {
                    ww.minOff = Math.min(ww.minOff, iv[0]);
                    ww.maxOff = Math.max(ww.maxOff, iv[1]);
                }
            }
            ww.wallDamage += step(s, w, option, enemy);
        }
        Prediction p = new Prediction();
        p.end = s;
        if (ww.crossPos == null) {
            ww.crossPos = new Point2D.Double(s.pos.x, s.pos.y);
        }
        ww.dive = RcMath.limit(1, DIVE_PROTECT_DISTANCE / s.pos.distance(enemy), 3);
        p.window = ww;
        return p;
    }

    /**
     * 单波单选项的危险值：
     *   (覆盖质量 × 子弹伤害 + ε) × 俯冲因子 + 撞墙伤害
     * ε 让「零窗口完全躲开」的选项之间仍按距离分优劣——精确交点下俯冲保护必须这样做，
     * 否则一个贴脸但恰好躲开本波的选项会拿到干净的 0 分、下一波直接送命。
     * extra1/extra2 为对应波上的假想阴影（主动阴影评估用，平时传 null）。
     */
    private double windowDanger(WaveWindow ww, EnemyWave wave1, List<double[]> extra1,
                                EnemyWave wave2, List<double[]> extra2) {
        List<double[]> extra = ww.wave == wave1 ? extra1 : ww.wave == wave2 ? extra2 : null;
        double mass = ww.minOff <= ww.maxOff
                ? intervalMass(ww.wave, ww.minOff, ww.maxOff, extra) : 0;
        return (mass * bulletDamage(ww.wave.power) + DANGER_EPSILON) * ww.dive + ww.wallDamage;
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

    /** 角度窗口 → GF 窗口 → 覆盖 bin 的统计质量之和（bin 按阴影遮蔽比例打折）。 */
    private static double intervalMass(EnemyWave w, double minOff, double maxOff,
                                       List<double[]> extraShadows) {
        double mea = RcMath.maxEscapeAngle(w.speed);
        double gfA = RcMath.limit(-1, minOff / mea * w.direction, 1);
        double gfB = RcMath.limit(-1, maxOff / mea * w.direction, 1);
        int iLo = (int) Math.round(RcMath.limit(0, Math.min(gfA, gfB) * MID + MID, BINS - 1));
        int iHi = (int) Math.round(RcMath.limit(0, Math.max(gfA, gfB) * MID + MID, BINS - 1));
        boolean anyShadow = !w.shadows.isEmpty()
                || (extraShadows != null && !extraShadows.isEmpty());
        double mass = 0;
        for (int i = iLo; i <= iHi; i++) {
            double m = w.stats[i];
            if (anyShadow) {
                m *= 1 - shadowedFraction(w, (i - 0.5 - MID) / MID, (i + 0.5 - MID) / MID,
                        extraShadows);
            }
            mass += m;
        }
        return mass;
    }

    private static double bulletDamage(double power) {
        return 4 * power + (power > 1 ? 2 * (power - 1) : 0);
    }

    // ===================== 决策与执行 =====================

    private void surf(Snapshot cur) {
        EnemyWave w1 = closestSurfableWave(cur.myLocation, cur.time);
        lastEvalTime = cur.time;
        targetWave1 = targetWave2 = null;
        targetEnd1 = targetEnd2 = null;
        if (w1 == null) {
            lastEvals[0] = lastEvals[1] = lastEvals[2] = null;
            idle(cur);
            return;
        }
        MoveState now = new MoveState(new Point2D.Double(cur.myLocation.x, cur.myLocation.y),
                robot.getHeadingRadians(), robot.getVelocity(), cur.time);
        Point2D.Double enemy = cur.enemyLocation;

        // 平局时偏向保持当前方向，避免无谓抖动。三个选项全量评估（不剪枝），
        // 缓存窗口供主动阴影做「假想开火后的危险」重算
        int[] order = {lastSurfDirection, 0, -lastSurfDirection};
        double bestDanger = Double.POSITIVE_INFINITY;
        int bestOption = lastSurfDirection;
        int bestIdx = 0;
        for (int oi = 0; oi < 3; oi++) {
            int option = order[oi];
            Prediction p1 = predictOption(now, w1, option, enemy);
            OptionEval ev = new OptionEval();
            ev.first = p1.window;
            EnemyWave w2 = closestSurfableWave(p1.end.pos, p1.end.time);
            if (w2 != null && w2 != w1) {
                ev.second = new WaveWindow[3];
                for (int oj = 0; oj < 3; oj++) {
                    ev.second[oj] = predictOption(p1.end, w2, order[oj], enemy).window;
                }
            }
            lastEvals[oi] = ev;
            double total = optionTotal(ev, null, null);
            if (total < bestDanger) {
                bestDanger = total;
                bestOption = option;
                bestIdx = oi;
            }
        }

        // 记录当前方案在各波上的预测被扫位置（helpful shadow 拦截点）
        OptionEval chosen = lastEvals[bestIdx];
        targetWave1 = w1;
        targetEnd1 = chosen.first.crossPos;
        if (chosen.second != null) {
            double b2 = Double.POSITIVE_INFINITY;
            for (WaveWindow sw : chosen.second) {
                double d = windowDanger(sw, null, null, null, null);
                if (d < b2) {
                    b2 = d;
                    targetWave2 = sw.wave;
                    targetEnd2 = sw.crossPos;
                }
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

    /** 三选项冲浪总分（带可选假想阴影）：D1 + 0.5 × min D2。 */
    private double optionTotal(OptionEval ev, List<double[]> extra1, List<double[]> extra2) {
        EnemyWave wave1 = ev.first.wave;
        EnemyWave wave2 = ev.second != null && ev.second[0] != null ? ev.second[0].wave : null;
        double total = windowDanger(ev.first, wave1, extra1, wave2, extra2);
        if (ev.second != null) {
            double best2 = Double.POSITIVE_INFINITY;
            for (WaveWindow sw : ev.second) {
                best2 = Math.min(best2, windowDanger(sw, wave1, extra1, wave2, extra2));
            }
            total += SECOND_WAVE_WEIGHT * best2;
        }
        return total;
    }

    // ===================== 主动子弹阴影（阶段 2.3） =====================

    /**
     * 假想「本 tick 以 bulletSpeed 沿 fireAngle 开火」后，当前走位方案的冲浪危险。
     * 用缓存的三选项窗口 + 假想子弹在两个目标波上的阴影区间重算，取三选项最小值。
     * 返回值口径与 surf() 的 danger 一致，仅用于候选开火角之间的相对比较。
     */
    double dangerAfterHypotheticalShot(Point2D.Double origin, double fireAngle,
                                       double bulletSpeed, long fireTime) {
        if (lastEvalTime != fireTime || lastEvals[0] == null) {
            return -1; // 本 tick 没有冲浪评估（无波），阴影无意义
        }
        List<double[]> extra1 = hypoShadow(targetWave1, origin, fireAngle, bulletSpeed, fireTime);
        List<double[]> extra2 = hypoShadow(targetWave2, origin, fireAngle, bulletSpeed, fireTime);
        double best = Double.POSITIVE_INFINITY;
        for (OptionEval ev : lastEvals) {
            if (ev != null) {
                best = Math.min(best, optionTotal(ev, extra1, extra2));
            }
        }
        return best;
    }

    /** 无假想开火时的基准危险（与 dangerAfterHypotheticalShot 同口径）。 */
    double currentPlanDanger(long time) {
        if (lastEvalTime != time || lastEvals[0] == null) {
            return -1;
        }
        double best = Double.POSITIVE_INFINITY;
        for (OptionEval ev : lastEvals) {
            if (ev != null) {
                best = Math.min(best, optionTotal(ev, null, null));
            }
        }
        return best;
    }

    private List<double[]> hypoShadow(EnemyWave w, Point2D.Double origin, double fireAngle,
                                      double bulletSpeed, long fireTime) {
        if (w == null || w.imaginary) {
            return null;
        }
        return shadowIntervals(w, origin, fireAngle, bulletSpeed, fireTime, fieldW, fieldH);
    }

    /**
     * 「有用阴影」候选开火角（BeepBoop getHelpfulShadowGFs 思路）：解出能拦截
     * 「敌波上瞄着我当前走位方案预测被扫位置的那颗子弹」的我方开火角——这发子弹
     * 恰好在我将要经过的 GF 上挡出阴影。
     */
    List<Double> helpfulShadowAngles(Point2D.Double gunPos, double bulletSpeed, long fireTime) {
        List<Double> out = new ArrayList<Double>();
        if (lastEvalTime != fireTime) {
            return out;
        }
        addInterceptAngle(out, targetWave1, targetEnd1, gunPos, bulletSpeed, fireTime);
        addInterceptAngle(out, targetWave2, targetEnd2, gunPos, bulletSpeed, fireTime);
        return out;
    }

    /**
     * 敌方子弹沿 (波源 → target) 方向飞行，解「我 fireTime+1 起飞的子弹与它同 tick
     * 等距相遇」的开火角：|O + dir(φ)·v·s_w − M| = (v + t_w − t_f)·s_b 关于 v 的二次方程。
     */
    private void addInterceptAngle(List<Double> out, EnemyWave w, Point2D.Double target,
                                   Point2D.Double gunPos, double bulletSpeed, long fireTime) {
        if (w == null || w.imaginary || target == null) {
            return;
        }
        double phi = RcMath.absoluteBearing(w.origin, target);
        double sx = Math.sin(phi) * w.speed;
        double sy = Math.cos(phi) * w.speed;
        double ox = w.origin.x - gunPos.x;
        double oy = w.origin.y - gunPos.y;
        double dt = w.fireTime - fireTime; // 我方子弹飞行 tick 数 = v + dt
        double a = sx * sx + sy * sy - bulletSpeed * bulletSpeed;
        double b = 2 * (ox * sx + oy * sy) - 2 * bulletSpeed * bulletSpeed * dt;
        double c = ox * ox + oy * oy - bulletSpeed * bulletSpeed * dt * dt;
        double vMin = fireTime + 1 - w.fireTime; // 我方子弹至少飞满 1 tick
        double v = Double.POSITIVE_INFINITY;
        if (Math.abs(a) < 1e-9) {
            if (Math.abs(b) > 1e-9 && -c / b >= vMin) {
                v = -c / b;
            }
        } else {
            double disc = b * b - 4 * a * c;
            if (disc >= 0) {
                double s = Math.sqrt(disc);
                double v1 = (-b - s) / (2 * a);
                double v2 = (-b + s) / (2 * a);
                if (v1 >= vMin) {
                    v = v1;
                }
                if (v2 >= vMin && v2 < v) {
                    v = v2;
                }
            }
        }
        if (v < 200) { // 有解且在合理时程内
            Point2D.Double p = new Point2D.Double(
                    w.origin.x + sx * v, w.origin.y + sy * v);
            out.add(RcMath.absoluteBearing(gunPos, p));
        }
    }

    static void noteActiveShadowShot() {
        activeShadowShots++;
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
