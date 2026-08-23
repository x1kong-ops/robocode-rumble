# -*- coding: utf-8 -*-
"""
阶段 2.1：离线梯度下降学习 KNN 嵌入权重（枪）。

数据: ml/data/gun-<enemy>-<n>.csv，每行 = 一个到达的枪波:
      f0..f7, gf, gfWidth(车身半宽,GF单位), real(0/1)
每个文件是一场完整对战（时间有序）——训练/验证都只允许用同场里更早的波做候选，
模拟真实运行时"只见过过去"的 KNN。

模型: 距离 d_ij = ||w ⊙ (f_i - f_j)||²，注意力 a_ij = softmax_j(-d_ij)。
      命中核 h_ij = exp(-0.5 ((gf_j - gf_i)/width_i)²)。
      损失 = -log(Σ_j a_ij h_ij)：让"按学到的度量看起来近"的历史波，其 GF 真的能命中。
      w 的整体尺度自带温度含义（越大→有效近邻越少）；硬 KNN 只看排序，尺度无关。

验证: 硬 KNN（top-50 + KDE 峰值，与 Java 运行时一致），报告车身窗口命中率。
"""
import argparse
import glob
import math
import os
import sys

import numpy as np
import torch

DIMS = 8
FEATURE_NAMES = ["bft", "|latV|", "advV", "accel", "dirTime", "wallF", "wallB", "disp8"]
# 线上权重（3.5 rumble 全池导出）；leakbed 专用重训实战掉分已回退，勿覆盖
HAND_WEIGHTS = np.array(
    [5.290, 0.841, 2.623, 0.573, 1.096, 1.342, 0.980, 0.621], dtype=np.float64)


def load_battles(data_dir):
    """[(name, feats(N,8), gf(N,), width(N,), real(N,)), ...]"""
    battles = []
    for path in sorted(glob.glob(os.path.join(data_dir, "gun-*.csv"))):
        raw = np.loadtxt(path, delimiter=",", dtype=np.float64)
        if raw.ndim != 2 or raw.shape[0] < 200:
            continue
        battles.append((os.path.basename(path),
                        raw[:, :DIMS], raw[:, DIMS], raw[:, DIMS + 1], raw[:, DIMS + 2]))
    return battles


def sample_examples(battles, n_queries, n_cand, min_history, rng, real_boost):
    """随机采 (query特征, 候选特征, 候选gf, query gf/width/重要度)，候选只来自同场更早的波。"""
    # 长局（mega 互耗）可达 10 万+ 波，按原始长度加权会淹没 mid 池；封顶后每场仍从全量历史里抽。
    sizes = np.array([min(b[1].shape[0], 20000) for b in battles], dtype=np.float64)
    probs = sizes / sizes.sum()
    q_feat = np.empty((n_queries, DIMS))
    c_feat = np.empty((n_queries, n_cand, DIMS))
    c_gf = np.empty((n_queries, n_cand))
    q_gf = np.empty(n_queries)
    q_w = np.empty(n_queries)
    q_imp = np.empty(n_queries)
    for i in range(n_queries):
        b = battles[rng.choice(len(battles), p=probs)]
        feats, gfs, widths, reals = b[1], b[2], b[3], b[4]
        qi = rng.integers(min_history, feats.shape[0])
        cj = rng.integers(0, qi, size=n_cand)
        q_feat[i] = feats[qi]
        c_feat[i] = feats[cj]
        c_gf[i] = gfs[cj]
        q_gf[i] = gfs[qi]
        q_w[i] = widths[qi]
        q_imp[i] = 1.0 + real_boost * reals[qi]
    return q_feat, c_feat, c_gf, q_gf, q_w, q_imp


def embed_features(feat, w, a, b):
    """BeepBoop: w * (x + b)^|a|. feat (..., D)."""
    base = torch.clamp(feat + b, min=1e-9)
    return w * torch.pow(base, torch.abs(a))


def embed_features_np(feat, w, a, b):
    base = np.maximum(feat + b, 1e-9)
    return w * np.power(base, np.abs(a))


def soft_knn_loss(u, batch, ua=None, ub=None):
    q_feat, c_feat, c_gf, q_gf, q_w, q_imp = batch
    w = torch.nn.functional.softplus(u)
    if ua is None:
        diff = (q_feat.unsqueeze(1) - c_feat) * w
    else:
        a = torch.nn.functional.softplus(ua)
        b = torch.nn.functional.softplus(ub) * 0.05
        eq = embed_features(q_feat.unsqueeze(1), w, a, b)
        ep = embed_features(c_feat, w, a, b)
        diff = eq - ep
    d = (diff * diff).sum(-1)                          # (B, C)
    attn = torch.softmax(-d, dim=1)
    z = (c_gf - q_gf.unsqueeze(1)) / q_w.unsqueeze(1)
    hit = torch.exp(-0.5 * z * z)
    p = (attn * hit).sum(1).clamp_min(1e-9)
    return -(q_imp * torch.log(p)).sum() / q_imp.sum()


def hard_knn_eval(battles, weights, k=50, stride=3, min_history=100, chunk=512,
                  exponents=None, biases=None):
    """与 Java 运行时一致的评估：top-k 近邻 + KDE 峰值，车身窗口命中率。"""
    w = np.asarray(weights, dtype=np.float64)
    a = np.ones(DIMS) if exponents is None else np.asarray(exponents, dtype=np.float64)
    b = np.zeros(DIMS) if biases is None else np.asarray(biases, dtype=np.float64)
    hits = 0.0
    total = 0
    max_n = 20000  # 与运行时 60k cap 同量级的子集；全量 20 万波会 O(n²) 爆内存
    for _, feats, gfs, widths, _ in battles:
        n = feats.shape[0]
        if n > max_n:
            feats, gfs, widths = feats[-max_n:], gfs[-max_n:], widths[-max_n:]
            n = max_n
        step = stride if n < 8000 else max(stride, n // 2500)
        idx = np.arange(min_history, n, step)
        z = embed_features_np(feats, w, a, b)
        for s in range(0, len(idx), chunk):
            qs = idx[s:s + chunk]
            hi = qs.max()
            pool, pool_gf = z[:hi], gfs[:hi]
            d = ((z[qs, None, :] - pool[None, :, :]) ** 2).sum(-1)
            for row, qi in enumerate(qs):
                dq = d[row, :qi]
                kk = min(k, qi)
                nb = np.argpartition(dq, kk - 1)[:kk]
                g = pool_gf[nb]
                zz = (g[:, None] - g[None, :]) / max(widths[qi], 0.02)
                pred = g[np.exp(-0.5 * zz * zz).sum(0).argmax()]
                hits += abs(pred - gfs[qi]) < widths[qi]
                total += 1
    return hits / max(total, 1), total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(os.path.dirname(__file__), "data"))
    ap.add_argument("--steps", type=int, default=3000)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--cand", type=int, default=768, help="每 query 的候选数")
    ap.add_argument("--lr", type=float, default=0.03)
    ap.add_argument("--real-boost", type=float, default=2.0, help="实弹 query 的额外损失权重")
    ap.add_argument("--min-history", type=int, default=100)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--skip-eval", action="store_true")
    ap.add_argument("--nonlinear", action="store_true",
                    help="学 BeepBoop 嵌入 w*(x+b)^|a|（默认只学线性 w）")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)

    battles = load_battles(args.data)
    if not battles:
        sys.exit("no data in " + args.data)
    # 奇数场训练、偶数场验证（Battles≥2；Battles=4 时用满 1/3 vs 2/4）
    def _battle_num(name):
        import re
        m = re.search(r"-(\d+)\.csv$", name)
        return int(m.group(1)) if m else 0

    train = [b for b in battles if _battle_num(b[0]) % 2 == 1]
    val = [b for b in battles if _battle_num(b[0]) % 2 == 0]
    if not train or not val:
        sys.exit("need both odd (train) and even (val) battle files")
    n_train = sum(b[1].shape[0] for b in train)
    n_val = sum(b[1].shape[0] for b in val)
    print(f"battles: {len(train)} train ({n_train} waves) / {len(val)} val ({n_val} waves)")

    # 初始化于手工权重（softplus 反函数）
    u0 = np.log(np.expm1(HAND_WEIGHTS))
    u = torch.tensor(u0, dtype=torch.float64, requires_grad=True)
    ua = ub = None
    params = [u]
    if args.nonlinear:
        ua = torch.tensor(np.full(DIMS, np.log(np.expm1(1.0))), dtype=torch.float64,
                          requires_grad=True)
        ub = torch.tensor(np.full(DIMS, -8.0), dtype=torch.float64, requires_grad=True)
        params.extend([ua, ub])
        args.lr = min(args.lr, 1e-3)
    opt = torch.optim.Adam(params, lr=args.lr)

    def make_batch():
        b = sample_examples(train, args.batch, args.cand, args.min_history, rng, args.real_boost)
        return [torch.as_tensor(x) for x in b]

    ema = None
    for step in range(1, args.steps + 1):
        loss = soft_knn_loss(u, make_batch(), ua, ub)
        opt.zero_grad()
        loss.backward()
        opt.step()
        ema = loss.item() if ema is None else 0.98 * ema + 0.02 * loss.item()
        if step % 300 == 0 or step == 1:
            w = torch.nn.functional.softplus(u).detach().numpy()
            extra = ""
            if ua is not None:
                a = torch.nn.functional.softplus(ua).detach().numpy()
                extra = " a=[" + " ".join(f"{x:.2f}" for x in a) + "]"
            print(f"step {step:5d}  loss(ema) {ema:.4f}  w=[" +
                  " ".join(f"{x:.2f}" for x in w) + "]" + extra)

    w_learned = torch.nn.functional.softplus(u).detach().numpy()
    w_export = w_learned * (np.linalg.norm(HAND_WEIGHTS) / np.linalg.norm(w_learned))
    a_export = np.ones(DIMS)
    b_export = np.zeros(DIMS)
    if ua is not None:
        a_export = torch.nn.functional.softplus(ua).detach().numpy()
        b_export = (torch.nn.functional.softplus(ub) * 0.05).detach().numpy()

    print("\nfeature        hand   w_export    a       b")
    for name, h, we, ae, be in zip(FEATURE_NAMES, HAND_WEIGHTS, w_export, a_export, b_export):
        print(f"{name:12s} {h:6.2f} {we:8.3f} {ae:7.3f} {be:7.3f}")

    if not args.skip_eval:
        print("\nhard-KNN val (top-50 + KDE peak, bot-width hit rate):")
        acc_h, n1 = hard_knn_eval(val, HAND_WEIGHTS)
        print(f"  linear  hand: {acc_h:.4f}  ({n1} queries)")
        acc_l, _ = hard_knn_eval(val, w_export, exponents=a_export, biases=b_export)
        print(f"  learned:      {acc_l:.4f}")
        rel = (acc_l / max(acc_h, 1e-9) - 1) * 100
        print(f"  relative: {rel:+.1f}%")

    print("\n// Java:")
    print("private static final double[] WEIGHTS = {"
          + ", ".join(f"{x:.3f}" for x in w_export) + "};")
    if args.nonlinear:
        print("private static final double[] EXPONENTS = {"
              + ", ".join(f"{x:.3f}" for x in a_export) + "};")
        print("private static final double[] BIASES = {"
              + ", ".join(f"{x:.3f}" for x in b_export) + "};")


if __name__ == "__main__":
    main()
