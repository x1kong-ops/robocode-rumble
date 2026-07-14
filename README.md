# robocode-rumble

经典 Java Robocode 1v1 机器人开发项目，目标是冲击 RoboRumble（LiteRumble）排行榜。

技术路线、榜单分析和分阶段计划详见 [docs/roborumble-research-report.md](docs/roborumble-research-report.md)。

## 目录结构

```
docs/roborumble-research-report.md   深度研究报告（榜单快照、顶级机器人剖析、算法谱系、路线图）
src/rcr/Wavelet.java                 主力机器人：事件路由 / 雷达锁 / 能量簿记 / 健康指标（rcr.Wavelet dev）
src/rcr/Surfing.java                 True Surfing 走位（两波链式精确预测 + 精确交点 + GF 统计）
src/rcr/KnnGun.java                  KNN(DC) 双枪（通用 + anti-surfer，虚拟枪框架选枪）
src/rcr/PowerSelector.java           火力选择：期望得分最大化（BeepBoop 模型移植）
src/rcr/Knn.java                     KNN 样本库（线性扫描，带样本权重/序号/环形淘汰）
src/rcr/RcMath.java                  几何 / 物理 / 墙壁平滑公用函数
src/rcr/Snapshot.java                双方状态快照（敌波回溯用）
src/rcr/GeomTest.java                精确交点的暴力采样自检（java -cp out\classes;robocode.jar rcr.GeomTest）
src/rcr/ShadowTest.java              bullet shadow 几何的暴力采样自检（同上，rcr.ShadowTest）
scripts/build.ps1                    编译脚本（输出到 out/classes，保持 Java 8 兼容）
scripts/run-battle.ps1               打包部署 + 无头对战 + 输出战果（-ExtraJvmArgs 可传附加 JVM 参数）
scripts/testbed.ps1                  8 对手基准测试组一键跑分
scripts/datagen.ps1                  批量采集枪波训练数据到 ml/data/*.csv（11 对手 × N 场）
ml/train_gun_weights.py              PyTorch 训练 KNN 嵌入权重（可微 soft-KNN + NLL，硬 KNN 验证）
ml/eval_per_enemy.py                 学得权重按对手分解验证
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

### 离线训练（阶段 2.1+）

```powershell
.\scripts\datagen.ps1 -Battles 2 -Rounds 35    # 采数据：11 对手，写 ml/data/gun-*.csv（~25 万波，几分钟）
python ml\train_gun_weights.py                 # 训练嵌入权重 + 留出集硬 KNN 验证，末尾输出 Java 常量
python ml\eval_per_enemy.py                    # 按对手分解对比手工/学得权重
```

数据导出走 `-Drcr.datalog=<csv>` + `-DNOSECURITY=true`（仅本地 datagen；正常对战沙箱拒绝文件写、自动降级关闭）。
需要 Python 3.11 + `pip install torch --index-url https://download.pytorch.org/whl/cpu` + numpy。

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
- **阶段 1.3 KNN 双枪（通用 + anti-surfer）——已完成（2026-07-05）**：
  - **通用枪**：大容量（6 万环形）全量数据、无衰减、开火/虚拟波同权——打非自适应走位；
  - **anti-surfer 枪**：只信最近数据——小容量环形缓冲（2000）+ 样本年龄指数衰减（半衰期 300 条 ≈ 1/3 回合），虚拟波权重 0.05（冲浪者只对真子弹躲避学习，虚拟波里几乎没有它的躲避反应）；
  - **虚拟枪框架**：每个真实开火波记下两把枪当时的预测 GF，波到达时按核距离记衰减软分（比二值命中 EMA 稳）；通用枪为默认，AS 枪须领先 0.05 且 ≥50 个开火波才接管——分差在噪声带内时换枪只会两头吃亏（margin 0.01 版实测反而更差）；
  - 3 场 100 回合平均：vs BasicGFSurfer **74%**（1.2 基线 70%）；vs Komarious 58%（59%，噪声内持平，AS 枪对它很少接管）；50 回合 testbed 平均 **83.9%**，Cigaret 54%→**64%**；
  - 健康自检：0 skipped turns（每 tick 双库查询无压力）；对非冲浪对手（DuelistMini / RamFire）`asFired=0`——AS 枪从不无谓接管。诊断量 `gunMain/gunAS/asFired` 已写入 `stats.txt`。
- **阶段 1.4 能量管理（规则版）——已完成（2026-07-05）**：
  - 规则：基础 1.95；**打得准才打重**（整场命中率 ≥50% → 2.95、≥33% → 2.45；命中率 >1/3 开火才是能量正回报）；近距 <140 → 2.95；**能量差缩放**（中远距落后 >10 时每点降 0.02、下限 1.2，拖长回合等对手先垮）；击杀经济精确反解（p≤1 伤 4p / p>1 伤 6p−2）；<20 能量按 1/10 收缩防 disable；
  - 试过并**放弃**：滚动窗口命中率（连中片段冲过阈值误触发重弹，重弹更慢逃逸角更大，vs BasicGFSurfer 跌 7%）；阈值 0.25（同因误触发）；<300 无条件 2.45；击杀 +4 伤害余量（更差，精确反解本身够用）；
  - 实测（3×100 平均）：vs BasicGFSurfer ~70%、vs Komarious ~57-59%，与 1.3 在噪声带内持平——本阶段收益主要在弱走位对手的终结速度（Tracker 命中率 0.80 → 全程 2.95 重弹）与劣势局的能量续航；`hitRate/myShots` 已入 `stats.txt`。
- **阶段 1.5 bullet shadows（被动）+ gunheat waves + 冲浪统计升级——已完成（2026-07-05）**：
  - **Bullet shadows**：按引擎语义（每 tick 双方位移线段相交判定）精确计算我方每颗飞行中子弹在敌波上挡出的 GF 安全区，`ShadowTest` 暴力采样自检 3901 例 0 失配；阴影按 bin 遮蔽比例折扣该 bin 的统计质量；子弹死亡（命中/对撞/撞墙）时撤销尚未成立的阴影；
  - **Gunheat waves**：跟踪敌人枪热（开火 +1+p/5，每 tick 冷却 0.1），预计 ≤2 tick 内开火时用其当前位置/上次功率立预测波提前冲浪，消掉「检测滞后 1 tick + 反应 1 tick」的裸奔窗口；真波到达即替换，对手憋枪则滚动重建；
  - **冲浪统计升级**（为达标 85+ 补做）：命中 GF 统计按「我的横向速度 × 敌我距离」3×3 分段（对手的分段 GF 枪按局面打我们，躲避也必须按局面记）+ 命中滚动衰减 ×0.75（记忆深度 ≈4 次命中，跟上对手换瞄准解）——BasicGFSurfer 70%→**83%** 主要来自这一项；
  - 试过并**放弃**：KDE 近邻按特征距离加权 1/(1+√d)（testbed 掉 1%，回退）；
  - **达标验证**：3 轮 50 回合 testbed 平均 **85.3% / 86.6% / 87.8%**（目标 85+ ✓）；3×100 平均：BasicGFSurfer **83%**、Komarious **66%**、Cigaret **61%**（无 problem bot <60% ✓，Cigaret 贴线待攻坚）；0 skipped turns，`shadowPieces/gunheatWaves` 计数已入 `stats.txt`。
- **阶段 1（第 3–8 周，目标前 20 / ~87–88 APS）——全部完成**：~~precise prediction + precise intersection~~ → ~~距离控制 + wall smoothing~~ → ~~KNN 双枪（通用 + anti-surfer）~~ → ~~能量管理（基础 power ~1.95 规则）~~ → ~~被动 bullet shadows + gunheat waves~~。
- **阶段 2.1 离线梯度下降学 KNN 嵌入权重（枪）——已完成（2026-07-05）**：
  - **数据管道**：`KnnGun` 在 `-Drcr.datalog=<csv>`（配 `-DNOSECURITY=true`）时把每个到达的枪波写盘（8 特征, gf, 车身 GF 半宽, 是否实弹）；`datagen.ps1` 对 11 个走位风格各异的对手（追身/直线/随机/环绕/冲浪/躲避）× 2 场 × 35 回合批量采集，共 **245,664** 波；
  - **新特征**：近 8 tick 位移 `disp8`（BeepBoop 特征集常客），DIMS 7→8；
  - **训练**（`ml/train_gun_weights.py`，PyTorch CPU 约 1 分钟）：可微 soft-KNN——距离 `‖w⊙(fᵢ−fⱼ)‖²` 取 softmax 注意力，对候选 GF 按「落进 query 车身窗口」的高斯核算命中概率，损失 = −log P；候选只取同场更早的波（模拟运行时只见过过去），实弹 query 3× 权重，`softplus` 保证 w>0，从手工权重出发 Adam 3000 步；
  - **离线验证**（留出场，与运行时一致的硬 KNN top-50 + KDE 峰值，车身窗口命中率）：整体 **0.322 → 0.341（+5.7%）**；11 个对手 8 升 3 微降（SpinBot +0.133、BasicGFSurfer +0.030、Komarious +0.028；微降的是本就 0.8+ 的 Tracker/RamFire 和 Walls）。学到的口径：bft 和接近速度权重大幅上调，|横向速度|/加速度大幅下调；
  - **实战验证**：3×100 平均 vs BasicGFSurfer **87%**（1.5 基线 83%）、Komarious **66.7%**（66%）、Cigaret **62.7%**（61%）、DuelistMini 83%（84%，噪声内）；50 回合 testbed **86.8%**（85.3–87.8 带内）。冲浪/躲避系全线不掉、标杆对手 +4，改进立住；
  - **走位权重**：当前走位统计是 3×3 分段 bin 而非 KNN，没有嵌入可学——留到把走位统计升级成 KNN 型（或 flattener）时一并学，与「瞄准优先，其次走位」的优先级一致。
- **阶段 2.2 能量管理升级为期望得分最大化（BeepBoop 路线）——已完成（2026-07-05）**：
  - **模型**（`PowerSelector.java`，移植 BeepBoop `BulletPowerSelector`）：对每档候选功率，假设双方按当前命中率持续对射——(1) 每 tick 能量流（开火消耗 p/冷却期、命中返还 3p、被弹伤害）线性估计回合剩余时长；(2) 拉格朗日乘子解出「打平能量战」的临界命中率组合，二项分布正态近似算双方越线概率 → 回合胜率；(3) 期望得分 = 伤害 ×(1+0.2×胜率) + 60×胜率，**前 5 回合按 APS 百分比口径（含跨回合累计近似分）取比值、之后取差值**，选期望最高的功率；
  - **双方命中率跟踪**：`Tracker` 带先验 1/12、按逃逸角折算到候选功率（敌方命中率另设 ≤0.15 且不高于我方的开局假设）；敌方射击结算接进波系统（命中/飞过/对撞），gunheat 预测波不计；
  - **候选功率门控**（BeepBoop 的 antiBasicSurfer 开关）：第 0 回合或命中率置信上界 >0.2 用 **ABS 粗表** {2.45⁻,1.95,1.45,0.95,0.65,0.45,0.15}（x.45/x.95 下沿利用 BasicSurfer 系子弹速度取整 bug，榜上大量机器人中招）；躲得好的对手（上界 ≤0.2）换 37 档细表做能量战优化。A/B 实测：细表打 BasicGFSurfer 掉 4-5%（丢掉 bug 利用），纯 ABS 表打 Komarious 掉 2-3%（丢掉细粒度省弹）——**门控两头都要**；
  - 保留硬规则：<140 全功率 2.95、精确击杀反解、<5 能量不打让出能量领先的子弹（1.4 的 ≥33%/≥50% 命中率阈值、能量差缩放规则全部退役，由模型统一接管）；未移植：tryToDisable（打残后撞击补分）、镜像/护盾策略联动；
  - **实测（3×100 平均）**：Komarious 66.7% → **69.7%**（模型对省弹型对手会自动降功率拖能量战）；BasicGFSurfer 86.3%（87.0%，持平）；DuelistMini 83.7%（83.0%，持平）；Cigaret 61.3%（62.7%，方差带内）；sample 系（RamFire/SpinBot/Fire）99-100% 无回归；0 skipped turns；`pwrMy/pwrEn/powerHist` 已入 `stats.txt`。
- **阶段 2.3 主动子弹阴影（active bullet shadowing，BeepBoop Aimer 思路）——已完成（2026-07-05）**：
  - **机制**：临开火 tick（枪冷 ≤1 tick 且允许开火）不再直接打 KDE 峰值，而是在候选开火 GF 里选 `aimScore / danger^β` 最优者。候选 = KDE 高分近邻 GF（top-8、间隔 ≥0.04）+「有用阴影角」（解二次方程求能拦截「瞄着我预测被扫位置的敌方子弹」的开火角，即在我将经过的 GF 上挡出阴影）；
  - **danger 假设检验**：冲浪评估每 tick 缓存三选项 ×（第一/第二波）的精确覆盖窗口与撞墙/俯冲因子，对每个候选角用 `shadowIntervals` 算假想阴影、重算窗口质量（`dangerAfterHypotheticalShot`），取三选项最小——即「这发子弹铺出阴影后我的走位有多安全」；
  - **权重 β** = (敌命中率/我命中率 × 敌功率/我功率)^0.25：对手打不中我或只打小弹时少为阴影牺牲命中率（复用 2.2 的 PowerSelector.Tracker）；
  - **护栏**：候选命中分 <0.35× 峰值直接弃——能量战里白扔一发对紧平衡对手是净亏（不设门实测 Komarious −4）；
  - 试过并**放弃**：β 前置系数 2（BeepBoop 原值，我们的 danger 口径含 ε 底数，对比度比它的 bin 危险大，系数 2 过度让利命中率）；炮管本 tick 可达候选 ×1.1（BeepBoop 有，我们实测 BasicGFSurfer −2 回退）；
  - **实测（v4 定稿，6×100 平均）**：BasicGFSurfer 86.3% → **87.0%**（含单场 90 峰值；主动阴影收益本就集中在强 surfer）；Komarious 67.0%、Cigaret 61.0%、DuelistMini 82.7%（均在方差带内）；sample 系 99-100%；**0 skipped turns**（每 tick 12 次精确预测 + 临开火 ≤10 次假想阴影重算，CPU 仍有余量）；`activeShadowShots`（改角次数，Komarious 300 回合 ≈800 次 ≈8% 射击）已入 `stats.txt`；
  - 注：本地 testbed 对 rumble 全场分布低估此项——榜上 surfer 密度远高于我们 8 bot 组，Kev 点名该创新「对强 surfer 收益最大」。
- **阶段 2.4 Flattener（滚动命中率门控）——已完成（2026-07-13）**：
  - **机制**：敌枪**滚动**命中率（最近 40 发）>12% 时开启（<9% 关闭，滞回；窗口满 30 发才判），把飞过的敌波按我当前位置记为伪命中，抬高「我刚走过的 GF」危险，压平躲避轮廓，让 pattern-matching / 学走位的 GF 枪失效；
  - **弱权重伪命中**：`stats[x] += 0.15 / ((x−i)²+1)`，**不**做 0.75 衰减——全权重伪命中会冲掉真实命中尖峰，冲浪变噪声，敌命中率升高后 flattener 锁死开启（死亡螺旋：BasicGFSurfer 87%→78%）；
  - **滚动 vs 累计**：累计命中率门控会在开局连中后永久偏高，同样锁死；滚动窗口能在躲开后及时关掉；
  - 试过并**放弃**：经典 DrussGT 式「整场 HR>9% + 全权重 logHit」（上述死亡螺旋）；
  - **实测（3×100 平均）**：Cigaret 61% → **64.0%**（pattern matcher，目标收益）；Komarious 67% → **71.7%**；BasicGFSurfer 85.7%（87%，噪声内）；DuelistMini 83.7%（持平）；50 回合 testbed **86.9%**；0 skipped turns；`flattenVisits/flattener/enemyHRroll` 已入 `stats.txt`。
- **阶段 2（第 2–4 月，目标前 5 / 90+ APS）**：~~离线梯度下降学 KNN 嵌入权重（PyTorch 训练、导出常数进 Java）~~ → ~~期望得分最大化能量管理~~ → ~~主动子弹阴影（active bullet shadowing）~~ → ~~flattener（命中率门控）~~ → 走位统计 KNN 化 + 嵌入权重学习。

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
