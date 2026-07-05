package rcr;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Random;

import robocode.util.Utils;

/**
 * bullet shadow 几何的暴力自检（开发用，不参战）。
 *
 * 对随机的敌波 × 我方子弹组合：
 * - 解析解：Surfing.shadowIntervals 给出的 GF 阴影区间；
 * - 暴力解：在 GF ∈ [-1,1] 网格上放假想敌方子弹，逐 tick 用引擎语义
 *   （双方本 tick 的位移线段是否相交，Line2D.linesIntersect）判断是否对撞。
 * 两者必须一致（区间边界附近留网格容差）。
 *
 * 运行：java -cp out\classes;robocode.jar rcr.ShadowTest
 */
public final class ShadowTest {

    private static final double FIELD_W = 800, FIELD_H = 600;

    public static void main(String[] args) {
        Random rnd = new Random(20260705);
        int cases = 0, withShadow = 0, mismatches = 0;
        for (int iter = 0; iter < 4000; iter++) {
            Surfing.EnemyWave w = new Surfing.EnemyWave();
            w.origin = new Point2D.Double(20 + rnd.nextDouble() * (FIELD_W - 40),
                    20 + rnd.nextDouble() * (FIELD_H - 40));
            w.fireTime = rnd.nextInt(3);
            w.power = 0.1 + rnd.nextDouble() * 2.9;
            w.speed = RcMath.bulletSpeed(w.power);
            w.directAngle = rnd.nextDouble() * 2 * Math.PI - Math.PI;
            w.direction = rnd.nextBoolean() ? 1 : -1;

            Point2D.Double bo = new Point2D.Double(20 + rnd.nextDouble() * (FIELD_W - 40),
                    20 + rnd.nextDouble() * (FIELD_H - 40));
            if (bo.distance(w.origin) < 60) {
                continue; // 贴脸对撞的退化布局不测
            }
            // 子弹大致朝波源方向 ±60°，保证有相交机会
            double bAngle = RcMath.absoluteBearing(bo, w.origin)
                    + (rnd.nextDouble() - 0.5) * Math.PI * 2 / 3;
            double bPower = 0.1 + rnd.nextDouble() * 2.9;
            double bSpeed = RcMath.bulletSpeed(bPower);
            long bFire = rnd.nextInt(3);

            List<double[]> shadows = Surfing.shadowIntervals(
                    w, bo, bAngle, bSpeed, bFire, FIELD_W, FIELD_H);
            cases++;
            if (!shadows.isEmpty()) {
                withShadow++;
            }

            double mea = RcMath.maxEscapeAngle(w.speed);
            double step = 0.002, tol = 0.006;
            for (double gf = -1 + step; gf < 1 - step; gf += step) {
                boolean collided = bruteCollide(w, gf, mea, bo, bAngle, bSpeed, bFire);
                boolean inShadow = false, nearEdge = false;
                for (double[] s : shadows) {
                    if (gf >= s[0] - tol && gf <= s[1] + tol) {
                        inShadow = true;
                    }
                    if (Math.abs(gf - s[0]) < tol || Math.abs(gf - s[1]) < tol) {
                        nearEdge = true;
                    }
                }
                // GF 钳位边界（±1 附近）不追究：区间被 clamp 截断属预期
                boolean nearClamp = Math.abs(gf) > 1 - 0.02;
                if (collided != inShadow && !nearEdge && !nearClamp) {
                    mismatches++;
                    if (mismatches <= 5) {
                        System.out.println("MISMATCH gf=" + gf + " collided=" + collided
                                + " inShadow=" + inShadow + " iter=" + iter);
                    }
                }
            }
        }
        System.out.println("cases: " + cases + ", with shadow: " + withShadow
                + ", mismatches: " + mismatches);
        System.out.println(mismatches == 0 ? "SHADOW TEST PASSED" : "SHADOW TEST FAILED");
    }

    /** 引擎语义暴力模拟：GF 处的敌方子弹与我方子弹是否会对撞。 */
    private static boolean bruteCollide(Surfing.EnemyWave w, double gf, double mea,
                                        Point2D.Double bo, double bAngle, double bSpeed, long bFire) {
        double phi = Utils.normalAbsoluteAngle(w.directAngle + gf * mea * w.direction);
        long u = Math.max(bFire, w.fireTime);
        for (int guard = 0; guard < 150; guard++) {
            u++;
            Point2D.Double m1 = RcMath.project(bo, bAngle, (u - 1 - bFire) * bSpeed);
            Point2D.Double m2 = RcMath.project(bo, bAngle, (u - bFire) * bSpeed);
            double rOut = (u - w.fireTime) * w.speed;
            if (rOut > 0) {
                double rIn = Math.max(0, rOut - w.speed);
                Point2D.Double e1 = RcMath.project(w.origin, phi, rIn);
                Point2D.Double e2 = RcMath.project(w.origin, phi, rOut);
                if (Line2D.linesIntersect(m1.x, m1.y, m2.x, m2.y, e1.x, e1.y, e2.x, e2.y)) {
                    return true;
                }
            }
            if (m2.x < 0 || m2.x > FIELD_W || m2.y < 0 || m2.y > FIELD_H) {
                return false; // 我方子弹出界死亡
            }
        }
        return false;
    }
}
