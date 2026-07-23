package pc;

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
 * True Surfing 走位（阶段 1.1 起：precise prediction + precise intersection；
 * 阶段 3.6：三波前瞻）。
 *
 * 决策流程（每 tick）：
 *  1. 取最近的可冲浪敌波 w1，对三个选项（逆时针 / 刹停 / 顺时针）各做一次精确预测：
 *     逐 tick 按真实物理（转速上限、加减速、撞墙停止）模拟自己，直到 w1 完全越过车身；
 *  2. 模拟途中用精确交点累积「w1 每 tick 扫过的圆环带 ∩ 36×36 车身方形」的角度窗口，
 *     把窗口覆盖的全部 GF bin 统计质量求和 × 子弹伤害 = 该选项在 w1 上的危险 D1；
 *     （窗口为空 = 完全躲开，D1 = 0——这正是精确交点的价值）
 *  3. 从 w1 越过后的状态出发，对下一波 w2 的三个选项再做同样预测，取 min D2；
 *  4. 仅从危险最低的第二波终点继续：对 w3 再评三选项（True）或单续航（Path），取 min D3；
 *  5. 执行 total = D1 + 0.5×min(D2) + 0.25×min(D3) 最小的选项。
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
    private static final double THIRD_WAVE_WEIGHT = 0.25; // 阶段 3.6：第三波低权前瞻
    private static final double DIVE_PROTECT_DISTANCE = 360; // 低于此预测敌距开始惩罚俯冲
    private static final double DANGER_EPSILON = 0.05;       // 零窗口选项间仍按距离分优劣

    /**
     * 阶段 2.5：冲浪危险改为 KNN(DC) 密度（替代 3×3 分段 bin）。
     * 特征 = 开火时我方局面；样本 = (特征 → 命中/伪命中 GF)。评估时取 k 近邻在 GF 轴上
     * 铺核，得到与旧 w.stats 同口径的危险数组。嵌入权重可离线梯度下降学习。
     */
    // {bft, |latV|, advV, accel, dirTime, wallF, wallB, power}
    // 阶段 2.5：离线梯度下降学得（ml/train_surf_weights.py），留出集硬 KNN
    // 真实命中窗口命中率 0.251 -> 0.267（手工基线 {2,4,1,2,2,2.5,1,1.5}）
    // 阶段 3.5：rumble 全池重训（离线硬 KNN 0.719→0.732，+1.8%；advV/power 权重回升）
    private static final double[] SURF_WEIGHTS = {5.388, 1.368, 1.721, 0.763, 1.204, 0.988, 0.258, 1.251};
    private static final int SURF_DIMS = SURF_WEIGHTS.length;
    private static final int SURF_K = 50;
    private static final int SURF_CAPACITY = 20000;
    private static final Knn SURF_DATA = new Knn(SURF_WEIGHTS, SURF_CAPACITY);
    private static final double FLATTEN_SAMPLE_WEIGHT = 0.15;

    private static int lastSurfDirection = 1;
    private static int idleDirection = 1;
    private static int shadowPieces;  // 诊断：本场生成的阴影片段数
    private static int gunheatWaves;  // 诊断：本场生成的 gunheat 虚波数

    // 特征用：加速度 / 变向时长（跨 tick）
    private double speedT1;
    private double speedT2;
    private boolean hasSpeedHist;
    private long lastDirChangeTime;
    private int trackedLatDir = 1;
    private double lastMyHeading;
    private double lastOmega; // 上一扫描 tick 观测到的转向量（供 prev 波圆形预测）
    private boolean hasMyHeading;

    // 离线训练：-Drcr.surfdata=<csv>（配 -DNOSECURITY=true）
    private static java.io.PrintWriter surfLog;
    private static boolean surfLogInit;

    // Flattener（阶段 2.4，DrussGT 路线）：敌枪近期命中率高时把飞过的波也轻轻记成「命中」，
    // 压平躲避轮廓，让 pattern-matching / 学我们走位的 GF 枪失效。
    // 用滚动窗口门控（非整场累计）——累计门控会在早期连中后锁死开启，伪命中冲掉
    // 真实命中峰 → 更易挨打 → 死亡螺旋（实测 BasicGFSurfer 87→78）。
    // 伪命中弱权重、不衰减，避免冲掉 logHit 的尖峰。
    private static final double FLATTENER_ON = 0.12;
    private static final double FLATTENER_OFF = 0.09;
    private static final int FLATTENER_WINDOW = 40;
    private static final int FLATTENER_MIN_SHOTS = 30;
    private static final boolean[] FLAT_HITS = new boolean[FLATTENER_WINDOW];
    private static int flatHead;
    private static int flatCount;
    private static int flatHitCount;
    private static boolean flattenerOn;
    private static int flattenVisits;

    // Crowd surfing（阶段 3.2）：KNN + HOT/线性/圆形自模拟，按「预测是否贴近开火 GF」动态加权
    // 索引：0=KNN 1=HOT 2=LINEAR 3=CIRCULAR；先验给简单枪一点质量（榜上大量 HOT/线性）
    private static final int MODEL_KNN = 0;
    private static final int MODEL_HOT = 1;
    private static final int MODEL_LIN = 2;
    private static final int MODEL_CIRC = 3;
    private static final int MODEL_COUNT = 4;
    private static final double[] MODEL_SCORE = {2.0, 1.0, 0.9, 0.7};
    private static final double MODEL_SCORE_DECAY = 0.97;
    private static final double MODEL_MATCH_SIGMA = 0.18;
    // 叠加到完整 KNN 上的简单枪峰幅（过大稀释冲浪、过小无收益；8 曾使 testbed 88.9→86.5）
    private static final double CROWD_PEAK_SCALE = 3.5;
    private static int crowdUpdates;

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
    private double enemyEnergy = 100;
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
        double[] features;  // 开火时局面特征（KNN 查询键）
        double[] stats;     // 缓存的危险 bin（KNN+crowd 融合，变更后作废）
        // Crowd surfing：开火时我方运动状态 → 简单枪预测 GF
        Point2D.Double myPos;
        double myHeading;
        double myVelocity;
        double myOmega;     // 每 tick 转向量（圆形预测）
        double predLinGf;
        double predCircGf;
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
        /** 1.0 = 同 tick 精确对撞；0.5 = BeepBoop t±1 重叠阴影。 */
        final double weight;

        Shadow(double gfLow, double gfHigh, long crossTime, Object bullet, double weight) {
            this.gfLow = gfLow;
            this.gfHigh = gfHigh;
            this.crossTime = crossTime;
            this.bullet = bullet;
            this.weight = weight;
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
        WaveWindow[] third;             // 从最优第二波终点出发的第三波三选项（可为 null）
    }

    private final OptionEval[] lastEvals = new OptionEval[3];
    private OptionEval chosenEval; // 本 tick 实际执行的方案（True 或 Path），主动阴影用
    private long lastEvalTime = -1;
    // 当前最优走位方案在各波上的预测被扫位置（helpful shadow GF 用）
    private EnemyWave targetWave1;
    private Point2D.Double targetEnd1;
    private EnemyWave targetWave2;
    private Point2D.Double targetEnd2;
    private EnemyWave targetWave3;
    private Point2D.Double targetEnd3;
    private static int activeShadowShots; // 诊断：主动阴影改变开火角的次数
    private static int pathWins;         // 诊断：Path/GoTo 方案击败 True 三选项的次数
    private static int thirdWaveEvals;   // 诊断：附带第三波评估的次数

    /** Path surfing 候选：两阶段 True 或 GoTo 目标点。 */
    private static final class PathSpec {
        final int opt1;
        final int ticks1;          // GoTo 时忽略
        final int opt2;
        final Point2D.Double gotoTarget; // non-null = GoTo 模式
        final int firstOption;     // 本 tick 执行的 True 选项（GoTo 时用方向提示）

        static PathSpec twoPhase(int opt1, int ticks1, int opt2) {
            return new PathSpec(opt1, ticks1, opt2, null, opt1);
        }

        static PathSpec goTo(Point2D.Double target, int preferDir) {
            return new PathSpec(preferDir, 0, preferDir, target, preferDir);
        }

        private PathSpec(int opt1, int ticks1, int opt2, Point2D.Double gotoTarget, int firstOption) {
            this.opt1 = opt1;
            this.ticks1 = ticks1;
            this.opt2 = opt2;
            this.gotoTarget = gotoTarget;
            this.firstOption = firstOption;
        }
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
        double[] w = softmaxWeights();
        return "shadowPieces=" + shadowPieces + " gunheatWaves=" + gunheatWaves
                + " activeShadowShots=" + activeShadowShots
                + " flattenVisits=" + flattenVisits
                + " flattener=" + (flattenerOn ? 1 : 0)
                + " enemyHR=" + String.format(java.util.Locale.US, "%.3f",
                PowerSelector.ENEMY.rawHitRate())
                + " enemyHRroll=" + String.format(java.util.Locale.US, "%.3f",
                rollingEnemyHitRate())
                + " surfKnn=" + SURF_DATA.size()
                + " crowdUpd=" + crowdUpdates
                + String.format(java.util.Locale.US,
                " crowdW=%.2f/%.2f/%.2f/%.2f", w[0], w[1], w[2], w[3])
                + " pathWins=" + pathWins
                + " thirdWave=" + thirdWaveEvals;
    }

    /** 敌人最近一次开火功率（未观测到开火时为默认 1.9），PowerSelector 建模用。 */
    double lastEnemyPower() {
        return lastEnemyPower;
    }

    // ===================== 波管理 =====================

    /** 每个扫描 tick 调用：检测到开火则建波，然后推进波并执行冲浪。 */
    void onScan(Snapshot cur, Snapshot prev, double firedPower, double enemyEnergy) {
        this.enemyEnergy = enemyEnergy;
        if (cur.myLateralDirection != trackedLatDir) {
            trackedLatDir = cur.myLateralDirection;
            lastDirChangeTime = cur.time;
        }
        if (firedPower > 0 && prev != null) {
            EnemyWave w = new EnemyWave();
            w.origin = prev.enemyLocation;
            w.fireTime = cur.time - 1;
            w.speed = RcMath.bulletSpeed(firedPower);
            w.power = firedPower;
            w.directAngle = prev.absBearingEnemyToMe;
            w.direction = prev.myLateralDirection;
            w.features = surfFeatures(prev, firedPower, cur.time - 1);
            fillCrowdState(w, prev, lastOmega);
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
        double omegaNow = hasMyHeading
                ? Utils.normalRelativeAngle(cur.myHeading - lastMyHeading) : 0;
        updateGunheatWave(cur, enemyEnergy, omegaNow);
        updateWaves(cur);
        surf(cur);
        // 速度历史：本 tick 结束后供下一发波的加速度特征
        speedT2 = speedT1;
        speedT1 = Math.hypot(cur.myLateralVelocity, cur.myAdvancingVelocity);
        hasSpeedHist = true;
        lastOmega = omegaNow;
        lastMyHeading = cur.myHeading;
        hasMyHeading = true;
    }

    /**
     * gunheat wave：敌人枪热 ≤2 tick 归零时，用它当前位置/上次功率立一个预测波提前冲浪，
     * 消掉「开火检测滞后 1 tick + 反应 1 tick」的裸奔窗口。真波到达即替换；对手憋枪
     * 超过预计 2 tick 就撤销重建（跟着它的新位置走）。
     */
    private void updateGunheatWave(Snapshot cur, double enemyEnergy, double omegaNow) {
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
            w.features = surfFeatures(cur, pEst, cur.time);
            fillCrowdState(w, cur, omegaNow);
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
                    noteEnemyShotForFlattener(false);
                    if (updateFlattenerGate()) {
                        logFlattenVisit(w, cur.myLocation);
                        flattenVisits++;
                    }
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

    /** 滚动窗口记一发敌弹结果，供 flattener 门控。 */
    private static void noteEnemyShotForFlattener(boolean hit) {
        if (flatCount == FLATTENER_WINDOW) {
            if (FLAT_HITS[flatHead]) {
                flatHitCount--;
            }
        } else {
            flatCount++;
        }
        FLAT_HITS[flatHead] = hit;
        if (hit) {
            flatHitCount++;
        }
        flatHead = (flatHead + 1) % FLATTENER_WINDOW;
    }

    private static double rollingEnemyHitRate() {
        return flatCount == 0 ? 0 : flatHitCount / (double) flatCount;
    }

    /**
     * 按敌枪滚动命中率更新 flattener 开关（滞回）。窗口未满时强制关闭。
     * @return 当前是否应把飞过的波记为伪命中
     */
    private static boolean updateFlattenerGate() {
        if (flatCount < FLATTENER_MIN_SHOTS) {
            flattenerOn = false;
            return false;
        }
        double hr = rollingEnemyHitRate();
        if (flattenerOn) {
            if (hr < FLATTENER_OFF) {
                flattenerOn = false;
            }
        } else if (hr > FLATTENER_ON) {
            flattenerOn = true;
        }
        return flattenerOn;
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

    // ===================== 统计（KNN） =====================

    private static double factorGf(EnemyWave w, Point2D.Double target) {
        double offset = RcMath.absoluteBearing(w.origin, target) - w.directAngle;
        return RcMath.limit(-1, Utils.normalRelativeAngle(offset)
                / RcMath.maxEscapeAngle(w.speed) * w.direction, 1);
    }

    private static int factorIndex(EnemyWave w, Point2D.Double target) {
        return (int) RcMath.limit(0, factorGf(w, target) * MID + MID, BINS - 1);
    }

    /** 真实命中：入库权重 1，并作废所有波的危险缓存。 */
    private void logHit(EnemyWave w, Point2D.Double hitLocation) {
        if (w.features == null) {
            return;
        }
        double gf = factorGf(w, hitLocation);
        SURF_DATA.add(w.features, gf, 1);
        logSurfWave(w.features, gf, botGfWidth(w, hitLocation), 1);
        invalidateDangerCaches();
    }

    /**
     * Flattener 伪命中：弱权重入库（与旧 bin +=0.15 同量级），不冲掉真实命中峰。
     */
    private void logFlattenVisit(EnemyWave w, Point2D.Double location) {
        if (w.features == null) {
            return;
        }
        double gf = factorGf(w, location);
        SURF_DATA.add(w.features, gf, FLATTEN_SAMPLE_WEIGHT);
        logSurfWave(w.features, gf, botGfWidth(w, location), 0);
        invalidateDangerCaches();
    }

    private static double botGfWidth(EnemyWave w, Point2D.Double at) {
        double dist = Math.max(1e-3, w.origin.distance(at));
        return Math.atan(18 / dist) / RcMath.maxEscapeAngle(w.speed);
    }

    private void invalidateDangerCaches() {
        for (EnemyWave w : waves) {
            w.stats = null;
        }
    }

    /** 按波特征查 KNN，生成危险 bin（含 GF0 播种，零数据时仍躲 head-on）。 */
    private static double[] buildKnnBins(double[] features) {
        double[] bins = new double[BINS];
        for (int x = 0; x < BINS; x++) {
            bins[x] = 0.1 / ((x - MID) * (x - MID) + 1);
        }
        if (features == null || SURF_DATA.size() == 0) {
            return bins;
        }
        List<Knn.Neighbor> neighbors = SURF_DATA.nearest(features,
                Math.min(SURF_K, SURF_DATA.size()));
        for (Knn.Neighbor nb : neighbors) {
            int index = (int) RcMath.limit(0, nb.entry.value * MID + MID, BINS - 1);
            double wgt = nb.entry.weight;
            for (int x = 0; x < BINS; x++) {
                bins[x] += wgt / ((x - index) * (x - index) + 1);
            }
        }
        return bins;
    }

    /**
     * Crowd surfing 融合危险：完整 KNN 密度 + 按 softmax 权重叠加的
     * HOT(GF0) / 线性 / 圆形 自模拟峰。KNN 不参与稀释，只作简单枪相对加权基准。
     */
    private static double[] buildDangerBins(EnemyWave w) {
        double[] bins = buildKnnBins(w.features);
        double[] weights = softmaxWeights();
        addCrowdPeak(bins, 0.0, weights[MODEL_HOT] * CROWD_PEAK_SCALE);
        addCrowdPeak(bins, w.predLinGf, weights[MODEL_LIN] * CROWD_PEAK_SCALE);
        addCrowdPeak(bins, w.predCircGf, weights[MODEL_CIRC] * CROWD_PEAK_SCALE);
        return bins;
    }

    private static void addCrowdPeak(double[] bins, double gf, double scale) {
        if (scale <= 1e-9) {
            return;
        }
        int index = (int) RcMath.limit(0, gf * MID + MID, BINS - 1);
        for (int x = 0; x < BINS; x++) {
            bins[x] += scale / ((x - index) * (x - index) + 1);
        }
    }

    private static double[] softmaxWeights() {
        double max = MODEL_SCORE[0];
        for (int i = 1; i < MODEL_COUNT; i++) {
            max = Math.max(max, MODEL_SCORE[i]);
        }
        double sum = 0;
        double[] w = new double[MODEL_COUNT];
        for (int i = 0; i < MODEL_COUNT; i++) {
            w[i] = Math.exp(MODEL_SCORE[i] - max);
            sum += w[i];
        }
        for (int i = 0; i < MODEL_COUNT; i++) {
            w[i] /= sum;
        }
        return w;
    }

    /** 用观测到的开火 GF 更新各危险模型权重（命中/对撞）。 */
    private static void noteCrowdObservation(EnemyWave w, double actualGf) {
        double knnGf = peakGf(buildKnnBins(w.features));
        double[] preds = {knnGf, 0.0, w.predLinGf, w.predCircGf};
        for (int m = 0; m < MODEL_COUNT; m++) {
            double err = preds[m] - actualGf;
            double match = Math.exp(-0.5 * (err / MODEL_MATCH_SIGMA) * (err / MODEL_MATCH_SIGMA));
            MODEL_SCORE[m] = MODEL_SCORE[m] * MODEL_SCORE_DECAY + match;
        }
        crowdUpdates++;
        // 权重变了，作废缓存（由调用方接着 logHit→invalidate）
    }

    private static double peakGf(double[] bins) {
        int best = MID;
        double bestV = bins[MID];
        for (int i = 0; i < BINS; i++) {
            if (bins[i] > bestV) {
                bestV = bins[i];
                best = i;
            }
        }
        return (best - MID) / (double) MID;
    }

    /** 写入开火时我方状态，并预计算线性/圆形枪会打的 GF。 */
    private void fillCrowdState(EnemyWave w, Snapshot snap, double omega) {
        w.myPos = snap.myLocation;
        w.myHeading = snap.myHeading;
        w.myVelocity = snap.myVelocity;
        w.myOmega = omega;
        w.predLinGf = simpleGunGf(w, false);
        w.predCircGf = simpleGunGf(w, true);
    }

    /**
     * 从敌人视角的线性/圆形瞄准：迭代求「子弹飞行时间内我滑行到的位置」，
     * 再换成相对 directAngle 的 GF。circular=true 时每 tick 叠加 myOmega。
     */
    private double simpleGunGf(EnemyWave w, boolean circular) {
        Point2D.Double p = new Point2D.Double(w.myPos.x, w.myPos.y);
        double h = w.myHeading;
        double[] vel = {w.myVelocity};
        int ticks = 0;
        while (ticks < 110) {
            ticks++;
            if (circular) {
                h += w.myOmega;
            }
            p = RcMath.coastStep(p, h, vel, field);
            if (ticks * w.speed >= w.origin.distance(p)) {
                break;
            }
        }
        double offset = Utils.normalRelativeAngle(
                RcMath.absoluteBearing(w.origin, p) - w.directAngle);
        return RcMath.limit(-1, offset / RcMath.maxEscapeAngle(w.speed) * w.direction, 1);
    }

    private static void ensureStats(EnemyWave w) {
        if (w != null && w.stats == null) {
            w.stats = buildDangerBins(w);
        }
    }

    /**
     * 开火局面特征（与 ml/train_surf_weights.py 列顺序一致）。
     * fireTime 用于变向时长；加速度用 speedT1−speedT2（开火瞬间的两档速度史）。
     */
    private double[] surfFeatures(Snapshot snap, double power, long fireTime) {
        double dist = snap.myLocation.distance(snap.enemyLocation);
        double speed = RcMath.bulletSpeed(power);
        double bft = dist / speed;
        double mea = RcMath.maxEscapeAngle(speed);
        double accel = 0;
        if (hasSpeedHist) {
            accel = RcMath.limit(-2, speedT1 - speedT2, 1);
        }
        double[] f = new double[SURF_DIMS];
        f[0] = RcMath.limit(0, bft / 80, 1);
        f[1] = Math.abs(snap.myLateralVelocity) / 8;
        f[2] = RcMath.limit(0, (snap.myAdvancingVelocity + 8) / 16, 1);
        f[3] = (accel + 2) / 3;
        f[4] = RcMath.limit(0, (fireTime - lastDirChangeTime) / (2 * Math.max(1, bft)), 1);
        f[5] = orbitalWallSpace(snap.enemyLocation, snap.absBearingEnemyToMe, dist, mea,
                snap.myLateralDirection);
        f[6] = orbitalWallSpace(snap.enemyLocation, snap.absBearingEnemyToMe, dist, mea,
                -snap.myLateralDirection);
        f[7] = RcMath.limit(0, power / 3, 1);
        return f;
    }

    /** 我沿 direction 绕敌环绕、离场前可走的轨道角度，按 1.5*MEA 归一化到 [0,1]。 */
    private double orbitalWallSpace(Point2D.Double enemyLocation, double absBearingEnemyToMe,
                                    double distance, double mea, int direction) {
        double max = 1.5 * mea;
        int steps = 20;
        for (int i = 1; i <= steps; i++) {
            Point2D.Double p = RcMath.project(enemyLocation,
                    absBearingEnemyToMe + direction * (max * i / steps), distance);
            if (!field.contains(p)) {
                return (i - 1) / (double) steps;
            }
        }
        return 1;
    }

    private static void logSurfWave(double[] f, double gf, double width, int realHit) {
        if (!surfLogInit) {
            surfLogInit = true;
            String path = System.getProperty("pc.surfdata");
            if (path != null) {
                try {
                    surfLog = new java.io.PrintWriter(new java.io.BufferedWriter(
                            new java.io.FileWriter(path, true)));
                } catch (Exception denied) {
                    surfLog = null;
                }
            }
        }
        if (surfLog == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(110);
        for (double v : f) {
            sb.append(String.format(java.util.Locale.US, "%.4f", v)).append(',');
        }
        sb.append(String.format(java.util.Locale.US, "%.4f,%.4f,%d", gf, width, realHit));
        surfLog.println(sb);
    }

    static void closeDataLog() {
        if (surfLog != null) {
            surfLog.flush();
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
        // 对撞不算打中我，也不进 flattener 滚动窗口（那是枪瞄偏/阴影，不是走位可学信号）
        if (hitMe) {
            noteEnemyShotForFlattener(true);
        }
        if (match != null) {
            // Crowd 权重：命中与子弹对撞都更新（对撞无走位选择偏差，BeepBoop 偏好）
            noteCrowdObservation(match, factorGf(match, hitLocation));
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
            double weight = p.length > 3 ? p[3] : 1.0;
            w.shadows.add(new Shadow(p[0], p[1], (long) p[2], b, weight));
            shadowPieces++;
        }
    }

    /**
     * 我方子弹在敌波上的阴影区间（引擎语义级）：
     * 引擎每 tick 先动子弹再做「本 tick 两条位移线段相交」判定，因此
     * tick U 上，敌方位于波上角度 φ 的子弹段 = 波源沿 φ 的 [r(U-1), r(U)] 径向段，
     * 我方子弹段 = 弦 [p(U-1), p(U)]。两段相交 ⟺ 弦上存在点落在环带 [r(U-1), r(U)] 内
     * 且方位为 φ。所以「弦 ∩ 环带」每个连通片的方位角范围就是该 tick 挡出的阴影。
     * 另按 BeepBoop：对 (bullet_t, wave_{t−1}) 与 (bullet_{t−1}, wave_t) 各加 50% 权阴影
     * （引擎按随机顺序判弹撞，邻 tick 线段也可能相交）。
     *
     * @return 每片 {gfLow, gfHigh, crossTick, weight}
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
                // 同 tick 精确阴影（权 1）
                collectChordAnnulusPieces(out, w, mea, p1, p2, rInner, rOuter, u, 1.0);
                // 50%：(bullet_t, wave_{t-1})
                double rOuterPrev = rInner;
                if (rOuterPrev > 0) {
                    double rInnerPrev = Math.max(0, rOuterPrev - w.speed);
                    collectChordAnnulusPieces(out, w, mea, p1, p2, rInnerPrev, rOuterPrev, u, 0.5);
                }
                // 50%：(bullet_{t-1}, wave_t)
                if (u - 1 > bFireTime) {
                    Point2D.Double p0 = RcMath.project(bOrigin, bAngle,
                            (u - 2 - bFireTime) * bSpeed);
                    collectChordAnnulusPieces(out, w, mea, p0, p1, rInner, rOuter, u, 0.5);
                }
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
                                                  double rInner, double rOuter, long crossTick,
                                                  double weight) {
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
                out.add(new double[]{lo, hi, crossTick, weight});
            }
        }
    }

    /**
     * [lo,hi] GF 区间被阴影加权覆盖的平均比例（每点权重累加后钳到 1）。
     * extra 片格式 {gfLow, gfHigh, ..., weight?}，缺省 weight=1。
     */
    private static double shadowedFraction(EnemyWave w, double lo, double hi,
                                           List<double[]> extra) {
        if ((w.shadows.isEmpty() && (extra == null || extra.isEmpty())) || hi - lo < 1e-12) {
            return 0;
        }
        // 扫描线事件 {pos, +weight / -weight}
        List<double[]> ev = new ArrayList<double[]>();
        for (Shadow s : w.shadows) {
            double a = Math.max(lo, s.gfLow);
            double b = Math.min(hi, s.gfHigh);
            if (b > a) {
                ev.add(new double[]{a, s.weight});
                ev.add(new double[]{b, -s.weight});
            }
        }
        if (extra != null) {
            for (double[] s : extra) {
                double a = Math.max(lo, s[0]);
                double b = Math.min(hi, s[1]);
                if (b > a) {
                    double wt = s.length > 3 ? s[3] : 1.0;
                    ev.add(new double[]{a, wt});
                    ev.add(new double[]{b, -wt});
                }
            }
        }
        if (ev.isEmpty()) {
            return 0;
        }
        Collections.sort(ev, new Comparator<double[]>() {
            @Override
            public int compare(double[] x, double[] y) {
                int c = Double.compare(x[0], y[0]);
                return c != 0 ? c : Double.compare(x[1], y[1]);
            }
        });
        double covered = 0, curW = 0, prev = lo;
        for (double[] e : ev) {
            if (e[0] > prev) {
                covered += (e[0] - prev) * Math.min(1, curW);
                prev = e[0];
            }
            curW += e[1];
        }
        if (hi > prev) {
            covered += (hi - prev) * Math.min(1, curW);
        }
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
     * extra1/2/3 为对应波上的假想阴影（主动阴影评估用，平时传 null）。
     */
    private double windowDanger(WaveWindow ww, EnemyWave wave1, List<double[]> extra1,
                                EnemyWave wave2, List<double[]> extra2,
                                EnemyWave wave3, List<double[]> extra3) {
        List<double[]> extra = null;
        if (ww.wave == wave1) {
            extra = extra1;
        } else if (ww.wave == wave2) {
            extra = extra2;
        } else if (ww.wave == wave3) {
            extra = extra3;
        }
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
        ensureStats(w);
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
        targetWave1 = targetWave2 = targetWave3 = null;
        targetEnd1 = targetEnd2 = targetEnd3 = null;
        chosenEval = null;
        // 残废且无来弹：直冲撞击刷分（有在途敌波时仍优先躲）
        if (w1 == null && PowerSelector.shouldRam(enemyEnergy)) {
            lastEvals[0] = lastEvals[1] = lastEvals[2] = null;
            setBackAsFront(RcMath.absoluteBearing(cur.myLocation, cur.enemyLocation), 100);
            return;
        }
        if (w1 == null) {
            lastEvals[0] = lastEvals[1] = lastEvals[2] = null;
            idle(cur);
            return;
        }
        MoveState now = new MoveState(new Point2D.Double(cur.myLocation.x, cur.myLocation.y),
                robot.getHeadingRadians(), robot.getVelocity(), cur.time);
        Point2D.Double enemy = cur.enemyLocation;

        // 1) True 三选项（基线 + lastEvals 供「最小可达危险」）
        int[] order = {lastSurfDirection, 0, -lastSurfDirection};
        double bestDanger = Double.POSITIVE_INFINITY;
        int bestOption = lastSurfDirection;
        int bestIdx = 0;
        PathSpec bestPath = null;
        for (int oi = 0; oi < 3; oi++) {
            OptionEval ev = evaluateTrueOption(now, w1, order[oi], enemy, order);
            lastEvals[oi] = ev;
            double total = optionTotal(ev, null, null, null);
            if (total < bestDanger) {
                bestDanger = total;
                bestOption = order[oi];
                bestIdx = oi;
            }
        }

        // 2) Path/GoTo：ETA 足够时评估两阶段路径 + 轨道目标点（BeepBoop 轻量版）
        double remaining = w1.origin.distance(cur.myLocation)
                - (cur.time - w1.fireTime) * w1.speed;
        double eta = remaining / w1.speed;
        if (eta >= 6) {
            for (PathSpec spec : buildPathSpecs(w1, cur, eta)) {
                OptionEval ev = evaluatePath(now, w1, enemy, order, spec);
                double total = optionTotal(ev, null, null, null);
                // 需明显更好才换 Path，抑制无谓抖动
                if (total < bestDanger - 0.02) {
                    bestDanger = total;
                    bestOption = spec.firstOption;
                    bestPath = spec;
                    bestIdx = -1;
                    chosenEval = ev;
                }
            }
        }
        if (chosenEval == null) {
            chosenEval = lastEvals[bestIdx];
        } else {
            pathWins++;
        }

        // 记录当前方案在各波上的预测被扫位置（helpful shadow 拦截点）
        OptionEval chosen = chosenEval;
        targetWave1 = w1;
        targetEnd1 = chosen.first.crossPos;
        if (chosen.second != null) {
            double b2 = Double.POSITIVE_INFINITY;
            for (WaveWindow sw : chosen.second) {
                double d = windowDanger(sw, null, null, null, null, null, null);
                if (d < b2) {
                    b2 = d;
                    targetWave2 = sw.wave;
                    targetEnd2 = sw.crossPos;
                }
            }
        }
        if (chosen.third != null) {
            double b3 = Double.POSITIVE_INFINITY;
            for (WaveWindow sw : chosen.third) {
                double d = windowDanger(sw, null, null, null, null, null, null);
                if (d < b3) {
                    b3 = d;
                    targetWave3 = sw.wave;
                    targetEnd3 = sw.crossPos;
                }
            }
        }

        // 执行：GoTo 朝目标点；否则 True orbit
        if (bestPath != null && bestPath.gotoTarget != null) {
            if (bestPath.firstOption != 0) {
                lastSurfDirection = bestPath.firstOption;
            }
            setBackAsFront(RcMath.absoluteBearing(cur.myLocation, bestPath.gotoTarget), 100);
        } else if (bestOption == 0) {
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

    private OptionEval evaluateTrueOption(MoveState now, EnemyWave w1, int option,
                                          Point2D.Double enemy, int[] order) {
        Prediction p1 = predictOption(now, w1, option, enemy);
        OptionEval ev = new OptionEval();
        ev.first = p1.window;
        attachFollowWaves(ev, p1.end, w1, enemy, order, true);
        return ev;
    }

    private OptionEval evaluatePath(MoveState now, EnemyWave w1, Point2D.Double enemy,
                                    int[] order, PathSpec spec) {
        Prediction p1 = spec.gotoTarget != null
                ? predictGoto(now, w1, spec.gotoTarget, enemy)
                : predictTwoPhase(now, w1, spec.opt1, spec.ticks1, spec.opt2, enemy);
        OptionEval ev = new OptionEval();
        ev.first = p1.window;
        // Path 分支第三波只评最优第二波方向的单续航，控制 CPU
        attachFollowWaves(ev, p1.end, w1, enemy, order, false);
        return ev;
    }

    /**
     * 第二波：三选项精确预测。第三波：从危险最低的第二波终点继续——
     * fullThird=true 时再开三选项；否则只沿最优第二波方向单步续航（Path 用，省 CPU）。
     */
    private void attachFollowWaves(OptionEval ev, MoveState end, EnemyWave w1,
                                   Point2D.Double enemy, int[] order, boolean fullThird) {
        EnemyWave w2 = closestSurfableWave(end.pos, end.time);
        if (w2 == null || w2 == w1) {
            return;
        }
        ev.second = new WaveWindow[3];
        Prediction[] p2 = new Prediction[3];
        double best2 = Double.POSITIVE_INFINITY;
        int best2i = 0;
        for (int oj = 0; oj < 3; oj++) {
            p2[oj] = predictOption(end, w2, order[oj], enemy);
            ev.second[oj] = p2[oj].window;
            double d = windowDanger(ev.second[oj], null, null, null, null, null, null);
            if (d < best2) {
                best2 = d;
                best2i = oj;
            }
        }
        EnemyWave w3 = closestSurfableWave(p2[best2i].end.pos, p2[best2i].end.time);
        if (w3 == null || w3 == w1 || w3 == w2) {
            return;
        }
        thirdWaveEvals++;
        if (fullThird) {
            ev.third = new WaveWindow[3];
            for (int ok = 0; ok < 3; ok++) {
                ev.third[ok] = predictOption(p2[best2i].end, w3, order[ok], enemy).window;
            }
        } else {
            // Path：单续航近似第三波（权重本就低，精度够用）
            ev.third = new WaveWindow[]{
                    predictOption(p2[best2i].end, w3, order[best2i], enemy).window};
        }
    }

    /** 两阶段 / GoTo 候选（控制数量，避免 skipped turns）。 */
    private List<PathSpec> buildPathSpecs(EnemyWave w1, Snapshot cur, double eta) {
        List<PathSpec> specs = new ArrayList<PathSpec>();
        int dir = lastSurfDirection;
        int half = Math.max(2, (int) (eta * 0.45));
        int third = Math.max(2, (int) (eta / 3));
        // 两阶段 True：先冲后停 / 先停后冲 / 冲一段再反向
        specs.add(PathSpec.twoPhase(dir, half, 0));
        specs.add(PathSpec.twoPhase(0, Math.min(4, third), dir));
        specs.add(PathSpec.twoPhase(dir, third, -dir));
        specs.add(PathSpec.twoPhase(-dir, third, dir));
        // GoTo：沿波圆轨道采样低危候选点（不同 lean / 距离）
        double base = RcMath.absoluteBearing(w1.origin, cur.myLocation);
        double dist = cur.myLocation.distance(w1.origin);
        double[] leans = {-0.3, 0.05, 0.3};
        double[] dists = {
                RcMath.limit(120, dist, 500),
                RcMath.limit(120, DESIRED_DISTANCE, 500)};
        for (int d : new int[]{dir, -dir}) {
            for (double lean : leans) {
                for (double rd : dists) {
                    double ang = base + d * (Math.PI / 2 + lean);
                    Point2D.Double t = RcMath.project(w1.origin, ang, rd);
                    if (field.contains(t) && t.distance(cur.myLocation) > 40) {
                        specs.add(PathSpec.goTo(t, d));
                    }
                }
            }
        }
        // 上限：控制 CPU（True 3 + Path≤10 ≈ 二波评估可接受）
        if (specs.size() > 10) {
            return new ArrayList<PathSpec>(specs.subList(0, 10));
        }
        return specs;
    }

    /** 先 opt1 走 ticks1 步，再改 opt2，直到波越过。 */
    private Prediction predictTwoPhase(MoveState start, EnemyWave w, int opt1, int ticks1,
                                       int opt2, Point2D.Double enemy) {
        MoveState s = start.copy();
        WaveWindow ww = new WaveWindow();
        ww.wave = w;
        int tick = 0;
        int guard = 0;
        while (guard++ < 400) {
            double rNow = (s.time - w.fireTime) * w.speed;
            double dist = s.pos.distance(w.origin);
            if (rNow > dist + HALF_DIAGONAL) {
                break;
            }
            if (rNow + w.speed >= dist - HALF_DIAGONAL) {
                if (ww.crossPos == null) {
                    ww.crossPos = new Point2D.Double(s.pos.x, s.pos.y);
                }
                double[] iv = annulusSquareOffsets(w, Math.max(0, rNow), rNow + w.speed, s.pos);
                if (iv != null) {
                    ww.minOff = Math.min(ww.minOff, iv[0]);
                    ww.maxOff = Math.max(ww.maxOff, iv[1]);
                }
            }
            int option = tick < ticks1 ? opt1 : opt2;
            ww.wallDamage += step(s, w, option, enemy);
            tick++;
        }
        return finishPrediction(s, ww, enemy);
    }

    /** 朝目标点行驶直到波越过（GoTo Surfing）。 */
    private Prediction predictGoto(MoveState start, EnemyWave w, Point2D.Double target,
                                   Point2D.Double enemy) {
        MoveState s = start.copy();
        WaveWindow ww = new WaveWindow();
        ww.wave = w;
        int guard = 0;
        while (guard++ < 400) {
            double rNow = (s.time - w.fireTime) * w.speed;
            double dist = s.pos.distance(w.origin);
            if (rNow > dist + HALF_DIAGONAL) {
                break;
            }
            if (rNow + w.speed >= dist - HALF_DIAGONAL) {
                if (ww.crossPos == null) {
                    ww.crossPos = new Point2D.Double(s.pos.x, s.pos.y);
                }
                double[] iv = annulusSquareOffsets(w, Math.max(0, rNow), rNow + w.speed, s.pos);
                if (iv != null) {
                    ww.minOff = Math.min(ww.minOff, iv[0]);
                    ww.maxOff = Math.max(ww.maxOff, iv[1]);
                }
            }
            ww.wallDamage += stepToPoint(s, target);
        }
        return finishPrediction(s, ww, enemy);
    }

    private Prediction finishPrediction(MoveState s, WaveWindow ww, Point2D.Double enemy) {
        Prediction p = new Prediction();
        p.end = s;
        if (ww.crossPos == null) {
            ww.crossPos = new Point2D.Double(s.pos.x, s.pos.y);
        }
        ww.dive = RcMath.limit(1, DIVE_PROTECT_DISTANCE / s.pos.distance(enemy), 3);
        p.window = ww;
        return p;
    }

    /** 朝 target 行驶一步（GoTo 物理，与 setBackAsFront 一致）。 */
    private double stepToPoint(MoveState s, Point2D.Double target) {
        double maxTurning = Math.PI / 720d * (40d - 3d * Math.abs(s.velocity));
        double goAngle = RcMath.absoluteBearing(s.pos, target);
        double moveAngle = goAngle - s.heading;
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
        s.pos = RcMath.project(s.pos, s.heading, s.velocity);
        double wallDamage = 0;
        if (!field.contains(s.pos)) {
            s.pos = new Point2D.Double(
                    RcMath.limit(field.x, s.pos.x, field.x + field.width),
                    RcMath.limit(field.y, s.pos.y, field.y + field.height));
            wallDamage = Math.max(0, Math.abs(s.velocity) * 0.5 - 1);
            s.velocity = 0;
        }
        s.time++;
        return wallDamage;
    }

    /** 冲浪总分（带可选假想阴影）：D1 + 0.5×min D2 + 0.25×min D3。 */
    private double optionTotal(OptionEval ev, List<double[]> extra1, List<double[]> extra2,
                               List<double[]> extra3) {
        EnemyWave wave1 = ev.first.wave;
        EnemyWave wave2 = ev.second != null && ev.second[0] != null ? ev.second[0].wave : null;
        EnemyWave wave3 = ev.third != null && ev.third[0] != null ? ev.third[0].wave : null;
        double total = windowDanger(ev.first, wave1, extra1, wave2, extra2, wave3, extra3);
        if (ev.second != null) {
            double best2 = Double.POSITIVE_INFINITY;
            for (WaveWindow sw : ev.second) {
                best2 = Math.min(best2,
                        windowDanger(sw, wave1, extra1, wave2, extra2, wave3, extra3));
            }
            total += SECOND_WAVE_WEIGHT * best2;
        }
        if (ev.third != null) {
            double best3 = Double.POSITIVE_INFINITY;
            for (WaveWindow sw : ev.third) {
                best3 = Math.min(best3,
                        windowDanger(sw, wave1, extra1, wave2, extra2, wave3, extra3));
            }
            total += THIRD_WAVE_WEIGHT * best3;
        }
        return total;
    }

    // ===================== 主动子弹阴影（阶段 2.3） =====================

    /**
     * 假想「本 tick 以 bulletSpeed 沿 fireAngle 开火」后的冲浪危险。
     * 优先用本 tick 实际选中的 Path/True 方案；并取 True 三选项最小可达作下限，
     * 避免高估阴影价值（BeepBoop：当前方案危险 + 最小可达危险）。
     */
    double dangerAfterHypotheticalShot(Point2D.Double origin, double fireAngle,
                                       double bulletSpeed, long fireTime) {
        if (lastEvalTime != fireTime || chosenEval == null) {
            return -1;
        }
        List<double[]> extra1 = hypoShadow(targetWave1, origin, fireAngle, bulletSpeed, fireTime);
        List<double[]> extra2 = hypoShadow(targetWave2, origin, fireAngle, bulletSpeed, fireTime);
        List<double[]> extra3 = hypoShadow(targetWave3, origin, fireAngle, bulletSpeed, fireTime);
        double plan = optionTotal(chosenEval, extra1, extra2, extra3);
        double minReach = plan;
        for (OptionEval ev : lastEvals) {
            if (ev != null) {
                minReach = Math.min(minReach, optionTotal(ev, extra1, extra2, extra3));
            }
        }
        // 0.65×当前方案 + 0.35×最小可达：阴影既要护住正走路，也要打开更好选项
        return 0.65 * plan + 0.35 * minReach;
    }

    /** 无假想开火时的基准危险（与 dangerAfterHypotheticalShot 同口径）。 */
    double currentPlanDanger(long time) {
        if (lastEvalTime != time || chosenEval == null) {
            return -1;
        }
        double plan = optionTotal(chosenEval, null, null, null);
        double minReach = plan;
        for (OptionEval ev : lastEvals) {
            if (ev != null) {
                minReach = Math.min(minReach, optionTotal(ev, null, null, null));
            }
        }
        return 0.65 * plan + 0.35 * minReach;
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
        addInterceptAngle(out, targetWave3, targetEnd3, gunPos, bulletSpeed, fireTime);
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

    /** 无波可冲（对手还没开火）：绕敌环绕并保持距离，偶尔换向；残废则撞击。 */
    private void idle(Snapshot cur) {
        if (PowerSelector.shouldRam(enemyEnergy)) {
            setBackAsFront(RcMath.absoluteBearing(cur.myLocation, cur.enemyLocation), 100);
            return;
        }
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
