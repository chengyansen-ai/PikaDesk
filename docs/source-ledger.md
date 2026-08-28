# 来源台账

所有实现决策优先引用官方仓库、官方文档和源代码。社区项目用于比较，不把宣传文案当成已验证实现。

| 来源 | 类型 | 用途 | 当前证据级别 |
|---|---|---|---|
| <https://github.com/official-pikafish/Pikafish> | 官方源码 | UCI/UCCI 引擎、GPLv3、构建说明 | 官方；2026-08-26 在线核实 |
| <https://github.com/official-pikafish/Pikafish/releases/tag/Pikafish-2026-01-02> | 官方稳定发布 | 本机 AVX-VNNI 引擎、NNUE 与归档摘要 | 官方；2026-08-28 再次核实并完成真实 UCI/界面验收 |
| <https://github.com/official-pikafish/Pikafish/compare/Pikafish-2026-01-02...master> | 官方源码比较 | 稳定 Release 到当前开发分支的提交与文件差异 | 官方；2026-08-28 显示 250 提交、92 文件变化 |
| <https://github.com/official-pikafish/Pikafish/commits/master/> | 官方提交历史 | 固定当前候选 `b97ef0f9eb15bd99899b272e0236bfebf86313b6` 与最新提交时间/主题 | 官方；2026-08-28 在线核实并浅克隆固定提交 |
| <https://github.com/official-pikafish/Networks/releases/tag/master-net> | 官方开发网络 | 与当前 `master` 配套的 NNUE 候选 | 官方；只与开发候选成对验证，不和 2026-01-02 二进制混用 |
| <https://github.com/official-pikafish/Pikafish/actions/runs/32874284030> | 官方 CI 构建 | 固定 `b97ef0f...` 的成功 Windows/Linux/macOS/Android 构建和候选制品 | 官方 Actions；2026-08-28 本机经 GitHub API 核实成功且制品未过期 |
| <https://www.msys2.org/docs/installer/> | MSYS2 官方安装说明 | 在 `local-assets` 隔离准备与官方 CI 同类的 clang64/make 构建环境 | 官方；要求校验发行摘要/签名，不修改系统级 PATH |
| <https://github.com/msys2/msys2-installer/releases/tag/2026-06-11> | MSYS2 官方发行 | 固定本机隔离构建工具基线和 GitHub 资产摘要 | 官方；SFX SHA-256 `c105946e...1d65` 与摘要文件、GitHub digest 三方一致 |
| <https://www.msys2.org/docs/ci/> | MSYS2 官方 CI 说明 | 交叉核对官方 Pikafish Windows Actions 的 `msys2/setup-msys2` 构建路径 | 官方；2026-08-28 在线核实 |
| <https://github.com/official-pikafish/Pikafish/blob/master/src/uci.cpp> | 官方源码 | 核实 `go` 支持双方剩余时间、增益、剩余步数、固定时长、深度与节点等边界参数 | 官方源码；2026-08-26 在线核实 |
| <https://github.com/official-pikafish/Pikafish/blob/master/src/timeman.cpp> | 官方源码 | 核实引擎内部区分最优/最大时间，并扣除 Move Overhead、约束当前步最大占用 | 官方源码；2026-08-26 在线核实 |
| <https://github.com/official-pikafish/Pikafish/blob/master/src/search.cpp> | 官方源码 | 核实搜索中还会按评估下滑、最佳着稳定性与节点投入动态提前停止 | 官方源码；2026-08-26 在线核实 |
| <https://github.com/official-pikafish/Networks/blob/master/README.md> | 官方权重许可 | NNUE 合法使用与非商用限制 | 官方；2026-08-26 在线核实 |
| <https://www.chessdb.cn/cloudbook_info_en.html> | ChessDB 官方说明 | 持续由引擎分析扩充的中国象棋开局与残局知识库 | 官方；2026-08-28 在线核实 |
| <https://www.chessdb.cn/cloudbook_api_en.html> | ChessDB 官方 API | `queryall` 请求、FEN 参数与返回字段 | 官方；2026-08-28 在线核实并完成五次真实请求 |
| <https://github.com/noobpwnftw/chessdb> | ChessDB 公开源码 | 服务实现、数据工程与 Public Domain 声明 | 项目源码；2026-08-28 在线核实 |
| <https://bbs.pikafish.org/forum.php?fid=41&mod=forumdisplay> | 皮卡鱼官方论坛开局库版 | 发现兵河大型库、云库单机版、用户库等社区候选和使用反馈 | 社区发现证据；下载帖本身未证明每个库的数据权属或再分发许可，暂不纳入合并 |
| <https://www.sharkchess.com/archives/545> | 鲨鱼官方 XQB 说明 | 核实 OBK 哈希不可逆、OBK→XQB 无法完美全量转换，以及新建库建议采用可逆 XQB | 官方产品/格式说明；2026-08-28 在线复核 |
| <https://github.com/CGLemon/chinese-chess-PGN> | 社区公开棋谱集合 | 发现 41,743 盘世界象棋联合会棋谱与 99,813 盘东萍棋谱，作者称已过滤非法着但未检查长照、长捉和重复 | 社区质量说明；仓库首页未给出清晰数据再分发许可证，暂不下载或并入 |
| <https://github.com/Yvonne761/Chinese-Chess-Practical-Dataset/tree/368a47a947773dd8692c026e286dd19b6277b993> | CC BY 4.0 中国象棋实战数据集 | 固定 `Dataset/开局` 的 Big5/CP950 中文记谱；逐着合法性审计、去重和个人库空白补全 | 许可明确；961 文件中 828 通过、133 拒绝，682 条唯一合法线；数据本身不提交本仓库 |
| <https://www.wxf-xiangqi.org/index.php?Itemid=313&id=218&lang=en&option=com_content&view=article> | 世界象棋联合会棋谱目录 | 核对 1990—2025 比赛记录覆盖 | 官方目录；未看到批量再分发授权，因此未抓取或并入 |
| <https://github.com/nguyenpham/MRXqOpeningBook> | MIT 开局库工具源码 | 交叉参考中文记谱建库算法与未完成边界 | 源码许可明确；2018 v0.1，不作为棋谱数据源 |
| <https://github.com/nguyenpham/oobs> | MIT 开局库构建源码 | 交叉参考不可逆哈希开局库和权重处理 | 源码许可明确；只作算法参考，不复制数据 |
| <https://github.com/xqbase/eleeye> | LGPL 开源象棋引擎与工具 | 参考由 PGN 制作 `BOOK.DAT` 的历史开局库流程及随仓库样例 | 源码许可明确；仍需逐文件确认棋谱/BOOK.DAT 的数据许可和格式转换价值后才能纳入 |
| <https://github.com/sojourners/public-Xiangqi> | 开源源码 | 主线 GUI、连接器、识别、格式和开局库 | 已检查本地提交 |
| <https://github.com/Vincentzyx/VinXiangQi> | 开源源码 | Windows 配置/识别/点击交互参考 | 已检查源码，非主线 |
| <https://github.com/haruka411/cn-croissant> | 开源源码 | 分支树、MultiPV、数据库和现代桌面 UX | 已检查源码 |
| <https://github.com/franciscoBSalgueiro/en-croissant> | 开源源码 | 多引擎、数据库、成熟桌面结构 | 已检查仓库元数据 |
| <https://github.com/atopx/chessboard> | 开源源码 | 截图 + ONNX 状态机 | 已检查；模型缺失 |
| <https://github.com/dffge552/xiangqi-pwa-offline> | 开源源码 | 离线识别模型和 Pikafish WASM | 待逐文件许可审计 |
| <https://github.com/imbatony/electorn-chinese-chess> | 开源源码 | 引擎生命周期和超时测试场景 | 许可证标注不一致，不复制代码 |
| <https://github.com/fisherfan/xqbook/tree/93964abe35543e4338c3501618240ac191436d30> | 原作者公开格式证据 | XQB v1 的 SQLite 表、可逆局面键、镜像和真实两行样例 | 固定提交已检查；仓库无许可证，不复制源码或样例，只作互操作事实依据；样例 SHA-256 `721e5470...16b` |
| <https://www.sharkchess.com/archives/author/admin> | 鲨鱼官方格式说明 | 核实 OBK 为无源码的魔改 SQLite、8 字节哈希与跨平台限制，以及官方建议新库采用标准 SQLite 的 XQB | 官方；2026-08-27 在线核实 |
| <https://github.com/fengyunnc/fy/tree/f7d72009def464dcb795a3853b23c2f9b55fafa9> | 公开兵河技术资料 | 交叉核对 OBK 的 Zobrist 键、红方常数、初始局面键、16 位着法、表名和查询字段 | 固定提交已检查；仓库没有身份/许可证证明、真实样例或建表语句，只作互操作事实依据 |
| <https://pf.stkme.com/Blog/View/1610/data.htm> | 鹏飞官方开局库页面 | 核实鹏飞软件可把兵河库转换为鹏飞库 | 官方产品层证据；2026-08-27 在线核实，不是格式规范 |
| <https://pf.stkme.com/Blog/View/2293/data.htm> | 鹏飞官方移动版说明 | 核实移动版只接受鹏飞格式，其他格式需先由电脑版转换 | 官方产品层证据；2026-08-27 在线核实，不是格式规范 |
| <https://pf.stkme.com/Blog/View/1611/data.htm> | 鹏飞官方功能说明 | 核实软件支持兵河库、加密商业库并提供自有加密工具，据此隔离未加密标准容器与未知加密变体 | 官方产品层证据；2026-08-27 在线核实 |
| <https://www.sharkchess.com/buy> | 官方产品对比 | VIP 相对免费版的 13 项公开优势 | 官方；2026-08-26 在线核实 |
| <https://www.sharkchess.com/download> | 官方下载/更新日志 | 免费版 1.8.1、VIP 2.5.2、版本维护政策与新功能 | 官方；2026-08-26 在线核实 |
| <https://www.xqbase.com/protocol/cchess_pgn.htm> | 历史公开规范 | 中国象棋 PGN 的 Game/Format/FEN/结果/注释与分隔规则 | 规范原始发布页；2026-08-26 在线核实 |
| <https://www.xqbase.com/protocol/cchess_move.htm> | 历史公开规范 | ICCS 坐标及 UCCI 四字符简化形式 | 规范原始发布页；2026-08-26 在线核实 |
| <https://github.com/femto/cchesslib/blob/master/doc/XqfFormat.txt> | XQStudio 作者历史公开格式文档的仓库副本 | XQF 1.0 标记、头字段、32 子坐标、固定第 0 步、主线记录、评注长度及“不支持变着”边界 | 原作者署名格式文档；2026-08-26 在线核实 |
| <https://www.wxf-xiangqi.org/images/free_download_books/Short_manual_Simple_translation_of_CCBridge_20130824.pdf> | WXF 工具手册 | 国内 XQF 文本读取的 GB2312/GBK 与 Big5 现实差异 | 社区工具文档；2026-08-26 在线核实，仅用于编码风险提示 |
| <https://www.xqinenglish.com/images/Downloads/Short_manual_Simple_translation_of_CCBridge_20130826.pdf> | CCBridge 用户手册英文简译 | 核实 CCBridge 对 CBR/CBF/CBL、变例、注释与保存能力的产品层说明 | 社区发布的工具手册；2026-08-26 在线核实，不是二进制格式规范 |
| <https://github.com/walker8088/cchess/tree/de8648346227e7dbf8bd4ea5a17db88bb937af57> | GPL-3.0 社区源码与真实样例 | 交叉核对 CBR v2 头、UTF-16LE 字段、棋盘、结果、行棋方和递归变例记录；固定 `test.cbr`、`test2.cbr` 哈希用于回归 | 社区反向实现；固定提交已检查，不视为官方规范 |
| <https://github.com/sojourners/public-Xiangqi/blob/2d41525095639548059ebd930b0af4d29efc1364/src/main/java/com/sojourners/chess/manual/TxqChessManualImpl.java> | TCHESS 固定提交源码 | 确认旧 TXQ 直接以 Java 对象流读写 `ChessManual`，据此限定只读迁移入口 | 上游固定源码；2026-08-27 检查 |
| <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/ObjectInputFilter.html> | Oracle Java 21 API | `ObjectInputFilter` 的类、数组、深度、引用数和流字节过滤契约 | 官方 API；2026-08-27 在线核实 |
| <https://docs.oracle.com/en/java/javase/21/core/java-serialization-filters.html> | Oracle Java 21 安全指南 | 反序列化过滤默认不启用、应采用上下文专用允许规则和资源上限 | 官方指南；2026-08-27 在线核实 |
| <https://www.sharkchess.com/sharkhelp/tableContents_list.html> | 官方帮助目录 | 对弈、拆棋、连线、引擎、开局库、方案和设置的任务清单 | 官方；2026-08-26 在线核实 |
| <https://maven.apache.org/tools/mavenwrapper.html> | Apache Maven 官方文档 | Maven Wrapper 的组成、固定版本和 Windows 调用方式 | 官方；2026-08-26 在线核实 |
| <https://maven.apache.org/tools/wrapper/index.html> | Apache Maven Wrapper 官方文档 | only-script 类型与分发包 SHA-256 校验 | 官方；2026-08-26 在线核实 |
| <https://maven.apache.org/download.cgi> | Apache Maven 官方下载 | 选择当前推荐稳定版 Maven 3.9.16 | 官方；2026-08-26 在线核实 |
| <https://docs.junit.org/current/user-guide/> | JUnit 官方指南 | Maven BOM、Jupiter TestEngine 与 Surefire 集成 | 官方；2026-08-26 在线核实 |
| <https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html> | Maven Surefire 官方文档 | JUnit Platform 测试发现与单测命令 | 官方；2026-08-26 在线核实 |
| <https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml> | Maven Central 官方元数据 | 固定 JUnit 5 最新稳定版 5.14.4 | 官方制品元数据；2026-08-26 核实 |
| <https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal&image_type=jdk&jvm_impl=hotspot&os=windows&vendor=eclipse> | Adoptium 官方 API | Temurin JDK 21 Windows x64 下载元数据与 SHA-256 | 官方；2026-08-26 核实 |
| <https://github.com/microsoft/onnxruntime> | 官方源码/许可证 | ONNX Runtime 1.19.2 的 MIT 许可证与第三方通知入口 | 官方仓库 + Maven 构件 POM；2026-08-26 核实 |
| <https://github.com/java-native-access/jna> | 官方源码/许可证 | JNA/JNA Platform 5.15.0 双许可证 | 官方仓库 + Maven 构件 POM；2026-08-26 核实 |
| <https://github.com/kwhat/jnativehook> | 官方源码/许可证 | JNativeHook 2.1.0 的 LGPL/GPL 许可说明 | 官方仓库 + Maven 构件 POM；2026-08-26 核实 |
| <https://github.com/openjdk/jfx> | OpenJDK 官方源码 | JavaFX 23.0.1 的 GPL v2 + Classpath Exception | 官方仓库；2026-08-26 在线核实 |
| <https://openjfx.io/javadoc/23/javafx.graphics/javafx/application/Platform.html#runLater(java.lang.Runnable)> | OpenJFX 23 官方 API | 后台开局库结果回到 JavaFX Application Thread 后更新控件 | 官方 API；2026-08-28 在线核实 |
| <https://github.com/xerial/sqlite-jdbc> | 官方源码/许可证 | SQLite JDBC 3.45.2.0 的 Apache-2.0 许可 | 官方仓库 + Maven 构件 POM；2026-08-26 核实 |
| <https://github.com/xerial/sqlite-jdbc/blob/3.45.2.0/src/main/java/org/sqlite/SQLiteConfig.java> | 固定版本官方源码 | `READONLY` 打开标志、关闭扩展加载、私有缓存与 JDBC 配置入口 | 与项目固定依赖 3.45.2.0 对齐；2026-08-27 核实 |
| <https://www.sqlite.org/uri.html> | SQLite 官方文档 | URI `mode=ro` 和 `cache=private` 的只读打开语义 | 官方；2026-08-27 在线核实 |
| <https://www.sqlite.org/pragma.html#pragma_query_only> | SQLite 官方文档 | `query_only`、`trusted_schema` 和会话缓存等防御设置 | 官方；2026-08-27 在线核实 |
| <https://www.sqlite.org/c3ref/limit.html> | SQLite 官方 API | 每连接运行时降低字符串、SQL、列、表达式、附加库和触发器等限制 | 官方；2026-08-27 在线核实 |
| <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html> | Oracle Java 21 API | `tryLock` 的非阻塞独占文件锁、JVM 内重叠锁异常和跨进程互斥边界 | 官方 API；2026-08-27 在线核实 |
| <https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html> | Oracle Java 21 API | `ATOMIC_MOVE`、不支持原子移动时的显式异常及符号链接替换语义 | 官方 API；2026-08-27 在线核实 |
| <https://github.com/qos-ch/slf4j> | 官方源码/许可证 | SLF4J 1.7.36 的 MIT 许可 | 官方仓库；2026-08-26 在线核实 |

## 随包资源待确认项

| 资源 | 已有证据 | 缺口 | 发布处理 |
|---|---|---|---|
| `src/main/resources/model/yolov11.onnx` | 文件内元数据标注 Ultralytics YOLO11n 8.3.17 / AGPL-3.0；上游提交 `2d415250...`；SHA-256 `099c4ef0...13e1` | 自定义训练数据、训练脚本和具体训练者未说明 | 完成来源核实前仅保留开发审计，不宣称可无条件再分发 |
| `src/main/resources/font/chessman.ttf` | 随上游 GPLv3 初始提交；SHA-256 `016d2e09...89d` | 无单独字体名称、作者或许可证文件 | 发布前向上游确认或替换为来源明确字体 |

补充核查：TCHESS V1.6 发布说明只确认“升级使用 yolov11 模型”，未披露训练来源；按完整 SHA-256、模型描述及导出路径公开检索未找到独立副本。VinXiangQi 参考仓库也未提交 ONNX 权重。上述结果只能证明目前无法补齐来源，不能证明资源侵权或可自由再分发。

仓库内 44 个 PNG、1 个 JPG、1 个 ICO、5 个 WAV、1 个 ONNX 和 1 个 TTF 已全部进入 `docs/bundled-resources.sha256`，并由测试逐文件复算；图片和声音继续按 TCHESS 固定提交中的上游资源记录，模型与字体保留单独发布阻断。

## 证据标签

- `官方`：项目所有者发布的文档、许可证或版本。
- `源码已验证`：在固定提交中定位到具体实现。
- `实测`：在本机以记录的命令和样本复现。
- `推断`：从设计或代码结构推得，尚未运行验证。
