package pc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * KNN 存储（加权欧氏距离）：小库 / 环形淘汰期线性扫描；增长期用增量 kd-tree。
 * - 每条样本带先验权重与插入序号；
 * - 容量满后环形覆盖最旧样本；满容后回退线性（覆盖会破坏树不变量，频繁重建易 skippedTurns）；
 * - 增长期增量插入（无查询路径全量重建），避免 8 维下重建尖峰。
 */
final class Knn {

    static final class Entry {
        final double[] point;
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
    private final int dims;
    private final int capacity;
    private final List<Entry> entries = new ArrayList<Entry>();
    private long seq;
    private int head;
    private boolean ringMode;

    private Node root;
    private int inserts; // 诊断：树插入次数

    Knn(double[] weights, int capacity) {
        this.weights = weights;
        this.dims = weights.length;
        this.capacity = capacity;
    }

    void add(double[] point, double value, double weight) {
        Entry e = new Entry(point, value, weight, seq++);
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
        if (ringMode || root == null || entries.size() <= LINEAR_LIMIT) {
            return nearestLinear(query, k);
        }
        PriorityQueue<Neighbor> heap = maxHeap(k);
        search(root, query, k, heap);
        return new ArrayList<Neighbor>(heap);
    }

    List<Neighbor> nearestLinear(double[] query, int k) {
        PriorityQueue<Neighbor> heap = maxHeap(k);
        for (Entry e : entries) {
            consider(heap, k, e, dist2(query, e.point));
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
        double delta = weights[axis] * (query[axis] - node.entry.point[axis]);
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
            double z = weights[i] * (query[i] - point[i]);
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
