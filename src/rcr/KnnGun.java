package rcr;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import robocode.AdvancedRobot;
import robocode.util.Utils;

/**
 * KNN(DC) 单枪：
 * - 每个扫描 tick 记录一个虚拟枪波（当时的局面特征 + GF0 基准角 + 敌横向方向）；
 * - 波到达敌人时把 (特征 → 实际 GF) 存入 KNN；
 * - 瞄准时取 k 近邻做核密度估计（带宽 ≈ 车宽对应的 GF 宽度），取密度峰值 GF。
 */
final class KnnGun {

    // 特征权重：{子弹飞行时间, |横向速度|, 接近速度, 加速度, 方向未变时长, 前墙空间, 后墙空间}
    private static final double[] WEIGHTS = {2, 4, 1, 2, 2, 2.5, 1};
    private static final int DIMS = WEIGHTS.length;
    private static final int K = 50;

    /** 样本库，跨回合保留。35 回合的 tick 波远小于容量上限。 */
    private static final Knn DATA = new Knn(WEIGHTS, 60000);

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
    }

    KnnGun(AdvancedRobot robot) {
        this.robot = robot;
        this.field = new Rectangle2D.Double(18, 18,
                robot.getBattleFieldWidth() - 36, robot.getBattleFieldHeight() - 36);
    }

    static int dataSize() {
        return DATA.size();
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
        waves.add(w);

        double bandwidth = Math.max(0.02, Math.atan(18 / distance) / mea);
        double gf = aim(f, bandwidth);
        double fireAngle = Utils.normalAbsoluteAngle(
                absBearing + gf * mea * enemyLateralDirection);
        robot.setTurnGunRightRadians(
                Utils.normalRelativeAngle(fireAngle - robot.getGunHeadingRadians()));

        if (robot.getGunHeat() == 0
                && robot.getEnergy() > power + 0.1
                && Math.abs(robot.getGunTurnRemainingRadians()) < Math.atan(18 / distance)) {
            robot.setFire(power);
        }
    }

    /** 波扫过敌人中心时记录实际 GF。 */
    private void breakWaves(Point2D.Double enemyLocation, long time) {
        Iterator<GunWave> it = waves.iterator();
        while (it.hasNext()) {
            GunWave w = it.next();
            double traveled = (time - w.fireTime) * w.speed;
            if (traveled >= w.origin.distance(enemyLocation)) {
                double offset = Utils.normalRelativeAngle(
                        RcMath.absoluteBearing(w.origin, enemyLocation) - w.baseAngle);
                double gf = RcMath.limit(-1,
                        offset / RcMath.maxEscapeAngle(w.speed) * w.direction, 1);
                DATA.add(w.features, gf);
                it.remove();
            }
        }
    }

    /** 核密度估计：在近邻的 GF 中选密度峰值。 */
    private double aim(double[] query, double bandwidth) {
        if (DATA.size() == 0) {
            return 0;
        }
        List<Knn.Entry> neighbors = DATA.nearest(query, Math.min(K, DATA.size()));
        double bestGf = 0;
        double bestScore = -1;
        for (Knn.Entry ci : neighbors) {
            double score = 0;
            for (Knn.Entry cj : neighbors) {
                double z = (ci.value - cj.value) / bandwidth;
                score += Math.exp(-0.5 * z * z);
            }
            if (score > bestScore) {
                bestScore = score;
                bestGf = ci.value;
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
