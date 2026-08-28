# 本地开局库与 Pikafish 升级审计（2026-08-28）

## 结论

- `Pikafish-2026-01-02` 仍是官方最新稳定 Release；`master@b97ef0f9` 是 2026-08-26 的开发快照，不是更新的稳定版。
- 本机同为 AVX-VNNI PGO 构建时，master 在单线程固定节点测试快 7.80%，在应用参数 12 线程/1 GiB/1,500 ms 下快 9.70%。这证明本机速度收益，不等于已经证明实战棋力更强。
- 计时配对和本机 NPS 倾向 master，因此本机开发镜像默认使用 master 与匹配 `master-net`；稳定 Release 及其 NNUE 完整保留为回退，两套网络不混用。
- `小冰库.obk` 是可读的标准 SQLite OBK，但属于多来源汇编，约 51% 记录没有对局统计，并含低分库招；不能把原库整体称为“全是最优着法”。
- 已生成 622,175 行的本地高可信子集，并用许可清楚的 CCPD 开局语料保守补入 552 个空白局面，形成 622,727 行个人精选库并接入首启配置。ChessDB 继续作为本地缺失时的在线补充，不抓取整站，也不把单次云分或引擎分数包装成理论结论。

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
| CCPD | CC BY 4.0；固定提交含 Big5/CP950 中文开局 PGN | 严格解码、逐着验证和整线去重后，只用于主库空白局面补全 |
| WXF 官方棋谱目录 | 1990—2025 年比赛记录 | 页面未给出批量再分发许可，未抓取合并 |
| MRXqOpeningBook / OOBS | MIT 源码与哈希开局库算法说明 | 只作算法和互操作参考，不作为棋谱数据源 |

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

## 个人精选库补全结果

输出：`D:\象棋\PikaDesk\local-assets\books\PikaDesk-个人精选-20260828.obk`

| 项目 | 结果 |
|---|---:|
| 字节 | 56,328,192 |
| SHA-256 | `1EB27357BF44B666640024EF4BDE30995D4062F75672B93C691736F3C7061BC7` |
| 总行数 | 622,727 |
| CCPD 新增 | 552 |
| CCPD 文件审计 | 961 文件；828 通过、133 拒绝 |
| 唯一合法开局线 | 682 条、23,451 半回合 |
| 检查/跟随主库局面 | 6,131 / 4,920 |
| 因主库分歧停止 | 659 条线 |
| 同键同着重复组 | 0 |
| 构建规则下无效行 | 0 |
| 初始局面库招 | 11 |
| SQLite quick_check | `ok` |

补全器沿主库已存在的着法继续，只在正常与镜像局面都没有任何主库候选时写入 score 3、零胜和负统计的 CCPD 着法；一旦遇到主库已有其他候选即停止该条历史线。CCPD 的单盘结果只写入来源备注，不转换为聚合胜率。许可证原文、固定提交、筛选说明和变更说明随本地生成物保留，但数据与生成库均不提交 Git。

## 引擎配对结果

- 20 个合法开局、交换先后手的 40 局 40 ms 计时赛：master 5 胜、35 和、0 负。
- 先前 6 个开局的 12 局 50 ms 计时赛：master 0 胜、11 和、1 负；两组计时赛合计 5 胜、46 和、1 负。
- 同一 20 个开局、每步固定 20,000 节点的 40 局：master 3 胜、34 和、3 负。

因此选择 master 是“当前本机、当前时间预算”的工程决策。决定局数量有限，固定节点又打平，不能把表面得分换算成通用等级分。

## 仍不能作出的声明

- 不能证明 622,175 条都是理论最优或必胜着法。
- 不能从不可逆 OBK 哈希全局恢复 FEN，所以无法对原主库每条记录做完整棋规重放；CCPD 新增部分则已逐着重放。
- 不能凭本机 NPS 和有限配对宣称 master 在所有机器、时限和未来提交上都高于稳定 Release。
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
- CCPD：<https://github.com/Yvonne761/Chinese-Chess-Practical-Dataset>
- WXF 棋谱目录：<https://www.wxf-xiangqi.org/index.php?Itemid=313&id=218&lang=en&option=com_content&view=article>
- MRXqOpeningBook：<https://github.com/nguyenpham/MRXqOpeningBook>
- OOBS：<https://github.com/nguyenpham/oobs>
