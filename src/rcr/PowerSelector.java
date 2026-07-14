package rcr;

import robocode.Rules;

/**
 * 火力选择（阶段 2.2）：期望得分最大化，移植自 BeepBoop 的 BulletPowerSelector
 * （kc.mega.aim，MIT 许可）。
 *
 * 思路：对每档候选功率，假设双方按当前命中率持续对射，
 * - 用每 tick 能量流（开火消耗 / 命中返还 / 被弹伤害）线性估计回合剩余时长；
 * - 用拉格朗日乘子解出「刚好打平能量战」所需的双方命中率，再用二项分布的正态近似
 *   算出实际命中率越过该线的概率 → 本回合胜率；
 * - 期望得分 = 伤害分 ×(1+0.2×胜率) + 60×胜率（存活 50 + last survivor 10），
 *   前 5 回合按 RoboRumble 的百分比口径（含历史累计近似分）取比值，之后取差值；
 * - 另保留：贴身全功率、精确击杀反解、低能量不让出能量领先三条硬规则。
 */
final class PowerSelector {

    // anti-BasicSurfer 功率表：x.45/x.95 下沿利用 BasicSurfer 系对子弹速度处理的 bug
    // （榜上大量机器人基于 BasicSurfer）。命中率上界 >0.2（或第 0 回合）时启用
    private static final double[] CANDIDATE_POWERS_ABS = {
            Math.nextAfter(2.45, -1), 1.95, 1.45, 0.95, 0.65, 0.45, 0.15};
    // 细表：对躲得好的对手（命中率低）做细粒度能量战优化
    private static final double[] CANDIDATE_POWERS = {
            2.99, 2.75, 2.49, 2.3, 2.2, 2.1, 1.99, 1.9, 1.8, 1.7, 1.6, 1.49, 1.4, 1.3, 1.2, 1.1,
            0.99, 0.95, 0.9, 0.85, 0.8, 0.75, 0.7, 0.65, 0.6, 0.55, 0.49, 0.45, 0.4, 0.35, 0.3,
            0.25, 0.2, 0.175, 0.15, 0.125, 0.1};
    private static final double ABS_HIT_RATE_GATE = 0.2;
    private static final double FULL_POWER_DISTANCE = 140; // 沿用 1.4 实测值（BeepBoop 用 100）
    private static final double MIN_ENERGY = 0.05;
    private static final double POINTS_FOR_WIN = 60;
    private static final double BULLET_DAMAGE_BONUS = 0.2;

    static final Tracker MY = new Tracker();
    static final Tracker ENEMY = new Tracker();
    private static final int[] POWER_HIST = new int[6]; // <0.5,<1,<1.5,<2,<2.5,≥2.5

    private PowerSelector() {
    }

    /** 一方的命中率 / 伤害 / 近似得分跟踪（静态，跨回合累计）。 */
    static final class Tracker {
        private static final int PRIOR_HITS = 1;
        private static final int PRIOR_SHOTS = 12;

        private int shotsPassed;      // 已结算的射击（命中或飞过）
        private int hits;
        private double damageThisRound;
        private double totalDamage;
        private double meaSum;        // 各次射击 MEA 累计（命中率按逃逸角折算用）
        private double approxScore;   // 近似 Robocode 总分（伤害 + 胜场加成）

        /** 一发结算：hit 表示命中对方车身（子弹对撞/飞过均算 miss）。 */
        void shotPassed(double power, boolean hit) {
            if (this == MY) {
                POWER_HIST[Math.min(5, (int) (power * 2))]++;
            }
            shotsPassed++;
            meaSum += RcMath.maxEscapeAngle(RcMath.bulletSpeed(power));
            if (hit) {
                hits++;
                double dmg = Rules.getBulletDamage(power);
                damageThisRound += dmg;
                totalDamage += dmg;
            }
        }

        /** 折算到候选功率逃逸角的命中率估计，带早期先验 1/12。 */
        double estimateHitRate(double power) {
            double hitRate = (hits + PRIOR_HITS) / (double) (shotsPassed + PRIOR_SHOTS);
            double corr = shotsPassed == 0 ? 1
                    : (meaSum / shotsPassed)
                            / RcMath.maxEscapeAngle(RcMath.bulletSpeed(power));
            return RcMath.limit(0.001, hitRate * corr, 0.999);
        }

        /** 命中率置信上界（启发式 CI，样本少时更宽）。 */
        double maxHitRate() {
            double p = hits / Math.max(1.0, shotsPassed);
            double z = shotsPassed < 50 ? 1.282 : 0.842;
            return Math.min(1, p + z * Math.sqrt(p * (1 - p) / Math.max(1, shotsPassed)));
        }

        /** 未折算原始命中率（主动阴影 / flattener 门控用），下限 0.001。 */
        double rawHitRate() {
            return Math.max(0.001, hits / Math.max(1.0, shotsPassed));
        }

        int shotsPassed() {
            return shotsPassed;
        }

        int hits() {
            return hits;
        }

        private void roundEnd(boolean won) {
            approxScore += damageThisRound * (won ? 1 + BULLET_DAMAGE_BONUS : 1)
                    + (won ? POINTS_FOR_WIN : 0);
            damageThisRound = 0;
        }
    }

    /** 回合结束结算（近似：我死了就当对面拿到存活分）。 */
    static void roundEnd(boolean iWon, boolean iDied) {
        MY.roundEnd(iWon);
        ENEMY.roundEnd(iDied);
    }

    static String stats() {
        StringBuilder h = new StringBuilder();
        for (int c : POWER_HIST) {
            h.append(h.length() == 0 ? "" : "/").append(c);
        }
        return String.format("pwrMy=%d/%d pwrEn=%d/%d dmgMy=%.0f dmgEn=%.0f powerHist=%s",
                MY.hits, MY.shotsPassed, ENEMY.hits, ENEMY.shotsPassed,
                MY.totalDamage, ENEMY.totalDamage, h);
    }

    /**
     * 选功率。可能返回 <0.1，含义是「这个局面不该开火」（低能量护领先），调用方跳过开火。
     */
    static double choosePower(double myEnergy, double enemyEnergy, double distance,
                              double lastEnemyPower, int roundNum, double coolingRate) {
        Profile enemy = new Profile(
                Math.min(Math.max(0, enemyEnergy), lastEnemyPower), false, coolingRate);
        double minimumKillPower = enemyEnergy > 4 ? (enemyEnergy + 2) / 6 : enemyEnergy / 4;
        // 打得中的对手（命中率上界 >0.2）用 ABS 粗表：专攻 BasicSurfer 系 bug 且避免
        // 模型误差驱动的细碎降档；躲得好的对手用细表做能量战优化
        double[] candidates = roundNum == 0 || MY.maxHitRate() > ABS_HIT_RATE_GATE
                ? CANDIDATE_POWERS_ABS : CANDIDATE_POWERS;

        double bestPower;
        if (distance < FULL_POWER_DISTANCE) {
            bestPower = 2.95; // 这个距离基本必中，模型不用跑
        } else {
            bestPower = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (double power : candidates) {
                double score = scorePower(power, enemy, myEnergy, enemyEnergy,
                        roundNum <= 4, coolingRate);
                if (score > bestScore) {
                    bestScore = score;
                    bestPower = power;
                }
            }
        }

        bestPower = Math.max(0.1, Math.min(bestPower, minimumKillPower)); // 正好打死即可
        bestPower = Math.min(bestPower, myEnergy - MIN_ENERGY);
        if (distance < FULL_POWER_DISTANCE) {
            bestPower = Math.max(bestPower, 0.1);
        } else if (myEnergy < 5 && myEnergy > enemyEnergy) {
            // 低能量时不打让出能量领先的子弹（拖住等对手先垮）
            bestPower = Math.min(myEnergy - enemyEnergy - 0.11, bestPower);
        }
        return bestPower;
    }

    /** 候选功率的期望得分：前 5 回合用百分比口径（RoboRumble APS），之后用差值。 */
    private static double scorePower(double power, Profile enemy, double myEnergy,
                                     double enemyEnergy, boolean ratio, double coolingRate) {
        Profile mine = new Profile(power, true, coolingRate);
        double ticksLeft = estimateTicksLeft(mine, enemy, mine.hitRate, enemy.hitRate,
                myEnergy, enemyEnergy);
        double myDamage = MY.damageThisRound + mine.damage * mine.hitRate * ticksLeft;
        double enemyDamage = ENEMY.damageThisRound + enemy.damage * enemy.hitRate * ticksLeft;
        double winProb = estimateWinProb(mine, enemy, myEnergy, enemyEnergy);
        double myScore = myDamage * (1 + BULLET_DAMAGE_BONUS * winProb)
                + POINTS_FOR_WIN * winProb;
        double enemyScore = enemyDamage * (1 + BULLET_DAMAGE_BONUS * (1 - winProb))
                + POINTS_FOR_WIN * (1 - winProb);
        if (ratio) {
            double myTotal = MY.approxScore + myScore;
            return myTotal / (myTotal + ENEMY.approxScore + enemyScore);
        }
        return myScore - enemyScore;
    }

    /**
     * 本回合胜率：先用拉格朗日乘子求「打平能量战、且离双方当前命中率平方误差最小」的
     * 临界命中率组合，再用正态近似二项分布算双方实际越线的概率，取几何平均。
     */
    private static double estimateWinProb(Profile mine, Profile enemy,
                                          double myEnergy, double enemyEnergy) {
        double a = myEnergy * mine.damage + enemyEnergy * mine.gain;
        double b = enemyEnergy * enemy.damage + myEnergy * enemy.gain;
        double lambda = enemyEnergy * mine.loss - myEnergy * enemy.loss
                - mine.hitRate * a + enemy.hitRate * b;
        lambda /= a * a + b * b;
        double myNeededHR = lambda * a + mine.hitRate;
        double enemyNeededHR = -lambda * b + enemy.hitRate;
        if (myNeededHR <= 0 || enemyNeededHR >= 1) {
            return 1;
        }
        if (myNeededHR >= 1 || enemyNeededHR <= 0) {
            return 0;
        }
        double ticksLeft = estimateTicksLeft(mine, enemy, myNeededHR, enemyNeededHR,
                myEnergy, enemyEnergy);
        return Math.sqrt((1 - mine.cdf(myNeededHR, ticksLeft))
                * enemy.cdf(enemyNeededHR, ticksLeft));
    }

    /** 双方按给定命中率持续对射，估计谁先打光能量、还剩多少 tick。 */
    private static double estimateTicksLeft(Profile mine, Profile enemy,
                                            double myHitRate, double enemyHitRate,
                                            double myEnergy, double enemyEnergy) {
        double myNetLoss = Math.max(0.0001,
                enemy.damage * enemyHitRate - mine.gain * myHitRate + mine.loss);
        double enemyNetLoss = Math.max(0.0001,
                mine.damage * myHitRate - enemy.gain * enemyHitRate + enemy.loss);
        return Math.min(myEnergy / myNetLoss, enemyEnergy / enemyNetLoss);
    }

    /** 单侧火力档的每 tick 能量流参数。 */
    private static final class Profile {
        final int cooldown;    // 开火间隔（tick）
        final double damage;   // 命中时每 tick 期望伤害
        final double gain;     // 命中时每 tick 能量返还
        final double loss;     // 每 tick 开火消耗
        final double hitRate;

        Profile(double power, boolean mine, double coolingRate) {
            cooldown = (int) ((1 + power / 5) / coolingRate);
            damage = Rules.getBulletDamage(power) / cooldown;
            gain = 3 * power / cooldown;
            loss = power / cooldown;
            // 假设 (1) 对手命中率 <15%，(2) 我们不比对手差——开局样本少时稳住估计
            double enemyHR = Math.min(0.15, ENEMY.estimateHitRate(power));
            hitRate = mine ? Math.max(enemyHR, MY.estimateHitRate(power)) : enemyHR;
        }

        /** P(打 ticks 后实际命中率 ≤ targetHitRate)，二项分布的正态近似。 */
        double cdf(double targetHitRate, double ticks) {
            double shots = ticks / cooldown;
            double expectedHits = hitRate * shots;
            double targetHits = targetHitRate * shots;
            double variance = Math.max(1e-9, hitRate * (1 - hitRate) * shots);
            return phi((targetHits - expectedHits) / Math.sqrt(variance));
        }
    }

    private static double phi(double x) {
        return (1 + erf(x / Math.sqrt(2))) / 2;
    }

    // https://en.wikipedia.org/wiki/Error_function#Approximation_with_elementary_functions
    private static double erf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = 1 - t * Math.exp(-z * z - 1.26551223
                + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223
                + t * 0.17087277)))))))));
        return z > 0 ? ans : -ans;
    }
}
