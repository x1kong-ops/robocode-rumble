package pc;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/** 几何与 Robocode 物理常用函数。 */
final class RcMath {

    private RcMath() {
    }

    /** 从 from 指向 to 的绝对方位角（Robocode 约定：0 = 北，顺时针为正）。 */
    static double absoluteBearing(Point2D.Double from, Point2D.Double to) {
        return Math.atan2(to.x - from.x, to.y - from.y);
    }

    static Point2D.Double project(Point2D.Double src, double angle, double dist) {
        return new Point2D.Double(src.x + Math.sin(angle) * dist, src.y + Math.cos(angle) * dist);
    }

    static double limit(double min, double v, double max) {
        return Math.max(min, Math.min(max, v));
    }

    static double bulletSpeed(double power) {
        return 20 - 3 * power;
    }

    static double maxEscapeAngle(double bulletSpeed) {
        return Math.asin(8.0 / bulletSpeed);
    }

    /**
     * 沿 orientation 方向旋转 angle，直到从 pos 向前投影 stick 距离后仍在场内。
     * 走位和冲浪预测必须共用此函数，保证预测与实际行为一致。
     */
    static double wallSmoothing(Rectangle2D.Double field, Point2D.Double pos, double angle,
                                int orientation, double stick) {
        int guard = 0;
        while (!field.contains(project(pos, angle, stick)) && guard++ < 200) {
            angle += orientation * 0.05;
        }
        return angle;
    }

    /**
     * 敌人匀速滑行一步（保持 heading；撞墙贴边并速度清零）。
     * velocityInOut[0] 为当前速度，可能被改写为 0。
     */
    static Point2D.Double coastStep(Point2D.Double pos, double heading, double[] velocityInOut,
                                    Rectangle2D.Double field) {
        double v = velocityInOut[0];
        double x = pos.x + Math.sin(heading) * v;
        double y = pos.y + Math.cos(heading) * v;
        if (!field.contains(x, y)) {
            x = limit(field.x, x, field.x + field.width);
            y = limit(field.y, y, field.y + field.height);
            velocityInOut[0] = 0;
        }
        return new Point2D.Double(x, y);
    }

    /** 反解「造成至少 damage 点伤害」所需的最小子弹功率（钳位到 [0.1, 3]）。 */
    static double powerToDealDamage(double damage) {
        if (damage <= 0) {
            return 0.1;
        }
        if (damage <= 4) {
            return limit(0.1, damage / 4, 3);
        }
        return limit(0.1, (damage + 2) / 6, 3);
    }
}
