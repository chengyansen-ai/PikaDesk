# 规格：只读实时陪练

## 目标

在 Windows 上选择一个由用户拥有或明确授权的本地人机象棋窗口。PikaDesk 只截取该窗口的棋盘画面，自动识别红黑棋子、棋盘朝向和已经发生的合法着法，把确认后的着法写入本地棋谱，并用 Pikafish 在 PikaDesk 内高亮候选着法。任何画面和识别结果都不上传。

推荐着法只作预演，不提前改变 PikaDesk 的真实棋谱局面；真实棋谱仅跟随目标窗口中已经稳定确认的着法。这样即使用户没有采用第一候选，也不会造成两边失步。

## 技术栈

- Java 21、JavaFX 23、Maven Wrapper。
- Windows/JNA 只用于选取窗口、读取窗口身份和截图。
- ONNX Runtime 与现有 `yolo11-xiangqi` 模型负责棋盘和棋子识别。
- 现有 `RecognitionGate`、象棋合法性逻辑、棋谱树和 UCI/UCCI 引擎链路负责校验、记录与推荐。

## 命令

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd --batch-mode '-Dtest=LinkModeTest,GraphLinkerCharacterizationTest,ObservedBoardOrientationTest,ObservedTurnAlignmentTest,ReadOnlyAdvisorUiContractTest' test
.\mvnw.cmd --batch-mode verify
.\scripts\package-windows.ps1
```

## 项目结构

- `src/main/java/com/sojourners/chess/linker/`：只读连接模式、截图扫描和着法推断。
- `src/main/java/com/sojourners/chess/controller/`：模式选择、棋谱同步和引擎推荐显示。
- `src/main/java/com/sojourners/chess/recognition/`：红黑棋子、稳定帧和局面完整性校验。
- `src/test/java/com/sojourners/chess/linker/`：只读模式不得产生外部输入的契约测试。
- `docs/specs/`：功能规格和可验证边界。

## 代码风格

使用小型、不可变模式值表达权限，不以界面文字散落判断能力：

```java
public enum LinkMode {
    READ_ONLY_ADVISOR(false),
    AUTHORIZED_AUTOMATION(true);

    private final boolean externalInputAllowed;
}
```

生产代码保持失败闭锁；识别不确定时返回结构化拒绝或暂停，不捕获后继续猜测着法。

## 测试策略

- 小型单元测试：模式解析、只读权限、红黑行棋方跟踪、方向判断、一步棋差分。
- 中型集成测试：只读扫描链路同步棋谱并触发分析，但不创建或武装输入协调器。
- Windows 实机验收：使用仓库自带本地测试棋盘，验证红方在下、黑方在下、吃子、窗口移动、遮挡和动画稳定帧。
- 完成前运行完整 Maven `verify` 与 Windows app-image 打包。

## 边界

- 始终：目标由用户主动选择；图片只在内存中处理；稳定帧和合法性校验后才同步；模式状态在界面可见。
- 需要先确认：增加新识别模型、适配新主题、改变识别阈值或采集/保存截图。
- 绝不：只读模式发送鼠标键盘事件、注入目标进程、读取目标内存、联网传图、在识别歧义时猜着法、为公共真人对战平台提供实时适配。

## 成功标准

- 默认连接模式为“只读陪练”。
- 只读模式的 Windows 连接从不创建、武装或调用外部输入协调器。
- 经典主题下能区分红黑棋子，并由将/帅位置自动确定是否翻转 PikaDesk 棋盘。
- 初始局面按红方先行；非初始局面在观察到第一步后，以实际移动棋子颜色纠正行棋方，之后逐步同步。
- 每个稳定且唯一合法的普通着法或吃子只写入棋谱一次；多解、遮挡、低置信度或窗口变化时暂停。
- 轮到用户时，Pikafish 的 PV1 在 PikaDesk 棋盘和候选列表显示，但不改变已确认棋谱。
- 定向测试、完整测试和打包均为零失败。

## 未决问题

- 当前只承诺已经验证的经典主题。其他本地软件皮肤需要用户提供不含个人信息的测试截图后，才能形成新的识别兼容性证据。

## 2026-08-29 验证记录

- 只读陪练定向测试 37/37 通过；全项目 `verify` 377 个测试、0 失败、4 个需显式外部语料或长时对局的验收测试按设计跳过。
- Windows app-image 重建成功，234 个打包文件逐项通过 SHA-256 清单复核；打包内主用 Pikafish 返回 `uciok` 与 `readyok`。
- 仓库自带离线测试棋盘的经典主题在红方在下、黑方在下两种方向均由模型自动定位，并各完成一次 `a0a1` 视觉确认。
- 全自动双窗口脚本已验证 PikaDesk 能进入只读目标选择状态；窗口选择器按设计不接受合成鼠标事件，因此最后的目标窗口选择仍需验收者亲自点击。不得把前述模型探针误写成已完成的无人值守 PikaDesk UI 闭环。
