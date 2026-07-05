# -*- coding: utf-8 -*-
"""按对手分解：手工权重 vs 学得权重的硬 KNN 命中率（验证场 -2.csv）。"""
import os
import numpy as np
from train_gun_weights import load_battles, hard_knn_eval, HAND_WEIGHTS

LEARNED = np.array([5.001, 0.832, 2.843, 0.457, 1.532, 1.692, 0.824, 0.606])

battles = load_battles(os.path.join(os.path.dirname(__file__), "data"))
val = [b for b in battles if "-2.csv" in b[0]]
print(f"{'battle':40s} {'hand':>7s} {'learned':>8s} {'delta':>7s}")
for b in val:
    ah, n = hard_knn_eval([b], HAND_WEIGHTS)
    al, _ = hard_knn_eval([b], LEARNED)
    print(f"{b[0]:40s} {ah:7.3f} {al:8.3f} {al - ah:+7.3f}  ({n})")
