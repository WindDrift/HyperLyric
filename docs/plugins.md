# HyperLyric 插件体系

本文面向希望适配 HyperLyric、设计第三方插件或参与插件生态建设的开发者，介绍插件的运行边界、扩展模型和兼容性约束。具体制作流程请参阅[插件适配与开发指南](plugin-development.md)。

## 插件的定位

HyperLyric 插件是一个由宿主管理的 ZIP 扩展包。它通过稳定的 Plugin API 接入歌词处理链，在不修改 HyperLyric 核心代码的情况下，为歌词增加翻译、罗马音、词级信息或其他歌词增强能力。

插件不是独立 APK、Android Service，也不是 Xposed 模块。HyperLyric 负责安装、配置、加载和卸载插件；插件只通过公开的 `Plugin API` 与宿主通信，不能把宿主内部实现当作插件接口使用。

## 当前插件

- **OpenAI 歌词翻译**：HyperLyric 的第一个正式歌词插件，提供兼容 OpenAI API 的翻译能力，以及缓存、语言检测、请求队列、并发限制和切歌隔离。
- **Demo 歌词插件**：仓库中的运行时示例，用于验证插件安装、生命周期和歌词处理链路，不是面向普通用户的功能插件。

## 运行模型

插件从安装到处理歌词的大致流程如下：

```text
插件 ZIP
  → Manifest 校验与安装
  → SystemUI 启动时由 PluginRuntime 加载
  → PluginContext 注册 Extension
  → 接收 PluginSong 快照并返回增强结果
  → 宿主校验歌曲身份和歌词结构后写回
```

插件代码在 SystemUI 启动时加载，因此安装、卸载和代码升级需要重启 SystemUI。配置修改不需要重新加载代码，会通过 Remote Preferences 实时同步。

## 歌词处理边界

插件接收的是 `PluginSong` 快照，而不是 HyperLyric 内部的 `Song`。歌词原文会先正常显示，插件在后台执行增强；插件没有结果、发生异常、超时或网络失败时，宿主会保留原始歌词。

插件可以补充的内容包括：

- 行级翻译，写入 `PluginLyricLine.translation`；
- 只有时间轴能够可靠匹配时，才写入 `translationWords`；
- 罗马音、词级信息或插件自己的元数据。

插件不能直接访问宿主内部模型、Canvas、Renderer、SystemUI View、`LyriconDataBridge` 或 Xposed 对象。这样可以让插件和歌词渲染实现保持独立，也能让宿主在插件失败时安全降级。

## 配置与生命周期

插件通过 Manifest 声明设置。宿主根据这些语义描述生成配置页面，插件不需要依赖 Compose 或 MIUIX。标题、摘要、密码输入、动态值、`endActions` 位置以及禁用状态都由宿主统一处理。

插件入口实现 `HyperLyricPlugin`，可以在 `onLoad` 中注册一个或多个歌词处理扩展。插件配置改变时会收到 `onConfigChanged`；卸载时应在 `onUnload` 中释放网络请求、线程和缓存资源。

当前 Plugin API 版本为 `1`。插件应使用稳定的 ID，并在发布后保持 ID 不变；ID 同时用于插件识别、配置命名空间和存储空间。

对于第三方适配，最重要的是保持以下契约：

| 契约 | 要求 |
| --- | --- |
| Manifest | `id`、入口类和 API 版本必须稳定、可校验；`summary` 可以省略。 |
| API 依赖 | 只使用 `Plugins/api` 暴露的接口；API 依赖使用 `compileOnly`。 |
| 歌词处理 | 使用 `PluginSong` 输入，保持歌曲身份、行顺序和时间轴不变。 |
| 失败策略 | 异常、超时、空结果和缓存错误都必须回退到当前歌词。 |
| 配置页面 | 通过 Manifest Settings Schema 描述设置，不自行实现 MIUIX 或 Compose 页面。 |
| 运行库 | 插件自己的运行时依赖由插件携带，宿主已有 API 和运行库不能重复打包。 |

## 开始开发

准备编写插件时，请继续阅读[HyperLyric 插件适配与开发指南](plugin-development.md)。其中包含项目结构、Manifest、歌词 DTO、配置 Schema、存储、日志、R8、打包和验证流程。

如果你已经为 HyperLyric 做了插件适配，欢迎提交 Pull Request。插件代码、文档和测试案例都欢迎贡献。
