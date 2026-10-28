# 可调参数与来源

唯一实现位置：`:core:domain/src/main/kotlin/ai/deepseek/dsh/android/domain/SyncTuning.kt`。
除该文件与测试 fixture 外，源码里不允许出现下列语义的数字字面量（G09.3 扫描）。
每个值给出理由与生效层；改默认值必须同批更新本表和 `SyncTuning` 的 KDoc。

| 名称 | 值 | 层 | 理由 |
|---|---|---|---|
| `pollWaitMs` | 25000 | transport | 网关 `GATEWAY_EVENT_POLL_WAIT_MS` 默认 25s、上限 60s；取默认值避免被裁剪 |
| `pollWaitMsWorker` | 15000 | worker | Worker 单轮预算内需留出退避与写库时间，且网关允许 0–60s |
| `pollWaitUnsupportedDelayMs` | 2000 | transport | `waitSupported=false` 时（网关可被配成禁用长轮询）避免退化成忙轮询 |
| `workerMaxRounds` | 3 | worker | 原方案约束；3×15s=45s 落在 60s 预算内 |
| `workerRoundBudgetMs` | 60000 | worker | WorkManager 短任务经验值，超过会增加被中断概率 |
| `backoffInitialMs` | 1500 | transport | 与 Web 客户端一致，保持两端行为可对照 |
| `backoffMultiplier` | 2.0 | transport | 标准指数 |
| `backoffMaxMs` | 60000 | transport | 与 Web 客户端一致 |
| `backoffJitterRange` | 0.8–1.2 | transport | 与 Web 客户端 ±20% 一致 |
| `wsFailureDowngradeThreshold` | 3 | transport | 单通道连续 3 次失败降级为 poll，通道互不牵连 |
| `wsProbeIntervalMs` | 30000 | transport | 进入 poll 后每 30s 试探 WS，两条都 open 才停 poll |
| `badJsonCloseThreshold` | 3 | transport | 连续 3 次坏 JSON 关通道重连 |
| `auth401ProbeBudget` | 1 | auth | 每个 401 episode 只做一次凭据探测（claim 而非 verdict） |
| `auth401BackgroundPauseThreshold` | 3 | auth | 后台连续 3 次 401 暂停周期任务，重连/配对成功后恢复 |
| `channelMaxBytes` | 8 MiB | actor | 背压单位是字节而非批数：一个 baseline 批可达 500 行，按批计会让单批击穿 actor |
| `historyPageSize` | 50 | history | 服务端默认页大小 |
| `historyWindowItems` | 180 | ui | 原方案给定的保留窗口；只需 ≥ 单页（对齐由 Paging key 保证，不是页数整数倍） |
| `rosterPageSize` | 30 | ui | 原方案约束 |
| `fsListPageSize` | 50 | ui | 与历史页一致，虚拟化列表 |
| `fsListSoftCap` | 2000 | ui | `/fs/list` 无服务端分页；超过即显示截断提示，避免把整目录塞进组合 |
| `rosterForegroundStaleMs` | 10 min | sync | 前台 `ON_START` 判定是否重拉 `session.list` |
| `rosterWorkerStaleMs` | 30 min | sync | 周期 Worker 的独立阈值；必须大于 15min 周期，否则每轮都拉全量（原方案矛盾点） |
| `baselineKeySweepSkipThreshold` | 400 | pending | 网关 baseline replay 上限 500；留 100 余量避免边界抖动 |
| `baselineKeySweepResumeThreshold` | 300 | pending | 恢复点低于跳过点 100，形成迟滞带；统计对象是待处理 key 数 |
| `needsResyncMaxAttempts` | 2 | history | 连续两次未覆盖读即 `degraded`，停止自动 resync |
| `historyRepairPageBudget` | 20 | history | 单次修复读最多 20 页（=1000 条），防止后台无限拉取 |
| `islandMergeGap` | 1 | storage | islands 相邻即合并，保持 `applied_island` 行数与会话内实际连续段同阶 |
| `maxEventsPerSession` | 5000 | storage | raw frame 保留上限；超出按已覆盖前缀裁剪并计数 |
| `identityPurgeAfterMs` | 7 days | security | 原方案约束：retired digest 保留 7 天供迟到帧归因 |
| `tombstoneTtlMs` | 24 h | pending | tombstone 有效期；过期后由 baseline 重确认 |
| `uploadChunkBytes` | 4 MiB | upload | 与 Web 客户端约定一致；网关不约束分块大小，2 GiB 总量受限 |
| `fsPreviewMaxBytes` | 1 MiB | files | 网关 `/fs/preview` 的 413 阈值，超过直接走 `/fs/file` |
| `imagePromptMaxBytes` | 3 MiB | upload | 网关 JSON-RPC body 4 MiB 限制下 base64 的可用二进制上限 |
| `draftDebounceMs` | 2000 | ui | 原方案约束 |
| `releaseAabBudgetBytes` | 12 MiB | build | G11 体积门禁；超出需要显式放宽并记录原因 |
| `releaseApkBudgetBytes` | 20 MiB | build | 同上，APK 形态 |

## 与产品限制的关系

`rosterWorkerStaleMs` 和 `pollWaitMs` 决定了审批通知的最坏延迟：无推送时是“下一次同步周期”，
UI 文案必须与 `SyncTuning` 的周期值一致地表达，不得承诺分钟级实时（原方案 §2.8、§15）。
