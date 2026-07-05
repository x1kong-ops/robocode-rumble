package rcr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 线性扫描 KNN 存储（加权欧氏距离）。
 * 阶段 0 用线性扫描保证正确性：35 回合约 4 万条数据，单次查询远低于 1ms。
 * 之后如 profiling 表明需要，可在保持接口不变的前提下换成 kd-tree。
 */
final class Knn {

    static final class Entry {
        final double[] point;
        final double value; // GuessFactor

        Entry(double[] point, double value) {
            this.point = point;
            this.value = value;
        }
    }

    private static final class Neighbor {
        final Entry entry;
        final double dist;

        Neighbor(Entry entry, double dist) {
            this.entry = entry;
            this.dist = dist;
        }
    }

    private final double[] weights;
    private final int capacity;
    private final List<Entry> entries = new ArrayList<Entry>();

    Knn(double[] weights, int capacity) {
        this.weights = weights;
        this.capacity = capacity;
    }

    void add(double[] point, double value) {
        if (entries.size() < capacity) {
            entries.add(new Entry(point, value));
        }
    }

    int size() {
        return entries.size();
    }

    /** 返回 k 个加权距离最近的样本（顺序不保证）。 */
    List<Entry> nearest(double[] query, int k) {
        PriorityQueue<Neighbor> heap = new PriorityQueue<Neighbor>(Math.max(1, k),
                new Comparator<Neighbor>() {
                    @Override
                    public int compare(Neighbor a, Neighbor b) {
                        return Double.compare(b.dist, a.dist); // 最大堆
                    }
                });
        for (Entry e : entries) {
            double d = 0;
            for (int i = 0; i < weights.length; i++) {
                double z = weights[i] * (query[i] - e.point[i]);
                d += z * z;
            }
            if (heap.size() < k) {
                heap.add(new Neighbor(e, d));
            } else if (d < heap.peek().dist) {
                heap.poll();
                heap.add(new Neighbor(e, d));
            }
        }
        List<Entry> out = new ArrayList<Entry>(heap.size());
        for (Neighbor n : heap) {
            out.add(n.entry);
        }
        return out;
    }
}
