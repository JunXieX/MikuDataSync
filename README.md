# MikuDataSync

作者：JunXieX ｜ MikuMC系列插件交流群：1105054380 ｜ 非开源项目，请勿二次分发

跨服玩家数据同步插件：玩家在多个 Paper 子服之间切换时，物品栏、末影箱、状态、进度、统计、属性与 PDC 保持连续。插件不依赖任何数据库或外部中间件，由 Velocity 插件充当数据总线、快照存储与切换编排器。

> 正式版 1.7.0，适用于 Paper 26.3 / Velocity 4.3.0 / Java 25。

## 运行要求

| 项 | 要求 |
| --- | --- |
| Java | 25 |
| Velocity | 4.3.0 |
| Paper | 26.3（Paper 26.3 build 209 及以上），同时支持 Folia |

代理与后端之间需要一条内网通信通道（默认 `127.0.0.1:25666`）。全部子服与代理在同一台机器时无需额外网络配置；分机部署时需要放行该端口并配置一致的 secret。

## 安装

1. 把 `MikuDataSync-Velocity-<版本>.jar` 放入 Velocity 的 `plugins/`，`MikuDataSync-Paper-<版本>.jar` 放入每个 Paper 子服的 `plugins/`。
2. 启动一次，生成配置文件：
   - Velocity：`plugins/mikudatasync/config.yml`
   - Paper：`plugins/MikuDataSync/config.yml`
3. 在两端填入相同的 `secret`（至少 16 字符，建议随机 32 字节以上），并让 Paper 的 `proxy.server-id` 与 Velocity 的网络中子服名称完全一致。
4. 重启代理与子服。

## 配置（Velocity）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `protocol.bind` / `protocol.port` | 127.0.0.1 / 25666 | IPC 监听地址；分机部署改成内网网卡地址 |
| `protocol.secret` | 需修改 | 与所有后端一致的握手密钥 |
| `protocol.read-timeout-ms` | 60000 | IPC 读超时 |
| `store.journal-file` | data/journal.mds | 快照日志文件（唯一持久层，请纳入备份） |
| `store.fsync-mode` | interval | always / interval / never；切服与退服记录始终落盘 |
| `store.fsync-interval-ms` | 50 | interval 模式的刷盘周期（10~10000） |
| `store.compaction-threshold-mb` | 64 | 日志超过该大小后压缩为每玩家最新一条；压缩在后台进行，不阻塞切服 |
| `store.retain-offline-snapshots` | false | 是否在内存保留离线玩家快照 |
| `store.retain-offline-budget-mb` | 256 | 内存快照表的字节预算，与 `retain-offline-snapshots` 独立生效；条目数超过 32 后按最久未更新淘汰 |
| `sync.switch-timeout-ms` | 1500 | 切服闸门等待源服快照的时间 |
| `sync.get-wait-ms` | 1500 | 预登录取数时等待在途快照提交的时间；小于 `switch-timeout-ms` 时会被自动抬升并打日志 |
| `sync.max-snapshot-bytes` | 1048576 | 单份快照的落盘上限（1KB~1MB） |
| `sync.fail-mode` | closed | closed（不确定就拒绝切换）/ open / kick |
| `sync.periodic-flush-seconds` | 30 | 日志刷盘与压缩的周期；它不产生快照，周期快照由 Paper 端 `sync.periodic-snapshot-seconds` 控制 |
| `backups.keep` | 20 | `/mds backup` 保留的备份文件数量 |
| `commands.permission` | mikudatasync.admin | 代理命令权限节点；留空时按 `mikudatasync.admin` 处理，不会对玩家开放 |

## 配置（Paper）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `proxy.host` / `proxy.port` | 127.0.0.1 / 25666 | 代理 IPC 地址 |
| `proxy.server-id` | survival-1 | 必须唯一，且与代理中的子服名一致；最长 128 字节，不能含 `|` |
| `proxy.secret` | 需修改 | 与代理一致 |
| `proxy.connect-timeout-ms` | 3000 | 连接代理 IPC 的超时（100~30000） |
| `sync.sections.*` | 见默认配置 | 分段开关；关闭表示“不采集也不应用”，不会写入默认值。注意 health/food/experience/game-mode/flight 五项合并为 CORE 段，任一为 true 即整段同步 |
| `sync.pdc-merge` | false | true 时把快照 PDC 合并进现有 PDC，而不是整体替换 |
| `sync.advancement-strict` | true | true 时移除目标服上快照没有的进度，使快照成为权威 |
| `debug.log-timings` | false | 是否在日志中输出每次采集的耗时 |
| `sync.prelogin-fetch-timeout-ms` | 500 | 登录前取数超时（上限 3000，该值会阻塞登录线程） |
| `sync.on-sync-unavailable` | kick | 数据不可用时踢出（推荐）或放行（continue，可能读到旧数据） |
| `sync.periodic-snapshot-seconds` | 30 | 周期快照间隔 |
| `sync.sections.maps` | true | 地图颜色 + cursor 同步（物品 PDC + Renderer）；含潜影盒/Bundle 内地图 |
| `debug.enabled` | false | 是否启用管理员调试命令 |

## 命令与权限

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/mds status` | 查看同步状态（代理端显示在线快照数与 IPC 状态） | `mikudatasync.admin` |
| `/mds stats` | 查看快照数量、日志大小、fsync 模式 | `mikudatasync.admin` |
| `/mds doctor` | 自检配置、IPC 与日志状态 | `mikudatasync.admin` |
| `/mds reload` | 当前版本要求重启代理后生效（仅提示） | `mikudatasync.admin` |
| `/mds switch <server>` | 代理端：把执行者切换到指定子服（走同步闸门） | `mikudatasync.admin` |
| `/mds backup <player>` | 代理端：实时采集在线玩家当前数据并写入备份文件 | `mikudatasync.admin` |
| `/mds backups` | 代理端：列出备份文件 | `mikudatasync.admin` |
| `/mds restore <player> <file>` | 代理端：在线应用备份快照，返回 ACK/NACK（会立即覆盖当前数据） | `mikudatasync.admin` |
| `/mdsbackend status` | 后端：查看本服 IPC 状态 | `mikudatasync.admin` |
| `/mdsbackend bench` | 后端：运行一次 codec 微基准（p50/p95） | `mikudatasync.admin` |
| `/mdsbackend selftest` | 后端：检查 NMS 桥状态并可对执行者做一次采集测试 | `mikudatasync.admin` |
| `/mdsbackend timings` | 后端：查看采集/应用分段耗时 | `mikudatasync.admin` |
| `/mdsbackend debug ...` | 后端：调试用（需 `debug.enabled: true`）：PDC、物品 PDC、地图信息、压力测试数据 | `mikudatasync.admin` |

## 同步范围

- 物品栏（36 格 + 护甲 4 格 + 副手）、末影箱 27 格、容器光标物品
- 生命值、伤害吸收、饥饿、饱和度、消耗度、经验等级/进度/总值
- 药水效果（时长/等级/ambient/particles/icon）
- 游戏模式、飞行状态、飞行/行走速度、选中快捷栏
- 属性基础值（装备与药水产生的修饰符由游戏重新计算，不重复同步）
- 玩家 PDC（整体无损替换，含嵌套 Compound 与数组）
- 进度（全部已授予 criteria，按 NMS 静默写入，不重放进度奖励）与全类型非零统计
- 备份/恢复：`/mds backup|backups|restore`，备份文件位于 `plugins/mikudatasync/backups`
- 分段计时：`/mdsbackend timings` 可查看采集与应用各阶段耗时

## 切换语义与数据安全

- 切服时先让源服产出最终快照并写入代理日志，确认成功后才放行目标服连接；失败默认拒绝切换。
- 玩家在目标服可交互前完成数据应用，应用失败会踢出并记录，不会带着半份数据继续游戏。
- 每次提交有单调序号与内容哈希去重；同一玩家同一时刻只允许一个写者。
- 正常切服/退服不丢数据；代理崩溃最多丢最近的 fsync 窗口（默认 50ms）；后端被强杀最多丢一个周期快照间隔。该窗口是“不依赖数据库”的固有代价。
- 日志是唯一持久层，请定期备份 `plugins/mikudatasync/data/journal.mds`。
- 管理员可用 `/mds backup` 生成玩家快照备份；`/mds restore` 仅接受在线玩家，并会立即覆盖其当前数据。
- `/mds backup` 现在会实时采集在线玩家数据；`/mds restore` 会明确返回应用成功或失败。

## 限制

- 仅支持单 Velocity 代理网络；多代理需要共享 Redis，当前版本不支持。
- Folia 下所有采集/应用/冻结都在玩家所属区域线程执行，周期任务走全局区域调度器。
- 不支持从其他同步插件导入历史数据。
- 地图颜色同步默认开启；物品内嵌颜色按 320KB 字节预算分配（通常 15~19 张，视已有数据而定），超出部分只走地图段同步，避免物品段超出上限导致快照无法生成。
- 仅支持列表中的最新版本（Paper 26.3 / Velocity 4.3.0 / Java 25），不提供旧版本兼容。

## 故障排查

| 现象 | 排查 |
| --- | --- |
| 切服被拒绝 | 查看代理日志中的 `Denying switch`；确认两端 secret、server-id、端口与防火墙 |
| 进服背包是旧的 | 确认 Paper 与代理日志中 IPC 已连接；检查 `sync.on-sync-unavailable` |
| 切服被拒绝且日志出现 backend not connected | Paper 的 `proxy.server-id` 必须与 Velocity 中子服名称完全一致（含大小写） |
| 数据版本不匹配 | 所有子服必须运行同一 MC 版本，升级时先停服统一升级 |
| 日志出现 protocol version does not match / HMAC mismatch | 代理与所有后端必须同版本且 secret 完全一致；只升级一端会导致该后端全员进不去 |
| 日志出现 sync.get-wait-ms was raised to sync.switch-timeout-ms | 旧配置里的 get-wait-ms 偏小，已自动抬升并提示；按提示改成不小于 switch-timeout-ms 即可消除 |
| 日志增长异常 | 检查 `/mds stats` 的 journal 大小；确认周期快照间隔与玩家数量 |
