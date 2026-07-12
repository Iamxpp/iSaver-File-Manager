# M2 iOS Files 三标签与 Root 浏览 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Root 门禁、位置模型和 BrowserViewModel 上完成“最近项目 / 视图 / 浏览”三标签、高保真 iOS Files 列表/网格、排序、添加路径和安全新建文件夹，并在小米 9 真机完成视觉验收。

**Architecture:** 新增纯领域的显示与排序偏好模型，由 DataStore Repository 持久化；`ISaverHomeViewModel` 只编排当前标签和导航目的地，位置解析仍由 `LocationHomeViewModel` 负责，真实目录仍由 `BrowserViewModel` 负责。Compose 拆分为可独立测试的 Files 风格组件，Activity 只组装依赖和 Root Granted 后的 App Shell。

**Tech Stack:** Kotlin 2.0.21、Jetpack Compose/Material 3、Coroutines/Flow、Room、Preferences DataStore、libsu、JUnit、Robolectric、Compose UI Test、ADB/UIAutomator。

---

## File map

- Create `app/src/main/java/com/iamxpp/isaver/ui/files/FilesModels.kt`: 标签、显示模式、排序字段和方向。
- Create `app/src/main/java/com/iamxpp/isaver/data/local/BrowserPreferencesRepository.kt`: DataStore 偏好。
- Create `app/src/main/java/com/iamxpp/isaver/ui/ISaverHomeViewModel.kt`: 标签与页面导航编排。
- Create `app/src/main/java/com/iamxpp/isaver/ui/ISaverHomeScreen.kt`: 三标签 App Shell。
- Create `app/src/main/java/com/iamxpp/isaver/ui/LocationHomeScreen.kt`: “视图”页。
- Create `app/src/main/java/com/iamxpp/isaver/ui/CustomLocationDialog.kt`: 添加/编辑备注与 Root 路径。
- Create `app/src/main/java/com/iamxpp/isaver/ui/files/FilesComponents.kt`: 顶栏、搜索、列表行、网格项、底栏和菜单。
- Modify `app/src/main/java/com/iamxpp/isaver/ui/BrowserUiState.kt`: 显示/排序/选择状态。
- Modify `app/src/main/java/com/iamxpp/isaver/ui/BrowserViewModel.kt`: 根 `/`、排序与偏好事件。
- Modify `app/src/main/java/com/iamxpp/isaver/ui/BrowserScreen.kt`: 高保真列表/网格与菜单。
- Modify `app/src/main/java/com/iamxpp/isaver/MainActivity.kt`: Granted 后组装三标签。
- Modify `app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt`: Room、Store 和 Preferences 单例。

### Task 1: 显示与排序领域模型

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/ui/files/FilesModels.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/ui/files/FileEntrySorterTest.kt`

- [ ] **Step 1: 写排序 RED**

测试目录优先和 `DISPLAY_NAME/TYPE/MODIFIED_AT/SIZE` 的稳定升降序。目录未知大小不得触发递归计算。

```kotlin
@Test fun `size sorting keeps directories first and unknown sizes stable`() {
    val result = FileEntrySorter.sort(entries, SortSpec(SIZE, ASCENDING))
    assertEquals(listOf("dir", "unknown", "small", "large"), result.map { it.name })
}
```

- [ ] **Step 2: 运行 RED**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.iamxpp.isaver.ui.files.FileEntrySorterTest"`

Expected: FAIL，缺少 `FileEntrySorter`/模型。

- [ ] **Step 3: 最小实现**

```kotlin
enum class HomeTab { RECENT, VIEWS, BROWSE }
enum class DisplayMode { LIST, GRID }
enum class SortField { DISPLAY_NAME, TYPE, MODIFIED_AT, SIZE }
enum class SortDirection { ASCENDING, DESCENDING }
data class SortSpec(val field: SortField, val direction: SortDirection)
```

实现稳定 comparator，所有字段保持目录优先。

- [ ] **Step 4: focused/full GREEN 并提交**

Run: `.\gradlew.bat testDebugUnitTest`

Commit: `feat: add file display and sorting models`

### Task 2: DataStore 显示偏好

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/iamxpp/isaver/data/local/BrowserPreferencesRepository.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/data/local/BrowserPreferencesRepositoryTest.kt`

- [ ] **Step 1: 写持久化 RED**

```kotlin
@Test fun `display mode and sort survive repository recreation`() = runTest {
    first.setDisplayMode(DisplayMode.GRID)
    first.setSort(SortSpec(SortField.MODIFIED_AT, SortDirection.DESCENDING))
    assertEquals(
        BrowserPreferences(DisplayMode.GRID, SortSpec(SortField.MODIFIED_AT, SortDirection.DESCENDING)),
        second.preferences.first(),
    )
}
```

- [ ] **Step 2: 运行 RED，加入 Preferences DataStore 依赖**

Expected: 缺少 Repository/依赖而 FAIL。

- [ ] **Step 3: 实现容错解析**

未知枚举或损坏值回退为 `LIST + DISPLAY_NAME + ASCENDING`，写入使用单次 `edit`。

- [ ] **Step 4: focused/full/lint GREEN 并提交**

Commit: `feat: persist browser display preferences`

### Task 3: BrowserViewModel 显示、排序和 Browse 根目录

**Files:**
- Modify: `app/src/main/java/com/iamxpp/isaver/ui/BrowserUiState.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/ui/BrowserViewModel.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/ui/BrowserViewModelTest.kt`

- [ ] **Step 1: 写事件 RED**

覆盖：`openRoot(/, "浏览")`；偏好 Flow 改变后不重新读取目录；列表/网格切换；四种排序；降序；旧加载结果不能覆盖新排序；根 back 返回 `RETURN_HOME`。

- [ ] **Step 2: 运行 RED**

Expected: 缺少 preferences 依赖、事件和 state 字段。

- [ ] **Step 3: 最小实现**

`BrowserUiState` 增加 `displayMode`、`sortSpec`、`searchQuery`。保留原始 `allEntries`，显示列表由纯 sorter 派生；偏好变化只重排，不调用 `RootFileSystem.list()`。

- [ ] **Step 4: focused/full GREEN 并提交**

Commit: `feat: add configurable root browser presentation`

### Task 4: 三标签 App Shell 与位置导航

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/ui/ISaverHomeViewModel.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/ui/ISaverHomeUiState.kt`
- Test: `app/src/test/java/com/iamxpp/isaver/ui/ISaverHomeViewModelTest.kt`

- [ ] **Step 1: 写导航 RED**

覆盖默认标签为 `VIEWS`；切换 Recent/Views/Browse；Browse 打开 `/`；位置点击用备注标题调用 `openRoot`；Browser 根 back 回来源标签；编辑备注不改变 `RootPath`。

- [ ] **Step 2: 运行 RED**

Expected: 类型缺失。

- [ ] **Step 3: 实现单向事件模型**

```kotlin
sealed interface HomeDestination {
    data class Tab(val tab: HomeTab) : HomeDestination
    data class Browser(val path: RootPath, val title: String, val source: HomeTab) : HomeDestination
}
```

- [ ] **Step 4: full GREEN 并提交**

Commit: `feat: add three-tab file navigation`

### Task 5: iOS Files 可复用 Compose 组件

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/ui/files/FilesComponents.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/ui/theme/Theme.kt`
- Test: `app/src/androidTest/java/com/iamxpp/isaver/ui/files/FilesComponentsTest.kt`

- [ ] **Step 1: 写 Compose 语义 RED**

覆盖大标题、搜索、三点菜单、选中的显示/排序项、三标签、列表行和三列网格语义；不要测试具体实现节点层级。

- [ ] **Step 2: 编译/运行 RED**

Run: `.\gradlew.bat compileDebugAndroidTestKotlin`

- [ ] **Step 3: 最小高保真实现**

颜色使用 `#007AFF/#8E8E93/#E5E5EA/#F2F2F7`；文件夹使用项目自有矢量/Canvas 形状；保留 Android system bars；所有 48dp 触控区域和 contentDescription 完整。

- [ ] **Step 4: compile/lint/assemble GREEN 并提交**

Commit: `feat: add ios files compose components`

### Task 6: “视图”页和自定义路径弹窗

**Files:**
- Create: `app/src/main/java/com/iamxpp/isaver/ui/LocationHomeScreen.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/ui/CustomLocationDialog.kt`
- Test: `app/src/androidTest/java/com/iamxpp/isaver/ui/LocationHomeScreenTest.kt`

- [ ] **Step 1: 写 UI RED**

覆盖应用/通用/自定义位置、微信空态、备注名、添加/编辑/移除、校验错误和进行中禁用。移除语义必须明确为“移除视图”，不出现“删除文件”。

- [ ] **Step 2: 运行 RED**

- [ ] **Step 3: 实现列表/网格共享内容**

点击位置只发出 `(RootPath, displayName)`；弹窗原始路径不 trim；备注 trim 后不能为空。

- [ ] **Step 4: tests/lint/assemble GREEN 并提交**

Commit: `feat: add custom path views screen`

### Task 7: BrowserScreen 高保真菜单与新建文件夹

**Files:**
- Modify: `app/src/main/java/com/iamxpp/isaver/ui/BrowserScreen.kt`
- Test: `app/src/androidTest/java/com/iamxpp/isaver/ui/BrowserScreenTest.kt`

- [ ] **Step 1: 写 UI RED**

覆盖 `/` 标题、返回、搜索、列表/图标、四种排序与方向、新建文件夹可用性、只读/符号链接禁用、错误提示、创建成功定位。

- [ ] **Step 2: 运行 RED**

- [ ] **Step 3: 实现**

菜单只发送 typed callback；Composable 不解析 `FolderName`、不调用 Root、不过滤真实路径。首版“压缩文件”和“连接服务器”显示入口；压缩在 M4 启用，服务器在 M6 启用并显示阶段说明。

- [ ] **Step 4: tests/lint/assemble GREEN 并提交**

Commit: `feat: add ios-style root browser screen`

### Task 8: Activity/Application 集成

**Files:**
- Modify: `app/src/main/java/com/iamxpp/isaver/MainActivity.kt`
- Modify: `app/src/main/java/com/iamxpp/isaver/ISaverApplication.kt`
- Create: `app/src/main/java/com/iamxpp/isaver/ui/ISaverHomeScreen.kt`
- Test: `app/src/androidTest/java/com/iamxpp/isaver/ui/ISaverHomeScreenTest.kt`

- [ ] **Step 1: 写 Granted 后默认显示“视图”的 RED**
- [ ] **Step 2: 运行 RED**
- [ ] **Step 3: 组装 Room store、resolver、preferences 和 ViewModels**
- [ ] **Step 4: 运行 unit、androidTest compile、lint、assemble 并提交**

Commit: `feat: integrate location-based ios files home`

### Task 9: 小米 9 真机和视觉验收

- [ ] **Step 1: 新鲜自动门禁**

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

- [ ] **Step 2: 确认设备与 Root**

```powershell
adb devices -l
adb -s d51f42ac shell su -c id
```

- [ ] **Step 3: Root 安装并启动**

```powershell
adb -s d51f42ac push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/isaver-debug.apk
adb -s d51f42ac shell su -c "pm install -r /data/local/tmp/isaver-debug.apk"
adb -s d51f42ac shell rm /data/local/tmp/isaver-debug.apk
```

- [ ] **Step 4: UIAutomator 与截图验收**

验证三标签、视图备注、`/` 浏览、列表/网格、排序、新建目录；截图与四张参考图对比标题、搜索、间距、行高、网格列数、底栏和菜单。

- [ ] **Step 5: Root 测试目录验收**

只在 `/data/local/tmp/isaver-test` 或 `/storage/emulated/0/isaver-test` 创建测试数据；不修改真实微信数据。检查 logcat 无 FATAL/ANR，清理测试数据。

- [ ] **Step 6: 最终双审与提交**

每个实现任务先规格审查再质量审查；所有问题修复并复审后，提交真机必要修复。
