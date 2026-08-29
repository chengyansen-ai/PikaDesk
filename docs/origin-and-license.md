# PikaDesk 来源、归属与许可证说明

> 本文解释“代码从哪里来、我们具体做了什么、哪些内容只作参考、哪些内容不进入仓库”。
> 它是工程审计说明，不替代任何许可证原文或法律意见。

## 结论先行

PikaDesk 不是从零开始的 clean-room 项目，也不是对商业软件的二进制修改。它以 TCHESS 的 GPL v3 代码为直接基线，在此基础上独立完成产品定位、安全策略、测试与验收体系，以及一批新的或重构的工作台能力。所有直接继承、协议集成、研究参考和本地数据，都按不同类别记录，避免把“参考过”写成“复制了”，也避免把“独立维护”写成“没有上游”。

## 代码与数据关系

| 类别 | 项目、维护者或组织 | PikaDesk 的实际关系 | 许可证/约束 | 是否进入 Git 源码 |
|---|---|---|---|---|
| 直接代码基线 | [TCHESS / public-Xiangqi](https://github.com/sojourners/public-Xiangqi)，`sojourners` | JavaFX 工作台、棋盘规则、基础引擎/棋谱/识别能力及部分资源的固定基线；固定提交 `2d41525095639548059ebd930b0af4d29efc1364` | 上游随附 GNU GPL v3；本项目按 `GPL-3.0-only` 保守标记 | 是，带归属与 GPL 义务 |
| 引擎协议与本机验收 | [Pikafish](https://github.com/official-pikafish/Pikafish)，`official-pikafish` 组织 | 通过公开 UCI/UCCI 管理外部引擎进程；以官方 Release/master 做本机握手、配对与性能验收 | Pikafish GPL v3；NNUE 另有合法使用、非商用和反在线作弊条件 | 引擎源码、EXE、NNUE 均否 |
| 本地开局语料 | [Chinese Chess Practical Dataset](https://github.com/Yvonne761/Chinese-Chess-Practical-Dataset)，Yu-Han Tseng / `Yvonne761` | 仅在本机读取 `Dataset/开局`，进行 Big5 解码、逐着合法性审计、去重和个人库空白补全 | CC BY 4.0；使用时需署名、保留许可证链接并说明变更 | 否，原始语料和个人库均不提交 |
| 商业产品公开对照 | 鲨鱼象棋公开帮助、下载页和格式说明 | 用于识别公开功能维度、格式边界和 UX 目标；用于黑盒对照时只观察本机界面 | 不构成代码或素材授权 | 否，不包含任何鲨鱼内容 |
| 算法与工程研究 | [MRXqOpeningBook](https://github.com/nguyenpham/MRXqOpeningBook)、[oobs](https://github.com/nguyenpham/oobs)，`nguyenpham` | 交叉研究中文记谱建库、不可逆哈希和权重处理的设计边界 | MIT；只作算法参考 | 否，不复制代码或数据 |
| 格式与工具研究 | [eleeye](https://github.com/xqbase/eleeye)，`xqbase` | 研究 PGN 到开局库的历史流程与兼容边界 | LGPL；未经逐文件复核不引入其样例/数据 | 否 |
| 桌面交互研究 | [VinXiangQi](https://github.com/Vincentzyx/VinXiangQi)、[cn-croissant](https://github.com/haruka411/cn-croissant)、[en-croissant](https://github.com/franciscoBSalgueiro/en-croissant)、[chessboard](https://github.com/atopx/chessboard) 等 | 比较 Windows 交互、分支树、多引擎、数据库和识别状态机的公开思路 | 逐仓库、逐文件核对；许可证或资源不清时只保留研究结论 | 否，不复制代码、模型或样例 |

GitHub 账号/组织是可追溯的维护者标识，不等同于全部自然人作者或版权人名单。精确来源、版本、哈希、社区证据和不纳入理由见 [source-ledger.md](source-ledger.md) 与 [third-party.md](third-party.md)。

## PikaDesk 独立实现与维护的工作

本项目的关键价值不是把多个工具放进同一个窗口，而是把它们收束为可以证明安全、可维护且可回退的工作流。PikaDesk 团队在现有基线之上独立设计、实现或重构并持续维护的内容包括：

- 本地优先的默认配置、资源引导、中文路径打包和可复现 Windows `app-image`；
- 多引擎会话与资源预算、MultiPV 比较、共识/分歧工作区；
- 分支棋谱树、格式网关、错误报告、受限旧格式迁移与跨进程恢复；
- 开局库质量筛选、XQB 批处理、语料审计、个人库补全与许可证隔离；
- 通用窗口目录、授权会话、识别闸门、合法性校验、单步输入、视觉确认和失败闭锁；
- 默认只读的实时陪练模式，及对公共排位平台专用适配的明确拒绝；
- Windows SAPI 本地语音：受限文本、8 条有界队列、类别开关、故障熔断和离线验收；
- JUnit 特征测试、真实 Windows 探针、性能基线、哈希清单、发布门和完整文档体系。

这些内容是 PikaDesk 的新增工程成果，但不会改变 TCHESS 衍生关系，也不会转移 Pikafish、数据集或依赖库的版权。

## 开源协议如何适用

### 1. 仓库代码：GNU GPL v3

PikaDesk 承接 TCHESS 的 GPL v3 代码基线，因此仓库整体以 [GNU GPL v3](../LICENSE)（`GPL-3.0-only`）分发。你可以运行、研究、修改和再分发代码；若分发修改后的程序或对应副本，应遵守 GPL v3，包括保留许可证与版权声明、提供相应源码，并以兼容的 GPL v3 条款分发衍生代码。

“GPL 开源”不表示一切随仓库接触到的内容都自动改为 GPL，以下边界必须分别处理。

### 2. 外部引擎、网络与依赖

- Pikafish 源码遵循其上游 GPL v3；PikaDesk 仅以公开协议启动用户自行取得的引擎。若未来把 EXE 随安装包分发，还需履行其源码和通知义务。
- Pikafish NNUE 的合法使用、非商用与反在线作弊条件独立于 GPL 代码许可。不得把它误称为可无条件商用或公共平台实时辅助资源。
- JavaFX、JNA、ONNX Runtime、JNativeHook、SQLite JDBC 和 SLF4J 等依赖分别保留自己的许可证。版本、哈希和原始许可入口列在 [third-party.md](third-party.md)。

### 3. 数据、模型与资源

- CC BY 4.0 棋谱语料不随仓库发布；若分发衍生数据，必须满足署名、许可证链接和变更说明要求。
- 用户提供的 OBK、筛选库和个人开局库均是本地数据，不进入 Git。
- `yolov11.onnx` 的训练数据/脚本来源和 `chessman.ttf` 的单独许可证尚未闭环。它们是公开二进制发布阻断项，不应被解释为已经获得自由再分发许可。

### 4. 商业软件与公平使用

鲨鱼象棋仅作为公开资料和用户体验对照对象；PikaDesk 不包含其可执行文件、会员功能、私有源码、素材、品牌、授权数据或绕过逻辑。公共平台排位实时辅助、自动走棋、托管和反检测不属于本项目能力。

## 发行状态

当前 GitHub 仓库提供完整源代码、测试、规格和审计记录，适合研究、构建和协作。它**不是**已经完成所有第三方授权审查的公开安装包。任何面向公众的二进制发行，都必须先完成模型/字体来源核实或替换、最终 SBOM、第三方许可证全文、引擎/NNUE 分发审查和全量回归。

如需商用、闭源集成、分发二进制或合并外部棋谱/模型，请自行取得专业法律意见并重新核对各项许可证原文。
