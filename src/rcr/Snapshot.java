package rcr;

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
    /** 我相对敌人的横向速度绝对值（冲浪统计分段用）。 */
    final double myAbsLateralVelocity;

    Snapshot(long time, Point2D.Double myLocation, Point2D.Double enemyLocation,
             int myLateralDirection, double absBearingEnemyToMe, double myAbsLateralVelocity) {
        this.time = time;
        this.myLocation = myLocation;
        this.enemyLocation = enemyLocation;
        this.myLateralDirection = myLateralDirection;
        this.absBearingEnemyToMe = absBearingEnemyToMe;
        this.myAbsLateralVelocity = myAbsLateralVelocity;
    }
}
