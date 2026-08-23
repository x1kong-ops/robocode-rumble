package pc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * KNN 存储。距离在嵌入空间：embed_i = w_i * (x_i + b_i)^|a_i|（BeepBoop）。
 * a=1、b=0 时退化为原来的加权欧氏距离。
 * 小库 / 环形淘汰期线性扫描；增长期用增量 kd-tree（在嵌入坐标上分裂）。
 */
final class Knn {

    static final class Entry {
        final double[] point; // 已嵌入
        final double value;
        final double weight;
        final long seq;

        Entry(double[] point, double value, double weight, long seq) {
            this.point = point;
            this.value = value;
            this.weight = weight;
            this.seq = seq;
        }
    }

    static final class Neighbor {
        final Entry entry;
        final double dist;

        Neighbor(Entry entry, double dist) {
            this.entry = entry;
            this.dist = dist;
        }
    }

    private static final int LINEAR_LIMIT = 512;

    private final double[] weights;
    private final double[] exponent; // |a|，默认全 1
    private final double[] bias;     // b，默认全 0
    private final int dims;
    private final int capacity;
    private final List<Entry> entries = new ArrayList<Entry>();
    private long seq;
    private int head;
    private boolean ringMode;

    private Node root;
    private int inserts; // 诊断：树插入次数

    Knn(double[] weights, int capacity) {
        this(weights, null, null, capacity);
    }

    Knn(double[] weights, double[] exponent, double[] bias, int capacity) {
        this.weights = weights;
        this.dims = weights.length;
        this.exponent = exponent != null ? exponent : ones(this.dims);
        this.bias = bias != null ? bias : new double[this.dims];
        this.capacity = capacity;
    }

    private static double[] ones(int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = 1;
        }
        return a;
    }

    /** BeepBoop 嵌入：w * (x + b)^|a|。x 在 [0,1]，b≥0 保证底为正。 */
    double[] embed(double[] x) {
        double[] z = new double[dims];
        for (int i = 0; i < dims; i++) {
            double base = Math.max(1e-9, x[i] + bias[i]);
            z[i] = weights[i] * Math.pow(base, Math.abs(exponent[i]));
        }
        return z;
    }

    void add(double[] point, double value, double weight) {
        Entry e = new Entry(embed(point), value, weight, seq++);
        if (entries.size() < capacity) {
            entries.add(e);
            if (!ringMode && entries.size() > LINEAR_LIMIT) {
                if (root == null) {
                    // 越过阈值瞬间：一次性建树（仅一次）
                    bulkBuild();
                } else {
                    insert(root, e, 0);
                    inserts++;
                }
            }
        } else {
            entries.set(head, e);
            head = (head + 1) % capacity;
            if (!ringMode) {
                ringMode = true;
                root = null;
            }
        }
    }

    int size() {
        return entries.size();
    }

    long seq() {
        return seq;
    }

    int rebuilds() {
        return inserts; // 兼容自检字段名：增量插入次数
    }

    boolean usingTree() {
        return !ringMode && root != null;
    }

    List<Neighbor> nearest(double[] query, int k) {
        if (k <= 0 || entries.isEmpty()) {
            return new ArrayList<Neighbor>();
        }
        k = Math.min(k, entries.size());
        double[] q = embed(query);
        if (ringMode || root == null || entries.size() <= LINEAR_LIMIT) {
            return nearestEmbedded(q, k);
        }
        PriorityQueue<Neighbor> heap = maxHeap(k);
        search(root, q, k, heap);
        return new ArrayList<Neighbor>(heap);
    }

    List<Neighbor> nearestLinear(double[] query, int k) {
        return nearestEmbedded(embed(query), k);
    }

    private List<Neighbor> nearestEmbedded(double[] q, int k) {
        PriorityQueue<Neighbor> heap = maxHeap(k);
        for (Entry e : entries) {
            consider(heap, k, e, dist2(q, e.point));
        }
        return new ArrayList<Neighbor>(heap);
    }

    private void bulkBuild() {
        root = null;
        for (int i = 0, n = entries.size(); i < n; i++) {
            Entry e = entries.get(i);
            if (root == null) {
                root = new Node(e, 0);
            } else {
                insert(root, e, 0);
            }
            inserts++;
        }
    }

    private void insert(Node node, Entry e, int depth) {
        int axis = depth % dims;
        boolean left = e.point[axis] < node.entry.point[axis]
                || (e.point[axis] == node.entry.point[axis] && e.seq < node.entry.seq);
        if (left) {
            if (node.left == null) {
                node.left = new Node(e, (depth + 1) % dims);
            } else {
                insert(node.left, e, depth + 1);
            }
        } else {
            if (node.right == null) {
                node.right = new Node(e, (depth + 1) % dims);
            } else {
                insert(node.right, e, depth + 1);
            }
        }
    }

    private void search(Node node, double[] query, int k, PriorityQueue<Neighbor> heap) {
        if (node == null) {
            return;
        }
        consider(heap, k, node.entry, dist2(query, node.entry.point));
        int axis = node.axis;
        double delta = query[axis] - node.entry.point[axis];
        Node near = delta < 0 ? node.left : node.right;
        Node far = delta < 0 ? node.right : node.left;
        search(near, query, k, heap);
        if (far != null && (heap.size() < k || delta * delta < heap.peek().dist)) {
            search(far, query, k, heap);
        }
    }

    private double dist2(double[] query, double[] point) {
        double d = 0;
        for (int i = 0; i < dims; i++) {
            double z = query[i] - point[i];
            d += z * z;
        }
        return d;
    }

    private static PriorityQueue<Neighbor> maxHeap(int k) {
        return new PriorityQueue<Neighbor>(Math.max(1, k), new Comparator<Neighbor>() {
            @Override
            public int compare(Neighbor a, Neighbor b) {
                int c = Double.compare(b.dist, a.dist);
                if (c != 0) {
                    return c;
                }
                return Long.compare(a.entry.seq, b.entry.seq);
            }
        });
    }

    private static void consider(PriorityQueue<Neighbor> heap, int k, Entry e, double d) {
        if (heap.size() < k) {
            heap.add(new Neighbor(e, d));
        } else {
            Neighbor worst = heap.peek();
            int cmp = Double.compare(d, worst.dist);
            if (cmp < 0 || (cmp == 0 && e.seq > worst.entry.seq)) {
                heap.poll();
                heap.add(new Neighbor(e, d));
            }
        }
    }

    private static final class Node {
        final Entry entry;
        final int axis;
        Node left;
        Node right;

        Node(Entry entry, int axis) {
            this.entry = entry;
            this.axis = axis;
        }
    }
}
