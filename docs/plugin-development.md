# HyperLyric 插件适配与开发指南

本文面向第三方开发者，介绍如何为 HyperLyric 适配现有歌词能力，或从零制作并发布自己的插件。目标是让插件只依赖稳定的 Plugin API，不需要了解宿主内部的歌词模型、渲染器或 Xposed 实现。

## 1. 开发前需要知道的边界

插件运行在 HyperLyric 注入的 SystemUI 进程中，但插件能够使用的宿主能力只有 `PluginContext`、Plugin API DTO 和每次处理调用携带的 `PluginProcessingContext`。不要导入或操作以下对象：

- HyperLyric 内部的 `Song`、`RichLyricLine` 或其他歌词模型；
- `LyriconDataBridge`、Renderer、Canvas、SystemUI View；
- `HookEntry`、`XposedModule`、SystemUI ClassLoader 或其他 Xposed 对象。

插件处理的是不可变的 `PluginSong` 快照。它是 Core `Song` 的独立 DTO，不是宿主内部类。快照至少包含 `id`、`name`、`artist`、`album`、`duration`、`metadata` 和完整 `lyrics`；歌词行还包含时间轴、原文、次要文本、翻译、罗马音、metadata 以及各自的逐字时间轴。`PluginProcessingContext.mediaInfo` 另外提供 Core 组装并确认过的当前标题、艺术家、Album 和 Duration，只用于搜索、缓存或请求参数。

原始歌词会先显示，处理器在后台执行；插件返回 `null`、抛出异常、超时、网络失败或缓存失败时，宿主继续使用当前歌词结果，不会因为插件失败而影响原始歌词。

当前 Processor 只接收已经产生完整 `Song` 的歌词源。SuperLyric 目前只提供 metadata 和当前歌词行/纯文本，因此不会被伪造为空 Song 送入 Processor；metadata-only source 将来需要独立的 `LyricSourceExtension` 契约。

## 2. 创建插件模块

插件模块放在 `Plugins/modules/` 下，每个插件使用自己的 Gradle 模块。一个最小目录可以是：

```text
Plugins/
├─ api/                         # 宿主与插件共享的 API
└─ modules/
   └─ my-plugin/
      ├─ build.gradle.kts
      ├─ proguard-rules.pro
      └─ src/main/
         ├─ java/.../MyPlugin.kt
         └─ plugin/manifest.json
```

`Plugins/api` 是仓库内部的编译契约，不单独发布为 SDK。当前 `HYPERLYRIC_PLUGIN_API_VERSION` 为 `1`，宿主接受不高于自身版本的插件。

### Gradle 依赖

API 和宿主已经提供的运行库必须使用 `compileOnly`，插件自己的网络库、解析库或其他运行时依赖才使用 `implementation`：

```kotlin
dependencies {
    // 宿主在运行时提供 API，不要打进插件 ZIP。
    compileOnly(project(":plugins:api"))

    // 宿主已经提供 Kotlin 运行库，不要重复打包。
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:<host-version>")

    // 只有插件自己的运行时依赖才使用 implementation。
    implementation("com.example:plugin-only-library:<version>")
}
```

插件不能依赖 `:app`。这条限制可以避免把宿主内部实现带进插件，也能保持插件 ZIP 的运行库边界清晰。

## 3. 编写 Manifest

Manifest 位于 `src/main/plugin/manifest.json`。最少需要声明插件 ID、名称、版本、API 版本和入口类：

```json
{
  "id": "hyperlyric.example.translation",
  "name": "示例翻译插件",
  "version": "1.0.0",
  "apiVersion": 1,
  "entry": "com.example.hyperlyric.MyPlugin"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `id` | 稳定唯一 ID，用于安装识别、配置命名空间和插件存储；发布后不要修改。 |
| `name` | 插件在 HyperLyric 中显示的名称，必填。 |
| `summary` | 插件的简短说明，可选；没有合适的说明时可以省略。 |
| `author` | 作者信息，可选；当前版本会保存和传递，但设置页暂不展示。 |
| `version` | 插件版本，由插件作者维护。 |
| `apiVersion` | 所需的 Plugin API 版本；高于宿主版本的插件不会安装或加载。 |
| `entry` | 实现 `HyperLyricPlugin` 的无参数入口类。 |

入口类必须有公共的无参数构造函数。正式 Release 使用 R8 时，入口类和宿主通过反射调用的协议方法也必须保留，详见[第 8 节](#8-r8-与正式打包)。

## 4. 实现入口和歌词处理器

入口实现 `HyperLyricPlugin`，在 `onLoad` 中保存上下文并注册处理器：

```kotlin
class MyPlugin : HyperLyricPlugin {
    private lateinit var context: PluginContext

    override fun onLoad(context: PluginContext) {
        this.context = context
        context.registerExtension(MyLyricProcessor(context))
    }

    override fun onEnable() {
        context.logger.info("lifecycle=onEnable")
    }

    override fun onConfigChanged(config: PluginConfig) {
        context.logger.debug("lifecycle=onConfigChanged")
    }

    override fun onUnload() {
        // 取消请求、关闭线程池并释放插件资源。
    }
}

private class MyLyricProcessor(
    private val context: PluginContext,
) : LyricProcessorExtension {
    override val id: String = "translation"
    override val stage = PluginProcessorStage.TRANSLATION_ENHANCEMENT

    override fun processResult(
        song: PluginSong,
        processingContext: PluginProcessingContext
    ): PluginSongResult? {
        val lyrics = song.lyrics ?: return null
        val updated = song.copy(
            lyrics = lyrics.map { line ->
                line.copy(translation = translate(line.text))
            }
        )
        return PluginSongResult(
            song = updated,
            changedFields = setOf(PluginSongField.LYRICS)
        )
    }
}
```

一个插件可以注册多个 `LyricProcessorExtension`。每个处理器都有独立的异常和超时隔离；返回 `null` 表示本次没有增强结果，宿主会继续保留上一个快照。旧 API 处理器仍可实现 `process(song): PluginSong?`，宿主会用歌词快照差异自动生成 `PluginSongResult`；新插件应使用带 `PluginProcessingContext` 的显式结果。

## 5. `PluginSong` 与完整写回规则

`PluginSong` 是插件边界上的只读 DTO。`album` 是歌曲核心字段，不放在 `PluginMetadata` 中；标题、艺术家、Album、Duration 和 Metadata 都只能读取，不能通过插件结果写回 Core。处理器只能返回新的歌词 DTO 和 `changedFields`：

| `PluginSongField` | 语义 |
| --- | --- |
| `LYRICS` | 覆盖完整歌词候选，包含所有歌词行字段和逐字时间轴。 |

V1 的 `PluginSongField` 只有 `LYRICS`。插件返回的候选即使携带修改后的标题、艺术家、Album、Duration 或 Metadata，Core 也会忽略这些字段；它们不会改变当前播放歌曲的身份。

| 字段 | 规则 |
| --- | --- |
| `PluginLyricLine.begin/end/duration` | 必须为合法正向时间轴，`duration` 与行区间一致。 |
| `words`、`secondaryWords`、`translationWords` | 每个词必须位于对应行范围内，按时间不倒退，词 `duration` 合法。 |
| `text`、`secondary`、`translation`、`roma` | 可以单独修改或清空；逐字字段只有可靠时才写入。 |
| `PluginLyricLine.metadata`、`PluginWord.metadata` | 保持为 DTO metadata，不依赖宿主内部类型。 |
| `lyrics` | 不能返回空列表或 `null` 作为完整替换；V1 不支持清空整首歌词。结果大小也受 Core 限制。 |

“只返回翻译”和“完整歌词替换”都使用 `LYRICS`，区别在于候选 DTO：翻译插件应基于收到的最新快照，只修改 `translation/translationWords`；原文、罗马音或词级增强插件可以返回完整行列表，Core 会校验并替换所有行字段。插件不需要也不能创建内部 `Song`。

逐字歌词的可见原文来自 `words`，因此不能只修改 `PluginLyricLine.text`。如果原文内容发生变化，插件应同时返回匹配的新 `words` 和合法时间轴；可以保留原时间轴，也可以在当前行范围内重新分配时间。Demo 插件会新增一个带时间轴的 `[Demo] ` 词并重排原词，作为完整逐字替换示例。

建议使用 `copy(...)` 返回结果，不要改变收到的对象，也不要在处理器里修改宿主对象。处理器不能等待完成后才允许原始歌词显示；网络和模型调用必须在插件自己的后台任务中完成。

### 多插件顺序

Runtime 使用固定阶段和稳定顺序：

```text
LYRIC_REPLACEMENT
→ TRANSLATION_ENHANCEMENT
```

同一阶段内按启用插件 ID、扩展 ID 的稳定顺序执行。每个成功结果先由 Core 校验并替换完整 lyrics，再把合并后的快照交给下一个插件；后返回且有效的歌词候选覆盖前一个候选。一个插件异常、超时或结果非法时只跳过该插件，后续插件继续看到最近一次有效快照，原始歌词不会被清空。

典型链路是：搜索插件在 `LYRIC_REPLACEMENT` 返回新的原文歌词 → AI 插件在 `TRANSLATION_ENHANCEMENT` 看到新原文并返回翻译 → 罗马音/逐字增强插件继续处理最新快照。

宿主对每个歌词 Processor 设置独立的 40 秒超时。网络请求、模型调用和缓存读取应设置更短的超时，并正确处理线程中断和协程取消。即使服务返回空结果、响应解析失败或缓存损坏，也应返回 `null` 或保留当前快照。

## 6. Manifest Settings Schema

插件通过 Manifest 描述设置，宿主再将这些描述映射为当前的 Miuix 设置组件。插件不需要依赖 Compose、MIUIX，也不能自行创建设置页面。

### 设置类型

| `type` | 宿主交互 |
| --- | --- |
| `switch` | 开关。 |
| `text` | 文本输入。 |
| `password` | 密码输入，宿主负责隐藏敏感值。 |
| `select` | 单选下拉。 |
| `multiSelect` | 多选对话框。 |
| `number` | 整数输入。 |
| `slider` | 数值滑块，可指定 `min`、`max`、`step`。 |
| `action` | 预留的操作项协议，当前宿主不执行插件自定义动作。 |

一个设置的 `title` 必须清楚表达它控制的内容，`summary` 用于补充说明，`summary` 本身不是必填项。需要多语言时，可以使用 `titleLocales`、`summaryLocales` 等字段。

### 宿主显示语义

- `valuePresentation: "endAction"`：把当前值放在 `ArrowPreference.endActions` 位置，适合模型名称、目标语言等短值。
- `valuePresentation: "summary"`：把当前值放入摘要。
- `valuePresentation: "summaryPreview"`：按 `previewLineCount` 预览多行文本。
- `emptyValueSummary`：没有值时显示的摘要或 End Action 文本。
- `inputType: "uri"` / `"number"`：提示宿主使用对应的输入语义。
- `conflictsWith`：当前开关打开时，让宿主关闭指定的互斥设置。

如果插件声明 `activationSettingKey`，宿主插件页顶部的通用“启用”开关会同时同步插件加载注册表和这个设置项。设置项本身不会在列表中重复显示。插件禁用时，其他配置仍然可见，但宿主会将它们置为不可用。

### 敏感值与备份

设置默认会进入完整 ZIP 备份。如果某个值不应离开设备，例如 API Key，应显式声明 `backup: false`：

```json
{
  "type": "password",
  "key": "api_key",
  "title": "API Key",
  "default": "",
  "backup": false
}
```

`backup: false` 的值不会写入完整备份，恢复时也不会覆盖设备上已有的值。JSON 备份只包含宿主设置，与插件备份相互独立。

## 7. 配置、存储和日志

插件通过 `PluginContext` 使用宿主提供的三个接口；处理时另有只读的 `PluginProcessingContext`：

- `config`：只读读取 App 写入的配置。支持 `getBoolean`、`getString`、`getLong`、`getFloat` 和 `getStringSet`。
- `storage`：当前插件独立的字符串 Key/Value 存储，支持读、写、删除和清空。
- `logger`：宿主托管的日志接口，支持 `debug`、`info`、`warn` 和 `error`。
- `PluginProcessingContext.mediaInfo`：当前媒体的标题、艺术家、Album 和 Duration，只用于网络查询、缓存 key 或请求参数。它不是 `MediaMetadataHelper`，也不包含包名、Session Token 或 Xposed 对象。

示例：

```kotlin
context.logger.info("event=processSong, lines=${song.lyrics?.size ?: 0}")
context.logger.debug("lifecycle=onConfigChanged, enabled=$enabled")
context.logger.warn("event=cacheMiss")
context.logger.error("event=requestFailed", exception)
```

不要直接使用 Android `Log`、Xposed API 或自定义日志文件。宿主会以插件 ID 作为日志来源，并保留插件组件的日志标签；消息建议使用 `lifecycle=...` 或 `event=...` 开头，再用 `key=value` 表达上下文。

`PluginStorage` 适合保存小型状态、缓存索引和 JSON 条目，不是文件系统，也不是宿主的 SQLite。缓存应带有自己的版本或校验信息；读取失败时应清除损坏条目并回退到原始歌词。PluginStorage 不会进入 HyperLyric 的插件 ZIP 备份。

## 8. R8 与正式打包

正式插件必须使用 Release 和 R8。宿主通过 Manifest 反射入口类，并通过 API 接口调用生命周期和处理器，因此至少需要保留：

- Manifest 中声明的入口类及其无参数构造函数；
- `onLoad`、`onEnable`、`onConfigChanged`、`onUnload`；
- `HyperLyricExtension.id`；
- `LyricProcessorExtension.stage`；
- 迁移旧插件时的 `LyricProcessorExtension.process`；
- 新插件的带 `PluginProcessingContext` 的 `LyricProcessorExtension.processResult`；
- `PluginSong`、`PluginSongResult`、`PluginSongField`、`PluginMediaInfo`、`PluginProcessingContext` 及其协议字段和 DTO 构造/访问成员。

可以参考 Demo 插件的 `proguard-rules.pro`，只保留协议需要的成员，让插件内部实现继续由 R8 优化和混淆。

插件 ZIP 不是 APK，通常只包含：

```text
my-plugin.zip
├─ manifest.json
├─ classes.dex
└─ classes2.dex       # 如果构建产生多 DEX
```

打包任务应从 Release APK 中提取全部 `classes*.dex`，再与 `manifest.json` 合并。不要把 `Plugins/api`、Kotlin 运行库或宿主已有运行库打入 ZIP；插件自己的 `implementation` 依赖才由插件携带。

## 9. 构建和安装

本地调试 ZIP：

```powershell
.\gradlew.bat :plugins:my-plugin:packageDebugPlugin --max-workers=2
```

正式 ZIP：

```powershell
.\gradlew.bat :plugins:my-plugin:packagePlugin --max-workers=2
```

正式任务必须依赖 Release 构建并启用 R8。生成 ZIP 后，在 HyperLyric 的“插件管理”中安装，完成配置并启用插件；安装、卸载和代码升级后重启 SystemUI。

## 10. 验证清单

提交插件前，至少验证以下路径：

1. Debug 和 Release ZIP 都能生成，Release 入口类可以被 R8 后的 Runtime 加载。
2. ZIP 只有预期的 `manifest.json`、DEX 和插件自有运行库，没有宿主 API 或宿主包的重复定义。
3. 插件首次加载、启用、配置变更和卸载都能在日志中确认。
4. 成功结果能写入正确的歌词字段；不可靠的词级时间信息不要写入任何 `*Words` 字段。
5. 网络超时、异常、空结果、解析错误、缓存损坏和未配置必要参数时，原始歌词仍然正常显示。
6. 播放过程中切歌，旧歌曲的迟到结果不会写回新歌曲。
7. 禁用插件后配置项仍然可见但不可编辑；配置更新无需重启，代码更新需要重启 SystemUI。

## 11. 提交贡献

欢迎提交新的歌词插件、已有插件的改进、测试案例和文档修正。请在 Pull Request 中说明插件用途、所需 API 版本、运行时依赖、配置项和验证方式；不要把 API Key 等敏感值写入插件包或提交到仓库，并为对应设置声明 `backup: false`。
