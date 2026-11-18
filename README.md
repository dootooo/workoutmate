# WorkoutMate

**离线跳绳计数 App / Offline jump-rope counter for Android**

WorkoutMate 使用手机摄像头和端侧姿态估计计数，视频与姿态数据不离开设备。项目目前处于
**debug / 研究阶段**：正式计数仍使用髋部信号计数器；第二阶段分类器和轮换跳检测器正在
离线验证和 debug 影子模式中测试。

WorkoutMate counts jump-rope reps from the phone camera using on-device pose
estimation. Video and pose data stay on the device. The project is currently in
a **debug / research phase**: production counting still uses the hip-signal
counter, while the Phase 2 classifier and alternating-step detector are being
validated offline and in debug shadow mode.

---

## 当前状态 / Current status

| 项目 / Item | 状态 / Status |
|---|---|
| 正式计数 / Production counter | 可用，但轮换跳和停跳段仍不完美 / Works, but alternating-step and post-stop cases are imperfect |
| 第二阶段分类器 / Phase 2 classifier | 离线验证，未接入正式计数 / Offline only, not in production counting |
| 影子模式 / Shadow mode | debug 版可用，只写日志 / Debug builds only, log-only |
| 数据 / Data | 仅成人数据参与当前模型；儿童数据排除 / Adult-only model; children's data excluded |
| 平台 / Platform | Android API 24–37 |

### 当前实测 / Recent measurements

四组各实跳 100 个 / Four sessions, 100 real jumps each:

| 风格 / Style | App 计数 / App count | 说明 / Notes |
|---|---:|---|
| 并脚 / Feet together | 103 | 基本准确，停后偶尔多 2–3 / Close; occasional +2–3 after stop |
| 并脚 / Feet together | 105 | 基本准确 / Close |
| 轮换高抬腿 / Alternating, high lift | 90 | 少计 / Undercount |
| 轮换低抬腿 / Alternating, low lift | 69 | 明显少计 / Significant undercount |

影子分类器在这些组上只有约 44–81，说明当前模型还不能直接做实时门控。

The shadow classifier produced only about 44–81 on these sessions, so it is not
ready to gate the live counter yet.

---

## 功能 / Features

- 前置摄像头实时计数 / Front-camera real-time counting
- 自由跳和定数模式 / Free and target-count modes
- MoveNet Lightning 17 点单姿态估计，TensorFlow Lite 端侧推理 / On-device MoveNet Lightning pose estimation
- 全身入镜引导、停跳自动暂停、恢复候选 / Full-body guidance and pause/resume handling
- 跟脚波纹反馈 / Foot ripple feedback
- 中英文界面，跟随系统语言 / Chinese/English UI based on system language
- 首页液体 shader 背景；API 33+ 使用 RuntimeShader，API 24–32 使用 Canvas 兼容实现 / Liquid shader home background with a Canvas fallback
- 隐私说明、服务条款、第三方许可页面 / Privacy, terms and third-party license pages
- debug：自动屏幕录制、影子分类器日志 / Debug: screen recording and shadow-classifier logs

---

## 构建 / Build

要求 / Requirements: Android Studio, JDK 17, Android SDK.

```powershell
$env:JAVA_HOME='D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:installDebug --console=plain --no-daemon
```

单元测试 / Unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
```

---

## 计数原理 / How counting works

当前正式链路 / Current production pipeline:

```text
MoveNet 17 点
→ 肩/髋中心归一化
→ 髋部纵向信号
→ EMA + 自适应带回差
→ 过零事件
→ 计数
```

第二阶段研究 / Phase 2 research:

```text
姿态窗口 → 23 维时序特征 → 轻量分类器
→ 时间状态机（ACTIVE / FROZEN）
→ 离线门控模拟
```

轮换跳候选检测原型 / Alternating-step candidate prototype:

```text
风格比例 = 左右脚高度差幅度 / 髋部幅度
比例 < 0.5  → 并脚：髋部候选
比例 ≥ 0.5  → 轮换：髋部 + 较高脚融合候选
```

四组实跳 100 个的离线候选数 / Offline candidate counts:

```text
并脚：91、92
轮换：101、99
```

---

## 已知限制 / Known limitations

- 并脚跳通常较准，停跳后偶尔多 2–3 个 / Feet-together is usually close; a few extra counts can appear after stopping
- 轮换跳会少计，低抬腿最明显 / Alternating-step undercounts, especially with a low leg lift
- 停跳后整理跳绳、甩手、挠头仍可能误计 / Rope adjustment, arm swings and head scratching can still be counted
- 影子分类器仍会漏掉部分真实跳跃 / The shadow classifier still misses some real jumps
- 正式计数尚未接入分类器或轮换检测 / Production counting does not yet use the classifier or alternating detector

---

## 第二阶段文件 / Phase 2 files

| 文件 / File | 用途 / Purpose |
|---|---|
| `tools/phase2_offline_classifier.py` | 窗口特征、训练、留一验证 / Window features and classifier validation |
| `tools/phase2_gate_simulation.py` | 逐事件门控模拟 / Event-gate simulation |
| `tools/phase2_time_gate_simulation.py` | 时间状态机门控模拟 / Time-state gate simulation |
| `tools/phase2_alternating_detector.py` | 风格自适应轮换跳候选原型 / Style-aware alternating-step candidate prototype |
| `data/phase2_manifest.csv` | 成人标签清单 / Adult label manifest |
| `data/jump_gate_model.json` | 逻辑回归模型 / Logistic model |
| `app/src/main/assets/jump_gate_model.json` | debug 影子模式使用的模型 / Model used by debug shadow mode |

---

## Debug 工具 / Debug tools

### 屏幕录制 / Screen recording

进入计数页首次请求系统录屏权限；开始计数自动录制，结束自动停止。视频保存在本地：

```text
/sdcard/Android/data/com.example.workoutmate/files/screen-recordings/
```

A system MediaProjection prompt appears on first entry. Recording starts and
stops with each counting session and stays local.

### 影子模式 / Shadow mode

`JumpGateShadow.kt` 在 debug 版中加载模型、计算窗口概率并运行状态机，只写日志，
不修改 `JumpCounter` 或界面计数。

`JumpGateShadow.kt` loads the model, scores windows and runs the state machine in
debug builds. It writes logs only and never changes `JumpCounter` or the
visible count.

```text
/data/data/com.example.workoutmate/files/shadow-gate.log
```

拉取日志 / Pull the log:

```powershell
adb exec-out run-as com.example.workoutmate cat files/shadow-gate.log > shadow-gate.log
```

---

## 数据与隐私 / Data and privacy

- 正式功能只要求 CAMERA 权限 / CAMERA permission is required for the production feature
- debug 录屏额外使用前台服务和 MediaProjection，不请求网络权限 / Debug recording uses a foreground service and MediaProjection; no network permission
- 视频、姿态 JSONL、录屏都留在本地 / Videos, pose JSONL and recordings stay local
- 原始姿态数据不提交到 Git / Raw pose data is not committed
- 儿童参与者的数据已从当前成人模型中排除 / Children's data is excluded from the adult model
- 收集真实数据前取得同意；未成年人需监护人同意 / Obtain consent before collecting data; minors need guardian consent

采集协议 / Collection protocol: [DATA_COLLECTION.md](DATA_COLLECTION.md)

---

## 目录 / Repository layout

```text
app/                    Android 应用 / Android app
  src/main/java/...     Kotlin 源码 / Kotlin sources
  src/main/assets/      MoveNet 与影子模型 / MoveNet and shadow model
  src/test/             回放回归测试 / Replay regression tests
data/                   标签、模型、分析结果 / labels, model, analysis; raw JSONL ignored
tools/                  Python 离线分析 / offline analysis and prototypes
DATA_COLLECTION.md      数据采集说明 / data collection protocol
THIRD_PARTY_NOTICES.md  第三方许可 / third-party notices
```

---

## 路线图 / Roadmap

1. 将风格自适应候选检测接入 debug 影子模式 / Integrate the style-aware candidate detector into debug shadow mode
2. 用真实并脚/轮换/停跳数据验证门控 / Validate gating on real feet-together, alternating and post-stop data
3. 验证通过后再接入正式计数 / Move into production counting only after validation
4. UI、可访问性和发布前检查 / UI, accessibility and release checks

---

## 第三方许可 / Third-party notices

本项目使用 MoveNet、TensorFlow Lite、CameraX 等开源组件，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

This project uses MoveNet, TensorFlow Lite, CameraX and other open-source
components. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
