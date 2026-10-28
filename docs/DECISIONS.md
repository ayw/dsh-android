# DSH Android 客户端：决策增补（对方案 C 的裁定）

版本：v1.1 增补稿
被增补文档：`/home/keivn/.codebuddy/plans/cosmic-cascade-curie-BtnkTcCG.md`（下称“原方案”）
验收标准：[ACCEPTANCE.md](ACCEPTANCE.md)
本文只写“原方案没定、或与事实冲突、需要裁定”的部分。原方案未被本文修改的约束继续生效。

落地位置：`/home/keivn/AndroidStudioProjects/deepseek-harness/dsh-android/`
原方案写的 `AndroidStudioProjects/dsh-android` 作废：产物必须落在用户指定的 deepseek-harness 目录内。
选择仓库顶层 `dsh-android/` 而不是 `apps/android/`：`apps/*` 同时是 pnpm workspace 与
`scripts/check-workspace-constraints.ts` 的扫描范围，往里塞一个 Gradle 工程会把客户端构建
变成 Node 门禁的输入；顶层目录不在任何 Node 门禁的 glob 内，互不干扰。
实测确认（2026-10-27）：`package.json` 的 workspaces 为 `vendor/*`、`packages/*/*`、`native/system`、
`native/system/packages/*`、`apps/*`、`website`，`dsh-android/` 不匹配任何一条；
`git status --porcelain` 只多出一条 `?? dsh-android/`，无其他污染。
工程内另置 `.gitignore` 排除 `build/`、`.gradle/`、`.kotlin/`、`local.properties`，
使该目录将来独立成仓时不会带入本机 SDK 路径。

---

## 1. 事实修正（原方案对后端的描述有错，必须先纠正）

| # | 原方案说法 | 实际 | 证据 | 对客户端的影响 |
|---|---|---|---|---|
| F1 | 历史请求带 `throughSeq` | `throughSeq` 由网关注入，客户端只发 `beforeSeq`/`maxMessages` | `dsh-Remote/gateway.js:2977-2998` | `:core:protocol` 的 `HistoryRequest` 不含 `throughSeq` |
| F2 | 历史页有 `cut` 字段，`readFrontier = 页面的 cut` | 响应没有 `cut` | `gateway.js:2896-2903` | frontier 取 `page.events.first().event.seq`；空页时保持不动 |
| F3 | poll 只需 `since`/`wait` | 还必须回传 `epoch`；响应另有 `ok`、`kind`、`waitSupported` | `gateway.js:3251-3252,3342` | `PollRequest` 增加 `epoch`；`waitSupported=false` 时退化为短间隔轮询并计入诊断 |
| F4 | ws-ticket “8s” | 8s 只是 Web 客户端取票超时；票据本身 TTL 90s、最多 4 次使用 | `gateway.js:1291,1468`、`public/app.js:527` | 每条 WS 连接取一次票，缓存上限 4 次使用；不共享票据 |
| F5 | `POST /api/respond` 的失败按 409 三分类 | 网关对已结算/未知 id/上游失败一律返回 **HTTP 200 `{accepted:false}`**，三类无法区分 | `gateway.js:6297-6317` | v0 只实现 `accepted:true/false` 两分支；409 三分类保留给 BFF v1，v0 allowlist 为空即等价于全部 STALE |
| F6 | capability 可能要自造 | 网关已在 `/health` 无鉴权发布整数 capability 表，字段名与原方案完全一致 | `gateway.js:1076-1093,6732` | 配对/连接时读 `/health`，无需新握手 |
| F7 | 身份 digest 出自网关 | 出自遗留 Android 客户端：`sha256(base.replace(/\/+$/,"") + "\n" + token)`，UTF-8、小写 hex | `dsh-Remote/android/app/src/main/java/com/dshremote/app/PendingNotifications.java:25-27` | 以该 Java 公式为唯一权威；Web 用的是另一种（`\u0000` 分隔、不哈希），不得参照 |
| F8 | 审批 id 是 `payload.approvalId` | `approval/asked`/`approval/decided` 的 id 字段是 `data.id`，asked 另有 `toolName`、`callId?`、`reason?` | `packages/interaction/user-approval/src/types.ts:44-58` | Kotlin 模型用 `data.id`；outcome 枚举固定为 `allowed-once | rejected | cancelled | unavailable` |
| F9 | 提问用 `payload.questionRpcId`，`rpcId == eventId` | DSH 没有 question 事件。提问就是名为 `ask_user_question` 的 `tool/call`，身份是 `callId`；解决由它的 `tool/result` 或后续 `user/message(source.kind='user-question-reply')` 表示；`rpcId` 只存在于客户端连接层 | `packages/interaction/user-questions/src/types.ts:89-96,134`、`src/projection.ts:28`、`packages/client/connection/src/rpc.ts:69` | pending 的 question 分支改为 tool-call 派生；golden 不依赖 `questionRpcId` |
| F10 | 帧上有 `replay:true` | DSH 事件里没有 `replay`；它是网关 poll/mux 缓冲的传输元数据 | 全仓 grep 无此事件字段 | `replay` 属于 transport 层，不进 durable 领域模型（与 F3 的 `epoch/reset` 同级） |
| F11 | `interrupted:true` 在 `assistant/attempt` | 该标记在 `assistant/message.data`；`attempt` 没有 | `packages/core/session/src/types.ts:349-357,363` | “已中断”按 message 判定，attempt 只做诊断行 |
| F12 | stream/reasoning 是独立帧 | 无独立帧：`stream` 内嵌在 `assistant/message.data.stream` 与 `attempt.data.stream`；reasoning 是 content block + `reasoning-chunks` 记录 | `types.ts:354,363`、`packages/llm/llm/src/assistant-stream.ts:20-44` | 投影器只消费 message 帧，overlay 从内嵌 stream 取 |
| F13 | tool result 关联键 `data.callId ?? data.message?.source?.callId` | `tool/result` 无顶层 `callId`；可用键为 `data.message.toolCallId ?? data.message.source.callId`；内嵌 `tool-call` block 用的是 `block.id`（同一 id 空间、不同字段名） | `packages/llm/llm/src/message.ts:29-32,173-178`、`llm/types.ts:105-108` | 关联函数按此三级顺序实现，并有 golden |
| F14 | `/fs/upload/probe` 与 `/fs/upload/control` 都 POST；4 MB 分块是服务端约束 | 实际是 `GET /fs/upload-probe`、`POST /fs/upload-control`；4 MiB 是 Web 客户端约定，网关只有 JSON-RPC body 4 MiB 上限与 2 GiB 总量上限；`/fs/preview` 另有 1 MiB 上限（超限 413 `preview-too-large`） | `gateway.js:1230,5109,5556,5666,5993` | 上传分块是客户端策略（默认 4 MiB，进 tunable）；预览超 1 MiB 直接走 `/fs/file` |
| F15 | prompt 超时只能 unknown | `operations>=1` 时 `POST /api/operations` 提供 202 + 持久回执 | `gateway.js:6157-6248` | prompt/审批写操作优先走 operations，UNKNOWN 窗口显著缩小 |
| F16 | 未知事件“可忽略” | 未知且**没有** `ignorable:true` 的事件表示日志不可安全重建；事件目录远大于原方案列举（约 63 类，另有 `plugin:*` 开放类型、`compaction/prune`、`workspace/changes` 等） | `packages/core/session/src/types.ts:510-519,532`、`known-event-types.ts:22-85` | 见 D08 |

原方案其余被抽查的关键约束全部成立（`session.list` 忽略 cursor、`beforeSeq` exclusive、默认 50、
baseline replay 500 上限、事件缓冲默认 1000、`host/session-removed` 的 replay map bug、`/fs/list` 无分页、
401 是设备认证唯一失败码、403 只用于 scope/管理面拒绝）。

---

## 2. 新增决策

### D01 工程与身份（BLOCKER）
新建独立 Gradle 工程 `dsh-android`，`rootProject.name = "dsh-android"`，
`applicationId = "ai.deepseek.dsh.android"`，`versionName 0.1.0`。
与 `com.dshremote.app`（dsh-Remote 的 Capacitor 壳）和既有的 `dsh-android-app` v1.0.0 **并列共存**：
不复用、不迁移、不改写它们；两者数据目录与签名互不影响。升级链迁移不在 v1 范围。

### D02 工具链版本（BLOCKER，全部实测过，不是猜的）
JDK 17（`/home/keivn/jdk17/jdk-17.0.20+8`）作为启动 JDK；Android SDK `/home/keivn/Android/Sdk`。
Gradle 9.6.1（本机已有发行版缓存）、AGP 9.4.1、AGP 9 内置 Kotlin（**不得**再应用
`org.jetbrains.kotlin.android` 插件）、Kotlin stdlib 2.4.21、Compose BOM 2026.09.00、
KSP 2.3.12、Room 2.8.5、kotlinx-serialization-json 1.12.0、OkHttp 5.5.1、WorkManager 2.12.0、
Paging 3.5.3、Turbine 1.2.1、JUnit 4.13.2 与显式版本的 `kotlin-test-junit`（versionless `kotlin("test")`
在本机镜像下解析不到）。`compileSdk 37 / targetSdk 37 / minSdk 26`。
`gradle.properties` 必须声明 `org.gradle.java.installations.paths`，否则 AGP 找不到本机 JDK。
minSdk 取 26 而非 24：免 desugaring 配置、通知渠道与 `dataExtractionRules` 语义清晰，且与同产品线的
`dsh-android-app` 一致。

### D03 依赖仓库与镜像（BLOCKER）
**结论修订（实测推翻原写法）**：工程自身的 `settings.gradle.kts` 必须显式声明镜像仓库，不能依赖全局
init 脚本。实测两条证据：
1. `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 会把 `~/.gradle/init.d/mirrors.init.gradle` 注入的仓库
   `maven` 判为违规并直接失败，因此模式只能是 `PREFER_SETTINGS`，注入与本工程声明并存。
2. 走 `repo.maven.apache.org` 拉 `kotlin-scripting-compiler-impl-embeddable-2.4.21.jar` 报
   `Remote host terminated the handshake`（本机 TUN 代理断 TLS），而阿里云与华为云镜像同路径均 200。

因此 `pluginManagement` 与 `dependencyResolutionManagement` 的 `repositories` 顶部按序声明
`maven.aliyun.com/repository/{gradle-plugin,google,public}`、`repo.huaweicloud.com/repository/maven/`，
其后保留 `gradlePluginPortal()`/`google()`/`mavenCentral()` 以维持可移植性：镜像不可用时工程仍能构建，
镜像可用时优先命中。任何“只有阿里云没有”的构件一律不引入。

### D04 DI（BLOCKER）
手写构造图：`AppScope`（进程级）+ `WorkerScope`（WorkManager 用，从 `AppScope` 派生）。不引 Hilt/Koin。
理由：SyncActor 的生命周期绑定 connection identity、Worker 可能运行在另一个进程，注解作用域管理
反而掩盖这两个事实；手写让“谁创建、谁取消”可读。

### D05 导航（BLOCKER）
`androidx.navigation:navigation-compose` + kotlinx-serialization `@Serializable` route 对象，
单 `NavHost`，三个顶层目的地（会话/收件箱/设置）+ 会话详情、子代理详情子路由。
不引入 Navigation 3：v1 不需要多返回栈/scene，且版本可用性风险不应压在骨架阶段。
类型安全由 route 类满足，满足原方案“一个 type-safe NavHost”。

### D06 JSON 规格（BLOCKER）
全工程一个 `Json` 实例（`DshJson.spec`）：`ignoreUnknownKeys=true`、`explicitNulls=false`、
`encodeDefaults=false`、`classDiscriminator="type"`。该配置本身有一条 golden 测试，
`payloadJson` 一律原样存取、不重新编码，保证 BFF 切换时的字节可比性。

### D07 持久化（BLOCKER）
Room + KSP。**投影器是纯 JVM 函数**（`List<DurableEvent> -> TimelineSnapshot`），不引用 Room 类型，
Room 只出现在写侧 `EventApplier`。这消解了原方案 §5.1 与 §12.3 的冲突。
所有表以 `(serverId, sessionId)` 为键（修正 §6 的 `session_head`、`applied_seq` 漏 `serverId`）。
`session_event` 增加保留上限：每会话最多 `maxEventsPerSession=5000` 行，超出按 contiguousSeq 之前的
已覆盖前缀裁剪，并写入 `retentionTrimmed` 诊断计数。

### D08 未知事件规则（BLOCKER，消解 §17.6 与 §13 的矛盾）
帧一律持久化并推进 `contiguousSeq`；投影分三档：
已登记类型 → 正常投影；未知且 `ignorable==true` → 只进“未支持事件”计数，不产生条目；
未知且 `ignorable!=true` → 会话标记 `historyUnsafe`，时间线在该处显示硬边界并提示历史不可重建。
这条规则让“推进 frontier”和“不假装理解”同时成立。

### D09 单一所有者的两条逻辑通道（BLOCKER，消解 §17.1 与 §5.7 的矛盾）
不变量改为：**一个 connection identity 在一个进程内只有一个 SyncActor，它是所有 transport 的唯一所有者；
mux 与 host 是该 actor 内的两条逻辑通道，不得由 Activity/Worker 另开第二条。**
Worker 不拥有 socket，只通过 actor 的请求队列触发 one-shot poll。

### D10 前后台交接改为 Room 事实（BLOCKER，消解 §5.7/§10 不可实现的 CAS 描述）
`sync_state` 增加 `foregroundRequestedAt`、`owner`（`NONE|FOREGROUND|WORKER`）两列，作为跨进程唯一真相。
Worker 开始前 CAS `owner`；抢不到就返回 `Result.retry()`（前台接管时返回 `Result.success()`）。
WorkManager 没有 `Deferred(ForegroundTookOver)` 这种结果，原方案的内存 CAS 表在“Worker 新进程”下不成立，
故只保留进程内 `stopSignal`，进程间一律走 Room。
one-time 任务用 `REPLACE` 语义的边界写清：`enqueueUniqueWork(..., ExistingWorkPolicy.REPLACE)` 只替换
**未开始**的任务；已在跑的任务由 `owner` 与 `stopSignal` 收敛，不声称能取消。

### D11 roster 全量拉取节奏（BLOCKER，消解 §4.2.1/§5.4 与 §10 的矛盾）
两个阈值分层且互不矛盾：前台 `ON_START` 在 `rosterAge > 10min` 时拉 `session.list`；
周期 Worker 只在 `hostResyncPending` 或 `rosterAge > 30min` 时拉。
15 分钟周期因此不会每轮都拉全量。

### D12 可调参数唯一来源（BLOCKER）
所有阈值集中在 `SyncTuning`（`:core:domain`，纯 JVM data class，带默认值与来源注释），由 DI 注入；
不再有散落的魔法数。数值与理由见 [TUNING.md](TUNING.md)。关键裁定：
`baselineKeySweepSkipThreshold=400 / baselineKeySweepResumeThreshold=300` 针对**待处理 key 数**
（不是 baseline 行数），带宽 100 用于避免抖动；`historyWindowItems=180` 与 `historyPageSize=50` 是
两个独立数值（原方案给定），窗口不是整数页——`SyncTuning` 的不变式只要求窗口**至少容纳一页**，
分页对齐由 Paging 3 的 key 保证，与保留条数无关；
backpressure 单位是**字节**（`channelMaxBytes=8MiB`）而不是批数，因为一个 baseline 批可达 500 行；
`pollWaitMs` 客户端请求 25s 但必须尊重响应里的 `waitSupported`，`false` 时降到
`pollWaitUnsupportedDelayMs=2s` 并标记 degraded。
落地：这个「唯一来源」由 G09 的两个相反方向共同守住——G09.3 禁止 `SyncTuning.kt` 与测试源码之外
出现字面量，G09.5（`tools/acceptance/check-tuning.sh`）要求 `SyncTuning` 的 37 个字段与
`docs/TUNING.md` 的行一一对应且默认值求值后相等，因此新增字段忘记写表、或表里留旧默认值都会 FAIL，
而不只是靠评审发现。

### D13 pending 身份与状态机（BLOCKER）
`requestId`：approval 取 `approval/asked.data.id`；question 取 `ask_user_question` 的 `callId`。
主键 `(serverId, kind, requestId)` 不变。v0 的 resolve 结果只有两分支（`accepted:true` → 乐观 RESOLVED
等确认；`accepted:false` → STALE 并刷新基线）。`operations>=1` 时用回执把 UNKNOWN 收窄到“回执存在但未结算”。
sweep 未执行（key 数超阈值）时 completeness 取 `unknown`，计数显示“未知”而不是数字，补齐 §8.4 缺的那个状态。

### D14 凭据与轮换（BLOCKER）
`AndroidKeyStore` AES-256-GCM，别名 `dsh.credential.v1`，随机 12 字节 IV，
密文 blob 带 `magic + version` 头，AAD 绑定 identity digest。
token 轮换 = 重新配对（QR token 是全权限凭据，网关没有面向设备的轮换端点）；
旧 identity 进 `identity_retired`，7 天后按 ledger 清理。StrongBox 不可用时回退普通 AES-256-GCM 并记录诊断。

### D15 文案与语言（BLOCKER）
默认 `values/` 为简体中文，`values-en/` 为英文，键集必须完全一致（有门禁）。
所有用户可见文案走 `ui/text` 下的类型化字符串资源，禁止在 Composable 里硬编码。
主题：Material 3 + 动态取色（Android 12+ 生效，以下回退内置调色板），深色跟随系统。
v1 不做平板双栏（手机优先），但布局必须能在宽屏不崩（`maxWidth=640dp` 内容栏）。

### D16 日志（BLOCKER）
自定义 `DshLog` 门面 + `Redaction` 过滤器；除 `:core:network` 的门面实现文件外，源码禁止出现
`android.util.Log`（脚本门禁）。禁止 `Timber`/`X509TrustManager` 匿名实现（脚本门禁）。

### D17 R8 与构建型态（BLOCKER）
`debug`：不混淆、不签名上架；`release`：`minifyEnabled=true`、`shrinkResources=true`、
无签名时产出 unsigned AAB；`acceptance` 变体：注入 canary token 供 APK 字符串扫描。
keep 规则只允许按注解/类名精确保留，禁止 `-keep class **{*;}`。

### D18 契约来源与所有权（BLOCKER，消解 §12.1 与 §16 的冲突）
录制器属于客户端，放在 `dsh-android/tools/contract/record.mjs`，只读地
`require(process.env.DSH_REMOTE_HOME + '/tests/fixtures/realistic-stack.cjs')` 并取得
`createRealisticStack`（该 fixture 已导出，且自身支持无参启动打印 base/token）。
产物写 `dsh-android/contract/gateway/v0/*.json` + `SHA256SUMS` + `manifest.json`
（网关版本、录制时间、占位符规则：token/ticket/epoch/端口/base 一律规范化）。
`--check` 重新录制并与已提交产物比对，漂移即失败。dsh-Remote 仓库不改一个字节。
`DSH_REMOTE_HOME` 未设置时：录制门禁报“环境不满足”，验收脚本把该门禁标记为 BLOCKED 而非 PASS。

### D19 分页实现（BLOCKER）
`androidx.paging:Paging 3` + 自写 `PagingSource`，keyset 使用不可变 `rosterRank`
（每次 roster 同步写入的单调序号 + `sessionId` 破平），**不用 `updatedAt` 排序**——
`updatedAt` 会被后台同步改写，导致原方案 §12.7 要求的不跳项无法成立。
历史分页用 `(sessionId, seq)` desc，`beforeSeq` exclusive。

### D20 设备相关测试的处理（BLOCKER）
本机无 emulator/系统镜像/设备（`adb devices` 为空，`emulator/`、`system-images/` 不存在），
因此 instrumented 与真机测试**不进阻塞门禁**，改为 `docs/DEVICE_CHECKLIST.md` 一次性人工清单，
在每个里程碑出口执行并把结果贴进验收证据。原方案 §12.8 的能力缺口在产品文档里明说。

### D21 Robolectric 的条件性（NON-BLOCKER，失败时降级）
Room 的 JVM 测试用 Robolectric。本机的 TUN 代理会让 Robolectric 自带的 Maven 解析器
（不受 Gradle init script 影响）在取 `android-all-instrumented` 时挂住。
处置顺序：先在测试 JVM 参数里指定可用镜像仓；拿不到就走“预取 jar 到本地仓库”；仍失败则
Room 测试降级为 schema 导出 + SQL 层校验（`:core:database:schema` + `contract` 断言），
并在验收报告中标为 DEGRADED，不静默跳过。业务正确性已由纯 JVM 投影测试覆盖，降级不改变结论强度。

### D22 CI 与提交边界（BLOCKER）
不在 deepseek-harness 仓库的 `.github/workflows` 或 `.gitlab-ci.yml` 里挂客户端作业
（那是共享 CI）。验收以 `dsh-android/tools/acceptance/run-gates.sh` 为唯一入口，
仓库外/后续接入由维护者决定。GitHub Actions 片段作为文档示例提供，不落地为共享配置。
落地形式：`tools/ci/env.sh`（JAVA_HOME/ANDROID_HOME/DSH_REMOTE_HOME 的单一来源，脚本先 source 它，
不依赖调用者 shell）、`tools/ci/run-ci.sh`（前置检查 → `./gradlew build -x lint` → 门禁；
**run-gates.sh 缺失时 exit 1**，构建成功不构成绿）、`.ci/ci.yml`（示例片段，独立成仓后复制到
`.github/workflows/`；release 门禁只在 `release-*`/tag 上跑，因为签名材料不在仓库里）。

### D23 观测与诊断页（NON-BLOCKER for v1 UI，BLOCKER for 验收）
诊断页展示的计数必须在 `:core:domain` 里有类型化的 `DiagnosticsSnapshot` 字段并有测试，
否则“degraded 状态有机器可读定义”无法验收。字段清单见 ACCEPTANCE.md 的 G10。

### D24 回滚与开关（BLOCKER）
`FeatureFlags`（Room 持久化的用户级开关，非编译期）：后台同步、通知、图片 prompt、文件浏览。
关掉后台同步即取消周期任务并撤回通知，投影与读取链路不受影响。任一开关默认值变更需要更新 TUNING.md。

### D25 依赖围栏的具体边（BLOCKER，G17 的可执行定义）
围栏不是“feature 不碰网络”这种口号，而是一组可脚本判定的边。已裁定并在 `settings.gradle.kts`
与各模块 `build.gradle.kts` 落实：

| 模块 | 允许 | 禁止 | 理由 |
|---|---|---|---|
| `:core:model` | 无（纯 JVM） | Android/Room/OkHttp | 词汇层，变更成本最低 |
| `:core:domain` | `:core:model` | Room/OkHttp/WorkManager | 端口与纯逻辑；能跑 JVM 测试才有 golden |
| `:core:protocol` | `:core:model` | **`:core:domain`** | DTO 只映射到传输中性类型（`TransportFrame` 等）；映射到端口是 `:core:network` 的职责，否则协议变化牵动用例层 |
| `:core:database` | `:core:model` | **`:core:domain`** | schema 层不实现 `*Store` 端口；`DurableStore` 适配器在 `:core:data`，否则实体会开始携带端口类型 |
| `:core:network` | model/domain/protocol | Room/Work | 传输 + `RemoteSource` 实现 |
| `:core:data` | 上列 + database/network | 被 feature 直接依赖 | 唯一装配 `SyncActor`/`EventApplier`/Worker 的地方 |
| `:feature:*` | model/domain/designsystem | protocol/network/database/data | G17 主判定 |
| `:app` | 全部 | 无（组合根豁免） | 豁免仅限装配点：`app/src/main/kotlin` 中除 `AppScope.kt`/`DshApplication.kt` 外的文件不得 import 实现层 |

`Room` 的 `PagingSource` 不放 `:core:database`（那会把 androidx.paging 引进 schema 层）；DAO 只提供 keyset
查询原语，`:core:data` 用它实现 `PagingSource`。与 D19 一致。

### D26 island 合并算法只有一个实现（BLOCKER，防止两套真相）
`applied_island` 的区间合并/相邻折叠由 `:core:domain` 的 `Islands`（`applyIslands`/`normalize`/
`trimFrontier`/`coveredHead`）唯一实现，纯函数、可 JVM 测试。`:core:database` **不得**再写一份合并算法
（SQL 侧 `DELETE FROM applied_island WHERE endSeq BETWEEN …` 式的折叠就是第二套真相，一旦与 domain 的
相邻判定不一致，覆盖率与 frontier 会各自漂移）。DAO 只提供原语：
`replaceIslands(sessionId, spans)` —— 在同一事务内删除该会话的全部区间并批量插入已合并的列表。
调用方（`:core:data` 的 EventApplier）先用 `Islands` 合并，再整体替换。岛数量本来就小（frontier 之前的
会被 `trimFrontier` 删掉），全量替换的代价可接受，换来的是只有一处合并语义。

### D27 端口不得出现 stringly-typed 协议值（BLOCKER，落地时发现）
`DurableStore`/`SyncStateStore`/`PendingStore` 最初用 `kind: String`、`state: String`、`channel: String`
描述待处理类型、外发箱状态与通道。这与“投影不依赖网关 DTO”的直接推论冲突，而且会让两类验收变成
字符串比对而不是类型比对。已改为：

| 位置 | 原类型 | 现类型 | 出处 |
|---|---|---|---|
| `PendingKey.kind` | `String` | `PendingKind` | `model/Pending.kt` |
| `PendingKey.requestId` / `PendingStore.dismiss/tombstone` | `String` | `RequestId` | `model/Ids.kt` |
| `OutboxEntry.state` | `String` | `OutboxState`（New/Sent/Cancelled/Skipped） | `model/Sync.kt` |
| `OutboxEntry.notificationTag` | `String` | `NotificationTag`（值类，`init` 断言 `^[0-9a-f]{64}:[aq]:.+$`） | §8.5 冻结格式 |
| `ProjectionUpsert.kind/key` | `String`/`String` | `ProjectionFamily`/`recordKey` | `model/Sync.kt` |
| `SyncStateStore.cursor(channel)` | `String` | `ChannelKind` | `model/Ids.kt` |
| `SyncOwnerRecord.role` / `casOwner(expected,next)` | `String` | `SyncOwnerRole` + `holderId` | `model/Sync.kt` |

通知 tag 的 `a:`/`q:` 段收敛为 `PendingKind.wirePrefix`，构造点只有 `NotificationTag.of`，格式违规在构造时
就抛，不需要等到运行时撤不回通知。tag 的精确字符串由 G09 的 golden 测试固定。
网关的事件名、RPC 字段名只允许出现在 `:core:protocol` 的映射表里；`:core:domain` 与 `:core:model`
里出现 `payloadJson.contains("…")` 这类子串判定视为围栏违规（实施中已在 `ResetPlanner` 发现一处，
改为由协议层填 `BaselineEntry.pendingRef`，见 ACCEPTANCE G17 的补充断言）。

---

### D28 outbox 只有一个含义：通知外发期望（BLOCKER，落地时发现）
实现期出现两套互不兼容的 outbox 词汇：`:core:domain` 的写路径想往里塞 `kind="prompt", state="QUEUED"`
的“写请求回执队列”，而 `:core:database` 的表就叫 `notification_outbox`，按 `notificationTag` 分组计数
（badge）并支持整 tag 撤回。两者不能共存于同一张表：提示词没有通知 tag，也没有读取该行的消费者。
裁定：
- `DurableStore.OutboxEntry(kind: PendingKind, requestId: RequestId, tag: NotificationTag, state: OutboxState)`，
  `kind` 只能 Approval|Question，`tag` 非空且必须是冻结格式（`digest + ":a:|q:" + id`）。
- `SendPromptUseCase` 不再写 `DurableStore`：写侧结果由 `WriteResult.accepted/receiptId` 加 `operations`
  能力承接（D13），造一条没人读的行属于投机状态。
- `:core:database` 删除 `object OutboxStates { NEW, IN_PROGRESS, DONE, FAILED }`，列改存
  `OutboxState`（`New, Sent, Cancelled, Skipped`）枚举并经 `DshTypeConverters` 落库，
  避免同一概念两套拼写。
验证：`SendPromptUseCaseTest` 断言 accepted 与 timeout 两条路径都产生 `durable.batches.size == 0`。

### D29 帧信封按通道分形，且 mux 是双层信封（BLOCKER，真实契约录制后修正）
`contract/gateway/v0/`（网关 0.8.1，真实录制）证明原方案与初版 DTO 假设的扁平
`{seq, type, payload, replay?}` 都不对。观测到的实际形状：
- host 通道：`events[] = {seq, ts, event:{rpcId, payload:{sessionId, type, ...行字段}}}`，
  `payload` 本身就是那一行（`session/subscribed`、`host/session-status`）。
- mux 通道：`payload` 是 `{sessionId, type:"session/event", event:{type, data, seq}}`，
  即**再套一层**：真正的持久事件在 `payload.event`，外层 `type` 恒为 `session/event`。
- baseline 条目只有 `{rpcId, payload}`，**没有 `seq`**；因此 `BaselineEntry.lastSeq` 只能来自
  内层（有则取，无则为 null），sweep 取水位必须用非空最大值而不是末行。
- 时间字段是 `ts`（帧级）而不是 `time`。
裁定：
1. 外层 `seq` 是**传输游标**（`TransportCursor.sinceSeq`、poll 增量、gap/重放判定只看它）；
   内层 `payload.event.seq` 才是**会话内序号**（`SessionPosition.contiguousSeq`、`AppliedIsland`、
   `anchorSeq` 只看它）。两者不得互换，这正是双游标层存在的理由。
2. `:core:protocol` 建模两个 DTO：外层帧 `EventFrameDto`（`seq`/`ts`/`event`）与内层
   `session/event` 的 `payload.event`；`toFrame` 产出的 `TransportFrame` 同时携带
   `transportSeq` 与 `seq`（会话内），domain 只读后者、network 只游标前者。
3. `waitSupported` 语义修正：`wait=0` 时响应为 `false`，它表示“本次请求模式下是否支持长挂起”，
   不是能力缺失；因此 `waitSupported=false` 的降级只适用于我们**请求了** `wait>0` 的场合
   （D 系列里 `pollWaitUnsupportedDelayMs` 的生效条件）。
落地位置：`core/protocol/.../gateway/v0/Envelopes.kt`、`mobile/v1/Events.kt` 的映射，
以及 G04 契约重放的解码用例必须直接消费 `contract/gateway/v0/*.json`，让形状错误在 CI 里失败而不是在真机上。

### D30 历史行的第三种信封（BLOCKER，真实契约录制后修正）
`contract/gateway/v0/session-history-page.json` 里的一行是 `{"event": {type, seq, data}}`：
既没有 poll 帧的外层 `seq`，也没有 `rpcId`/`payload` 包裹。它既不是 D29 的 host 行，也不是 mux 双层信封，
而是**第三种形状**。初版 `SessionPageResponse.events` 复用 `List<EventFrameDto>`，而 `EventFrameDto.seq`
是必填、`event` 是 `{rpcId, payload}` —— 于是真实历史页在反序列化时直接缺字段失败，
M3 的向上翻页在真机上会整页读不出来；这个缺陷只因为没有一条重放测试走历史路径而躲过了 CI。
裁定：
1. 历史行用独立 DTO `HistoryRowDto(rpcId?, event?)`，`event` 就是持久事件对象本身；
   `toFrame` 里 `seq == transportSeq == event.seq`。历史是快照读，**不是游标流**，
   应用一页历史绝不能推进 poll 传输游标，因此 transportSeq 只是占位而不是第二层含义。
2. 缺 `event.seq` 的行落到 `EventSeq(0)` 排在最前，不借用当前游标：伪造位置会让去重把未应用的行当成已应用。
3. `resolveSessionEventRow` 是 mux 内层与历史行**共用**的唯一读取函数（同一族 D26 的“只有一个实现”理由），
   两条路径不得各自演化出对 `{type, seq, data}` 的不同理解。
落地位置：`core/protocol/.../gateway/v0/SessionHistory.kt`（`HistoryRowDto`）与
`Envelopes.kt`（`resolveSessionEventRow`）；G04/G12 的重放用例必须直接从 `contract/gateway/v0/*.json`
解码历史页（`PollContractReplayTest` 的 `history page rows decode through the D30 row shape…`），
并断言正文与内容块顺序——`assistant/message` 曾把 `content` 读成 `message.content`，
投影出空气泡而测试仍全绿（golden 用的 fixture 恰好是空 content），因此同一批测试同时钉住真实非空正文。

### D31 客户端分页不用 androidx.paging
`SessionRepository.paged` / `TimelineRepository.historyPage` 已经用显式 keyset 表达分页，
而 `:core:domain` 是纯 JVM 模块（G05 注记），不可能依赖 Android 的 androidx.paging；
`:feature:*` 又不能依赖 `:core:data`（G17）。三方夹逼下唯一自洽的做法是：
1. 特性模块自己做 keyset load-more（`SessionPage.nextKey`、`HistoryWindow.nextBeforeSeq`），
   按 key 去重，禁止 offset。
2. 特性模块与 `:app` 不声明 `paging-runtime` / `paging-compose`（已从四个 `:feature:*` 的
   build 文件删除；`:core:data` 是 android.library，仍可保留必须的分页依赖）。
3. G12 的 `--tests '*PagingSource*'` 指 `:core:data` 里把 keyset 变成一页的页源类
   （Room 查询 + 排序稳定性），不是 androidx 的 `PagingSource` 子类。

### D32 pending 卡片内容的解码缝
`:core:designsystem` 的 `PendingAnchorContent` 明确“由特性模块解码后传入”，但特性模块既不能依赖
`:core:protocol`（G17），也不该解析 JSON（D27）。因此新增三层：
1. `:core:model` 的 `PendingContent`（Approval / Question / Undecodable）——纯数据，无 JSON 类型。
2. `:core:protocol` 的 `decodePendingContent(kind, payloadJson)`——唯一解析点，坏 JSON/缺字段落
   `Undecodable` 而不抛。
3. `:core:domain` 的 `PendingContentDecoder` 端口，实现放 `:core:data`；特性模块只注入端口。
提问同时接受 `{arguments:{questions:[…]}}` 与 `{questions:[…]}` 两种形态（后者是
`DurableEvent.QuestionAsked.questionsJson` 的形态），因为 pending 行与事件解码器喂的是同一份录制内容。
G04/G07 的用例直接从 `contract/gateway/v0/*.json` 断言解码结果（`PendingContentCodecTest`）。

### D34 G18 验收的是移交语义，本轮不装 WorkManager 调度器
G18 的主题是 **worker-handoff-semantics**：前后台谁进临界区、回合边界让不让、失败要不要重来。
这三件事全部落在可静态验证的语义层，因此实现为 `SyncOwnerArbiter`（D10 的 CAS 裁决 + 过期接管 +
接管标记）与 `WorkerResultPolicy`（`SyncAttemptResult` → `Success/Retry/Cancel` 的唯一映射表），
测试为 `SyncOwnerArbiterTest` / `WorkerResultPolicyTest`。

**本轮不安装真正的 `DshSyncWorker`/`SyncRequestPort` 实现**，理由是事实性的而不是排期的：
M4 还没有配对与传输链路，`AppScope.syncRequestPort` 唯一诚实的回答就是
`DomainError.UnavailableCapability("background_sync")`（D13：不伪造 0 计数、也不伪造一条排上的周期任务）。
调度器落地的必要条件——网关可达、token 可续、周期任务有真实工作可做——属于 M5，
届时 `WorkerResultPolicy` 的三态就是 `ListenableWorker.Result` 的直接来源，不需要重写语义。

由此 G18 的证据只声明语义层通过；“后台周期同步真的跑起来”由 M5 的设备清单（G20）与
`UnavailableCapability` 消失来证明，不由本门禁冒充。

### D33 M4 收件箱只读，远端解决留给 M5
`/api/respond` 的 **请求体字段**（`approvalId`/`callId`/`outcome`/`value`）在 `Respond.kt` 里仍是
contract-recording TODO：录制里只有 200 `accepted:true` 响应，没有请求体。缺请求体就实现“批准”，
等于让 UI 假装能用。故 M4 的 `PendingAnchorCard` 传 `onDecision = null`（组件因此禁用提交），
只保留本地 `dismiss`/`undismiss`；编码侧（审批 outcome、回答批次）在 M5 随请求体录制一起落地。

## 3. 范围裁定：本轮实施到哪

原方案 M0–M8 是一次性交付不了的量。实施按里程碑推进，本轮的**必须完成并可验收**范围：

- M0 契约：录制器 + 真实录制的 `contract/gateway/v0/*` + `SHA256SUMS` + `--check`。
- M1 骨架：全部模块、版本目录、依赖方向围栏、release 安全门脚本、可组装 debug APK 与 release AAB。
- M2 时间线核心：领域模型、投影器、F8–F13 修正后的全部 golden（§12.3 列表 + D08 三档）。
- M3 读取链路：`:core:protocol` DTO、契约重放、Room 实体/DAO、cursor 双列、reset 顺序、islands、
  needsResync/HistoryIncomplete、SyncActor 与 EventApplier 的纯逻辑测试。
- M4 只读 UI（编译进 APK 的最小可用面）：会话列表、会话时间线、pending 卡片、收件箱、错误/空态/unknown 文案。
- M6 的安全内核：identity digest golden、SecretStore 实现、redaction golden、`identity_retired` 清理。

明确不在本轮（保持计划而非实现）：M5 写路径闭环的网关联调、M7 真机矩阵、M8 BFF 服务端。
`:core:network` 提供 `MobileV1Source` 的 DTO 与投影对等测试所需的最小实现，BFF 服务端本身不在客户端工程内。
每个未实施项在 ACCEPTANCE.md 里有对应门禁的 BLOCKED/NOT_IN_SCOPE 记录，不允许出现“绿灯但其实没测”。
