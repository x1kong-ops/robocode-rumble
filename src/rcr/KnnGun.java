package rcr;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import robocode.AdvancedRobot;
import robocode.Bullet;
import robocode.util.Utils;

/**
 * KNN(DC) 双枪（阶段 1.3）：
 * - 通用枪：大容量全量数据、不衰减、开火/虚拟波同权——打非自适应走位（榜上大多数）；
 * - anti-surfer 枪：小容量环形缓冲 + 按样本年龄指数衰减（只信最近数据），
 *   虚拟波低权重——冲浪者只对真子弹躲避学习，虚拟波里几乎不含它的躲避反应；
 * - 虚拟枪框架：每个真实开火波同时记下两把枪当时的预测 GF，波到达敌人时按
 *   「预测是否落进敌人车身 GF 窗口」分别记分（EMA 命中率），瞄准用分高的枪。
 *
 * 每个扫描 tick 记录一个虚拟枪波；波到达敌人时把 (特征 → 实际 GF) 存进两个库。
 * 瞄准 = k 近邻核密度估计（带宽 ≈ 车宽对应 GF），取密度峰值。
 */
final class KnnGun {

    // 特征权重：{子弹飞行时间, |横向速度|, 接近速度, 加速度, 方向未变时长, 前墙空间, 后墙空间}
    private static final double[] WEIGHTS = {2, 4, 1, 2, 2, 2.5, 1};
    private static final int DIMS = WEIGHTS.length;

    // 通用枪
    private static final int MAIN_K = 50;
    // anti-surfer 枪
    private static final int AS_K = 20;
    private static final int AS_CAPACITY = 2000;      // 小容量环形缓冲：物理上只留最近样本
    private static final double AS_HALF_LIFE = 300;   // 年龄半衰期（按插入条数，≈1/3 回合）
    private static final double AS_VIRTUAL_WEIGHT = 0.05;
    // 虚拟枪记分：衰减累计软分（核距离），比二值命中 EMA 稳得多，避免选枪来回抖
    // 通用枪是默认；AS 枪必须「明显」领先才接管——分差在噪声带内时换枪只会两头吃亏
    private static final double SCORE_DECAY = 0.995;  // 有效窗口 ≈ 最近 200 个开火波
    private static final double AS_SWITCH_MARGIN = 0.05;
    private static final int MIN_WAVES_TO_SWITCH = 50;

    /** 样本库与两把枪的记分，跨回合保留。 */
    private static final Knn MAIN_DATA = new Knn(WEIGHTS, 60000);
    private static final Knn AS_DATA = new Knn(WEIGHTS, AS_CAPACITY);
    private static double mainScore;
    private static double asScore;
    private static double scoreNorm; // 衰减后的分母（≈样本数）
    private static int realWaves;
    private static int asFired; // 用 AS 枪开的真实炮数（诊断）

    private final AdvancedRobot robot;
    private final Rectangle2D.Double field;
    private final List<GunWave> waves = new ArrayList<GunWave>();

    private double prevEnemyVelocity;
    private boolean hasPrev;
    private int enemyLateralDirection = 1;
    private long lastDirChangeTime;

    private static final class GunWave {
        Point2D.Double origin;
        long fireTime;
        double speed;
        double baseAngle;
        int direction;
        double[] features;
        boolean real;   // 该 tick 真的发射了子弹
        double gfMain;  // 开火 tick 两把枪各自的预测（记分用）
        double gfAs;
    }

    KnnGun(AdvancedRobot robot) {
        this.robot = robot;
        this.field = new Rectangle2D.Double(18, 18,
                robot.getBattleFieldWidth() - 36, robot.getBattleFieldHeight() - 36);
    }

    static int dataSize() {
        return MAIN_DATA.size();
    }

    /** 健康指标：两枪命中率与 AS 枪实际使用占比。 */
    static String gunStats() {
        double n = Math.max(1e-9, scoreNorm);
        return String.format("gunMain=%.3f gunAS=%.3f realWaves=%d asFired=%d",
                mainScore / n, asScore / n, realWaves, asFired);
    }

    private static boolean useAsGun() {
        return scoreNorm >= MIN_WAVES_TO_SWITCH
                && (asScore - mainScore) / scoreNorm > AS_SWITCH_MARGIN;
    }

    void onScan(Point2D.Double myLocation, Point2D.Double enemyLocation,
                double enemyVelocity, double enemyHeading, double enemyEnergy, long time) {
        double absBearing = RcMath.absoluteBearing(myLocation, enemyLocation);
        double distance = myLocation.distance(enemyLocation);

        double lateralVelocity = enemyVelocity * Math.sin(enemyHeading - absBearing);
        double advancingVelocity = -enemyVelocity * Math.cos(enemyHeading - absBearing);
        if (Math.abs(lateralVelocity) > 0.1) {
            int newDir = lateralVelocity > 0 ? 1 : -1;
            if (newDir != enemyLateralDirection) {
                enemyLateralDirection = newDir;
                lastDirChangeTime = time;
            }
        }
        double accel = 0;
        if (hasPrev) {
            accel = RcMath.limit(-2, Math.abs(enemyVelocity) - Math.abs(prevEnemyVelocity), 1);
        }
        prevEnemyVelocity = enemyVelocity;
        hasPrev = true;

        breakWaves(enemyLocation, time);

        double power = choosePower(robot.getEnergy(), enemyEnergy, distance);
        double bulletSpeed = RcMath.bulletSpeed(power);
        double mea = RcMath.maxEscapeAngle(bulletSpeed);
        double bft = distance / bulletSpeed;

        double[] f = new double[DIMS];
        f[0] = RcMath.limit(0, bft / 80, 1);
        f[1] = Math.abs(lateralVelocity) / 8;
        f[2] = RcMath.limit(0, (advancingVelocity + 8) / 16, 1);
        f[3] = (accel + 2) / 3;
        f[4] = RcMath.limit(0, (time - lastDirChangeTime) / (2 * bft), 1);
        f[5] = orbitalWallSpace(myLocation, absBearing, distance, mea, enemyLateralDirection);
        f[6] = orbitalWallSpace(myLocation, absBearing, distance, mea, -enemyLateralDirection);

        GunWave w = new GunWave();
        w.origin = myLocation;
        w.fireTime = time;
        w.speed = bulletSpeed;
        w.baseAngle = absBearing;
        w.direction = enemyLateralDirection;
        w.features = f;

        double bandwidth = Math.max(0.02, Math.atan(18 / distance) / mea);
        w.gfMain = aim(MAIN_DATA, f, MAIN_K, bandwidth, 0);
        w.gfAs = aim(AS_DATA, f, AS_K, bandwidth, AS_HALF_LIFE);
        boolean useAs = useAsGun();
        double gf = useAs ? w.gfAs : w.gfMain;
        waves.add(w);

        double fireAngle = Utils.normalAbsoluteAngle(
                absBearing + gf * mea * enemyLateralDirection);
        robot.setTurnGunRightRadians(
                Utils.normalRelativeAngle(fireAngle - robot.getGunHeadingRadians()));

        if (robot.getGunHeat() == 0
                && robot.getEnergy() > power + 0.1
                && Math.abs(robot.getGunTurnRemainingRadians()) < Math.atan(18 / distance)) {
            Bullet b = robot.setFireBullet(power);
            if (b != null) {
                w.real = true;
                if (useAs) {
                    asFired++;
                }
            }
        }
    }

    /** 波扫过敌人中心：实际 GF 入库（两库权重不同），真实波给两把枪记分。 */
    private void breakWaves(Point2D.Double enemyLocation, long time) {
        Iterator<GunWave> it = waves.iterator();
        while (it.hasNext()) {
            GunWave w = it.next();
            double traveled = (time - w.fireTime) * w.speed;
            double dist = w.origin.distance(enemyLocation);
            if (traveled >= dist) {
                double mea = RcMath.maxEscapeAngle(w.speed);
                double offset = Utils.normalRelativeAngle(
                        RcMath.absoluteBearing(w.origin, enemyLocation) - w.baseAngle);
                double gf = RcMath.limit(-1, offset / mea * w.direction, 1);

                MAIN_DATA.add(w.features, gf, 1);
                AS_DATA.add(w.features, gf, w.real ? 1 : AS_VIRTUAL_WEIGHT);

                if (w.real) {
                    double gfWidth = Math.atan(18 / dist) / mea; // 车身对应的 GF 半宽
                    double zm = (w.gfMain - gf) / gfWidth;
                    double za = (w.gfAs - gf) / gfWidth;
                    mainScore = mainScore * SCORE_DECAY + Math.exp(-0.5 * zm * zm);
                    asScore = asScore * SCORE_DECAY + Math.exp(-0.5 * za * za);
                    scoreNorm = scoreNorm * SCORE_DECAY + 1;
                    realWaves++;
                }
                it.remove();
            }
        }
    }

    /**
     * 加权核密度估计：在近邻的 GF 中选密度峰值。
     * 每个近邻的贡献 = 先验权重 ×（可选）年龄衰减 0.5^(age/halfLife)。
     */
    private static double aim(Knn data, double[] query, int k, double bandwidth, double halfLife) {
        if (data.size() == 0) {
            return 0;
        }
        List<Knn.Entry> neighbors = data.nearest(query, Math.min(k, data.size()));
        int n = neighbors.size();
        double[] wgt = new double[n];
        for (int i = 0; i < n; i++) {
            Knn.Entry e = neighbors.get(i);
            wgt[i] = e.weight;
            if (halfLife > 0) {
                wgt[i] *= Math.pow(0.5, (data.seq() - e.seq) / halfLife);
            }
        }
        double bestGf = 0;
        double bestScore = -1;
        for (int i = 0; i < n; i++) {
            double vi = neighbors.get(i).value;
            double score = 0;
            for (int j = 0; j < n; j++) {
                double z = (vi - neighbors.get(j).value) / bandwidth;
                score += wgt[j] * Math.exp(-0.5 * z * z);
            }
            if (score > bestScore) {
                bestScore = score;
                bestGf = vi;
            }
        }
        return bestGf;
    }

    /** 敌人沿 direction 绕我环绕、离场前可走的轨道角度，按 1.5*MEA 归一化到 [0,1]。 */
    private double orbitalWallSpace(Point2D.Double myLocation, double absBearing,
                                    double distance, double mea, int direction) {
        double max = 1.5 * mea;
        int steps = 20;
        for (int i = 1; i <= steps; i++) {
            Point2D.Double p = RcMath.project(myLocation,
                    absBearing + direction * (max * i / steps), distance);
            if (!field.contains(p)) {
                return (i - 1) / (double) steps;
            }
        }
        return 1;
    }

    /**
     * 火力选择（阶段 0 规则版）：基础 1.95，近距离 2.95，
     * 低能量收缩，并按敌方剩余能量做击杀经济（4*power 伤害恰好击杀即可）。
     */
    static double choosePower(double myEnergy, double enemyEnergy, double distance) {
        double power = 1.95;
        if (distance < 140) {
            power = 2.95;
        }
        if (myEnergy < 20) {
            power = Math.min(power, myEnergy / 10);
        }
        power = Math.min(power, Math.max(0.1, enemyEnergy / 4));
        return RcMath.limit(0.1, Math.min(power, myEnergy - 0.1), 3.0);
    }
}
