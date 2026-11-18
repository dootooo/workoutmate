# WorkoutMate / 跳绳计数

WorkoutMate is an offline Android jump-rope counter. It uses the phone camera
and on-device pose estimation; no video or pose data leaves the device. The
project is currently in a **debug / research phase**: the shipping counter is
still the original hip-signal counter, while a second-stage jump/non-jump
classifier and an alternating-step detector are being validated offline and in
a debug shadow mode.

WorkoutMate 是一个离线 Android 跳绳计数 App。它使用手机摄像头和端侧姿态估计，
视频与姿态数据不会离开设备。项目目前处于 **debug / 研究阶段**：正式计数仍使用
原有的髋部信号计数器；第二阶段 jump/non-jump 分类器和轮换跳检测器正在通过离线
数据和 debug 影子模式验证。

### 中文

### 当前功能

- 摄像头实时跳绳计数，支持自由跳和定数模式
- MoveNet Lightning 单姿态估计，TensorFlow Lite 端侧推理
- 前置摄像头预览、全身入镜引导
- 停跳自动暂停、恢复候选
- 跟脚波纹反馈
- 中英文界面，跟随系统语言
- 透明状态栏、首页液体 shader 背景（API 33+ 使用 RuntimeShader，API 24–32 使用 Canvas 兼容实现）
- 隐私说明、服务条款、第三方许可页面
- debug 专用：自动屏幕录制、影子分类器日志

### 环境与构建

- Android Studio / JDK 17
- minSdk 24，targetSdk 37
- CameraX、TensorFlow Lite、Material Components

```powershell
$env:JAVA_HOME='D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:installDebug --console=plain --no-daemon
```

运行单元测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
```

### 当前算法与已知限制

正式计数链路：

```text
MoveNet 17 点 → 肩/髋归一化 → 髋部纵向信号
→ EMA + 自适应带回差 → 过零事件 → 计数
```

已知限制：

- 并脚跳通常较准确；停跳后偶尔多计 2–3 个
- 轮换跳（交替步）会少计，低抬腿时最明显
- 停跳后整理跳绳、甩手、挠头等动作仍可能被误计
- 当前 App 中的正式计数逻辑尚未接入第二阶段分类器

### 第二阶段 / Phase 2

当前重点是提高轮换跳和停跳场景的稳定性：

- 离线窗口分类器：`tools/phase2_offline_classifier.py`
- 门控模拟：`tools/phase2_gate_simulation.py`
- 时间状态机门控：`tools/phase2_time_gate_simulation.py`
- 风格自适应候选检测原型：`tools/phase2_alternating_detector.py`
- 模型文件：`data/jump_gate_model.json`，并复制到 `app/src/main/assets/jump_gate_model.json`
- 标签清单：`data/phase2_manifest.csv`

风格自适应原型结论：

```text
并脚跳：使用髋部信号
轮换跳：融合髋部 + 较高脚信号
```

四组实跳 100 个的候选数：

```text
并脚：91、92
轮换：101、99
```

这只是离线原型，尚未改变 App 正式计数。

### Debug 工具

- 屏幕录制：进入计数页首次请求系统录屏权限；开始计数自动录制，结束自动停止
- 影子模式：`JumpGateShadow.kt` 在 debug 版中运行分类器，只写日志，不修改界面计数
- 影子日志：

```text
/data/data/com.example.workoutmate/files/shadow-gate.log
```

拉取日志：

```powershell
adb exec-out run-as com.example.workoutmate cat files/shadow-gate.log > shadow-gate.log
```

### 数据与隐私

- App 只需要 CAMERA 权限
- 不请求网络权限，不上传视频或姿态
- debug 录屏、姿态 JSONL 均保存在本地
- 儿童参与者的数据已从当前成人模型中排除；原始文件不参与训练
- 采集真实数据前应取得参与者（未成年人需监护人）同意

更多采集协议见 [DATA_COLLECTION.md](DATA_COLLECTION.md)。

### 目录

```text
app/                    Android 应用
  src/main/java/...     Kotlin 源码
  src/main/assets/      MoveNet 与影子模型
  src/test/             回放回归测试
data/                   标签、模型、分析结果；raw JSONL 不提交
tools/                  Python 离线分析与原型脚本
DATA_COLLECTION.md      数据采集说明
THIRD_PARTY_NOTICES.md  第三方许可
```

### 路线图

1. 将风格自适应候选检测接入 debug 影子模式
2. 用真实轮换跳/停跳数据验证门控
3. 验证通过后，再将候选层接入正式计数
4. 完成 UI、可访问性和发布前检查

---

## English

### What it does

- Real-time jump-rope counting from the front camera
- Free mode and target-count mode
- MoveNet Lightning single-pose estimation with on-device TensorFlow Lite
- Full-body framing guidance, stop/resume handling, foot ripple feedback
- Chinese/English UI based on the system language
- Transparent status bar; liquid shader home background (RuntimeShader on API 33+, Canvas fallback on API 24–32)
- Privacy, terms and third-party license pages
- Debug-only screen recording and shadow-classifier logging

### Build

Requirements: Android Studio, JDK 17, Android SDK. minSdk 24, targetSdk 37.

```powershell
$env:JAVA_HOME='D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:installDebug --console=plain --no-daemon
```

Run unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
```

### Current counter and limitations

```text
MoveNet 17 keypoints → shoulder/hip normalization → hip vertical signal
→ EMA + adaptive-band hysteresis → zero-crossing events → count
```

Known limitations:

- Feet-together jumps are usually close; a few false counts can appear just after stopping
- Alternating-step jumps undercount, especially with a small leg lift
- Rope adjustment, arm swings and head scratching after stopping can still be counted
- The production counter does not yet use the Phase 2 classifier

### Phase 2

Current research work targets alternating-step jumps and post-stop false counts:

- Offline window classifier: `tools/phase2_offline_classifier.py`
- Event gate simulation: `tools/phase2_gate_simulation.py`
- Time-state gate simulation: `tools/phase2_time_gate_simulation.py`
- Style-aware candidate detector: `tools/phase2_alternating_detector.py`
- Model: `data/jump_gate_model.json` (also packaged in `app/src/main/assets/`)
- Label manifest: `data/phase2_manifest.csv`

Prototype result on four labelled 100-jump sessions:

```text
Feet together: 91, 92 candidates
Alternating:   101, 99 candidates
```

The prototype is offline only and does not change the production count.

### Debug tools

- Screen recording: a system MediaProjection prompt appears on first entry;
  recording starts/stops with each counting session
- Shadow mode: `JumpGateShadow.kt` scores poses in debug builds and writes a
  log only; it never changes the visible count
- Shadow log:

```text
/data/data/com.example.workoutmate/files/shadow-gate.log
```

Pull it with:

```powershell
adb exec-out run-as com.example.workoutmate cat files/shadow-gate.log > shadow-gate.log
```

### Data and privacy

- Only CAMERA permission is required
- No network permission; videos and poses stay on the device
- Debug recordings and pose JSONL files are local
- Data from the participating children has been excluded from the adult model
- Obtain consent before collecting data; minors require guardian consent

See [DATA_COLLECTION.md](DATA_COLLECTION.md) for the annotation protocol.

### Repository layout

```text
app/                    Android app
  src/main/java/...     Kotlin sources
  src/main/assets/      MoveNet and shadow model
  src/test/             replay regression tests
data/                   labels, model, analysis results; raw JSONL is not committed
tools/                  Python offline analysis and prototypes
DATA_COLLECTION.md      data collection protocol
THIRD_PARTY_NOTICES.md  third-party notices
```

### Roadmap

1. Integrate style-aware candidate detection into debug shadow mode
2. Validate gating on real alternating-step and post-stop data
3. Move the candidate layer into production counting only after validation
4. UI, accessibility and release checks

---

## Third-party notices / 第三方许可

This project uses MoveNet, TensorFlow Lite, CameraX and other open-source
components. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

本项目使用 MoveNet、TensorFlow Lite、CameraX 等开源组件，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
