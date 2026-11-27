# sherpa

基于 sherpa-onnx 的 Android 语音能力库：ASR（语音转文字）与 TTS（文字转语音）各自独立发布，
引擎走远程依赖，模型不进 APK。Gradle / Kotlin / Compose 版本与 `android-basics` 保持一致。

## 模块

| 模块 | 说明 | 发布坐标 |
| --- | --- | --- |
| `lib_asr` | 中英双语流式识别（Zipformer int8）、麦克风采集、模型校验 | `com.github.Blue1-0.sherpa:lib-asr:2.0.0` |
| `lib_tts` | 离线 TTS（Matcha 中英双语、非自回归高速合成）、AudioTrack 播放、外部模型目录 | `com.github.Blue1-0.sherpa:lib-tts:2.0.0` |
| `demo` | Compose 测试页：入口菜单两个按钮分别进入 ASR / TTS | 不发布 |

引擎统一使用 `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:2.0.1`（JitPack），
包含 JNI 与 ONNX Runtime，不含模型权重。

> **2.0.0 破坏性变更**：门面与包名已去掉底层名称——`SherpaAsr` → `VoiceAsr`（包
> `com.blue.voice.asr`）、`SherpaTts` → `VoiceTts`（包 `com.blue.voice.tts`），底层类同步更名，
> 不保留旧类兼容。从 1.0.0 升级需改 import 和类名；JitPack 坐标仍是 `com.github.Blue1-0.sherpa`。
>
> **模型需重新推送**：demo 的 `applicationId` 从 `com.blue.sherpa.demo` 改为 `com.blue.voice.demo`，
> 模型放在应用外部私有目录（`.../files/sherpa/`），换包名后旧目录读不到，需重新推送：
> `python tools/fetch_sherpa_asr.py --push`（或 `--package <你的包名>` 指定目标 app）。
> 接入方自己的包名若变化，同样需要重新下载/推送模型。

### 代码混淆（R8）

两个库的 AAR 都自带 `consumer-rules.pro`，使用方开启 `isMinifyEnabled = true` 时会自动合并，
**无需手动添加 keep 规则**。规则内容：保住引擎 `com.k2fsa.sherpa.onnx.**` 的 JNI native 方法
（防止 `UnsatisfiedLinkError`）、本库 `com.blue.voice.asr.**` / `com.blue.voice.tts.**` 的公开 API
与回调接口，并保留注解/泛型签名。若使用方禁用了 consumer rules 的合并，需自行加上这些 keep。

## 使用

```kotlin
// settings.gradle.kts 的 repositories 加 jitpack
maven { url = uri("https://jitpack.io") }

// 按需依赖，两个模块互不捆绑
implementation("com.github.Blue1-0.sherpa:lib-asr:2.0.0")
implementation("com.github.Blue1-0.sherpa:lib-tts:2.0.0")
```

### 最简单用法（一个门面类 + 回调）

App 创建时 init 一次，回调统一在主线程：

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VoiceAsr.init(this, VoiceAsr.Config(), object : VoiceAsr.Callback {
            override fun onReady() { /* 模型就绪 */ }
            override fun onPartial(text: String) { /* 实时上屏 */ }
            override fun onFinal(text: String) { /* 最终结果 */ }
            override fun onError(message: String) { /* 失败原因，直接显示给用户 */ }
        })
    }
}

// 拿到 RECORD_AUDIO 运行时权限后：
if (VoiceAsr.hasRecordPermission(this)) VoiceAsr.start()
else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

VoiceAsr.stop()     // 结束，结果走 onFinal
VoiceAsr.cancel()   // 丢弃当前语音
VoiceAsr.release()  // 释放引擎
```

### TTS（文字转语音）

默认用 Matcha 中英双语模型（约 130MB，含声码器），**首次进入 TTS 页面再 init**，
不要放在 Application.onCreate，避免拖慢启动。`init` 是幂等的，重复调用只替换回调：

```kotlin
VoiceTts.init(application, VoiceTts.Config(), object : VoiceTts.Callback {
    override fun onReady() { /* 模型就绪 */ }
    override fun onFirstAudioLatency(ms: Int) { /* 点击 → 首句播放 的耗时 */ }
    override fun onStart(text: String, sampleRate: Int, durationMs: Int) { /* 开始播放 */ }
    override fun onComplete(text: String, frames: Int) { /* 播放完成 */ }
    override fun onError(message: String) { /* 失败原因 */ }
})

VoiceTts.synthesize("你好，欢迎使用语音合成。Hello, this is offline TTS.") // 中英混读，按句流式输出
VoiceTts.synthesize("你好", speed = 1.2f) // 语速加快
VoiceTts.stop()                           // 停止播放，保留引擎
VoiceTts.release()                        // 释放引擎
```

`synthesize` 会按标点分句：第一句合成完就开始出声，后续句子在播放期间继续合成。
耗时通过 `onFirstAudioLatency(ms)` 回调（同时用 `Log.d("VoiceTts", ...)` 打印）。

**关于音色（speakerId）**：默认 Matcha 模型的 `n_speakers=1`，只有 1 个音色且为女声，
所以 `speakerId` 只能填 `0`（默认），传别的值也不会变成男声。要男声需换多说话人模型
（如 aishell3、kokoro 等），换模型后 `speakerId` 可选 `0 until numSpeakers`，具体 id 对应
男声/女声请查该模型说明。

**为什么用 Matcha**：非自回归、3 步解码，实测合成耗时约为 VITS 的 1/10
（本机长句 136ms vs VITS int8 978ms vs melo 1715ms）。中文词典 + 英文词典
（`lexicon.txt,lexicon-en.txt`）并列加载实现中英混读；英文单词靠英文词典查音素，
字典里没有的词（如纯字母串 `ABCDEFD`）会被跳过——这是所有 phoneme 词典方案的共同限制。
`Config` 可指定 `modelDir`、`acousticModelName`、`vocoder`、`lexicons`、`ruleFsts`、
`numThreads`（默认 4）、`speakerId`、`speed`。

`Config` 可指定 `modelDir`（服务器下载到自定义目录时用）、`numThreads`、`hotwords`（热词）、
`verifyModel`（默认 true，加载前做 SHA-256 校验，避免半截文件导致 JNI 崩溃）、
`wakeWord`（唤醒词配置，见下）。
需要更细控制的场景可直接使用底层 `VoiceStreamRecognizer` / `VoiceMicCapture` / `VoiceAsrModels` /
`VoiceWakeWord` / `VoiceKwsModels`。

### 唤醒词（类小爱同学）

在 `Config` 里配置 `WakeWordConfig` 即可开启，初始化后 KWS 小模型（约 5MB）常驻监听，
命中后回调 `onWakeWord`；默认 `autoStartAsr=true` 会自动进入 ASR 识别，命令词结果仍走 `onFinal`：

```kotlin
val config = VoiceAsr.Config(
    wakeWord = VoiceAsr.WakeWordConfig(
        enable = true,             // 默认 false
        autoStartAsr = true,       // 命中后自动开始识别
        keywordsScore = 2.0f,          // 越大越容易触发（库内默认，官方默认 1.0）
        keywordsThreshold = 0.15f,     // 越小越容易触发；误唤醒多就调回 0.2~0.25（官方默认 0.25）
        numTrailingBlanks = 1,         // 调大会要求停顿更久、反而更钝（官方默认 1）
        maxActivePaths = 4,            // 仍不够灵敏可试 8，CPU 开销略增
    ),
)
VoiceAsr.init(this, config, callback)

// callback 里接收唤醒：
override fun onWakeWord(keyword: String) { /* 默认「小卓小卓」 */ }
```

**默认唤醒词是「小卓小卓」**：库会用它替换模型自带的 `keywords.txt`，
因此**不再响应**小爱同学/小艺小艺/你好问问等自带词。

自定义唤醒词有两种方式：

1. **代码字符串（推荐）**——传已 token 化的字符串，库自动生成词表文件并替换自带词表：

   ```kotlin
   wakeWord = VoiceAsr.WakeWordConfig(
       enable = true,
       keywords = "n ǐ h ǎo zh uó zh uó @你好卓卓",  // 每行一个词；灵敏度用下面字段配置
   )
   ```

2. **词表文件**——先把 `keywords` 置空，再用 `keywordsFile` 指向已 token 化的文件：

   ```kotlin
   wakeWord = VoiceAsr.WakeWordConfig(
       enable = true,
       keywords = "",
       keywordsFile = File(getExternalFilesDir(null), "my_keywords.txt"),
   )
   ```

唤醒词格式说明（ppinyin 模型，**不能直接写汉字**）：

- 中文词要拆成拼音声母+韵母 token，如 `小卓小卓` → `x iǎo zh uó x iǎo zh uó`；
- 灵敏度不用写在词表里：用 `keywordsScore`（越大越易触发，库内默认 2.0）和 `keywordsThreshold`（越小越易触发，库内默认 0.15）配置；
  词表里也支持行内 `:分数 #阈值`，但会覆盖配置值，现在库内默认词表不再使用这种写法；
- 调灵敏度的顺序：先把 `keywordsThreshold` 从 0.15 往下试（0.12/0.10），再把 `keywordsScore` 提到 2.5~3.0；
  `numTrailingBlanks` 保持 1，调大只会要求说完唤醒词后停顿更久；`maxActivePaths` 可试 8。
  误唤醒变多就把阈值往回调到 0.2~0.25。
- `@小卓小卓` 是命中回调 `onWakeWord` 返回的原文，ppinyin 模型**必须提供**且不能含空格；
- 生成 token 的标准做法：在项目根目录对原始词表执行
  `sherpa-onnx-cli text2token --tokens <kws目录>/tokens.txt --tokens-type ppinyin keywords_raw.txt keywords.txt`，
  其中 `keywords_raw.txt` 每行是 `汉字 :分数 #阈值 @汉字`。

- 运行期可 `VoiceAsr.setWakeWordEnabled(true/false)` 开关，无需重新 init。
- KWS 与 ASR 共用麦克风：唤醒监听期间麦克风归 KWS，命中/手动 start 后切给 ASR，
  stop/cancel 后自动恢复 KWS 监听。
- 常驻监听需要前台 Service + `foregroundServiceType="microphone"`（Android 9+），demo 为简化未做前台 Service，
  产品化时请按此改造以免后台被系统回收。

### 兼容性

- 依赖后即可用：JNI 与 ONNX Runtime 的 `.so` 由远程 AAR 自动打进 APK（arm64-v8a / armeabi-v7a /
  x86 / x86_64 均已验证）；**模型文件不包含在内**，需按下面步骤单独准备。
- minSdk 24（Android 5.0+），JDK 17 / Kotlin 2.0.21 / Compose 1.8，与主项目一致。
- `VoiceMicCapture` 内显式检查 `RECORD_AUDIO` 运行时权限，未授权抛 `SecurityException`，
  门面会转成 `onError` 回调；App 仍需自行申请权限。
- 依赖库本身不申请权限、不弹窗、不依赖任何 Activity/Fragment，可在 Service 中使用。

## 模型准备

模型约 190MB，不进 assets / APK，默认放在 app 外部私有目录：

```
/sdcard/Android/data/<applicationId>/files/sherpa/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/
```

开发机准备模型（脚本带 SHA-256 校验，可重复执行）：

```bash
python tools/fetch_sherpa_asr.py             # 下载 + 校验
python tools/fetch_sherpa_asr.py --push      # push 到已连接设备
python tools/fetch_sherpa_asr.py --package com.blue.voice.demo
```

`asr_models/manifest.json` 记录每个文件的大小与 SHA-256；`lib_asr` 里的
`assets/model_manifest.json` 供运行时做完整性校验（避免半截文件导致 JNI 崩溃）。
后续如需服务器下载，把 4 个模型文件 + manifest 放服务器即可，下载到同一目录，
`VoiceStreamRecognizer.create(modelDir)` 支持传入任意可读取目录。

唤醒词模型（约 5MB，启用唤醒词时必须）在：

```
/sdcard/Android/data/<applicationId>/files/sherpa/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile/
```

`python tools/fetch_sherpa_asr.py` 默认会连同 KWS 模型一起下载、校验、push；
只要 ASR 模型时加 `--no-kws`。KWS 包里自带 `keywords.txt`（含小爱同学/小艺小艺等），
自定义唤醒词需先用 `sherpa-onnx-cli text2token --tokens <kws目录>/tokens.txt --tokens-type ppinyin
keywords_raw.txt keywords.txt` 生成（注意 `tokens.txt` 路径指向 KWS 模型目录）。

### TTS 模型

默认用 `matcha-icefall-zh-en`（中英双语，非自回归），放外部私有目录，不进 assets / APK：

```
/sdcard/Android/data/<applicationId>/files/sherpa/matcha-icefall-zh-en/
    model-steps-3.onnx        # 声学模型 72MB
    vocos-16khz-univ.onnx     # 声码器 51MB（独立下载）
    lexicon.txt               # 中文发音词典
    lexicon-en.txt            # 英文发音词典（随仓库入库，脚本复制到此目录）
    tokens.txt / date-zh.fst / number-zh.fst / phone-zh.fst
```

模型包 75MB + 声码器 51MB，合计约 130MB。实测对比（同机、同一文本、4 线程）：
Matcha 短句 40ms / 长句 136ms；VITS int8 短句 357ms / 长句 978ms；
melo float32 短句 411ms / 长句 1533ms。Matcha 快一个数量级。

```bash
python tools/fetch_sherpa_asr.py --tts-only          # 只下载 + 校验 TTS
python tools/fetch_sherpa_asr.py --tts-only --push   # 推送到已连接设备
```

不加过滤参数时脚本会一次处理 ASR + KWS + TTS；也可用 `--no-asr` / `--no-kws` /
`--no-tts` 任意组合。`tts_models/manifest.json` 记录每个文件大小与 SHA-256。

## AAR 离线备份

`root_libs/sherpa-onnx-2.0.1.aar` 是官方 2.0.1 AAR 的本地备份（SHA-256:
`a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`），
仅用于断网或 JitPack 不可用时手工替换，**Gradle 不依赖它**。
正常构建一律走远程坐标，两个 lib 都通过 `api` 引入，Gradle 自动去重。

## 发布

仓库根目录的 `jitpack.yml` 使用 JDK 17，发布时执行：

```bash
./gradlew :lib_asr:publishToMavenLocal
./gradlew :lib_tts:publishToMavenLocal
```

打 tag（如 `2.0.0`）后在 JitPack 构建即可按上面坐标引用。
