# 第三方组件、资源与发布门

本文件记录 PikaDesk 当前固定基线中的上游代码、运行时依赖和随包资源。它是工程审计记录，不替代各组件的许可证原文或法律意见。

## 1. 主线代码

| 项目 | 固定版本 | 来源 | 许可证 | 状态 |
|---|---|---|---|---|
| PikaDesk | 0.1.0-dev | 本仓库 | `GPL-3.0-only` | 开发中 |
| TCHESS / public-Xiangqi | commit `2d41525095639548059ebd930b0af4d29efc1364`，上游界面版本 1.9 | <https://github.com/sojourners/public-Xiangqi> | 上游随附 GNU GPL v3；本项目保守标记 `GPL-3.0-only` | 已固定并保留归属 |

本仓库没有鲨鱼私有资源，也不把皮卡鱼引擎或 NNUE 权重提交进 Git。当前这台电脑的本地开发 app-image 会从被 Git 忽略的 `local-assets` 目录复制经哈希校验的官方引擎和权重；这不等于公开二进制发布许可已经关闭。

## 2. 本地验收用皮卡鱼（未随包）

2026-08-26 从皮卡鱼官方 GitHub Release 下载 `Pikafish-2026-01-02`，源归档保存在仓库外本地工具目录。2026-08-28 将下列经哈希复核的两个运行文件复制进这台电脑的本地开发 app-image；它们没有加入 Git 或 Maven JAR。官方发布页：<https://github.com/official-pikafish/Pikafish/releases/tag/Pikafish-2026-01-02>。

| 文件 | SHA-256 | 许可/用途 |
|---|---|---|
| `Pikafish.2026-01-02.7z` | `84257063905615919fb4ee6a70273a94843bb6ec04c45e3ac706098838bc1a49` | GitHub Release API 官方摘要已逐字节核对 |
| `pikafish-avxvnni.exe` | `013161b469559552ccce8ac6af22b25b964ba75504b07d04dfc41cfe485c15d0` | Pikafish GPL v3；本机 i7-14700KF 真实 UCI 验收 |
| `pikafish.nnue` | `c4026370d7516d9b0f668447f9ca1931241538bdc689cde6fec6a991ac4d5f77` | 上游 `NNUE-License.md`：仅限合法使用，未经许可不得商用，并明确禁止在线作弊等违法/违规用途 |

同一镜像还保留开发候选，但不设为默认；候选来自官方源码 `master@b97ef0f9eb15bd99899b272e0236bfebf86313b6`，只与 2026-08-28 固定的 `master-net` 配套：

| 文件 | SHA-256 | 许可/用途 |
|---|---|---|
| `Pikafish-master-b97ef0f-avxvnni.exe` | `47eec4637913068278ee336962fa7fd1dc3c27f0cd192ec06eddad9c251176b9` | 官方 GPL v3 源码的本机 AVX-VNNI PGO 构建；开发候选 |
| master `pikafish.nnue` | `3cd15292bf8c979884262f57fc723959fc0dea43b4d8d544f88db5ceb2479e24` | 官方 `master-net`；受同一 NNUE 合法/非商用条款约束 |

本机镜像使用 12 线程、1,024 MiB Hash、MultiPV 3；NNUE 与 EXE 同目录并以相对文件名加载，以兼容 `D:\象棋` 中文安装路径。完整性能证据见 [`performance-2026-08-28.md`](performance-2026-08-28.md)。

若未来选择随安装包分发，必须重新审查 GPL 源码提供义务、NNUE 的非商用限制并把许可证原文加入最终包；当前验证不能视为发布许可已经关闭。

## 3. Maven 运行时依赖

以下 SHA-256 是 2026-08-26 由 Maven Central 下载并用于本机验证的实际 JAR。当前普通 Maven JAR 不把这些依赖合并进去；未来 jlink/jpackage 发布时必须从最终包重新生成 SBOM 和文件哈希，不能把本表当作永久发布清单。

| 构件 | 许可证与官方来源 | 已验证 JAR SHA-256 |
|---|---|---|
| ONNX Runtime `com.microsoft.onnxruntime:onnxruntime:1.19.2` | MIT；<https://github.com/microsoft/onnxruntime>；其 JAR 内第三方代码仍受 `ThirdPartyNotices.txt` 约束 | `ddab9113a453c0200ac16caf308b76bc8e84653ddc11969983b783dff34397b2` |
| JNA Platform `net.java.dev.jna:jna-platform:5.15.0` | LGPL-2.1-or-later 或 Apache-2.0；<https://github.com/java-native-access/jna> | `18b7f6e7d34ce89309a6d9052ae1a987e8e64057e2f683e01e50f2f2b59cd153` |
| JNA `net.java.dev.jna:jna:5.15.0` | LGPL-2.1-or-later 或 Apache-2.0；<https://github.com/java-native-access/jna> | `a564158d28ab5127fc6a958028ed54279fe0999662c46425b6a3b09a2a52094d` |
| JNativeHook `com.1stleg:jnativehook:2.1.0` | 构件 POM 列出 GPL v3 / LGPL v3；官方项目说明按 LGPL 分发；<https://github.com/kwhat/jnativehook> | `41565e543a043ee2073a0b3d93082b78614d2241aa2c6669e05385d94511851c` |
| JavaFX Controls `org.openjfx:javafx-controls:23.0.1` | GPL v2 with Classpath Exception；<https://github.com/openjdk/jfx> | 通用：`dd771a1dcd8b744e1672035aa3c62833e63465fa6066b3a0c2ea27f177cbd4ef`；Windows：`660df50b84f90fcb5057f50d2fd8aef0fc592a030b2154f7497bca27bdfea8b0` |
| JavaFX Graphics `org.openjfx:javafx-graphics:23.0.1` | GPL v2 with Classpath Exception；<https://github.com/openjdk/jfx> | 通用：`9090abb6881488e74b8f87e45a8238ec3324ec44ecc60fc1fb7b5032d8095111`；Windows：`5449a4eafb0fe1f7d1ab529992f2a34299c10ec8513480df330f1a5cf2733d8a` |
| JavaFX Base `org.openjfx:javafx-base:23.0.1` | GPL v2 with Classpath Exception；<https://github.com/openjdk/jfx> | 通用：`88453a8d4cc921740c84e315e8021b9d984b0920d3c923187c5fd0c2cc1d683c`；Windows：`210ab9f5bcd8ca2c6824611c62e29a6843d45092445e4ffce2b5e4db26d605dc` |
| JavaFX FXML `org.openjfx:javafx-fxml:23.0.1` | GPL v2 with Classpath Exception；<https://github.com/openjdk/jfx> | 通用：`4400b42878b249a776a6cf920d9523cab42c9fb26ba220f9a4f86b932270dde0`；Windows：`ce4e849d4605dd4b142163b9c3d6ca0ceddeb5ff1f914bf4cd83693b64ac7708` |
| JavaFX Media `org.openjfx:javafx-media:23.0.1` | GPL v2 with Classpath Exception；<https://github.com/openjdk/jfx> | 通用：`d8f7e911089af90d03b80e6ce3c793630c150e5885d624bfef6715325d1a8a8c`；Windows：`c6e1805236a56c33f2d5322f76924de30c519f1a6f7f98876ff9636aae035e55` |
| JavaFX Swing `org.openjfx:javafx-swing:23.0.1` | GPL v2 with Classpath Exception；<https://github.com/openjdk/jfx> | 通用：`9cd930be0a540108573514c4f9a48bff2967dca83f5e3191efffef407eda75ed`；Windows：`c9e907b577d6cf0c4b6f58f90af89a1d272d5321ba9f5c48d03d30ba2ff53152` |
| SQLite JDBC `org.xerial:sqlite-jdbc:3.45.2.0` | Apache-2.0；<https://github.com/xerial/sqlite-jdbc> | `a817162384b7d9d98fd616ca880bcbf2528cf29e31393666d2df85b307b03764` |
| SLF4J API `org.slf4j:slf4j-api:1.7.36` | MIT；<https://github.com/qos-ch/slf4j> | `d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0` |

JUnit、Maven 插件和 Maven Wrapper 只用于构建/测试，不进入当前应用运行时。它们的固定版本在 `pom.xml` 与 `.mvn/wrapper/maven-wrapper.properties` 中维护。

## 4. 随包资源

所有非文本资源的逐文件 SHA-256 见 [`bundled-resources.sha256`](bundled-resources.sha256)，并由 `ThirdPartyNoticeTest` 自动核对，避免资源变化绕过审计。

| 资源族 | 版本/来源 | 许可证证据 | 结论 |
|---|---|---|---|
| `image/*`、`ui/*` PNG/JPG/ICO 和 `sound/*` WAV | TCHESS 固定提交 `2d415250...` | 与上游 GPL v3 仓库一并发布，未发现单独许可证声明 | 作为上游资源保留归属；若发现单独通知则立即补录 |
| `model/yolov11.onnx` | 10,575,683 字节；嵌入元数据：Ultralytics YOLO11n、导出版本 8.3.17、日期 2024-10-19；SHA-256 `099c4ef0cbfbd07f680037bb1aabf59024f5c0243964b36aeec7c7a57f7213e1` | 模型内自述 `AGPL-3.0`；上游 V1.6 只说明升级 YOLO11，未提供训练脚本、数据集许可或独立来源 | **发布阻断**：开发审计可保留；确认来源或替换前不得制作公开安装包 |
| `font/chessman.ttf` | 7,232 字节；随 TCHESS 固定提交引入；SHA-256 `016d2e0923bad6c81bd82a32b475cb630551d980c46151f37f85cc77e445089d` | 没有字体名称、作者或单独许可证 | **发布阻断**：向上游确认或替换为来源明确字体 |

## 5. 发布门

任何对外安装包必须同时满足：

1. 关闭上述两个发布阻断项，不能仅凭“来自开源仓库”推定模型训练数据或字体可再分发；
2. 生成最终包 SBOM、第三方许可证全文及所有二进制 SHA-256；
3. 皮卡鱼引擎和 NNUE 若由用户选择下载，必须显示官方来源、版本、许可和下载后哈希；
4. 运行许可证测试、完整构建和秘密扫描；
5. 明确产品与鲨鱼、TCHESS、皮卡鱼官方均无隶属或背书关系。
