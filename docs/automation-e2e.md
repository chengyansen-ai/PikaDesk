# Windows 本地自动走棋端到端验收

## 安全范围

此验收只控制仓库自带的 `PikaDesk 本地自动化测试棋盘`。探针按窗口标题精确匹配，标题不一致时立即拒绝；它不会搜索、适配或控制公共对弈平台。

识别模型 `model/yolov11.onnx` 目前仍受 `docs/third-party.md` 中的来源与再分发发布门约束。以下结果只证明本地开发机上的功能行为，不等于模型可以随安装包公开分发。

## 可复现步骤

在第一个 PowerShell 终端启动离线测试棋盘：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd --batch-mode javafx:run@local-test-board
```

保持测试棋盘完整可见，在第二个终端编译测试代码并运行受限探针：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd --batch-mode test-compile
.\mvnw.cmd --batch-mode org.codehaus.mojo:exec-maven-plugin:3.6.3:java `
  '-Dexec.mainClass=com.sojourners.chess.linker.WindowsAutomationE2EProbe' `
  '-Dexec.classpathScope=test'
```

黑方在下时，把最后一个命令增加 `'-Dexec.args=black'`。每次执行前点击测试棋盘的“标准开局”。成功输出必须同时包含：

```text
E2E_CALIBRATION=MODEL
E2E_MOVE=a0a1
E2E_CONFIRMATION=CONFIRMED
E2E_STATE=OBSERVING
```

运行有界耐久探针时，改用：

```powershell
.\mvnw.cmd --batch-mode org.codehaus.mojo:exec-maven-plugin:3.6.3:java `
  '-Dexec.mainClass=com.sojourners.chess.linker.WindowsAutomationE2EProbe' `
  '-Dexec.classpathScope=test' `
  '-Dexec.args=endurance=1000'
```

耐久模式只接受 `1..1000`，固定为经典主题、红方在下，并按 `a0a1`、`a9a8`、`a1a0`、`a8a9` 交替红黑双方；每四步回到标准局面。它每 50 步输出一次进度。

探针仅发送一次点击对，不会失败重试。失败时，协调器暂停并返回结构化确认状态、识别拒绝码和模型版本；诊断不包含截图或完整棋盘内容。

## 2026-08-27 实机记录

证据编号：`E2E-WIN-LOCAL-20260827-01`

| 测试组合 | 结果 | 证据摘要 |
|---|---|---|
| 经典木色、红方在下、100% | 通过 | 模型自动定位；`a0a1`；视觉确认 `CONFIRMED` |
| 经典木色、黑方在下、100% | 通过 | 旋转坐标映射；`a0a1`；视觉确认 `CONFIRMED` |
| 经典木色、红方在下、150% | 通过 | 完整显示棋盘后，自动定位区域 `x=232,y=128,w=660,h=751`；视觉确认 `CONFIRMED` |
| 高对比度、100% | 不支持且安全停止 | 初始盘面可识别、点击落子成功；走后确认因 `PIECE_COUNT_INVALID` 超时，状态进入暂停且没有重试 |

最后一次通过记录的窗口客户区为 `1434×1001`、系统报告 DPI 为 `96`。这里的 150% 是测试棋盘内部渲染缩放，不应误写成 Windows 150% DPI。

### 耐久测试中断记录

证据编号：`E2E-WIN-LOCAL-20260827-ENDURANCE`

- 预检 `4/4` 通过，四步后局面精确恢复。
- 第一次运行连续确认 138 步，在第 139 步检测到 `USER_INPUT_DETECTED` 并停止。
- 增加不含坐标的诊断后，第二次运行连续确认 406 步，在第 407 步检测到 `pointerChanged=true,inputSequenceChanged=true` 并停止。
- 两次都没有失败重试，也没有在安全事件后继续发送后续走法。由于没有完成 1,000 步，验收状态仍为“未通过”；最长无中断记录是 406 半回合。

## 当前结论与剩余门槛

- 向导只开放 `CLASSIC + yolo11-xiangqi`；旧配置中的 `AUTO` 以及未验证主题不能授权自动化。
- 红/黑朝向与经典主题 100%/150% 的单步链路已经通过；125%、200%、窗口移动、失焦、动画、崩溃恢复仍需逐项实机验证。
- 目前最长无中断记录是 406 半回合；两次运行都因真实用户活动门安全停止。这不是 1,000 半回合可靠性证明，达到发布标准前仍需在无人操作的专用桌面会话中跑满。
- 高对比主题需要补充有明确许可的训练数据/模型或独立识别适配，不能靠降低安全阈值开放。
