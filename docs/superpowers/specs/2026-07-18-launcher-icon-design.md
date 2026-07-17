# iSaver 启动图标设计

## 目标

将 iSaver 文件列表中现有的蓝色文件夹视觉作为应用启动图标，使桌面图标与应用内界面形成一致的品牌识别，同时正确适配 Android 11 及更高版本的启动器遮罩。

## 已确认方案

采用“浅色底蓝文件夹”方案：

- 前景沿用 `FolderGlyph` 的双层圆角文件夹结构与 `#007AFF` 品牌蓝色。
- 背景使用冷白到浅蓝的轻微渐变，避免透明背景在不同桌面壁纸和启动器主题下丢失轮廓。
- 文件夹放置在 adaptive icon 安全区内，在圆形、圆角方形和其他系统遮罩中不得裁切主体。
- 不引入文字、阴影纹理、Apple 图标资源或额外品牌元素。

## 资源结构

- `mipmap-anydpi-v26/ic_launcher.xml`：Android 8.0+ adaptive icon，组合背景和前景。
- `mipmap-anydpi-v26/ic_launcher_round.xml`：圆形启动器入口，复用相同图层。
- `drawable/ic_launcher_background.xml`：冷白到浅蓝背景。
- `drawable/ic_launcher_foreground.xml`：蓝色双层文件夹矢量前景。
- `mipmap-anydpi/ic_launcher*.xml`：基于同一矢量图层的兼容资源；项目最低 API 29，无需维护重复 PNG。
- `AndroidManifest.xml`：显式声明 `android:icon` 和 `android:roundIcon`。

## 视觉约束

- 文件夹主体保持居中，视觉中心可略向下，保留顶部文件夹标签的呼吸空间。
- 前景安全区至少覆盖标准 adaptive icon 66×66dp 安全区域，不把关键边缘放在遮罩裁切带。
- 兼容资源不使用透明外轮廓，避免 MIUI 对透明图标额外套白底后产生双重边框。
- 图标在浅色、深色桌面壁纸以及 MIUI 默认圆角方形遮罩下均保持清晰。

## 验证

- 添加资源级 Android 测试，确认应用声明的普通/圆形图标均可解析，并且 adaptive icon 前景、背景存在。
- 运行单元测试、Lint、Debug 构建与 instrumentation 测试。
- 在小米 9 `d51f42ac` Root 安装 APK，验证桌面图标、最近任务图标和应用信息页图标。
- 通过 ADB 截图检查 MIUI 实际遮罩下无裁切、无透明底异常和无旧图标缓存。

## 非目标

- 不修改应用内文件夹图形本身。
- 不添加动态图标、主题图标或通知栏图标。
- 不改变产品功能、权限、数据结构或导航。
