package rcr;

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
}
