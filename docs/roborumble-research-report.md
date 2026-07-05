# 经典 Java Robocode 1v1 RoboRumble 冲榜深度研究报告

## TL;DR
- **平台选择**：留在经典 Java 版 Robocode / RoboRumble（LiteRumble）。截至 2026 年 7 月 5 日榜单仍在活跃更新（当天每分钟都有新对战上传），拥有 1213 个机器人；Robocode Tank Royale 虽是官方"下一代"且物理规则相近，但其官方教材《The Book of Robocode》明确写道它"Does not have a community competition system like RoboRumble and LiteRumble yet"（还没有像 RoboRumble/LiteRumble 那样的社区竞赛系统），因此对"冲 RoboRumble 榜"这个目标而言迁移毫无意义。
- **务实路线**：APS（对全部 1200+ 机器人的平均得分）由"虐菜"能力主导，而非打赢前 5 名。当前榜首 kc.mega.BeepBoop 2.0 的 APS 高达 95.11，靠的是 GrubbmGait 所说的"Getting at least 70% against everyone"（对每个对手都至少拿 70%）。进前 20（约 87–88 APS）靠一个干净无 bug 的 GoTo/True 波面冲浪 + KNN(DC) 双枪即可；进前 5（90+ APS）必须把能量管理、bullet shadow、anti-surfer 枪、precise intersection 全部做对。
- **击败顶尖 vs 冲 APS 是两回事**：即便 Diamond 对 DrussGT 也只能拿约 44%（作者 Voidious 原话："DrussGT is officially by far Diamond's worst matchup now (at like 44%)"）。与其钻研如何"打赢" BeepBoop，不如复用开源的 BeepBoop / Diamond / Saguaro 代码作为架构起点，把机器学习投入在最高杠杆的两处——离线梯度下降学习 KNN 嵌入权重（瞄准）和能量管理的期望得分最大化。

---

## Key Findings

**1. 当前榜单（LiteRumble, roborumble, 2026-07-05，1213 bots）前十：**

| 排名 | 机器人 | 作者 | APS | PWIN | Survival |
|---|---|---|---|---|---|
| 1 | kc.mega.BeepBoop 2.0 | Kev (clarkkev) | 95.11 | 100.00 | 99.26 |
| 2 | jk.mega.DrussGT 3.1.12 | Skilgannon | 92.15 | 99.83 | 98.02 |
| 3 | oog.mega.saguaro.Saguaro 1.0 | CrazyBassoonist | 91.91 | 98.43 | 95.30 |
| 4 | aaa.r.ScalarR 0.005h.053-noshield | Xor | 91.36 | 99.92 | 97.89 |
| 5 | voidious.Diamond 1.8.22 | Voidious | 90.28 | 99.67 | 97.54 |
| 6 | cb.fire.Firestarter 2.0f | — | 88.23 | 99.67 | 96.75 |
| 7 | dsekercioglu.mega.Raven 3.56j8 | Dsekercioglu | 87.88 | 99.26 | 94.40 |
| 8 | xander.cat.XanderCat 12.9 | Skotty | 87.87 | 98.84 | 94.11 |
| 9 | lxx.Tomcat 3.68 | Jdev | 87.83 | 99.34 | 95.29 |
| 10 | rsalesc.mega.Knight 0.6.28 | rsalesc | 87.82 | 99.67 | 96.41 |

值得注意的是 BeepBoop 的 APS (95.11) 领先第二名 DrussGT (92.15) 近 3 个百分点——这在顶部密集区（第 6 到第 15 名挤在 86.7–88.2 之间）是巨大的鸿沟。

**2. BeepBoop（榜首，95.11 APS）为什么这么强：** 它是唯一系统性使用"主动子弹阴影 (active bullet shadowing)"的顶级机器人——对强敌约 40% 的射击不打最可能命中的角度，而是打能给自己制造安全阴影区的角度。瞄准用 KNN + 核密度估计，其嵌入函数（特征权重 w(x+b)^|a|）用离线随机梯度下降（Adam, lr=1e-3）训练而非手调。能量管理直接最大化"回合结束时的期望得分"。走位用 A* 搜索式的"路径冲浪 (path surfing)"，融合 True Surfing 与 GoTo Surfing。开源在 github.com/clarkkev/beep-boop。

**3. 已知弱点/机会窗口：**
- BeepBoop 的最大真实弱点是 **skipped turns（跳过回合）**：作者 Kev 与 Xor 都证实它在较老/开了 turbo boost 的 RoboRumble 客户端上会掉 0.2–0.3 APS，因为它偶尔超时。它是重 CPU 机器人。这是它唯一的系统性弱点，但那是实现/硬件层面的，你无法在对战中主动触发。
- **子弹屏蔽 (bullet shielding)** 对"射击角度精确可预测"的机器人异常有效；DrussGT 历史上一度靠 bullet shielding 才勉强守住第一（GrubbmGait："DrussGT only clings on the first place due to its Bullet Shielding"）。但顶级活跃机器人（DrussGT/ScalarR/BeepBoop/Saguaro）都已加入 shielding 防护或 shielding 模式，所以这条路对付顶尖机器人基本封死（注意 #4 的名字是 ScalarR "**-noshield**"）。
- **Rambot（冲撞流）** 对顶级机器人无效：Skilgannon 在 RoboWiki Talk:PureAggression 页面确认历史最强 rambot GrubbmThree 仅排 #226，并直言"So no, they aren't really competitive."顶级 surfer 用垂直/orbit 走位轻松化解。

---

## Details

### 一、物理与游戏规则（对策略有影响的要点）

**运动物理：**
- 最大速度 8 units/tick；加速度 +1/tick，减速度 −2/tick（刹车比加速快，这是走位可以突然反向的物理基础）。
- 车身转速 = (10 − 0.75·|velocity|) 度/tick——速度越快转得越慢；满速 8 时只能转 4°/tick。炮塔转速 20°/tick（叠加在车身之上），雷达转速 45°/tick（叠加在炮塔之上）。
- 车身是 36×36 的不旋转正方形。

**子弹物理：**
- 子弹速度 = 20 − 3·power（power∈[0.1,3.0]）。最快 19.7（power 0.1），最慢 11（power 3.0）。
- 伤害 = 4·power，若 power>1 再加 2·(power−1)。命中返还能量 = 3·power。
- 开火产生枪管热量 = 1 + power/5；gunHeat>0 不能开火；默认冷却 0.1/tick；每回合初始 gunHeat=3.0。所以 power 3 每 16 tick 才能开一枪。
- 撞墙伤害 = max(0, |velocity|·0.5 − 1)；撞车双方各受 0.6 伤害，冲撞（向前撞）方得 ramming bonus。

**最大逃逸角 (MEA)：** = arcsin(8/bulletSpeed)。GuessFactor = 实际命中角偏移 / MEA，范围 [−1,+1]。这是所有 GF 瞄准与波面冲浪的基础。

**每 tick 处理顺序（对精确预测至关重要）：** (1) 所有子弹先移动并判定碰撞（含本 tick 发射的）；(2) 所有机器人移动：gun→radar→heading→acceleration→velocity→distance；同时 gunHeat 递减；(3) 扫描；(4) 恢复机器人行动；(5) 处理事件队列。**关键推论**：setFire() 时子弹以"当前"炮管朝向射出（在炮管转动之前），所以要精确瞄准必须在上一 tick 把炮管转到位。此外敌人开火决策所依据的数据在能探测到其能量下降的 **2 tick 前**就已确定——这就是 gunheat waves 的物理来源（可提前 2 tick 开始冲浪，DrussGT 与 BeepBoop 都利用了这一点，对近距离战斗尤其关键）。

**场地与得分：** RoboRumble 1v1 标准场地 800×600，每场 35 回合。得分构成：子弹伤害（1 分/点伤害）+ 击杀 bonus（对该敌造成总伤害的 20%）+ 存活分（每有 1 个敌人死亡而你存活 +50）+ 最后存活 bonus（10×敌数）+ 冲撞伤害（2 分/点）+ 冲撞击杀 bonus（30%）。
- **APS (Average Percentage Score)** = 你在每个配对里的"得分百分比"= yourScore/(yourScore+enemyScore)，先对每个对手求平均，再对全部对手求平均。这是 RoboRumble 主排名。**关键**：每个对手权重相同，所以对 1200 个弱敌各多拿 1% 和对 BeepBoop 多拿 1% 价值完全一样——虐菜能力主导排名。
- **PWIN**：按每个配对"是否胜率>50%"计的 Condorcet 式排名（打赢每个对手就是 100）。
- **ANPP (Average Normalised Percentage Pairs)**：把每个配对按全场最高/最低分归一化后再平均，最能暴露"problem bots"。
- 代码大小分级（由编译字节码 codesize 决定）：NanoBot <250B，MicroBot <750B，MiniBot <1500B，MegaBot ≥1500B（无上限）。顶级 1v1 全是 MegaBot。

### 二、顶级机器人算法剖析与弱点

**kc.mega.BeepBoop 2.0（Kev，95.11 APS）——当前之王**
- **走位（Path Surfing）**：搜索由 {前进=1, 后退=−1, 停 =0} 组成的路径序列，先像 True Surfing 那样考虑全 1/全 −1/全 0，再像 GoTo Surfing 那样找低危险区并生成到达路径；末端速度随机化；用 A* 搜索。前两波用 Precise Prediction + Precise Intersection，第三波用近似危险估计。
- **危险度（Crowd Surfing）**：借鉴 Diamond，融合多个 KNN 危险估计系统，按敌人命中率动态加权（把高分给"敌人实际开火处"的系统权重更高）；只用子弹碰撞而非命中来调权，避免选择偏差。还内置对 HOT/线性/圆形/平均线性/当前GF 等简单瞄准的自我模拟，几乎完美闪避简单枪。
- **瞄准**：KNN + 核密度估计，嵌入函数离线梯度下降学习。据 Kev 本人披露，其瞄准输出分布为 `softmax(t * log(histogram + abs(b)))`，其中 t、b 为可学习参数（初始化为 1 和 1e-4）。Anti-surfer 枪用"did-hit"特征降低命中前后波的权重。惊人的是学出来的 anti-surfer 枪并不重视 recency（与传统认知相反）。
- **主动子弹阴影**：据《BeepBoop/Understanding BeepBoop》原文，它先"produces a set of 20 or so candidate firing angles...angles that shield its current movement plan, and angles sampled at random from the distribution over guessfactors produced by its aim model"（约 20 个候选射击角：最可能命中的、遮蔽当前走位的、按 GF 分布随机采样的），再综合"命中概率 + 危险降低"打分。对强敌约 40% 的射击放弃最优命中角以换取阴影安全区。
- **能量管理**：用 Lagrange 乘子求解会导致输的命中率，再用二项分布正态近似估计达到该命中率的概率，选择最大化期望得分的 power——涌现出"落后时打高 power 搏一击"的行为。
- **弱点**：重 CPU，会 skipped turns 掉分；这是唯一可利用的系统性弱点。作者自评它每 tick 平均耗时 <80% DrussGT、<50% Diamond，但 99.9 百分位的最慢 tick 略慢于 DrussGT。

**jk.mega.DrussGT 3.1.12（Skilgannon，92.15 APS）——霸榜 2008 至 2021**
- **走位**：GoTo Surfing（类似 Minimum Risk Movement 但额外计算"去往该点途中的危险"）。用 Apollon 的 precise prediction 生成候选点，第一波用 precise intersection、第二波用更快的单点交点。
- **统计系统（招牌）**：100+ 个并行 VCS buffer，每个随机选 5 个属性、随机切片；另有 50 个常规 flattener buffer 和 20 个 tick-flattener buffer；171 个 bin，超低 rolling average (0.5–1.5) 保证高适应性。三大优化（检索索引、惰性初始化、只存最近命中的 bin 索引队列）让它跑得起来。
- **flattener**：常规 + tick-flattener（每 tick 生成波）+ anti-bullet-shadow flattener。当敌命中率超过加权阈值 9% 时全部启用。
- **枪**：KNN 双调（静态/自适应）+ 遗传算法调权 + anti-surfer virtual gun + random gun 兜底。用 Manhattan 距离而非 Euclidean。
- **弱点/过拟合**：它用的是"上古" VCS 而非 KNN 走位（作者本人试过 DC 但性能更差）；多 buffer 被评论认为是"DrussGT 最弱的部分"。bullet shielding 只撑到受到 50 点子弹伤害就退回常规走位。它对 Diamond 的压制极强（Diamond 对它只有 ~44%），但它整体已被 BeepBoop 拉开近 3 APS。

**oog.mega.saguaro.Saguaro 1.0（CrazyBassoonist，91.91 APS）——2025/2026 黑马**
- 采取与其他顶级机器人不同的路线：**8 模式多模态机器人**。用置信区间跟踪每个模式的真实平均得分——差模式几回合就被排除，接近的模式则长时间测试。模式含 ScoreMax、BulletShielding、PrecisePrediction、以及 **WavePoison**（波面投毒）等。
- 大量学习 BeepBoop/DrussGT/Diamond 的开源代码；用 Rednaxela 的 kd-tree + FastMath；bullet shielding 模式基于 BeepBoop/EnergyDome；"最大化期望得分"思路来自 BeepBoop；50%-加权 bullet shadow 和 precise MEA 估计也学自 BeepBoop。跨对战保存模式探索数据和 KNN 权重（但不保存原始观测）。开源在 github.com/oogilbert/Saguaro。它证明了"多模态 + 在线模式选择"是一条可与单一强算法抗衡的新路线。

**aaa.r.ScalarR（Xor，Kotlin 编写，91.36 APS）**
- 用 Kotlin 写。提出通用冲浪框架（把冲浪抽象成树搜索，只需填 4 个方法即可切换 Minimum Risk / True / GoTo 策略）；是首个在 melee 实现 1v1 式波面冲浪（含第二波冲浪）的机器人。用遗传算法学习 KNN 权重。榜上以 "-noshield"（关闭子弹屏蔽）版本参赛。

**voidious.Diamond 1.8.22（Voidious，90.28 APS）——开源标杆**
- 1v1：波面冲浪 + Dynamic Clustering。预测顺/逆时针/刹车三种走位在敌波上的交点，取最低危险；第二波再分叉。按时间到达和 power 加权，乘距离因子。
- 双枪 DC：一支调对 surfer、一支调对非自适应。用高斯核密度估计。代码整洁，是学习 DC 的最佳开源范本（github.com/Voidious/Diamond）。

**其他值得研究：** WaveSerpent（用 segmentation 但不用 VCS）、Gilgalad（开源 GoTo 冲浪）、XanderCat（DC + 目标探测走位，自评最怕 DrussGT/Diamond/Tomcat）、Tomcat（PIF 枪 + 独特走位日志，对弱敌强、对强敌不可预测）、Raven（据信也用梯度下降学 KNN）、Knight/Roborio（rsalesc）、darkcanuck.Pris/Gaff（神经网络瞄准，Pris 是"用神经网络做瞄准和走位的最强机器人"）。

### 三、核心算法技术解析与对比

**波面冲浪：True Surfing vs GoTo Surfing**
- True Surfing：每 tick 从少数移动选项（前进/停/后退）中选危险最低的执行。
- GoTo Surfing：生成一堆目标点，计算"若开去该点波会在哪命中我"，选整条路径危险最低的。DrussGT 用 GoTo，BeepBoop 用融合两者的 path surfing。
- **社区共识**：算法风格远不如"干净、精确、调优良好的实现"重要。Voidious 和 Skilgannon 都认为 DrussGT 用 True Surfing 也能一样强。**新手不要纠结选哪种，先把一种做到零 bug。**
- 关键细节：precise prediction（逐 tick 模拟自己的转向/加速/移动直到波命中）、precise intersection（算出波与车身相交的角度宽度）、多波处理（第二波加权低）、bin smoothing、危险函数要极快（会被调用海量次）。

**瞄准算法谱系：**
- GuessFactor Targeting + VCS segmentation（经典，DrussGT 走位仍用）。
- Dynamic Clustering (DC/KNN)：k 近邻找相似局面 + 核密度估计定角。自 2007 年起顶级机器人主流。
- Play-It-Forward / Pattern Matching：对 wave surfer 效果一般，但对 pattern movement 仍最强。
- **Anti-Surfer 枪**：核心是"快速衰减数据 + 只用/高权重最近数据 + 降低非开火波权重"。社区反复试验的结论是"没有什么比只用最近数据的 GF 枪更好"；一旦对手开启 flattener，几乎无计可施——此时不如换距离/power 或开自己的 flattener。
- **Virtual Guns**：并行跑多支枪，用"虚拟子弹"统计各枪命中率来选枪。顶级配置通常是"通用枪（低/无衰减）+ anti-surfer 枪（高衰减）"。注意 VG 只统计真实开火波才对 surfer 有意义。

**神经网络/机器学习在 Robocode 的现实定位：**
- 历史：Engineer（2006，首个 NN 瞄准破 2000 分）、Chase-san 的 Prototype、darkcanuck 的 Gaff（waves + GF + RBF + 双 NN）、Pris（NN 瞄准+走位）。
- **现代最有效的 ML 用法不是端到端 RL，而是离线学习 KNN 嵌入权重**：据 RoboWiki《Innovations since 2005》页面，两种主流做法并存——"Tuning KNN models with genetic algorithms - Used with great success by DrussGT and ScalarR!"（遗传算法调 KNN 权重，DrussGT 和 ScalarR 用得极成功）和"Gradient-based learning of KNN models - KNN models can be learned using gradients as well!"（梯度学习 KNN，BeepBoop 采用）。BeepBoop 用 WaveSim 导出数据，用梯度下降（Adam）学习特征权重 w(x+b)^|a|，交叉熵损失对准"敌人实际所在的 GF 分布"。作者试过用完整神经网络替代但"效果没更好"。
- **务实结论**：学术界的 Deep Q-Learning / 端到端 RL Robocode 论文在 RoboRumble 竞技层面毫无竞争力。ML 应聚焦两处高杠杆环节：(1) 离线学 KNN 瞄准/走位嵌入权重；(2) 能量管理期望得分建模。训练在 Java 外部（如 PyTorch）离线做，把学出的权重导出为常数写进 Java——绕开 Robocode 的 CPU 时间限制。

**Bullet Shadows（子弹阴影）：**
- 被动：自己发射的子弹会拦截敌方子弹，因此敌波上被自己子弹覆盖的区段是"安全区"。DrussGT 用逐 tick play-forward 实现。
- **正确实现的细节（BeepBoop 揭示）**：因为 Robocode 按随机顺序遍历子弹判定碰撞，子弹可能撞上另一子弹的"上一"线段——《BeepBoop/Understanding BeepBoop》原文给出正确做法："Add a shadow for the bullet at t and the wave at t. Then add 50%-weighted shadows for the bullet at t and wave at t-1 and for the bullet at t-1 and wave at t."（除给 (bullet_t, wave_t) 加阴影外，还要给 (bullet_t, wave_{t−1}) 和 (bullet_{t−1}, wave_t) 各加 50% 权重的阴影。）
- 主动：见 BeepBoop，主动选制造阴影的射击角。这正是作者 Kev 亲自点名的下一个突破方向——他在讨论串中说："My guess for the next innovation that could improve bots is active bullet shadowing. Instead of always shooting at the angle your aiming model gives as most likely to hit, it is probably better to sometimes shoot at an angle that is l[ower]..."（下一个能提升机器人的创新我猜是主动子弹阴影：与其总打命中概率最高的角度，不如有时打命中概率略低但能制造阴影的角度）。这是当前最前沿、最值得投入的方向。

**能量管理与火力选择：** 保守 power 提升 survival 但损失同等 bullet damage，总分往往打平。最优做法是像 BeepBoop 那样直接建模期望得分。对已 disabled 的敌人应冲撞（ramming bonus 更高），BeepBoop 甚至会故意只 disable 不击杀以便冲撞刷分。

**Flattener（曲线压平器）：** 当敌枪命中率超阈值（DrussGT 是 9%）才启用；把每个经过的波都记为命中，压平自己的统计轮廓让对方学习枪失效。代价是对简单枪略微降低闪避，所以要按命中率门控。

### 四、平台现状与社区活跃度（2026 年 7 月）

- **RoboRumble/LiteRumble 仍活跃**：榜单显示 1213 个机器人，2026-07-05 当天每分钟都有新对战上传，标注"Rankings Stable"。LiteRumble（Skilgannon 维护）是 RoboRumble 的当前载体。
- **RoboWiki 仍活跃**：BeepBoop、Saguaro（页面 2026-04-13 更新）、DrussGT 讨论页都有 2024–2026 年的活跃讨论。
- **客户端要求**：RoboRumble 客户端目前固定用 robocode 1.9.4.2；社区建议用 Java 17（最新 LTS，仍带 SecurityManager）跑客户端；机器人本体建议保持 Java 8 兼容（多数产生评分的客户端跑 Java 8）。参赛规则：唯一包名、无下划线、Robot→Package robot for upload 打包、在 RoboRumble/Participants 页加一行"botname,jar_url"。
- **Robocode Tank Royale**：官方"下一代"，基于 WebSocket、多语言、确定性回合（不再有线程竞争导致的 skipped-turn 随机性）。物理规则相近但角度/坐标系改为标准数学约定。**但它明确"还没有像 RoboRumble/LiteRumble 那样的社区竞赛系统"**——没有可冲的榜。经典版持续维护（1.11.0，2026-06-06 发布，已支持 Java 24+）。

---

## Recommendations（分阶段、按优先级）

**平台决策（立即）：** 留在经典 Java Robocode + RoboRumble。用户目标是冲 RoboRumble 榜，而 Tank Royale 没有竞技榜。除非未来 Tank Royale 建立起活跃 rumble，否则不要迁移。搭好本地测试环境：Robocode 1.9.4.2 客户端 + RoboResearch（或 RoboRunner）跑批量对战，选一个覆盖不同强度的 test bed。

**阶段 0：架构起点（第 1–2 周）。** 不要从零造轮子——社区反复强调"从别人现在的位置出发"（"starting from where others are now is more efficient than trying to reinvent the wheel"）。以 **Diamond 开源代码**（最整洁、DC 走位+双枪的完整范本）或直接研读 **BeepBoop / Saguaro 开源代码** 为骨架。先用 Wave Surfing Tutorial 的 BasicGFSurfer 理解最小可用冲浪。目标：先做出一个零 bug 的 GoTo 或 True 冲浪 + KNN(DC) 单枪，能稳定进榜中游（BasicGFSurfer 在榜上约 70.7 APS，可作为最低及格线）。

**阶段 1：达到前 20（目标 ~87–88 APS，第 3–8 周）。** 优先级排序：
1. **干净的 precise prediction + precise intersection**（两波冲浪）——这是分数地基。
2. **距离控制 (distancing)** + wall smoothing——立刻拉高对全体的存活。
3. **KNN 双枪：通用枪 + anti-surfer 枪**（后者只用最近数据、高衰减、低权重非开火波）。
4. **能量管理**：先用合理规则（近距离/高命中率打 power ~2.95，否则按能量差缩放，参考 DrussGT 的基础 power 1.95 逻辑），别过度保守。
5. **Bullet shadows（被动）** + gunheat waves（提前 2 tick 冲浪）。
   —— 衡量基准：本地 test bed APS 稳定在 85+，且没有 problem bot 掉到 60% 以下。

**阶段 2：冲前 5（目标 90+ APS，第 2–4 月）。** 此时"虐菜"已到顶，必须在难点上超车：
1. **离线用梯度下降学习 KNN 嵌入权重**（瞄准优先，其次走位）——这是 BeepBoop 相对 DrussGT 的核心增量，用 PyTorch 训练、导出常数进 Java，绕开 CPU 限制。这是 ML 最高杠杆的投入点。（也可选遗传算法路线，DrussGT/ScalarR 已证明其"极成功"。）
2. **能量管理升级为期望得分最大化**（BeepBoop/Saguaro 路线）。
3. **主动子弹阴影 (active bullet shadowing)**——作者 Kev 亲自点名的"下一个能提升机器人的创新"，当前尚未被广泛复制，对强 surfer 收益最大。
4. **Flattener**（按命中率 9% 左右门控启用）。
   —— 衡量基准：对 BeepBoop/DrussGT/Diamond 的配对 APS 能到 40%+，对全体无 <70% 的配对。

**关于"击败特定顶级机器人"的现实预期：** 不要以打赢 BeepBoop/DrussGT 为主要 KPI。即便 Diamond 对 DrussGT 也只 ~44%（Voidious："DrussGT is officially by far Diamond's worst matchup now (at like 44%)"）。APS 排名靠的是对全体的平均，一个对顶级机器人只拿 35% 但对全体极稳的机器人，排名会高于一个专门克制 BeepBoop 却在别处漏分的机器人。唯一可针对 BeepBoop 利用的系统性弱点是 **skipped turns**——但那是它的实现/硬件问题，你无法在对战中主动触发。

**明确不要做的事（避免"为复杂而复杂"）：**
- 不要做 rambot 或 mirror movement 冲榜（顶级 surfer 完全免疫；GrubbmThree 这样的最强 rambot 仅 #226，Skilgannon 直言"not really competitive"，且会拖累对全体 APS）。
- 不要指望 bullet shielding 打顶尖——所有活跃顶级机器人已有防护，ScalarR 甚至专门出了 -noshield 版本。shielding 只对"射击角度精确可预测"的中低端机器人有奇效，可作为 Saguaro 式多模态里的一个可选模式，但别作为主力。
- 不要走端到端深度强化学习路线——学术论文级 DQL 在 RoboRumble 无竞争力。
- 不要在 True vs GoTo 之间纠结——先把一种做到零 bug 远比选型重要。

---

## Caveats
- **榜单是动态的**：APS 数字（尤其小数点后）随对战持续进行会波动，且受运行客户端的硬件影响（skipped turns 会让重 CPU 机器人如 BeepBoop 掉 0.2–0.3 APS）。本报告数字为 2026-07-05 的快照。
- **problem-bot 精确配对数据未能取得**：LiteRumble 的 BotPairings 页面因 URL 编码问题（空格被转为 +）无法直接抓取，因此 BeepBoop/DrussGT 各自"得分最低的 10 个对手"的精确百分比未能核实。报告中关于弱点的判断基于 RoboWiki 讨论页的定性证据（skipped turns、bullet shielding 易感性、rambot 无效性）而非精确配对表。如需精确 problem bot 列表，需用能保留 %20 编码的工具抓取 literumble.appspot.com/BotPairings 并按分数升序排列，或查 Wayback Machine 快照。
- **Saguaro 的 WavePoison 模式细节未能取得**：该子页面无法访问，"波面投毒"的具体机制未核实，仅知其为 Saguaro 8 个模式之一。
- **开源代码许可**：DrussGT 明确禁止未经许可用于 RoboRumble 以外的任何竞赛（RoboRumble 例外）；Diamond、BeepBoop、Saguaro 开源，Saguaro 用 RWPCL 许可。学习算法思路没问题，但直接复制代码参赛需遵守各自许可并注明出处。
- **ML 训练成本**：离线梯度下降/遗传算法学权重需要大量对战数据和多个 season 的验证；Kev 与 Xor 都指出验证 0.1 APS 级别的改进需要在完整 1200 机器人群体上跑很多 season，在普通笔记本上一次要几小时。此外 Kev 证实：anti-surfer 枪改用采样（而非取 argmax）虽提升了 BeepBoop 自战命中，但"scores dropped against weaker surfers, so the change didn't improve APS"——说明任何针对顶尖的改动都必须在全体群体上验证，别只盯着顶尖对战。
