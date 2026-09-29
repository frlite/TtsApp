# TTS 语音播报应用

一个安卓文字转语音（TTS）应用，支持无限循环播放和后台播放。

## 功能特性

- **文字转语音**：输入文字，自动转换为语音播放
- **无限循环播放**：语音播放完毕后立即重新开始，无间隔
- **后台播放**：使用前台服务，应用切到后台或锁屏后继续播放
- **语速调节**：支持 0.5x - 2.0x 语速调节
- **音调调节**：支持 0.5x - 2.0x 音调调节
- **语音选择**：支持选择系统可用的不同语音（如果 TTS 引擎提供多个语音）

## 项目结构

```
TtsApp/
├── app/
│   ├── build.gradle.kts          # 模块级构建配置
│   ├── proguard-rules.pro        # ProGuard 规则
│   └── src/main/
│       ├── AndroidManifest.xml   # 应用清单
│       ├── java/com/example/ttsapp/
│       │   ├── MainActivity.kt   # 主界面 Activity
│       │   └── TtsService.kt     # TTS 前台服务
│       └── res/
│           ├── layout/           # 布局文件
│           ├── values/           # 资源值（字符串、颜色、主题）
│           ├── drawable/         # 图标资源
│           └── mipmap-anydpi-v26/ # 自适应图标
├── build.gradle.kts              # 项目级构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # Gradle 属性
├── gradlew                       # Gradle Wrapper 脚本
└── gradle/wrapper/
    ├── gradle-wrapper.jar        # Gradle Wrapper JAR
    └── gradle-wrapper.properties # Wrapper 配置
```

## 构建和运行

### 前置要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34
- 支持 TTS 的 Android 设备或模拟器

### 构建步骤

1. 使用 Android Studio 打开项目：
   - `File` → `Open` → 选择 `TtsApp` 目录

2. 等待 Gradle 同步完成

3. 连接设备或启动模拟器

4. 点击 `Run` 按钮（或按 `Shift + F10`）

### 命令行构建

```bash
cd TtsApp
./gradlew assembleDebug
```

生成的 APK 位于：`app/build/outputs/apk/debug/app-debug.apk`

## 使用说明

1. **输入文字**：在文本框中输入要转换的文字
2. **调节语速**：拖动语速滑块（0.5x - 2.0x）
3. **调节音调**：拖动音调滑块（0.5x - 2.0x）
4. **选择语音**：从下拉列表中选择可用的语音（如果有多条语音）
5. **开始播放**：点击"开始播放"按钮
6. **停止播放**：点击"停止播放"按钮

## 技术实现

- **TTS 引擎**：使用 Android 原生 `android.speech.tts.TextToSpeech` API
- **后台播放**：通过 `Service` + `startForeground()` 实现前台服务
- **循环播放**：在 `UtteranceProgressListener.onDone()` 回调中重新调用 `speak()`
- **语音选择**：通过 `TextToSpeech.Voice` API 获取和设置可用语音

## 注意事项

- 设备需要安装 TTS 引擎（如 Google 文字转语音引擎）
- 部分设备可能不支持中文语音，需要安装相应的语言包
- Android 13+ 需要通知权限才能显示前台服务通知
- 循环播放会持续消耗电量，建议在不需要时停止播放
