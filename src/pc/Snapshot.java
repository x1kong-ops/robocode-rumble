package pc;

import java.awt.geom.Point2D;

/** 某一 tick 双方状态快照。敌波回溯需要 t-1 时刻的数据。 */
final class Snapshot {
    final long time;
    final Point2D.Double myLocation;
    final Point2D.Double enemyLocation;
    /** 我相对敌人的横向移动方向（±1，速度接近 0 时保持上一次的值）。 */
    final int myLateralDirection;
    /** 敌 → 我 的绝对方位角，即敌波的 GF0 基准角。 */
    final double absBearingEnemyToMe;
    /** 我相对敌人的横向速度绝对值（兼容旧分段逻辑 / 诊断）。 */
    final double myAbsLateralVelocity;
    /** 有符号横向速度（相对敌→我方位）。 */
    final double myLateralVelocity;
    /** 有符号接近速度：>0 表示朝敌人逼近。 */
    final double myAdvancingVelocity;

    Snapshot(long time, Point2D.Double myLocation, Point2D.Double enemyLocation,
             int myLateralDirection, double absBearingEnemyToMe,
             double myLateralVelocity, double myAdvancingVelocity) {
        this.time = time;
        this.myLocation = myLocation;
        this.enemyLocation = enemyLocation;
        this.myLateralDirection = myLateralDirection;
        this.absBearingEnemyToMe = absBearingEnemyToMe;
        this.myLateralVelocity = myLateralVelocity;
        this.myAbsLateralVelocity = Math.abs(myLateralVelocity);
        this.myAdvancingVelocity = myAdvancingVelocity;
    }
}
