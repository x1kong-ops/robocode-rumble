package pc;

import java.awt.geom.Point2D;
import java.util.Random;

/**
 * 开发期自检（非机器人代码）：用暴力采样交叉验证精确交点例程。
 * 运行：java -cp out\classes pc.GeomTest
 */
public final class GeomTest {

    public static void main(String[] args) {
        Random rnd = new Random(42);
        int failures = 0;
        int nonEmpty = 0;
        for (int trial = 0; trial < 20000; trial++) {
            Surfing.EnemyWave w = new Surfing.EnemyWave();
            w.origin = new Point2D.Double(0, 0);
            w.directAngle = rnd.nextDouble() * 2 * Math.PI - Math.PI;
            double speed = 11 + rnd.nextDouble() * 8.7;
            double dist = 60 + rnd.nextDouble() * 800;
            double angle = rnd.nextDouble() * 2 * Math.PI;
            Point2D.Double center = new Point2D.Double(
                    Math.sin(angle) * dist, Math.cos(angle) * dist);
            // 让圆环带随机落在方形附近（含完全不相交的情形）
            double rInner = Math.max(0, dist - 40 + rnd.nextDouble() * 80);
            double rOuter = rInner + speed;

            double[] fast = Surfing.annulusSquareOffsets(w, rInner, rOuter, center);
            double[] brute = bruteForce(w, rInner, rOuter, center);

            if (fast == null && brute == null) {
                continue;
            }
            nonEmpty++;
            // 采样分辨率有限：边界附近允许小误差
            if (fast == null || brute == null
                    || Math.abs(fast[0] - brute[0]) > 0.02
                    || Math.abs(fast[1] - brute[1]) > 0.02) {
                failures++;
                if (failures <= 5) {
                    System.out.println("MISMATCH dist=" + dist + " rInner=" + rInner
                            + " fast=" + str(fast) + " brute=" + str(brute));
                }
            }
        }
        System.out.println("non-empty cases: " + nonEmpty + ", failures: " + failures);
        System.out.println(failures == 0 ? "GEOM TEST PASSED" : "GEOM TEST FAILED");
    }

    /** 在方形内密集采样，统计落在圆环带内的点的角度范围（同样以中心方位为基准防回绕）。 */
    private static double[] bruteForce(Surfing.EnemyWave w, double rInner, double rOuter,
                                       Point2D.Double center) {
        double centerBearing = Math.atan2(center.x - w.origin.x, center.y - w.origin.y);
        double baseOffset = robocode.util.Utils.normalRelativeAngle(centerBearing - w.directAngle);
        double minOff = Double.POSITIVE_INFINITY;
        double maxOff = Double.NEGATIVE_INFINITY;
        boolean any = false;
        int n = 400;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n; j++) {
                double x = center.x - 18 + 36.0 * i / n;
                double y = center.y - 18 + 36.0 * j / n;
                double d = Point2D.distance(w.origin.x, w.origin.y, x, y);
                if (d >= rInner && d <= rOuter) {
                    any = true;
                    double off = baseOffset + robocode.util.Utils.normalRelativeAngle(
                            Math.atan2(x - w.origin.x, y - w.origin.y) - centerBearing);
                    minOff = Math.min(minOff, off);
                    maxOff = Math.max(maxOff, off);
                }
            }
        }
        return any ? new double[]{minOff, maxOff} : null;
    }

    private static String str(double[] a) {
        return a == null ? "null" : "[" + a[0] + ", " + a[1] + "]";
    }
}
