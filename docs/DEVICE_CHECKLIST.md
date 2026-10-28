# 真机验证清单（G20 证据载体）

本清单对应 ACCEPTANCE.md 的 G20。规则：本机无 emulator、无系统镜像、`adb devices` 为空，
所以这些项**不能**被任何自动化门禁判为 PASS；G20 的状态是 ADVISORY，本轮每项固定 `NOT_RUN`。
把这里任何一项写成 PASS 而无设备序列号的报告都是无效证据。

填写约定：
- `状态` 只允许 `NOT_RUN | PASS | FAIL | BLOCKED`。
- `PASS/FAIL` 必须同时填 `设备`（`adb getprop ro.product.model` + Android 版本 + 厂商 ROM）与
  `观测`（看到的实际现象，不写“符合预期”）。
- 一轮只能改一次，改完在末尾 `记录` 追加一行日期与结论，保留历史。

## 1. 后台调度真实性

| # | 检查项 | 判定方法 | 状态 | 设备 | 观测 |
|---|---|---|---|---|---|
| 1.1 | 15min 周期任务在未充电待机下实际触发间隔 | `adb shell dumpsys jobscheduler` 看 `dsh.sync.periodic:<serverId>` 的 `last run time` 序列，静置 4h | NOT_RUN | | |
| 1.2 | Doze（`dumpsys deviceidle force-idle`）后 pending 审批是否仍能到达 | 强制 Doze 30min，从 web 端发起审批，测手机通知延迟 | NOT_RUN | | |
| 1.3 | 厂商省电白名单缺失时的行为 | 小米/华为/OPPO 各一台，不加白名单，杀后台后 1h 内同步次数 | NOT_RUN | | |
| 1.4 | 进入后台的 one-time 补偿任务只覆盖首个 15min 空窗 | ON_STOP 后立即 `dumpsys jobscheduler`，确认只多一个 `dsh.sync.once:*` | NOT_RUN | | |
| 1.5 | 前台接管时 Worker 得到 `ForegroundTookOver -> success` 且不重试风暴 | 后台 Worker 运行中拉回前台，观察日志无重复 retry | NOT_RUN | | |
| 1.6 | Android 15 dataSync 配额：确认没有常驻前台服务 | `adb shell dumpsys activity services` 无长驻 sync 服务 | NOT_RUN | | |

## 2. 通知与权限

| # | 检查项 | 判定方法 | 状态 | 设备 | 观测 |
|---|---|---|---|---|---|
| 2.1 | Android 13+ 拒绝 POST_NOTIFICATIONS 后收件箱计数仍正确 | 拒绝权限，产生 3 条 pending，比对应用内计数 | NOT_RUN | | |
| 2.2 | 权限从拒绝改为允许后 outbox 补齐未发通知（不重复） | 切换权限后触发一次同步，检查通知数量与 tag 去重 | NOT_RUN | | |
| 2.3 | 进程被杀后点击通知能重建会话页并聚焦卡片 | 划掉 App 再点通知，验证冷启动落点与高亮 | NOT_RUN | | |
| 2.4 | 锁屏私密：锁定状态下不显示正文（本应为通用文案） | 锁屏收到通知，查看可见范围设置 | NOT_RUN | | |
| 2.5 | 通知正文不含 token/路径/问题正文 | 抓取通知的 `extras`（`adb shell dumpsys notification --noredact`）核对 | NOT_RUN | | |
| 2.6 | 已解决的请求其通知被 cancel（tag 命中） | 在 web 端解决审批，观察手机通知消失 | NOT_RUN | | |

## 3. 网络与配对

| # | 检查项 | 判定方法 | 状态 | 设备 | 观测 |
|---|---|---|---|---|---|
| 3.1 | 扫码在低光/倾斜/多地址 QR 下的成功率与耗时 | 同一 QR 打 10 次，含屏幕翻拍与斜 45° | NOT_RUN | | |
| 3.2 | 明文 LAN 的 MITM 面：确认页警告真实可见且 release 不跳过 | 抓包看确认页存在，release 构建检查警告未被裁剪 | NOT_RUN | | |
| 3.3 | 切换 Wi-Fi↔蜂窝时 cursor 不丢、不重复拉全量 baseline | 同步中途切网，观察后续请求是否 `since=<已有 cursor>` | NOT_RUN | | |
| 3.4 | captive portal（热点强制登录页）不置 AuthExpired | 连上需登录的公共 Wi-Fi，检查认证状态仍是 Unreachable | NOT_RUN | | |
| 3.5 | 网关重启后 epoch 变化触发 reset 并恢复 | 重启 dsh-Remote，观察 tombstone→baseline→events 顺序 | NOT_RUN | | |
| 3.6 | 长轮询在运营商 NAT 超时下的表现（`wait=25000`） | 蜂窝网络下统计 poll 失败率与退避分布 | NOT_RUN | | |
| 3.7 | 多服务器共用同一 token 时 identity 隔离 | 添加两台指向同一 DSH 的地址，检查 cursor/通知互不覆盖 | NOT_RUN | | |

## 4. 大列表与内存

| # | 检查项 | 判定方法 | 状态 | 设备 | 观测 |
|---|---|---|---|---|---|
| 4.1 | 1000 会话列表首帧时间与滚动帧率 | `adb shell dumpsys gfxinfo` 快速滚动 200 项 | NOT_RUN | | |
| 4.2 | `/fs/list` 10k 条目目录不 OOM 且有截断提示 | 进入构造的大目录，记录 PSS 峰值 | NOT_RUN | | |
| 4.3 | 180 条窗口 + 向上加载 20 页不泄漏 | 反复加载后 `am dumpheap` 比对 Room 缓存 | NOT_RUN | | |
| 4.4 | 低内存设备（≤3GB）回收 Activity 后草稿与滚动位置恢复 | 开 `Don't keep activities` + 输入草稿后切走再回 | NOT_RUN | | |

## 5. 时间线可读性（人工判定）

| # | 检查项 | 判定方法 | 状态 | 设备 | 观测 |
|---|---|---|---|---|---|
| 5.1 | gap marker 与“历史可能不完整”提示在真实断连后可见 | 拉网线制造 gap，重连后检查会话行与时间线 | NOT_RUN | | |
| 5.2 | 压缩提示不伪装成 assistant 消息 | 触发 `/compact` 后核对渲染 | NOT_RUN | | |
| 5.3 | INCOMPLETE 工具条目显示为中性而非红色失败 | 制造中断的 turn 后检查颜色与文案 | NOT_RUN | | |
| 5.4 | 中文/英文 locale 下无截断、无硬编码文案 | 切换系统语言遍历四个顶层页 | NOT_RUN | | |
| 5.5 | 深色模式 + 无障碍字号最大档不破版 | `ui_mode=night` 与 fontScale 1.5/2.0 | NOT_RUN | | |

## 记录

- 2026-10-27 清单建立，全部 `NOT_RUN`；本轮 G20 判 ADVISORY，不阻塞验收。
