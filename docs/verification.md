# 验证记录

日期：2026-11-02。插件版本：1.0.2。上游提交：`e8f33d9730618aba35cac9c27286d2f151126412`。

## 已执行

| 检查 | 结果 |
| --- | --- |
| 原始网页脚本在 Node 中运行原项目测试 | 88 passed, 0 failed |
| 随插件打包的核心代码在 Rhino 1.8.0 中运行相同断言 | 88 passed |
| Java 集成检查 | 43 passed，包含下述分段与注解新回归，以及既有数字精度、Protobuf、取消恢复和树节点预算等检查 |
| Swing 面板交互 | 选区传入、异步更新、禁用过时复制、清空、示例、JSON / Kotlin 切换均通过 |
| 布局渲染 | 1120px、460px、320px 视口预览生成并检查 |
| 深浅主题 | 实际 Swing Look-and-Feel 浅色 → 深色 → 浅色切换通过；验证内容、选区和 Kotlin 语法颜色，检查深色 JSON / Kotlin 预览 |
| 2025.1 平台 SDK 编译 | 通过，基线 API 251.25410.129，Java 17 字节码 |
| 标准 Gradle 构建 | `buildPlugin` 通过 |
| 独立 Python 构建 | 通过，生成 ZIP 和 SHA-256 文件 |
| JetBrains Plugin Verifier 1.411：Android Studio 2026.2.2 | Compatible，目标 AI-262.10968.63.2622.16730706 |
| JetBrains Plugin Verifier 1.411：IDEA 2025.2.5 | Compatible，目标 IU-252.28238.7；Android 可选依赖缺失属预期 |

## Logcat 接入依据

1.0.2 已用用户提交的完整三段日志验证。仓库内的回归样例将业务 ID、包名和图片地址替换成合成测试数据，并保留原始结构、时间戳和截断位置（`src/test/resources/regressions/apicodec-three-chunks.log`），与严格 JSON 验证过的完整期望结果逐字符比较。23 条记录全部保留；第 19 条记录的 `update_time` 为 `1793597004`，无 `004` 字段，生成的 Kotlin 与完整 JSON 的结果一致。另对包含 Long、负数、小数、指数、true/false/null、字符串的样例遍历每个字符切分位置，验证连续编号分段的输出不变。三种注解均检查上一行排版，无注解模式保持不变。

核对了 Android Studio 2025.1.1 的官方源码：

- [LogcatMainPanel.kt](https://android.googlesource.com/platform/tools/adt/idea/+/refs/tags/studio-2025.1.1/logcat/src/com/android/tools/idea/logcat/LogcatMainPanel.kt)：右键菜单加载 `Logcat.PopupActions`；通过 `LogcatPresenter.EDITOR` 提供日志编辑器，刻意不使用 `CommonDataKeys.EDITOR`。
- [LogcatPresenter.kt](https://android.googlesource.com/platform/tools/adt/idea/+/refs/tags/studio-2025.1.1/logcat/src/com/android/tools/idea/logcat/LogcatPresenter.kt)：上述 key 的实际名称为 `LogcatEditor`。

本机 Android Studio 2026.2.2 的 `META-INF/logcat.xml` 仍定义第三方扩展组 `Logcat.PopupActions`，`LogcatPresenter$Companion` 仍包含 `LogcatEditor` 数据键。插件优先读取它，普通编辑器使用 `CommonDataKeys.EDITOR` 兜底。

## 验证边界

- 未在每一个 Android Studio 2025.1.1+ 历史版本中启动实测。
- 未连接真实设备完成“Logcat 选择 → 右键 → 插件自动打开”的完整 UI 流程；菜单与选区接入已按两端版本的真实契约核对。
- UI 交互验证运行于真实 Swing 组件的 headless 测试，不等同于 Android Studio 内的人工验收。
- 深浅主题切换已做 Swing 事件链和截图验证，尚未在每种 Android Studio 主题中人工切换验收。
- 未验证 macOS / Linux 的系统剪贴板和高 DPI 视觉效果。
- 开放版本上限不意味着未来 ABI 不会发生破坏性变化。

官方检查报告保存在 `build/reports/verifier/`；页面渲染保存在 `build/previews/`。IDE 安装目录自身存在部分 `product-info.json` 列出的模块路径缺失，Verifier 报告了这些宿主布局警告，但未影响插件的 Compatible 结论。

参考：[JetBrains 版本兼容说明](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)、[Tool Window 文档](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)。
