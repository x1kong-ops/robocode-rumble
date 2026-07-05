package rcr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 线性扫描 KNN 存储（加权欧氏距离）。
 * - 每条样本带先验权重（开火波 vs 虚拟波）和插入序号（近期性衰减用）；
 * - 容量满后环形覆盖最旧样本（anti-surfer 枪靠小容量实现"只看最近"）。
 * 线性扫描在当前数据量（≤6 万条、每 tick 两次查询）下远低于 1ms，够用；
 * 接口不变，之后如 profiling 需要可换 kd-tree。
 */
final class Knn {

    static final class Entry {
        final double[] point;
        final double value;  // GuessFactor
        final double weight; // 先验权重（开火波 1 / 虚拟波更低）
        final long seq;      // 插入序号，越大越新

        Entry(double[] point, double value, double weight, long seq) {
            this.point = point;
            this.value = value;
            this.weight = weight;
            this.seq = seq;
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
    private long seq;
    private int head; // 环形覆盖游标（容量满后指向最旧样本）

    Knn(double[] weights, int capacity) {
        this.weights = weights;
        this.capacity = capacity;
    }

    void add(double[] point, double value, double weight) {
        Entry e = new Entry(point, value, weight, seq++);
        if (entries.size() < capacity) {
            entries.add(e);
        } else {
            entries.set(head, e);
            head = (head + 1) % capacity;
        }
    }

    int size() {
        return entries.size();
    }

    /** 当前插入序号（= 已插入总条数），配合 Entry.seq 计算样本年龄。 */
    long seq() {
        return seq;
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
