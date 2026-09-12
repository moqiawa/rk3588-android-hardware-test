# 摄像头场景模型与 ROI 交付记录

## 当前状态

- 每路摄像头独立保存固定/变化场景、归一化 ROI 与基线 ROI；旧基线缺少 ROI 元数据时必须重建。按“设置 ROI”后可在 16:9 预览直接拖出草稿框，再确认保存。
- 固定场景对 ROI 裁剪后的像素建立基线并执行规则、RKNN、空间遮挡、局部强光与相对过曝诊断。
- 变化场景显示“变化场景模型尚未配置”，当前不运行基线建立或录像检测；仍可预览、切换摄像头、设置 ROI 与调整相机参数。
- Camera2 已单独发布实际曝光支持状态、帧率、输出尺寸和亮度统计，供右侧面板显示。

## 真机验收矩阵

1. 全画面默认 ROI：建立基线、录制检测，确认固定场景模型可用。
2. 局部 ROI：确认裁剪框、建立基线、录制检测；修改 ROI 后确认提示重建基线。
3. 摄像头切换：确认模式、ROI 和基线状态彼此隔离。
4. 分辨率切换：确认预览持续，并记录实际输出尺寸、曝光支持状态和 FPS。
5. 变化场景：确认显示“模型尚未配置”，导入基线、录制基线与录制检测均不可执行。
6. 固定场景异常：局部手电筒、半画面遮挡、失焦和偏色分别验证摘要与 AI 状态。

## 设备可达性

当前开发环境未配置 `adb` 命令，无法连接或安装到目标设备；请在配有 Android Platform Tools 的 RK3588 验收机上执行上述矩阵，并记录右侧面板指标、ROI、AI 状态和最终摘要。

## 关键文件

- `app/src/main/java/com/rk/hardwaretest/camera/CameraSceneProfile.kt`：场景、ROI、裁剪和配置持久化。
- `app/src/main/java/com/rk/hardwaretest/camera/NormalSceneBaselineStore.kt`：基线与 ROI 元数据。
- `app/src/main/java/com/rk/hardwaretest/MainActivity.kt`：录像解码、ROI 编辑和场景工作流。
- `app/src/main/java/com/rk/hardwaretest/camera/Camera2PreviewController.kt`：Camera2 实际指标发布。

## 最近验证

2026-09-12 已在开发机执行 `./gradlew.bat testDebugUnitTest assembleDebug --console=plain --quiet`，命令以退出码 0 完成，调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。当前环境仍无 `adb`，尚未完成真机矩阵。
