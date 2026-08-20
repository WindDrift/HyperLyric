# HyperLyric Plugins

本目录保存 HyperLyric 的插件开发相关模块。

插件开发说明见 [DEVELOPMENT.md](DEVELOPMENT.md)。

```text
Plugins/
├─ api/
│  └─ 宿主与插件共享的公共 Plugin API，Gradle 模块 :plugins:api
└─ modules/
   ├─ demo-logger/
   │  └─ Demo 插件，Gradle 模块 :plugins:demo-logger
   └─ ai-translation/
      └─ AI 翻译插件，Gradle 模块 :plugins:ai-translation
```

`api/` 不是独立发布的 SDK，也不是可安装插件。它是 HyperLyric 仓库内部的稳定编译契约；插件通过 `compileOnly(project(":plugins:api"))` 使用，运行时由宿主提供同一份 API。

## 目录边界

- `api/` 是插件编译时使用的稳定 DTO、生命周期和 Extension 契约；不依赖 HyperLyric 内部 `Song`、Canvas、Renderer 或 Xposed。
- `modules/` 下每个子目录是一个独立的插件源码和打包模块，最终输出 HyperLyric 插件 ZIP，不作为 Android App 安装。
- SystemUI 侧 Runtime、App 侧安装/启用/配置管理和插件管理 UI 仍属于宿主 `app` 模块。
- 设备上的已安装 ZIP、配置和插件数据由 HyperLyric App 管理，不写入此源码目录。

README 只记录插件目录和当前插件；面向插件作者的构建、Manifest、R8、生命周期、歌词 DTO、设置 Schema 和验证流程见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## V1 当前边界

- `apiVersion` 当前为 `1`；宿主接受不高于自身版本的插件。
- 安装、卸载、启用、禁用和插件代码升级需要重启 SystemUI；设置修改实时同步。
- 设置页同时提供宿主 JSON 备份和包含插件包、非敏感配置的完整 ZIP 备份；两种格式都可以恢复，JSON 不依赖插件。
- 插件只能处理 `PluginSong` 快照，不能直接访问宿主 `Song`、Canvas、Renderer、LyriconDataBridge 或 Xposed 对象。
- 当前只有 `LyricProcessorExtension` 一种歌词处理 Extension；一个 ZIP 未来仍可以注册多个 Extension。

后续新增插件时，在 `modules/` 下创建独立子目录，并复用 Demo 的 Manifest、Release/R8 DEX 打包和 `Plugins/api` 依赖方式。

## 当前插件

| 插件 | ID | 说明 |
| --- | --- | --- |
| Demo 歌词插件 | `hyperlyric.demo.logger` | 记录歌曲信息并添加内存标记，用于验证 ZIP、DEX、Runtime 和歌词处理链路 |
| OpenAI 歌词翻译 | `hyperlyric.ai.translation` | 在插件管理页复刻原 AI 翻译配置与 Miuix 设置语义，作为异步歌词 Processor 提供缓存、队列和切歌隔离 |

插件的 `author` 字段已经纳入 Manifest 契约，但当前 HyperLyric UI 暂不展示该字段。
