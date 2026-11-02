# AndroidJsonFormatPlugin

Android Studio 的离线日志格式化与 Kotlin data class 生成插件。IDE 中的入口名称为 **Logcat2Kt**。

根据 [rendonglugit/logcat2kt](https://github.com/rendonglugit/logcat2kt) 实现，采用[原网页](https://rendonglugit.github.io/logcat2kt/)的白色面板、灰色分隔线、紫色操作按钮、JSON 树与 Kotlin 双页签布局。

## 安装与使用

1. 下载或构建 `build/distributions/logcat2kt-1.0.2.zip`，**无需解压**。已安装旧版时用同样方式覆盖升级。
2. Android Studio → Settings / Preferences → Plugins → 齿轮菜单 → Install Plugin from Disk，选择 ZIP。
3. 按 IDE 提示启用或重启，在右侧工具栏打开 **Logcat2Kt**。入口隐藏时，可通过 View → Tool Windows → Logcat2Kt 打开。
4. 在 **Logcat 中选中日志，右键 → 发送到 Logcat2Kt**。插件自动展开右侧工具窗口、填入选区并解析。每次发送替换上一批输入；剪贴板不会被修改。

也可以在普通编辑器选中文本后使用相同的右键菜单，或在插件中直接粘贴。没有选区时不显示发送动作。支持全部折叠 / 展开、切换 JSON / Kotlin 和复制当前结果。

## 功能与样式

- 复用原项目的日志前缀剥离、截断拼接、丢段修复与按 tag 分组逻辑。
- 支持 JSON、宽松 JSON、Kotlin / Java `toString`、Protobuf TextFormat 与二进制 hex。
- Kotlin 根类名、Gson / kotlinx.serialization / Moshi 注解、可空策略、camelCase 命名、时间戳 Long 设置。
- 19 位整数保留原文精度。Proto 二进制没有 `.proto` 时，字段名称为 `field_N`，类型推断沿用原项目规则。
- 宽窗口左右分栏，窄窗口上下分栏；JSON 可折叠，Kotlin 带语法高亮。
- 自动跟随 Android Studio 深色 / 浅色主题，运行中切换会同步更新输入区、JSON、Kotlin、按钮和下拉框；保留现有内容与选区。
- 原生 Swing 实现网页视觉样式，不是网页的逐像素复制。不依赖 JCEF、WebView 或外部浏览器。
- 处理完全离线；不上传日志，不写日志内容到磁盘；关闭项目后释放解析线程与面板数据。
- 输入最多 200 万字符，后台解析有取消和超时机制。树视图最多展示 5000 个节点，复制仍保留完整结果。

### 1.0.2 修复

连续编号的日志分段按原始字符拼接，避免在被截断的数字、布尔值、小数、指数等 token 中插入换行。已用完整的 23 条归档记录回归验证：`1793597` + `004` 恢复为 `1793597004`，不再出现额外的 `004` 字段。Gson、kotlinx.serialization、Moshi 的字段注解统一独占字段定义的上一行。

### 1.0.1 修复

支持 `13:24:29.069 ApiCodec com.example.demo D  [2/2] ...` 这类隐藏日期 / PID 列的表格日志。先识别日志前缀并移除分段标记，再拼接断开的 JSON 字段或字符串；例如 `que` 与 `stion` 恢复为 `question`，不会将时间和标签生成成 Kotlin 属性。仅处理已识别日志前缀后的分段标记，JSON 字符串内部的相同文本保持原样。

## 兼容范围

目标是 **Android Studio Narwhal 2025.1.1 及以后版本**（IntelliJ Platform 251+）。插件描述只设置 `since-build="251"`，**不设置 `until-build`**，后续 IDE 升级不会因为人为的版本上限拒绝加载。

代码使用 2025.1 平台 API 编译，输出 Java 17 字节码，仅依赖少量 IntelliJ 公共工具窗口 / Action API。Rhino 解析引擎随插件打包，不依赖 IDE 中的 Kotlin 或 JavaScript 引擎。Android 依赖为可选，专用于把动作挂入 `Logcat.PopupActions`；选区通过 `LogcatEditor` 数据键取得，不直接引用 Android 插件内部类。

这能减少随 IDE 升级而更新插件的需要，但**不是对所有未来版本永久兼容的保证**。未来平台删除公共 API、修改 Logcat 扩展点或改变运行环境时，仍可能需要发布适配版本。

已完成的验证见 [docs/verification.md](docs/verification.md)。最低版本进行了 API 编译和 Logcat 源码契约核对，未逐个安装历史版本，也未完成真实设备 Logcat 的端到端点击测试。

## 构建

需要 JDK **21 或更高**（编译 2025.1 SDK 所需），以及 Node.js（运行原项目测试）。Android Studio / IDEA 自带的完整 JBR 若包含 `javac`，也可用于构建。

```powershell
# JAVA_HOME 指向 JDK 21+
.\gradlew.bat buildPlugin
node scripts/test-upstream.cjs
```

macOS / Linux 使用 `./gradlew buildPlugin`。安装包输出到 `build/distributions/`。

另有不依赖 Gradle daemon 的构建入口，需要 Python 3.9+。依赖固定在 `scripts/dependencies.lock.json`，下载时校验 SHA-256，SDK 不会打入插件 ZIP：

```powershell
python scripts/build.py --java-home "$env:JAVA_HOME"
```

两个入口均运行原项目 88 项断言（在实际随包 Rhino 引擎中执行）、插件集成检查及原生面板交互测试。界面预览生成在 `build/previews/`。

本机 Windows 的 Java Unix-domain socket 临时路径曾触发 `Unable to establish loopback connection`。若遇到同样问题，可把 socket 临时目录指向已有、可写的纯英文路径，再运行构建。例如本机验证使用：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
.\gradlew.bat buildPlugin
```

不要把这项机器相关配置写入插件或用户的 Android Studio 设置。

## 源码结构

- `LogcatToolWindowFactory.java`：右侧工具窗口注册及销毁。
- `SendSelectionAction.java`：Logcat / 编辑器选区传入与自动展开。
- `LogcatPanel.java`：原生界面、异步解析、剪贴板、折叠树、响应式分栏。
- `PanelTheme.java`：深浅主题配色及控件样式，随 Swing Look-and-Feel 变化更新。
- `ConversionEngine.java`：Rhino 沙箱、取消 / 超时、AST 与界面数据转换。
- `src/main/resources/web/core.js`：从原项目提取，应用 `scripts/core-patches.mjs` 中明确的日志前缀、连续分段拼接和 Kotlin 注解换行修复。
- `src/main/resources/web/runtime.js`：离线转换入口与 UTF-8 / fixed64 API 补齐。
- `src/main/resources/web/index.html`：原网页完整副本，供追溯和样式对照。
- `scripts/vendor.mjs`：上游更新后重新提取核心，`vendor/revision.txt` 记录固定提交。

插件遵循 MIT；上游作者信息和 Rhino MPL-2.0 许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
