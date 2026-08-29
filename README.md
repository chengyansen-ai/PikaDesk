# PikaDesk

**把中国象棋分析、棋谱研究、多引擎比较和授权棋盘实验，放回一台真正由你掌控的电脑。**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](docs/development.md)
[![Tests](https://img.shields.io/badge/tests-361_total%2C_4_opt--in-brightgreen.svg)](docs/release-readiness.md)
[![Status](https://img.shields.io/badge/status-development_preview-yellow.svg)](CHANGELOG.md)

PikaDesk 是一个完全免费、本地优先的中国象棋桌面工作台。它把局面分析、五引擎并行、分支棋谱、开局库、多格式读写和经过授权的本地棋盘自动化组织成一套可运行、可测试、可审计的工程系统。

项目不把“界面能打开”当作完成，也不靠模糊的功能清单证明能力。每项关键工作流都必须落到明确状态机、失败闭锁、资源上限、自动化测试和真实 Windows 验收；证据不足的兼容性或发布条件，会被明确标记为未完成。

> PikaDesk 是独立社区项目。它不是鲨鱼、TCHESS 或 Pikafish 的官方产品，也未获得这些项目或产品的背书。上游代码、引擎、模型、字体和棋谱样例分别遵守各自许可证与使用条件。

## 为什么需要 PikaDesk

传统象棋工具常把引擎、棋谱、识别、自动输入和平台连接混在一起：用户不知道数据是否离开本机，也难以判断一次自动落子为何成功或失败。PikaDesk 把这些问题变成可检查的工程契约：

- **默认本地**：核心分析不依赖账号，新安装不主动启用云能力；
- **引擎解耦**：通过 UCI/UCCI 管理 Pikafish 等独立引擎进程，不把某个引擎写死在界面里；
- **证据优先**：局面、着法、窗口身份、坐标、用户活动和落子结果逐层验证；
- **失败即停止**：识别冲突、窗口变化、失焦、非法着法或视觉确认超时均停止输入；
- **格式不猜测**：不完整的外部格式证据会产生拒绝或兼容警告，不静默伪造数据；
- **公平使用边界**：公共平台排位实时辅助、自动走棋、挂机和反检测不属于项目能力。

## 已实现能力

| 能力 | 当前结果 | 工程边界 |
|---|---|---|
| UCI/UCCI 引擎接入 | 可用 | 启停、超时、崩溃隔离、输出上限均有测试 |
| 五引擎并行分析 | 已验证 | MultiPV、独立资源、排序、共识与分歧 |
| 分支棋谱工作台 | 已验证 | 主线、变例、注释、评估、失误与安全保存 |
| PGN/XQF/CBR/TXQ | 分级支持 | 无法表示的字段会明确拒绝或报告 |
| XQB v1 开局库 | 已验证 | 批量导入、去重、进度、取消与断点恢复 |
| 个人 OBK 构建 | 已验证 | 用户自有主库过滤；CC BY 4.0 语料严格解码、逐着校验、去重与空白补全 |
| 时间策略与脚本 DSL | 内核通过 | 有界预算与白名单能力，不执行任意脚本 |
| 授权本地棋盘自动化 | 开发预览 | 单步闭环和 406 半回合耐久证据；尚未完成 1,000 半回合门槛 |
| 正式二进制发行 | 暂未开放 | YOLO 模型来源与字体许可仍需关闭发布门 |

## 六段实施链

```mermaid
flowchart LR
    A[1 研究与定界] --> B[2 独立产品化]
    B --> C[3 分析与棋谱]
    C --> D[4 授权自动化闭环]
    D --> E[5 Windows 真实验收]
    E --> F[6 许可审计与开源交付]
```

1. **研究与定界**：核对公开功能、开源项目、协议和国内使用场景，先确定合法能力与拒绝范围。
2. **独立产品化**：重建 PikaDesk 身份、本地优先默认值、配置边界和可复现构建环境。
3. **分析与棋谱**：实现多引擎、分支树、评估、格式适配、开局库和跨进程恢复。
4. **授权自动化闭环**：将识别、合法性、决策、单步输入和视觉确认拆成失败闭锁状态机。
5. **Windows 真实验收**：验证中文路径、DPI、红黑方向、真实引擎进程、网络端点和进程残留。
6. **许可审计与开源交付**：扫描历史与敏感文件，记录第三方来源，用独立根提交发布完整源码。

完整实施经过、失败记录和取舍理由见[《PikaDesk 是怎样被做出来的》](docs/project-history.md)。其他智能体接手前应先阅读 [AGENTS.md](AGENTS.md)。

## 验证状态

截至 2026-08-29：

| 验证项 | 结果 |
|---|---|
| Maven 全量测试 | **361** 个测试，零失败、零错误；4 个本机资产验收用例按设计默认跳过并已显式运行通过 |
| Windows 中文路径 | `D:\象棋\PikaDesk` 构建、测试、打包通过 |
| 开发镜像 | 236 项 UTF-8 SHA-256 清单逐文件复算一致 |
| 默认引擎 | 官方 `master@b97ef0f9` 本机 AVX-VNNI PGO；稳定 Release 完整回退 |
| 个人开局库 | 622,727 行，SQLite `quick_check=ok`，同键同着重复 0 |
| 本地启动网络观察 | TCP/UDP 端点为 0 |
| 五个 Pikafish 进程 | 并行分析通过，退出后无残留 |
| 授权棋盘单步 | 红/黑方向与 100%/150% 测试缩放通过 |
| 自动化耐久 | 连续确认 406 半回合；检测到用户输入后安全停止 |

这些结果证明当前代码中的工程契约，不代表所有主题、DPI、外部棋盘、引擎版本或未来操作系统都已验证。证据入口见[交付就绪报告](docs/release-readiness.md)、[自动化 E2E](docs/automation-e2e.md)和[公开功能对照矩阵](docs/shark-vip-parity-matrix.md)。

## 快速开始

需要 JDK 21。Maven 已由 Wrapper 固定，无需全局安装。

```powershell
git clone https://github.com/chengyansen-ai/PikaDesk.git
cd PikaDesk
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd --batch-mode verify
.\mvnw.cmd --batch-mode javafx:run
```

启动完全离线的自动化测试棋盘：

```powershell
.\mvnw.cmd --batch-mode javafx:run@local-test-board
```

### 只读实时陪练

主界面的连接模式默认是“只读陪练”。先打开自己的本地人机棋盘并保持完整可见，再点击链形“连线”按钮，用准星选择目标窗口。检测到已验证的经典棋盘后，PikaDesk 会使用当前窗口范围自动完成无输入预校准；你只需核对本地目标并启动。工具会自动识别红黑棋子和棋盘方向，把目标窗口已经发生的着法写入本地棋谱，并用 Pikafish 的 PV1 高亮推荐着法。独立状态栏会持续显示选窗、校准、同步、等待走子和停止状态。

只读陪练不会点击或控制目标窗口，推荐着法也不会提前写入真实棋谱；图片只在本机内存中处理。当前实机验证范围是经典圆形棋子主题，其他皮肤识别不确定时会暂停。

生成本机 Windows 开发镜像：

```powershell
.\scripts\package-windows.ps1
.\target\windows-app-image\PikaDesk\PikaDesk.exe
```

当前镜像仅用于本地开发验收；资源许可门关闭前，不应把它作为公开安装包分发。

## 系统结构

```text
PikaDesk
├─ JavaFX 工作台：棋盘、分析、棋谱树、开局库与配置向导
├─ 引擎边界：UCI/UCCI、Pikafish、多引擎会话与资源预算
├─ 棋局边界：规则、FEN、分支树、评估与格式适配器
├─ 自动化边界：授权目标、识别闸门、坐标、单步输入与视觉确认
└─ 可信边界：默认关闭、资源上限、失败闭锁、脱敏与紧急停止
```

规格、实施计划、研究记录和关键决策分别位于 [SPEC](docs/SPEC.md)、[任务计划](task_plan.md)、[研究记录](findings.md)与 [ADR](docs/decisions/)。

## 项目立场

PikaDesk 希望把专业象棋研究能力做得更透明、更可控，而不是把商业软件换一个名字。项目可以研究公开工作流、兼容公开协议，也会认真吸收 TCHESS、Pikafish 与中国象棋社区的工程成果；但产品身份、设计决策、测试体系和新增实现由本项目独立维护。

自动化只服务于本人拥有、离线或明确授权的棋盘环境。已知 JJ、天天象棋等公共排位目标在校准前直接拒绝。公共平台棋局应在结束后主动导出，再用于本地复盘。详细边界见[威胁模型](docs/threat-model.md)。

## 贡献、安全与许可证

- 贡献前阅读[贡献指南](CONTRIBUTING.md)，涉及自动输入的改动必须保持授权边界和失败闭锁；
- 漏洞通过[安全政策](SECURITY.md)中的私密渠道报告，不要公开发布利用细节；
- 本项目是 TCHESS 的 GPL v3 衍生作品，整体采用 **GNU GPL v3（`GPL-3.0-only`）**；
- 运行、研究、修改和再分发均须遵守 [LICENSE](LICENSE)，分发修改版本时须履行对应源码和同许可证义务；
- 第三方来源、固定版本、模型/字体阻断项见 [NOTICE](NOTICE.md) 与[第三方清单](docs/third-party.md)。

主要上游：[TCHESS / public-Xiangqi](https://github.com/sojourners/public-Xiangqi) · [Pikafish](https://github.com/official-pikafish/Pikafish)
