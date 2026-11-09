# Android 参考数据与素材来源

## Glyph CSV

Android 当前内置的 `app/src/main/assets/glyph_line.csv`（形状）和 `glyph.csv`（序列）
来自 [chibatching/glyph-predictor-data](https://github.com/chibatching/glyph-predictor-data)，
原迁移记录所用版本为 `109f6c32d24c9f219e946a1423642390db6ce86b`。
本次仓库清理保留这两个文件原始字节，形状表为 LF、序列表为 CRLF；规则在 `.gitattributes`。

原项目的来源声明记录该第三方仓库未提供许可证；本项目的代码许可证不授予这些数据的权利。
旧 iOS 文档中的“表不随仓库分发”不适用于当前 Android 目录；这里明确记录已有内置资源的实际状态。

- `glyph_line.csv`：`name,shape`，节点编号为 `0`–`9` 和 `a`。
- `glyph.csv`：每行一个 Glyph 序列，最多五列。

## 图像素材

`recognition/src/test/resources/map-controls/` 保留 Android 检测器使用的裁剪、遮罩测试输入。
模板生成方法和采样边界见 [地图按钮工具说明](../Tools/MapControls/README.md)。
游戏图像及商标属于各自权利人，不由本仓库的代码许可证授权。
原始录屏、完整地图帧及本机设备验收输出不随仓库分发。

iOS 私有素材子模块已随 Swift 工程移除，Android 构建不需要执行 `git submodule update`。
