# PikaDesk 安全智能脚本 DSL

当前实现是可嵌入界面的规则内核。它不是 PowerShell、JavaScript 或宏录制器，不接受任意函数名、命令参数、文件路径、类名或 URL。未知版本、语句、事件、条件、动作和多余参数一律拒绝。

## 格式

脚本是 UTF-8 纯文本，第一行必须为 `PDSCRIPT 1`。每条规则由唯一英文标识、一个事件、零到多个且条件和至少一个动作组成：

```text
PDSCRIPT 1
RULE tactical
WHEN ENGINE_RESULT
IF SCORE_CP LE -200
IF COMPLEXITY GE 75
DO SET_TIME_SCALE 130
DO SHOW_NOTICE TACTICAL_POSITION
END
```

条件全部按 AND 计算。数字条件只支持 `LE`/`GE`，枚举条件只支持 `EQ`。保存时会输出规范化文本，再次加载得到相同规则对象。

## 固定白名单

事件：

- `POSITION_STABLE`
- `ENGINE_RESULT`
- `MOVE_CONFIRMED`
- `AUTOMATION_PAUSED`
- `MANUAL_ANALYSIS`

条件字段：

- `SCORE_CP`：`-100000`～`100000`
- `COMPLEXITY`：`0`～`100`
- `REMAINING_MILLIS`、`TIME_TARGET_MILLIS`：非负整数
- `PHASE`：`OPENING`、`MIDDLEGAME`、`ENDGAME`
- `SIDE`：`RED`、`BLACK`
- `AUTOMATION_STATE`：自动化安全状态机中的固定状态名

动作：

- `START_ANALYSIS`
- `STOP_ANALYSIS`
- `SET_TIME_SCALE 25..200`
- `SHOW_NOTICE TIME_PRESSURE|TACTICAL_POSITION|RULE_TRIGGERED`
- `PAUSE_AUTOMATION`
- `REQUEST_AUTHORIZED_MOVE`

动作接收方只会得到上述结构化枚举和有界数字，不会得到可解释为系统命令的自由文本。

## 执行与安全

- `DRY_RUN` 只生成确定性审计记录，不调用动作接收方，也不读取墙钟。
- `LIVE` 默认最多 2,048 步、250 ms；调用方可把上限收紧，但不能超过 2,048 步或 5 秒。
- 每条规则、条件和动作都会消耗步数；每步前检查取消，实时执行还检查协作式超时。
- 解析上限为 64 KiB、每行 512 字符、128 条规则、每规则 16 个条件和 8 个动作、总计 1,024 条条件/动作。
- 审计记录只有规则标识、固定事件类型和有界摘要，没有时间戳、账号、截图、路径或动作接收方异常正文。
- `REQUEST_AUTHORIZED_MOVE` 只有在真实 `AutomationSafetyKernel` 同时处于 `READY` 且仍持有授权时才会发送给应用；脚本本身拿不到坐标或低层输入许可。接收方最终执行时仍必须调用同一个安全状态机，状态变化会继续拒绝动作。
- 动作接收方必须是应用内短时、有界的适配器。DSL 不加载插件、不反射类、不访问文件、不启动进程，也不建立网络连接。

当前还没有图形化规则编辑器和主窗口事件接线；在这两项完成前，功能矩阵按“内核通过、UI 待接线”记录，不宣称用户界面已完整对等。
