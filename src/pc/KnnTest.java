package pc;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * kd-tree 与线性扫描交叉验证。运行：java -cp out\classes pc.KnnTest
 */
public final class KnnTest {

    public static void main(String[] args) {
        double[] w = {5.290, 0.841, 2.623, 0.573, 1.096, 1.342, 0.980, 0.621};
        Random rnd = new Random(7);
        int failures = 0;

        // 1) 增长期（kd-tree）：超过 LINEAR_LIMIT、未满容
        Knn knn = new Knn(w, 5000);
        for (int i = 0; i < 3000; i++) {
            knn.add(randPoint(rnd, w.length), rnd.nextDouble() * 2 - 1, 1);
            if (i % 200 == 199) {
                failures += check(knn, rnd, w.length, 20);
            }
        }
        if (!knn.usingTree()) {
            System.out.println("expected tree mode during growth");
            failures++;
        }
        for (int q = 0; q < 40; q++) {
            failures += check(knn, rnd, w.length, 50);
        }

        // 2) 环形覆盖期：应回退线性，结果仍正确
        for (int i = 0; i < 2500; i++) {
            knn.add(randPoint(rnd, w.length), rnd.nextDouble() * 2 - 1, 1);
        }
        if (knn.usingTree()) {
            System.out.println("expected linear mode after ring");
            failures++;
        }
        for (int q = 0; q < 20; q++) {
            failures += check(knn, rnd, w.length, 50);
        }

        System.out.println("rebuilds=" + knn.rebuilds() + " size=" + knn.size()
                + " failures=" + failures);
        System.out.println(failures == 0 ? "KNN TEST PASSED" : "KNN TEST FAILED");
    }

    private static int check(Knn knn, Random rnd, int dims, int k) {
        double[] q = randPoint(rnd, dims);
        List<Knn.Neighbor> tree = knn.nearest(q, k);
        List<Knn.Neighbor> linear = knn.nearestLinear(q, k);
        if (tree.size() != linear.size()) {
            System.out.println("SIZE tree=" + tree.size() + " linear=" + linear.size());
            return 1;
        }
        tree.sort(byDistThenSeq());
        linear.sort(byDistThenSeq());
        Set<Long> treeSeq = new HashSet<Long>();
        Set<Long> linSeq = new HashSet<Long>();
        for (int i = 0; i < tree.size(); i++) {
            treeSeq.add(tree.get(i).entry.seq);
            linSeq.add(linear.get(i).entry.seq);
            if (Math.abs(tree.get(i).dist - linear.get(i).dist) > 1e-9) {
                System.out.println("DIST mismatch i=" + i
                        + " tree=" + tree.get(i).dist + " lin=" + linear.get(i).dist);
                return 1;
            }
        }
        if (!treeSeq.equals(linSeq)) {
            System.out.println("SET mismatch tree=" + treeSeq + " lin=" + linSeq);
            return 1;
        }
        return 0;
    }

    private static Comparator<Knn.Neighbor> byDistThenSeq() {
        return new Comparator<Knn.Neighbor>() {
            @Override
            public int compare(Knn.Neighbor a, Knn.Neighbor b) {
                int c = Double.compare(a.dist, b.dist);
                if (c != 0) {
                    return c;
                }
                return Long.compare(b.entry.seq, a.entry.seq);
            }
        };
    }

    private static double[] randPoint(Random rnd, int dims) {
        double[] p = new double[dims];
        for (int i = 0; i < dims; i++) {
            p[i] = rnd.nextDouble();
        }
        return p;
    }
}
