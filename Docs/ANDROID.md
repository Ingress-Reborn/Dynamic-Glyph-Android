# Android 构建与验证

更新日期：2026-11-09。当前版本为 Glyph Lens 0.4.3，应用 ID 为 `org.szres.glyphlens`。
功能范围见 [Plan.md](../Plan.md)，纯特征实现与既有验收见 [FEATURE_ONLY.md](FEATURE_ONLY.md)。

## 工具链

| 组件 | 仓库配置 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 9.8.2 |
| Android Gradle Plugin | 9.5.0；内置 Kotlin 支持 |
| Kotlin JVM | 2.4.21 |
| compileSdk / targetSdk | 37.0 / 37 |
| minSdk | 29（Android 10） |
| AndroidX Activity / Core | 1.13.0 / 1.19.1 |

SDK 路径写入本机 `local.properties`（不提交），例如 `sdk.dir=C:/Android/Sdk`。
具体依赖以 `gradle/libs.versions.toml` 和模块构建文件为准。当前没有 OCR、ML Kit、Room 或云同步依赖。

## 构建

在仓库根目录执行；macOS/Linux 将 `gradlew.bat` 替换为 `./gradlew`：

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
需要 Release 时执行 `:app:assembleRelease`；签名与发布使用本机现有流程，不提交密钥。
默认构建输出在 `app/build/outputs/`，历史发布的重命名、签名 APK 不由仓库保存。

## 按改动验证

只运行受影响用例，例如修改 CSV 加载或资源布局后：

```powershell
.\gradlew.bat :recognition:test --tests "*BundledGlyphCorpusTest" :app:testDebugUnitTest --tests "*RealGlyphTablesTest"
```

修改采集、窗口或阶段判断时，再选择对应的 JVM 用例和现有 AndroidTest runner。
不将编译成功当作设备通过，不重复无关全套测试，也不下载已发布 APK 重算哈希。
设备测试源码在 `app/src/androidTest/`；原始视频与本机证据不随仓库分发。

## 源码与资源

`app/` 和 `recognition/` 是仅有的 Gradle 模块，无 Swift、Xcode 或子模块前置条件。
`app/src/main/assets/` 包含现有 Glyph CSV；来源见 [PROVENANCE.md](PROVENANCE.md)。
`Tools/MapControls/` 保留 Android 地图按钮特征生成器；说明见其 [README](../Tools/MapControls/README.md)。
生成器使用 Python 与 Pillow，仅重新标定模板时需要，普通 Android 构建不依赖它。

现有交付边界为 arm64、竖屏整屏共享；无原生库的 0.4.3 APK 不额外加入空库来限制 ABI。
横屏、分屏、单应用共享与全部设备组合未全面认证。
