# robocode-rumble

经典 Java Robocode 1v1 机器人开发项目，目标是冲击 RoboRumble（LiteRumble）排行榜。

技术路线、榜单分析和分阶段计划详见 [docs/roborumble-research-report.md](docs/roborumble-research-report.md)。

## 目录结构

```
docs/roborumble-research-report.md   深度研究报告（榜单快照、顶级机器人剖析、算法谱系、路线图）
src/rcr/Wavelet.java                 主力机器人：事件路由 / 雷达锁 / 能量簿记 / 健康指标
src/rcr/Surfing.java                 True Surfing 走位（两波链式精确预测 + 精确交点 + GF 统计）
src/rcr/KnnGun.java                  KNN(DC) 单枪（tick 虚拟波 + 核密度估计瞄准 + 火力选择）
src/rcr/Knn.java                     KNN 样本库（线性扫描版，接口可平滑换 kd-tree）
src/rcr/RcMath.java                  几何 / 物理 / 墙壁平滑公用函数
src/rcr/Snapshot.java                双方状态快照（敌波回溯用）
src/rcr/GeomTest.java                精确交点的暴力采样自检（java -cp out\classes;robocode.jar rcr.GeomTest）
scripts/build.ps1                    编译脚本（输出到 out/classes，保持 Java 8 兼容）
scripts/run-battle.ps1               打包部署 + 无头对战 + 输出战果
scripts/testbed.ps1                  8 对手基准测试组一键跑分
reference/                           Diamond / BeepBoop / Saguaro 开源代码（gitignore，仅学习参考）
```

## 环境准备

1. **JDK 17**（推荐）：用于运行 RoboRumble 客户端；机器人代码本身保持 Java 8 兼容（多数产生评分的 rumble 客户端跑 Java 8）。
2. **Robocode 客户端**：本机 `C:\robocode` 已安装 1.10.3，日常开发/对战直接用即可。
   注意：RoboRumble 评分客户端固定用 **1.9.4.2**，正式提交前应在 1.9.4.2 上回归验证一遍
   （下载：<https://sourceforge.net/projects/robocode/files/robocode/1.9.4.2/>，可另装一份到 `C:\robocode-1.9.4.2`）。
3. （可选）设置环境变量 `ROBOCODE_HOME` 指向安装目录（默认按 `C:\robocode` 查找）。

## 构建与本地测试

```powershell
# 编译 + 打包成 rcr.Wavelet_dev.jar 部署进 C:\robocode\robots + 无头跑一场 35 回合
.\scripts\run-battle.ps1 -Enemy "sample.Tracker"
.\scripts\run-battle.ps1 -Enemy "wiki.BasicGFSurfer 1.0" -SkipBuild   # 复用上次编译

# 只编译
.\scripts\build.ps1
```

注意：本机这份 Robocode 不识别 robots 目录下的松散 .class，必须走 jar 打包（脚本已处理）。
GUI 观战：打开 Robocode，新建对战选 `rcr.Wavelet dev`（开 Paint 可看到敌波圈）。
对战结束后 `C:\robocode\robots\.data\rcr\Wavelet.data\stats.txt` 会记录跳过回合数与 KNN 数据量。

批量测试（调参必备）后续接入 RoboResearch 或 RoboRunner，搭覆盖不同强度对手的 test bed。

## 路线图(详见报告 Recommendations 一节)

- **阶段 0（第 1–2 周）——已完成（2026-07-05）**：True 冲浪 + KNN(DC) 单枪，健康指标（skipped turns / 撞墙 / KNN 数据量）写入 `robots\.data\rcr\Wavelet.data\stats.txt`。参考骨架（Diamond / BeepBoop / Saguaro）已克隆到 `reference/`。
- **阶段 1.1 precise prediction + precise intersection（两波冲浪）——已完成（2026-07-05）**：
  - 走位重写为状态化逐 tick 模拟（真实转速/加减速/撞墙物理），三选项 × 两波链式评估（D1 + 0.5·min D2）；
  - 精确交点：每 tick 计算波扫过的圆环带与 36×36 车身的角度交集，整窗口统计质量 × 子弹伤害，另计入模拟撞墙伤害；几何例程用暴力采样自检通过（`GeomTest`，15087 例 0 失败）；
  - 修复角落撞墙问题（wall stick 随敌距缩短），每场撞墙从 42 次降到 0–5 次；
  - 100 回合基线：vs wiki.BasicGFSurfer 1.0 **78%**（阶段 0 版 65%）；vs voidious.mini.Komarious 1.88 **61%**（59%）；全程 0 skipped turns。
  - 注：35 回合单场方差很大（±8%），100 回合单场也有 ±5% 左右，有效对比要多场平均。
- **阶段 1.2 距离控制 + wall smoothing 强化——已完成（2026-07-05）**：
  - 距离控制改以**敌人当前位置**为基准（原来参照波源=敌人开火时的旧位置，会被贴身流不断压近），环绕基准角仍用波源；
  - **俯冲保护**：每波危险 = (窗口质量 × 子弹伤害 + ε) × 俯冲因子（预测终点敌距 < 360 开始放大，上限 ×3）+ 撞墙伤害。ε 的作用：精确交点下「完全躲开」的选项危险恰好是 0，若无 ε，一个贴脸但恰好躲开本波的选项会拿到干净 0 分、下一波送命；
  - 试过并**放弃**的方案（各跑 100 回合×2-3 场 A/B，均不如当前组合）：wall stick 随速度收缩（低速撞墙无伤害 → 模拟看不到代价 → 诱导贴墙，撞墙数从 <10 暴涨到 50+）；激进后撤角 0.5–0.75 rad（走位变径向，反而更好打）；Diamond 全套危险公式（÷时间到达 + 命中率基线 + 期望距离 650，Komarious 掉到 52%）；
  - 3 场 100 回合平均：vs BasicGFSurfer **70%**（1.1 基线三场平均 67%）；vs Komarious **59%**（58%）；50 回合 testbed 平均 **83.6%**，GouldingiHT 99% / DuelistMini 84% / 三个 sample 98-100%，对贴身流（RamFire 99%、GouldingiHT、Tracker 100%）存活满分 5000/5000；
  - 结论：1.1 的动态 wall stick + 撞墙伤害计入已覆盖大部分收益，1.2 的增量主要在贴身流对手的存活与俯冲边界情形。
- **阶段 1（第 3–8 周，目标前 20 / ~87–88 APS）**：~~precise prediction + precise intersection~~ → ~~距离控制 + wall smoothing~~ → KNN 双枪（通用 + anti-surfer）→ 能量管理（基础 power ~1.95 规则）→ 被动 bullet shadows + gunheat waves。
- **阶段 2（第 2–4 月，目标前 5 / 90+ APS）**：离线梯度下降学 KNN 嵌入权重（PyTorch 训练、导出常数进 Java）→ 期望得分最大化能量管理 → 主动子弹阴影（active bullet shadowing）→ flattener（约 9% 命中率门控）。

明确不做：rambot / mirror movement、以 bullet shielding 为主力、端到端深度强化学习、在 True vs GoTo 选型上纠结。

## 参赛（RoboRumble）备忘

- 包名全局唯一且不含下划线——当前占位包名为 `rcr`，正式参赛前改成自己的标识。
- 客户端固定 robocode 1.9.4.2。
- 打包：Robocode 客户端 **Robot → Package robot for upload**。
- 在 RoboWiki 的 RoboRumble/Participants 页面加一行 `botname,jar_url`。

## 关键参考

- LiteRumble 榜单：<https://literumble.appspot.com>
- RoboWiki：<https://robowiki.net>（Wave Surfing Tutorial、GuessFactor Targeting、Understanding BeepBoop 等）
- 开源顶级机器人：[BeepBoop](https://github.com/clarkkev/beep-boop) · [Diamond](https://github.com/Voidious/Diamond) · [Saguaro](https://github.com/oogilbert/Saguaro)（注意各自许可，DrussGT 禁止用于 RoboRumble 以外的竞赛）
