# HyperLyric Plugins

本目录保存 HyperLyric 的插件开发相关模块。

插件开发说明见 [DEVELOPMENT.md](DEVELOPMENT.md)。

```text
Plugins/
├─ api/
│  └─ 公共 Plugin API，Gradle 模块 :plugins:api
└─ modules/
   └─ demo-logger/
      └─ Demo 插件，Gradle 模块 :plugins:demo-logger
```

## 目录边界

- `api/` 是插件编译时使用的稳定 DTO、生命周期和 Extension 契约；不依赖 HyperLyric 内部 `Song`、Canvas、Renderer 或 Xposed。
- `modules/` 下每个子目录是一个独立的插件源码和打包模块，最终输出 HyperLyric 插件 ZIP，不作为 Android App 安装。
- SystemUI 侧 Runtime、App 侧安装/启用/配置管理和插件管理 UI 仍属于宿主 `app` 模块。
- 设备上的已安装 ZIP、配置和插件数据由 HyperLyric App 管理，不写入此源码目录。

后续新增插件时，在 `modules/` 下创建独立子目录，并复用 Demo 的 manifest、DEX 打包和 `Plugins/api` 依赖方式。

## 当前插件

| 插件 | ID | 说明 |
| --- | --- | --- |
| Demo 歌词插件 | `hyperlyric.demo.logger` | 记录歌曲信息并添加内存标记，用于验证 ZIP、DEX、Runtime 和歌词处理链路 |

插件的 `author` 字段已经纳入 Manifest 契约，但当前 HyperLyric UI 暂不展示该字段。
