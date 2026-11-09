# Glyph Lens Android 0.4.3 实施计划

更新日期：2026-11-09。

**0.4.3 已将全流程改为纯特征识别，移除全部 OCR。** 阶段由槽位高亮变迁、点阵和输入计时条驱动，预测、CSV 与地图五目标保持。生产源码 `6fea790` 的 11 项重点 JVM 测试、2 项正式双轮流程及 10 张关键图人工核对通过；本地 APK 417,845 bytes，较 0.4.2 小 97.36%，没有 OCR 依赖、模型或原生库。实际增量范围与交付入口见 [FEATURE_ONLY.md](Docs/FEATURE_ONLY.md)。

## 1. 目标与本轮顺序

工程继续位于 `C:\Users\YuanChu\Dynamic-Glyph-Android`，源 iOS 参考为 `C:\Users\YuanChu\Dynamic-Glyph`，不修改源 iOS 目录。沿用 Kotlin、Views/Canvas 和已完成的 iOS Glyph 识别/预测迁移；用通用图像几何处理设备差异，不引入机型特例。

1. 将阶段入口从文字读取改为槽位高亮变迁、点阵与输入计时条的特征组合和顺序，沿用原有状态追踪结构。
2. 移除全部 OCR 调用、ML Kit 依赖、Latin/中文/日文/韩文模型、游戏语言设置和仅服务于 OCR 的调度/保留规则；不留下 OCR 兜底或替代模型。
3. 保持形状匹配、跨帧确认、输入冻结、序列预测、CSV 及地图五目标策略，改动只围绕去 OCR 所需衔接。
4. 实现、相关测试与文档落盘后，只跑受影响的视觉 tracker、生产像素 pipeline/engine JVM 用例，以及现有正式 APK runner 两项（实时遮标题五图两轮、省电新三图两轮），各图实际看截图。不重复跑 Debug 设备流程、全单元、Lint 或多尺寸矩阵。
5. 使用已有构建方式查看本地 APK 大小及 OCR 依赖/模型是否移除。不做旧 CPU 对照，不发布新降幅，不上传/下载 APK 验 SHA，不引入新工具或流程。
6. 生产 `6fea790` 已推原仓库 main，v0.4.3 标签指向该提交，[Latest Release](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.3) 与 [APK](https://github.com/vhu231/Dynamic-Glyph-Android/releases/download/v0.4.3/Glyph-Lens-0.4.3-arm64.apk) 已发布/上传；目标同步沿用现有流程，不新增资产验证步骤。0.4.2 交付记录保留为历史。

## 2. 强制通用规则

以下为用户原始要求，适用于全部实现、重构、测试及评审：

- 不要为猜测出来、几乎不会发生的竞态或安全边界情况加复杂度。真觉得要加，先说清楚具体会怎么出错、概率多大、要多写多少代码，我同意了再做。不要悄悄扩大改动范围。
- 外部传进来的数据，只在入口那一层校验一次。往里传已经确定类型的值，内部不要反复做防御性检查。
- 不要提前抽只用一次的函数。只有会复用、包住了确实复杂的逻辑，或者它代表一个能起清楚名字的概念时才抽。
- 主函数只写正常流程，校验和细节放到它下面的小函数里。
- 注释只写不明显的约束和反直觉的行为，不给显而易见的代码写注释。
- 自动生成的文件不要手改，改源头后重新生成。
- 测试测真实实现，不要把业务逻辑抄一份进测试。尽量少用 mock。
- 按仓库现有的流程走，不要引入仓库没在用的工具或流程。

CSV、系统图像缓冲区和文件读取在各自入口解析与校验，内部传递明确类型。识别不确定、缺槽和尚未确认是正常状态。不为单一实现增加工厂、仓库层或通用框架；源码、资源与构建配置改变后重新生成产物，不手改生成文件。

## 3. 当前范围

| 项目 | 0.4.3 要求 |
| --- | --- |
| 版本 | versionName 0.4.3 / versionCode 7 |
| 平台 | 原生 Kotlin + Android Views/Canvas；交付目标 Android 10 以上、arm64、竖屏整屏共享 |
| 阶段判断 | 槽位高亮及变化、点阵与输入计时条，完全不读取文字 |
| Glyph 核心 | 11 节点标定、固定采样、形状匹配、稳定确认、槽位顺序保持 |
| 输入保护 | 实际输入像素驱动冻结，玩家笔画不能改写展示序列 |
| 预测与 CSV | 连续确认前缀、总槽位数与原序列表；唯一/歧义/重复/缺槽规则不重写，CSV 内容及导入保持 |
| 地图隐藏 | 菱形/指南针、信号弧、叠层、方框加号、公文包五目标独立检测，任一命中按选项隐藏，不发 COMPLETED |
| 界面 | 实时/省电、可选返回地图后自动隐藏、画布/通知/开始停止/表导入保持；删除游戏语言设置 |
| 删除 | ML Kit、四类模型、阶段 OCR、标题请求及只服务于 OCR 的依赖/保留规则 |
| 构建 | 现有 Gradle/R8 增量构建通过；本地包 417,845 bytes，无 OCR 内容或原生库，保留 NDK arm64 配置，不添加空库强制 ABI |

不加入 OpenCV、替代 OCR、额外推理模型、跨平台壳或新的抽象框架。已删除的自动停止、结算/分数、掉落、命令业务、输入正确性判断、历史统计、数据库、云同步、录制/音频、诊断导出与岛接入继续不在范围中。

## 4. 识别与窗口行为

阶段不依赖标题文字或语言，从槽位高亮变化、点阵与输入计时条的组合和先后关系判断。保持真实槽位确认与预测边界；点阵暂时消失不单独代表成功完成或新轮。输入阶段冻结确认序列，新展示特征出现后恢复下一轮，不能等待已经删除的 OCR 结果。

实时模式授权后自动后台识别，初始无图标/窗口；省电模式图标点击后开始。已确认前缀加唯一预测覆盖整组，或全部真实确认后，按现有规则显示；预测仍区别于真实确认。

返回地图后自动隐藏保持可选。开启时只保留可拖动画布，五个目标任一命中就隐藏，不要求先进入 INPUT/frozen。地图上通知恢复窗口后仍会按正向目标再次隐藏。关闭选项时，实时 × 隐藏并继续识别，省电 × 停止回图标；明确停止释放共享。地图信号不修改 tracker、槽位或预测，不当作游戏成功 COMPLETED。

本轮不改变地图模板、603 宽采集路径和已有通用适配方式，也不借去 OCR 重写序列表或预测算法。原始 CSV 换行与内容保持，不手改生成文件。

## 5. 增量验收与交付

**本轮受影响重点验收已完成，沿用已有工具和流程。** 8 项 Tracker、1 项 SessionPipeline、2 项 GlyphCaptureEngine 共 11/11 通过，无跳过失败；一次增量构建 1m4s。正式 APK 的实时遮标题五图两轮、省电新三图两轮 2/2 通过，实际查看 10 张关键图确认结果。证据见 [FEATURE_ONLY.md](Docs/FEATURE_ONLY.md#3-本轮增量验收)。

- [x] 受影响 tracker/pipeline/engine 的 11 项实际实现测试通过，特征驱动阶段与下一轮。
- [x] 正式 runner 两项双轮通过；五图第 4、5 项正确，确认/预测样式保持；新三图第三项确认前不提前显示，确认后显示正确整组。
- [x] 输入不污染，同授权下一轮继续识别；两模式两轮回地图隐藏、无 × 行，fullStop 为 true。
- [x] 实际核对本轮 10 张关键截图，不将 UI 动作断言或少量截图说成全视频逐帧检查。
- [x] 本地 0.4.3/code7 APK 417,845 bytes，较旧包小 97.36%；无 OCR 依赖/资产及任何原生库，保留 NDK arm64 配置、未加空库。

本轮没有跑重复 Debug 设备流程、全单元、主动完整 Lint、多尺寸矩阵或 CPU 对比；Release 自带 lintVital 随构建执行，没有上传/下载 APK 算 SHA。未发布新的 CPU 降幅。旧矩阵不继承为新版本认证，增量回归也不等于所有设备、横屏、分屏或单应用共享均通过。

## 6. 保留的 0.4.2 发布结果

0.4.2 已完成原仓库 main、[正式 Release](https://github.com/vhu231/Dynamic-Glyph-Android/releases/tag/v0.4.2) 和 [szres/dynamic-glyph-android](https://github.com/szres/dynamic-glyph-android) main 同步。目标保留自身既有历史，核验时两边源码树一致，未使用 force push。标签为 `6a2ef623497e7cc03d476e3c569f6fc54f7f7cb9`，APK 内嵌生产提交为 `6fd03bc9d4eab6c57bc1f2f21a0e60d938621422`。

此前发布的 [0.4.2 arm64 APK](https://github.com/vhu231/Dynamic-Glyph-Android/releases/download/v0.4.2/Glyph-Lens-0.4.2-arm64.apk) 为 15,824,427 bytes，SHA-256 `e5f381a0c28b8dd15bbbfc26cd131a3365a27331c1c926998b7446193e539e27`；当时重新下载与受测包、发布端 digest 一致。该已完成历史记录保留，不构成本轮重做下载校验的要求。

0.4.2 的单元/实采/正式包人工核对、CPU 36.46% 对照及范围限制完整保留在 [VISUAL_COMPLETION.md](Docs/VISUAL_COMPLETION.md)，不记为 0.4.3 的结果；本轮只使用第 5 节列出的增量证据和本地包检查。

## 7. 历史与来源

当前范围以本计划和 [FEATURE_ONLY.md](Docs/FEATURE_ONLY.md) 为准。[VISUAL_COMPLETION.md](Docs/VISUAL_COMPLETION.md) 是 0.4.2 已发布证据；[REALTIME_VISIBILITY.md](Docs/REALTIME_VISIBILITY.md)、[CALIBRATED_VISION.md](Docs/CALIBRATED_VISION.md)、[POWER_MODES.md](Docs/POWER_MODES.md) 与 [GLYPH_ONLY.md](Docs/GLYPH_ONLY.md) 保留更早版本记录，不覆盖其原始数据。

2026-11-09 仓库清理已移除 `Apps/`、`Packages/`、`Config/`、iOS 脚本、私有素材子模块及过时平台文档。`Tools/MapControls/` 保留为 Android 模板生成源；构建入口见 [ANDROID.md](Docs/ANDROID.md)，数据来源见 [PROVENANCE.md](Docs/PROVENANCE.md)。原始外部录屏、私有素材和构建输出不提交源码仓库；移除 OCR 的包大小变化以本地实际产物为准，不删除验证素材来宣称瘦身。
