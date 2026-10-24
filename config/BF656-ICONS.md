# BF656 自定义图标

自有图标与 HMCL 原图标均位于 `HMCL/src/main/resources/assets/img/`。只替换 `bf656-` 前缀文件，原始 `icon*.png` 保留。

当前七张 PNG 是 HMCL 原图标的副本。ICO 以原始 32、64、128、256 像素 PNG 封装，暂时显示相同品牌。

| 自有文件 | 尺寸 | 用途 |
| --- | --- | --- |
| `bf656-icon.png` | 32×32 | Windows/其他桌面窗口、自有“关于”条目 |
| `bf656-icon@2x.png` | 64×64 | 高分辨率窗口图标 |
| `bf656-icon@4x.png` | 128×128 | 高分辨率窗口图标 |
| `bf656-icon@8x.png` | 256×256 | 高分辨率窗口图标 |
| `bf656-icon-title.png` | 24×24 | 标题栏 |
| `bf656-icon-title@2x.png` | 48×48 | 高清标题栏 |
| `bf656-icon-mac.png` | 512×512 | macOS 窗口与 Dock |
| `bf656-icon.ico` | 多尺寸 | Windows EXE 文件图标 |

PNG 保持透明背景、表中尺寸和原文件名。ICO 单独维护，建议包含 16、24、32、48、64、128、256 等尺寸，至少应包含适合桌面和列表显示的尺寸。更换 PNG 不会自动重新生成 ICO。

替换文件后按原有流程重新构建。Windows 上运行 `:HMCL:makeExecutables` 时，会先在 `HMCL/build/generated/bf656-native/` 生成自定义封装程序，再打入 JAR 并生成最终 EXE。资源校验和原生图标写入均自动执行，不修改依赖缓存中的原始封装程序。使用 `HMCL_LAUNCHER_EXE` 时同样只处理其构建副本。

EXE 图标写入使用 Windows 系统 API，无需额外安装图标编辑工具。非 Windows 构建保留原生封装程序原图标并输出提示；自有 PNG 仍正常打包。需要带自定义 EXE 图标的成品时请在 Windows 上构建。

“关于”页面中原始 Hello Minecraft! Launcher 条目仍使用 `icon.png`。其他 HMCL 原有资源引用保持各自用途，自有品牌不通过全局替换原资源实现。

自有路径和窗口图标逻辑集中在 `org.jackhuang.hmcl.bf656.BF656Icons`。构建接入集中在 `gradle/bf656.gradle.kts`，原生资源写入由 `gradle/bf656-icons.ps1` 完成。
