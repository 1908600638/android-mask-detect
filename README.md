# 口罩识别

## 功能

- 摄像头实时预览，点「识别」抓取当前这一帧
- 一次可检测多张人脸，逐个人脸画框并标注是否戴口罩
- 结果文本末尾汇总「共检测到 N 张人脸，其中 M 张正确佩戴口罩」
- 点一下画面回到实时预览

## 实现原理

### 整体流程

```
摄像头抓帧（JPEG）
   │
   ▼
ImageSource.fromJpeg()  ──▶  Bitmap（最长边 ≤ 2048，已旋正）
   │
   ▼
MaskEngine.scan()
   ├── 首次调用：initMask() 从 assets 加载 mask_min-sim-opt.bin/.param
   ├── detectMask(bitmap)
   │     │
   │     ▼
   │   JNI（yolov5ncnn_jni.cpp）
   │     ├── 图片 letterbox 缩放到 640×640 并补齐边
   │     ├── ncnn 前向推理，输出 3 个尺度的特征图
   │     ├── 按 stride 8 / 16 / 32 解出候选框（anchor + 置信度 + 类别）
   │     ├── NMS 去掉重叠框
   │     └── 反 letterbox：把框坐标映射回原图尺寸
   │
   ▼
List<Obj> ──▶ 转成 List<Detect> ──▶ BoxPainter 画框 ──▶ 预览层 + 结果文本
```

### 1. 推理引擎

用的是**腾讯 ncnn**——一个为手机端优化的 C++ 推理框架，以静态库形式随仓库附带，通过 CMake 编译成 `libyolov5ncnn.so`。

Java 层通过 JNI 调用，只暴露两个方法：`initMask(AssetManager)` 和 `detectMask(Bitmap, boolean)`。

### 2. 模型结构

YOLOv5s 系列，输入 640×640，输出 3 个尺度的检测头：

| stride | 网格 | 负责 |
|---|---|---|
| 8 | 80×80 | 小目标 |
| 16 | 40×40 | 中目标 |
| 32 | 20×20 | 大目标 |

每个格子按 3 个 anchor 解码，类别数 2。解码公式就是 YOLOv5 官方的：

```
cx = (2σ(tx) - 0.5 + gridX) × stride
w  = (2σ(tw))² × anchorW
```

置信度阈值 0.25，NMS 阈值 0.45。

### 3. letterbox 与坐标还原

模型要求固定输入尺寸，直接拉伸会变形。所以先把图等比缩放到 640×640，空白处补灰边（letterbox），推理完再把框坐标按相同比例映射回原图，保证框画在原图的正确位置。

### 4. 类别名来自模型

`detectMask` 里硬编码了类别表：

```c
static const char* class_names_6[] = {"戴上了口罩", "并未正确佩戴口罩"};
```

所以 Java 层不需要做标签映射，直接用 `obj.label` 就是中文。

## 代码架构

```
app/src/main/
├── jni/
│   ├── CMakeLists.txt                    链接 ncnn 并编译 libyolov5ncnn.so
│   ├── yolov5ncnn_jni.cpp         457 行  推理 + YOLOv5 后处理（只保留口罩模型）
│   └── ncnn-20230816-android-vulkan/      ncnn 静态库与头文件（31 MB，第三方）
├── assets/
│   ├── mask_min-sim-opt.bin       26.8 MB 模型权重
│   └── mask_min-sim-opt.param            模型结构描述
└── java/com/shizi/mask/
    ├── MainActivity.java          178 行  摄像头预览、抓帧、结果展示
    ├── img/
    │   └── ImageSource.java        74 行  JPEG 解码 + 降采样 + 旋正
    ├── scan/
    │   ├── Scanner.java            13 行  引擎接口
    │   ├── Detect.java             17 行  一条结果
    │   ├── ScanOutcome.java        17 行  结果集合 + 汇总文本
    │   ├── BoxPainter.java         84 行  画框与标签
    │   ├── YoloDetector.java       18 行  native 方法声明
    │   └── MaskEngine.java        100 行  ★ 加载模型、推理、转结果
    └── util/
        └── UiKit.java              11 行  Toast
```

### 各文件职责

| 文件 | 职责 |
|---|---|
| `MainActivity` | 相机权限 → CameraX 预览 → 抓帧 → 调引擎 → 显示标注图和文字 |
| `ImageSource` | JPEG 解码、降采样到 2048、旋正 |
| `Scanner` | 引擎接口，隔离具体实现 |
| `Detect` / `ScanOutcome` | 结果模型 |
| `BoxPainter` | 把框和标签画到图上 |
| `YoloDetector` | 只声明 `initMask` / `detectMask` 两个 native 方法，加载 `libyolov5ncnn.so` |
| `MaskEngine` | **核心**：懒加载模型、调推理、把 `Obj[]` 转成 `Detect`、统计口罩数量 |
| `yolov5ncnn_jni.cpp` | **核心**：YOLOv5 的完整推理与后处理实现 |

### 核心代码位置

| 位置 | 作用 |
|---|---|
| `MaskEngine.scan()` | 懒加载模型 + 一次推理 |
| `MaskEngine.describe()` | 统计佩戴数量并拼文本 |
| `yolov5ncnn_jni.cpp` → `initMask` | 从 assets 读模型并建 ncnn::Net |
| `yolov5ncnn_jni.cpp` → `detectMask` | letterbox、前向、解码、NMS、坐标还原 |
| `yolov5ncnn_jni.cpp` → `generate_proposals` | 单尺度候选框解码 |
| `yolov5ncnn_jni.cpp` → `nms_sorted_bboxes` | 非极大值抑制 |

## 环境要求

| 项 | 版本 |
|---|---|
| Android Studio | 2021.3.1（Dolphin）及以上 |
| JDK | 11 或 17 |
| Android Gradle Plugin | 7.3.1 |
| Gradle | 7.4 |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0） |
| **NDK** | **23.1.7779620** |
| **CMake** | **3.10.2** |
| ABI | 只编 **arm64-v8a** |
| 设备 | ARM64 真机 + 摄像头 |

NDK 和 CMake 可以在 Android Studio 的 SDK Manager → SDK Tools 里勾选安装，版本对不上时在 `app/build.gradle` 里改 `cmake { version }` 即可。

> 只出 arm64-v8a 是因为仓库里附带的 ncnn 静态库只包含 arm64-v8a 和 armeabi-v7a 两套，为控制体积只留了 64 位。需要 32 位可以把 `abiFilters` 改成 `'arm64-v8a', 'armeabi-v7a'`。

## 编译运行

1. 用 Android Studio 打开本目录
2. 确认 SDK Manager 里装了 NDK 23.1.7779620 和 CMake 3.10.2
3. 首次打开会自动生成 `local.properties`
4. 连接 ARM64 真机，点 Run
5. 首次启动请求相机权限，允许后看到实时画面
6. 对准人脸，点「识别」

命令行构建：

```bash
./gradlew assembleDebug
```

首次构建会先跑 CMake 编译 C++，比纯 Java 工程慢一些（约 20 秒）。产物在 `app/build/outputs/apk/debug/`。

> 工程放在中文路径下时 AGP 会报非 ASCII 路径错误，本工程已在 `gradle.properties` 加了一行 `android.overridePathCheck=true`。

## 依赖

| 依赖 | 用途 | 许可 |
|---|---|---|
| [ncnn](https://github.com/Tencent/ncnn) 20230816（含 Vulkan） | 端侧推理框架，静态库随仓库附带 | BSD-3-Clause，版权所有 Tencent，全文见 `app/src/main/jni/ncnn-20230816-android-vulkan/LICENSE` |
| CameraX 1.3.0（core / camera2 / lifecycle / view） | 摄像头预览与抓帧 | Apache-2.0 |
| AndroidX AppCompat、Activity、Core | 基础 UI | Apache-2.0 |
| `mask_min-sim-opt.bin` | YOLOv5 训练得到的口罩检测模型 | 仅用于学习演示 |
