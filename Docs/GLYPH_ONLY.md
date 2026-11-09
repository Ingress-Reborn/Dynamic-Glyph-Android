# Glyph Lens 0.2.0：仅 Glyph 识别与预测

更新日期：2026-11-09。

> **0.2.0 历史记录。** 本文保留该版本的范围、构建产物及验证证据。0.3.0 新增实时/省电两种模式，当前操作和待命行为以 [POWER_MODES.md](POWER_MODES.md) 与 [Plan.md](../Plan.md) 为准；本文的测试数量、CPU、APK 大小及哈希不代表 0.3.0 已通过验收。

本文件定义精简后的 Android 产品范围。**0.2.0 只做 Glyph 自动识别、连续确认前缀预测及原生悬浮显示，仅打包 arm64。** 完整 iOS 业务迁移已不再是目标；旧计划里尚未验收的统计、同步、录制等功能不会以关闭开关的形式保留为后续任务。

本轮统一构建、JVM 测试、14 项 Android 仪器测试及 3 组显示配置共 6 次 Debug 首次播放实采已通过。播放器迁入独立测试 APK 后再完成 2 项 Debug live；实际交付的优化 Release 完成 2 项真实 UI 旅程和截图人工核对。Release 自动检查阶段变化和持续 3 秒，逐 Glyph 与预测颜色由截图人工核对，不将其写成 Release 逐 Glyph 自动断言。包哈希及分层证据见第 6 节。

## 1. 用户可见范围

| 保留 | 行为 |
| --- | --- |
| 首页 | 系统采集授权、开始/停止、状态、窗口显示/隐藏、打开 Ingress、CSV 导入 |
| 设置 | 游戏语言、画布宽高与清晰度、窗口显示设置 |
| 自动识别 | 从真实捕获画面读取节点、线条、标题与槽位证据，确认展示中的 Glyph |
| 序列预测 | 根据连续已确认前缀及槽位总数查询序列表，唯一时显示尚未展示的后缀 |
| 悬浮窗 | 原生 Canvas 显示确认/观察/预测状态，输入阶段冻结，支持拖动和内容避让 |
| 离线数据 | 内置并可导入的形状/序列表，Latin/中文/日文/韩文四类随包 OCR |

已删除的 Android 用户功能：

- 结果分数、奖励、装备、掉落和 Portal 信息读取。
- 命令识别业务、接受情况核验及命令记录；玩家输入是否正确的核验。
- 历史、统计、Room、本地业务数据库、Drive 和其他云同步。
- 录像保存、游戏音频、用户诊断记录、截图报告与导出。
- 小组件、快捷设置磁贴、系统快捷入口、引导和演示，以及苹果岛/小米岛功能计划。

采集所需的前台通知与停止按钮仍保留。测试源码中的本地播放器、性能计数和一次性验收证据不提供为产品功能，也不把旧 iOS 参考目录打包进 Release。

## 2. 识别语义与 iOS 对齐

当前管线为单个 `CaptureService` 接收 MediaProjection 画面，转换后由 Glyph 引擎完成识别，`PresentationTracker` 追踪确认槽位，`PresentationSnapshotFactory` 基于 `confirmedPrefix` 生成预测并交给 Canvas 渲染。

必须保持以下行为：

1. 唯一匹配的当前形状属于观察证据，不能直接冒充已确认槽位。确认仍由稳定样本、槽位前进或展示结束等原有边界产生。
2. 连续重复 Glyph 保留原样；漏槽保留缺口，不能把后面的项向前挪。连续确认前缀在第一个未确认槽位处停止。
3. 序列表查询只使用已确认前缀，并结合已观察的槽位总数。唯一匹配时才显示确定后缀；歧义、矛盾或缺少序列表时不虚构预测。
4. INPUT 冻结展示序列，玩家笔画不再用于输入正确性核验，也不能回写展示槽位。redo 仍遵循原阶段边界。
5. 标题仍需区分命令、展示、输入、完成；新鲜 command/completed 标题的优先级保留。这里的命令/完成只是状态边界，不触发已删除的命令、结果或 Gear 业务。

**标题读取节奏恢复原 iOS 规则：PRESENTING 和 INPUT 正常为 100ms，系统受限时为 200ms。** 本轮删除先前“当前像素证明阶段后改为 250ms”的策略。其他场景沿用原非 hack/静止画面调度，省电调度固定启用，不保留用户关闭开关。

单个在途 OCR 请求、请求与返回的源帧时间一致性、**0.35 秒标题来源年龄**及 **0.75 秒阶段 grace**不变。100/200ms 表示允许提交的最小间隔，不保证 OCR 在该时间内完成，也不改写旧 OCR 的 sourceTime。

主要源码：[GlyphCaptureEngine](../app/src/main/java/org/szres/glyphlens/capture/GlyphCaptureEngine.kt)、[PresentationTracker](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/session/PresentationTracker.kt)、[PresentationSnapshotFactory](../recognition/src/main/kotlin/org/szres/glyphlens/recognition/session/PresentationSnapshotFactory.kt)、[GameHeaderReader](https://github.com/vhu231/Dynamic-Glyph-Android/blob/2901166d4145a53c8e1deaf8f9ed217f8961c227/recognition/src/main/kotlin/org/szres/glyphlens/recognition/schedule/GameHeaderReader.kt)。

## 3. 保留已经证实必要的通用适配

初始实施顺序仍为先照搬 iOS 的几何、布局比例和判定，再对实际失败作通用修正。此前已观察到内容居中黑边使固定全帧比例取错槽位，以及固定窗口位置遮挡槽位，因此保留：

- 以真实 11 节点和槽位行定位游戏内容，统一供标题、槽位和计时条读取使用。
- 在 `Image.planes` 访问和全屏复制前执行约 20Hz 帧准入；一次转换提供灰度与颜色，不重新做重复全帧转换。
- 窗口根据阵列、真实计时条和实际窗口高度选择空隙，必要时缩小画布；用于窗口摆放的估计值不作为阶段识别证据。
- 用户拖动后保留其位置；尚未手动拖动时，显示尺寸或密度变化可触发重新自动摆放。

没有品牌、型号或分辨率参数名单。通用机制不等于所有 Android 设备已验证：引擎仍限定 `frame.width < frame.height`，当前目标是**竖屏整屏共享**。横屏、方形和折叠设备横屏，以及单应用共享/分屏到全屏悬浮窗的坐标映射尚未认证。

采集尺寸使用系统内容变化回调更新；这项实现不替代上述共享模式的实测。平台依据见 [Android MediaProjection 内容尺寸说明](https://developer.android.com/media/grow/media-projection)。旧实现根因和对应旧包证据归档于 [ANDROID_ADAPTATION.md](https://github.com/vhu231/Dynamic-Glyph-Android/blob/2901166d4145a53c8e1deaf8f9ed217f8961c227/Docs/ANDROID_ADAPTATION.md)。

## 4. 为什么仍保留离线 OCR

iOS 参考代码调用系统 Vision；Android 当前使用 ML Kit 的 bundled 模型，把模型和推理库随应用携带。保留四类模型是为了英文、简中/繁中、日文、韩文阶段标题可在安装后离线读取，避免把首次联网下载作为识别前提。模型存在不等于这些语言全部完成实际游戏验收。

Google 官方区分随包模型与通过 Play 服务下载的模型：前者增加包体积但可立即使用，后者首次使用前可能需要下载。当前选择随包方式，未引入模型商店或第二套 OCR 后端。参见 [ML Kit Android 文本识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。iOS 使用的系统接口见 [Apple Vision 文本识别](https://developer.apple.com/documentation/vision/recognizing-text-in-images)。

已有产物内 **arm64 OCR 原生库条目为 11,064,544 B，约 11MB**；该数字仅指该库，既不是四类模型的全部成本，也不是本轮 APK 的最终大小。原生代码、模型、DEX、资源和打包方式共同决定安装包大小；R8 不能删除实际仍在使用的 OCR 模型。仅 arm64 去除其他 ABI 的重复库，但不保证达到 iOS 包体积。

当前 [app/build.gradle.kts](../app/build.gradle.kts) 声明 `versionName = "0.2.0"`、`abiFilters += "arm64-v8a"`，Release 启用 R8 优化。最小系统 API 29、compile/target API 37。应用 manifest 只保留 MainActivity、SettingsActivity 与 CaptureService；本轮实际包检查确认仅含 `lib/arm64-v8a`，无 INTERNET、RECORD_AUDIO 权限。

## 5. 工程约束

继续遵守 [Plan.md 第 2 节](../Plan.md#2-强制通用规则) 中用户七条原文：不扩大范围处理猜测风险；入口校验一次；只在复用、复杂逻辑或清晰概念需要时抽取；正常流程与细节分开；只注释不明显约束；生成文件改源头；测试真实实现并少用 mock。

本轮精简直接删除不需要的产品入口、执行路径和依赖，不建立可选功能框架或插件系统来保留旧业务。当时保留的 iOS 参考材料已在后续 Android 仓库清理中移除；独立的源 iOS 仓库不修改。

## 6. 本轮验收记录

### 已完成检查与对应产物

以下记录来自本轮精简后的代码与安装包，旧版本数据未计入：

| 项目 | 实际结果 | 验证边界 |
| --- | --- | --- |
| 识别 JVM | 213 项，0 失败，3 项既有私有 fixture 缺失跳过 | 跳过项不是识别通过 |
| 应用 JVM | 80 项，0 失败 | 运行实际生产逻辑回归 |
| 构建 | Debug、Release、AndroidTest 编译全部通过 | 编译成功不代替设备行为验证 |
| Lint | Debug/Release 均通过，0 error、26 warning | 警告主要为原生 Switch、既有文案及仅 ARM64 配置，不写成零警告 |
| Android 仪器测试 | 全部 14 项通过，包含 2 项 live、真实 OCR/crop 与 UI 测试 | 测试目标为本轮 Debug APK |
| 扩展 live | 另两组尺寸各 2 项通过，总计 3 组、6 次首播 | 真实系统共享与生产服务，设备通过 native bridge 运行 arm64 |
| 包内容 | 仅 `lib/arm64-v8a`，无 INTERNET/RECORD_AUDIO 权限 | Release 启用 R8；不保留其他 ABI |
| 播放器迁移后 Debug 复测 | 独立测试 APK 播放器下 2 项 live 通过 | 保留逐 Glyph ID、前两项确认前缀预测及冻结自动断言 |
| 实际交付 Release UI 黑盒 | 2 项真实 UI 旅程通过，逐 Glyph 截图人工核对完成 | 自动断言仅覆盖 Watching→Your turn 及持续 3 秒；不声称逐 Glyph 自动断言 |
| 物理 ARM64 手机、实体温度与长期功耗 | 尚未测量 | 下述环境与 CPU 不能替代 |

当前已签名 Release 文件：Glyph-Lens-0.2.0-arm64.apk（本机历史产物，不随仓库分发）。

| 产物字段 | 值 |
| --- | --- |
| 大小 | **15,775,219 B**，约 15.78MB / 15.04MiB |
| SHA-256 | `f0833e32d47b99e12a566b64357f65ddad3ba9ef7ac66ab455cc86393ff374ba` |
| 版本与 ABI | `0.2.0`，仅 `arm64-v8a` |
| 签名 | 本地测试 debug key，APK Signature Scheme v3；**不是生产发布签名** |

哈希与大小锁定此时的文件。后续若重新生成或重新签名，需重新记录；不能将本表签名或检查结果无条件转移到新文件。

### Debug 真实采集矩阵

设备是 **x86_64 Android 环境，通过 ARM64 native bridge 执行本版 arm64 应用**，不是物理 ARM64 手机。游戏语言使用自动模式，系统选择整屏共享，要求实际悬浮窗和生产 `CaptureService` 工作。原片以正常速度、`fitCenter` 播放后重新被采集，未直接向识别引擎注入离线帧。

| 显示尺寸 / density | 五 Glyph 首播 | 三 Glyph 首播 | 主进程 CPU：五 / 三 |
| --- | --- | --- | --- |
| 1216×2688 / 440dpi | [通过](../build/glyph-only-validation/debug-1216x2688/real-recording.json) | [通过](../build/glyph-only-validation/debug-1216x2688/real-recording-093906.json) | 96.1% / 100.4% |
| 1080×1920 / 420dpi | [通过](../build/glyph-only-validation/debug-1080x1920/real-recording.json) | [通过](../build/glyph-only-validation/debug-1080x1920/real-recording-093906.json) | 78.8% / 86.5% |
| 720×1600 / 320dpi | [通过](../build/glyph-only-validation/debug-720x1600/real-recording.json) | [通过](../build/glyph-only-validation/debug-720x1600/real-recording-093906.json) | 89.0% / 95.8% |

每组顺序为五 Glyph、三 Glyph。机器汇总见 [summary.json](../build/glyph-only-validation/summary.json)。每个 JSON 包含实际尺寸、密度、运行状态、确认序列、预测前缀、预测后缀、冻结结果及计时证据。

五 Glyph 原片就是用户追加指定的 `C:/Users/YuanChu/Downloads/Screenrecorder-20261108-133013.mp4`：**57,471,446 B**，SHA-256 为 `f0aca5dc80aff0fb673acde4b0b2c4fdd1aae999acaf49e38f7b75c8cc06c022`。已核对原文件与两份实际测试设备副本哈希一致；设备测试中的文件名为 `real-recording.mp4`，上述矩阵和下述最终 Debug/Release 记录覆盖这份确切原片。

| 原片 | 完整确认序列 | 预测时已确认前缀 | 唯一预测后缀 |
| --- | --- | --- | --- |
| `real-recording.mp4` | question / less / forget / all / lie | question / less | forget / all / lie |
| `real-recording-093906.mp4` | nourish / xm / portal | nourish / xm | portal |

6 次均在首次进入 INPUT 时已满足前两项确认前缀的唯一预测、完整序列确认，并继续冻结 **3 秒**；不允许后续循环掩盖首轮失败。

CPU 是每次 Debug 短会话内部记录的应用主进程 CPU 时间/会话时间，**100% 表示一个核心**，不含单独播放器进程。native bridge、Debug 构建和不同显示尺寸都会影响成本；这里仅保留本次观测，**不与旧版作性能改善比较，也不推断物理 ARM64 手机负载或发热**。同一条件的长期性能、实体温度及电池功耗仍未测量。

### 独立测试播放器及交付 Release 复测

播放器已迁入独立测试 APK，进程为 `org.szres.glyphlens.test:fixture_video`，不再成为产品 APK 的测试入口。迁移后 Debug 两段原片重新执行，逐 Glyph ID、前两项确认前缀唯一预测、完整序列和 3 秒冻结自动断言均通过：[运行日志](../build/glyph-only-live-final-fixture.log)、[五 Glyph JSON](../build/glyph-only-validation/debug-final-fixture/real-recording.json)、[三 Glyph JSON](../build/glyph-only-validation/debug-final-fixture/real-recording-093906.json)。这 2 次是前述 6 次矩阵之外的最终复测。

最终五 Glyph Debug 报告的 5 个 CONFIRMED 槽位分别有 **6、5、6、6、5** 个支持样本。第 4 槽 all 在推进到第 5 槽时确认，第 5 槽 lie 在末槽释放、阵列清空时确认；前两项 question/less 对应的唯一预测为 forget/all/lie。这些证据验证的是实际槽位边界和完整序列，不由预期序列表直接填充确认状态。

随后直接安装本节列出的签名 Release，保持 SHA-256 `f0833e32d47b99e12a566b64357f65ddad3ba9ef7ac66ab455cc86393ff374ba` 不变，以仅依赖 Android framework 的测试 runner 执行真实首页、系统共享、悬浮窗、游戏播放和停止流程。没有为测试给生产代码增加 R8 keep 规则，也没有替换为未优化 APK。

自动部分确认 `Watching the sequence` → `Your turn · draw the sequence`，并在输入阶段持续观察至少 3 秒；五 Glyph 首次 INPUT 为播放后 14,051ms，两段输入观察时长分别为 3,122ms 与 3,091ms。**该 Release 测试不自动断言逐 Glyph ID 或前两项确认前缀发生的瞬间。** 逐个图形、预测颜色及输入后序列由负责验收的 agent 使用图像工具人工核对，结论和限制记录在 [visual-review.json](../build/glyph-only-validation/release/visual-review.json)。[运行日志](../build/glyph-only-release-ui.log) 明确区分 UI 自动检查和截图核对。

| Release 原片 | 自动 UI 报告 | 已人工核对的图像与结果 |
| --- | --- | --- |
| 五 Glyph | [report.json](../build/glyph-only-validation/release/real-recording-1794184786354/report.json) | [frame-00023](../build/glyph-only-validation/release/real-recording-1794184786354/frame-00023.png) 的 all/lie 为蓝色预测；[frame-00024](../build/glyph-only-validation/release/real-recording-1794184786354/frame-00024.png) 展示完整序列；[输入 3 秒后](../build/glyph-only-validation/release/real-recording-1794184786354/input-after-three-seconds.png) 的 question/less/forget/all/lie 均为紫色确认且顺序保留 |
| 三 Glyph | [report.json](../build/glyph-only-validation/release/real-recording-093906-1794184805029/report.json) | [frame-00018](../build/glyph-only-validation/release/real-recording-093906-1794184805029/frame-00018.png) 和[输入 3 秒后](../build/glyph-only-validation/release/real-recording-093906-1794184805029/input-after-three-seconds.png) 均呈现完整 nourish/xm/portal，输入期为确认颜色 |

该黑盒测试的构建与运行入口如下。运行时设备已安装上述交付 Release 和生成的独立测试 APK，并准备好两段原片及悬浮窗权限：

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest '-PglyphTestRunner=org.szres.glyphlens.capture.ReleaseCaptureInstrumentation'
adb am instrument -w org.szres.glyphlens.test/org.szres.glyphlens.capture.ReleaseCaptureInstrumentation
```

Release 截图采样会影响 CPU，**此轮不测量性能**。测试结束后已确认 MediaProjection 为 null，显示恢复为 1216×2688 / 440dpi，实际交付签名 Release 保持安装。最后重跑生产构建与 Lint 通过，仍为 0 error、26 warning，签名包哈希未变化。

旧 [GLYPH_PREDICTION.md](https://github.com/vhu231/Dynamic-Glyph-Android/blob/2901166d4145a53c8e1deaf8f9ed217f8961c227/Docs/GLYPH_PREDICTION.md)、[ANDROID_ADAPTATION.md](https://github.com/vhu231/Dynamic-Glyph-Android/blob/2901166d4145a53c8e1deaf8f9ed217f8961c227/Docs/ANDROID_ADAPTATION.md) 和 [精简前计划](https://github.com/vhu231/Dynamic-Glyph-Android/blob/2901166d4145a53c8e1deaf8f9ed217f8961c227/Docs/HISTORY/PLAN_BEFORE_GLYPH_ONLY_2026-11-09.md) 保留原版本历史证据。旧 18 次矩阵、旧 CPU 区间或旧测试计数未在本节复用为新版本验收；物理 ARM64 手机、横屏及前述共享模式边界仍保持未认证状态。
