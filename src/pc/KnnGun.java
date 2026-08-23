package pc;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
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

    // 特征权重：{子弹飞行时间, |横向速度|, 接近速度, 加速度, 方向未变时长, 前墙空间, 后墙空间, 近8tick位移}
    // 阶段 2.1：离线梯度下降学得（ml/train_gun_weights.py，soft-KNN NLL），
    // 留出集硬 KNN 车身窗口命中率 0.322 -> 0.341（手工权重基线 {2,4,1,2,2,2.5,1,2}）
    // 阶段 3.5：rumble 全池重训（离线硬 KNN 相对线上基线 -0.4%，比例微调）
    // 阶段 3.8 leakbed 重训试过并放弃：离线硬 KNN +0.5%，实战 Gaff/Komarious/Cigaret 均掉 3–4%
    // 阶段 2.1 / 3.5：线性权重。非线性 a、b 默认恒等；覆盖见 Params。
    private static double[] WEIGHTS = {5.290, 0.841, 2.623, 0.573, 1.096, 1.342, 0.980, 0.621};
    private static double[] EXPONENTS = {1, 1, 1, 1, 1, 1, 1, 1};
    private static double[] BIASES = {0, 0, 0, 0, 0, 0, 0, 0};
    private static int DIMS = WEIGHTS.length;

    // 通用枪
    private static int MAIN_K = 50;
    private static int AS_K = 20;
    private static int AS_CAPACITY = 2000;
    private static double AS_HALF_LIFE = 300;
    private static double AS_VIRTUAL_WEIGHT = 0.05;
    private static double SCORE_DECAY = 0.995;
    private static double AS_SWITCH_MARGIN = 0.05;
    private static int MIN_WAVES_TO_SWITCH = 50;
    private static double PIF_SWITCH_MARGIN = 0.08;

    /** 样本库与两把枪的记分，跨回合保留。 */
    private static Knn MAIN_DATA;
    private static Knn AS_DATA;
    private static boolean bound;
    private static double mainScore;
    private static double asScore;
    private static double pifScore;
    private static double scoreNorm; // 衰减后的分母（≈样本数）
    private static int realWaves;
    private static int asFired; // 用 AS 枪开的真实炮数（诊断）
    private static int pifFired;

    // 实弹命中率（能量管理用）。用整场累计而非滚动窗口：滚动窗口对冲浪对手会在
    // 连中片段里冲过阈值、误触发重弹（弹速慢 → 逃逸角大 → 更好躲），来回震荡两头亏
    private static int myShots;
    private static int myHits;
    private static int preciseRefineUses; // 诊断：主枪精确预测改角次数
    private static int ramAimShots;       // 诊断：近距/冲撞 HOT-线性混合开火次数

    private final AdvancedRobot robot;
    private final Surfing surfing; // 开火时通知铺 bullet shadow
    private final Rectangle2D.Double field;
    private final List<GunWave> waves = new ArrayList<GunWave>();

    private double prevEnemyVelocity;
    private boolean hasPrev;
    private int enemyLateralDirection = 1;
    private long lastDirChangeTime;
    private final List<Point2D.Double> enemyHistory = new ArrayList<Point2D.Double>();
    private double prevEnemyHeading;
    private boolean hasEnemyHeading;

    // PM-PIF：环形记录敌方 (latV, omega, vel)，匹配历史窗口后把后续动作接到当前状态上前推
    private static int PIF_CAP = 1800;
    private static int PIF_PAT = 10;
    private static int PIF_K = 6;
    private static int REFINE_MIN_SHOTS = 80;
    private static double REFINE_MIN_HIT_RATE = 0.16;
    private static double RAMMER_AIM_DIST = 160;
    private static double RAMMER_AIM_ADV = 5.5;
    private final double[] pifLat;
    private final double[] pifOmg;
    private final double[] pifVel;
    private int pifSize;
    private int pifHead;

    // 离线训练数据导出：-Drcr.datalog=<csv 路径> 时启用（需 -DNOSECURITY=true，仅 datagen 用）
    private static java.io.PrintWriter dataLog;
    private static boolean dataLogInit;

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
        double gfPif;
    }

    KnnGun(AdvancedRobot robot, Surfing surfing) {
        bind();
        this.robot = robot;
        this.surfing = surfing;
        this.field = new Rectangle2D.Double(18, 18,
                robot.getBattleFieldWidth() - 36, robot.getBattleFieldHeight() - 36);
        this.pifLat = new double[PIF_CAP];
        this.pifOmg = new double[PIF_CAP];
        this.pifVel = new double[PIF_CAP];
    }

    static void bind() {
        if (bound) {
            return;
        }
        bound = true;
        Params p = Params.get();
        WEIGHTS = p.gunWeights;
        EXPONENTS = p.gunExponents;
        BIASES = p.gunBiases;
        DIMS = WEIGHTS.length;
        MAIN_K = p.mainK;
        AS_K = p.asK;
        AS_CAPACITY = p.asCapacity;
        AS_HALF_LIFE = p.asHalfLife;
        AS_VIRTUAL_WEIGHT = p.asVirtualWeight;
        SCORE_DECAY = p.scoreDecay;
        AS_SWITCH_MARGIN = p.asSwitchMargin;
        MIN_WAVES_TO_SWITCH = p.minWavesToSwitch;
        PIF_SWITCH_MARGIN = p.pifSwitchMargin;
        PIF_CAP = p.pifCap;
        PIF_PAT = p.pifPat;
        PIF_K = p.pifK;
        REFINE_MIN_SHOTS = p.refineMinShots;
        REFINE_MIN_HIT_RATE = p.refineMinHitRate;
        RAMMER_AIM_DIST = p.rammerAimDist;
        RAMMER_AIM_ADV = p.rammerAimAdv;
        MAIN_DATA = new Knn(WEIGHTS, EXPONENTS, BIASES, p.gunCapacity);
        AS_DATA = new Knn(WEIGHTS, EXPONENTS, BIASES, AS_CAPACITY);
    }

    static int dataSize() {
        bind();
        return MAIN_DATA.size();
    }

    /** 健康指标：两枪虚拟命中率、AS 枪使用量、实弹命中率。 */
    static String gunStats() {
        bind();
        double n = Math.max(1e-9, scoreNorm);
        return String.format(
                "gunMain=%.3f gunAS=%.3f gunPif=%.3f realWaves=%d asFired=%d pifFired=%d hitRate=%.3f myShots=%d preciseRefine=%d ramAim=%d",
                mainScore / n, asScore / n, pifScore / n, realWaves, asFired, pifFired, myHitRate(), myShots,
                preciseRefineUses, ramAimShots);
    }

    /** Wavelet.onBulletHit 转发：我的子弹命中敌人。 */
    static void onMyBulletHit() {
        myHits++;
    }

    private static double myHitRate() {
        return myShots == 0 ? 0 : myHits / (double) myShots;
    }

    private static boolean useAsGun() {
        return scoreNorm >= MIN_WAVES_TO_SWITCH
                && (asScore - mainScore) / scoreNorm > AS_SWITCH_MARGIN;
    }

    private static boolean usePifGun() {
        double best = Math.max(mainScore, asScore);
        return scoreNorm >= MIN_WAVES_TO_SWITCH
                && (pifScore - best) / scoreNorm > PIF_SWITCH_MARGIN;
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
        recordPif(lateralVelocity, enemyHeading, enemyVelocity);

        breakWaves(enemyLocation, time);

        double rawPower = PowerSelector.choosePower(robot.getEnergy(), enemyEnergy, distance,
                surfing.lastEnemyPower(), robot.getRoundNum(), robot.getGunCoolingRate(),
                advancingVelocity);
        boolean fireAllowed = rawPower >= 0.0995;
        double power = RcMath.limit(0.1, rawPower, 3.0);
        double bulletSpeed = RcMath.bulletSpeed(power);
        double mea = RcMath.maxEscapeAngle(bulletSpeed);
        double bft = distance / bulletSpeed;

        enemyHistory.add(enemyLocation);
        double disp8 = enemyLocation.distance(
                enemyHistory.get(Math.max(0, enemyHistory.size() - 9))) / 64.0;

        double[] f = new double[DIMS];
        f[0] = RcMath.limit(0, bft / 80, 1);
        f[1] = Math.abs(lateralVelocity) / 8;
        f[2] = RcMath.limit(0, (advancingVelocity + 8) / 16, 1);
        f[3] = (accel + 2) / 3;
        f[4] = RcMath.limit(0, (time - lastDirChangeTime) / (2 * bft), 1);
        f[5] = orbitalWallSpace(myLocation, absBearing, distance, mea, enemyLateralDirection);
        f[6] = orbitalWallSpace(myLocation, absBearing, distance, mea, -enemyLateralDirection);
        f[7] = Math.min(1, disp8);

        GunWave w = new GunWave();
        w.origin = myLocation;
        w.fireTime = time;
        w.speed = bulletSpeed;
        w.baseAngle = absBearing;
        w.direction = enemyLateralDirection;
        w.features = f;

        double bandwidth = Math.max(0.02, Math.atan(18 / distance) / mea);
        Kde kdeMain = kde(MAIN_DATA, f, MAIN_K, bandwidth, 0);
        Kde kdeAs = kde(AS_DATA, f, AS_K, bandwidth, AS_HALF_LIFE);
        w.gfMain = kdeMain.bestGf();
        w.gfAs = kdeAs.bestGf();
        w.gfPif = pifGf(myLocation, enemyLocation, enemyHeading, enemyVelocity,
                absBearing, mea, bulletSpeed);
        boolean usePif = usePifGun();
        boolean useAs = !usePif && useAsGun();
        double gf = usePif ? w.gfPif : (useAs ? w.gfAs : w.gfMain);

        // 主枪：匀速滑行精确预测校验候选 GF（AS / PIF 不做线性拉扯）
        // 命中率已经很低时对手在躲线性/匀速，再把瞄准往线性拉只会更偏（Samekh/Tigger）
        if (!usePif && !useAs && MAIN_DATA.size() > 0
                && (myShots < REFINE_MIN_SHOTS || myHitRate() >= REFINE_MIN_HIT_RATE)) {
            double refined = refineMainGf(kdeMain, gf, myLocation, enemyLocation,
                    enemyHeading, enemyVelocity, absBearing, mea, bulletSpeed);
            if (Math.abs(refined - gf) > 0.01) {
                preciseRefineUses++;
            }
            gf = refined;
            w.gfMain = gf;
        }

        // 近距 / 高速逼近：KNN 库被冲浪对手主导，GF 峰远离 HOT/线性。
        // rumble 上 rammer/nano 存活 90%+ 但 APS ~65%——枪打不中直线冲撞。
        double ramBlend = (useAs || usePif) ? 0 : rammerAimBlend(distance, advancingVelocity);
        if (ramBlend > 0) {
            double simpleGf = Math.abs(enemyVelocity) < 1.0 ? 0.0
                    : linearPredictGf(myLocation, enemyLocation, enemyHeading,
                    enemyVelocity, absBearing, mea, bulletSpeed);
            gf = gf * (1 - ramBlend) + simpleGf * ramBlend;
            w.gfMain = gf;
        }

        // 主动子弹阴影：临开火（≤1 tick 枪冷）时在候选角里选「命中分 / 冲浪危险^β」最优
        // 冲撞局面阴影改角只会丢掉必中角，跳过
        boolean shieldMode = ModeBook.shield() && distance >= Params.get().shieldMinDist
                && !PowerSelector.shouldRam(enemyEnergy);
        boolean nearFire = !shieldMode
                && robot.getGunHeat() <= robot.getGunCoolingRate() + 1e-9
                && robot.getEnergy() > power && fireAllowed;
        if (nearFire && ramBlend < 0.35) {
            gf = activeShadowGf(useAs ? kdeAs : kdeMain, gf, myLocation, absBearing,
                    mea, bulletSpeed, power, time);
        }
        waves.add(w);

        double fireAngle = Utils.normalAbsoluteAngle(
                absBearing + gf * mea * enemyLateralDirection);
        if (shieldMode) {
            double intercept = surfing.shieldFireAngle(myLocation, time);
            if (!Double.isNaN(intercept)) {
                fireAngle = intercept;
                power = 0.1;
                bulletSpeed = RcMath.bulletSpeed(power);
                w.speed = bulletSpeed;
                fireAllowed = robot.getEnergy() > 1.0;
            } else {
                fireAngle = absBearing;
                fireAllowed = false;
            }
        }
        robot.setTurnGunRightRadians(
                Utils.normalRelativeAngle(fireAngle - robot.getGunHeadingRadians()));

        if (fireAllowed
                && robot.getGunHeat() == 0
                && robot.getEnergy() > power + 0.05
                && Math.abs(robot.getGunTurnRemainingRadians()) < Math.atan(18 / distance)) {
            Bullet b = robot.setFireBullet(power);
            if (b != null) {
                w.real = true;
                myShots++;
                if (shieldMode) {
                    Surfing.noteShieldShot();
                }
                if (usePif) {
                    pifFired++;
                } else if (useAs) {
                    asFired++;
                }
                if (ramBlend > 0.15) {
                    ramAimShots++;
                }
                // 子弹在下一 turn 移动前、炮管转动前发射：出膛角 = 当前炮管朝向
                surfing.onMyBulletFired(b, myLocation, robot.getGunHeadingRadians(),
                        bulletSpeed, time);
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
                double gfWidth = Math.atan(18 / dist) / mea; // 车身对应的 GF 半宽

                MAIN_DATA.add(w.features, gf, 1);
                AS_DATA.add(w.features, gf, w.real ? 1 : AS_VIRTUAL_WEIGHT);
                logWave(w.features, gf, gfWidth, w.real);

                if (w.real) {
                    double zm = (w.gfMain - gf) / gfWidth;
                    double za = (w.gfAs - gf) / gfWidth;
                    double zp = (w.gfPif - gf) / gfWidth;
                    mainScore = mainScore * SCORE_DECAY + Math.exp(-0.5 * zm * zm);
                    asScore = asScore * SCORE_DECAY + Math.exp(-0.5 * za * za);
                    pifScore = pifScore * SCORE_DECAY + Math.exp(-0.5 * zp * zp);
                    scoreNorm = scoreNorm * SCORE_DECAY + 1;
                    realWaves++;
                }
                it.remove();
            }
        }
    }

    /** 近邻核密度估计的可查询快照：任意 GF 的密度 + 峰值 + 高分候选（主动阴影用）。 */
    private static final class Kde {
        final double[] gf;
        final double[] wgt;
        final double bandwidth;

        Kde(double[] gf, double[] wgt, double bandwidth) {
            this.gf = gf;
            this.wgt = wgt;
            this.bandwidth = bandwidth;
        }

        double density(double x) {
            double score = 0;
            for (int j = 0; j < gf.length; j++) {
                double z = (x - gf[j]) / bandwidth;
                score += wgt[j] * Math.exp(-0.5 * z * z);
            }
            return score;
        }

        double bestGf() {
            double best = 0, bestScore = -1;
            for (double x : gf) {
                double s = density(x);
                if (s > bestScore) {
                    bestScore = s;
                    best = x;
                }
            }
            return best;
        }

        /** 按密度降序取至多 n 个彼此间隔 ≥minGap 的近邻 GF。 */
        List<Double> topCandidates(int n, double minGap) {
            Integer[] idx = new Integer[gf.length];
            final double[] dens = new double[gf.length];
            for (int i = 0; i < gf.length; i++) {
                idx[i] = i;
                dens[i] = density(gf[i]);
            }
            java.util.Arrays.sort(idx, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Double.compare(dens[b], dens[a]);
                }
            });
            List<Double> out = new ArrayList<Double>();
            for (Integer i : idx) {
                boolean dup = false;
                for (double x : out) {
                    if (Math.abs(x - gf[i]) < minGap) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    out.add(gf[i]);
                    if (out.size() >= n) {
                        break;
                    }
                }
            }
            return out;
        }
    }

    /**
     * 加权核密度估计快照。
     * 每个近邻的贡献 = 先验权重 ×（可选）年龄衰减 0.5^(age/halfLife)。
     */
    private static Kde kde(Knn data, double[] query, int k, double bandwidth, double halfLife) {
        if (data.size() == 0) {
            return new Kde(new double[]{0}, new double[]{1}, bandwidth);
        }
        List<Knn.Neighbor> neighbors = data.nearest(query, Math.min(k, data.size()));
        int n = neighbors.size();
        double[] gf = new double[n];
        double[] wgt = new double[n];
        for (int i = 0; i < n; i++) {
            Knn.Neighbor nb = neighbors.get(i);
            gf[i] = nb.entry.value;
            wgt[i] = nb.entry.weight;
            if (halfLife > 0) {
                wgt[i] *= Math.pow(0.5, (data.seq() - nb.entry.seq) / halfLife);
            }
        }
        return new Kde(gf, wgt, bandwidth);
    }

    /**
     * 近距或敌方高速朝我冲时，把瞄准从 KNN 拉向 HOT（静止）/ 线性（运动）。
     * 距离 ≥200 且非冲撞时为 0，不影响中远距 surfer。
     */
    private static double rammerAimBlend(double distance, double advancingVelocity) {
        double byDist = distance < RAMMER_AIM_DIST
                ? RcMath.limit(0, (RAMMER_AIM_DIST - distance) / 80, 1) : 0;
        double byAdv = advancingVelocity > RAMMER_AIM_ADV
                ? RcMath.limit(0, (advancingVelocity - RAMMER_AIM_ADV) / 2.5, 0.7) : 0;
        return Math.max(byDist, byAdv);
    }

    /**
     * 主枪精确预测选角（阶段 3.1）：在 KDE 高分候选 + 匀速滑行 GF 上，
     * 用「敌人保持当前速度/朝向滑行」做子弹到达几何命中校验，
     * 取 density × (0.2 + 0.8×hit) 最高者。对 Tracker/Walls/直线走位收益最大。
     */
    private double refineMainGf(Kde kde, double gfAim, Point2D.Double myLocation,
                                Point2D.Double enemyLocation, double enemyHeading,
                                double enemyVelocity, double absBearing, double mea,
                                double bulletSpeed) {
        List<Double> candidates = kde.topCandidates(8, 0.04);
        if (!candidates.contains(gfAim)) {
            candidates.add(0, gfAim);
        }
        double linGf = linearPredictGf(myLocation, enemyLocation, enemyHeading,
                enemyVelocity, absBearing, mea, bulletSpeed);
        if (Math.abs(linGf) <= 0.99) {
            boolean has = false;
            for (double c : candidates) {
                if (Math.abs(c - linGf) < 0.02) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                candidates.add(linGf);
            }
        }
        // 密度门控：几何命中只在接近 KDE 峰值的候选里做微调，避免匀速假设
        // 带偏振荡/pattern 走位（Cigaret 上曾用 0.2+0.8×hit 掉约 10%）
        double peak = Math.max(1e-9, kde.density(gfAim));
        double aimFloor = 0.6 * peak;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestGf = gfAim;
        for (double gf : candidates) {
            double dens = kde.density(gf);
            if (dens < aimFloor && Math.abs(gf - gfAim) > 1e-9) {
                continue;
            }
            double fireAngle = Utils.normalAbsoluteAngle(
                    absBearing + gf * mea * enemyLateralDirection);
            double hit = geometricHit(myLocation, enemyLocation, enemyHeading,
                    enemyVelocity, fireAngle, bulletSpeed);
            double score = dens * (1 + 0.25 * hit);
            if (score > bestScore) {
                bestScore = score;
                bestGf = gf;
            }
        }
        return bestGf;
    }

    private void recordPif(double latV, double heading, double velocity) {
        double omega = hasEnemyHeading
                ? Utils.normalRelativeAngle(heading - prevEnemyHeading) : 0;
        pifLat[pifHead] = latV;
        pifOmg[pifHead] = omega;
        pifVel[pifHead] = velocity;
        pifHead = (pifHead + 1) % PIF_CAP;
        if (pifSize < PIF_CAP) {
            pifSize++;
        }
        prevEnemyHeading = heading;
        hasEnemyHeading = true;
    }

    private int pifAt(int logical) {
        return (pifHead - pifSize + logical + PIF_CAP) % PIF_CAP;
    }

    /**
     * 模式匹配 Play-It-Forward：在历史 (latV, omega) 窗口里找近邻，把匹配段之后的
     * 速度/转向接到当前状态上积分到子弹到达。样本不足时退回匀速滑行。
     * 只作为第三把虚拟枪；VG 分不领先不会开火。
     */
    private double pifGf(Point2D.Double myLocation, Point2D.Double enemyLocation,
                         double enemyHeading, double enemyVelocity,
                         double absBearing, double mea, double bulletSpeed) {
        int n = pifSize;
        int minFuture = 8;
        if (n < PIF_PAT + minFuture) {
            return linearPredictGf(myLocation, enemyLocation, enemyHeading,
                    enemyVelocity, absBearing, mea, bulletSpeed);
        }
        int q0 = n - PIF_PAT;
        int limit = n - PIF_PAT - minFuture;
        double[] bestD = new double[PIF_K];
        int[] bestI = new int[PIF_K];
        for (int k = 0; k < PIF_K; k++) {
            bestD[k] = Double.POSITIVE_INFINITY;
            bestI[k] = -1;
        }
        int stride = n > 400 ? 2 : 1;
        int oldest = Math.max(0, limit - 600);
        for (int s = oldest; s < limit; s += stride) {
            double d = 0;
            for (int t = 0; t < PIF_PAT; t++) {
                int hi = pifAt(s + t);
                int qi = pifAt(q0 + t);
                double dl = pifLat[hi] - pifLat[qi];
                double dw = pifOmg[hi] - pifOmg[qi];
                d += dl * dl + dw * dw * 16;
            }
            int worst = 0;
            for (int k = 1; k < PIF_K; k++) {
                if (bestD[k] > bestD[worst]) {
                    worst = k;
                }
            }
            if (d < bestD[worst]) {
                bestD[worst] = d;
                bestI[worst] = s;
            }
        }
        double gfSum = 0;
        double wSum = 0;
        int flight = Math.min(80, Math.max(minFuture, (int) Math.ceil(
                myLocation.distance(enemyLocation) / bulletSpeed)));
        for (int k = 0; k < PIF_K; k++) {
            if (bestI[k] < 0) {
                continue;
            }
            Point2D.Double pos = new Point2D.Double(enemyLocation.x, enemyLocation.y);
            double h = enemyHeading;
            double[] vel = {enemyVelocity};
            int fut0 = bestI[k] + PIF_PAT;
            for (int t = 0; t < flight; t++) {
                int fi = fut0 + t;
                if (fi < n) {
                    int idx = pifAt(fi);
                    vel[0] = pifVel[idx];
                    h += pifOmg[idx];
                }
                pos = RcMath.coastStep(pos, h, vel, field);
                if ((t + 1) * bulletSpeed >= myLocation.distance(pos)) {
                    break;
                }
            }
            double offset = Utils.normalRelativeAngle(
                    RcMath.absoluteBearing(myLocation, pos) - absBearing);
            double gf = RcMath.limit(-1, offset / mea * enemyLateralDirection, 1);
            double wgt = 1.0 / (1.0 + bestD[k]);
            gfSum += wgt * gf;
            wSum += wgt;
        }
        if (wSum < 1e-9) {
            return linearPredictGf(myLocation, enemyLocation, enemyHeading,
                    enemyVelocity, absBearing, mea, bulletSpeed);
        }
        return RcMath.limit(-1, gfSum / wSum, 1);
    }

    /** 假设敌人匀速滑行，子弹沿直线飞行时到达处对应的 GF。 */
    private double linearPredictGf(Point2D.Double myLocation, Point2D.Double enemyLocation,
                                   double enemyHeading, double enemyVelocity,
                                   double absBearing, double mea, double bulletSpeed) {
        Point2D.Double ePos = new Point2D.Double(enemyLocation.x, enemyLocation.y);
        double[] eV = {enemyVelocity};
        int ticks = 0;
        while (ticks < 110) {
            ticks++;
            ePos = RcMath.coastStep(ePos, enemyHeading, eV, field);
            if (ticks * bulletSpeed >= myLocation.distance(ePos)) {
                break;
            }
        }
        double offset = Utils.normalRelativeAngle(
                RcMath.absoluteBearing(myLocation, ePos) - absBearing);
        return RcMath.limit(-1, offset / mea * enemyLateralDirection, 1);
    }

    /**
     * 子弹与敌人匀速滑行的几何命中（1=会打中 36×36 车身，0=否）。
     * 仅用于主枪候选排序，不替代 KDE 学习。
     */
    private double geometricHit(Point2D.Double myLocation, Point2D.Double enemyLocation,
                                double enemyHeading, double enemyVelocity,
                                double fireAngle, double bulletSpeed) {
        Point2D.Double ePos = new Point2D.Double(enemyLocation.x, enemyLocation.y);
        double[] eV = {enemyVelocity};
        double halfDiag = 18 * Math.sqrt(2);
        for (int t = 1; t <= 110; t++) {
            ePos = RcMath.coastStep(ePos, enemyHeading, eV, field);
            Point2D.Double bPos = RcMath.project(myLocation, fireAngle, t * bulletSpeed);
            if (bPos.x < -18 || bPos.x > field.x + field.width + 36
                    || bPos.y < -18 || bPos.y > field.y + field.height + 36) {
                return 0;
            }
            if (Math.abs(bPos.x - ePos.x) <= 18 && Math.abs(bPos.y - ePos.y) <= 18) {
                return 1;
            }
            if (myLocation.distance(bPos) > myLocation.distance(ePos) + halfDiag + bulletSpeed) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 主动子弹阴影（阶段 2.3 / 3.3，BeepBoop Aimer 思路）：临开火 tick 在约 20 个候选
     * （KDE top + 密度网格采样 + 有用阴影角）里选 aimScore / danger^β 最优开火 GF。
     * danger 含 50% t±1 重叠阴影；β 随敌我命中率比和功率比缩放。
     */
    private double activeShadowGf(Kde kde, double gfAim, Point2D.Double myLocation,
                                  double absBearing, double mea, double bulletSpeed,
                                  double power, long time) {
        double baseline = surfing.currentPlanDanger(time);
        if (baseline < 0) {
            return gfAim; // 没有在冲的波，阴影无从谈起
        }
        // 中游 surfer 漏分：曾有 70–85% 射击被阴影改角、命中率~14%。
        // 不用硬关阴影（Glacier 上硬关会掉分），改为抬瞄准门限 + 要求危险明显下降。
        double enemyRoll = Surfing.enemyRollingHitRate();
        List<Double> candidates = buildShadowCandidates(kde, gfAim, myLocation,
                absBearing, mea, bulletSpeed, time);
        double beta = Math.pow(
                PowerSelector.ENEMY.rawHitRate() / PowerSelector.MY.rawHitRate()
                        * surfing.lastEnemyPower() / power, 0.25);
        // 命中分下限：阴影再好也不打「几乎不可能命中」的子弹——能量战里白扔一发的
        // 代价（-p 无返还）对紧平衡对手（如 Komarious）是净亏，实测不设门全线回退
        // 敌枪偏弱时抬门限，减少「略降危险就改角」的廉价阴影
        double aimFloorMul = enemyRoll < 0.12 ? 0.55 : 0.42;
        double aimFloor = aimFloorMul * kde.density(gfAim);
        // 敌枪越弱，要求阴影带来的危险降幅越大（硬关在 Glacier 上已验证有害）
        double maxDangerFrac = enemyRoll < 0.10 ? 0.85 : (enemyRoll < 0.12 ? 0.90 : 0.95);
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestGf = gfAim;
        for (double gf : candidates) {
            double aimScore = kde.density(gf);
            if (aimScore < aimFloor && Math.abs(gf - gfAim) > 1e-9) {
                continue;
            }
            double fireAngle = Utils.normalAbsoluteAngle(
                    absBearing + gf * mea * enemyLateralDirection);
            double danger = surfing.dangerAfterHypotheticalShot(
                    myLocation, fireAngle, bulletSpeed, time);
            if (danger < 0) {
                return gfAim;
            }
            if (Math.abs(gf - gfAim) > 1e-9 && danger > baseline * maxDangerFrac) {
                continue;
            }
            double score = aimScore / Math.pow(Math.max(1e-6, danger), beta);
            if (score > bestScore) {
                bestScore = score;
                bestGf = gf;
            }
        }
        if (Math.abs(bestGf - gfAim) > 0.01) {
            Surfing.noteActiveShadowShot();
        }
        return bestGf;
    }

    /**
     * 主动阴影候选：KDE top-12 + 稀疏密度网格（≥0.25×峰值）+ helpful 拦截角，约 16–20 个。
     * 确定性采样；过密网格曾引入低密度角、能量战吃亏。
     */
    private List<Double> buildShadowCandidates(Kde kde, double gfAim,
                                               Point2D.Double myLocation, double absBearing,
                                               double mea, double bulletSpeed, long time) {
        List<Double> candidates = kde.topCandidates(12, 0.03);
        addCandidate(candidates, gfAim, 0.02);
        double peak = Math.max(1e-9, kde.density(gfAim));
        for (int i = 0; i <= 16; i++) {
            double gf = -1.0 + i * (2.0 / 16.0);
            if (kde.density(gf) >= 0.25 * peak) {
                addCandidate(candidates, gf, 0.05);
            }
        }
        for (double angle : surfing.helpfulShadowAngles(myLocation, bulletSpeed, time)) {
            double gf = Utils.normalRelativeAngle(angle - absBearing)
                    / mea * enemyLateralDirection;
            if (Math.abs(gf) < 0.99) {
                addCandidate(candidates, gf, 0.03);
            }
        }
        return candidates;
    }

    private static void addCandidate(List<Double> candidates, double gf, double minGap) {
        for (double x : candidates) {
            if (Math.abs(x - gf) < minGap) {
                return;
            }
        }
        candidates.add(gf);
    }

    /** 离线训练数据：features..., gf, gf半宽, 是否实弹。无 -Drcr.datalog 或无权限时静默关闭。 */
    private static void logWave(double[] f, double gf, double width, boolean real) {
        if (!dataLogInit) {
            dataLogInit = true;
            String path = System.getProperty("pc.datalog");
            if (path != null) {
                try {
                    dataLog = new java.io.PrintWriter(new java.io.BufferedWriter(
                            new java.io.FileWriter(path, true)));
                } catch (Exception denied) {
                    dataLog = null; // 正常参战模式下沙箱会拒绝，安静降级
                }
            }
        }
        if (dataLog == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(110);
        for (double v : f) {
            sb.append(String.format(java.util.Locale.US, "%.4f", v)).append(',');
        }
        sb.append(String.format(java.util.Locale.US, "%.4f,%.4f,", gf, width));
        sb.append(real ? 1 : 0);
        dataLog.println(sb);
    }

    /** battle 结束时冲刷数据文件（Wavelet.onBattleEnded 调用）。 */
    static void closeDataLog() {
        if (dataLog != null) {
            dataLog.flush();
        }
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

}
