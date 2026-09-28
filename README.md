# 微信视频号短剧推广跳过模块

看微信视频号短剧时，有些剧集之间会插入一段必须等待几秒、暂时不能上滑的推广视频。这个 Android 模块用于处理这种情况。

**当前只适配微信 8.0.78（versionCode 3180）。** 其他微信版本的内部代码可能不同，不能保证生效。

## 它能做什么

- **v0.4.0：**尝试阻止推广视频进入短剧列表，让剧集直接衔接。如果推广视频仍然出现，保留提前上滑的功能。
- **v0.3.0：**推广视频出现后，无需等待倒计时结束，可以直接上滑看下一集。

目前，**提前上滑已在真机上验证成功**；v0.4.0 新增的“推广视频完全不出现”功能已编译并安装，**还没有完成真机效果验证**。因此，安装 v0.4.0 后仍可能看到推广视频。

## 安装与使用

你需要一部已获取 Root 权限、能正常运行 **LSPosed 或兼容框架**的 Android 手机，并安装微信 **8.0.78**。这个模块不是普通的独立 App；安装 APK 后，还要在框架中启用它。

1. 下载并安装 [v0.4.0 APK](dist/WeChatDramaAdSkip-v0.4.0-wechat8.0.78.apk)。手机上打开 APK 安装即可。
2. 打开 LSPosed 或兼容框架，启用 **「视频号短剧去推广」**模块。
3. 在模块的**作用域**中勾选 **微信（`com.tencent.mm`）**。
4. 在手机设置中**强制停止微信**，然后重新打开微信。
5. 进入视频号短剧继续观看。模块会尝试过滤推广视频；如果仍出现，可在倒计时结束前尝试上滑。

使用 Android 调试工具安装时，也可以在仓库目录执行：

```sh
adb install -r dist/WeChatDramaAdSkip-v0.4.0-wechat8.0.78.apk
```

Android 16 上的一种可用组合是 KernelSU + [NeoZygisk](https://github.com/JingMatrix/NeoZygisk) + [Vector](https://github.com/JingMatrix/Vector)。如果你的 LSPosed 兼容环境已经正常工作，无需重复安装这些组件。

## 没有生效怎么办

1. 在微信「我 → 设置 → 关于微信」确认版本是 **8.0.78**。
2. 确认模块已启用、作用域包含微信，然后强制停止并重开微信。
3. 进入一次短剧页面后，在 LSPosed/Vector 日志中搜索 `WeChatDramaAdSkip`。如果只有“当前微信版本的类或方法不匹配”，没有“已安装微信 8.0.78 短剧去推广 Hook”，通常需要重新适配。
4. 如果日志显示 Hook 已安装，但推广视频仍不能提前上滑，请记录微信版本、广告页面截图和相关日志，便于定位问题。

仓库还保留了 [v0.3.0 APK](dist/WeChatDramaAdSkip-v0.3.0-wechat8.0.78.apk)，它只提供已验证的“广告出现后提前上滑”功能。

## 从源码构建

准备 JDK 17 或更新版本、Android SDK 36。仓库自带 Gradle Wrapper 和编译所需的 Xposed API JAR。

```sh
export ANDROID_HOME=/你的/Android/Sdk/目录
./gradlew :app:assembleDebug
```

构建结果在 `app/build/outputs/apk/debug/app-debug.apk`。Xposed API 只用于编译，不会打包进模块 APK。

## 适用范围

模块仅针对微信视频号的原生短剧时间线和其中识别出的推广内容；它不会改变服务器上的广告投放、会员或付费解锁状态，也不需要网络权限。微信更新后可能失效，请以实际微信版本和日志为准。
