# Glyph 自动识别与预测验收

## 目标与来源

续接 Deepseek Harness 会话 session-3edd550c-a5fa-4fa8-8ad6-a2b16519328c，完成 Android 的自动识别、前缀预测、原生悬浮窗显示和输入冻结，对齐 Dynamic-Glyph 的 iOS 行为。本次不扩大到云同步、装备或岛接入。

先沿用 iOS 的算法、布局比例和阈值，再修正真实运行已经暴露的问题。采集尺寸归一化、按比例定位、空阵列 11 节点校准均为通用路径，没有手机型号名单或每款手机单独的识别参数。

内置数据为 142 个 Glyph、542 行原始序列、540 个去重序列。预测只采用连续 CONFIRMED 前缀；正在展示的槽位保留观察状态，未来槽位显示预测。进入 INPUT 后冻结展示序列，玩家笔划不能将其覆盖。

## 此前回放成功与实时失败的差异

原会话主要验证 AndroidBenchmarkFrameSource 到 GlyphCaptureEngine 的解码回放。实际 MediaProjection 输入仍是 1216 × 2688，回放却是 603 × 1333；相同算法在实时入口承担了更高的像素处理量。旧实测展示阶段分析间隔中位数约 492 ms，短暂显示的 Glyph 可能仅得到一次有效样本，达不到 iOS 的两次稳定样本要求。

另外，初始悬浮窗覆盖了输入倒计时条；逐帧日志与窗口刷新还会请求完整功耗报告，包含内存测量和百分位排序。离线回放无法验证这些实时入口问题。

本次修正：

1. CaptureSize 统一实时输入，沿用 iOS Broadcast 的 603 像素目标宽度，保持原始比例，不放大小画面。只调整采集缓冲，不修改设备显示分辨率。
2. FrameConverter 对常见 RGBA 连续像素布局逐行批量读取，保留实际 stride 和源时间。
3. GlyphFrameRecognizer 将逐像素外轮廓、圆环遮罩、线段距离中的临时点对象改为标量计算。公式、运算次序、阈值、采样点和候选排序不变，没有跨帧缓存。
4. 默认悬浮窗移至画面高度 26% 的位置，避开本次原片的输入倒计时和绘制按钮，仍可拖动。
5. 窗口颜色和逐帧日志读取已有轻量采样值，完整功耗报告在请求时才计算。CPU 日志使用已有约 1 Hz 采样值，同一秒内重复值是预期行为。
6. 关闭节能时绕过安静画面节流，保留正常约 20 Hz 上限，与 iOS 的开关语义一致。

没有降低稳定样本数量、扩大形状容错或用预测填补未确认槽位。

## 实际系统采集验收

设备连接 127.0.0.1:7555，Android 15 / API 35，报告型号 NCO_AL00 的模拟器。采集前显示为 1216 × 2688，本次没有更改其显示设置。

LiveCaptureRecognitionInstrumentedTest 每项独立启动采集：

MainActivity 开始按钮 → 系统整屏共享授权 → CaptureService / MediaProjection → 正常速度播放用户原片 → 真实悬浮窗 → 生产识别快照。

视频经过系统屏幕采集进入识别器，不把解码帧直接注入引擎。普通配置为节能开启、语言自动、悬浮窗开启、结果读取开启、会话诊断关闭；关闭历史保存、同步和录制副作用。结束时恢复设置，并停止采集和播放器。

| 用户原片 | 首次完整确认的序列 | 前两项确认后预测 |
| --- | --- | --- |
| Screenrecorder-20261108-133013.mp4 | question / less / forget / all / lie | question / less → forget / all / lie |
| Screenrecorder-20261108-093906.mp4 | nourish / xm / portal | nourish / xm → portal |

每次必须同时满足：

- 第一次进入 INPUT 前，同一 hack 已从前两个 CONFIRMED Glyph 得到正确后缀。
- 第一次进入 INPUT 时，完整序列与原片一致，所有槽位 CONFIRMED。
- INPUT 内连续 3 秒保持同一 hack、同一已冻结序列，不受玩家笔划影响。
- 实际采集运行且正常悬浮窗可见；不等第二次视频循环补考。

**优化版普通配置连续 5 轮，每轮两项，共 10 次实时采集全部通过。** 每项均是独立启动后的首次播放。日志为 build/codex-live-optimized-run1.log 至 run5.log；run2–run5 的逐项 JSON 保存于 app/build/outputs/analysis/codex-live-optimized/。run1 的设备报告未成功拉回，只有运行日志，不冒充保留了该轮详细状态。

**随后开启会话诊断连续 2 轮，共 4 次同条件测试也全部通过，合计 14 次通过。** 证据为同目录 diagnostics-run1/、diagnostics-run2/，日志为 build/codex-live-diagnostics-run1.log、run2.log。其中两次五 Glyph 的稳定样本数分别为 5/6/5/6/5、4/5/5/5/5，三 Glyph 为 5/6/5、5/5/4。诊断开关不会改变识别阈值或首次 INPUT 的验收要求。

最后检查系统 MediaProjection 为 null、应用没有活动 Service，采集和播放器已停止。

报告包含本轮时间、passed/failed、实际前缀和后缀、每槽样本数/提交原因、状态时间线和一次性功耗报告。失败时同样写文件并保留原断言，每次开始删除该视频旧报告。

## 失败过程与性能证据

仅缩小采集后，一度因窗口遮挡不能识别输入倒计时。调整窗口及功耗采样后，两段视频已能完整识别，但普通配置重复测试中，五 Glyph 原片仍有一次在首次 INPUT 前未观察到前两项预测。该轮旧版测试没有写失败快照，不能确定是漏首槽还是漏观察中间快照；成功 JSON 实为上轮遗留，不能作为该轮证据。

随后补充失败状态记录，并检查实际热循环。同一 question/all/玩家输入拖尾帧在桌面 JVM 分别分配约 2.85/10.02/13.88 MB 临时对象；标量优化后降为约 0.65/1.19/1.85 MB。

52 张实际截图加 142 个独立合成 Glyph，共 194 个输入，优化前后完整识别输出摘要逐项相同，覆盖全部边采样、校准、候选、墨迹索引和计数。比较文件为 recognition/build/probe/equivalence-before.txt 与 equivalence-after.txt。独立合成全表测试不等同于全部 142 个 Glyph 均经过实景验证。

同一模拟器五 Glyph 用例普通配置测量，单位 ms：

| 工作 | 标量优化前平均 / p95 | 优化后平均 / p95（run2） |
| --- | --- | --- |
| 整帧处理 | 49.2 / 109 | 33.6 / 62.5 |
| Glyph 读取 | 22.8 / 79.1 | 10.5 / 30.3 |
| 空阵列搜索 | 7.93 / 19.6 | 6.76 / 15.4 |

优化后 run2 五槽各有 5 次稳定样本。这些数值仅说明该设备和原片下的实际改善。旧版开启证据/截图并反复跳回片头的运行也出现过单槽样本不足，保存在 codex-live-acceptance/；不把旧失败与优化版通过结果混为同一版本。

## 自动检查与复现

- 识别模块：467 项测试，0 失败、0 错误，4 项因既有私有语料缺失跳过。
- 应用 JVM：183 项测试，0 失败、0 错误、0 跳过。
- 新增回归涵盖完整内置形状表、全部 540 个序列及其前缀、redo/冻结/过期证据、采集尺寸、RGBA stride、节能开关和轻量功耗读取。
- 原文档的 84 项设备测试属于前会话记录，本轮不声称重新执行过。
- 最终 Debug/Release 构建、AndroidTest 构建和 Debug/Release Lint 全部通过，见 build/codex-final-verified-build.log。

构建和静态检查：

    .\gradlew.bat :recognition:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease :app:lintRelease --console=plain

实时测试先安装 Debug APK 和 AndroidTest APK，给应用悬浮窗权限。两段用户原片在应用 external files 下分别命名 real-recording.mp4 和 real-recording-093906.mp4；缺原片或缺权限明确 skip，不能记为通过。设备空闲且采集停止时执行：

    adb -s 127.0.0.1:7555 shell am instrument -w -e class org.szres.glyphlens.capture.LiveCaptureRecognitionInstrumentedTest org.szres.glyphlens.test/androidx.test.runner.AndroidJUnitRunner

在 runner 参数中加入 -e saveSessions true 可用相同验收条件检查开启会话诊断的真实采集，报告会标明实际配置。

交付产物：

| 文件 | 字节数 | SHA-256 |
| --- | --- | --- |
| app/build/outputs/apk/debug/app-debug.apk（已安装到当前模拟器） | 62,461,692 | 3c44d2ac89877932b638093f45c47203b6a55e2d5182b5fdb9aff36115e3138a |
| app/build/outputs/apk/release/app-release-unsigned.apk（未签名） | 58,501,521 | f72bdd615690f8119deb48cb9827a86bbd0d08cc041158cd99257d9baadce5ee |

验收范围为两段用户原片经实际系统采集的识别、预测和冻结，以及上述生产代码回归。当前没有安装游戏直接游玩的验证，也未完成第二种设备比例、全部语言、15–20 分钟 Release 游戏会话或所有 142 个 Glyph 的实景覆盖；不以本次结果冒充完成这些范围。
