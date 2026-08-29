# 规格：本地中文语音播报

## 目标

为 PikaDesk 增加可完全关闭的本地中文语音，播报已确认走法、安全警告和棋局结果。
语音工作不得阻塞 JavaFX、引擎、识别或自动走棋线程，不依赖账号、云端或网络。

## 技术栈与结构

- Java 21；现有 JNA 5.15，不增加依赖。
- `media` 包：受限播报文本、偏好设置、有界非阻塞队列与 Windows SAPI COM 后端。
- `controller`/`fxml`：总开关及走法、警告、结果三类开关。
- `config`：新字段对旧序列化配置兼容；总开关默认关闭，三类事件默认允许。

```java
voiceService.configure(new VoicePreferences(true, true, true, true));
voiceService.announce(VoiceAnnouncement.move("炮二平五"));
```

## 用户流程

1. 新安装和旧配置升级后语音均保持关闭。
2. 用户在“设置 → 中文语音”开启总开关，并单独选择走法、警告、结果。
3. 已确认走子播报中文棋谱；将军/绝杀播报警告/结果；连接安全暂停或配置失败播报警告。
4. 关闭总开关时立即清空尚未播报的队列，不再初始化或调用 SAPI。

## 威胁模型与边界

棋谱、窗口状态和错误信息都是不可信文本。

- 注入：不启动 shell，不构造命令行；文本只作为 COM `Speak` 参数。
- 资源耗尽：每条文本最多 120 个字符，队列最多 8 条；满载时丢弃最旧未播报项。
- 线程阻塞：调用方只执行校验和非阻塞 `offer`；SAPI 只在专用守护平台线程中运行。
- 信息泄漏：不写入棋盘、语音或文本日志；诊断只记录有界失败类型。
- 失效：SAPI 初始化/播报失败会停用当前后端且清空队列，不影响走棋与分析。

## 命令与测试

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd --batch-mode '-Dtest=LocalVoiceServiceTest,VoiceAnnouncementTest,PropertiesDefaultsTest' test
.\mvnw.cmd --batch-mode '-Dtest=WindowsSapiVoiceAcceptanceTest' '-Dpikadesk.voice.acceptance=true' test
.\mvnw.cmd --batch-mode '-Dtest=LocalVoicePerformanceAcceptanceTest' '-Dpikadesk.voice.performance=true' test
.\mvnw.cmd --batch-mode verify
.\scripts\package-windows.ps1
```

- 小型测试：文本归一化/限长、默认关闭、类别过滤、队列满载、非阻塞、关闭清理和后端失败。
- 契约测试：FXML 开关、Controller 事件接线、应用退出关闭语音服务。
- Windows 验收：使用专用探针播报一句固定中文，证明 COM 初始化、本地语音和正常释放。

## 始终/询问/禁止

- 始终：默认关闭；有界、非阻塞、离线；应用退出时关闭。
- 询问：增加云语音、下载音色、允许自由 SSML、改变队列上限或开启默认值。
- 禁止：上传局面/语音；执行用户文本；在 JavaFX/引擎/识别/输入线程中同步语音。

## 验收标准

- 总开关关闭时后端从不初始化；旧配置升级仍为关闭。
- 走法、警告、结果可分别开关，调用端不因语音后端卡住。
- 最多 8 条待播报，不保留过时的最旧消息；关闭后不再接收新消息。
- Windows SAPI 真实固定短句播报返回成功，过程不创建网络连接。
- 完整测试、打包、启动和退出残留验收通过。
