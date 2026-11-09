# Dynamic Glyph → Android 完整功能迁移矩阵

更新日期：2026-11-08

本文是 [Plan.md](../Plan.md) 的逐项实施与验收补充，不是高层功能概览，也不替代其中的工程约束。依据为当前工作目录内的 iOS 参考源码；**当前 Swift 实现优先于旧 Docs**。复选框用于记录 Android 的实现与验证完成情况，初始未勾选既不表示 iOS 缺失，也不表示已经检查过 Android 当前代码。只有实现和对应验证均有证据时才可勾选。

## 1. 完成标准与排除边界

- [ ] **除下列明确排除项外，本文 P1–P5 每一项均交付并通过相应验证。** P1–P5 是顺序，不是可选范围；P3 核心可用版不等于完整迁移。当前总范围见 [Plan.md:13](../Plan.md#L13) 和 [Plan.md:112](../Plan.md#L112)。
- [ ] **Apple Dynamic Island / Live Activity 本期暂缓。** 包括 `Sources/LiveActivity/`、`Widget/GlyphLiveActivity.swift`、Island/Lock Screen 内容、Activity 调度、Island 专用设置和诊断。不得用普通悬浮窗或通知宣称已经替代。不得因此排除整个 `Widget/`、`Shared/` 或其他包含非岛功能的文件。
- [ ] **Apple 岛之外的功能不能顺带删除。** `DisplayChoicesCard` 的悬浮窗、`SummaryWidget`、`StartControl`、结果统计、CloudKit 远程变更和诊断均仍需实现；`remote-notification` 还服务于 CloudKit，不属于岛专属业务：[project.yml:71–83](../Apps/DynamicGlyph/project.yml#L71-L83)、[project.yml:99–105](../Apps/DynamicGlyph/project.yml#L99-L105)。
- [ ] **小米灵动岛 / 超级岛只按 P6 独立规划和验证，当前不计入 P1–P5 核心交付。** 当前 P6 已明确官方模板、审核、白名单、权限、降级和真机验收边界：[Plan.md:265–300](../Plan.md#L265-L300)。普通前台通知、悬浮窗或伪造系统外观均不能称为小米超级岛已实现。
- [ ] **平台差异采用功能等价，不以没有同名 API 为由删除业务。** 当前 Plan 已明确此约束：[Plan.md:251](../Plan.md#L251)。
- [ ] **文档中的未来设计不冒充现有功能。** 见第 12 节；当前源码没有的功能不能借“完整迁移”无依据扩张，也不能把旧文档的缺失说明用于回退现有功能。

### 本矩阵使用的证据规则

- 以 `Apps/DynamicGlyph/Sources`、`Broadcast`、`Widget`、`FrameTransport`、`Packages/GlyphRecognition` 的当前实现为主，Docs 用于解释行为和验证范围。
- [FIELD_TESTING.md:32–36](FIELD_TESTING.md#L32-L36) 与 [PROVENANCE.md:9–12](../Packages/GlyphRecognition/Tests/GlyphRecognitionTests/Fixtures/PROVENANCE.md#L9-L12) 的“无形状表不能开始采集”已过时，以 gear-only 当前代码为准。
- [RECOGNITION.md:126–130](../Packages/GlyphRecognition/RECOGNITION.md#L126-L130) 的“自动游戏阶段识别未实现”同样是旧说明；当前 `PresentationTracker`、header、slot 和 timer 证据链已经存在。
- 标注“已验证/未验证”时只描述 iOS 参考证据，不冒充 Android 验证。Android 需要重新执行相应测试和真机验收。

### Android 实施与验证快照（2026-11-08）

下表逐项区分四种证据：**代码**仅表示生产实现已落地；**JVM**表示本次实际执行的生产单元/集成测试；**协议**表示本地 HTTP、真实临时文件或格式协议测试；**AT 编译**仅表示 `:app:assembleDebugAndroidTest` 成功，不表示仪器用例已运行。本次 `:recognition:test --rerun-tasks :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug :app:assembleRelease :app:bundleRelease :app:lintRelease` 一次执行通过，recognition 为 451 项、0 失败、4 个明确语料 skipped，app 为 173 项、0 失败、0 skipped；Debug/Release APK、AAB 和 Debug/Release Lint 均通过。ADB 无设备，故所有真机项均未闭合；原有复选框继续表示完整验收，不能因下表出现“代码”或“AT 编译”就勾选。

| 项目 | Android 代码实现 | 已有自动验证 | 仍未验证 / 未完成 |
| --- | --- | --- | --- |
| P1-01 | 原生三页、Start/Stop/Stopping 服务闭环；首页使用真实快照/gear-only 计数并合并最近 hack/drop | JVM 状态/生命周期/HomeDisplayModel 测试；首页 AT 仅编译 | 真机完整开始/停止、取消、重复操作和首页视觉 |
| P1-02 | 版本化首 run、重跑、完整设置与支持入口 | JVM 设置/首 run/资源测试；AT 编译；Lint | 全新安装/升级真机流程；日/中翻译人工 review |
| P1-03 | fresh consent、会话代次、资源释放与旧回调隔离 | JVM OCR 代次/完成回流测试；AT 编译 | 授权取消、系统停止、进程死亡的真机行为 |
| P1-04 | RGBA/padding、source/arrival、idle/complete、连续性监控 | JVM FrameConverter/SourceMonitor 测试；AT 编译 | 真机旋转、静止、断帧恢复和 RGBA golden 语料（1 项 skipped） |
| P1-05 | `acquireLatestImage`、有界识别入口及独立命令小裁剪队列；原始 Plane 的 stride/sourceTime 裁剪先于全屏转换和 49 ms 节流 | JVM FrameConverter 保真裁剪、队列/连续性/单帧命令与节流测试 | 真机慢识别背压、60 Hz 短通知和短空白覆盖 |
| P1-06 | 主线程 WindowManager/Canvas、拖动、隐藏/恢复 | JVM 渲染/几何测试；AT 编译 | 首次安装仅验证过可拖且有卡顿；修复版流畅度和触摸仍待真机复测 |
| P2-01 | 两类 CSV 严格导入、原子保存、启动读取和状态报告 | JVM TableFileStore/parser 测试；AT 编译 | 系统选择器/重启真机流程；参考 CSV 未随包提供 |
| P2-02 | 形状/序列 parser、重复与前缀语义 | JVM parser/predictor 测试 | 完整 142-Glyph 表与真实序列语料 |
| P2-03 | 首次成功导入才启用辅助、之后尊重用户选择 | JVM first-import 规则测试；AT 编译 | 真机首次/二次导入及升级路径 |
| P2-04 | 节点、线证据、方向无关匹配与候选拒绝 | JVM 独立合成像素/几何测试 | 真实标注 Glyph/空阵列语料（与 P2-05 共用 skipped） |
| P2-05 | 11 环校准、8 秒缓存、尺寸/时间失效 | JVM 校准/缓存合成测试 | 真实空阵列、遮挡和屏幕变化语料 |
| P2-06 | header/slot/timer/保留期驱动阶段 | JVM scheduler/tracker/冲突测试 | 真机 OCR 延迟、遮挡和完整 hack |
| P2-07 | 彩色槽位、完整行、遮挡恢复 | JVM SlotStrip 合成测试 | 真实 banner/window/slot/countdown 语料（1 项 skipped） |
| P2-08 | 确认边界、重复/漏槽、推断与输入冻结 | JVM PresentationTracker/validator 测试 | 真实重复、漏槽和玩家输入流程 |
| P2-09 | confirmed prefix 预测、歧义和长度冲突 | JVM SequencePredictor/Outlook 测试 | 用户序列表与真实预测对照 |
| P2-10 | 统一快照、状态/时效和主线程渲染；无新帧按 deadline 用同一快照刷新 stale/核验淡出，不伪造源时间 | JVM snapshot/renderer/OverlayDisplayRefresh deadline 测试；AT 编译 | 真机各状态视觉、staleness 与输入高亮 |
| P2-11 | 单 OCR lane、优先级、sourceTime/session 失效和公开只读诊断 | JVM GameHeaderReader/HeaderOcrProcessor/真实完成回流测试 | 设备 ML Kit 慢读、五语言 OCR 和真实过期完成 |
| P2-12 | luma/active/resting/quiet、受限节奏和模式信号 | JVM AnalysisGate/PowerMeter 测试 | 真机热/低功耗切换、证据覆盖和功耗趋势 |
| P3-01 | command→presentation→input→completion 同一生产管线 | JVM live-business/replay 集成测试；AT 编译 | 真实游戏或等价真实采集的完整 hack；私有 replay 语料 skipped |
| P3-02 | 可调悬浮窗、首页/设置/Setup 共用生产 renderer 的预览、即时应用和自采集约束 | JVM 配置/几何/渲染测试；预览 AT 仅编译 | 极值尺寸、多个比例、游戏触摸与自采集真机检查 |
| P3-03 | Open Ingress 入口、恢复符合条件的已隐藏窗口及查找/启动异常可见状态 | JVM OpenIngressPolicy 测试；AT 编译 | 已安装、未安装、窗口关闭和重复点击真机路径 |
| P3-04 | 归一化布局；未加入品牌/机型参数表 | JVM 多尺寸合成测试 | 至少两种物理屏幕比例；无失败证据时不新增定位框架 |
| P4-01 | 无表/辅助 Off 的 gear-only 与独立 drop | JVM LiveBusinessPipeline/GlyphCaptureEngine 测试 | 六种设置组合的真实屏幕和掉落验收 |
| P4-02 | 抬手核验、稳定窗口、末槽排除 | JVM PlayerInputValidator 测试 | 真实笔划、Redo、遮挡和帧间断 |
| P4-03 | 核验显示时效与最终 attempt 对照 | JVM InputCheckPresentation/ResultComparison 测试 | 真机重绘、重复 Glyph 与 Redo 关联 |
| P4-04 | 两类别命令、接受/拒绝、窗口绑定 | JVM command reducer/reader 测试 | 真实通知 OCR、INVALID/unreadable 和跨 hack 路径 |
| P4-05 | 最多 16 个小裁剪、过期/dropped、drone footer | JVM 队列/footer/调度测试 | 60 Hz 真机短通知、无人机/普通 Portal 对照 |
| P4-06 | 结果页检测、bonus 和逐槽 verdict | JVM ResultScreenReader/Observation 合成测试 | 真实结果页、金/品红、遮挡及两 Glyph/错误末槽语料 |
| P4-07 | notObserved/partial、时长和结构化摘要 | JVM result session、迟到 OCR 完成回流测试 | 真机早退、停止、下一 hack 和结果时长 |
| P4-08 | Portal 等级、置信、冲突、阵营与时间关联 | JVM PortalLevel/Screen/关联测试 | 真实 Portal 画面、Neutral 不可确认边界和跨 hack 时序 |
| P4-09 | 玩家阵营设置；result/gear/drop 共用同一语言记忆与 CaptureOriginFactory，记录版本/build/mode/language/source/UTC offset/faction | JVM CaptureOriginFactory、设置/export 测试；AT 编译 | 历史真机快照、未设置/Machina/gear-only 导出对照 |
| P4-10 | 掉落 allowlist、数量、逐位置确认 | JVM parser/tracker/panel 合成测试 | 真实省略名称、错列、漏行和矛盾 OCR |
| P4-11 | 稀有度、红字 multiplied 与 ITO EN | JVM 像素/文本/tracker 测试 | 真实混色、邻近等级和两种 ITO EN 面板 |
| P4-12 | 掉落组闭合、FIFO 关联、独立 drop | JVM GearDropObservation/live-business 测试 | 真实普通 hack 穿插、queueLimit/frameGap/capture stop |
| P4-13 | 五种语言记忆及 Latin/中/日/韩 bundled ML Kit 选择 | JVM 词包/语言记忆/调度测试 | **Android 上 en、zh-Hans、zh-Hant、ja、ko 全部缺真实 OCR 验收**；词包和模型存在不算通过 |
| P4-14 | en/ja/zh-Hans/zh-Hant UI 资源；无 ko UI | Lint、JVM 设置/资源检查；AT 编译 | 四种 UI 人工文案/布局 review；五种游戏 OCR 逐项样本；日/中翻译仍待审 |
| P4-15 | 三表 Room、原始 JSON、内存历史、独立保留/删除；未闭合 hack 只进内存，isClosed 后才写 Room/outbox，早到 gear 等闭合父结果 | JVM JSON/历史策略/CaptureResultPersistence 闭合与同步顺序测试；真实 Room AT 仅编译 | AT 未执行；重启、迁移、损坏/未来版本和事务真机验证 |
| P4-16 | Drive 授权、appDataFolder、outbox/墓碑/cursor、scope 修复与远端刷新；应用级 owner 在 Activity 销毁后继续持有，保存/删除/远端合并均刷新并 requestSync | JVM 本地 HTTP 协议/scope/本地与远端 follow-up 测试；应用级 owner AT 仅编译 | **真实 Google 账号、双设备、新增/删除/离线/容量/权限全部未验证**；OAuth 未配置时不模拟成功，Activity 存活期的仪器行为未执行 |
| P4-17 | 全部统计范围、自由半开区间和统一过滤 | JVM StatisticsRange/Selection 测试；AT 编译 | 页面深链/真实数据库、午夜/DST/时区设备检查 |
| P4-18 | 统计口径、细分及 ResultsPage 驱动的原生 bonus/activity/items/长度/Portal/命令/Glyph/绘制时间图表，保留精确文本表 | JVM HackStatistics/ResultsPage 测试；全部图表实际绘制 AT 仅编译 | AT 未执行；真实数据覆盖、图表视觉和 partial/notRead/unavailable 对照 |
| P4-19 | 范围/单条/多选导出和删除 | JVM export/UI policy；AT 编译 | 系统分享/取消、失败回滚、云删除和迟到 OCR 真机路径 |
| P4-20 | Gear CSV v1 全列、时间语义、稳定匿名 ID | JVM 精确列/CRLF/空值/公式/ID/export 协议测试 | 真实历史/时区语料和设备文件分享 |
| P4-21 | Include hour、预览、checksum、摘要与系统分享 | JVM 同一快照/export/file-store 测试；AT 编译 | 长范围性能、系统分享取消和临时文件清理真机检查 |
| P5-01 | 实时快照/采集/窗口/观察/事件/功耗；Off 仅轻量计数；Stop 冻结 | JVM EvidenceWriter、LiveStore、真实计量与冻结测试；AT 编译 | 真机 On/Off 会话、结果/统计连续工作和最终 UI 刷新 |
| P5-02 | frames/events/recognition/tracking/snapshots/results/result-frames/input-validation/commands/gear/receipt/表与普通 JPEG 均已接生产；普通 JPEG 只计 saved，busy/failed/budgetExhausted/writerClosed 有明确状态；frames.csv 接真实方向/CPU/悬浮窗可见性 | JVM 生产 tracker/validator、JSONL/sourceTime、普通文件、PowerMeter 与停止测试；Android Bitmap、图片回执和预算用例 **AT 仅编译** | 图片编码/回执/预算的仪器用例未执行；长会话 drain、停止后文件可读性和 crash 恢复未验 |
| P5-03 | Gear 图片/遮罩及 result/command 专用图片流均已接生产；queued 与 saved 分开回执 | JVM gear/result/command 生产 pipeline、调度、图片引用与报告测试；真实图片 writer/尺寸/回执 **AT 仅编译** | 短暂最终阶段、supporting read、writer busy、预算耗尽、遮罩和隐私的真机/仪器验收未闭合 |
| P5-04 | 会话列表、备注、zip、分享/删除；仅当前 activeFolder 受活跃保护，其他历史报告在全局 RUNNING 时仍可用 | JVM SessionReportStore/Policy/zip 测试；AT 编译 | 真机活跃状态、含视频、分享取消/失败和删除失败 |
| P5-05 | gear 专项报告、结果卡入口、可选完整未遮图 | JVM GearReport/日志过滤/真实临时文件测试；AT 编译 | 无图/他机记录、隐私选择、分享取消/完成真机检查 |
| P5-06 | 错误报告录制、独立提前停止、限制和有界背压 | JVM 录制政策/生命周期测试；编码 AT 编译 | 真机 MediaCodec、尺寸变化、磁盘/背压和识别连续性 |
| P5-07 | 相册录制、仅播放声、MediaStore/Files 回退 | JVM 状态/政策测试；AT 编译 | 目标拒绝音频、无声、Photos 拒绝、并行录制和真实保存 |
| P5-08 | receipt、分段 MP4、wall-time 与预警 | JVM receipt/warning/限制测试；AT 编译 | 真机静态画面 wall-time、通知拒绝、最终化和旧回调 |
| P5-09 | 实时 PowerMeter、Release 强制节能、模式色、录像 benchmark；公开 OCR 诊断替代反射 | JVM meter/report/runner/真实 OCR 计时/隔离测试；benchmark AT 编译 | 真机录像 Off/On、accuracy/real pace、CPU/热/功耗趋势；benchmark 无正式显示故显示延迟 unavailable |
| P5-10 | Summary widget、三期间、共享切换和深链 | JVM summary/period/rollover 测试；RemoteViews AT 编译 | 真机各尺寸、多实例、空数据、跨午夜/时区和深链 |
| P5-11 | 保存发布、字节去重、节流及 Stop/前台补发 | JVM publisher/refresh 测试；AT 编译 | 真机连续保存/删除/远刷/停止与前台最终刷新 |
| P5-12 | QS 磁贴和 Start/Stop/Statistics 快捷动作 | JVM intent/心跳/停止状态测试；AT 编译 | 真机授权、无表 gear-only、进程死亡、停止超时和三期间 |
| P5-13 | 生产 recognize/replay CLI、完整 reducer 和消融；共享 lane 下 results=off 不做结果 OCR；full 缺 --ocr-trace fail-fast，仅显式 no-OCR/消融允许 unavailable | JVM replay/CLI 退出码/results-off/消融测试 | 私有 glyph 表、masked frames、OCR trace 和标注 replay（1 项 skipped）；缺输入不记为通过 |
| P5-14 | Release APK/AAB、实时计时/内存/功耗测点已落地 | Debug/Release Lint、JVM、AT 编译；APK/AAB 字节和 OCR 内容已测 | 启动、真实内存、识别/OCR p50/p95、显示延迟、15–20 分钟、功耗趋势、两种比例和录屏开销均**未验证** |

Release 本地测量：unsigned universal APK 58,480,148 B（55.771 MiB），AAB 25,004,477 B（23.846 MiB）；哈希和 ML Kit 内容见 [ANDROID.md](ANDROID.md)。这不是启动、安装后大小或性能验收。四个 recognition skipped 分别是真实空阵列/Glyph 标注、banner/window/slot/countdown、opaque RGBA golden、私有 replay 输入；它们均保持未验证。

### 2026-11-08 真实两类录制补充

同一设备实际开启错误报告视频和相册视频后，`events.log` 分别记录两条 started，`recordings.jsonl` 的 bugReport/photos 均为 `finished`。116 帧写入、所有丢弃计数为 0、未使用音频；生成 9 个 1080×1920 H.264/MP4 分段，时长 48.442856 秒，相册存在对应文件且末段 SHA-256 与错误报告一致。证据在 `app/build/outputs/device-smoke/recording-session/`。设备未安装 Ingress，真实播放音频和完整游戏录制仍未验证。

### 2026-11-08 真实前台采集长会话补充

在用户提供的 Android 15 / API 35 设备 `127.0.0.1:7555` 上，真实前台 MediaProjection 主页采集持续 **986.777 秒**，2948 帧全部 `complete`，停止后服务释放。31 个独立 PSS 样本覆盖 904 秒，首/末为 167,367/147,117 KB，范围 146,038–185,417 KB，未观察到单调增长；进程 CPU 时间增量对应 0.468 核/墙钟。证据位于 `app/build/outputs/device-smoke/long-session-samples.csv` 和 `long-session/`。这是主页采集资源数据，不是 Ingress 游戏识别、完整双录制或电池功耗验收；对应长会话验收项仍保留为未完全验证。

### 2026-11-08 模拟器仪器验收补充

已在 Android 16 / API 36 的无窗口 `GlyphLensTest` AVD 上执行完整 `:app:connectedDebugAndroidTest`：**81 项测试，0 失败、0 错误、0 跳过**。结果文件为 `app/build/outputs/androidTest-results/connected/debug/TEST-GlyphLensTest(AVD) - 16.xml`。
随后在用户提供的 Android 15 / API 35 设备 `127.0.0.1:7555`（NCO-AL00）上完成全量 **84 项测试，0 失败、0 错误、0 跳过**，包含 RGB_565 解码和 32.88 秒五 Glyph 真实回放；结果文件为 `app/build/outputs/androidTest-results/connected/debug/TEST-NCO-AL00 - 15.xml`，独立副本为 `app/build/outputs/androidTest-results/task-2-regression-gear-84.xml`。这些覆盖真实 Android 运行时的 Room、Bitmap、MediaCodec、MediaStore、FileProvider、RemoteViews、图表和系统入口，不等同于物理真机验收；MediaProjection 游戏流程、真实语言 OCR、Google 账号、物理显示/音频策略及 15–20 分钟持续运行仍按本矩阵保持未验证。

## 2. P1：原生应用与采集显示闭环

- [ ] **P1-01 三页应用、首页状态和 Start / Cancel / Stop / Stopping 闭环。**
  - 源码：[ContentView.swift:33–103](../Apps/DynamicGlyph/Sources/Home/ContentView.swift#L33-L103)、[ContentView.swift:215–316](../Apps/DynamicGlyph/Sources/Home/ContentView.swift#L215-L316)。
  - 必须保留 Home / Statistics / Settings，区分 idle、preparing、capturing、stopping；等待共享时允许 Cancel，运行时可 Stop，停止中不可重复启动。首页按 Glyph/gear-only 切换序列卡或 gear 计数，展示最近结果、录制警告和错误。
  - 验收：开始 → 授权 → 进入游戏 → 显示 → 停止完整走通；拒绝、取消、系统停止、连续重复启动和停止均无旧显示或旧会话残留。

- [ ] **P1-02 首 run 引导、版本化重跑、设置与支持入口。**
  - 源码：[SetupView.swift:5–12](../Apps/DynamicGlyph/Sources/Setup/SetupView.swift#L5-L12)、[SetupView.swift:125–156](../Apps/DynamicGlyph/Sources/Setup/SetupView.swift#L125-L156)、[SetupView.swift:346–429](../Apps/DynamicGlyph/Sources/Setup/SetupView.swift#L346-L429)、[SettingsView.swift:29–55](../Apps/DynamicGlyph/Sources/Settings/SettingsView.swift#L29-L55)。
  - 必须保留账号风险、显示选择、窗口比例/清晰度、结果读取选择和完成摘要；完成版本只在最后一页记录，中途退出下次重进，支持 Run setup again。**当前引导不要求先导入 Glyph 表**：[SetupFlow.swift:62–64](../Apps/DynamicGlyph/Sources/Setup/SetupFlow.swift#L62-L64)。
  - 设置还包括版本、邮件/Telegram、复制地址及报告指引：[SettingsView.swift:327–358](../Apps/DynamicGlyph/Sources/Settings/SettingsView.swift#L327-L358)、[SupportContact.swift:14–32](../Apps/DynamicGlyph/Sources/Settings/SupportContact.swift#L14-L32)。
  - 验收：全新安装、未完成退出、版本升级、重跑、无表和已有表分别进入正确文案；Apple 岛页面按排除边界移除后，仍保留其余引导和可用显示路径。

- [ ] **P1-03 每次会话重新授权、会话身份、资源释放和迟到回调隔离。**
  - 源码：[CaptureEngine.swift:444–570](../Apps/DynamicGlyph/Sources/Capture/CaptureEngine.swift#L444-L570)、[CaptureEngine.swift:615–652](../Apps/DynamicGlyph/Sources/Capture/CaptureEngine.swift#L615-L652)、[CaptureRequest.swift:1–30](../Apps/DynamicGlyph/Sources/Capture/CaptureRequest.swift#L1-L30)。
  - 必须保留 fresh consent、准备/运行/停止状态、唯一 capture session、旧请求失效；停止还要关闭识别输出、结果/掉落观察、图片 writer 和两种 recorder。
  - 验收：授权取消后可重试；系统停止可重新开始；旧 OCR、旧帧、旧 recorder 回调不会进入下一会话；进程结束后不自动恢复采集。

- [ ] **P1-04 全彩帧、源时间、方向、idle/complete 与连续性中断语义。**
  - 源码：[CapturedFrame.swift:5–47](../Apps/DynamicGlyph/Sources/Capture/CapturedFrame.swift#L5-L47)、[LiveGlyphOutput.swift:474–540](../Apps/DynamicGlyph/Sources/Capture/LiveGlyphOutput.swift#L474-L540)。
  - `idle` 表示无新图像，不等于流中断；`complete`、blank、suspended、stopped 等不得折叠成一个布尔值。竖屏和布局不一致时 unknown，不拿错误区域继续识别。
  - Android 不照搬 App Group/mmap，但必须保留全彩、source timestamp、arrival uptime、方向和 continuity break；槽位、结果、Portal、掉落都依赖颜色：[FRAME_TRANSPORT.md:11–19](FRAME_TRANSPORT.md#L11-L19)。
  - 验收：RGBA/padding/pixelStride、旋转/尺寸变化、静止画面、时间倒退、断帧和恢复均有独立测试；停止后 Image、VirtualDisplay、Service、reader 均释放。

- [ ] **P1-05 ReplayKit/FrameTransport 只迁移行为，不另造 Android 跨进程架构。**
  - 源码：[ReplayKitBackend.swift:155–195](../Apps/DynamicGlyph/Sources/Capture/ReplayKitBackend.swift#L155-L195)、[FRAME_TRANSPORT.md:95–118](FRAME_TRANSPORT.md#L95-L118)、[BroadcastContinuity.swift:3–58](../Apps/DynamicGlyph/Sources/Capture/BroadcastContinuity.swift#L3-L58)。
  - 源实现用短 ring 降低复制线程延迟，避免吞掉约 100 ms Glyph 空白；最新待处理帧只用于识别入口，丢失/撕裂要形成 continuity break。暂停、心跳过期、会话变化与静止画面必须区分。
  - 验收：慢识别时不积压全屏旧帧，同时不会把短空白、短暂通知误当无证据；队列丢失有计数和未知状态。

- [ ] **P1-06 悬浮窗显示、拖动、隐藏/恢复和停止。**
  - 源码：[GlyphPiPController.swift:20–23](../Apps/DynamicGlyph/Sources/FloatingWindow/GlyphPiPController.swift#L20-L23)、[GlyphPiPController.swift:245–300](../Apps/DynamicGlyph/Sources/FloatingWindow/GlyphPiPController.swift#L245-L300)。
  - 关闭窗口只隐藏显示，采集继续；回到应用后再离开会重新 armed，Stop 才停止整个会话。Android 用真实 WindowManager 行为，不照搬 iOS PiP 私有 API。
  - 验收：拖动、隐藏、首页恢复、切游戏持续更新、系统停止、进程结束、反复显示/隐藏均正确；小窗口不遮挡关键操作区，不承诺不可触摸等于必然触摸穿透。

## 3. P2：识别核心、表导入、预测与调度

- [ ] **P2-01 形状表和可选序列表的文件导入、启动自动读取与状态报告。**
  - 源码：[CaptureEngine.swift:305–415](../Apps/DynamicGlyph/Sources/Capture/CaptureEngine.swift#L305-L415)。
  - 两表可从应用目录自动读取，也可文件选择导入并原子保存；运行/准备/停止中禁止替换。序列表状态分别显示 distinct 数、原始行数、重复合并、缺失形状名和未检查状态。
  - 验收：有效、空文件、非 UTF-8、非法 CSV、替换、重启自动读取、运行中导入均被正确处理；无表不阻断 gear-only。

- [ ] **P2-02 严格表解析与重复语义。**
  - 源码：[GlyphTable.swift:44–81](../Packages/GlyphRecognition/Sources/GlyphRecognition/GlyphTable.swift#L44-L81)、[SequenceTable.swift:67–121](../Packages/GlyphRecognition/Sources/GlyphRecognition/SequenceTable.swift#L67-L121)。
  - 形状表校验列数、空名、非法路径和重名，仅首行允许 header；序列表只去尾部空列，中间空洞报错，最长 5，重复序列去重但保留 rowCount/weight。
  - 验收：逐类错误有生产 parser 测试；重复序列不会把唯一候选误报成歧义，完整序列同时是更长序列前缀的情况仍保留两种可能。

- [ ] **P2-03 首次形状表启用辅助及用户选择持久化。**
  - 源码：[GlyphAssistance.swift:21–49](../Apps/DynamicGlyph/Sources/App/GlyphAssistance.swift#L21-L49)、[CaptureEngine.swift:345–370](../Apps/DynamicGlyph/Sources/Capture/CaptureEngine.swift#L345-L370)。
  - 第一次成功加载才自动开启；已有表迁移不覆盖旧选择，之后替换表不重新打开用户已关闭的辅助。无表不显示开关，读取失败不能当作可用表。
  - 验收：首次导入、二次替换、重启、已有表升级、损坏表和关闭辅助后的行为分别通过。

- [ ] **P2-04 节点定位、线证据、方向无关匹配和候选拒绝。**
  - 源码：[GlyphFrameRecognizer.swift:72–155](../Packages/GlyphRecognition/Sources/GlyphRecognition/GlyphFrameRecognizer.swift#L72-L155)、[GlyphTable.swift:88–109](../Packages/GlyphRecognition/Sources/GlyphRecognition/GlyphTable.swift#L88-L109)、[GlyphPath.swift:36–54](../Packages/GlyphRecognition/Sources/GlyphRecognition/GlyphPath.swift#L36-L54)。
  - 保留 24 空间 bin、中心/背景对比、80% coverage、最多 2 条边差异、方向无关 edge set、extensions 和同形候选；空证据不猜最近邻，unexplainedInk 不接受 exact match，距离不是概率。
  - 验收：正反方向、镜像空间差异、重复线段、断线、亮度、杂墨、空阵列、同形候选和未见过的合法输入都直接测试生产识别器。

- [ ] **P2-05 空阵列校准、缓存、失效和布局一致性。**
  - 源码：[GlyphFrameSession.swift:28–54](../Packages/GlyphRecognition/Sources/GlyphRecognition/GlyphFrameSession.swift#L28-L54)、[GameUILayout.swift:74–84](../Packages/GlyphRecognition/Sources/GlyphRecognition/GameUILayout.swift#L74-L84)。
  - 仅 11 个清晰节点环可取得校准；缓存最多 8 秒，定时尝试重新拟合；尺寸变化、时间倒退、间隔过大和方向不支持重置。缓存 fit 不是“当前仍在 Glyph 页面”的证明。
  - 验收：绘制遮挡后不盲目重新校准，空阵列可刷新，尺寸/布局改变不复用错误 fit，calibrationUnavailable 不被当成离开游戏。

- [ ] **P2-06 阶段证据：header、slot transition、倒计时和保留期。**
  - 源码：[GamePhaseEvidence.swift:15–57](../Packages/GlyphRecognition/Sources/GlyphRecognition/GamePhaseEvidence.swift#L15-L57)、[PresentationTracker.swift:277–383](../Packages/GlyphRecognition/Sources/GlyphRecognition/PresentationTracker.swift#L277-L383)。
  - 当前帧倒计时和有效校准优先于延迟 OCR 的旧 header；只有已观察 hack 才能由 countdown 进入 input，不能用无关屏幕上的 bar 开始 hack。未知阶段是一等状态。
  - 验收：header 延迟/冲突/过期、header 被遮、指标过渡、倒计时晚出现和重复 Glyph 均不会把命令或玩家笔划收入展示序列。

- [ ] **P2-07 彩色槽位、完整行和遮挡恢复。**
  - 源码：[SlotStripReader.swift:49–71](../Packages/GlyphRecognition/Sources/GlyphRecognition/SlotStripReader.swift#L49-L71)、[SlotStripReader.swift:150–214](../Packages/GlyphRecognition/Sources/GlyphRecognition/SlotStripReader.swift#L150-L214)。
  - 必须检查数量、完整居中位置、pitch、宽度、高度、唯一高亮、完整倒计时轨道和端点；被遮一部分的标记不能缩成更短的“可信序列”。通知横幅和薄窗下缘有各自的恢复规则。
  - 验收：遮挡左侧/对称两侧/顶部/整条、通知横幅、悬浮窗边线、多个高亮、槽数变化均返回正确 reading 或 rejection，不伪造槽数。

- [ ] **P2-08 槽位确认、重复/漏槽、推断槽位和序列冻结。**
  - 源码：[PresentationTracker.swift:15–60](../Packages/GlyphRecognition/Sources/GlyphRecognition/PresentationTracker.swift#L15-L60)、[PresentationTracker.swift:518–717](../Packages/GlyphRecognition/Sources/GlyphRecognition/PresentationTracker.swift#L518-L717)、[PresentationTracker.swift:736–786](../Packages/GlyphRecognition/Sources/GlyphRecognition/PresentationTracker.swift#L736-L786)。
  - 看到形状不等于确认；使用指示前进、最后指示释放或输入开始等边界。重复 Glyph 不去重，跳槽/中途加入保留 missing，后续不前移。嵌套稳定形状取最大，非嵌套稳定形状留 uncertain；槽数两票，冲突停止指导。
  - 指标不可读时的 inferred 只作虚线展示，不进 confirmedPrefix、预测或下一步指导；指标恢复后重新锚定，未验证位置变缺口。
  - 验收：重复、漏读、嵌套、非嵌套、跨边界、帧间断、槽数冲突、遮挡恢复、最后一槽释放均通过状态机回归。

- [ ] **P2-09 序列预测、替代候选和长度冲突。**
  - 源码：[SequenceOutlook.swift:63–117](../Packages/GlyphRecognition/Sources/GlyphRecognition/SequenceOutlook.swift#L63-L117)、[SequencePredictor.swift:116–153](../Packages/GlyphRecognition/Sources/GlyphRecognition/SequencePredictor.swift#L116-L153)。
  - 只用连续 confirmed prefix，并结合独立 slot count；区分 unavailable、unobserved、ambiguous、resolved、contradicted、lengthConflict。候选等权只用于稳定展示，不是概率或排序推荐。
  - 验收：缺序列表只失去预测；缺槽不压缩 prefix；四 Glyph 既是完整序列又是五 Glyph 前缀时由独立槽数决定；预测与观察视觉可辨。

- [ ] **P2-10 统一显示快照、当前/下一指导和时效。**
  - 源码：[GlyphDisplaySnapshot.swift:90–176](../Packages/GlyphRecognition/Sources/GlyphRecognition/GlyphDisplaySnapshot.swift#L90-L176)、[GlyphDisplaySnapshot.swift:214–289](../Packages/GlyphRecognition/Sources/GlyphRecognition/GlyphDisplaySnapshot.swift#L214-L289)、[SlotPresentation.swift:150–194](../Packages/GlyphRecognition/Sources/GlyphRecognition/SlotPresentation.swift#L150-L194)。
  - 所有表面共用同一状态语义，观察/预测/推断用线型和说明区分。输入指导在指示超过 0.75 秒、遮挡、槽数冲突、含 inferred、非 input 或停止时撤回；gear 信息不得覆盖 command/presenting/input。
  - 验收：状态色和文字一致；旧帧不会被定时刷新伪装成新指导；输入前进即使无 tracker event 也触发重绘。

- [ ] **P2-11 共享 OCR lane、源时间、优先级与过期处理。**
  - 源码：[GameTextSchedule.swift:142–222](../Packages/GlyphRecognition/Sources/GlyphRecognition/GameTextSchedule.swift#L142-L222)、[GameHeaderReader.swift:6–23](../Apps/DynamicGlyph/Sources/Capture/GameHeaderReader.swift#L6-L23)、[GameHeaderReader.swift:208–250](../Apps/DynamicGlyph/Sources/Capture/GameHeaderReader.swift#L208-L250)。
  - header/result/gear/portal/command 共用一个在途请求，结果保留 source time 和 hack identity；result、gear、portal、command 各有优先级，不能并行另起 OCR 通道。新 gear 面板可取消旧读取，但未确认旧面板受保护，新面板首读不等待旧间隔。
  - 验收：慢 OCR、过期完成、错误会话、panel 替换、候选连续失败和读取取消都不会串 hack 或饿死 header；live cadence 与 exhaustive replay 分开验证。

- [ ] **P2-12 功耗门控和活跃/静止/受限节奏。**
  - 源码：[AnalysisGate.swift:174–205](../Packages/GlyphRecognition/Sources/GlyphRecognition/AnalysisGate.swift#L174-L205)、[AnalysisGate.swift:242–343](../Packages/GlyphRecognition/Sources/GlyphRecognition/AnalysisGate.swift#L242-L343)、[PowerConditions.swift:5–32](../Apps/DynamicGlyph/Sources/Capture/PowerConditions.swift#L5-L32)。
  - 活跃约 20 Hz，resting/quiet 降频，屏幕切换有唤醒 burst；低功耗或严重发热时在 hack 外减半，hack 内保持必要采样。gear 面板短暂出现时不能因节能只读一次。
  - 验收：地图静止、游戏等待、进入 hack、命令短帧、gear 面板、低功耗/发热切换均保持正确证据覆盖；不是统一大降频换取表面低功耗。

## 4. P3：真实游戏可用性与交互补齐

- [ ] **P3-01 真实 command → presentation → input → completion 全流程。**
  - 源码：同 P2-06～P2-10，集成位于 [LiveGlyphOutput.swift:702–867](../Apps/DynamicGlyph/Sources/Capture/LiveGlyphOutput.swift#L702-L867)。
  - 验收：至少用真实游戏或等价真实采集验证完整 hack；展示期逐步收集，输入期序列冻结且玩家笔划不覆盖指导；观察、预测、推断和 unknown 可区分。不能以首页或 demo 代替。

- [ ] **P3-02 悬浮窗比例、清晰度、预览和自采集约束。**
  - 源码：[PiPCanvasControls.swift:16–44](../Apps/DynamicGlyph/Sources/Settings/PiPCanvasControls.swift#L16-L44)、[PiPCanvasConfiguration.swift:3–23](../Packages/GlyphRecognition/Sources/GlyphRecognition/PiPCanvasConfiguration.swift#L3-L23)、[GlyphPiPRenderLoop.swift:129–190](../Apps/DynamicGlyph/Sources/FloatingWindow/GlyphPiPRenderLoop.swift#L129-L190)。
  - 默认 640×64、2×；宽 256–640、高 64–192、清晰度 1–3×，Apply 即时生效且不重启采集或擅自重开已关闭窗口，Reset 恢复标准。渲染 key 包含输入高亮、核验、gear、时效、窗口宽度和调试底色。
  - 自采集防御只绘制小实心节点，不画可触发阵列检测的空心环；仍需提示避开 header、slot、array、结果和掉落区域：[FIELD_TESTING.md:281–298](FIELD_TESTING.md#L281-L298)。
  - 验收：极值尺寸、运行中修改、窗口已关闭时修改、重启持久化、多个比例、不同屏幕尺寸和游戏触摸均通过；Android 不照搬私有 `controlsStyle`。

- [ ] **P3-03 Open Ingress 与失败回退。**
  - 源码：[CaptureEngine.swift:655–674](../Apps/DynamicGlyph/Sources/Capture/CaptureEngine.swift#L655-L674)、[IngressLauncher.swift:19–22](../Apps/DynamicGlyph/Sources/App/IngressLauncher.swift#L19-L22)。
  - 采集运行后才显示入口；需要时先确保悬浮窗已打开，再启动游戏。源实现无游戏时回退商店，Android 应记录实际包未安装和启动失败的可见状态。
  - 验收：已安装、未安装、窗口先前被关闭、启动失败和重复点击均有确定行为，不重复创建采集会话。

- [ ] **P3-04 通用布局修正只由真实失败驱动。**
  - 约束：[Plan.md:15–21](../Plan.md#L15-L21)、[Plan.md:127–137](../Plan.md#L127-L137)。
  - 首版沿用归一化比例，不能按品牌、型号或分辨率建立参数表；实际黑边/系统栏/比例失败后用通用内容定位修正，并回归既有成功样本。
  - 验收：至少两种屏幕比例记录实际结果，缺设备明确未验证；没有失败证据时不新增复杂定位框架。

## 5. P4：业务功能、语言、历史、统计、同步与导出

- [ ] **P4-01 gear-only：无形状表或关闭辅助仍完整读取掉落。**
  - 源码：[CaptureEngine.swift:72–88](../Apps/DynamicGlyph/Sources/Capture/CaptureEngine.swift#L72-L88)、[LiveGlyphOutput.swift:250–302](../Apps/DynamicGlyph/Sources/Capture/LiveGlyphOutput.swift#L250-L302)、[LiveGlyphOutput.swift:729–738](../Apps/DynamicGlyph/Sources/Capture/LiveGlyphOutput.swift#L729-L738)。
  - 无表或 Off：table=nil，**不读 header、槽位、命令、Glyph 结果或预测，但始终读 gear**；Start 和 Shortcuts 不得被无表阻断。辅助 On 时 gear 还需要 Read results + Read gear。
  - gear-only 组是独立 drop，不伪造 hack、Portal 或 bonus 关联；首页、悬浮窗和统计累计真实读取物品：[GearDropDisplay.swift:84–126](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropDisplay.swift#L84-L126)。
  - 验收：无表、只有序列表、表损坏、辅助 Off、辅助 On 但 Read gear Off、日志 Off 六种组合均符合上述分支，且不会静默把 gear-only 当 Glyph 辅助。

- [ ] **P4-02 抬手后输入核验，末槽和未确认期望不核验。**
  - 源码：[PlayerInputValidator.swift:67–93](../Packages/GlyphRecognition/Sources/GlyphRecognition/PlayerInputValidator.swift#L67-L93)、[PlayerInputValidator.swift:208–280](../Packages/GlyphRecognition/Sources/GlyphRecognition/PlayerInputValidator.swift#L208-L280)、[PlayerInputValidator.swift:349–403](../Packages/GlyphRecognition/Sources/GlyphRecognition/PlayerInputValidator.swift#L349-L403)。
  - 仅 confirmed 期望可核验，**最后一槽不核验**。match 可在稳定 3 样本/100 ms 时先显示；differs 需 5 样本/200 ms 且窗口关闭；30%–80% partial edge 只能 unavailable。两次一致高亮变化确认 lift，后退/跳槽按规则失效。
  - 验收：正确、错误、弯曲笔划、短暂杂边、快速下一笔、无 clear、Redo、后退、多槽跳转、遮挡、帧间断和末槽均无越权 verdict；玩家笔划不改期望。

- [ ] **P4-03 输入核验显示时效与最终 attempt 对照。**
  - 源码：[InputCheckPresentation.swift:39–57](../Packages/GlyphRecognition/Sources/GlyphRecognition/InputCheckPresentation.swift#L39-L57)、[ValidatorResultComparison.swift:73–95](../Packages/GlyphRecognition/Sources/GlyphRecognition/ValidatorResultComparison.swift#L73-L95)。
  - ✓ 标记约 1.5 秒淡出，历史 verdict 仍附在槽位和 attempt。游戏结果只关联可证明的最终 attempt；Redo 后不使用旧 attempt。未知游戏结果、无法核验和无法关联都不是 disagreement。
  - 验收：核验变化触发所有保留显示表面重绘；重复 Glyph 和 Redo 不串 attempt；last slot 不伪造 validator、drawSeconds 或对照。

- [ ] **P4-04 命令通知的两类别、接受/拒绝和窗口绑定。**
  - 源码：[HackCommandSummary.swift:1–57](../Packages/GlyphRecognition/Sources/GlyphRecognition/HackCommandSummary.swift#L1-L57)、[HackCommandSummary.swift:97–193](../Packages/GlyphRecognition/Sources/GlyphRecognition/HackCommandSummary.swift#L97-L193)。
  - SIMPLE/COMPLEX 与 REQUEST/NO/ONLY KEY 独立，最后 accepted 各类别生效；INVALID 不覆盖旧值。清晰单帧可成立，否则两次一致；unreadable replacement 令旧值 unresolved，不能写 no command。只绑定紧随其后的 hack。
  - 命令笔画不进展示槽位，命令是否接受来自游戏通知而不是识别的形状：[COMMAND_RECOGNITION.md:23–48](COMMAND_RECOGNITION.md#L23-L48)。
  - 验收：类别替换、直接 notice→notice、单帧最终替换、INVALID、 unreadable、遮挡、断帧、取消后下一 hack 均不串证据。

- [ ] **P4-05 命令短证据队列与 drone 语义。**
  - 源码：[CommandNoticeReader.swift:105–177](../Packages/GlyphRecognition/Sources/GlyphRecognitionImages/CommandNoticeReader.swift#L105-L177)、[CommandNoticeReader.swift:209–267](../Packages/GlyphRecognition/Sources/GlyphRecognitionImages/CommandNoticeReader.swift#L209-L267)。
  - 最终替换通知可能只出现一帧；最多 16 个独立小裁剪、约 1 秒过期并记录 dropped。不能把“全屏只留最新帧”用于这些证据。队列溢出、过期、遮挡和断帧都形成覆盖未知。
  - drone 的 key command 为 notApplicable；footer 像素只是候选，需文本确认或复用已确认签名：[DroneFooterProbe.swift:5–10](../Packages/GlyphRecognition/Sources/GlyphRecognitionImages/DroneFooterProbe.swift#L5-L10)。普通 portal 不能仅因“不是 drone”就标成已确认 portal。
  - 验收：60 Hz 输入但 20 Hz 分析时仍能保住最终通知；无人机、普通、未知和错过 footer 各有正确模式。

- [ ] **P4-06 结果页检测、两项 bonus 和逐槽游戏 verdict。**
  - 源码：[ResultScreenReader.swift:12–36](../Packages/GlyphRecognition/Sources/GlyphRecognitionImages/ResultScreenReader.swift#L12-L36)、[ResultTextParser.swift:20–45](../Packages/GlyphRecognition/Sources/GlyphRecognition/ResultTextParser.swift#L20-L45)、[HackResultTracker.swift:52–160](../Packages/GlyphRecognition/Sources/GlyphRecognition/HackResultTracker.swift#L52-L160)。
  - 状态为 awaiting/partial/complete/notObserved。两项 bonus 分别要求最后阶段两次一致且跨 100 ms；金色/品红轮廓经重复证据映射 accepted/rejected。未读到不是 0 或 rejected，Hacking Bonus 可超过 100%。
  - 结果识别不依赖 tracker 已进入 completed，也不依赖阵列/header 成功；短 stage 名只作位置佐证，重复 Glyph 不能靠名称索引。
  - 验收：最终值、旧中间值、0%、超过 100%、冲突 OCR、遮挡、缺失最后阶段、金色/品红和 unread 均保留正确未知/finality。

- [ ] **P4-07 无结果结束、结果时长和结构化摘要。**
  - 源码：[ResultObservationSession.swift:16–58](../Packages/GlyphRecognition/Sources/GlyphRecognition/ResultObservationSession.swift#L16-L58)、[HackResultSummary.swift:18–52](../Packages/GlyphRecognition/Sources/GlyphRecognition/HackResultSummary.swift#L18-L52)。
  - 提前 Done、resultTimeout、新 hack、capture stop 均可留下 notObserved 或 partial；未观察到退出证据不能仅因遮挡而关闭。保存 final/last-seen bonus、逐槽 outcome/check/comparison、drawSeconds、inputSeconds、commands 和 CaptureOrigin。
  - drawSeconds 是最终被判定 attempt 的 first ink→lift；inputSeconds 只在结果实际出现且有合理输入边界时产生：[ResultFrameEvidence.swift:80–92](../Packages/GlyphRecognition/Sources/GlyphRecognition/ResultFrameEvidence.swift#L80-L92)。
  - Read results 关闭时不得运行结果 OCR：[AppPreferences.swift:60–64](../Apps/DynamicGlyph/Sources/App/AppPreferences.swift#L60-L64)。
  - 验收：无结果、部分结果、早退、停止、下一个 hack 和迟到 OCR 均不串记录。

- [ ] **P4-08 Portal 等级、置信度、冲突和阵营。**
  - 源码：[PortalLevel.swift:57–152](../Packages/GlyphRecognition/Sources/GlyphRecognition/PortalLevel.swift#L57-L152)、[PortalLevel.swift:164–218](../Packages/GlyphRecognition/Sources/GlyphRecognition/PortalLevel.swift#L164-L218)。
  - 综合 resonator、badge 色相和 Glyph 数，保留范围、置信度、冲突及所有来源。命令未确定时 Glyph 数不得缩窄等级；warm L1–L4 在这种情况下也不缩窄。 Neutral 无条不能检测，不能伪造 neutral faction。
  - Portal 画面只按时间关联到 10 秒内下一 Glyph hack，不是身份识别；不同 faction 可替换旧画面，迟到 OCR 不得复用到下一 hack。
  - 验收：完整/部分 resonator、L1 黄色纠正、badge 校准/未校准、命令未知、冲突和时序关联均通过。

- [ ] **P4-09 玩家阵营快照与 Portal 对齐导出。**
  - 源码：[SettingsView.swift:123–135](../Apps/DynamicGlyph/Sources/Settings/SettingsView.swift#L123-L135)、[CaptureOrigin.swift:26–46](../Packages/GlyphRecognition/Sources/GlyphRecognition/CaptureOrigin.swift#L26-L46)、[GearExport.swift:361–369](../Apps/DynamicGlyph/Sources/Results/GearExport.swift#L361-L369)。
  - Resistance/Enlightened/未设置可选；记录生成时快照，修改不回写历史。Machina 不需要玩家阵营即可对齐，Neutral 尚不能识别。
  - 验收：历史记录保留原阵营，未设置和 Machina 不错填；gear-only 无 Portal 关联时字段为空。

- [ ] **P4-10 掉落面板、allowlist 物品、数量与逐位置确认。**
  - 源码：[GearDropTextParser.swift:7–75](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropTextParser.swift#L7-L75)、[GearDropTextParser.swift:109–143](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropTextParser.swift#L109-L143)、[GearDropTracker.swift:1–34](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropTracker.swift#L1-L34)。
  - 只接受面板中明确数量的 allowlist 物品，不能从背包、奖励页或模糊名称猜物品。每 row/column 独立两次一致、跨 100 ms；名称/等级/稀有度可逐步细化，矛盾则 unknown。物理位置在效果行漏读时仍稳定。
  - 验收：未知名称、截断/省略名称、错列、缺数量、跨列文本、行遗漏和矛盾读取均不伪造物品或合并不同位置。

- [ ] **P4-11 稀有度、红字 multiplied 与 ITO EN 效果。**
  - 源码：[GearDropFrameEvidence.swift:14–74](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropFrameEvidence.swift#L14-L74)、[GearQuantityColorReader.swift:1–33](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearQuantityColorReader.swift#L1-L33)、[GearRarityReader.swift:1–42](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearRarityReader.swift#L1-L42)。
  - 固定稀有度和像素 meter 必须区分来源；meter unread 不使整面板 partial，也不擦掉已确认物品/数量。红字只读数量 OCR 框，颜色独立投票，未知不等于 false。
  - ITO EN 效果是独立元数据，不计入物品，± 号不可丢，两个 Portal/Bonus 面板独立确认：[GearDropTracker.swift:273–326](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropTracker.swift#L273-L326)。
  - 验收：Common/Rare/Very Rare/fixed/unreadable、遮挡、混色、L4/L5 邻近等级颜色、红/白/未知和 ITO EN 两种符号均正确。

- [ ] **P4-12 掉落组闭合、FIFO hack 关联和独立 drop。**
  - 源码：[GearDropObservationSession.swift:1–47](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropObservationSession.swift#L1-L47)、[GearDropObservationSession.swift:97–163](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropObservationSession.swift#L97-L163)、[GearDropSummary.swift:14–70](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropSummary.swift#L14-L70)。
  - 普通 hack 不可见，所以 FIFO `ordered` 不是证明。保留 orderedWithoutBonus、bonusExpected、noWaitingHack、uncertainContinuity、repeatedBonus；重复 Bonus 不重复计数，连续性问题后不得硬配 hack。
  - 未关联且没有任何已读行的组只留诊断，不生成有效 drop：[LiveGlyphOutput.swift:1106–1115](../Apps/DynamicGlyph/Sources/Capture/LiveGlyphOutput.swift#L1106-L1115)。完整度是“所见面板覆盖”，不是总库存：[GearDropSummary.swift:53–70](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearDropSummary.swift#L53-L70)。
  - 验收：普通 hack 穿插、缺 Bonus、Bonus 承诺未出现、重复 Bonus、队列超时、queueLimit、frameGap 和 capture stop 均保持关联未知，不把未读写成 0。

- [ ] **P4-13 五种游戏语言、自动/手动与逐屏回退。**
  - 源码：[GameText.swift:5–25](../Packages/GlyphRecognition/Sources/GlyphRecognition/GameText.swift#L5-L25)、[GameLanguageMemory.swift:58–114](../Packages/GlyphRecognition/Sources/GlyphRecognition/GameLanguageMemory.swift#L58-L114)。
  - en、zh-Hans、zh-Hant、ja、ko 均有词包；English 总是后备，匹配仅忽略大小写、空格、标点，不能模糊匹配。自动模式从上次/系统/英语开始，只在形状已确认页面换语言；固定模式不切换。
  - 单面板临时语言最多 5 秒，面板边界变化或时间倒退失效，不写全局偏好；共享英文名不能单独选语言。不同文本使用原置信阈值，不虚构 Android OCR 等价置信度。
  - 验收：自动检测、固定语言、跨语言切换、英文混排、逐屏重试间隔、临时面板语言和首 hack 旧语言通知限制均有测试。

- [ ] **P4-14 游戏语言验证范围和 App UI 本地化分开记录。**
  - 证据：[GAME_LANGUAGES.md:12–18](GAME_LANGUAGES.md#L12-L18)、[TRANSLATION.md:1–13](TRANSLATION.md#L1-L13)、[TRANSLATION.md:47–51](TRANSLATION.md#L47-L51)。
  - en、zh-Hans、ja 有录制核对；zh-Hant、ko 只有翻译词表，不能宣称已验证。**App UI 仅英/日/简中/繁中，无韩语 UI**；Glyph 名不翻译，日/中翻译当前仍标 Needs Review。
  - 验收：四种 UI 的字符串、复数、占位符、权限文案和 widget/shortcut 文案完整；五种游戏 OCR 分别记录真实样本验证，不用词表存在替代识别验收。

- [ ] **P4-15 结果与 drop 的独立持久化、内存历史和保留策略。**
  - 源码：[HackResults.swift:174–235](../Apps/DynamicGlyph/Sources/Results/HackResults.swift#L174-L235)、[HackResultStore.swift:250–376](../Apps/DynamicGlyph/Sources/Results/HackResultStore.swift#L250-L376)、[HackResultSummary.swift:96–115](../Packages/GlyphRecognition/Sources/GlyphRecognition/HackResultSummary.swift#L96-L115)。
  - Read results、Keep results 默认开，Read gear 默认关；闭合结果才保存，运行中仅内存展示，gear 可随后附着。结果和独立 drop 各最多 1,000 条/90 天；本次运行内存 log 上限 50。
  - 清除/删除后迟到 OCR 不得复活；无法识别的新版本数据保留为 unsupported，不当空数据覆盖。gear 只随已保存结果持久化，独立 drop 到达即闭合保存。
  - 验收：四种 read/keep 组合、跨重启、gear 晚到、保留裁剪、重复云记录、版本迁移、损坏记录、删除和 Clear 均用真实数据库验证。

- [ ] **P4-16 默认关闭的 Android 私有云同步——真实 CloudKit 功能等价，必交付。**
  - 真实使用证据：[DynamicGlyph.entitlements:7–14](../Apps/DynamicGlyph/Sources/Resources/DynamicGlyph.entitlements#L7-L14)、[HackResultStore.swift:260–292](../Apps/DynamicGlyph/Sources/Results/HackResultStore.swift#L260-L292)、[HackResults.swift:128–171](../Apps/DynamicGlyph/Sources/Results/HackResults.swift#L128-L171)。
  - 默认 Off，开启前明确隐私确认，切换立即重开存储；同步结构化 hack 结果、gear 和独立 drop，**不同步面板图片、视频和诊断报告**。当前 Plan 已把私有云同步列为 P4：[Plan.md:110](../Plan.md#L110)、[Plan.md:250](../Plan.md#L250)。
  - 必须保留删除/保留策略传播、远程重复记录去重和离线本地保留；源 schema 为 CloudKit 去除 unique attribute，应用自行去重：[HackResultStore.swift:55–57](../Apps/DynamicGlyph/Sources/Results/HackResultStore.swift#L55-L57)。
  - 状态覆盖账号不可用、离线、容量、限流/重试、配置拒绝、同步中、最近成功；不能把 setup 成功当作已同步：[ICloudSyncStatus.swift:81–119](../Apps/DynamicGlyph/Sources/Results/ICloudSyncStatus.swift#L81-L119)。
  - 验收：用真实账号至少两台设备验证新增、修改、独立 drop、删除、保留策略、离线恢复、容量/权限错误和图片仅本机；本地 Room 完成不能代替此项。技术选型不得继续写成无限期暂缓，也不得借此新建无关通用后端。

- [ ] **P4-17 统计范围：预设、自由双端范围和统一过滤。**
  - 源码：[StatisticsRange.swift:3–18](../Apps/DynamicGlyph/Sources/Results/StatisticsRange.swift#L3-L18)、[StatisticsRange.swift:187–268](../Apps/DynamicGlyph/Sources/Results/StatisticsRange.swift#L187-L268)。
  - 实际包括 Day、7 days、30 days、All、Last 24 hours、Last hour和自由双端范围；本地日历日与经过时间分开，起点含、终点不含，live end 与 frozen end 不混淆。
  - 所有图表、列表、选择和 Export 使用同一 range；前期比较仅在对应历史完整时显示，drop 与 hack 的可比较性分别判断：[StatisticsRange.swift:271–375](../Apps/DynamicGlyph/Sources/Results/StatisticsRange.swift#L271-L375)。
  - 验收：午夜、DST、时区变化、最短一分钟、记录裁剪、保存关闭、空范围和 store error 均显示真实原因，不把未知历史显示成 0。

- [ ] **P4-18 统计口径、细分和图表。**
  - 源码：[HackStatistics.swift:36–93](../Apps/DynamicGlyph/Sources/Results/HackStatistics.swift#L36-L93)、[HackStatistics.swift:121–249](../Apps/DynamicGlyph/Sources/Results/HackStatistics.swift#L121-L249)、[HackSummaryCard.swift:29–160](../Apps/DynamicGlyph/Sources/Results/HackSummaryCard.swift#L29-L160)。
  - 只将最终 bonus 纳入 bonus；accepted 分母是已知游戏结果，不是全部槽位。最弱/耗时排名至少 3 次，末槽无绘制时长；秒/Glyph 使用有 inputSeconds 的记录。
  - 图表包含最终 bonus 随时间、accepted、activity、逐小时/日物品、长度/Portal 等级、命令组、最弱 Glyph、绘制中位时间及读取覆盖。命令产出只用完整且非 multiplied 的 gear，partial 和红字不得混入，drone 独立。
  - 验收：未知不进入分母，完整空前期可比较 hack 数但不伪造 bonus，测量数/总数、partial/notRead/unavailable 均分别展示。

- [ ] **P4-19 范围/单条/多选导出与删除。**
  - 源码：[HackResultsView.swift:116–179](../Apps/DynamicGlyph/Sources/Results/HackResultsView.swift#L116-L179)、[HackResultsView.swift:231–320](../Apps/DynamicGlyph/Sources/Results/HackResultsView.swift#L231-L320)、[HackResults.swift:238–287](../Apps/DynamicGlyph/Sources/Results/HackResults.swift#L238-L287)。
  - Export 对当前范围或选择集合的固定快照，支持单卡；仍在读取的 hack 不可删除或导出。删除 hack 同时删 gear，独立 drop 可单独删；诊断目录里的面板图片不随历史删除。
  - 同步开启时确认文案说明其他设备也会删除；删除失败不改 UI 选择，可重试。
  - 验收：范围中新记录到达、iCloud 远程删除、选择交集、未闭合记录、失败回滚、图片保留和迟到 OCR 均符合。

- [ ] **P4-20 Gear CSV v1 全列、时间语义和稳定匿名 ID。**
  - 源码/规格：[GearExport.swift:79–127](../Apps/DynamicGlyph/Sources/Results/GearExport.swift#L79-L127)、[GearExport.swift:249–339](../Apps/DynamicGlyph/Sources/Results/GearExport.swift#L249-L339)、[GEAR_EXPORT_FORMAT.md:37–87](GEAR_EXPORT_FORMAT.md#L37-L87)。
  - 必须实现全部列及 read/read_reason/association/coverage/hack/command/portal/origin 等语义；空白是未知，已知 0/false 明写。记录按时间排序，一项一行，空面板或无 gear 的 hack 也有对应行。
  - record id 使用保存 key 的 SHA-256 前 128 位并带 h/d 前缀，跨导出和同步设备稳定；session/order 仅当前文件有效。采集时区决定小时/日桶，未知 offset 用 UTC 且本地字段为空。
  - 验收：UTF-8 无 BOM、RFC 4180 CRLF、公式注入防护、精确字段顺序、空值、0/false、非整点 offset、版本迁移和重复 record id 均通过：[GearExport.swift:130–177](../Apps/DynamicGlyph/Sources/Results/GearExport.swift#L130-L177)。

- [ ] **P4-21 导出页：Include hour、预览、checksum、文本摘要和系统分享。**
  - 源码：[GearExportView.swift:51–107](../Apps/DynamicGlyph/Sources/Results/GearExportView.swift#L51-L107)、[GearExportView.swift:117–167](../Apps/DynamicGlyph/Sources/Results/GearExportView.swift#L117-L167)、[GearExport.swift:379–445](../Apps/DynamicGlyph/Sources/Results/GearExport.swift#L379-L445)。
  - 每次打开从默认 Include hour=On 开始，选项变化重新生成同一快照；计数、预览、checksum 和分享字节一致。支持不完整/排除项计数、CSV 预览、SHA-256、文本摘要与系统分享，临时文件分享后清理。
  - 验收：长时间范围、部分读取、未闭合、未知格式、选项切换、分享取消和再次导出均无陈旧文件或错误时间。
  - 边界：源码没有通用结果历史导入或 gear CSV 回导，不应误加。

## 6. P5：诊断、报告、录屏、系统入口与验证

- [ ] **P5-01 诊断页与“证据保存 Off”真实行为。**
  - 源码：[DiagnosticsView.swift:10–95](../Apps/DynamicGlyph/Sources/Diagnostics/DiagnosticsView.swift#L10-L95)、[LiveGlyphOutput.swift:81–92](../Apps/DynamicGlyph/Sources/Capture/LiveGlyphOutput.swift#L81-L92)、[LiveGlyphOutput.swift:865–958](../Apps/DynamicGlyph/Sources/Capture/LiveGlyphOutput.swift#L865-L958)。
  - 默认关闭证据保存；关闭时不做逐帧证据 hash、JSONL、截图、识别历史或编码，**但轻量 PowerMeter 和内存事件仍存在**，不能写成所有测量都停。诊断页含识别、采集、窗口、功耗、最近观察和事件。
  - 验收：Off 时无证据目录和逐帧文件，识别/结果/统计仍工作；On/Off 切换仅作用于下一会话，当前会话中不能暗中改变授权。

- [ ] **P5-02 会话证据文件全集及有界写入。**
  - 源码：[EvidenceWriter.swift:18–68](../Apps/DynamicGlyph/Sources/Diagnostics/EvidenceWriter.swift#L18-L68)、[LiveGlyphOutput.swift:282–308](../Apps/DynamicGlyph/Sources/Capture/LiveGlyphOutput.swift#L282-L308)、[GlyphSnapshotWriter.swift:1–56](../Apps/DynamicGlyph/Sources/Diagnostics/GlyphSnapshotWriter.swift#L1-L56)。
  - 不是只有“日志+截图”：还包括 frames.csv、events.log、recognition/tracking、snapshots、results/result-frames、input-validation、commands、gear-drops、recording receipt 及会话实际使用的 CSV 表。
  - 普通帧 JPEG 与 Glyph PNG 是不同预算；writer 有容量上限，queued 不等于 saved，必须记录成功、失败、busy、budgetExhausted。停止时先停止提交，再 drain writer。
  - 验收：各文件 schema、时间关联、丢失原因、容量限制和 crash/停止后的可读性均通过；不得为证据而阻塞识别队列。

- [ ] **P5-03 结果、命令和掉落专用证据预算及隐私。**
  - 源码：[ResultEvidenceSchedule.swift:1–29](../Packages/GlyphRecognition/Sources/GlyphRecognition/ResultEvidenceSchedule.swift#L1-L29)、[ResultEvidenceWriter.swift:5–62](../Apps/DynamicGlyph/Sources/Diagnostics/ResultEvidenceWriter.swift#L5-L62)、[CommandEvidenceWriter.swift:6–38](../Apps/DynamicGlyph/Sources/Diagnostics/CommandEvidenceWriter.swift#L6-L38)、[GearEvidenceSchedule.swift:1–31](../Packages/GlyphRecognition/Sources/GlyphRecognition/GearEvidenceSchedule.swift#L1-L31)。
  - 结果保存 detection、动画及 decision 证据，普通和 decision writer 独立；命令只保存已识别的小裁剪并有每 hack/session 限制；gear 每类面板独立预算。
  - gear 裁切图默认遮标题，完整未遮帧只用于用户明确选择的报告：[GearEvidenceWriter.swift:6–12](../Apps/DynamicGlyph/Sources/Diagnostics/GearEvidenceWriter.swift#L6-L12)。原始 Portal/agent/任意 OCR 文本不得作为身份或额外证据保存。
  - 验收：短暂最终阶段、前一张 supporting read、writer busy、预算耗尽、标题遮罩和缺少图片时的显式状态均通过。

- [ ] **P5-04 会话报告列表、备注、分享和删除。**
  - 源码：[SessionReportStore.swift:27–97](../Apps/DynamicGlyph/Sources/Diagnostics/SessionReportStore.swift#L27-L97)、[SessionReportsView.swift:77–185](../Apps/DynamicGlyph/Sources/Diagnostics/SessionReportsView.swift#L77-L185)。
  - 按时间列出大小和视频，查看 event log；活跃会话不能分享/删除。zip 包含整个停止会话及可选 note.txt，分享临时副本完成后清理，原目录保留，删除才移除。
  - 验收：无日志、活跃会话、含视频、备注、打包失败、分享取消和删除失败均有真实状态；报告分享和历史记录删除互不影响。

- [ ] **P5-05 gear 专项问题报告。**
  - 源码：[GearReportView.swift:22–60](../Apps/DynamicGlyph/Sources/Results/GearReportView.swift#L22-L60)、[GearReport.swift:51–97](../Apps/DynamicGlyph/Sources/Diagnostics/GearReport.swift#L51-L97)、[GearReport.swift:98–165](../Apps/DynamicGlyph/Sources/Diagnostics/GearReport.swift#L98-L165)。
  - 支持缺物、错物、数量、等级/稀有度、错 hack、其他及可选备注；打包该 reading 的结构化记录、渲染图、对应面板图和相关 gear log，而非整个会话。
  - **完整未遮截图是明确隐私选择**，可暴露地图和 Portal 名；裁切图遮标题。云同步他机只有记录没有图片时须标 missing，不伪造成存在。
  - 验收：无图片、日志已删、他机记录、选择/取消完整图、备注上限、分享取消与发送完成均正确。

- [ ] **P5-06 错误报告调试录屏。**
  - 源码：[DebugRecordingPolicy.swift:24–67](../Apps/DynamicGlyph/Sources/Recording/DebugRecordingPolicy.swift#L24-L67)、[DebugScreenRecorder.swift:6–38](../Apps/DynamicGlyph/Sources/Recording/DebugScreenRecorder.swift#L6-L38)。
  - 依赖会话日志，每次会话 opt-in、启动前确定、下次启动不保留；10 分钟/300 MiB、H.264、最高 20 fps，记录分析后的帧而非独立时钟，视频始终无音轨。
  - 可提前 Stop recording 而采集继续；录制失败不能拖停识别。缓冲池有界，丢帧按 rate/status/outOfOrder/pool/notReady/finalization 分类。
  - 验收：独立开关、日志 Off 时不可开启、提前停止、达到上限、尺寸变化、磁盘不足、编码背压和最终化均保持识别连续。

- [ ] **P5-07 相册录屏、设备音频和保存回退。**
  - 源码：[AlbumRecording.swift:15–52](../Apps/DynamicGlyph/Sources/Recording/AlbumRecording.swift#L15-L52)、[AlbumRecording.swift:92–124](../Apps/DynamicGlyph/Sources/Recording/AlbumRecording.swift#L92-L124)、[RecordingAudioOutput.swift:8–24](../Apps/DynamicGlyph/Sources/Recording/RecordingAudioOutput.swift#L8-L24)。
  - 不依赖日志，独立临时目录、30 分钟/1 GiB，可独立提前保存且采集继续，与错误报告录制可同时各编码一份。相册保存 add-only，失败回退 Files；无帧、失败和回退分别提示。
  - Include sound 持久但默认 Off；录制开关本身不持久：[AppPreferences.swift:138–141](../Apps/DynamicGlyph/Sources/App/AppPreferences.swift#L138-L141)。源实现只有 iOS 27 相册路径采设备播放声，AAC 48 kHz 双声道，排除本 app 声音；ReplayKit 路径无声：[SampleHandler.swift:3–5](../Apps/DynamicGlyph/Broadcast/SampleHandler.swift#L3-L5)。
  - Android 只采设备播放音频且以目标游戏允许为前提，**不得改用麦克风**；音频独立队列，编码落后即丢并计数。
  - 验收：两种录制并行、声音开关、无声环境、目标拒绝音频、Photos 拒绝、回退、音频背压和采集停止均正确。

- [ ] **P5-08 录制 receipt、分段文件、wall-time 和容量/时长预警。**
  - 源码：[DebugScreenRecorder.swift:110–139](../Apps/DynamicGlyph/Sources/Recording/DebugScreenRecorder.swift#L110-L139)、[RecordingLimitWarning.swift:28–50](../Apps/DynamicGlyph/Sources/Recording/RecordingLimitWarning.swift#L28-L50)、[RECORDING_LIMIT_WARNINGS.md:17–28](RECORDING_LIMIT_WARNINGS.md#L17-L28)。
  - 开始即写 recording.json，分段 MP4 使中断文件尽量可播；wall-time watchdog、磁盘起止阈值、最终化 deadline 和 stop reason/结果分开记录。
  - 每种录制只警一次：90% 文件上限或剩 1 分钟，时长同时看 wall time 与 source coverage。通知权限拒绝不阻止录制，应用内仍警告；停止、替换、失败撤销旧通知，到上限只结束视频。
  - 验收：静态画面仍按 wall-time 推进，空录制不预警，已上限不发送“提前”警告，异步旧通知不污染下一录制。

- [ ] **P5-09 功耗读数、模式着色和录像 benchmark。**
  - 源码：[PowerSavingSection.swift:12–39](../Apps/DynamicGlyph/Sources/Diagnostics/PowerSavingSection.swift#L12-L39)、[BenchmarkSection.swift:157–230](../Apps/DynamicGlyph/Sources/Diagnostics/BenchmarkSection.swift#L157-L230)。
  - 功耗开关默认开，测试构建可比较关闭，正式发布强制开；模式色仅调试，不能改变业务状态色。功耗计数在日志 Off 时仍可测，但测试工具只在对应构建开放。
  - Benchmark 支持 power saving Off/On/Both、accuracy/real pace/both；原录像不复制、不发送，不写结果、不驱动正式显示，开始采集或退后台取消。
  - 验收：实时丢帧与 accuracy 全等待分开，报告可复制，功耗包含解码边界清楚，实时采集不被 benchmark 污染。

- [ ] **P5-10 Summary Home Screen 小组件和期间深链。**
  - 源码：[SummaryWidget.swift:5–23](../Apps/DynamicGlyph/Widget/SummaryWidget.swift#L5-L23)、[SummaryWidgetProvider.swift:29–58](../Apps/DynamicGlyph/Widget/SummaryWidgetProvider.swift#L29-L58)、[SelectSummaryPeriodIntent.swift:25–55](../Apps/DynamicGlyph/Widget/SelectSummaryPeriodIntent.swift#L25-L55)。
  - 小/中/大尺寸，Today/7 days/All，含 hacks/drops/items、gear 和可用图表；点击打开对应统计范围。期间选择对所有 widget 实例共享，小组件循环切换，大中尺寸直接选择。
  - 未知 gear 用 dash，不把 drop 当 hack；无可读摘要显示 unavailable，不能显示假 0。午夜自动 rollover；时区不匹配时 Today/7 days 暂停显示而不是切错日：[StatisticsWidgetSummary.swift:41–75](../Apps/DynamicGlyph/Shared/StatisticsWidgetSummary.swift#L41-L75)。
  - 验收：各尺寸、空数据、仅 drop、未读 gear、深链、多个实例、跨午夜、时区变化、无 App Group/损坏摘要均通过。

- [ ] **P5-11 Summary 数据发布、刷新节流与 Stop/前台最终刷新。**
  - 源码：[SummaryWidgetPublisher.swift:5–20](../Apps/DynamicGlyph/Sources/Results/SummaryWidgetPublisher.swift#L5-L20)、[SummaryWidgetPublisher.swift:60–103](../Apps/DynamicGlyph/Sources/Results/SummaryWidgetPublisher.swift#L60-L103)、[HackResults.swift:369–380](../Apps/DynamicGlyph/Sources/Results/HackResults.swift#L369-L380)。
  - widget 不直接打开结果库，应用发布保存记录的分日/All 摘要；字节相同不重复 reload。采集期间刷新节流，pending 在 Stop 和回前台补发，时区/时钟变化强制重算。
  - 验收：连续多次保存、删除、远程同步、保留裁剪、时钟变化和停止后数据均不陈旧；运行中未保存 hack 不进入 widget。

- [ ] **P5-12 快捷设置采集开关与 Start / Stop / Open Statistics 快捷动作。**
  - 源码：[DynamicGlyphShortcuts.swift:7–32](../Apps/DynamicGlyph/Sources/Intents/DynamicGlyphShortcuts.swift#L7-L32)、[StartCaptureIntent.swift:1–35](../Apps/DynamicGlyph/Sources/Intents/StartCaptureIntent.swift#L1-L35)、[StopCaptureIntent.swift:20–40](../Apps/DynamicGlyph/Sources/Intents/StopCaptureIntent.swift#L20-L40)、[OpenStatisticsIntent.swift:29–39](../Apps/DynamicGlyph/Sources/Intents/OpenStatisticsIntent.swift#L29-L39)。
  - Start 前台进入系统授权，已运行不得重复启动，授权等待不宣称已开始；Stop 是明确停止而非 toggle，不强行打开应用，并区分 notRunning/stopped/unconfirmed。
  - 开关只有真实采集心跳时为 On，准备授权不算 On：[StartControl.swift:49–57](../Apps/DynamicGlyph/Widget/StartControl.swift#L49-L57)。停止需等待并读取真实状态，超时不得说已停止。
  - Open Statistics 支持 Today/7 days/All，冷/热启动都到正确页，且不改变 widget 自己的期间：[StatisticsShortcutPeriod.swift:1–18](../Apps/DynamicGlyph/Sources/Intents/StatisticsShortcutPeriod.swift#L1-L18)。
  - 验收：无表 gear-only、已运行、准备中、设置引导中、进程死亡、停止成功/超时和三个期间均通过；不能绕过每次 MediaProjection 授权。

- [ ] **P5-13 Debug intent 入口、真实 replay 和消融验证。**
  - 源码：[AppIntentsDebugSection.swift:22–77](../Apps/DynamicGlyph/Sources/Debug/AppIntentsDebugSection.swift#L22-L77)、[Package.swift:8–26](../Packages/GlyphRecognition/Package.swift#L8-L26)、[Ablation.swift:3–59](../Packages/GlyphRecognition/Sources/GlyphReplay/Ablation.swift#L3-L59)。
  - Debug/Simulator 可执行 Start/Stop/Open Statistics；`glyph-recognize` 只输出未确认形状，`glyph-replay` 跑完整实时管线，含 results/commands/gear、语言、OCR 延迟、功耗约束、输入核验和消融。
  - 验收：实时路径和 replay 共用生产 reducer/reader/scheduler，不把业务复制进测试；标注只用于对照，不进入识别。

- [ ] **P5-14 发布测量与逐项真机验收。**
  - 约束：[Plan.md:255–263](../Plan.md#L255-L263)、[Plan.md:304–320](../Plan.md#L304-L320)。
  - 测量 Release 体积、启动、内存、识别 p50/p95、显示延迟、丢帧、持续运行和功耗趋势；普通识别和 OCR 分开，录屏另测。
  - 跑 15–20 分钟完整会话并检查停止释放、内存和游戏触摸；至少两种屏幕比例，记录真实设备和语言范围。
  - 验收：不能用编译、Lint、demo 或 JVM 单测替代真机识别、后台采集、悬浮窗、录制、系统入口和云同步验收。

## 7. 四种导入导出，不能合并成一项

- [ ] **识别表导入/自动读取。** `glyph_line.csv` 必需于 Glyph 辅助，可选 `glyph.csv` 只影响预测；系统文件选择器导入、应用目录自动读取、严格错误状态和首次启用规则全部完成。对应 P2-01～P2-03。
- [ ] **Gear CSV v1 与文本摘要。** 当前范围/单条/多选，Include hour、预览、checksum、分享、完整未知语义全部完成。对应 P4-19～P4-21。
- [ ] **停止会话 zip。** 整个诊断目录、事件日志、可选 note、分享与删除完成；活跃会话不可分享。对应 P5-04。
- [ ] **单个 gear reading 专项 zip。** 结构化 reading、渲染图、遮罩面板图、相关日志和可选完整帧完成，缺失图片明确标记。对应 P5-05。

边界：源项目没有通用结果历史导入，也没有 gear CSV 回导为数据库记录的功能。

## 8. 设置默认值和会话开关对照

实现时不能只保留开关名称而丢掉生效时机。主要依据 [AppPreferences.swift:15–179](../Apps/DynamicGlyph/Sources/App/AppPreferences.swift#L15-L179)。

| 设置 | 源默认/持久性 | 必须保留的业务含义 |
| --- | --- | --- |
| Floating window | On，持久 | 可独立开关；采集与显示生命周期分离，Apple 岛开关不纳入本期。 |
| Game language | Automatic，持久 | 与 UI 语言独立；自动检测结果供下次启动使用。 |
| Save logs and screenshots | Off，持久 | 下一会话生效；控制证据目录、截图和调试录屏，不等于结果历史开关。 |
| Read results | On，持久 | 下一会话生效；Off 时不运行结果 OCR，gear-only 不读 Glyph 结果页。 |
| Keep results | On，持久 | 闭合结果和 drop 的持久化，独立于诊断日志。 |
| Cloud/iCloud sync | Off，持久 | 开启前隐私确认；Android 等价同步默认也必须 Off。 |
| Glyph assistance | 默认 On，但仅在有形状表时生效 | 第一次成功加载自动 On，之后尊重用户 Off；无表直接 gear-only。 |
| Read gear | Off，持久 | Glyph 辅助下还需 Read results；gear-only 无视此开关始终读 gear。 |
| Save item panel pictures | On，但需会话日志 | 图片仅本机诊断目录，不进结构化历史或云同步。 |
| Hide from screenshots | Off，持久 | 只是平台保护请求，不能承诺所有 Android 设备都能排除自身悬浮窗。 |
| Photos Include sound | Off，持久 | 仅设备播放声，非麦克风；只影响实际启动的相册录制。 |
| Power saving | On | 测试构建可关，正式发布强制 On；下一会话生效。 |
| Mode colors | Off，测试构建 | 只改调试底色，不改业务状态色。 |
| Debug recording | Off，不持久 | 每次会话重新授权，依赖日志，不能会话中开启。 |
| Album recording | Off，不持久 | 每次会话重新授权，独立于日志和调试录制。 |

补充：结果保留是结果与独立 drop 各 1,000 条/90 天；诊断目录有自己的删除规则，不随历史保留策略自动清理：[HackResultStore.swift:250–255](../Apps/DynamicGlyph/Sources/Results/HackResultStore.swift#L250-L255)、[RESULT_RECOGNITION.md:488–497](RESULT_RECOGNITION.md#L488-L497)。

## 9. 语言和验证状态矩阵

| 范围 | 已有实现 | 参考证据限制 | Android 验收要求 |
| --- | --- | --- | --- |
| English 游戏 OCR | 词包、header/result/command/gear | 有录制 | 完整流程及混合英文文本。 |
| 简中游戏 OCR | 同上 | 有录制，但部分词/标题未出现 | 实际中文样本，不能把未出现项标已验证。 |
| 日文游戏 OCR | 同上 | 有录制，SIMPLE/INVALID 等部分未出现 | 日文截断/省略名称及英文混排。 |
| 繁中游戏 OCR | 词包 | 无录制 | 必须补真实识别验证，不能用词表代替。 |
| 韩文游戏 OCR | 词包 | 无录制 | 必须补真实识别验证。 |
| App UI | en/ja/zh-Hans/zh-Hant | 日/中机器翻译待人工 review，无 ko UI | 字符串、复数、占位符、权限、widget/shortcut 全量检查。 |

来源：[GAME_LANGUAGES.md:12–32](GAME_LANGUAGES.md#L12-L32)、[TRANSLATION.md:1–13](TRANSLATION.md#L1-L13)。

## 10. 旧 Plan 冲突与当前已落实修正

以下保留审计轨迹，避免后续高层表格再次缩范围。当前 Plan 已落实主要修正，应以链接的新行号为准，不再沿用旧版“CloudKit 暂缓”等措辞。

| 旧版问题 | 当前处理与本矩阵要求 |
| --- | --- |
| Apple 岛曾被直接映射为悬浮窗/通知，容易把排除项当替代完成；小米岛未单列。 | 当前分别列出 Apple 暂缓与小米 P6：[Plan.md:99–100](../Plan.md#L99-L100)、[Plan.md:265–300](../Plan.md#L265-L300)。悬浮窗/普通通知仍独立交付，不冒充任何岛。 |
| CloudKit 曾写“本地先行、同步明确暂缓”。 | 当前已改为 Android 私有云同步 P4，要求默认关闭、删除/保留传播、图片仅本机和真实账号验证：[Plan.md:110](../Plan.md#L110)、[Plan.md:250](../Plan.md#L250)。本地数据库不等于同步完成。 |
| P4/P5 曾可“逐项交付或明确延期”。 | 当前除两类岛外均为必交付，不能以延期关闭验收：[Plan.md:112](../Plan.md#L112)、[Plan.md:263](../Plan.md#L263)。 |
| 系统入口曾写“有对应价值的入口”，可能主观删减。 | 当前 P5 已明确 Summary、采集开关、Start、Stop、Open Statistics 及其心跳/授权/停止确认/rollover/节流：[Plan.md:259](../Plan.md#L259)。高层 [Plan.md:109](../Plan.md#L109) 必须与该细项一起读。 |
| 曾可广泛“清除无 Android 对应能力的 iOS 专属选项”。 | 当前只允许排除 Apple Island/Live Activity，其余平台差异功能等价：[Plan.md:251](../Plan.md#L251)。 |
| 网络实现曾完全受轻量化测量限制，会与同步需求冲突。 | 当前为明确同步需求保留必要账号/云/网络实现，同时禁止无关通用后端：[Plan.md:80](../Plan.md#L80)。 |
| “关闭时不做逐帧诊断工作”过宽，容易误删 PowerMeter 和内存事件。 | 以当前 P5 精确语义为准：不做逐帧 hash/JSONL/截图/证据历史/编码，保留轻量功耗计数和内存事件：[Plan.md:257](../Plan.md#L257)。高层 [Plan.md:107](../Plan.md#L107) 不得据此扩大成“所有测量都停”。 |
| “只有最新待处理帧”可能吞掉一帧命令。 | 当前已补充有界、带源时间的小裁剪队列及溢出/过期/断帧未知语义：[Plan.md:125](../Plan.md#L125)。 |
| 旧状态曾写“仅建立计划、无 Gradle、未执行构建、下一步 P0”。 | 当前已有构建与真机修正记录，应使用实时状态而不是旧句：[Plan.md:5](../Plan.md#L5)、[Plan.md:221](../Plan.md#L221)、[Plan.md:331](../Plan.md#L331)。 |

## 11. 全局验证清单

- [ ] **生产实现测试。** parser、predictor、像素识别、tracker、result/gear/command reducer 和统计均由生产代码直接执行，不把 Swift 业务抄成 Kotlin 测试答案。
- [ ] **参考数据与私有截图。** 缺表/素材的测试明确 skipped 和原因；完整语料模式缺输入失败，绿色但 skipped 不称全量通过：[PROVENANCE.md:14–29](../Packages/GlyphRecognition/Tests/GlyphRecognitionTests/Fixtures/PROVENANCE.md#L14-L29)。
- [ ] **实时与离线一致。** replay 直接复用实时识别核心、OCR 调度和原始时间，live cadence 与 exhaustive 分开；测试 OCR 延迟、乱序完成、旧 session 和断帧。
- [ ] **真实持久化。** 验证数据库重启、schema migration、损坏/未知版本、删除、保留、事务失败，不能只用 mock。
- [ ] **真实云同步。** 至少两台设备、真实账号，验证新增/删除/离线/错误/图片仅本机；模拟通知不能代替。
- [ ] **真实系统行为。** MediaProjection 授权、拒绝、系统停止、悬浮窗触摸、快捷入口、通知权限、设备音频和 Photos/Files 保存均真机检查。
- [ ] **语言逐项。** 五种游戏 OCR 分别记录样本与结果；四种 UI 分别检查资源，未验证项明确列出。
- [ ] **隐私。** 检查诊断图片标题遮罩、完整图选择、CSV 精确时间最小化、匿名 ID、日志/图片不随云同步、分享临时文件清理。
- [ ] **长会话和性能。** 普通识别与 OCR 分开，录制另测；15–20 分钟完整会话、停止释放、内存趋势、功耗、游戏触摸和 Release 体积均留记录。
- [ ] **完成定义。** 只有本矩阵每项及其验证完成后才能声明“除 Apple 岛外全部实现”；P3、构建成功、Lint 或首页可用均不能替代完整验收。

## 12. 不能误算为当前源码已有功能的项目

以下条目可用于区分实现边界或后续验证，但**不能写成当前 iOS 已有并因此强制 Android 照搬的“现成功能”**：

1. **InputAttemptTracker、完整 attempt/redraw/pause 账本仍是计划。** 当前只有被游戏判定 attempt 的 `drawSeconds` 与既有 validator 对照；完整独立账本设计见 [RESULT_RECOGNITION.md:461–477](RESULT_RECOGNITION.md#L461-L477)，当前概览也明确其仍 planned：[RESULT_RECOGNITION.md:32–35](RESULT_RECOGNITION.md#L32-L35)。
2. **结果页悬浮摘要和通用识别问题表单仍属未来里程碑。** 当前已有应用内结果卡和 gear 专项报告，但没有同一套通用 result issue flow：[RESULT_RECOGNITION.md:52–58](RESULT_RECOGNITION.md#L52-L58)、[GearReportView.swift:1–24](../Apps/DynamicGlyph/Sources/Results/GearReportView.swift#L1-L24)。
3. **普通 Portal 命令模式不能被“非 drone”正向确认。** `HackCommandSummary.Mode.portal` 是模型值，但当前命令观察主要正向确认 drone；无 drone footer 时应保留 unknown，不得根据没钥匙或 INVALID 推断：[COMMAND_RECOGNITION.md:44–48](COMMAND_RECOGNITION.md#L44-L48)。
4. **Neutral Portal 检测未实现。** 无 resonator bars 即无法通过当前 detector 确认；不能为“完整”伪造 neutral 结果：[PortalScreenDetector.swift:1–4](../Packages/GlyphRecognition/Sources/GlyphRecognition/PortalScreenDetector.swift#L1-L4)。
5. **两 Glyph 结果布局和错误末槽结果仍缺真实证据。** 两 Glyph 当前沿用三 Glyph 间距假设：[ResultScreenReader.swift:84–92](../Packages/GlyphRecognition/Sources/GlyphRecognitionImages/ResultScreenReader.swift#L84-L92)；错误末槽仍是验证缺口：[RESULT_RECOGNITION.md:162–167](RESULT_RECOGNITION.md#L162-L167)。实现已有，但不能宣称这些类别已验证。
6. **繁中、韩文 OCR 和日/中 UI 翻译审核尚未完成。** 词包/字符串存在不等于真实识别或母语审核通过：[GAME_LANGUAGES.md:12–18](GAME_LANGUAGES.md#L12-L18)、[TRANSLATION.md:47–51](TRANSLATION.md#L47-L51)。
7. **Apple Dynamic Island / Live Activity 不属于本期实现。** 其文档中的 freshness、结果摘要、动画和调度不能转成普通 Android 功能的虚假完成声明。
8. **小米超级岛当前没有 Android 实现。** P6 目前只有接入方案和验证清单，App ID、审核、白名单、模板和代码均未完成：[Plan.md:300](../Plan.md#L300)。

---

**使用方式：**实施时逐项勾选功能，验收时另附构建、测试、真实样本、真机或账号证据。任何一项只有代码而缺少其指定验证，都应保持未完成，不得以“已有类似功能”代替。
