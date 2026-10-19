# AI Live Overflow —— 修复版

这是你那个半成品的**修复版**。原仓库（`MugenX40620/AI-Live-Overflow`）因为几个构建配置问题，APK 装到手机上一点就闪退。这份已把问题全部修好，并内置了一只**不需要任何图片素材**的像素风桌宠。

---

## 一、原来为什么闪退（三个根因）

1. **`app/build.gradle` 缺 Kotlin 插件** —— 源码全是 `.kt`，但编译模块没声明 `org.jetbrains.kotlin.android`，导致 Kotlin 代码根本没被打进 APK。APK 里没有 `MainActivity` / `OverlayService` 这两个类，点开 app 系统找不到入口类 → **秒闪退**。
2. **仓库里有两个 `app` 目录打架** —— `settings.gradle` 只 `include ':app'`，而你真正写对的那份配置（带 Kotlin 插件 + 完整依赖 + 前台服务通知）放在 `AiLiveOverflow/app/` 里，那个目录压根没参与编译。
3. **`app/` 的 OverlayService 没调 `startForeground()`** —— `targetSdk` 较高的系统上，前台服务启动后 5 秒内不调用 `startForeground` 会直接崩。

## 二、这一版改了什么

| 项 | 说明 |
| --- | --- |
| 目录统一 | 只保留一个 `app/` 模块，删掉冗余的 `AiLiveOverflow/app/` |
| Kotlin 插件 | 根 `build.gradle` 声明版本，`app/build.gradle` 正确 apply |
| 前台服务 | `OverlayService` 里 `startForeground` + 常驻通知 + 通知渠道 |
| 渲染方式 | 改为 **WebView 加载本地 `assets/pet.html`**（蓝图的标准做法） |
| 桌宠形象 | 纯 SVG 手绘的**黑色小狼**，琥珀色杏仁眼 + 黑雾 + 三颗飘浮眼珠子，零图片素材 |
| 手势 | 拖拽 / 单击 / 双击 / 长按，都会触发不同表情 + 气泡 |

## 三、桌宠能干什么（当前版本）

- 呼吸：身体轻微上下浮动
- 耳朵会偶尔抖一抖、尾巴左右摇
- 身体周围飘着三颗暗红色小眼珠子（会漂浮、瞳孔会转）
- 身体底下笼着一层缓缓飘动的黑雾
- 自动眨眼
- 单击 → 开心回应 + 气泡（"嗯？""在的。"……）
- 双击 → 更开心 + 气泡（"最喜欢你啦！"）
- 长按 → 害羞 + 腮红加深
- 拖拽 → 跟着手指走，可放到屏幕任意位置
- 偶尔自言自语冒一句（约 26 秒一次，嫌吵可删 `pet.html` 末尾那段 `setInterval`）

所有气泡文案都在 `app/src/main/assets/pet.html` 的 `phrases` 对象里，改成你们自己的话即可。

## 四、怎么把它变成能装的 APK

### 方式 A（最快，本地直接装手机）

1. 电脑装 [Android Studio](https://developer.android.com/studio)
2. 用 Android Studio 打开**这个 `AiLiveOverflow-fixed` 文件夹**
3. 等它同步完，点顶部绿色 ▶ Run，选你的手机（手机开 USB 调试）
4. 或者菜单 Build → Build APK(s)，产物在 `app/build/outputs/apk/debug/app-debug.apk`，拷到手机装

### 方式 B（走 GitHub 自动构建，和之前一样能下载）

1. 把这个文件夹里的**所有文件**，替换到你 GitHub 仓库 `MugenX40620/AI-Live-Overflow` 里（覆盖旧文件，删掉旧的 `AiLiveOverflow/app/` 和 `examples/`、`docs/` 可留可删）
2. push 到 `main` 分支
3. GitHub Actions 会自动跑 `android-ci.yml`，2 分钟左右在 **Actions → 最新一次运行 → Artifacts** 里下载 `app-debug` 的 APK

> 注意：装好后第一次打开，手机会弹「悬浮窗权限」，点允许，桌宠才会出现。

## 五、项目结构

```
AiLiveOverflow-fixed/
├── settings.gradle
├── build.gradle               ← 声明 Kotlin / AGP 版本
├── gradle.properties
├── .github/workflows/android-ci.yml
└── app/
    ├── build.gradle           ← 已加 Kotlin 插件
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/pet.html    ← 桌宠形象（核心，纯 SVG）
        ├── java/com/aivo/liveoverflow/
        │   ├── MainActivity.kt
        │   └── service/OverlayService.kt
        └── res/...
```

## 六、下一步（可选扩展）

按蓝图 `docs/` 里的思路，可以继续加：前台 App 检测、截图摆 pose、充电/时段感知、Supabase 后端同步让 AI 主动推送话术和表情、通知栏碎碎念等。这些是增量功能，不影响现在这版先跑起来。
