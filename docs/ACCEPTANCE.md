# DSH Android 客户端：最终验收标准

版本：v1.0
适用工程：`/home/keivn/AndroidStudioProjects/deepseek-harness/dsh-android/`
决策依据：[DECISIONS.md](DECISIONS.md)；参数依据：[TUNING.md](TUNING.md)

原方案只给了里程碑名称和一句“v0/v1 投影快照相同”，没有可判定的通过条件，因此“验收通过”此前无法声明。
本文补上：每条门禁的机器判定方式、阻塞与否、证据格式、里程碑进出条件。

模块种类决定测试任务名，本文的示例命令按此解析：`:core:model`、`:core:domain`、`:core:protocol`、
`:core:testing` 是纯 JVM 模块（任务 `test`）；`:core:database`、`:core:network`、`:core:data`、
`:core:designsystem`、`:feature:*`、`:app` 是 Android 模块（任务 `testDebugUnitTest`）。
**精确任务名以 `acceptance/gates.json` 为准**，执行器只读 `gates.json`，正文与 `gates.json` 冲突时
`gates.json` 胜出并需在 `REPORT.md` 里记为文档偏差。

## 0. 判定规则

唯一入口：

```sh
cd /home/keivn/AndroidStudioProjects/deepseek-harness/dsh-android
tools/acceptance/run-gates.sh            # 全量
tools/acceptance/run-gates.sh G05 G09    # 指定门禁
```

产物：`acceptance/manifest.json`（机器可读）+ `acceptance/REPORT.md`（人读摘要）。

状态只有六种，含义固定：

| 状态 | 含义 | 能否算验收通过 |
|---|---|---|
| PASS | 命令执行且断言全过 | 能 |
| FAIL | 命令执行且有断言失败 | 不能 |
| BLOCKED | 前置环境缺失，无法执行（如 `DSH_REMOTE_HOME` 未设） | 不能（范围内门禁） |
| DEGRADED | 以更弱但真实的检查替代了原检查，原因与恢复条件已记录 | 仅当该门禁声明允许 |
| NOT_IN_SCOPE | 本轮不实现（必须在 DECISIONS §3 有出处） | 不影响判定，但必须留痕 |
| ADVISORY | 非阻塞检查，失败只告警 | 不影响判定 |

**验收通过 ⇔ 所有 `blocking=true` 且属于本轮范围的门禁状态为 PASS，且 `manifest.json` 里每条 PASS 都带
可复算的证据（命令、JUnit 计数或工件 sha256）。** 绿色聚合数字不接受：报告必须能追溯到单条断言。

本机事实约束（写入判定，不允许被“跳过后当通过”利用）：无 emulator、无系统镜像、`adb devices` 为空，
因此 instrumented/Compose UI 设备测试一律 NOT_IN_SCOPE，由 G20 的人工清单承接；Robolectric 受 TUN 代理影响，
按 DECISIONS D21 允许 DEGRADED。

## 1. 阻塞门禁

每条给出：判定命令 / 断言 / 证据。命令全部能在无网络的 Linux 上跑（依赖已预热）。

### G01 toolchain-sanity
`tools/acceptance/run-gates.sh` 的前置检查：JDK 17 存在且 `java -version` 成功；`ANDROID_HOME` 下
`platforms/android-37`、`build-tools/36.0.0`、`cmdline-tools/latest/bin/apkanalyzer` 存在；
`./gradlew --version` 输出 9.6.1；`gradle.properties` 含 `org.gradle.java.installations.paths`。
断言失败即整套 FAIL，不做部分执行。

### G02 module-build
`./gradlew build -x lint`。断言：退出码 0；`:core:model`、`:core:domain`、`:core:protocol`、
`:core:database`、`:core:network`、`:core:data`、`:core:designsystem`、`:core:testing`、四个 feature、
`:app` 全部配置成功。证据：任务清单 + `.build-probe` 里的模块列表。

### G03 contract-drift
`node tools/contract/record.mjs --check`。断言：重新录制的事件轨迹与 `contract/gateway/v0/` 已提交内容
在规范化后逐字节相同（规范化规则见 `contract/gateway/v0/README.md`：token/ticket/epoch/port/base 替换）；
`SHA256SUMS` 全部匹配。证据：`manifest.json` 记录网关版本（取自 `/health`）与每个工件 sha256。
`DSH_REMOTE_HOME` 未设或 fixture 缺失 → BLOCKED。

### G04 contract-replay
`./gradlew :core:network:testDebugUnitTest --tests '*ContractReplay*'`。重放测试与传输实现同模块
（`:core:protocol` 只放 DTO，不依赖 OkHttp，故不能承载 MockWebServer 用例）。断言：每条录制轨迹由
OkHttp `MockWebServer` 重放，请求路径/方法/必要头与轨迹记录一致，响应解析出的 DTO 字段与轨迹的
期望值一致；负例（401、truncated、坏 JSON、未知事件、`accepted:false`）各自命中规定的处理分支。
证据：JUnit XML 用例数与名称。

### G05 projector-goldens
`./gradlew :core:domain:test`。断言：`acceptance/golden-cases.json` 列出的每个必需用例名
在测试源码里存在且通过（存在性本身是一条测试，防止悄悄删用例）。清单必须覆盖原方案 §12.3 全部 14 项
加 DECISIONS 修正后的新增项：approval `data.id` 身份、question 由 `ask_user_question` 的 `callId` 派生、
`interrupted` 位于 `assistant/message`、tool 关联三级键、`ignorable` 三档、closer-only turn、
内嵌 tool block 与独立 tool 事件同时存在时只渲染独立事件。
证据：`golden-cases.json` 与 JUnit XML 的双向比对结果（缺失/多余都列出）。

### G06 cursor-and-reset
`./gradlew :core:domain:test --tests '*Cursor*' --tests '*Reset*'`。断言：
`contiguousSeq` 与 `appliedMaxSeq` 独立推进；快照尾部 900..950 应用后 `contiguousSeq` 仍为 100；
只有 `seq > contiguousSeq+1` 记 gap；`replay` 低 seq 丢弃不算 gap；四个 reset 条件与
`tombstone → baseline → events → cursor` 顺序（用记录写入序列的假持久层断言顺序）；
单批异常时 cursor 不动；mid-poll 取消时 cursor 不动；batch 提交在 NonCancellable 下完成。
证据：JUnit XML。

### G07 pending-state-machine
`./gradlew :core:domain:test --tests '*Pending*'`。断言：状态转移表的每条边（含
`UNKNOWN → PENDING` 由 baseline 重确认、resolved-before-request、resolve 后重放 request 保持 tombstoned）；
`accepted:false → STALE` 且不重试；dismissed 不减少 `resolvedAt IS NULL` 的语义、计数不计 dismissed 但
会话 chip 显示其数量；sweep 跳过时 completeness=`unknown` 且计数不显示数字。
证据：JUnit XML + 转移表快照（`acceptance/pending-transitions.txt`）。

### G08 capability-matrix
`./gradlew :core:domain:test --tests '*Capability*'`。断言：以 8 个 capability 整数的
规定组合驱动（`wsTicket=0` ⇒ 假引擎断言从未打开 WS；`eventPolling=0` ⇒ 拒绝注册周期同步；
`eventEpoch=0` ⇒ 不追踪 epoch，reset 只由 `truncated`/`latestSeq<since` 触发；
`operations=0` ⇒ accepted 后显示 unknown 且无自动重试；`imagePromptTransport=0` ⇒ 图片入口禁用）。
未知 capability 值 ⇒ 功能隐藏且不崩溃。证据：JUnit XML，参数化用例名含组合标识。

### G09 security-invariants
分段执行，任一段失败即 FAIL：
1. `./gradlew :core:domain:test --tests '*IdentityDigest*'`：digest 逐字节等于
   `sha256(base 去尾部斜杠 + "\n" + token)`，固定测试向量 4 组（含带/不带尾斜杠、含端口、含路径）；
   断言实现里没有 `HttpUrl` 归一化、没有 host 小写、没有默认端口省略。
2. `./gradlew :core:data:testDebugUnitTest --tests '*Redaction*'`：把 canary token/ticket/prompt/工具参数/
   文件路径/会话标题写入假 log sink，断言 sink 文本不含这些值，session id 只出现后 8 位。
3. `tools/acceptance/check-source.sh`：`:core:*` 与 `feature/*` 源码中 `android.util.Log` 只允许出现在
   `DshLog` 门面实现文件；无 `X509TrustManager` 匿名实现、无 `hostnameVerifier { true }`、
   无 `allowBackup="true"`、Manifest 无 `dshremote` scheme、导出组件白名单只有 launcher；
   魔法数只出现在 `SyncTuning.kt`（正则扫描 `.kt` 数字字面量，白名单文件外即 FAIL）。
4. `./gradlew :core:data:testDebugUnitTest --tests '*SecretStore*'`：断言密文头含 magic+version、
   AAD 绑定 identity digest、IV 每次随机、解密在篡改后失败；并断言凭据不出现在 Room 实体字段类型里
   （通过实体清单反射检查，字段名/类型层面禁止 token 落 Room）。
5. `tools/acceptance/check-tuning.sh`：D12 的反方向。第 3 段只禁止 `SyncTuning.kt` 之外出现字面量，
   无法发现某个字段压根没进 `docs/TUNING.md`，或表里写的是过期的旧默认值。本段要求两边逐字段对齐：
   字段名集合相等（`backoffJitterLow/High` 允许对应表内单行 `backoffJitterRange`），且默认值求值后
   等于表内数值（`8L * 1024 * 1024` 与 `8 MiB`、`7L * 24 * 60 * 60 * 1000` 与 `7 days` 同一）；
   遇到无法解析的表达式即 FAIL，不做近似匹配。负向自检：改旧值、删行、改行名、注入不可解析默认值，
   四种漂移均必须让本段退出码为 1。
证据：各段 JUnit XML + `check-source.sh` / `check-tuning.sh` 的逐项 PASS 列表。

### G10 diagnostics-schema
`./gradlew :core:domain:test --tests '*Diagnostics*'` + `tools/acceptance/check-diagnostics.sh`。
断言：`DiagnosticsSnapshot` 至少包含 `transportCursorPerChannel`、`contiguousSeqLag`、`needsResyncCount`、
`historyIncompleteSessions`、`historyUnsafeSessions`、`pendingCompleteness`、`baselineSweepSkipped`、
`authState`、`lastPollWaitSupported`、`retentionTrimmedCount`、`badJsonCount`、`wsDowngradeCount`、
`identityRetiredPendingPurge`；每个字段有测试；UI 诊断页渲染该类型而不是临时字符串。
“degraded 状态有机器可读定义”由此门禁承担。

### G11 release-artifact
```sh
./gradlew :app:bundleRelease :app:assembleRelease
apkanalyzer manifest print app/build/outputs/apk/release/*.apk   # 与 acceptance/manifest-baseline.txt diff
apkanalyzer apk summary ...                                      # 体积
```
断言：R8 开启且 `mapping.txt` 存在；release AAB ≤ 12 MiB、release APK ≤ 20 MiB（超出即 FAIL，收紧依据
记录在 TUNING.md）；manifest diff 显示 exported 组件只有 launcher、`allowBackup=false`、
`dataExtractionRules` 存在、无 `REQUEST_INSTALL_PACKAGES`、FileProvider `grantUriPermissions=true`
且无全局读写；`acceptance` 变体 APK 的 `strings` 扫描不含 canary secret 明文。
证据：apkanalyzer 输出、体积数字、mapping.txt sha256。

### G12 paging-and-large-data
`./gradlew :core:data:testDebugUnitTest --tests '*PagingSource*' --tests '*Retention*'`。断言：
1000 会话/500 pending/10k 文件条目场景下 `PagingSource.load` 首屏 ≤ 30（会话）/≤ 50（历史与文件）；
50 次 invalidation 后 keyset 无重复、无遗漏、顺序稳定（用固定 `rosterRank` 序列断言）；
`/fs/list` 全量进内存前有上限保护（超 `fsListSoftCap=2000` 显示截断提示并本地分页）。
证据：JUnit XML + 用例内的计数断言输出。

### G13 coverage-floor
优先 `./gradlew koverVerify`：`:core:domain` 投影与 cursor 路径行覆盖 100%，`:core:data` 的
EventApplier/outbox 路径 ≥ 90%，其余模块 ≥ 70%，按模块声明而非仓库平均。
Kover 插件在本机镜像不可用时降级为：`tools/acceptance/check-golden-cases.sh` 的用例存在性 +
每个门禁的 JUnit 用例数下限（记录在 `acceptance/coverage-floor.json`），状态记 DEGRADED 并写明恢复条件。

### G14 i18n-parity
`tools/acceptance/check-i18n.sh`：`values/strings*.xml` 与 `values-en/strings*.xml` 键集完全相等、
无空串、无重复键；Composable 源码中不含硬编码中文/英文用户可见文案（扫描 `Text("...")` 字面量，
测试与预览文件除外）。

### G15 v0-v1-projection-parity
`./gradlew :core:network:testDebugUnitTest --tests '*Parity*'`。断言：同一份轨迹分别经
`GatewayV0Source` 与 `MobileV1Source` 映射后，规范化投影快照逐字节相同（两个适配器都在
`:core:network`，`:core:domain` 不感知后端）。
规范化定义（原方案缺这条，导致门禁不可判定）：递归排序 object key、丢弃 transport 字段
（`epoch`、`since`、`latestSeq`、`reset`、`truncated`、`replay`、`ok`、`kind`、`waitSupported`）、
`wallClock` 替换为固定值、数组保持投影输出顺序。证据：两份快照的 sha256 与 diff 长度 0。

### G16 database-schema-migration
`./gradlew :core:database:assembleDebug` 后（Room schema 由 KSP `room.generateKotlin` 落盘，工程里没有
`exportSchema` 这个任务）：`core/database/schemas/*.json` 必须存在，且与
`acceptance/schema-baseline/*.json` 逐字节一致（不一致时先跑 `tools/acceptance/update-schema-baseline.sh`
并在 `REPORT.md` 记录变更的表/列，人工确认后才能刷新基线；不使用 `git diff`，因为 dsh-android 尚未纳入版本控制）。
`DshDatabase` 无 `fallbackToDestructiveMigration()`；`identity_retired` 清理测试覆盖 A→B→A 幂等与 7 天边界。
Robolectric 可用时增加 Room 迁移实测；不可用时按 D21 记 DEGRADED（schema diff + 纯 JVM 假持久层仍在）。

### G17 dependency-fence
`tools/acceptance/check-modules.sh`：解析各模块 `build.gradle.kts` 的依赖声明，断言 `feature/*` 只出现
`:core:domain`、`:core:model`、`:core:designsystem`（不得出现 `:core:protocol`、`:core:network`、
`:core:database`、`:core:data`）；`:core:model` 无 Android 依赖；`:core:domain` 无 Room/OkHttp 依赖。
**`:app` 是组合根，按构造豁免**：它可以依赖 `:core:data`/`:core:network`/`:core/database` 做 DI 装配，
但脚本必须断言 `app/src/main/kotlin` 里除 `AppScope.kt`/`DshApplication.kt` 外的源文件不 import
`ai.deepseek.dsh.android.(protocol|network|database|data).` —— 豁免是装配点的豁免，不是 UI 层的豁免。
证据：脚本输出的依赖图与违反项列表。

### G18 worker-handoff-semantics
`./gradlew :core:data:testDebugUnitTest --tests '*SyncOwner*' --tests '*WorkerResult*'`。断言：
`owner` 列的 CAS（NONE→WORKER 成功、FOREGROUND 时 worker 得 retry、接管后得 success）；
结果映射表四类（Completed/接管/Busy/AuthExpired/Unreachable）与 D10 一致；
`AuthExpired` 只能由观察到的 401/403 进入，超时/TLS/DNS/1006/坏 JSON 都不置位（每种输入一条用例）；
ON_START 不从 AuthExpired 自动启动；401 连续 3 次后台失败暂停周期且能在重连后恢复。
证据：JUnit XML。

### G19 pairing-flow-invariants
`./gradlew :core:data:testDebugUnitTest --tests '*Pairing*'`。断言：QR 解析多地址去重、
只有确认后落盘（未确认时 SecretStore 与 Room 均无写入）、`/health` 成功才连接、
配对阶段允许 http→https 升级并持久化 https base、跨 host:port 的重定向一律不携带 Authorization
（用假 interceptor 链断言头消失）。

### G20 device-checklist-evidence
`docs/DEVICE_CHECKLIST.md` 存在且每项有 `状态 = NOT_RUN/…` 字段；本轮全为 NOT_RUN，
门禁状态 ADVISORY。任何把它当 PASS 的报告都是无效证据。

## 2. 建议性检查（失败告警，不阻塞）

- `./gradlew :app:lintRelease` 新增 issue 数（基线 0）。
- `apkanalyzer dex packages` 的包体占比报告（体积异常增长提示）。
- `tools/acceptance/check-docs-links.sh`：`docs/` 内相对链接可达。
- 轨迹体积统计：单次 baseline 批的字节数分布（用于验证 D12 的 `channelMaxBytes` 选择）。

## 3. 里程碑进出条件

| 里程碑 | 进入条件 | 退出条件（门禁 id） |
|---|---|---|
| M0 契约 | G01 PASS | G03 PASS |
| M1 骨架 | M0 退出或 G03 合法 BLOCKED | G02、G17、G11（骨架阶段允许 debug 构建，release 门禁只校验清单/R8 配置存在）、G14、G16 |
| M2 时间线核心 | M1 退出 | G05、G06、G07、G08、G10 |
| M3 读取链路 | M2 退出 | G04、G12、G15、G18 中的 cursor 部分、G13 |
| M4 只读 UI | M3 退出 | G02 含 UI 模块编译、G14、G09.3、G11；设备侧交互由 G20 承接 |
| M5 交互闭环 | M4 退出 | G04 的写路径用例、G07 的 resolve 分支、G18 的 AuthExpired 分支 |
| M6 后台与配对 | M5 退出 | G18、G19、G09.1、G09.4、G16 的 retirement 清理 |
| M7 加固 | M6 退出 | G11 全量、G13、G20 至少一轮真机记录 |
| M8 BFF | 服务端 `/mobile/v1` 可用 | G15 覆盖真实 BFF、G04 对 v1 轨迹重放 |

本轮范围（DECISIONS §3）到 M4 + M6 的安全内核：M5 的网关联调与 M7/M8 记 NOT_IN_SCOPE。

## 4. 证据格式

`acceptance/manifest.json`：

```json
{
  "schemaVersion": 1,
  "runId": "…",
  "startedAt": "…",
  "toolchain": { "jdk": "…", "gradle": "9.6.1", "agp": "9.4.1", "compileSdk": 37 },
  "gates": [
    {
      "id": "G05",
      "title": "projector goldens",
      "blocking": true,
      "status": "PASS",
      "command": "./gradlew :core:domain:test",
      "assertions": { "tests": 41, "failures": 0, "skipped": 0 },
      "artifacts": [ { "path": "…", "sha256": "…" } ],
      "notes": ""
    }
  ],
  "verdict": { "accepted": true, "blockingFailures": 0, "blockedInScope": 0, "degraded": ["G16"] }
}
```

规则：`verdict.accepted` 由脚本按 §0 计算，不允许人工改；每条 PASS 必须有 `assertions` 或 `artifacts`；
`degraded` 与 `NOT_IN_SCOPE` 必须在 `notes` 指向 DECISIONS 条目。
