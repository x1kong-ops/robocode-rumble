package pc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import robocode.AdvancedRobot;
import robocode.RobocodeFileOutputStream;

/**
 * 每对手 4 模式的跨场记账 + 置信区间选择（Saguaro 路线精简版）。
 *
 * SURF：现行冲浪（距离 450）。POISON：WavePoison ±0.5。FAR：冲浪但目标距离 600。
 * SHIELD：停车 + HOT 弹道拦截。只存累计得分，不存 KNN 观测。
 * 文件 {@code modebook.txt}（v2），LRU 200 名对手；v1 四列自动补零。
 */
final class ModeBook {

    static final int SURF = 0;
    static final int POISON = 1;
    static final int FAR = 2;
    static final int SHIELD = 3;
    static final int N = 4;
    static final String[] NAMES = {"SURF", "POISON", "FAR", "SHIELD"};

    private static final int MAX_OPPONENTS = 200;
    private static final String FILE = "modebook.txt";
    private static final String VERSION = "v2";
    private static double PRIOR_POINTS = 50;
    private static double PRIOR_FADE = 400;
    private static final double SCORE_UNIT = 50;
    private static double Z = 1.28; // ~80% 正态区间
    private static double EARLY_BONUS_FADE = 120;
    private static double EARLY_BONUS = 0.07;
    private static double SETTLE_POINTS = 280;
    private static double[] UNTESTED_MEAN = {0.58, 0.50, 0.52, 0.48};
    private static boolean bound;

    private static final LinkedHashMap<String, double[]> BOOK =
            new LinkedHashMap<String, double[]>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
                    return size() > MAX_OPPONENTS;
                }
            };

    private static boolean fileLoaded;
    private static String opponent;
    private static double[] live; // our0, opp0, ...
    private static int current = SURF;
    private static int selectedRound = -1;
    private static final int[] picks = new int[N];
    private static boolean dirty;

    private ModeBook() {
    }

    static void prepare(AdvancedRobot robot) {
        bind();
        ensureLoaded(robot);
    }

    static void bind() {
        if (bound) {
            return;
        }
        bound = true;
        Params p = Params.get();
        PRIOR_POINTS = p.priorPoints;
        PRIOR_FADE = p.priorFade;
        Z = p.modeZ;
        EARLY_BONUS = p.earlyBonus;
        EARLY_BONUS_FADE = p.earlyBonusFade;
        SETTLE_POINTS = p.settlePoints;
        UNTESTED_MEAN = p.untestedMean.clone();
    }

    static void onScan(AdvancedRobot robot, String enemyName, int roundNum) {
        bind();
        if (enemyName == null || enemyName.length() == 0) {
            return;
        }
        if (!enemyName.equals(opponent)) {
            flushLive();
            opponent = enemyName;
            live = copyOrNew(BOOK.get(opponent));
            selectedRound = -1;
        }
        if (roundNum == 0 && selectedRound != 0) {
            for (int i = 0; i < N; i++) {
                picks[i] = 0;
            }
        }
        if (selectedRound != roundNum) {
            current = select(live, robot.getNumRounds());
            picks[current]++;
            selectedRound = roundNum;
        }
    }

    static boolean poison() {
        return current == POISON;
    }

    static boolean far() {
        return current == FAR;
    }

    static boolean shield() {
        return current == SHIELD;
    }

    static int current() {
        return current;
    }

    static void endRound(AdvancedRobot robot, boolean won, boolean died) {
        if (live == null) {
            return;
        }
        live[current * 2] += PowerSelector.roundPoints(true, won, died);
        live[current * 2 + 1] += PowerSelector.roundPoints(false, won, died);
        dirty = true;
        flushLive();
        if (robot != null && robot.getRoundNum() + 1 >= robot.getNumRounds()) {
            save(robot);
        }
    }

    static void save(AdvancedRobot robot) {
        if (!dirty || robot == null) {
            return;
        }
        flushLive();
        try {
            PrintStream ps = new PrintStream(
                    new RobocodeFileOutputStream(robot.getDataFile(FILE)), false, "UTF-8");
            ps.println(VERSION);
            for (Map.Entry<String, double[]> e : BOOK.entrySet()) {
                String name = e.getKey();
                if (name == null || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
                    continue;
                }
                double[] v = e.getValue();
                ps.println(name);
                for (int i = 0; i < N * 2; i++) {
                    if (i > 0) {
                        ps.print(' ');
                    }
                    ps.printf(Locale.US, "%.3f", v[i]);
                }
                ps.println();
            }
            ps.close();
            dirty = false;
        } catch (Exception ignored) {
            // 模式簿写不出去不影响对战；下场会当新对手再探
        }
    }

    static String stats() {
        String name = opponent == null ? "-" : opponent.replace(' ', '_');
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(NAMES[current]);
        sb.append(" modeOpp=").append(name);
        sb.append(" modePicks=");
        for (int i = 0; i < N; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(picks[i]);
        }
        if (live != null) {
            sb.append(" modeShare=");
            for (int i = 0; i < N; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(String.format(Locale.US, "%.2f", share(live, i)));
            }
            sb.append(" modePts=");
            for (int i = 0; i < N; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(String.format(Locale.US, "%.0f:%.0f", live[i * 2], live[i * 2 + 1]));
            }
        }
        return sb.toString();
    }

    private static double share(double[] v, int i) {
        double tot = v[i * 2] + v[i * 2 + 1];
        return tot > 0 ? v[i * 2] / tot : 0.5;
    }

    private static void flushLive() {
        if (opponent != null && live != null) {
            BOOK.put(opponent, live);
        }
    }

    private static double[] copyOrNew(double[] src) {
        double[] d = new double[N * 2];
        if (src != null) {
            System.arraycopy(src, 0, d, 0, Math.min(src.length, d.length));
        }
        return d;
    }

    /**
     * 未测按 SURF → POISON → FAR → SHIELD 各打一回合。
     * 上界低于其余模式最高均值则淘汰；存活者证据够则锁高均值，否则探不确定度。
     * RoboRumble 1v1 固定 35 回合：CI 探索在结算前会把整场轮完四个模式，
     * FAR/SHIELD 打 rammer 会赢回合但刷不出伤害（线上 SuperRamFire / WaveRammer ~70% APS、存活 90%+）。
     */
    private static int select(double[] v, int numRounds) {
        if (numRounds > 0 && numRounds <= 40) {
            return SURF;
        }
        if (v[0] + v[1] <= 0) {
            return SURF;
        }
        double[] mean = new double[N];
        double[] se = new double[N];
        double[] hi = new double[N];
        double[] tot = new double[N];
        boolean[] alive = new boolean[N];
        for (int i = 0; i < N; i++) {
            double our = v[i * 2];
            double opp = v[i * 2 + 1];
            tot[i] = our + opp;
            double prior = PRIOR_POINTS * Math.max(0, 1 - tot[i] / PRIOR_FADE);
            if (tot[i] <= 0) {
                mean[i] = UNTESTED_MEAN[i];
            } else {
                mean[i] = (our + prior) / (tot[i] + prior);
            }
            se[i] = 0.5 / Math.sqrt(1 + tot[i] / SCORE_UNIT);
            double bonus = tot[i] < EARLY_BONUS_FADE
                    ? EARLY_BONUS * (1 - tot[i] / EARLY_BONUS_FADE) : 0;
            hi[i] = Math.min(1, mean[i] + Z * se[i] + bonus);
        }
        for (int i = 0; i < N; i++) {
            double bestOther = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < N; j++) {
                if (j != i && mean[j] > bestOther) {
                    bestOther = mean[j];
                }
            }
            alive[i] = hi[i] + 1e-9 >= bestOther;
        }
        boolean any = false;
        for (int i = 0; i < N; i++) {
            any |= alive[i];
        }
        if (!any) {
            alive[SURF] = true;
        }
        for (int i = 0; i < N; i++) {
            if (alive[i] && tot[i] <= 0) {
                return i;
            }
        }
        boolean settled = true;
        for (int i = 0; i < N; i++) {
            if (alive[i] && tot[i] < SETTLE_POINTS) {
                settled = false;
                break;
            }
        }
        int best = -1;
        for (int i = 0; i < N; i++) {
            if (!alive[i]) {
                continue;
            }
            if (best < 0) {
                best = i;
                continue;
            }
            if (settled) {
                if (mean[i] > mean[best]) {
                    best = i;
                }
            } else if (se[i] > se[best] + 1e-9) {
                best = i;
            } else if (Math.abs(se[i] - se[best]) <= 1e-9
                    && mean[i] + Z * se[i] > mean[best] + Z * se[best]) {
                best = i;
            }
        }
        return best < 0 ? SURF : best;
    }

    private static void ensureLoaded(AdvancedRobot robot) {
        if (fileLoaded) {
            return;
        }
        fileLoaded = true;
        try {
            File f = robot.getDataFile(FILE);
            if (f == null || !f.isFile() || f.length() == 0) {
                return;
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String ver = br.readLine();
                if (ver == null) {
                    return;
                }
                ver = ver.trim();
                if (!VERSION.equals(ver) && !"v1".equals(ver)) {
                    return;
                }
                String name;
                while ((name = br.readLine()) != null) {
                    name = name.trim();
                    String nums = br.readLine();
                    if (name.length() == 0 || nums == null) {
                        break;
                    }
                    String[] p = nums.trim().split("\\s+");
                    if (p.length < 4) {
                        continue;
                    }
                    double[] d = new double[N * 2];
                    int n = Math.min(p.length, d.length);
                    for (int i = 0; i < n; i++) {
                        d[i] = Double.parseDouble(p[i]);
                    }
                    BOOK.put(name, d);
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
            // 坏文件当空簿；本场重新探索
        }
    }
}
