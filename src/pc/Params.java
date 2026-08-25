package pc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Locale;

import robocode.AdvancedRobot;

/**
 * 当前 bot 的策略常量。无覆盖时与线上 1.3 / 本机 dev 一致。
 * 覆盖：-Dpc.params.&lt;SimpleName&gt;=文件路径（Wavelet / Spar 各写各的；系统属性共享，必须按类名区分）。
 */
final class Params {

    static final int GUN_DIMS = 8;
    static final int SURF_DIMS = 8;

    String source = "default";

    double desiredDistance = 450;
    double farDistance = 600;
    double secondWaveWeight = 0.5;
    double thirdWaveWeight = 0.25;
    double diveProtectDistance = 360;
    double dangerEpsilon = 0.05;
    double[] surfWeights = {5.388, 1.368, 1.721, 0.763, 1.204, 0.988, 0.258, 1.251};
    double[] surfExponents = {1, 1, 1, 1, 1, 1, 1, 1};
    double[] surfBiases = {0, 0, 0, 0, 0, 0, 0, 0};
    int surfK = 50;
    int surfCapacity = 20000;
    double flattenSampleWeight = 0.15;
    double flattenerOn = 0.12;
    double flattenerOff = 0.09;
    int flattenerWindow = 40;
    int flattenerMinShots = 30;
    double[] modelScore = {2.0, 1.0, 0.9, 0.7};
    double modelScoreDecay = 0.97;
    double modelMatchSigma = 0.18;
    double crowdPeakScale = 3.5;
    double leanLimit = 0.35;
    double farLeanLimit = 0.45;
    double poisonVelocity = 0.5;
    double poisonMinDist = 160;
    double closingAbort = 5;
    double shieldMinDist = 180;
    double farCloseDist = 180;
    double gotoLean = 0.3;
    double gotoOrbitCap = 500;
    double gotoOrbitCapFar = 620;
    int pathCap = 12;
    double pathHalfFrac = 0.45;
    double beamEtaMin = 8;
    int beamWidth = 3;
    int beamTicksMin = 6;
    int beamTicksMax = 10;
    double termStopEta = 3;
    double termStopHitRate = 0.16;
    double termStopDangerSlack = 0.08;
    double termStopProb = 0.35;

    double[] gunWeights = {5.290, 0.841, 2.623, 0.573, 1.096, 1.342, 0.980, 0.621};
    double[] gunExponents = {1, 1, 1, 1, 1, 1, 1, 1};
    double[] gunBiases = {0, 0, 0, 0, 0, 0, 0, 0};
    int gunCapacity = 60000;
    int mainK = 50;
    int asK = 20;
    int asCapacity = 2000;
    double asHalfLife = 300;
    double asVirtualWeight = 0.05;
    double scoreDecay = 0.995;
    double asSwitchMargin = 0.05;
    int minWavesToSwitch = 50;
    double pifSwitchMargin = 0.08;
    int pifCap = 1800;
    int pifPat = 10;
    int pifK = 6;
    int refineMinShots = 80;
    double refineMinHitRate = 0.16;
    double rammerAimDist = 160;
    double rammerAimAdv = 5.5;
    double radarLock = 2;

    double absHitRateGate = 0.2;
    double fullPowerDistance = 140;
    double ramCloseDistance = 180;
    double ramCloseAdvancing = 5.5;

    double priorPoints = 50;
    double priorFade = 400;
    double settlePoints = 280;
    double modeZ = 1.28;
    double earlyBonus = 0.07;
    double earlyBonusFade = 120;
    double[] untestedMean = {0.58, 0.50, 0.52, 0.48};

    static final Params DEFAULT = new Params();
    private static Params live;
    private static boolean loaded;

    private Params() {
    }

    static Params get() {
        return live != null ? live : DEFAULT;
    }

    static void load(AdvancedRobot robot) {
        if (loaded) {
            return;
        }
        loaded = true;
        live = DEFAULT.copy();
        if (robot == null) {
            return;
        }
        String spec = null;
        try {
            spec = System.getProperty("pc.params." + robot.getClass().getSimpleName());
        } catch (Exception ignored) {
            live.source = "default";
            return;
        }
        if (spec == null || spec.length() == 0) {
            live.source = "default";
            return;
        }
        try {
            if ("data".equals(spec)) {
                File f = robot.getDataFile("params.txt");
                if (f != null && f.isFile()) {
                    live.parseFile(f);
                    live.source = "data";
                }
                return;
            }
            File f = new File(spec);
            if (f.isFile()) {
                live.parseFile(f);
                live.source = spec;
                return;
            }
            live.parseText(spec.replace(';', '\n'));
            live.source = "inline";
        } catch (Exception ignored) {
            live = DEFAULT.copy();
            live.source = "default";
        }
    }

    Params copy() {
        Params p = new Params();
        p.source = source;
        p.desiredDistance = desiredDistance;
        p.farDistance = farDistance;
        p.secondWaveWeight = secondWaveWeight;
        p.thirdWaveWeight = thirdWaveWeight;
        p.diveProtectDistance = diveProtectDistance;
        p.dangerEpsilon = dangerEpsilon;
        p.surfWeights = surfWeights.clone();
        p.surfExponents = surfExponents.clone();
        p.surfBiases = surfBiases.clone();
        p.surfK = surfK;
        p.surfCapacity = surfCapacity;
        p.flattenSampleWeight = flattenSampleWeight;
        p.flattenerOn = flattenerOn;
        p.flattenerOff = flattenerOff;
        p.flattenerWindow = flattenerWindow;
        p.flattenerMinShots = flattenerMinShots;
        p.modelScore = modelScore.clone();
        p.modelScoreDecay = modelScoreDecay;
        p.modelMatchSigma = modelMatchSigma;
        p.crowdPeakScale = crowdPeakScale;
        p.leanLimit = leanLimit;
        p.farLeanLimit = farLeanLimit;
        p.poisonVelocity = poisonVelocity;
        p.poisonMinDist = poisonMinDist;
        p.closingAbort = closingAbort;
        p.shieldMinDist = shieldMinDist;
        p.farCloseDist = farCloseDist;
        p.gotoLean = gotoLean;
        p.gotoOrbitCap = gotoOrbitCap;
        p.gotoOrbitCapFar = gotoOrbitCapFar;
        p.pathCap = pathCap;
        p.pathHalfFrac = pathHalfFrac;
        p.beamEtaMin = beamEtaMin;
        p.beamWidth = beamWidth;
        p.beamTicksMin = beamTicksMin;
        p.beamTicksMax = beamTicksMax;
        p.termStopEta = termStopEta;
        p.termStopHitRate = termStopHitRate;
        p.termStopDangerSlack = termStopDangerSlack;
        p.termStopProb = termStopProb;
        p.gunWeights = gunWeights.clone();
        p.gunExponents = gunExponents.clone();
        p.gunBiases = gunBiases.clone();
        p.gunCapacity = gunCapacity;
        p.mainK = mainK;
        p.asK = asK;
        p.asCapacity = asCapacity;
        p.asHalfLife = asHalfLife;
        p.asVirtualWeight = asVirtualWeight;
        p.scoreDecay = scoreDecay;
        p.asSwitchMargin = asSwitchMargin;
        p.minWavesToSwitch = minWavesToSwitch;
        p.pifSwitchMargin = pifSwitchMargin;
        p.pifCap = pifCap;
        p.pifPat = pifPat;
        p.pifK = pifK;
        p.refineMinShots = refineMinShots;
        p.refineMinHitRate = refineMinHitRate;
        p.rammerAimDist = rammerAimDist;
        p.rammerAimAdv = rammerAimAdv;
        p.radarLock = radarLock;
        p.absHitRateGate = absHitRateGate;
        p.fullPowerDistance = fullPowerDistance;
        p.ramCloseDistance = ramCloseDistance;
        p.ramCloseAdvancing = ramCloseAdvancing;
        p.priorPoints = priorPoints;
        p.priorFade = priorFade;
        p.settlePoints = settlePoints;
        p.modeZ = modeZ;
        p.earlyBonus = earlyBonus;
        p.earlyBonusFade = earlyBonusFade;
        p.untestedMean = untestedMean.clone();
        return p;
    }

    private void parseFile(File f) throws Exception {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            parseText(sb.toString());
        } finally {
            br.close();
        }
    }

    private void parseText(String text) {
        String[] lines = text.split("[\n\r]+");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.charAt(0) == '#') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            apply(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }

    private void apply(String key, String raw) {
        if ("desiredDistance".equals(key)) {
            desiredDistance = dbl(raw, desiredDistance);
        } else if ("farDistance".equals(key)) {
            farDistance = dbl(raw, farDistance);
        } else if ("secondWaveWeight".equals(key)) {
            secondWaveWeight = dbl(raw, secondWaveWeight);
        } else if ("thirdWaveWeight".equals(key)) {
            thirdWaveWeight = dbl(raw, thirdWaveWeight);
        } else if ("diveProtectDistance".equals(key)) {
            diveProtectDistance = dbl(raw, diveProtectDistance);
        } else if ("dangerEpsilon".equals(key)) {
            dangerEpsilon = dbl(raw, dangerEpsilon);
        } else if ("surfWeights".equals(key)) {
            copyInto(surfWeights, doubles(raw, SURF_DIMS));
        } else if ("surfExponents".equals(key)) {
            copyInto(surfExponents, doubles(raw, SURF_DIMS));
        } else if ("surfBiases".equals(key)) {
            copyInto(surfBiases, doubles(raw, SURF_DIMS));
        } else if ("surfK".equals(key)) {
            surfK = num(raw, surfK);
        } else if ("surfCapacity".equals(key)) {
            surfCapacity = num(raw, surfCapacity);
        } else if ("flattenSampleWeight".equals(key)) {
            flattenSampleWeight = dbl(raw, flattenSampleWeight);
        } else if ("flattenerOn".equals(key)) {
            flattenerOn = dbl(raw, flattenerOn);
        } else if ("flattenerOff".equals(key)) {
            flattenerOff = dbl(raw, flattenerOff);
        } else if ("flattenerWindow".equals(key)) {
            flattenerWindow = Math.max(1, num(raw, flattenerWindow));
        } else if ("flattenerMinShots".equals(key)) {
            flattenerMinShots = num(raw, flattenerMinShots);
        } else if ("modelScore".equals(key)) {
            copyInto(modelScore, doubles(raw, 4));
        } else if ("modelScoreDecay".equals(key)) {
            modelScoreDecay = dbl(raw, modelScoreDecay);
        } else if ("modelMatchSigma".equals(key)) {
            modelMatchSigma = dbl(raw, modelMatchSigma);
        } else if ("crowdPeakScale".equals(key)) {
            crowdPeakScale = dbl(raw, crowdPeakScale);
        } else if ("leanLimit".equals(key)) {
            leanLimit = dbl(raw, leanLimit);
        } else if ("farLeanLimit".equals(key)) {
            farLeanLimit = dbl(raw, farLeanLimit);
        } else if ("poisonVelocity".equals(key)) {
            poisonVelocity = dbl(raw, poisonVelocity);
        } else if ("poisonMinDist".equals(key)) {
            poisonMinDist = dbl(raw, poisonMinDist);
        } else if ("closingAbort".equals(key)) {
            closingAbort = dbl(raw, closingAbort);
        } else if ("shieldMinDist".equals(key)) {
            shieldMinDist = dbl(raw, shieldMinDist);
        } else if ("farCloseDist".equals(key)) {
            farCloseDist = dbl(raw, farCloseDist);
        } else if ("gotoLean".equals(key)) {
            gotoLean = dbl(raw, gotoLean);
        } else if ("gotoOrbitCap".equals(key)) {
            gotoOrbitCap = dbl(raw, gotoOrbitCap);
        } else if ("gotoOrbitCapFar".equals(key)) {
            gotoOrbitCapFar = dbl(raw, gotoOrbitCapFar);
        } else if ("pathCap".equals(key)) {
            pathCap = Math.max(4, num(raw, pathCap));
        } else if ("pathHalfFrac".equals(key)) {
            pathHalfFrac = dbl(raw, pathHalfFrac);
        } else if ("beamEtaMin".equals(key)) {
            beamEtaMin = dbl(raw, beamEtaMin);
        } else if ("beamWidth".equals(key)) {
            beamWidth = Math.max(1, num(raw, beamWidth));
        } else if ("beamTicksMin".equals(key)) {
            beamTicksMin = num(raw, beamTicksMin);
        } else if ("beamTicksMax".equals(key)) {
            beamTicksMax = num(raw, beamTicksMax);
        } else if ("termStopEta".equals(key)) {
            termStopEta = dbl(raw, termStopEta);
        } else if ("termStopHitRate".equals(key)) {
            termStopHitRate = dbl(raw, termStopHitRate);
        } else if ("termStopDangerSlack".equals(key)) {
            termStopDangerSlack = dbl(raw, termStopDangerSlack);
        } else if ("termStopProb".equals(key)) {
            termStopProb = dbl(raw, termStopProb);
        } else if ("gunWeights".equals(key)) {
            copyInto(gunWeights, doubles(raw, GUN_DIMS));
        } else if ("gunExponents".equals(key)) {
            copyInto(gunExponents, doubles(raw, GUN_DIMS));
        } else if ("gunBiases".equals(key)) {
            copyInto(gunBiases, doubles(raw, GUN_DIMS));
        } else if ("gunCapacity".equals(key)) {
            gunCapacity = num(raw, gunCapacity);
        } else if ("mainK".equals(key)) {
            mainK = Math.max(1, num(raw, mainK));
        } else if ("asK".equals(key)) {
            asK = Math.max(1, num(raw, asK));
        } else if ("asCapacity".equals(key)) {
            asCapacity = num(raw, asCapacity);
        } else if ("asHalfLife".equals(key)) {
            asHalfLife = dbl(raw, asHalfLife);
        } else if ("asVirtualWeight".equals(key)) {
            asVirtualWeight = dbl(raw, asVirtualWeight);
        } else if ("scoreDecay".equals(key)) {
            scoreDecay = dbl(raw, scoreDecay);
        } else if ("asSwitchMargin".equals(key)) {
            asSwitchMargin = dbl(raw, asSwitchMargin);
        } else if ("minWavesToSwitch".equals(key)) {
            minWavesToSwitch = num(raw, minWavesToSwitch);
        } else if ("pifSwitchMargin".equals(key)) {
            pifSwitchMargin = dbl(raw, pifSwitchMargin);
        } else if ("pifCap".equals(key)) {
            pifCap = Math.max(pifPat + 2, num(raw, pifCap));
        } else if ("pifPat".equals(key)) {
            pifPat = Math.max(2, num(raw, pifPat));
        } else if ("pifK".equals(key)) {
            pifK = Math.max(1, num(raw, pifK));
        } else if ("refineMinShots".equals(key)) {
            refineMinShots = num(raw, refineMinShots);
        } else if ("refineMinHitRate".equals(key)) {
            refineMinHitRate = dbl(raw, refineMinHitRate);
        } else if ("rammerAimDist".equals(key)) {
            rammerAimDist = dbl(raw, rammerAimDist);
        } else if ("rammerAimAdv".equals(key)) {
            rammerAimAdv = dbl(raw, rammerAimAdv);
        } else if ("radarLock".equals(key)) {
            radarLock = dbl(raw, radarLock);
        } else if ("absHitRateGate".equals(key)) {
            absHitRateGate = dbl(raw, absHitRateGate);
        } else if ("fullPowerDistance".equals(key)) {
            fullPowerDistance = dbl(raw, fullPowerDistance);
        } else if ("ramCloseDistance".equals(key)) {
            ramCloseDistance = dbl(raw, ramCloseDistance);
        } else if ("ramCloseAdvancing".equals(key)) {
            ramCloseAdvancing = dbl(raw, ramCloseAdvancing);
        } else if ("priorPoints".equals(key)) {
            priorPoints = dbl(raw, priorPoints);
        } else if ("priorFade".equals(key)) {
            priorFade = dbl(raw, priorFade);
        } else if ("settlePoints".equals(key)) {
            settlePoints = dbl(raw, settlePoints);
        } else if ("modeZ".equals(key)) {
            modeZ = dbl(raw, modeZ);
        } else if ("earlyBonus".equals(key)) {
            earlyBonus = dbl(raw, earlyBonus);
        } else if ("earlyBonusFade".equals(key)) {
            earlyBonusFade = dbl(raw, earlyBonusFade);
        } else if ("untestedMean".equals(key)) {
            copyInto(untestedMean, doubles(raw, 4));
        }
    }

    private static void copyInto(double[] dest, double[] src) {
        if (src != null && src.length == dest.length) {
            System.arraycopy(src, 0, dest, 0, dest.length);
        }
    }

    private static double dbl(String raw, double fallback) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int num(String raw, int fallback) {
        return (int) Math.round(dbl(raw, fallback));
    }

    private static double[] doubles(String raw, int expect) {
        String[] parts = raw.split(",");
        if (parts.length != expect) {
            return null;
        }
        double[] out = new double[expect];
        try {
            for (int i = 0; i < expect; i++) {
                out[i] = Double.parseDouble(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    static String stats() {
        return "params=" + get().source;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "Params[%s d=%.0f far=%.0f]", source, desiredDistance, farDistance);
    }
}
