# 本地开局库与 Pikafish 升级审计（2026-08-28）

## 结论

- `Pikafish-2026-01-02` 仍是官方最新稳定 Release；`master@b97ef0f9` 是 2026-08-26 的开发快照，不是更新的稳定版。
- 本机同为 AVX-VNNI PGO 构建时，master 在单线程固定节点测试快 7.80%，在应用参数 12 线程/1 GiB/1,500 ms 下快 9.70%。这证明本机速度收益，不等于已经证明实战棋力更强。
- 当前默认保留稳定版；master EXE 与匹配 `master-net` 作为隔离候选保留，不混用稳定版 NNUE。
- `小冰库.obk` 是可读的标准 SQLite OBK，但属于多来源汇编，约 51% 记录没有对局统计，并含低分库招；不能把原库整体称为“全是最优着法”。
- 已生成 622,175 行的本地高可信子集并接入首启配置。ChessDB 继续作为本地缺失时的在线补充，不抓取整站，也不把单次云分或引擎分数包装成理论结论。

## 原库只读审计

| 项目 | 结果 |
|---|---:|
| 路径 | `C:\Users\MSI\Desktop\小冰库.obk` |
| 字节 | 337,306,624 |
| SHA-256 | `A3504EDF2E3ABBC13B1BDBDB9707E894357A35BC0B2FAD8446934CE5027FDF2E` |
| SQLite quick_check | `ok` |
| 总行数 | 6,525,840 |
| `vvalid=1` | 6,525,790 |
| C90 结构错误 | 0 |
| 不可查询/非有限键 | 1,613 |
| 精确额外重复 | 328 |
| 同键同着字段冲突组 | 282 |
| 无胜和负统计 | 3,326,048 |

备注中约 317 万行标记“裸奔库”，少量还出现天规库、云地址和群号。这能证明它不是单一来源原生库，却不能证明这些来源的许可或逐着质量。

初始局面原库有 14 着。与 ChessDB `queryall` 的 44 着对照时，本地 `vscore>=3` 的候选大体落在云库 rank 1～2，而 `vscore=2` 的 `h2h4` 云分为 -8，`vscore=1` 的 `h2h1` 云分为 -39。因此选用 3 作为本机保守阈值；这只是交叉校验后的工程阈值。

## 社区候选比较

| 候选 | 可用信息 | 本次处理 |
|---|---|---|
| 皮卡鱼论坛开局库版 | 可发现大型 OBK、云库单机版和用户库 | 下载帖没有自动提供数据权属与再分发许可，未下载、未混入 |
| CGLemon 中国象棋 PGN | 141,556 盘 ICCS 棋谱；作者说明过滤非法着，但未检查长照、长捉、重复 | 未找到清晰数据许可，未下载、未混入 |
| ElephantEye | LGPL 工具链，可由 PGN 生成 `BOOK.DAT` | 代码许可不自动覆盖棋谱数据；只作流程参考 |
| `fisherfan/xqbook` | 提供 XQB 表结构与建库思路 | 仓库无许可证；只引用互操作事实，不复制代码/样例 |
| ChessDB | 公开查询 API、服务源码与 Public Domain 声明 | 保留为按局面在线查询的补缺来源，不做全量镜像 |

“网上能找到”不等于“可以合法合并并公开发布”。本次没有为了追求体积而混入许可证不清的社区库，也没有下载付费、加密或破解库。

## 生成结果

输出：`D:\象棋\PikaDesk\local-assets\books\PikaDesk-精选高可信-20260828.obk`

| 项目 | 结果 |
|---|---:|
| 字节 | 55,787,520 |
| SHA-256 | `54287F034B6D95B20E68495C901217D4C9B8344C29D9EC04CF78024C59A146C5` |
| 写入行数 | 622,175 |
| 分数 3 / 4 / 5 | 620,289 / 1,878 / 8 |
| 整数键 / 有限实数键 | 312,230 / 309,945 |
| 同键同着重复组 | 0 |
| 初始局面库招 | 11 |
| SQLite quick_check | `ok` |

筛选器只保留 `vvalid=1`、`vscore>=3` 且字段可验证的记录。同键同着按分数、样本量、胜和负统计、备注和稳定源行顺序择优，绝不把来源不明的重复统计相加。输出成功前只操作随机临时文件；源库哈希生成前后相同，且没有 journal/WAL/SHM 副文件。

## 仍不能作出的声明

- 不能证明 622,175 条都是理论最优或必胜着法。
- 不能从不可逆 OBK 哈希全局恢复 FEN，所以无法对每条记录做完整棋规重放。
- 不能凭本机 NPS 宣称 master 的实战棋力一定高于稳定 Release。
- 不能保证 ChessDB 永久在线、永远优于本地库或返回唯一正确答案。

因此应用采用“精选本地库命中 → ChessDB 在线补缺 → Pikafish 搜索/战术复核”的分层方式。它提高可用性与速度，同时保留对证据边界的诚实描述。

## 来源

- Pikafish 稳定 Release：<https://github.com/official-pikafish/Pikafish/releases/tag/Pikafish-2026-01-02>
- 稳定版与 master 比较：<https://github.com/official-pikafish/Pikafish/compare/Pikafish-2026-01-02...master>
- Pikafish 测试与贡献流程：<https://github.com/official-pikafish/Pikafish/blob/master/CONTRIBUTING.md>
- master 配套网络：<https://github.com/official-pikafish/Networks/releases/tag/master-net>
- ChessDB 说明与 API：<https://www.chessdb.cn/cloudbook_info_en.html>、<https://www.chessdb.cn/cloudbook_api_en.html>
- ChessDB 源码：<https://github.com/noobpwnftw/chessdb>
- 鲨鱼 XQB/OBK 转换边界：<https://www.sharkchess.com/archives/545>
- 皮卡鱼论坛开局库版：<https://bbs.pikafish.org/forum.php?fid=41&mod=forumdisplay>
- CGLemon 棋谱集合：<https://github.com/CGLemon/chinese-chess-PGN>
- ElephantEye：<https://github.com/xqbase/eleeye>
