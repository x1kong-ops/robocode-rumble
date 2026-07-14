package pc;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;

import robocode.AdvancedRobot;
import robocode.BattleEndedEvent;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.BulletMissedEvent;
import robocode.DeathEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.HitWallEvent;
import robocode.RobocodeFileOutputStream;
import robocode.RoundEndedEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.SkippedTurnEvent;
import robocode.WinEvent;
import robocode.util.Utils;

/**
 * Wavelet —— 1v1 主力机器人（pc.Wavelet dev）。
 * True Surfing（两波精确预测 + KNN 危险密度/学得嵌入 + 被动/主动 shadows + flattener）
 * + KNN(DC) 双枪（学得嵌入 + 主动阴影改角）
 * + 期望得分最大化火力（BeepBoop 模型）。
 * 架构参考 Diamond / BeepBoop；健康指标写入 stats.txt。
 */
public class Wavelet extends AdvancedRobot {

    private static int skippedTurns; // 整场 battle 累计
    private static int wallHits;     // 冲浪 bot 撞墙应接近 0，健康指标

    private Surfing surfing;
    private KnnGun gun;
    private double enemyEnergy = 100;
    private Snapshot prev;
    private int myLateralDirection = 1;
    private boolean roundWon;
    private boolean roundDied;

    @Override
    public void run() {
        setBodyColor(new Color(28, 28, 40));
        setGunColor(new Color(90, 170, 255));
        setRadarColor(Color.WHITE);
        setBulletColor(new Color(255, 210, 90));
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        surfing = new Surfing(this);
        gun = new KnnGun(this, surfing);

        setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
        while (true) {
            if (getRadarTurnRemainingRadians() == 0) {
                setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            }
            execute();
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        Point2D.Double myLocation = new Point2D.Double(getX(), getY());
        double absBearingToEnemy = getHeadingRadians() + e.getBearingRadians();
        Point2D.Double enemyLocation =
                RcMath.project(myLocation, absBearingToEnemy, e.getDistance());

        // 雷达窄锁（factor 2）
        setTurnRadarRightRadians(2 * Utils.normalRelativeAngle(
                absBearingToEnemy - getRadarHeadingRadians()));

        // 我相对敌人的横向方向（粘滞，速度过小时保持原值）
        double absBearingEnemyToMe = Utils.normalAbsoluteAngle(absBearingToEnemy + Math.PI);
        double myLateralVelocity =
                getVelocity() * Math.sin(getHeadingRadians() - absBearingEnemyToMe);
        // >0 = 朝敌人逼近（与枪侧 advancing 口径一致）
        double myAdvancingVelocity =
                -getVelocity() * Math.cos(getHeadingRadians() - absBearingEnemyToMe);
        if (Math.abs(myLateralVelocity) > 0.1) {
            myLateralDirection = myLateralVelocity > 0 ? 1 : -1;
        }
        Snapshot cur = new Snapshot(getTime(), myLocation, enemyLocation,
                myLateralDirection, absBearingEnemyToMe,
                myLateralVelocity, myAdvancingVelocity);

        // 开火检测：能量下降 (0.09, 3.01) 视为开火（事件优先级保证扫描前已处理 HitByBullet 等）
        double drop = enemyEnergy - e.getEnergy();
        double firedPower = drop > 0.09 && drop < 3.01 ? drop : -1;
        enemyEnergy = e.getEnergy();

        surfing.onScan(cur, prev, firedPower, e.getEnergy());
        gun.onScan(myLocation, enemyLocation, e.getVelocity(), e.getHeadingRadians(),
                e.getEnergy(), getTime());

        prev = cur;
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        enemyEnergy += 3 * e.getBullet().getPower(); // 命中返还能量
        surfing.onBulletContact(
                new Point2D.Double(e.getBullet().getX(), e.getBullet().getY()),
                e.getBullet().getVelocity(), e.getBullet().getPower(), true, getTime());
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        surfing.onBulletContact(
                new Point2D.Double(e.getHitBullet().getX(), e.getHitBullet().getY()),
                e.getHitBullet().getVelocity(), e.getHitBullet().getPower(), false, getTime());
        surfing.onMyBulletDeath(e.getBullet(), getTime());
        PowerSelector.MY.shotPassed(e.getBullet().getPower(), false);
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        enemyEnergy -= Rules.getBulletDamage(e.getBullet().getPower());
        KnnGun.onMyBulletHit();
        surfing.onMyBulletDeath(e.getBullet(), getTime());
        PowerSelector.MY.shotPassed(e.getBullet().getPower(), true);
    }

    @Override
    public void onBulletMissed(BulletMissedEvent e) {
        surfing.onMyBulletDeath(e.getBullet(), getTime());
        PowerSelector.MY.shotPassed(e.getBullet().getPower(), false);
    }

    @Override
    public void onWin(WinEvent e) {
        roundWon = true;
    }

    @Override
    public void onDeath(DeathEvent e) {
        roundDied = true;
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        enemyEnergy -= 0.6;
    }

    @Override
    public void onSkippedTurn(SkippedTurnEvent e) {
        skippedTurns++;
        out.println("SKIPPED TURN @" + getTime() + " (battle total: " + skippedTurns + ")");
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        wallHits++;
        out.println("HIT WALL @" + getTime() + " (battle total: " + wallHits + ")");
    }

    @Override
    public void onRoundEnded(RoundEndedEvent e) {
        PowerSelector.roundEnd(roundWon, roundDied);
        out.println("round " + (getRoundNum() + 1) + "/" + getNumRounds()
                + " | knn data: " + KnnGun.dataSize()
                + " | " + KnnGun.gunStats()
                + " | " + PowerSelector.stats()
                + " | skipped turns (battle): " + skippedTurns);
    }

    /** 无头模式看不到 robot console，把关键健康指标写进数据文件供脚本检查。 */
    @Override
    public void onBattleEnded(BattleEndedEvent e) {
        try {
            java.io.PrintStream ps = new java.io.PrintStream(
                    new RobocodeFileOutputStream(getDataFile("stats.txt")), false, "UTF-8");
            ps.println("skippedTurns=" + skippedTurns);
            ps.println("wallHits=" + wallHits);
            ps.println("knnData=" + KnnGun.dataSize());
            ps.println(KnnGun.gunStats().replace(' ', '\n'));
            ps.println(Surfing.surfStats().replace(' ', '\n'));
            ps.println(PowerSelector.stats().replace(' ', '\n'));
            ps.close();
        } catch (Exception ignored) {
            // 统计写不出去不影响对战
        }
        KnnGun.closeDataLog();
        Surfing.closeDataLog();
    }

    @Override
    public void onPaint(Graphics2D g) {
        if (surfing != null) {
            surfing.paint(g);
        }
    }
}
