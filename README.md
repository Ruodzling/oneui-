# One UI 小窗补全计划 (SamsungPopupScale)

[中文](README.md) | [English](README_EN.md)

面向三星 One UI（LSPosed 模块，包名 `io.github.s24u.popupscale`）的小窗显示缩放与手势增强开源代码仓库。

> **作者**：by 酷安 若冬之冷（[主页](https://www.coolapk.com/u/2970139)）  
> **开源协议**：GNU General Public License v3.0（详见 [`LICENSE`](LICENSE)）

## 开源说明

- 本项目源码已按 GPL-3.0 协议开源，**已移除内置的诊断采集、录屏及反馈打包导出功能**。
- 本仓库不再提供远程诊断排查；如果在不同机型、系统补丁或包含较多模块/改动的复杂环境下遇到兼容或显示问题，欢迎有动手能力的开发者直接查阅源码自行适配、调试或基于本仓库 Fork 维护。

## 主要功能

- **系统统一小窗内容缩放**：按参考宽度（`400 / 500 / 600 dp` 或自定义）调整小窗内应用渲染密度与布局；支持全部普通应用 + 排除名单，或仅对已选应用生效；支持主用户的系统分身配置档。缩放名单与小窗交互解耦，无边框、控制条、挂起与迷你窗等交互默认对全部普通应用开放。
- **应用进程兼容补全**：针对微信（画布缩放与对话框适配）、QQ / TIM（小窗切换密度校准防重启与输入面板尺寸兼容）、哔哩哔哩（9.20+ 播放器小窗全屏适配）、抖音（评论/分享面板及迷你窗挂起播放状态保持）、微博等应用提供独立兼容策略。
- **顶部无边框**：移除自由窗口顶部装饰栏轮廓并同步重设内容内边距与裁切范围。
- **底部小横条手势与透明度调节**：
  - 慢拖向内缩小并挂起为实时迷你小窗；快速向内甩关闭（或退到后台保留最近任务）；向外甩全屏。
  - 长按控制条（及支持的原生顶部滚动菜单入口）可调节小窗透明度。
- **实时迷你小窗与多卡堆叠**：支持自由拖动、惯性移动、贴边半隐藏图标（空闲自动半透明）、边缘拖出关闭、多迷你窗自动堆叠与左右滑动切卡、展开一张后其余迷你窗自动归位右上角，以及返回桌面时对活动迷你任务的最小化保护。
- **详细通知横幅下拉小窗** & **One Hand Operation+ 快速启动器小窗重定向**。
- **状态与接口检测**：点击模块首页顶部状态栏可查看 `android`（系统框架）、`com.android.systemui`（系统界面）与 `com.sec.android.app.launcher`（One UI 主屏幕）三个核心进程的注入与接口匹配状态。

## 运行环境与作用域

- **LSPosed**：`xposedminversion >= 93`，依赖 `xposedsharedprefs`。
- **Android / One UI 版本**：面向三星 One UI 7 / 8 / 9（Android 15–17）按实际进程类与方法签名自适应匹配；APK 构建配置为 `minSdk 28` / `compileSdk 35` / `targetSdk 35`。
- **作用域列表**（与 `app/src/main/res/values/resources.xml` 中的 `xposed_scope` 一致）：
  - 核心系统作用域：`android`、`com.android.systemui`、`com.sec.android.app.launcher`
  - 手势联动：`com.samsung.android.sidegesturepad`（One Hand Operation+）
  - 权限与按需应用兼容：`com.google.android.permissioncontroller`、`com.tencent.mm`、`com.tencent.mobileqq`、`com.tencent.tim`、`tv.danmaku.bili`、`com.ss.android.ugc.aweme`、`com.sina.weibo`、`com.coolapk.market`

## 本地构建与验证

### 环境要求

- JDK 17 或 JDK 21（`JAVA_HOME`）
- Android SDK Platform 35 与 Build Tools `35.0.0`（配置 `ANDROID_HOME`，或在项目根目录创建 `local.properties` 指定 `sdk.dir=...`）

### 构建 APK

Linux / macOS：

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

> **签名说明**：若未在本地 `.local/signing/debug.keystore` 放置自定义调试签名或未通过环境变量提供 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`，Gradle 将自动回退至当前环境的 Debug 签名配置，可直接克隆编译。

### 离线测试与检查

```powershell
.\gradlew.bat :app:verifyReleaseNotes :app:verifyGesturePolicy :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
powershell -ExecutionPolicy Bypass -File .\checks\Run-PolicyChecks.ps1
```

## 目录结构

- `app/src/main/java/io/github/s24u/popupscale/`：Xposed 主入口、设置界面、兼容性上报及各应用缩放/密度兼容策略
- `app/src/main/java/io/github/s24u/popupscale/gestures/`：SystemUI / 系统框架 / Launcher 手势、底部控制条、顶部无边框、实时迷你窗、窗口透明度及通知下拉实现
- `app/src/test/`：缩放、作用域、手势及生命周期约束离线单元测试
- `app/src/androidTest/`：设备端只读接口与行为检测用例
- `checks/`：独立纯 Java 策略校验脚本

## 协议与致谢

- 本项目采用 [GNU General Public License v3.0](LICENSE) 开源。
- 第三方依赖与参考说明见 [`THIRD_PARTY.md`](THIRD_PARTY.md)。
