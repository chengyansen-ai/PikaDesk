# PikaDesk 本机引擎与响应性能验收（2026-08-28）

## 结论

本机开发镜像已经完成“启动即加载”的真实闭环：界面能读到本机构建的官方 master，并同时保存最新稳定 Release 作为回退；两套 NNUE 在中文安装路径下分别绑定；个人精选 OBK 优先命中，ChessDB 补缺，单引擎分析持续输出深度、PV、分数和 NPS；窗口在查询与分析期间保持可交互，退出后没有残留应用或引擎进程。

这里的“最新稳定版”指 2026-08-28 能从 Pikafish 官方 Releases 验证的 `Pikafish-2026-01-02`。另外已从官方 `master@b97ef0f9` 本机构建 AVX-VNNI PGO 并配套 `master-net`。本机计时配对与 NPS 结果倾向 master，因此开发镜像将它设为默认；固定节点配对打平，稳定版继续完整保留为回退。该选择不作“任何机器、任何时限下绝对最强”的不可验证宣传。

## 本机运行配置

| 项目 | 配置 |
|---|---|
| 默认引擎 | Pikafish dev-20260826 `b97ef0f` AVX-VNNI PGO |
| 稳定回退 | Pikafish 2026-01-02 AVX-VNNI |
| NNUE | 默认与候选各用自己的官方匹配网络，不混用 |
| 线程 | 12 |
| 哈希 | 1,024 MiB |
| 候选线 | MultiPV 3 |
| 普通单步预算 | 固定时间 1,500 ms，可在界面修改 |
| 开局知识 | 622,727 行个人精选 OBK 优先；本地缺失时 ChessDB，超时 1,800 ms；再回退引擎 |
| 安装位置 | `D:\象棋\PikaDesk\target\windows-app-image\PikaDesk` |

引擎与 NNUE 放在同一目录，向引擎发送相对 `EvalFile`。这是必须的兼容处理：Windows 原生引擎收到包含中文的绝对路径时会发生编码损坏，并在首次计算时退出；相对文件名已在 `D:\象棋` 下用真实引擎输出验证。

## 实测数据

测试主机为 Intel Core i7-14700KF、28 个逻辑处理器、约 64 GiB 内存。除单独标明外，均从最终 app-image 测量。

| 测试 | 结果 |
|---|---|
| 加入双引擎与 55.8 MB 本地库后的五次窗口就绪 | 1,675–1,864 ms，平均 1,733.5 ms |
| 加入双引擎与本地库后的五次默认引擎进程就绪 | 1,167–1,373 ms，平均 1,245.7 ms |
| 本地 OBK 打开 20 次 | 中位 1.31 ms，P95 1.93 ms；首次冷路径最大 140.47 ms |
| 初始局面本地 OBK 查询 1,000 次 | 11 着；平均 20.06 ms，中位 19.93 ms，P95 21.06 ms，最大 27.43 ms |
| 五次 UCI 握手 | 平均 289 ms |
| UCI + NNUE + 12 线程 + 1 GiB Hash + MultiPV 3 就绪 | 平均累计 650 ms |
| 五次 `go movetime 1000` | 平均 999 ms，应用外引擎协议没有可见额外延迟 |
| ChessDB 标准开局五次请求 | HTTP 200，均含合法 `move`；747–914 ms，平均 803 ms |
| 最终界面开局库显示 | 173 ms 读到 18 个可见“云库”来源单元 |
| 最终界面分析输出 | 点击后 1,454 ms 出现深度/PV/NPS；样例深度 14、MultiPV 3、约 10.47 MNPS |
| 引擎官方 `bench`（深度 12、49 局面） | 19,691,126 节点，1,472 ms，13,377,123 NPS |
| master 单线程固定 5,000,000 节点 | 平均 NPS 比稳定版高 7.80% |
| master 按应用 12 线程/1 GiB/1,500 ms 五轮 | 平均 NPS 比稳定版高 9.70%；仍不等同棋力证明 |
| master 对稳定版计时配对 | 合计 52 局：master 5 胜、46 和、1 负；决定局少，不作为正式 Elo |
| master 对稳定版固定 20,000 节点配对 | 40 局：3 胜、34 和、3 负；固定节点下未测出净优势 |
| 最终首次启动真实分析 | 深度 23、MultiPV 3、约 12.37 MNPS；子进程精确为 master `b97ef0f` |
| 最终本地库界面查询 | `PikaDesk-个人精选-20260828.obk` 显示 9 个初始局面候选 |
| 窗口状态 | 云库和分析期间均为 `ReadyForUserInteraction` |
| 退出清理 | 应用与 Pikafish 残留进程均为 0 |

初始局面命中本地精选库时不需要等待网络，典型数据库查询约 20 ms。只有本地缺失才需要参考五次直接请求的 803 ms 网络均值；断网或超时不会卡住 JavaFX 界面，查询运行在虚拟线程，随后回退到本地引擎。

## 已做的性能与稳定性修正

- 把云库结果切回 JavaFX Application Thread 后再修改表格，消除后台线程直接碰 UI 的竞争。
- 关闭默认的逐行 UCI 和整段云库响应打印；需要诊断时可显式开启 `pikadesk.engine.trace`、`pikadesk.book.trace` 和 `pikadesk.http.trace`。
- 云库查询和引擎分析保持后台虚拟线程，不阻塞主界面。
- 本机默认由 5 秒调整为 1.5 秒，兼顾响应和计算深度；分析模式仍可无限分析。
- 每次正式分析前写入 Threads、Hash 和搜索模式，实测引擎输出确认使用 12 线程并加载 NNUE。
- 关闭窗口时同时回收皮卡鱼子进程，五次启动和最终 E2E 均无残留。

## 开局库取舍

没有把社区里来源不清或没有明确再分发许可的所谓“最强 XQB/OBK”直接塞进软件。当前本地库以用户提供的标准 SQLite OBK 为主，经 `vscore>=3`、字段校验和同键同着确定性择优后缩成 622,175 行；再从 CC BY 4.0 的 CCPD 开局语料中，只对主库完全空白的局面补入 552 行，形成 622,727 行个人精选库。ChessDB 继续负责本地缺失。详细证据见 [`opening-book-audit-2026-08-28.md`](opening-book-audit-2026-08-28.md)。

因此当前达到的是“用户自有输入可追溯、启动即用、本地优先、网络补缺、失败可回退”的开局能力，不是对某个商业私有棋库的复制。

## 尚不能声称“全面优化”的部分

- 1 GiB Hash 加 12 线程在本机分析时引擎工作集约 1.79 GiB；对 16 GiB 或更低内存机器应降低到 256–512 MiB、4–8 线程。
- ChessDB 是网络服务，延迟和可用性不由 PikaDesk 控制；真正纯离线需要用户提供许可清楚的本地棋库。
- 目前完成的是冷启动、UCI、云库、首次分析、界面响应和进程清理的针对性验收，尚未完成长时多局压力、低内存、断网抖动与所有 DPI 组合的完整性能矩阵。

## 可复核来源

- Pikafish 稳定发布：<https://github.com/official-pikafish/Pikafish/releases/tag/Pikafish-2026-01-02>
- Pikafish 稳定版与 master：<https://github.com/official-pikafish/Pikafish/compare/Pikafish-2026-01-02...master>
- ChessDB 项目说明：<https://www.chessdb.cn/cloudbook_info_en.html>
- ChessDB API：<https://www.chessdb.cn/cloudbook_api_en.html>
- ChessDB 公开源码：<https://github.com/noobpwnftw/chessdb>
- JavaFX `Platform.runLater` 线程契约：<https://openjfx.io/javadoc/23/javafx.graphics/javafx/application/Platform.html#runLater(java.lang.Runnable)>
