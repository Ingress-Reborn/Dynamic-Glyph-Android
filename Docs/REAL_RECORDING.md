# 真实录屏回放证据（2026-11-08）

> 最新 `Screenrecorder-20261108-133013.mp4` 的 RGB_565 解码、五 Glyph、结果和 gear 证据见文末“task-1：133013 新录屏与 RGB_565”。此前 093906 样本及其历史回归保留在原章节中。

## 结论

对 `Screenrecorder-20261108-093906.mp4` 的生产链路回放**已经识别成功**，没有复现“内置表已加载时三个 Glyph 都无法识别”的失败。生产 `GlyphCaptureEngine` 依次确认 `nourish / xm / portal`，三个槽均为 `CONFIRMED`，随后由真实计时轨道进入 `INPUT`。

因此本次没有修改亮度、边缘覆盖、unexplained-ink、坐标或机型/分辨率参数。为了消除非展示阶段的瞬时候选而调参，会破坏现有保守语义且没有证据支持。新增的落地工作是：

- 用生产视频解码、`GlyphCaptureEngine` 和生产 ML Kit 建立可重复的真实录屏仪器回放；
- 保存逐帧布局、节点、Glyph、slot/tracker 和原始 OCR JSONL，以及关键 PNG；
- 固化内置表形状、`nourish / xm / portal` 序列、完整 11 节点校准、`INPUT`、command/input OCR 的真实回归断言；
- 核查结果和掉落业务输出（见下方“结果与掉落”）。

诊断用的 `headerLineObserver` 只观察生产 header OCR 的原始行；默认值为 `null`，不改变识别、调度或生产返回值。

## 输入与只读性

- 原始文件：`C:\Users\YuanChu\Downloads\Screenrecorder-20261108-093906.mp4`
- SHA-256：`249E8C0A3F18548A771BC8CBB1BD9AFB8F867EF7EBFBF2821BBA7B2B78428276`
- 视频：H.264 High / yuv420p / BT.601 limited，1216×2688，无旋转元数据，2732 帧。
- 视频流时长 22.958167 秒；容器时长 23.099041 秒；约 119 fps 的可变帧率，不是固定 20 fps。
- 音频：AAC mono 48 kHz；本任务未分析音频。
- 原视频只通过 `adb push` 复制到 AVD，分析读取副本，没有修改原文件。

生产解码路径为 `AndroidBenchmarkFrameSource`：`MediaExtractor + MediaCodec + ImageReader + 生产像素/RGBA 转换`。093906 的历史运行使用 `YUV_420_888`；当前实现保留该默认路径，并支持首帧交付前按实际 Surface 错误回退到 `RGB_565`。均按原比例不放大得到 603×1333 RGBA，以源时间最多 20 Hz 送入 `GlyphCaptureEngine`。

## 产物

宿主证据目录：

- `app/build/outputs/analysis/baseline/summary.json`：完整运行计数、OCR 指标和最终快照。
- `app/build/outputs/analysis/baseline/frames.jsonl`：446 条逐帧生产 engine/direct Glyph、独立节点检测、slot 和 tracker 证据。
- `app/build/outputs/analysis/baseline/production-ocr.jsonl`：生产 OCR lane 的 21 次原始文本、置信度、归一化坐标和 phrase 结果。
- `app/build/outputs/analysis/baseline/ocr.jsonl`：五个代表时间点，同一生产 header crop 与扩大诊断区域的 ML Kit 对照。
- `app/build/outputs/analysis/baseline/frame-*.png`：生产解码后的 23 张每秒关键帧。
- `app/build/outputs/analysis/contact-sheet.png` 与 `initial-frames/`：最初人工时间轴筛查图，不代替上述生产证据。
- `app/build/outputs/analysis/regression-gear-task48/summary.json`：关闭后的首选项、OCR 调度、检测计数、队列和真实业务回调。
- 同目录 `gear.jsonl`：446 帧 gearBounds / OCR in-flight / tracker / waiting 证据；`gear-tail-ocr.jsonl`：三个尾部时间点的原始诊断文本、置信度和归一化位置；`detector-probe.txt`：生产检测器对四张真实尾部 PNG 的 bounds 与横线采样。
- 回归实现：`app/src/androidTest/java/org/szres/glyphlens/analysis/RealRecordingAnalysisInstrumentedTest.kt`。

## 时间轴与生产证据

| 源时间（秒） | 真实内容 | 生产证据 |
| --- | --- | --- |
| 0–约 2.7 | 地图 | header OCR 是地图文本，不应产生 hack phase。 |
| 2.796844 | 空 Glyph 阵列 | `NodeArrayDetector` 命中 11/11；origin=(75.4697, 492.9582)，size=(452.0725, 542.7564)，nodeRadius=13，RMS=0.3238 px；engine=`NO_STROKE`。 |
| 3.117755 / 3.167477 | 命令频道正在打开 | 独立对照和生产 lane 均在**原有** `FrameCrops.header` 区域读出中文；生产 phrase=`command`。所以“固定 header crop 把文字裁掉”不成立。 |
| 约 4–8 | 命令/早期图形 | 可出现 `complex`、`more` 等瞬时几何 exact candidate；tracker 仍为 `INACTIVE`，这些候选没有被提交为序列，不能当作最终结果错误。 |
| 8.476755 | 三个 slot 出现 | `SlotStripReader` 读出 3 个 marker，中心约 0.340 / 0.500 / 0.659，无 timer。 |
| 8.983744 | 第一个展示 Glyph | exact=`nourish`，边集合 `[5-6, 5-a, 6-8, 8-a]`；tracker=`PRESENTING`，phaseSource=`INDICATOR_TRANSITION`。 |
| 9.493722 | 第二个展示 Glyph | exact=`xm`，边集合 `[3-4, 3-6, 4-7, 5-6, 5-7]`；第一槽已 `CONFIRMED`。 |
| 10.012355 | 第三个展示 Glyph | exact=`portal`，边集合 `[1-3, 1-8, 2-4, 2-9, 3-4, 6-7, 6-8, 7-9]`；第二槽已 `CONFIRMED`。 |
| 约 10.98–11.34 | 玩家输入开始 | slot timer=true，tracker 由 `INPUT_TIMER` 进入 `INPUT`；生产 OCR 在 11.341211 读出“开始!”并解析为 `input`。 |
| 约 11.5 起 | 输入序列 | 三槽 `nourish / xm / portal` 均已 `CONFIRMED`，各 6 个展示样本；这是内置表后的生产成功证据。 |
| 约 18–22.9 | 回地图与掉落展示 | glyph 状态随阵列消失而失去新鲜校准是预期生命周期，不应把地图亮像素当成 Glyph 来“修正”。 |

三个名字同时由实际边集合和内置 CSV 交叉验证：

- `nourish = 568a5`
- `xm = 347563`
- `portal = 134297681`
- `glyph.csv` 含完整序列 `nourish,xm,portal`（第 118 行）。

## OCR 核查

生产 header crop 在 603×1333 下为 `Rect(0, 79, 603, 139)`。真实 OCR 行位于该 crop 内，以下对照说明 OCR 与布局均能工作：

| 时间 | 原始 ML Kit 文本 | phrase |
| --- | --- | --- |
| 3.117755 | `命令频道正在打开..` | command，confidence 0.5805 |
| 9.239811 | `正在接收符文序列..` | presenting，confidence 0.5480 |
| 11.291077 | `开始!` | input，confidence 0.7292 |

生产异步 lane 另在 3.167477、4.439633、5.706177、7.404444、8.268388 读出 command，在 11.341211、12.804533、14.084244 读出 input。

并非每次 OCR 都完美：9.290488 的一次生产读取为“正在接收符女序列..”，10.776322 的“准备重复符文序列..”也未形成已知 phase。真实 slot highlight/transition 与 timer 已提供独立状态证据，因此这些 OCR miss 没有导致本次序列失败，不能据此修改 Glyph 像素参数。

baseline 的 OCR 指标为 `completed=21 / droppedBusy=414 / failed=0`。这次 AVD 上 23 秒影片用了约 280 秒墙钟，中文 ML Kit 单次读取很慢；`droppedBusy` 是该准确性回放环境的实际调度结果，**不是**对真实手机实时 OCR 吞吐的测量，也不应作为放宽状态机的理由。

## 结果与掉落

最新结果/掉落专项回归和原始输出在 `app/build/outputs/analysis/regression-gear-task48/`。该轮同时运行真实序列回放与生产 `TableController` 内置表断言，明确启用 `analysisGear=1`。`summary.json` 的 `readsGearRequested=true`、`readsGearEffective=true`，所以首选项和 `LiveBusinessPipeline.readsGear` 都不是断点。

本轮把仪器改为在 end-of-stream 等待 OCR lane 空闲后先执行生产 `engine.close()`，再记录回调；`engineClosedBeforeSummary=true`。这修正了旧 `regression-gear/summary.json` 在 `finally` 关闭前生成、只能表示中间状态的问题。最终业务输出为：

- 两条 `HackResultSummary`：原先的 `awaiting / closed=false`，以及关闭时的 `notObserved / closed=true / closeReason=captureStopped`；两条都没有可验证的 hacking/speed bonus。
- 一条 hackID=1 的 `HackGearAttachment`：`reading.state=notRead`、`reason=captureStopped`。**这不是 gear 读取成功，也不含任何物品。**
- `drops=[]`；没有 `HackGearAttachment.Reading.Read`，没有闭合的 gear panel/group。
- 最终整体 OCR 指标为 `submitted=43 / completed=43 / droppedBusy=389 / failed=0`，其中 portal=`1/1`、result=`0/0`；gear 的零提交见下方断点。
- `GearDropObservationSession` 在 17.994466 秒为 hackID=1 记录 `waiting`，22.901044 秒记录 `captureStopped`；最终 `trackerObserving=false`、`readingDisposition=nonPanel`、`waitingHackIDs=[]`。旧证据里的 `gearAttachments=[]` 是因为快照早于关闭，不能再用它判断生产状态机没有排队。

### 断点：没有 gearBounds，因而从未进入 gear OCR

`gear.jsonl` 对 446 个 admitted 帧逐条保存了同一生产 `GearDropScreenReader.bounds` 的独立对照，以及调度、tracker 和队列状态：

- `probedFrames=446`、`candidateFrames=0`；18 秒后的 `tailProbedFrames=96`、`tailCandidateFrames=0`。
- 每个尾部样本均为 `bounds=null`、`pendingGearSourceTime=null`、`trackerObserving=false`。
- `gearSubmitted=0`、`gearCompleted=0`、`gearCancelRequested=0`。所以 `production-ocr.jsonl` 没有 Portal Key / Power Cube / XMP 并不是“gear OCR 已读但未保存”，而是**生产 gear OCR 根本没有被提交**。
- 解码尺寸 603×1333 的 aspect=2.210614，处于检测器已有的 2.1..2.25 范围；不是长宽比拒绝。
- 用同一生产 `GearDropPanelDetector` 直接检查真实解码 PNG：19.57 秒的 full/left/right 横线最高命中分别只有 22/32/14（每项均要求至少 74/80），20.58 秒 full 最高 18/80。可见“当日首次入侵”是较窄的金/紫奖励框，没有现有检测器要求的近全宽连续 lavender 横线。不能把这一证据转化成按机型降低阈值。

### 尾部真实 OCR 行与布局边界

`gear-tail-ocr.jsonl` 是额外的大区域 ML Kit **诊断对照**，crop 为源帧 y=0.25..0.84、2×像素；它没有被伪装成生产 gear reading。20.5834 秒的代表原始行如下，坐标均为全帧归一化 `(x,y,width,height)`：

| 原始文本 | 坐标 | confidence |
| --- | --- | --- |
| `当日首次入侵` | (0.3201, 0.3053, 0.3648, 0.0255) | 0.7083 |
| `X1` | (0.0274, 0.4370, 0.0365, 0.0158) | 0.5020 |
| `Portal Key` | (0.0257, 0.4584, 0.2305, 0.0263) | 0.8655 |
| `L5, x1.` | (0.0705, 0.6080, 0.1476, 0.0206) | 0.5111 |
| `Power Cube` | (0.0282, 0.6339, 0.2604, 0.0210) | 0.8698 |
| `L6. x1` | (0.0622, 0.7809, 0.1401, 0.0188) | 0.6391 |
| `XMP 能量炮ew` | (0.0282, 0.8057, 0.3151, 0.0195) | 0.6045 |

21.6156 秒的对照也能读出 Portal Key、Power Cube、`XMP 能量炮ew`，数量变为 x2。真实布局中数量/等级和物品名称是分行的，道具文字位于奖励框外的 y≈0.46 / 0.63 / 0.81。现有 `GearDropScreenReader` 只读取 summary box 内固定位置的 `L… xN name` 行；iOS 同源实现也明确写着动画卡即使在框下也不提交 OCR。因此这段录屏展示的是当前 reader 未支持的“奖励框 + 框外浮动动画卡”，不是一次标准 gear summary-panel 成功样本。

本轮没有证据支持把 74/80、颜色、坐标或机型参数放宽后宣称修复。若以后正式支持此布局，安全的通用方向是独立的 floating-card OCR/分组器：按 x 与相邻基线组合数量/等级行和名称行，再复用现有 parser/tracker 的保守确认与闭合语义；奖励框只能提供上下文，不能把框外文本冒充标准 panel。那不是一条检测阈值修复，本轮不实现也不宣称验证。

标准结果 bonus、标准 gear summary-panel 的物品/等级/数量/红字、组闭合与关联仍然**未验证**。最终 `NotRead` 只准确表示此段录屏在现有生产边界内没有读到 gear，不能写成零掉落或识别成功。此次没有修改生产识别参数、CSV 或其他业务。

## task-49 floating-card 独立验证：证据不足，未接入生产

本轮先实现并测试了独立的 `FloatingGearDropReader`，没有改 `GearDropPanelDetector`、74/80 横线要求、标准 panel 坐标、语言阈值或机型参数。独立路径只把 `GearDropTextParser` 白名单内的名称变成物品：数量/等级行必须完整匹配 `L1..L8 + xN` 或无等级的 `xN`，其中心 x 必须落在名称行范围内，且名称行紧邻其下方；一对多配对、低置信、AP、Portal/地图/命令文本和普通文本均保持 `unknown`。真实 OCR 的 `XMP 能量炮ew` 只允许去掉最多两个行尾 ASCII 噪声字母，仍须回到既有白名单，不能模糊发明物品。

数量/等级与名称组合后按名称的**绝对基线**量化到共享 rowLimit 行槽，而不是按当帧可见物品数编号。这样缺读 Portal Key 时，Power Cube/XMP 不会移动到它的 tracker slot。`GearDropTracker.acceptFloating` 只用于该独立路径：同一物品/等级数量增长需要重新获得两票，数量下降仍冲突；floating 两票最大间隔为 2 秒，标准 `accept` 的 1.2 秒策略不变。独立测试覆盖 `panelLeft`、`captureStopped`、来源时间和无 waiting hack 的独立 drop 语义。

`recognition/src/test/kotlin/org/szres/glyphlens/recognition/gear/FloatingGearDropReaderTest.kt` 直接采用 task-48 的真实文本、坐标和 confidence，实际验证：

- 20.5834 秒行组合为 `Portal Key x1`、`L5 Power Cube x1`、`L6 XMP Burster x1`，21.6156 秒对应三项均为 `x2`；名称行落在稳定 row 3/4/5。
- 缺读 Portal Key 时，Power Cube/XMP 仍保持 row 4/5；x1→x2 不复制 drop，新数量需两票，数量下降不确认。
- 地图文字、单独的 Portal Key 名称、AP、命令、普通文本、未知物品、歧义配对和低置信均不产生物品。

### 生产接入试验及撤回原因

随后曾以同一 `HeaderOcrProcessor` 单 lane、最多 2 Hz 的 full-frame OCR 做接入试验；这些 app capture/OCR 改动**已撤回，不在最终生产代码中**。试验只用于判断真实证据是否足够，原始 privacy-safe 解析结果保留在：

- `app/build/outputs/analysis/floating-gear-task49-probe2/`：4 次 floating read，最终只有 Portal Key x2 得到两票，`partial=true`。
- `app/build/outputs/analysis/floating-gear-task49-probe3/`：可见顺序编号导致缺卡后 slot 冲突，最终 `entries=[]`；该证据促成了上述绝对基线回归。
- `app/build/outputs/analysis/floating-gear-task49-probe4/`：修正绝对基线后，20.533677 秒一次完整读出三项 x1（row 6/8/10，`unreadableRows=0`），21.099155 秒读出 Portal Key/XMP x2 但缺 Power Cube，21.665455 和 22.543577 秒均为 `unknown`。最终没有任何一项获得同数量两票，`entries=[]`、`recognizedQuantity=0`、`partial=true`，关联为 `orderedWithoutBonus`，关闭为 `panelLeft`。

probe2–4 的旧仪器仍断言 task-48 的 `NotRead`，所以在已经产生部分/空 Read 时按预期失败；这些失败不能被写成回放通过，也不能把单次 OCR 条目当成已确认 gear。完整三项 x1 的单次读取和跨帧出现的 x2 片段仍不足以满足生产确认要求。因此最终没有保留 full-frame 触发、频率改变或 app 业务接入，独立 reader/Tracker 测试保留为可复现的保守证据，最终生产结果仍以 `regression-gear-task49` 的实际 `NotRead` 为准。

## 真实回归与复现

仪器默认读取 app 私有 external files 下的 `real-recording.mp4`。视频不存在时回放测试明确 `Assume` 跳过；内置表测试不需要视频。真实回放启用后断言：

1. 解码帧数和 admitted 帧数有效；
2. 至少一次完整 11 节点校准；
3. 三个展示槽按顺序全部确认为 `nourish / xm / portal`；
4. 真实倒计时进入 `INPUT`；
5. 生产 OCR 至少产生 command 和 input 证据；
6. 内置 `TableController` 的三条形状和完整序列存在；
7. `analysisGear=1` 时有效 gear lane、446 条逐帧检测记录和三个尾部诊断均实际执行，且尾部文本包含 Portal Key、Power Cube、XMP 能量炮；
8. engine 在 summary 前关闭，等待中的 hackID=1 最终产生真实的 `NotRead/captureStopped` gear 附件；测试不把 `drops=[]` 断言成识别成功。

复现命令（工作目录为仓库根）：

```powershell
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest
adb push 'C:\Users\YuanChu\Downloads\Screenrecorder-20261108-093906.mp4' /sdcard/Android/data/org.szres.glyphlens/files/real-recording.mp4
adb shell am instrument -w -r `
  -e class org.szres.glyphlens.analysis.RealRecordingAnalysisInstrumentedTest `
  -e analysisRun regression-gear-task49 -e analysisGear 1 `
  org.szres.glyphlens.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/org.szres.glyphlens/files/analysis/regression-gear-task49 app/build/outputs/analysis/
```

只跑不访问录屏的内置表回归：

```powershell
adb shell am instrument -w -r `
  -e class 'org.szres.glyphlens.analysis.RealRecordingAnalysisInstrumentedTest#builtInTablesContainTheRecordedSequence' `
  org.szres.glyphlens.test/androidx.test.runner.AndroidJUnitRunner
```

## 实际验证结果

- baseline 真实回放：`OK (1 test)`，279.947 秒；446 admitted / 2732 decoded。
- 旧 `regression-gear` 真实回放 + 内置表测试：`OK (2 tests)`，310.045 秒；它保留了关闭前 summary 的历史证据。
- 最终 `regression-gear-task48` 真实回放 + 内置表测试：`OK (2 tests)`，212.196 秒；446 admitted / 2732 decoded。除序列、11 节点、`INPUT`、command/input OCR 与内置表外，尾部三类道具诊断、446 帧 gear 检查和最终 `NotRead/captureStopped` 断言均实际通过。
- 最终强制构建：`:app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug --rerun-tasks` 为 `BUILD SUCCESSFUL`（42 秒，91 个 actionable tasks 全部执行）。app JVM 结果为 49 个 suite、175 tests，0 failure/error/skip；lint HTML/SARIF 均生成。
- task-49 独立识别/解析回归与最终生产回放：`FloatingGearDropReaderTest` 6 项通过；完整 recognition 457 tests（0 failure/error，4 个既有 private-corpus skip）+ app 175 tests（0 failure/error/skip），合计 632。最终 `regression-gear-task49` 为 `OK (2 tests)`，188.082 秒，446 admitted / 2732 decoded；`gearSubmitted=0`、`gearCompleted=0`、446/446 标准 bounds=null，最终仍为 hackID=1 的 `NotRead/captureStopped`、`drops=[]`。这验证撤回后没有默认 floating OCR 或半接入输出。
- task-49 最终指定构建：`:app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug` 为 `BUILD SUCCESSFUL`（20 秒，91 个 actionable tasks，17 executed / 74 up-to-date），另 `:recognition:test` 成功；lint HTML/SARIF 均生成。
- 构建保留项目既有 Kotlin/deprecation 警告；本任务没有新增编译错误或修改无关警告。

## task-1：133013 新录屏与 RGB_565

### 输入与首次真实错误

- 原始文件：`C:\Users\YuanChu\Downloads\Screenrecorder-20261108-133013.mp4`，只读复制到设备；SHA-256=`F0ACA5DC80AFF0FB673ACDE4B0B2C4FDD1AAE999ACAF49E38F7B75C8CC06C022`。
- H.264 High / yuv420p / BT.601 limited，1216×2688，3873 帧；视频流 32.884367 秒，容器 33.000583 秒。音频未分析。
- 设备为 `127.0.0.1:7555`、Android 15/API 35/x86_64。首次生产回放在 `AndroidBenchmarkFrameSource.next:201` 失败：`The producer output buffer format 0x4 doesn't match the ImageReader's configured buffer format 0x23.`
- logcat 显示 `OMX.qcom.video.decoder.avc` 经 `SoftwareRenderer` 输出 RGB_565；其公布的 codec colorFormats 仍只有 YUV420，所以不能靠能力列表或型号预判。

### 通用 RGB_565 修复

`AndroidBenchmarkFrameSource` 默认仍创建 `YUV_420_888 (0x23) ImageReader`。只有在尚未取得/交付任何图像时，异常文本同时明确给出 producer=`0x4`、ImageReader=`0x23`，才关闭并从头重开 extractor/decoder 为 `RGB_565 (0x4)`；其他异常不触发回退，已开始消费后也不重开，避免重复帧。没有型号、品牌或分辨率分支。

RGB565 转换读取单 plane 的 native-endian 16 位像素，以 bit replication 展开为 RGBA8888，alpha=255；与原 YUV 路径一样遵守 crop、rowStride、pixelStride 和最近邻不放大缩放。原 YUV 三 plane、BT.601/709/2020 与 limited/full range 逻辑未改。`summary.json` 以 `readerFormat=0x4` 记录本次实际路径。

### 新片 Glyph 与生产状态

最终解码为 603×1333，3873 decoded / 639 admitted。真实 `GlyphCaptureEngine` 证据为 11/11 节点、五槽 centers≈0.180/0.340/0.500/0.659/0.819，依次确认：

| Glyph | 首个 direct exact（秒） | 首槽 CONFIRMED（秒） | 内置形状 |
| --- | ---: | ---: | --- |
| `question` | 11.185022 | 11.592000 | `0436` |
| `less` | 11.592000 | 11.999533 | `354` |
| `forget` | 11.999533 | 12.413588 | `68` |
| `all` | 12.413588 | 12.775777 | `018a920` |
| `lie` | 12.775777 | 13.251077 | `547536` |

13.251077 秒五槽全部 `CONFIRMED`，样本数 6/5/5/5/6；13.815922 秒进入 `INPUT`。`glyph.csv:331` 含完整序列 `question,less,forget,all,lie`。生产 OCR 同时具有 command 和 input 证据，不是仅凭单次 OCR 或内置表推断识别成功。

### 结果：阶段中心边界已修复，真实两票不足仍是 provisional

`result-ocr.jsonl` 保存了每次生产 `ResultFrameEvidence` 及其原始行。它解释了新片奖励数字的变化，而不是把同一画面 10% 误读成 30%：

- QUESTION/LESS 阶段为 hacking=10%、speed=0%；
- FORGET/ALL 阶段为 hacking=20%、speed=0%；
- 两次 LIE 阶段为 hacking=30%、speed=0%。

真实 `device-133013-evidence2/result-ocr.jsonl` 的末次清晰 LIE 行为 `y=0.2273068267`、`height=0.0375093773`，行中心 `y=0.2460615154`，超过旧 stage-name band 的 0.24 上限；其他真实阶段行中心也约为 0.238–0.249，而 hacking label 区从 0.30 开始。task-4 因此只把 Android `ResultTextParser.stageNameBand` 改为 **0.18..0.28**，保留标签/数值 band、中文行置信度下限 0.3、`ResultObservationSession`、`HackResultTracker` 的两票/100ms/最大间隔和 complete 条件，没有按机型或 glyph 名分支。

同一设备、同一原片的 `device-133013-stage-band` 定向回放为 `OK (2 tests)`，69.548 秒，3873 decoded / 639 admitted。修复在真实输出中生效：QUESTION/LESS/FORGET/ALL/LIE 分别得到 stageIndex=0/1/2/3/4；24.055488 秒的 LIE confidence=0.740885，stageIndex=4，hacking=30%、speed=0%。证据保存在 `app/build/outputs/analysis/device-133013-stage-band/`，并有同名 tar。

但最终 `HackResultSummary` 仍为 **`partial / closed=true / panelLeft`，hacking=30% / final=false，speed=0% / final=false**，`inputSeconds=7.766855`。23.847955 秒的前置 `LIE、` confidence=0.248046875，仍被置信度门槛排除而 stage=null；24.055488 秒只有唯一可靠最终阶段一票，不能确认 final bonus。五槽 game outcome 也仍全是 unknown，validator check 依次为 `UNAVAILABLE / DIFFERS / MATCHED / DIFFERS / null`。因此本轮确认的是“跨旧边界的真实阶段现在可读”，不是“真实结果已 final/complete”，更没有为追求完成放宽置信度或 tracker。

`ResultTextParserTest` 的 7 项测试包含上述真实行、跨旧 0.24 边界、非阶段文本、超出新上限、低置信排除、两票 final 语义，以及“低置信前票 + 一次清晰 LIE 仍不 final”的历史顺序。修复前新增的阶段/两票测试均失败，最小 band 修复后通过；完整 recognition 为 461 tests（0 failure/error，4 个既有 private-corpus skip），app JVM 为 177 tests（0 failure/error/skip）。

### Gear：portal 完整，bonus 两个 XMP 仍未确认

639 个 admitted 帧均有 gear 检测记录，58 帧有 bounds。生产 gear OCR 和真实 PNG 交叉证明：

- portal panel：`partial=false`，`L8 XMP Burster x1`、`L8 Resonator x2`，`unreadableRows=0`；
- bonus panel 实际可见 `L8 XMP x2`、`L7 XMP x1`、`L8 Resonator x1`，但生产在各轮最多只确认 Resonator x1，最终轮甚至为零项，始终 `partial=true`；
- 最终 `device-133013-regression` 附件关联 hackID=1，`Read / ordered / panelLeft`，`recognizedQuantity=3`、`unidentifiedQuantity=0`、`panelsReadInFull=false`，即 portal 三项完整、bonus `entries=[] / partial=true`。其他完整轮回曾确认 bonus 共振器 x1、使总数为 4；这是异步 OCR 确认票的波动，不能写死为全部七项或固定四项。`drops=[]` 是因为这些物品进入已关联的 gearAttachment，不表示没有掉落。

修复前的 `gear-raw-ocr.jsonl` 显示，四次 bonus whole-panel OCR 都把左右两列 XMP 合并成一条横跨约 84% 帧宽的文本，例如 `L8x2 XMP 能量炮 L7x1 XMP 能量炮`。`GearDropTextParser.position` 对跨列行返回 null，后续 row crop 只恢复到共振器。因此两个 bonus XMP 是明确的未确认项，不是 OCR 已读且保存成功。

曾试验仅在完整 element boxes、左右各自唯一 xN、无元素跨中线时拆分的保守适配，并用严格 `recognizedQuantity=7 + panelsReadInFull=true` 断言验证。真实运行仍未触发有效拆分，最终只有 4/7，断言在 `RealRecordingAnalysisInstrumentedTest.kt:564` 失败。该生产试验及专用测试已撤回，没有把失败改成通过，也没有修改白名单、parser 阈值或机型参数。另一轮把 bonus 数量写死为 1（总数 4）的断言也因实际只有 portal 三项而失败，故最终回归不把 partial 数量伪装成固定事实。跨列合并行的通用支持仍未完成。

### task-3 文本候选拆行：恢复两个 XMP，但 6/7 未通过并已撤回

本轮改用 `device-133013-regression/gear-raw-ocr.jsonl` 的真实文本，不再依赖 element boxes。试验候选只处理几何上横跨 x=0.5 的行，枚举去重后的文本边界，并要求边界唯一、左右分别通过现有 `GearDropTextParser.parseEntry` 的合法数量、非 unreadable 等级和白名单；为避免非英语前导词规则把“左 XMP / 右能量炮+等级”当成另一组合法候选，拆分支额外要求完整白名单名称。原 `ResultTextLine` 的 confidence 和 box 不变，unknown 不变成物品，74/80、坐标、1.2 秒确认窗和机型参数均未改。

定向测试使用了 29.979022 秒的 `AL8x2 XMP 能量炮 2L7x1XMP能量炮` 和 30.534177 秒的 `L8x2 XMP 能量炮 2L7x1XMP能量炮` 及其真实 confidence/box；未知物品、L9、额外数量、三个物品、仅前导词 XMP、低置信和不跨列几何均拒绝。试验版 `GearDropTextParserTest` 为 12/12 通过，但这只证明离线候选，不能代替生产确认。

同一设备、同一 133013 原片的严格运行 `task-3-merged-text-probe1` 使用 `analysisExpectedGearQuantity=7` 和 `analysisExpectedGearFull=1`。仪器在 70.946 秒后以 `Expected gear quantity 7 (full=true)` 失败；summary 记录 3873 decoded / 639 admitted、RGB_565、gear submitted=8 / completed=7。真实 `gear-ocr.jsonl` 证明拆分确实生效，而不是“没有触发”：

- 29.256955 和 30.583966 秒均得到 `L8 XMP x2`、`L7 XMP x1`、`L8 Resonator x1`，`unreadableRows=0`；中间 29.722022 / 30.185455 秒得到两个 XMP，共振器仍未读。
- 两个 XMP 获得确认票；共振器两次完整证据相隔 **1.327011 秒**，超过现有 1.2 秒最大确认间隔，因此没有两票。没有为让它通过而放宽 tracker 或确认窗。
- 最终附件仍正确关联 hackID=1、`ordered / panelLeft`，portal 三项完整；bonus 只确认两个 XMP，`partial=true`。合计 `recognizedQuantity=6`、`unidentifiedQuantity=0`、`panelsReadInFull=false`，不是 7/7，也不是完整面板。

按任务约定，未证明的生产拆分和 `GameText` 试验参数已经全部撤回，最终生产代码不拆分跨列行。当前 `GearDropTextParserTest` 保留真实 AL/L 行和上述反例，断言没有已证明拆分时仍保持 unread，撤回后为 11/11 通过；最终完整 `:recognition:test` 为 461 tests、0 failure/error、4 个既有 private-corpus skip。试验版源码及 12 项 XML 保存在 `app/build/outputs/analysis/task-3-merged-text-probe1/attempted-source/` 和 `parser-tests-12.xml`，仅作历史证据，不在生产源码中。完整失败日志、summary、原始 OCR、gear evidence 和 PNG 均保留在同目录；后续若研究提高未决 gear OCR 的采样频率，必须另行验证，不能把本轮写成跨列合并行已通用支持。

### task-5 未决 gear 0.5 秒采样试验：仍未形成两票，已撤回

本轮精确恢复 task-3 保存的保守文本拆行候选；恢复后 `GearDropTextParser.kt` 和 `GameText.kt` 与 `task-3-merged-text-probe1/attempted-source/` 的 SHA-256 分别逐文件一致。唯一调度试验是 `GameTextSchedule` 三次未读后的 `gearBackedOff` 间隔由 **1.0 秒降为 0.5 秒**。常规 gearInterval 0.2 秒和既有证据保存 0.5 秒预算未改；tracker 两票、1.2 秒最大间隔、白名单、置信度、74/80、坐标及机型参数均未改。

设备回放前先运行 `GearDropTextParserTest`、`GameTextScheduleTest` 和 `GameHeaderReaderTest`：试验态分别为 12/12、15/15、21/21，通过合计 **48 tests，0 failure/error/skip**。XML 和试验源码保存在 `app/build/outputs/analysis/task-5-gear-cadence/parser-schedule-tests-attempted/` 与 `attempted-source/`。

随后在 `127.0.0.1:7555` 重跑同一 133013 原片；宿主与设备副本 SHA-256 均为 `F0ACA5DC80AFF0FB673ACDE4B0B2C4FDD1AAE999ACAF49E38F7B75C8CC06C022`。严格运行 `task-5-gear-cadence` 使用 `analysisExpectedGearQuantity=7`、`analysisExpectedGearFull=1`，在 **70.248 秒**后以 `Expected gear quantity 7 (full=true)` 失败，不能把本轮记为通过。summary 记录 3873 decoded / 639 admitted、RGB_565、gear submitted=7 / completed=7、raw OCR passes=23。

真实结果没有满足保留门槛：

- 29.562655 和 30.794133 秒的 bonus evidence 各含一次 `L8 Resonator x1`，相隔 **1.231478 秒**，仍超过既有 1.2 秒确认窗，因此 Resonator 没有两票；本轮没有改确认窗。
- 两个 XMP 只在 29.927977 秒共同得到有效拆分；其他 bonus 原始行出现 `XMEP`、`能暈炮`、`x-` 等真实 OCR 变化，不能靠白名单外猜测补齐。
- 最终附件仍关联 hackID=1、`ordered / panelLeft`；portal `partial=false`，但只有 XMP x1 被识别，另一个数量为 2 的条目仍 unidentified；bonus `entries=[] / partial=true`。合计 **`recognizedQuantity=1`、`unidentifiedQuantity=2`、`panelsReadInFull=false`**，不是 7/7。这一数量低于 task-3 的 6/7，反映单 lane 异步 OCR 的实际波动，不能选取历史较好一轮冒充本轮结果。

按任务约定，生产拆行、`GameText` 的 `allowLeadingWords` 试验参数和 0.5 秒 backoff **全部撤回**，最终生产代码恢复三次未读后的 1.0 秒间隔且不拆跨列行。撤回后同三组定向测试恢复为 11/11、14/14、21/21，通过合计 **46 tests，0 failure/error/skip**；XML 保存在 `parser-schedule-tests-withdrawn/`。完整失败日志、summary、gear evidence、原始 OCR、逐帧记录和 PNG 均保留在 `app/build/outputs/analysis/task-5-gear-cadence/`，设备原始目录另有 `task-5-gear-cadence-device.tar`。本试验只证明这次 backoff 调整没有达到严格业务门槛，不证明其他采样策略有效，也不宣称跨列合并行已通用支持。

撤回态最终执行 `:recognition:test :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug --rerun-tasks` 为 **BUILD SUCCESSFUL**（29 秒，93 个 actionable tasks 全部执行）。recognition 为 63 suites / 461 tests（0 failure/error，4 个既有 private-corpus skip），app JVM 为 49 suites / 177 tests（0 failure/error/skip）；lint HTML/SARIF 均生成。汇总保存在 `app/build/outputs/analysis/task-5-gear-cadence/final-verification.txt`。

### task-6 调度核对：不存在可单独调整的 unsettledInterval

继续检查生产实现后，确认 Android 的 `GameTextSchedule` 常规 gear 间隔为 `gearInterval=0.2s`，连续三次未读后的 `gearBackedOff` 间隔为 `max(1.0, gearInterval)`；`GearEvidenceSchedule.minimumInterval=0.5s` 只控制证据图片入队，不是 OCR 请求节流。源码注释中的 unsettled/steady 节奏没有对应的独立生产参数。因此，不能再以“把 unsettledInterval 改为 0.5s”为名义做无依据的采样修复；此前实际 backoff 试验已经失败并撤回。当前严格 gear 结论仍是 portal 完整、bonus partial，未证明跨列合并行的通用完整恢复。

### 复现与实际验证

新片参数只作用于仪器期望和诊断时间点，不进入生产识别。当前设备默认使用 `question/less/forget/all/lie`；显式 `analysisRun=baseline`、`regression-gear-task47` 或 `regression-gear-task48` 才使用旧 `nourish/xm/portal`。`analysisGlyphs` 保留为其他命名运行的显式覆盖项，不属于生产识别参数。旧尾部 OCR 和 `NotRead` 断言保持兼容；`analysisInternal=1` 仅把证据写入 app 内部 files，绕过此设备 shell/run-as 对 external-files 的读取限制。

在 `127.0.0.1:7555` 上复现包含真实回放的全量 84 项仪器测试（不要用过滤参数替换 `:app:connectedDebugAndroidTest`）：

```powershell
# connectedAndroidTest 结束会卸载 app；若默认 external-files 视频已被移除，先重新部署。
.\gradlew.bat :app:installDebug
adb -s 127.0.0.1:7555 shell mkdir -p /sdcard/Android/data/org.szres.glyphlens/files
adb -s 127.0.0.1:7555 push 'C:\Users\YuanChu\Downloads\Screenrecorder-20261108-133013.mp4' /sdcard/Android/data/org.szres.glyphlens/files/real-recording.mp4
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.analysisRun=regression-gear' `
  '-Pandroid.testInstrumentationRunnerArguments.analysisGear=1' `
  '-Pandroid.testInstrumentationRunnerArguments.analysisInternal=1' `
  '-Pandroid.testInstrumentationRunnerArguments.analysisTailTimes=24.5,26.5,28.5' `
  '-Pandroid.testInstrumentationRunnerArguments.analysisTailRequired=XMP,共振器' `
  '-Pandroid.testInstrumentationRunnerArguments.analysisExpectedGear=read' `
  '-Pandroid.testInstrumentationRunnerArguments.analysisRawGear=1'
```

Gradle XML 保留在 `app/build/outputs/androidTest-results/connected/debug/TEST-NCO-AL00 - 15.xml`；其中 `testsuites` 必须报告 `tests="84" failures="0" errors="0" skipped="0"`，不能用单个分析类的 `OK (2 tests)` 代替全量结果，也不能把视频缺失导致的 `Assume` 跳过当成真实回放通过。

以下是此前 `device-133013-regression` 命名运行的定向复现命令，显式覆盖同样的五 Glyph 期望：

```powershell
.\gradlew.bat :app:installDebug :app:installDebugAndroidTest
adb -s 127.0.0.1:7555 shell am instrument -w -r `
  -e class org.szres.glyphlens.analysis.RealRecordingAnalysisInstrumentedTest `
  -e analysisRun device-133013-regression -e analysisGear 1 -e analysisInternal 1 `
  -e analysisTailTimes 24.5,26.5,28.5 `
  -e analysisGlyphs question,less,forget,all,lie `
  -e analysisTailRequired XMP,共振器 `
  -e analysisExpectedGear read -e analysisRawGear 1 `
  org.szres.glyphlens.test/androidx.test.runner.AndroidJUnitRunner
```

主要宿主证据在 `app/build/outputs/analysis/device-133013-evidence2/`、`device-133013-evidence3/` 和 `device-133013-regression/`：summary、逐帧 Glyph/gear JSONL、生产 result/gear evidence、原始 gear OCR 和关键 PNG。`device-133013-evidence4/` 保留撤回的七项严格失败试验；`device-133013-final/` 保留写死总数 4 的失败期望。它们都不能当作最终通过。

- RGB565 定向 JVM：`RecordingBenchmarkTest` 11 tests，0 failure/error/skip；覆盖实际异常文本、位展开、crop/stride/缩放。
- 同设备真实 MediaCodec 小样本：`realTemporaryMovieDecodesAndRunsWithoutCopyingOrWritingOutputs` 为 `OK (1 test)`，0.564 秒；8/8 帧、首像素颜色、SAF descriptor 和生产 runner 均通过。
- 参数化生产回归 `device-133013-evidence2`：`OK (2 tests)`，75.563 秒；`device-133013-evidence3` 加原始 gear OCR：`OK (2 tests)`，81.106 秒。
- 撤回未证明的拆行试验后，最终 `device-133013-regression`：`OK (2 tests)`，76.932 秒；3873 decoded / 639 admitted、readerFormat=0x4。最终 result=provisional 30%/0%、partial；gear recognizedQuantity=3、portal 三项完整、bonus 空且 partial。
- task-2 按 `analysisRun` 固定预期后，重新部署 133013 原片并执行上述全量命令：`:app:connectedDebugAndroidTest` 为 `BUILD SUCCESSFUL`（1 分 39 秒），XML 根节点为 `tests="84" failures="0" errors="0" skipped="0"`。其中真实回放 77.912 秒、内置表 0.016 秒，两者均通过且没有 `Assume` 跳过。最终 XML 同时复制为 `app/build/outputs/androidTest-results/task-2-regression-gear-84.xml`。
- 最终构建 `:recognition:test :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug :app:lintDebug` 为 `BUILD SUCCESSFUL`；app 49 suites/177 tests（0 failure/error/skip），recognition 457 tests（0 failure/error，4 个既有 private-corpus skip）。lint HTML/SARIF 均生成。
- task-4 阶段边界修复后的同一最终构建命令再次 `BUILD SUCCESSFUL`（17 秒，93 个 actionable tasks，11 executed / 82 up-to-date），lint HTML/SARIF 均生成；随后对最终源码执行 `:recognition:test --rerun-tasks` 也为 `BUILD SUCCESSFUL`（9 秒），确认 recognition 461 tests（0 failure/error，4 个既有 skip），app 49 suites/177 tests（0 failure/error/skip）。`device-133013-stage-band` 真实回放为 `OK (2 tests)`、69.548 秒；stage=4 已读出，但最终仍一票不足、30%/0% 均 final=false、partial。

本轮没有验证另一台只输出 YUV 的设备、解码中途动态换格式、标准结果 final-stage 两票完成、bonus 两个 XMP 的跨列合并行通用恢复，或 Room/导出持久化。RGB565 的首次错误及新片生产回放已验证，但不能据此宣称所有 decoder、结果页或 gear 布局都已支持。

## 尚未证明 / 不应过度结论

- 没有证据表明用户设备上“看不到 Glyph”由这份录屏的通用生产识别算法造成。若实际设备未加载/未启用内置表、语言选择不同、悬浮窗被遮挡、采集方向/stride 不同或使用的是其他构建，需要该设备的 `TableController` 状态、capture evidence 和悬浮窗快照，不能从本视频推断。
- 本任务没有改 CSV 导入，也没有验证 CSV 导入问题。
- 未验证真实手机的悬浮窗可见性、MediaProjection 授权、实时 OCR 延迟、耗电、120 Hz 原始输入或不同厂商屏幕特效。
- 未验证标准结果页的 hacking/speed bonus；此录屏未展示足够证据。
- 未验证标准 gear summary-panel 的 OCR、逐位置两帧确认、物品/等级/数量/红字、panel/group 闭合和 FIFO 关联。尾部大区域诊断 OCR 只证明文字可见，不能代替这些断言。
- 未验证这份浮动动画卡布局的通用生产支持；当前没有 app 接入或默认 full-frame OCR 触发。独立 reader 能从真实行组合三项，但生产回放没有给三项 x2 的同数量两票，不能把单次/跨帧片段当成确认结果，也没有把 `NotRead` 或 `drops=[]` 当成“实际没有掉落”。
- 本轮验证的是生产回调，不是 Room/导出持久化；关闭后的 `NotRead` 附件如何入库未在此仪器测试中断言。
- iOS 成功样本语义没有被修改；task-4 只调整 Android `ResultTextParser.stageNameBand`，没有改 iOS 对应实现、gear 或其他业务阈值。现有 JVM 单测用于固定这一最小几何修复及其保守投票语义。
