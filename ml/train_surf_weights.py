# -*- coding: utf-8 -*-
"""
阶段 2.5：离线梯度下降学习冲浪 KNN 嵌入权重。

数据: ml/data/surf-<enemy>-<n>.csv，每行 = 一次命中或 flattener 伪命中:
      f0..f7, gf, gfWidth, realHit(1=真实命中, 0=flatten)
训练目标与枪相同：soft-KNN NLL，让相近局面的历史命中 GF 能盖住 query。
真实命中额外加权；flatten 样本保留但权重低（与运行时 FLATTEN_SAMPLE_WEIGHT 一致）。
"""
import argparse
import glob
import os
import sys

import numpy as np
import torch

DIMS = 8
FEATURE_NAMES = ["bft", "|latV|", "advV", "accel", "dirTime", "wallF", "wallB", "power"]
# 阶段 3.5：以当前线上权重为基线（2.5 学得结果）
HAND_WEIGHTS = np.array(
    [5.682, 1.268, 0.036, 0.769, 0.476, 1.242, 1.292, 0.760], dtype=np.float64)


def load_battles(data_dir):
    battles = []
    for path in sorted(glob.glob(os.path.join(data_dir, "surf-*.csv"))):
        raw = np.loadtxt(path, delimiter=",", dtype=np.float64)
        if raw.ndim != 2 or raw.shape[0] < 80:
            continue
        battles.append((os.path.basename(path),
                        raw[:, :DIMS], raw[:, DIMS], raw[:, DIMS + 1], raw[:, DIMS + 2]))
    return battles


def sample_examples(battles, n_queries, n_cand, min_history, rng, hit_boost):
    sizes = np.array([b[1].shape[0] for b in battles], dtype=np.float64)
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
        q_w[i] = max(0.02, widths[qi])
        q_imp[i] = 0.25 + hit_boost * reals[qi]  # flatten 样本也学，但真实命中为主
    return q_feat, c_feat, c_gf, q_gf, q_w, q_imp


def soft_knn_loss(u, batch):
    q_feat, c_feat, c_gf, q_gf, q_w, q_imp = batch
    w = torch.nn.functional.softplus(u)
    diff = (q_feat.unsqueeze(1) - c_feat) * w
    d = (diff * diff).sum(-1)
    attn = torch.softmax(-d, dim=1)
    z = (c_gf - q_gf.unsqueeze(1)) / q_w.unsqueeze(1)
    hit = torch.exp(-0.5 * z * z)
    p = (attn * hit).sum(1).clamp_min(1e-9)
    return -(q_imp * torch.log(p)).sum() / q_imp.sum()


def hard_knn_eval(battles, weights, k=50, stride=2, min_history=40, chunk=256):
    """硬 KNN：近邻 GF 核密度峰值是否落在车身窗口（只在真实命中 query 上评）。"""
    w2 = (np.asarray(weights, dtype=np.float64) ** 2)[None, :]
    hits = 0.0
    total = 0
    for _, feats, gfs, widths, reals in battles:
        idx = [i for i in range(min_history, feats.shape[0], stride) if reals[i] > 0.5]
        for s in range(0, len(idx), chunk):
            qs = np.array(idx[s:s + chunk], dtype=np.int64)
            if len(qs) == 0:
                continue
            hi = int(qs.max())
            pool, pool_gf = feats[:hi], gfs[:hi]
            d = ((feats[qs, None, :] - pool[None, :, :]) ** 2 * w2).sum(-1)
            for row, qi in enumerate(qs):
                dq = d[row, :qi]
                kk = min(k, qi)
                nb = np.argpartition(dq, kk - 1)[:kk]
                g = pool_gf[nb]
                bw = max(0.02, widths[qi])
                zz = (g[:, None] - g[None, :]) / bw
                pred = g[np.exp(-0.5 * zz * zz).sum(0).argmax()]
                hits += abs(pred - gfs[qi]) < widths[qi]
                total += 1
    return hits / max(total, 1), total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(os.path.dirname(__file__), "data"))
    ap.add_argument("--steps", type=int, default=2500)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--cand", type=int, default=512)
    ap.add_argument("--lr", type=float, default=0.03)
    ap.add_argument("--hit-boost", type=float, default=3.0)
    ap.add_argument("--min-history", type=int, default=40)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--skip-eval", action="store_true")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)

    battles = load_battles(args.data)
    if not battles:
        sys.exit("no surf data in " + args.data)

    def battle_num(name):
        # surf-foo-3.csv -> 3
        try:
            return int(name.rsplit("-", 1)[-1].split(".")[0])
        except ValueError:
            return 0

    train = [b for b in battles if battle_num(b[0]) % 2 == 1]
    val = [b for b in battles if battle_num(b[0]) % 2 == 0]
    if not train or not val:
        train, val = battles[: max(1, len(battles) // 2)], battles[max(1, len(battles) // 2):]
    print(f"battles: {len(train)} train ({sum(b[1].shape[0] for b in train)} rows) / "
          f"{len(val)} val ({sum(b[1].shape[0] for b in val)} rows)")

    u0 = np.log(np.expm1(HAND_WEIGHTS))
    u = torch.tensor(u0, dtype=torch.float64, requires_grad=True)
    opt = torch.optim.Adam([u], lr=args.lr)

    def make_batch():
        b = sample_examples(train, args.batch, args.cand, args.min_history, rng, args.hit_boost)
        return [torch.as_tensor(x) for x in b]

    ema = None
    for step in range(1, args.steps + 1):
        loss = soft_knn_loss(u, make_batch())
        opt.zero_grad()
        loss.backward()
        opt.step()
        ema = loss.item() if ema is None else 0.98 * ema + 0.02 * loss.item()
        if step % 250 == 0 or step == 1:
            w = torch.nn.functional.softplus(u).detach().numpy()
            print(f"step {step:5d}  loss(ema) {ema:.4f}  w=[" +
                  " ".join(f"{x:.2f}" for x in w) + "]")

    w_learned = torch.nn.functional.softplus(u).detach().numpy()
    w_export = w_learned * (np.linalg.norm(HAND_WEIGHTS) / np.linalg.norm(w_learned))

    print("\nfeature        hand   learned(raw)  export")
    for name, h, lr_, e in zip(FEATURE_NAMES, HAND_WEIGHTS, w_learned, w_export):
        print(f"{name:12s} {h:6.2f} {lr_:12.3f} {e:8.3f}")

    if not args.skip_eval:
        print("\nhard-KNN val (top-50 + KDE peak on real hits):")
        acc_h, n1 = hard_knn_eval(val, HAND_WEIGHTS)
        print(f"  hand    weights: {acc_h:.4f}  ({n1} queries)")
        acc_l, _ = hard_knn_eval(val, w_export)
        print(f"  learned weights: {acc_l:.4f}")
        print(f"  relative: {(acc_l / max(acc_h, 1e-9) - 1) * 100:+.1f}%")

    print("\n// Java:")
    print("private static final double[] SURF_WEIGHTS = {"
          + ", ".join(f"{x:.3f}" for x in w_export) + "};")


if __name__ == "__main__":
    main()
