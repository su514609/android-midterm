# NotePad 记事本（期中实验扩展版）

本项目基于 [fjnu-cse/NotePad](https://github.com/fjnu-cse/NotePad)（Android 官方 NotePad 示例）完成《移动软件开发期中实验》的功能扩展。

## 一、完成的功能

### 基本要求

| 功能 | 说明 | 实现位置 |
| --- | --- | --- |
| 列表条目显示时间戳 | 每个条目在标题下方显示带年月日的完整最后修改时间，格式为「2026年11月13日 14:30」 | `NotesList.NoteAdapter#bindView`、`NoteUtils#formatTime` |
| 笔记查询 | 标题栏搜索框，可**按标题或正文内容**模糊查询，输入即时过滤，收起搜索框后自动恢复全部列表 | `NotesList#requery`（`title LIKE ? OR note LIKE ?`） |

### 附加功能

| 功能 | 说明 |
| --- | --- |
| UI 美化 | Material 3 卡片式列表、圆角便签、空状态插画提示；编辑器保留原有「横线稿纸」效果并提升字号与行距 |
| 更换便签背景 | 每篇笔记可从 6 种颜色中选一种作为便签底色，列表卡片与编辑器整页同步变色，状态栏跟随 |
| 笔记分类 | 新增 5 个分类（工作 / 学习 / 生活 / 灵感 / 未分类），编辑器内可设置，列表顶部标签可单分类筛选 |
| 笔记排序 | 支持「修改时间（新→旧）/（旧→新）/ 标题 / 分类」四种排序，选择结果持久化到 SharedPreferences |
| 条目常驻编辑 / 删除 | 每条笔记卡片右上角常驻「编辑」「删除」图标，不必先长按；删除前有确认对话框，避免误触 |
| 标题手动设置与修改 | 编辑器正文上方新增标题输入框，新建时可手动填写，已有笔记可随时点标题框改写 |
| 整篇复制 / 粘贴 | 长按笔记只弹出「复制笔记」；复制内容包含标题、正文、分类与便签背景色，粘贴后完整还原 |
| 鼠标右键粘贴 | 在列表任意位置按鼠标右键即弹出浮动菜单，可直接「粘贴笔记」，右键落在某条笔记上时额外提供「复制笔记」 |
| 退出前询问是否保存 | 点编辑页左上角返回箭头（或按系统返回键）时，若正文/标题有改动则弹出「保存 / 不保存 / 取消」；选「不保存」会把正文和标题还原成进入界面时的内容，**且不改动最后修改时间**；没有改动时直接静默退出 |

## 二、源码分析与改动要点

原始 NotePad 采用 `NotesList` + `NoteEditor` + `NotePadProvider`（ContentProvider）三层结构，列表通过 `managedQuery` + `SimpleCursorAdapter` 直接绑定 Cursor。扩展时沿用了这套结构，主要改动：

1. **`NotePad.java`（数据契约）**：新增 `COLUMN_NAME_CATEGORY`、`COLUMN_NAME_COLOR` 两个字段，以及 `CATEGORIES`、四种 `SORT_*` 排序常量。
2. **`NotePadProvider.java`（数据层）**：数据库版本 2 → 3；`onCreate` 建表增加两列；`onUpgrade` 改用 `ALTER TABLE` 原地升级（原实现是 `DROP TABLE`，会丢失已有笔记）；`insert` 为新笔记补默认分类与颜色。
3. **`NotesList.java`（列表页）**：改为 `AppCompatActivity` + `MaterialToolbar`；用 `ContentResolver.query(uri, projection, selection, selectionArgs, sortOrder)` 自行管理 Cursor，把搜索关键字、分类条件、排序方式拼成 SQL 条件交给 SQLite 过滤；`SimpleCursorAdapter` 改为子类并重写 `bindView`，一次绑定标题、时间戳、分类与卡片底色。
4. **`NoteEditor.java`（编辑页）**：改为 `AppCompatActivity` + `MaterialToolbar`；新增分类/时间信息栏、**标题输入框**、分类对话框、圆形色板对话框；把便签颜色应用到根布局、工具栏与状态栏，并同步调整稿纸横线颜色；`onPause` 中仅在正文或标题真正改变时才写回数据库，避免「打开笔记就刷新时间戳」；**返回时先判断是否改动**，有改动则弹窗询问保存，选「不保存」调用 `restoreOriginal()` 只回写正文与标题两列、不碰 `modified`，并用 `mDiscardChanges` 标记让 `onPause` 跳过自动保存。
5. **`NoteUtils.java`（新增工具类）**：时间戳格式化、便签配色方案、由正文推导标题。时间格式化显式指定 `Asia/Shanghai` 时区，不依赖设备默认时区——部分模拟器 / 刷机包的时区停在 GMT，会让界面时间比真实时间早 8 小时。
6. **`NoteClipboard.java`（新增工具类）**：整篇笔记的复制 / 粘贴。复制时把标题、正文、分类、背景色打包成 JSON——可读文本放进剪贴板，完整快照同时写入多条目并备份到 SharedPreferences；粘贴时优先读多条目快照，若系统把剪贴板裁剪为单条则用文本比对找回快照，保证分类和背景色不丢。
7. **`NotesList` 的条目交互**：点击 / 长按统一绑定在卡片视图上（条目内含有可点击图标时，ListView 会判定整行不可点）；长按只弹「复制笔记」；空白区域通过 `dispatchGenericMotionEvent` 捕获鼠标右键，在落点弹出「粘贴笔记 / 新建笔记」浮动菜单。

## 三、运行方式

```bash
# 在项目根目录执行（需 JDK 17+ 与 Android SDK 36/37）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开本目录运行。`minSdk 24 / targetSdk 37`，无第三方依赖之外的额外权限。

## 四、界面截图

截图位于同级目录 `../screenshots/`，依次为：列表时间戳、按标题搜索、按内容搜索、分类筛选、笔记排序、编辑器、选择便签颜色、更换背景效果、编辑器菜单、设置分类、新建笔记、空状态提示、长按复制菜单、返回询问是否保存。

## 五、说明

原始版本的其他能力（粘贴建笔记、标题编辑 `TitleEditor`、LiveFolder）均保留可用；长按菜单按本次要求只保留「复制」，打开与删除改由条目上常驻的编辑 / 删除图标承担。
