# Glyph Lens 0.4.1：自动识别与可选绘制完成后隐藏

更新日期：2026-11-09。

**实时模式授权后在后台自动识别，初始隐藏窗口与图标；整组确认或唯一预测就绪后自动显示。** 新增“绘制完成后自动隐藏”，默认关闭。开启后去掉顶部状态与 × 整行，仅显示可拖动的 Glyph 画布；本轮绘制结束后自动隐藏，继续同一会话等待下一轮。通知提供“显示悬浮窗”和“停止”。原“本轮识别完成后自动停止”、静态停止结果与结果窗重新开始按钮删除。

本轮实现分支为 `codex/glyph-notification-visibility`，已交付到 `main`，版本为 **versionCode 5 / 0.4.1**。最终生产提交 `e0c8f9d` 已推送，使用原有 350ms 标题有效期与 iOS 阶段判断，精确保留 ML Kit 三个反射构造入口以修复 Release OCR 初始化失败。当前核心 236 项（3 跳过、0 失败）、Android 单元 102 项、构建/Lint、新签名 APK 核验和实际 Release 六项界面流程均通过；Glyph 截图人工总审已通过，main 推送和 v0.4.1 Release 发布均已完成。既有 Debug 界面、实采与尺寸矩阵因 Kotlin 业务实现相同而复用，证据范围见第 5 节。用户七条规则原文见 [Plan.md](../Plan.md#2-强制通用规则)。

## 1. 启动与手动控制

| 操作 | 实时模式 | 省电模式 |
| --- | --- | --- |
| 首页开始并授权 | 立即后台识别，初始不显示窗口或图标 | 显示 48dp 图标，点击后开始识别 |
| 自动隐藏关闭时启动识别 | 等待整组结果可用后自动显示 | 保留原识别窗与 × 操作 |
| 自动隐藏开启时启动识别 | 初始隐藏，结果可用后显示画布 | 点击图标后初始隐藏，结果可用后显示画布 |
| 有顶部行时点 × | 完全隐藏，无图标；同会话识别继续 | 停止采集/引擎/OCR，清空结果并回图标 |
| 通知“显示悬浮窗” | 显示最新结果，不重启识别 | 显示当前窗口或待命图标，不伪造新识别结果 |
| 离开首页 | 不重新打开隐藏的实时窗口 | 保留省电待命入口，自动隐藏流程不被首页离开打断 |
| 首页或通知“停止” | 彻底释放共享、采集和窗口 | 彻底释放共享、采集和窗口 |

“绘制完成后自动隐藏”仅在共享完全停止时允许修改，开启时确保浮窗显示功能可用。旧自动停止偏好不会迁移为新选项；升级后自动隐藏保持默认关闭，需用户主动选择。

实时 × 和自动隐藏都只改变窗口可见性，不重建服务、识别引擎或采集会话，不丢弃已确认槽位与预测。隐藏期间继续处理当前画面和生成最新快照，仅取消 Canvas 绘制及过期显示重绘。系统共享指示与前台通知保持，通知手动显示看到当前结果。

省电初始图标待命不采集图像、不运行 OCR。自动隐藏关闭时，× 解除图像接收面，关闭读取器、引擎及 OCR 线程，清空快照后回图标；再次点图标创建新识别会话，仍使用同次共享授权。自动隐藏开启并启动识别后，使用连续自动显示/隐藏流程，隐藏本身不回到省电待命。

## 2. 每轮自动显示与窗口位置

实时模式，以及开启自动隐藏后已经启动的省电识别，在本轮整组内容可展示时自动显示，满足以下任一条件：

- 已知本轮槽位总数，连续已确认前缀加**唯一预测**覆盖整个序列。
- 已知整组的全部槽位都已有真实确认结果。

未知总槽位数、结合预测后仍有未覆盖槽位、只有未确认观察或预测有歧义时，不提前认为整组可用。预测后缀仍按预测样式显示，不改成确认，不改变后续第 4、5 项等真实观察的确认边界。

每轮只自动显示一次。首次完整结果可用时可以从隐藏状态弹出；同轮主动 × 后不会因后续帧重新打开。自动隐藏完成后，通知手动显示也不会被同一完成状态反复关闭。下一轮仍可重新自动显示，轮次以真实识别会话中的 `hackID` 区分，不通过重启引擎模拟连续识别。

| 打开方式 | 位置规则 |
| --- | --- |
| 结果就绪触发的自动显示 | 清除旧手动位置，按当前阵列、计时条与空隙重新避让 |
| 通知“显示悬浮窗” | 保留用户已拖动的位置，不强行移回自动位置 |

自动显示不能恢复到曾经挡住阵列的旧拖动位置，尤其需要继续识别剩余 Glyph 时。自动隐藏启用后，整条顶部控制行使用实际不可见布局，窗口高度与可用空隙不再计入该行；画布仍可拖动。关闭自动隐藏后恢复正常顶部行，实时 × 为隐藏、省电 × 为停止识别。

## 3. 绘制结束后的自动隐藏

自动隐藏判断跟随真实游戏阶段，不按固定延时猜测玩家是否已经画完：

1. 当前轮必须实际进入过 INPUT；进入 INPUT 只表示玩家开始绘制，结果窗继续显示。
2. 当前有效画面随后确认该轮 COMPLETED，才自动隐藏本轮窗口。
3. 隐藏不停止采集或引擎，继续同一会话等待下一轮；通知仍可手动显示或彻底停止。

标题读取保持原有 **350ms** 有效期与既有阶段判断，不为 COMPLETED 增加额外延迟宽限，也不新增输入起点追踪状态。自动隐藏只使用正常识别链提供的当前有效完成状态；OCR 频率、Glyph 确认及预测规则不变。

计时条短暂消失、断帧、过时快照、INTERRUPTED 或展示结束都不能单独作为“绘制完成”。完成隐藏每轮只执行一次，避免用户从通知打开后被每个完成帧重新关闭。

开启自动隐藏时，新一轮命令/展示开始也会清除旧窗口；即使上一轮没有观察到明确完成画面，也不会让旧结果遮挡新一轮。随后等待该轮结果就绪再自动显示。关闭自动隐藏时，完成后窗口继续保留，由实时 × 或其他明确用户操作控制可见性。

本轮删除 `RESULT_READY`、停止后静态结果和结果窗重新开始按钮。识别与预测不会因为自动隐藏而在 INPUT 开始时停止；完成阶段读取只用于识别生命周期，不恢复分数、装备或结算业务。

## 4. 等价计算优化与范围

本轮继续使用 0.4.0 的传统 Glyph 识别、固定采样、按需 RGBA 视图和位置缓存，进一步减少重复计算：

| 改动 | 等价关系与要求 |
| --- | --- |
| 38 条基本边使用 `Long` 位掩码 | 用位运算和置位计数替代逐帧模板 Set 差集；保留候选距离、精确匹配、歧义和包含关系 |
| 节点/槽位连通域单次判色 | 同一次搜索中每个像素只执行一次判色；保留连通区域与定位结果 |
| 隐藏时停止显示工作 | 不继续 Canvas 渲染或过期显示刷新；采集、识别快照与阶段读取继续 |

采样位置、阈值、帧率、确认边界、预测规则和 OCR 节奏保持。PRESENTING 仍按正常 100ms / 系统受限 200ms 的资格间隔提交标题读取；有当前像素支持的 INPUT 继续跳过不需要的形状与标题读取，离开条件后恢复当前阶段读取。不能为降低 CPU 跳过第 4、5 项或放宽确认标准。

[.gitattributes](../.gitattributes) 固定两份内置表各自的原始换行：`glyph_line.csv` 为 LF，`glyph.csv` 为 CRLF。它们的字节需继续匹配原始固定哈希，避免 Windows 切换分支改变打包内容；不改表数据、不放宽完整性断言。

### 此前的三次匹配 CPU 观测

以下数据对应此前 `48a7d00`，保留原始测量，不作为当前 R8 修正后签名包的新性能证明。

在同一 Android 15 / API 35、1216×2688、440dpi 的 x86_64 模拟器上，通过 ARM64 native bridge 运行 Debug 应用，使用同一五图录屏、相同冷启动流程和系统整屏共享。视频播放器位于独立进程；这里记录应用及其测试进程累计 CPU 时间，不能解释为实体手机整机占用。0.4.0 初始显示窗口，0.4.1 初始隐藏，因此对比也包含显示工作量变化。

| 次数 | 0.4.0 会话 / CPU | 0.4.1 会话 / CPU | 0.4.0 分析帧 / 标题 OCR | 0.4.1 分析帧 / 标题 OCR |
| --- | --- | --- | --- | --- |
| 1 | 17.519s / 14.37s | 17.250s / 11.08s | 244 / 36 | 248 / 42 |
| 2 | 17.197s / 11.37s | 17.202s / 12.64s | 242 / 40 | 246 / 43 |
| 3 | 17.193s / 11.68s | 17.282s / 12.33s | 247 / 40 | 239 / 33 |
| CPU 中位数 | **11.68s** | **12.33s** | — | — |

**本轮未测得稳定的进一步 CPU 降幅。** CPU 时间中位数增加 0.65s，约 5.6%；逐次 CPU/会话时长折算的单核等效占用中位数为 67.93% → 71.34%。不能只采用第一次较低的值宣称优化成功。各次标题 OCR、图像处理和模拟器桥接开销存在波动，这些观测不足以归因到某个改动，也不证明实体手机温度或长期电池改善。

六次测量区间内均完成 `question, less, forget, all, lie` 的真实确认，并观察到 `question, less` 前缀预测 `forget, all, lie`。0.4.1 第三次在 CPU 快照冻结后的通知按钮查找环节失败，因此该次保留为性能观测，**不计为完整流程通过**。后来完整 7 项回归通过；额外 720px 验证进一步捕获到系统通知卡片仍折叠，测试展开方式已据此修正并重跑通过，未修改生产通知实现，详见第 5 节。原始数据：[旧版 1](../build/v041-validation/baseline/run-1.json)、[旧版 2](../build/v041-validation/baseline/run-2.json)、[旧版 3](../build/v041-validation/baseline/run-3.json)、[新版 1](../build/v041-validation/current-cpu/run-1.json)、[新版 2](../build/v041-validation/current-cpu/run-2.json)、[新版 3](../build/v041-validation/current-cpu/run-3.json)。

0.4.0 文档中的 40.65% 属于旧优化，不能作为 0.4.1 新降幅；隐藏仍持续识别，也不等同于省电待命或零耗电。

产品范围保持 Glyph 自动识别与预测、Kotlin/Views/Canvas 原生实现、仅 `arm64-v8a`、Release R8、随包离线 OCR。目标为 Android 10 / API 29 以上、竖屏整屏共享。

主要实现位置：

- [CaptureService.kt](../app/src/main/java/org/szres/glyphlens/capture/CaptureService.kt)、[RealtimeOverlayVisibility.kt](../app/src/main/java/org/szres/glyphlens/capture/RealtimeOverlayVisibility.kt)：采集生命周期、每轮显隐与通知操作。
- [OverlayController.kt](../app/src/main/java/org/szres/glyphlens/overlay/OverlayController.kt)：画布、控制行可见性和自动/手动位置。
- [MainActivity.kt](../app/src/main/java/org/szres/glyphlens/ui/MainActivity.kt)、[AppPreferences.kt](../app/src/main/java/org/szres/glyphlens/settings/AppPreferences.kt)：新选项及首页状态。
- [GlyphFrameRecognizer.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/GlyphFrameRecognizer.kt)：基本边位掩码与模板匹配。
- [NodeArrayDetector.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/NodeArrayDetector.kt)、[GameUILayoutLocator.kt](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/image/GameUILayoutLocator.kt)：节点/槽位定位和连通区域。

## 5. 0.4.1 验收与发布

当前生产提交为 **`e0c8f9da1d297d9c08266cbe27300ceac6570980`**，已包含在 `main` 与 `v0.4.1` 标签中。全部实现、测试和文档落盘后的统一构建及单元测试通过，新签名 APK 已核验；该实际签名包的六项界面流程全部通过，Glyph 截图人工总审已通过，main 推送和 v0.4.1 Release 发布均已完成。

### 验证结果与适用范围

| 检查 | 结果与证据 |
| --- | --- |
| 当前源码统一构建 | Debug、Release、测试 APK 通过，见 [r8-fix-build.log](../build/v041-validation/r8-fix-build.log) |
| 当前核心 / Android 单元 | **236 项，3 跳过、0 失败 / 102 项通过**；缺素材跳过不计为识别通过 |
| 当前 Lint | Debug、Release 均 **0 错误、30 警告** |
| R8 保留结果 | 三个 ML Kit 入口的公开无参构造出现在保留清单、未出现在删除清单；六项实际签名流程中的真实反射构造均通过，继续启用 R8 |
| 同业务实现界面证据 | 既有 **20 项通过，4.767s**，见 [ui-tests-final.log](../build/v041-validation/ui-tests-final.log) |
| 同业务实现 Debug 实采 | 既有 **7 项通过，255.718s**，见 [full-live-final.log](../build/v041-validation/full-live-final.log)，流程如下 |
| 当前签名 APK | ABI、权限、表哈希、v3 签名、ZIP/ELF 16KiB 对齐、大小和 SHA-256 已核验 |
| 当前签名 Release | **6 项界面流程通过**，三个组件真实反射构造及彻底停止均通过；[r8-fix-release-ui.log](../build/v041-validation/r8-fix-release-ui.log)，Glyph 截图人工总审已通过 |
| CPU | 三次匹配观测属于此前 `48a7d00`，未证实进一步下降；不作当前签名包的新性能证明 |

当前 `app/src/main` 与 `recognition/src/main` 的 Kotlin 业务源码与 `7bf1d51` 相同；额外延迟处理已撤回。下面复用该业务实现已经完成的 Debug 证据，**不声称在 `e0c8f9d` 上重新跑过一轮 Debug 7 项或矩阵**。R8 差异由当前签名 Release 验收单独覆盖。

本次实际签名包的六项流程为五图、三图、省电待命/恢复、实时自动隐藏、省电自动隐藏、实时隐藏与通知恢复。六份报告均为 `ui_checks_passed_manual_screenshot_review_required`，三个组件实际构造通过，`fullStopVerified = true`。实时自动隐藏首/次轮分别发生在播放后 21.753s / 21.895s，省电为 21.754s / 21.980s；这是这份录屏的观测时间，不是固定隐藏定时器。两种自动隐藏都在原授权下完成两轮，真实点击通知恢复后保持显示。证据位于 [r8-fix-release-ui](../build/v041-validation/r8-fix-release-ui/)。

六项流程的截图人工总审已通过：五图 `question, less, forget, all, lie`（含第 4、5 项）和三图 `nourish, xm, portal` 与原表一致；绘制中保留完整无顶部行画布，两种模式连续两轮完成后均稳定无窗、无图标，通知恢复正确。实时 × 后完全隐藏、下一轮自动显示也已核对。省电首轮的瞬时 `automatic-hidden.png` 尚含上一合成帧的画布，因此使用持续隐藏观察后的 `after-automatic-hide.png` 确认稳定结果；不把瞬时截图当成已无窗的证据。人工核对与报告内的 UI 自动断言分别完成。

| 既有真实流程 | 已验证内容 | 报告 |
| --- | --- | --- |
| 五图 | `question, less, forget, all, lie` 全部真实确认，第 4、5 项正确；前两项预测后三项，输入冻结 | [五图](../build/v041-validation/full-live-final/real-recording.json) |
| 三图 | `nourish, xm, portal` 全部确认，前两项预测 `portal` | [三图](../build/v041-validation/full-live-final/real-recording-093906.json) |
| 实时隐藏 | 初始隐藏、自动显示、× 后持续识别和通知恢复 | [实时隐藏](../build/v041-validation/full-live-final/real-recording-hidden-realtime.json) |
| 实时自动隐藏 | 两轮真实完成后隐藏、下一轮显示，通知恢复不重复关闭 | [实时自动隐藏](../build/v041-validation/full-live-final/real-recording-auto-hide.json) |
| 省电自动隐藏 | 图标启动后同会话连续两轮自动显隐和通知恢复 | [省电自动隐藏](../build/v041-validation/full-live-final/real-recording-power-saving-auto-hide.json) |
| 连续五图 | 同一采集会话下一轮继续确认和预测 | [连续五图](../build/v041-validation/full-live-final/real-recording-continuous.json) |
| 省电待命/恢复 | × 停止回图标，同授权重新开始并清除旧结果 | [省电待命](../build/v041-validation/full-live-final/real-recording-power-saving.json) |

上述 7 项在 1216×2688 / 440dpi 的模拟器运行。同业务实现的额外矩阵使用同一真实五图录屏和系统共享，均覆盖连续两轮五图、预测、真实 COMPLETED 后自动隐藏及通知恢复：

| 共享输出 / 密度 | 结果 | 证据 |
| --- | --- | --- |
| 720×1600 / 320dpi | 通过，49.354s | [报告](../build/v041-validation/matrix-720x1600-final/report.json)、[日志](../build/v041-validation/matrix-720x1600-final/test.log) |
| 1080×1920 / 420dpi | 通过，49.164s | [报告](../build/v041-validation/matrix-1080x1920/report.json)、[日志](../build/v041-validation/matrix-1080x1920/test.log) |

### Release OCR 的实际故障与修正

此前签名包自动隐藏失败。真实诊断 [temporary-release-trace.json](../build/v041-validation/temporary-release-trace.json) 记录 **88 次 OCR 请求、88 次失败、0 次完成**，语言设置正确；[release-trace-logcat.txt](../build/v041-validation/release-trace-logcat.txt) 第 1386 行起出现以下入口的 `NoSuchMethodException`，说明公开无参构造被 R8 删除：

- `com.google.mlkit.common.internal.CommonComponentRegistrar`
- `com.google.mlkit.vision.text.internal.TextRegistrar`
- `com.google.mlkit.vision.common.internal.VisionCommonRegistrar`

当前只显式保留这三个实际反射入口，继续启用 R8；独立 Java Release 验收中三个组件的实际反射构造及六项界面流程均通过。Android 官方说明，full mode 不会因为类被保留就自动保留无参构造，反射使用的构造方法需显式保留。见 [R8 full mode 的构造规则](https://developer.android.com/topic/performance/app-optimization/full-mode#retain-the-default-constructor)。

过程中的 1px 布局取整断言、状态读取时序、通知卡片实际未展开和独立播放器旧 STOP 延迟均在测试夹具修正，不能解释此 R8 故障。此前 `11d2ea9` 的 750ms 尝试缺少对应失败帧证据，已经撤回；最终继续使用原有 350ms 标题有效期与阶段判断，不增加输入起点状态。旧 `cb932…`、`0ef440…` APK 及其失败日志保留为过程记录，不作为发布附件。

### 当前签名 APK

| 项目 | 已核验值 |
| --- | --- |
| 文件 | `app/build/outputs/apk/release/Glyph-Lens-0.4.1-arm64.apk` |
| 版本 | `versionCode = 5` / `versionName = 0.4.1` |
| 生产源码 | `e0c8f9da1d297d9c08266cbe27300ceac6570980` |
| 大小 | **15,808,043 bytes**（约 15.81MB / 15.08MiB） |
| SHA-256 | `d13e3f0a7dcda22c6956d0a19bf3649163d54ae58bac3e05ecfa3113b75b580c` |
| 原生库 | 仅 `lib/arm64-v8a/libmlkit_google_ocr_pipeline.so`，11,064,544 bytes |
| 构建与签名 | R8 Release，当前本地测试密钥的 v3 签名；ZIP 16KiB 对齐通过，原生库 3 个 ELF LOAD 段均按 16,384 bytes 对齐 |
| SDK 与权限 | minSdk 29 / targetSdk 37；悬浮窗、前台服务、屏幕共享前台服务、通知、网络状态及应用内部动态接收器权限；无 INTERNET、麦克风或存储读写权限 |
| `glyph.csv` | `b1eaac450aa60643e25c17ae5c12ba8803dd0c50d60823dda5f6768ba9e2c5bf` |
| `glyph_line.csv` | `72c3e0f1b93992cf725bf2ce8adfdb2df4daddf6b5f8a6c8f7e8629e6934b6e7` |

证据：[final-apk-verification.json](../build/v041-validation/final-apk-verification.json)、[final-apk-badging.txt](../build/v041-validation/final-apk-badging.txt)、[final-signature.txt](../build/v041-validation/final-signature.txt)。识别表与原始字节完全一致；Windows 换行修正不改变 Glyph 或序列数据。包大小未变不代表内容相同，发布时须核对当前完整哈希。

### 发布与验证限制

已推送原仓库 `main` 并发布 [v0.4.1 Release](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.1)，公开且标记为 Latest。发布提交及标签均指向 `912b7dbc420f21a0c6422c628a2e00d239ca11e6`，生产源码为 `e0c8f9d`；随后仅补齐发布核验记录。附件为 [arm64 APK](https://github.com/vhu231/Dynamic-Glyph-Android/releases/download/v0.4.1/Glyph-Lens-0.4.1-arm64.apk) 与 [SHA256SUMS.txt](https://github.com/vhu231/Dynamic-Glyph-Android/releases/download/v0.4.1/SHA256SUMS.txt)。GitHub 资产 digest 和上传后重新下载的 APK 均为上表的 `d13e3f…` 完整哈希，大小 15,808,043 bytes，与通过验收的签名文件完全一致。

新增目标 [szres/dynamic-glyph-android](https://github.com/szres/dynamic-glyph-android) 按用户最新要求暂不操作，不推送、不合并其独立历史，也不作为本轮交付阻塞项。

自动 Glyph ID 断言与截图人工核对分开记录。模拟环境的录屏 `fitCenter` 只证明对应共享输出，不等同于真实游戏在所有机型重新布局；实体 ARM64 手机的长期温度与电池表现尚未实测。原始外部录屏、私有素材及 `build/` 证据不随源码分发，APK 通过 Release 附件交付。
