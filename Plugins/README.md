# HyperLyric 插件

HyperLyric 可以通过插件增加更多歌词功能。插件由 HyperLyric 统一安装和管理，不需要像普通应用一样单独安装。

## 现在可以使用的插件

| 插件 | 作用 |
| --- | --- |
| OpenAI 歌词翻译 | 为当前播放的歌曲生成歌词翻译，并支持自定义服务和翻译选项。 |

## 插件处理边界

插件收到的是独立的只读 `PluginSong` 快照，包含 `id`、`name`、`artist`、`album`、`duration`、`metadata` 和完整 `lyrics`。处理调用还会携带只读的 `PluginProcessingContext.mediaInfo`，用于网络搜索、缓存 key 或提示词。插件不能导入宿主内部 `Song`、`LyricMediaMetadata`、`MediaMetadataHelper`、`CurrentMediaInfoResolver`、`LyriconDataBridge`、Xposed 或 SystemUI 类型。

处理器通过 `PluginSongResult` 返回候选结果。当前 API 版本仍为 `1`，Demo 阶段不升级版本号，也不处理正式发布后的兼容迁移：

- `PluginSongField` 支持 `ID`、`NAME`、`ARTIST`、`ALBUM`、`DURATION`、`METADATA` 和 `LYRICS`；字段未声明时 Core 保留原值，字段已声明且候选为 `null` 时明确清空；
- `LYRIC_REPLACEMENT` 阶段适合搜索并替换原文歌词、逐字时间轴或罗马音；
- `TRANSLATION_ENHANCEMENT` 阶段会看到前一阶段已经合并的最新歌词，适合翻译和其他增强；
- `PluginLyricsUpdateMode.PATCH` 要求行数不变，按稳定行索引只合并声明的 `PluginLyricField`；`REPLACE` 允许返回全新的歌词列表和时间轴；
- 同阶段按稳定插件 ID、扩展 ID 顺序执行，后一个有效的同字段结果覆盖前值，不同字段同时保留；
- 插件异常、超时、无匹配或非法时间轴只跳过当前插件，后续插件继续运行，最终没有有效结果时保持原始 Song。

Core 会校验完整候选的行/词时间轴和结果大小：歌词行要求 `begin >= 0`、`end > begin`、`duration == end - begin`、按 begin 升序且有 text 或 words；每个 words 列表必须在行范围内、按 begin 升序，不能产生越界时间轴。只翻译时声明 `TRANSLATION/TRANSLATION_WORDS`，原文插件声明 `TEXT/WORDS`，罗马音插件声明 `ROMA`；这些字段可以在同一批歌词行上同时存在。Album、标题、艺术家、Duration 和 Metadata 通过 DTO 受控写回，不允许插件访问宿主内部媒体对象。

Core 写回最终 Song 后会同步 `LyriconDataBridge.currentSong`、`currentSongName`、完整歌词可用状态和 `TimingNavigator`，并按修改字段刷新 metadata/lyric。插件只能访问 `PluginContext`、`PluginSong`、`PluginProcessingContext.mediaInfo` 和公开 API，不能访问 `Song`、`LyriconDataBridge`、Renderer、Canvas、MediaMetadataHelper、MediaSession、CurrentMediaInfoResolver、Xposed 或 SystemUI。

对于逐字歌词，渲染使用的是 `words` 的文本和时间轴，不能只修改行级 `text` 后期待逐字内容变化。PATCH 时必须声明 `WORDS` 并返回匹配的行索引；REPLACE 时可以同时返回新的 `words`。每个词的时间必须仍在对应行范围内、按顺序排列。Demo 插件的“替换原文歌词”会新增带时间轴的 `[Demo] ` 词并重排当前行词时间，用于验证字段级合并路径。

## 统一缓存入口

插件通过 `PluginContext.cache` 使用 Core 提供的抽象缓存，支持 `getString`、`putString`、`getBytes`、`putBytes`、`contains`、`remove` 和 `clear`。插件负责缓存什么、key、序列化格式、schema 版本、TTL/失效逻辑和 cache hit 后的 `PluginSongResult`；Core 负责插件 ID 隔离、实际存储后端、大小限制和读写异常隔离。

当前实现使用宿主运行时侧按插件隔离的 `SharedPreferences`，不是 `plugins/<id>/cache/` 文件目录，也不假设 Remote Files 能从 SystemUI 写回 HyperLyric App。插件看不到 Android Context、文件路径、SystemUI、Xposed 或 MediaSession。缓存损坏、解析失败、读取/写入失败不会影响原始歌词，插件可以删除当前条目并回退到网络请求。AI Translation 会先查缓存，命中后禁止网络请求，并基于当前 Song 重新生成只声明翻译字段的 PATCH；成功且校验通过后保存翻译结果条目，而不是整份 Song。API Key 不进入缓存 key。卸载插件时 App 会通过 Remote Preferences 发布一次性清理标记，SystemUI Core 清理对应宿主缓存；仅禁用插件不会删除缓存。

## 怎么使用

打开 HyperLyric 的“插件管理”，选择插件包安装，然后进入插件配置页完成设置即可。

- 安装、卸载或更新插件后，需要重启系统界面才能使用新的插件内容。
- 插件开关和配置都可以在应用内调整，普通配置修改会及时生效。
- 设置备份仍然支持 JSON；如果希望连同插件一起保存，可以使用 ZIP 备份。

## 想参与贡献

如果你想为 HyperLyric 增加歌词翻译或其他歌词功能，可以先看看[插件介绍](../docs/plugins.md)和[插件适配与开发指南](../docs/plugin-development.md)。

插件系统还在不断完善，欢迎提交插件、改进文档，或者通过 Pull Request 和 Issue 分享你的想法。
