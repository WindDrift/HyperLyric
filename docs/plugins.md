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
  → 接收只读 PluginSong + PluginProcessingContext.mediaInfo
  → 按 LYRIC_REPLACEMENT → TRANSLATION_ENHANCEMENT 阶段返回 PluginSongResult
  → 宿主按 changedFields 与歌词 PATCH/REPLACE 合并并校验后写回
```

插件代码在 SystemUI 启动时加载，因此安装、卸载和代码升级需要重启 SystemUI。配置修改不需要重新加载代码，会通过 Remote Preferences 实时同步；配置改变不会主动重跑当前歌曲，下一次正常插件处理时读取新配置。

## 歌词处理边界

插件接收的是只读 `PluginSong` 快照，而不是 HyperLyric 内部的 `Song`。快照包含 `id`、`name`、`artist`、`album`、`duration`、`metadata` 和 `lyrics`；`PluginProcessingContext.mediaInfo` 提供 Core 确认过的当前媒体标题、艺术家、Album 和 Duration，供网络查询使用。歌词原文会先正常显示，插件在后台执行增强；插件没有结果、发生异常、超时或网络失败时，宿主会保留当前有效 Song。

插件可以补充的内容包括：

- `PluginSongField.ID/NAME/ARTIST/ALBUM/DURATION/METADATA/LYRICS` 中明确声明的顶层字段；
- 完整原文歌词或新的逐字时间轴，使用 `LYRICS + PluginLyricsUpdateMode.REPLACE`；
- 行级翻译、罗马音、secondary、行/词 metadata 或逐字增强，使用 `LYRICS + PATCH` 并声明对应的 `PluginLyricField`；
- 只有时间轴能够可靠匹配时，才写入 `WORDS`、`TRANSLATION_WORDS` 或其他逐字字段。

`changedFields` 是顶层修改的权威声明。字段未声明时保留当前值；字段已声明且候选值为 `null` 时明确清空。Core 会校验行/词的 begin、end、duration、顺序和结果大小。当前 API 版本仍为 `1`，Demo 阶段不做版本迁移。

`PATCH` 必须保持行数不变，按当前快照的稳定行索引匹配，只覆盖声明的歌词行字段；`REPLACE` 可以改变行数、行时间轴、原文、words、secondary、translation 和 roma，并返回全新的歌词列表。显式的 `REPLACE` 空列表或 `null` 表示清空歌词；插件异常或非法结果则回退到上一份有效歌词，不能借失败路径清空歌词。

多个插件按固定阶段执行，同一阶段按稳定顺序运行。后返回且有效的同字段结果覆盖前值，不同字段结果同时保留；每个插件都接收前一个插件合并后的最新快照。某个插件失败不能清空原歌词，也不能阻止后续插件继续运行。

字段级合并示例：原文插件修改 `TEXT/WORDS`，AI 翻译只修改 `TRANSLATION/TRANSLATION_WORDS`，罗马音插件修改 `ROMA`；三次合并后同一批歌词行同时保留原文、翻译、罗马音和逐字时间轴。Core 写回后会同步最终 `Song`、歌曲标题、完整歌词状态、TimingNavigator，并按需刷新 metadata/lyric。

插件不能直接访问宿主内部模型、Canvas、Renderer、SystemUI View、`LyriconDataBridge` 或 Xposed 对象。这样可以让插件和歌词渲染实现保持独立，也能让宿主在插件失败时安全降级。

## 统一缓存入口

插件通过 `PluginContext.cache` 使用宿主提供的抽象缓存，不需要也不能取得 Android `Context`、文件路径、SystemUI Context、Xposed、MediaSession 或 Remote Files。`PluginCache` 支持字符串和字节读写、存在性检查、删除及清空；插件负责缓存内容、key、序列化格式、schema 版本、TTL/失效逻辑，以及命中后如何基于当前 `PluginSong` 生成 `PluginSongResult`。

当前 Core 后端是按插件 ID 隔离的宿主运行时 `SharedPreferences`，物理上不是 `plugins/<id>/cache/` 文件目录。未来切换文件或字节后端时，插件调用方式不变。缓存读写异常、损坏、解析失败或超限只会忽略/删除当前条目并回退到网络或无结果路径，不会清空原始歌词，也不会阻止其他插件。AI Translation 先查缓存，命中时不请求网络；未命中后成功且通过校验的翻译条目才写入缓存，并基于当前 Song 重新生成翻译 PATCH，不保存整份旧 Song。API Key 不进入缓存 key。网络任务完成后，Scheduler 会暂留已完成结果，直到调用方完成缓存写入并释放任务，避免相同 key 在缓存写入窗口内再次发起网络请求。卸载插件时 App 通过 Remote Preferences 发布一次性清理标记，由 SystemUI Core 清理对应宿主缓存；仅禁用插件不会删除缓存。

Core 同时对同一首歌的请求使用完整源 `PluginSong`、Core 内部媒体身份和 `PluginMediaInfo` 指纹，并用 generation 拒绝迟到结果；`onSongChanged()` 紧接 `onMetadata()` 时尽量合并成一个处理请求。重复源 DTO 会先刷新 Core 的歌曲/时间轴状态，不会直接跳过 `onSongChanged`；如果后续 metadata 证明媒体身份或来源改变，则提交新的原始 Song 并重新处理。相关生命周期会在 LSP 日志中记录 `request_started`、`request_deduplicated`、`request_cancelled`、`stale_result_ignored` 和 `request_completed`。

## 配置与生命周期

插件通过 Manifest 声明设置。宿主根据这些语义描述生成配置页面，插件不需要依赖 Compose 或 MIUIX。标题、摘要、密码输入、动态值、`endActions` 位置以及禁用状态都由宿主统一处理。

插件入口实现 `HyperLyricPlugin`，可以在 `onLoad` 中注册一个或多个歌词处理扩展。插件配置改变时会收到 `onConfigChanged`；卸载时应在 `onUnload` 中释放网络请求、线程和缓存资源。

当前 Plugin API 版本为 `1`。插件应使用稳定的 ID，并在发布后保持 ID 不变；ID 同时用于插件识别、配置命名空间和存储空间。

对于第三方适配，最重要的是保持以下契约：

| 契约 | 要求 |
| --- | --- |
| Manifest | `id`、入口类和 API 版本必须稳定、可校验；`summary` 可以省略。 |
| API 依赖 | 只使用 `Plugins/api` 暴露的接口；API 依赖使用 `compileOnly`。 |
| 歌词处理 | 使用只读 `PluginSong` 和 `PluginProcessingContext.mediaInfo` 输入，以 `PluginSongResult.changedFields` 声明顶层字段，并用 `PATCH/REPLACE` 描述歌词更新。 |
| 失败策略 | 异常、超时、空结果和缓存错误都必须回退到当前歌词。 |
| 配置页面 | 通过 Manifest Settings Schema 描述设置，不自行实现 MIUIX 或 Compose 页面。 |
| 运行库 | 插件自己的运行时依赖由插件携带，宿主已有 API 和运行库不能重复打包。 |

## 开始开发

准备编写插件时，请继续阅读[HyperLyric 插件适配与开发指南](plugin-development.md)。其中包含项目结构、Manifest、歌词 DTO、配置 Schema、存储、日志、R8、打包和验证流程。

如果你已经为 HyperLyric 做了插件适配，欢迎提交 Pull Request。插件代码、文档和测试案例都欢迎贡献。
