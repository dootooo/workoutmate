# WorkoutMate

**离线跳绳计数 App / Offline jump-rope counter for Android**

WorkoutMate 使用手机摄像头和端侧姿态估计计数，视频与姿态数据不离开设备。正式计数已加入
锁定躯干尺度、下肢运动冻结和停跳后禁止自动恢复；第二阶段分类器和轮换跳候选检测仍在
离线验证和 debug 影子模式中测试。

WorkoutMate counts jump-rope reps from the phone camera using on-device pose
estimation. Video and pose data stay on the device. Production counting now
uses a locked torso scale, lower-body motion freeze and no automatic resume
after a pause; the Phase 2 classifier and alternating-step candidate detector
are still offline / debug-shadow research.

---

## 当前状态 / Current status

| 项目 / Item | 状态 / Status |
|---|---|
| 正式计数 / Production counter | 可用；停跳/擦汗/叉腰冻结已验证，轮换跳仍会少计 / Usable; post-set arm movements are frozen, alternating still undercounts |
| 第二阶段分类器 / Phase 2 classifier | 离线验证，未接入正式计数 / Offline only, not in production counting |
| 影子模式 / Shadow mode | debug 版可用，只写日志 / Debug builds only, log-only |
| 数据 / Data | 仅成人数据参与当前模型；儿童数据排除 / Adult-only model; children's data excluded |
| 平台 / Platform | Android API 24–37 |

### 当前实测 / Recent measurements

最近一次分段测试 / Latest segmented test:

```text
实跳 20 + 10 + 20 = 50
App 计数 47
站定、挠头、擦汗、叉腰未增加
```

之前四组各实跳 100 个 / Previous four sessions, 100 real jumps each:

| 风格 / Style | App 计数 / App count | 说明 / Notes |
|---|---:|---|
| 并脚 / Feet together | 103 | 基本准确 / Close |
| 并脚 / Feet together | 105 | 基本准确 / Close |
| 轮换高抬腿 / Alternating, high lift | 90 | 少计 / Undercount |
| 轮换低抬腿 / Alternating, low lift | 69 | 明显少计 / Significant undercount |

当前剩余误差主要是少计，不再由站定/擦汗动作造成。  
The remaining error is mainly undercount, not post-set arm false positives.

---

## 功能 / Features

- 前置摄像头实时计数 / Front-camera real-time counting
- 自由跳和定数模式 / Free and target-count modes
- MoveNet Lightning 17 点单姿态估计，TensorFlow Lite 端侧推理 / On-device MoveNet Lightning pose estimation
- 全身入镜引导、停跳自动暂停、手动结束（停跳后不自动恢复） / Full-body guidance, pause, manual finish (no auto-resume)
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
→ 锁定躯干长度
→ 髋部纵向信号（轮换时使用较高脚）
→ EMA + 自适应带回差
→ 下肢运动冻结门控
→ 计数
```

停跳 3 秒后暂停并冻结；擦汗、挠头、叉腰、走动不会自动恢复计数。  
After a 3-second pause the counter freezes; arm/upper-body movements cannot
resume it automatically.

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

- 停跳/擦汗/叉腰已冻结，不再继续计数 / Post-set arm movements are frozen
- 并脚跳通常较准 / Feet-together is usually close
- 轮换跳仍会少计，低抬腿最明显 / Alternating-step undercounts, especially with a low leg lift
- 分段测试 50 个计 47，当前主要误差是少计 / Segmented 50-jump test counted 47; remaining error is undercount
- 停跳后不自动恢复；需要点“结束”再开新一组 / No auto-resume; tap Stop and start a new set
- 影子分类器仍会漏掉部分真实跳跃 / The shadow classifier still misses some real jumps

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

1. 继续降低轮换跳少计，同时保持停跳冻结 / Reduce alternating-step undercount without losing the post-set freeze
2. 用真实并脚/轮换/停跳数据验证候选检测 / Validate candidate detection on real feet-together, alternating and post-stop data
3. 验证通过后再把候选检测接入正式计数 / Move candidate detection into production only after validation
4. UI、可访问性和发布前检查 / UI, accessibility and release checks

---

## 第三方许可 / Third-party notices

本项目使用 MoveNet、TensorFlow Lite、CameraX 等开源组件，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

This project uses MoveNet, TensorFlow Lite, CameraX and other open-source
components. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
