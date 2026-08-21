# HyperLyric 插件

HyperLyric 可以通过插件增加更多歌词功能。插件由 HyperLyric 统一安装和管理，不需要像普通应用一样单独安装。

## 现在可以使用的插件

| 插件 | 作用 |
| --- | --- |
| OpenAI 歌词翻译 | 为当前播放的歌曲生成歌词翻译，并支持自定义服务和翻译选项。 |

## 插件处理边界

插件收到的是独立的只读 `PluginSong` 快照，包含 `id`、`name`、`artist`、`album`、`duration`、`metadata` 和完整 `lyrics`。处理调用还会携带只读的 `PluginProcessingContext.mediaInfo`，用于网络搜索、缓存 key 或提示词。插件不能导入宿主内部 `Song`、`LyricMediaMetadata`、`MediaMetadataHelper`、`CurrentMediaInfoResolver`、`LyriconDataBridge`、Xposed 或 SystemUI 类型。

处理器通过 `PluginSongResult(song, changedFields)` 返回候选结果：

- `LYRIC_REPLACEMENT` 阶段适合搜索并替换原文歌词、逐字时间轴或罗马音；
- `TRANSLATION_ENHANCEMENT` 阶段会看到前一阶段已经合并的最新歌词，适合翻译和其他增强；
- 同阶段按稳定顺序执行，后一个有效结果覆盖前一个完整 lyrics 候选；Song 的媒体字段始终由 Core 保留；
- 插件异常、超时、无匹配或非法时间轴会回退到上一个有效 Song，不能清空原歌词。

插件结果只能声明 `PluginSongField.LYRICS`。Core 会校验完整候选的行/词时间轴和结果大小；只翻译时只改行的 `translation`，完整歌词替换时可以同时返回原文、翻译、罗马音、secondary、metadata 和所有逐字字段。Album 等媒体字段仅供读取和搜索，不会被插件写回。

对于逐字歌词，渲染使用的是 `words` 的文本和时间轴，不能只修改行级 `text` 后期待逐字内容变化。插件需要在返回完整歌词时同时返回新的 `words`（或明确保留原词文本）；每个词的时间必须仍在对应行范围内、按顺序排列。Demo 插件的“替换原文歌词”会新增带时间轴的 `[Demo] ` 词并重排当前行词时间，用于验证这个路径。

## 怎么使用

打开 HyperLyric 的“插件管理”，选择插件包安装，然后进入插件配置页完成设置即可。

- 安装、卸载或更新插件后，需要重启系统界面才能使用新的插件内容。
- 插件开关和配置都可以在应用内调整，普通配置修改会及时生效。
- 设置备份仍然支持 JSON；如果希望连同插件一起保存，可以使用 ZIP 备份。

## 想参与贡献

如果你想为 HyperLyric 增加歌词翻译或其他歌词功能，可以先看看[插件介绍](../docs/plugins.md)和[插件适配与开发指南](../docs/plugin-development.md)。

插件系统还在不断完善，欢迎提交插件、改进文档，或者通过 Pull Request 和 Issue 分享你的想法。
