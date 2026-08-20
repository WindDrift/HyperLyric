# HyperLyric 插件开发指南

本文面向希望为 HyperLyric 编写插件的开发者。插件是 HyperLyric 管理的 ZIP 扩展，不是独立安装的 APK、Android Service 或 Xposed 模块。

## 1. 插件边界

插件代码运行在 HyperLyric 注入的 SystemUI 进程中，但只能通过 `PluginContext` 使用宿主能力。插件不应导入或操作以下对象：

- HyperLyric 内部 `Song`、`RichLyricLine` 等模型；
- `LyriconDataBridge`、Renderer、Canvas 或 SystemUI View；
- `HookEntry`、`XposedModule`、SystemUI ClassLoader 或其他 Xposed 对象。

插件只接收 `PluginSong` 快照并返回处理结果。最终结果是否采用、何时写回和如何刷新歌词，始终由 HyperLyric Core 决定。原始歌词先正常显示，插件只是异步 Enhancement；插件异常、超时、空结果和切歌后的迟到结果都不会替换原始歌词。

## 2. 源码目录

每个插件在 `Plugins/modules/` 下使用独立目录：

```text
Plugins/
├─ api/                         # 宿主与插件共享的 compileOnly API
└─ modules/
   └─ my-plugin/
      ├─ build.gradle.kts
      └─ src/main/
         ├─ java/.../MainPlugin.kt
         └─ plugin/manifest.json
```

插件构建模块使用 Android Gradle Plugin 生成 DEX，但输出物是 HyperLyric ZIP，不要把它当作可以直接安装的 APK。

`Plugins/api` 是仓库内部契约，不单独发布 SDK。当前 `HYPERLYRIC_PLUGIN_API_VERSION` 为 `1`；宿主兼容 `apiVersion <= 1` 的插件。API 发生不兼容变化时才提升版本，并保留旧版本迁移策略。

最小依赖如下：

```kotlin
dependencies {
    // API 由 HyperLyric 宿主提供，不能打进插件 ZIP。
    compileOnly(project(":plugins:api"))

    // HyperLyric 宿主已经提供 Kotlin 运行库，也不要重复打包。
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:<host-version>")

    // 只有插件自己的运行时依赖才使用 implementation。
    implementation("com.example:plugin-only-library:<version>")
}
```

`compileOnly` 是插件与宿主共享能力的关键：API、Kotlin 运行库等宿主已有内容只参与编译，不能进入插件 ZIP；插件自己的网络库、解析库等才使用 `implementation`，由插件自行携带。

插件入口必须提供无参数构造函数，并实现 `HyperLyricPlugin`：

```kotlin
class MyPlugin : HyperLyricPlugin {
    override fun onLoad(context: PluginContext) {
        context.registerExtension(MyLyricProcessor(context))
    }
}
```

## 3. Manifest

`src/main/plugin/manifest.json` 至少包含：

```json
{
  "id": "hyperlyric.example.translation",
  "name": "示例翻译插件",
  "summary": "为当前歌词生成翻译",
  "author": "Example Author",
  "version": "1.0.0",
  "apiVersion": 1,
  "entry": "com.example.hyperlyric.MainPlugin"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `id` | 稳定唯一 ID，用于安装识别、配置命名空间、启用状态和插件存储；发布后不要修改 |
| `name` | 插件展示名称 |
| `summary` | 插件展示说明 |
| `author` | 作者或开发者信息，可选；当前版本只保存和传递，不在 UI 展示 |
| `version` | 插件版本，由插件作者维护 |
| `apiVersion` | 所需 Plugin API 版本；高于宿主版本的插件不会加载 |
| `entry` | 实现 `HyperLyricPlugin` 的无参数入口类 |

`author` 缺失时仍兼容现有插件。宿主会保留非空作者信息，但当前设置页面不会渲染它。

## 4. ZIP 输出格式

插件 ZIP 直接使用 `.zip` 扩展名：

```text
my-plugin.zip
├─ manifest.json
├─ classes.dex
├─ classes2.dex       # 如果构建产生多 DEX
└─ assets/            # 可选资源
```

当前 Runtime 的 ZIP 限制为：总大小不超过 64 MB，单个 DEX 不超过 32 MB，最多 16 个 DEX，`manifest.json` 不超过 512 KB。`assets/` 可以随 ZIP 保存，但 V1 的 `PluginContext` 尚未提供资源读取接口，插件暂时不能依赖它读取运行时资源。

正式插件的打包任务应从启用 R8 的 Release APK 提取全部 `classes*.dex`，再与 `manifest.json` 合并：

```powershell
.\gradlew.bat :plugins:demo-logger:packagePlugin --max-workers=2
```

输出目录为 `Plugins/modules/demo-logger/build/outputs/plugin/`。本地调试可使用不混淆的 Debug ZIP：

```powershell
.\gradlew.bat :plugins:demo-logger:packageDebugPlugin --max-workers=2
```

新插件应提供同名的 `packagePlugin` 任务；正式 ZIP 只能从 Release 变体生成。

## 5. R8 与运行时协议

插件入口类名来自 `manifest.json`，由 Runtime 反射加载；生命周期和歌词处理方法则通过 `plugin-api` 的接口调用。因此插件的混淆规则必须至少保留：

- 实现 `HyperLyricPlugin` 的入口类原名和无参数构造函数；
- `onLoad`、`onEnable`、`onConfigChanged`、`onUnload` 生命周期方法；
- `HyperLyricExtension.id` 与 `LyricProcessorExtension.process` 通信方法。

Demo 插件的 `proguard-rules.pro` 已提供这组最小规则。规则保留协议名称但允许 R8 优化，插件内部未暴露给宿主的实现类仍可正常混淆。

## 6. 生命周期与歌词处理

宿主在 SystemUI 启动时读取启用 ID 和 Remote File，校验 Manifest/API 版本，使用每个插件独立的 `InMemoryDexClassLoader` 加载入口类，并依次调用：

```text
onLoad(context)
onEnable()
onConfigChanged(config)   # 配置通过 App 修改后实时回调
onUnload()                # Runtime 关闭时回调
```

一个插件可以注册一个或多个 Extension。V1 目前支持 `LyricProcessorExtension`：

```kotlin
private class MyLyricProcessor : LyricProcessorExtension {
    override val id: String = "translation"

    override fun process(song: PluginSong): PluginSong? {
        return song.copy(
            lyrics = song.lyrics?.map { line ->
                line.copy(translation = translate(line.text))
            }
        )
    }
}
```

处理函数接收不可变快照，在后台线程运行。没有结果时返回 `null`；不要等待插件完成后才显示原始歌词，也不要修改歌曲 ID、标题、艺术家、时长和已有行时间轴。插件可以补充翻译、罗马音、词级时间信息等歌词增强数据。

每个歌词 Processor 最多运行 15 秒；网络请求、模型调用和缓存读取必须在插件内部自行设置更短的超时，并正确处理取消或异常。

### 6.1 `PluginSong` 快照约束

`PluginSong` 是插件边界上的 DTO，不要把 HyperLyric 内部模型类型带入插件。常用字段如下：

| DTO | 作用 | V1 约束 |
| --- | --- | --- |
| `PluginSong.id`、`name`、`artist`、`duration` | 歌曲身份 | 返回时保持不变 |
| `PluginSong.lyrics` | 歌词行快照 | 可补充或增强，但不能改变行顺序、数量和时间轴 |
| `PluginLyricLine.translation` | 行翻译 | AI 翻译的主要写回字段 |
| `PluginLyricLine.translationWords` | 翻译词级时间 | 只有能可靠匹配时间轴时才补充 |
| `PluginLyricLine.roma` | 罗马音 | 作为歌词增强字段写回 |
| `PluginLyricLine.words`、`secondaryWords` | 原词或副行词级信息 | 不要伪造已有时间 |
| `PluginMetadata` | 插件或 Core 的元数据 | 只写入插件自己的命名空间 |

Processor 应使用 `copy(...)` 返回新快照，不修改歌曲身份和已有行时间。返回 `null` 表示本次没有增强结果。

### 6.2 多插件执行与失败策略

启用插件按稳定 ID 顺序加载；同一插件内的 Extension 按注册顺序执行。上一个 Processor 返回的有效快照会作为下一个 Processor 的输入，因此后应用的有效字段会覆盖前一个结果。V1 不提供复杂冲突解决系统。

每个 Processor 都有独立异常隔离。异常、超时或 `null` 结果会保留当前快照并继续执行其他 Processor；最终写回前 Core 还会重新校验歌曲身份和歌词时间轴。任何失败都必须回退到原始 `Song`。

## 7. 配置 Schema 与 UI 映射

插件只提供语义化设置描述，不依赖 MIUIX 或 Compose 类名。配置放在 `plugin.<pluginId>` 命名空间中，修改后会实时同步到 SystemUI Runtime。

```json
{
  "settings": [
    {
      "type": "switch",
      "key": "skip_existing",
      "title": "跳过已有翻译",
      "summary": "已有翻译时不再调用服务",
      "default": true
    }
  ]
}
```

当前宿主 UI 映射如下：

| Schema 类型 | 宿主组件 | 交互 |
| --- | --- | --- |
| `switch` | `SwitchPreference` | 布尔开关 |
| `text` | `ArrowPreference` + `TextInputDialog` | 普通文本输入 |
| `password` | `ArrowPreference` + `TextInputDialog` | 密码输入，摘要脱敏 |
| `number` | `ArrowPreference` + `TextInputDialog` | 数字输入 |
| `select` | `WindowDropdownPreference` | 单选下拉 |
| `multiSelect` | `ArrowPreference` + `MultiSelectDialog` | 多选对话框 |
| `slider` | `SliderPreference` | 数值滑块，可指定 `min`、`max`、`step` |
| `action` | `ArrowPreference` | V1 仅保留协议，当前宿主不执行操作 |

插件不能写 `SwitchPreference`、`ArrowPreference` 等 MIUIX 名称，也不能自行提供设置页面。宿主未来可以替换 UI 实现而不改变插件协议。插件禁用时，配置页面中的设置项会由宿主统一置为不可用。

安装、卸载、启用、禁用和代码升级需要重启 SystemUI 才生效；配置值通过 Remote Preferences 实时同步，不触发代码热加载。当前 `PluginStorage` 是每个插件独立的 SharedPreferences Key/Value 命名空间，只提供字符串读写和清空，不等同于 ZIP 内的文件系统或缓存目录。

## 8. 配置、存储与日志

通过 `PluginContext` 获取能力：

- `config`：只读读取 App 写入的插件设置；
- `storage`：当前插件独立的持久化 Key/Value 命名空间；
- `logger`：宿主托管的日志接口；
- `registerExtension(...)`：注册歌词处理扩展。

插件日志不要直接使用 Android `Log`、Xposed API 或自定义日志文件。使用：

```kotlin
context.logger.info("event=processSong, lines=${song.lyrics?.size ?: 0}")
context.logger.debug("lifecycle=onConfigChanged, enabled=$enabled")
context.logger.warn("event=cacheMiss")
context.logger.error("event=requestFailed", exception)
```

Runtime 会使用插件 `id` 作为日志来源名，并通过 HyperLyric 的宿主日志入口输出到 LSPosed 日志。消息建议使用 `lifecycle=...` 或 `event=...` 开头，后面使用 `key=value` 字段；不要重复添加插件 ID 或 `[Plugin]` 前缀。

## 9. 验证流程

以 Demo 插件为例：

1. 执行 `:plugins:demo-logger:packagePlugin` 生成 ZIP。
2. 在 HyperLyric App 的插件页面安装 ZIP。
3. 启用插件并重启 SystemUI。
4. 播放一首有歌词的歌曲。
5. 在 LSPosed 日志中按 `hyperlyric.demo.logger` 查找 `onLoad`、`onEnable` 和 `processSong` 日志。
6. 确认插件失败时原始歌词仍能显示，且切歌后旧歌曲结果不会写回。
7. 修改 Demo 配置并确认无需重启即可收到 `onConfigChanged`；再禁用插件并重启 SystemUI，确认 Runtime 不再加载它。

Demo 还会在 `PluginMetadata` 中写入内存标记，用来验证插件结果回到 Core 的链路；这不是正式插件功能的要求。
