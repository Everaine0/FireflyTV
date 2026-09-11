# Android TV 上 1080p / 4K 的 UI 缩放调研报告

**问题背景**：原生 View 的 Android TV 应用（minSdk 21 / targetSdk 34），在 SurfaceView 之上叠一层全屏浮层
（居中竖排 TextView，字号 64sp / 32sp / 24sp）。1080p 模拟器（density 320 / xhdpi）正常；
长虹 CHiQ 43Q3T（Android 5.1，MediaTek MT5520，4K 面板）上按 OK 后「只有屏幕中间一小块有内容」。

**结论速览**

1. **Android TV 11+ 默认不是原生 4K UI**：UI 逻辑分辨率被钳到 1920×1080 并把 density 同步减半，
   再由显示管线放大到 4K 面板（官方口径 + AOSP `config_maxUiWidth` 实现，见 §1.1、§1.2）。
   （例外：**ATV 15 QPR1 起 xxxhdpi 设备的上限改为 3840，即原生 4K UI 成为默认**。）
   **但长虹是 Android 5.1 —— 连这个钳位机制都不存在**，UI 分辨率没有任何框架保证（§1.2）。
2. 因此 **`widthPixels/heightPixels` 通常报 1920×1080，`densityDpi` 报 320**；`getRealSize/getRealMetrics`
   返回的是**逻辑尺寸**，**看不到 4K 面板**（源码级证据，见 §1.3、§3）。
3. dp/sp **在平台自洽时本来就能等比缩放**（1080p@320 与 4K@640 都是 960dp 宽）。
   本 bug 的根因是**平台不自洽**：3840×2160 的窗口配了 320 的 densityDpi（H1），
   或者浮层在构造时缓存了旧的像素值而之后发生了模式/密度切换（§6.3），
   于是所有 dp/sp 内容的相对尺寸只剩一半 —— 居中的 `wrap_content` 竖排就变成「中间一小块」。
   另一种可能是 H3：UI 层根本没被放大（§1.5，有 Kodi/Amlogic 的同款先例）。
4. **本项目 targetSdk 34 使问题更容易出现**：AOSP 对 TV 上 `targetSdk < 31` 的 App 会**强制降采样到
   1080p 窗口**，而 `targetSdk ≥ 31` 的 App **直接拿到 4K 窗口**（§1.6）—— "别的 App 正常"可能就是这个原因。
5. 推荐做法：**把浮层自己归一化到 960dp×540dp 的 TV 画布**
   `uiScale = min(widthPx, heightPx×16/9) / (6 × densityDpi)`（见 §6）。
   它同时兼容 1080p@320、4K@640（真 4K UI）和 4K@320（坏设备）三种状态。
6. **不要**用 `widthPx/1920` 这种纯像素倍率：在**真正的 4K UI 设备上会放大 2 倍**（§2b）。
   这类设备**已实测存在**（Chromecast with Google TV 4K：`wm density` = **640**，CSS 视口 960×540），
   而且 **ATV 15 QPR1 起 `values-xxxhdpi → config_maxUiWidth=3840` 已是 AOSP 默认** —— 风险只会变大。
7. **先按 §6.4 在真机取样**，用 `dumpsys SurfaceFlinger` 区分 H1 与 H3 —— 两者修法完全不同。

---

## 1. 真机上 4K 面板时的 DisplayMetrics 与合成方式

### 1.1 官方口径：UI 仍是 1080p，放大到 4K

Android 6.0 发行说明「4K Display Mode」一节是这件事最权威的官方表述：

> "While in 4K display mode, the UI continues to be rendered at the original resolution (such as 1080p)
> and is upscaled to 4K, but SurfaceView objects may show content at the native resolution."

> "If the UI is drawn at a lower logical resolution and is upscaled to a larger physical resolution,
> be aware that the physical resolution the `getPhysicalWidth()` method returns may differ from the
> logical resolution reported by `getSize()`."

来源：<https://developer.android.com/about/versions/marshmallow/android-6.0.html>（搜索 "4K Display Mode"）

同一节还说明 `preferredDisplayModeId`（API 23）可以让 App 主动请求把物理分辨率切到 4K。

Android TV 官方设计文档给出了**面板分辨率 ↔ 逻辑密度**的对应表（这是 Google 期望的平台行为）：

| 面板分辨率 | Screen pixel density |
| :--- | :--- |
| 720p | tvdpi (213) |
| 1080p | xhdpi (320) |
| 4K | xxxhdpi (640) |

来源：<https://developer.android.com/training/tv/playback/compose/layouts>（"Manage layout resources for TV"）

注意这张表意味着一个**不变量**：720p/213 ≈ 6.0、1080p/320 = 6.0、4K/640 = 6.0
→ 三种面板的**逻辑宽度都是 960dp**。官方设计基准也正是 960×540：

> "Always design at MDPI resolution at 960px * 540px. At MDPI 1px = 1dp."
> 来源：<https://developer.android.com/design/ui/tv/guides/styles/layouts>

**只要平台遵守这张表，dp/sp 就自动等比缩放**，不需要任何额外代码。本 bug 是平台违反了它。

### 1.2 AOSP 实现：`config_maxUiWidth = 1920`（真正起作用的开关）

AOSP 的 Android TV 设备 overlay 里有这样一条资源（AOSP main 至今仍是 1920；版本时间线见下表）：

```xml
<!-- Maximum size, specified in pixels, to restrain the display space width to. Height and
     density will be scaled accordingly to maintain aspect ratio. A value of 0 indicates no
     constraint will be enforced.
     ATV default dpis limits the UI graphics width to 1920 because higher resolution is
     unnecessary and causes too much overhead on the GPU for Android TV devices. -->
<integer name="config_maxUiWidth">1920</integer>
```

来源（AOSP main）：
<https://android.googlesource.com/device/google/atv/+/refs/heads/main/overlay/TvFrameworkOverlay/res/values/config.xml>

**版本时间线（我逐个版本核对过，注意路径变过）**

| 版本 | `frameworks/base` 的 `config_maxUiWidth` | ATV 设备 overlay 的取值 | 位置 |
| :--- | :--- | :--- | :--- |
| Android 5.1 / 6 / 7 | **不存在** | 无 | — |
| Android 8.0 起 | 存在，默认 **0**（不限制） | 无 | `frameworks/base/core/res/res/values/config.xml` |
| Android TV 10 | 存在（默认 0） | **无** | — |
| Android TV 11 ~ 14 | 存在 | **1920** | `overlay/frameworks/base/core/res/res/values/config.xml` → 新路径 `overlay/TvFrameworkOverlay/...` |
| **Android TV 15 QPR1 起** | 存在 | **按密度分档**：`values-tvdpi`→**1280**、`values-xhdpi`→**1920**、`values-xxxhdpi`→**3840** | `overlay/TvFrameworkOverlay/res/values-<density>/config.xml` |
| Android TV 12+ 模拟器 / SDK 产品 | 存在 | **0（不限制）** | `sdk_overlay/frameworks/base/core/res/res/values/config.xml` |

即：**框架的「钳位机制」从 Android 8 就有（默认关闭），ATV 从 11 起在正式 TV 产品上开启到 1920
（commit `92b6197`，"This doesn't affect hardware composed video streams as well as apps that create native
views or intentionally render into a different-sized Surface"，Bug 145791247）。**

**⚠️ ATV 15 QPR1 起，4K 电视（xxxhdpi）的上限被改成 3840，也就是「原生 4K UI 成为 AOSP 默认」**
（commit `6b0eaafd`，"Configure config_maxUiWidth per dpi / Fix: 343252722 / Test: `wm size` on both 2K and 4K TVs"）：
<https://android.googlesource.com/device/google/atv/+/6b0eaafdbe7456087a97555726341efab3e2d1cc>

**这意味着「真 4K UI + densityDpi 640」不是纸上假设，而是新设备的默认行为**
（见 §2b 与 §5 的 Chromecast with Google TV 4K 实测）。

ATV 12 宣布 4K UI 时，实现方式是**只在模拟器/SDK 产品上**把它放开成 0
（提交 "Lift maxUiWidth restriction on TV emulator"，Sergey Nikolaienkov，2021-06，Bug 189182666，
测试步骤写的就是 `adb shell wm size` 应输出 `Physical size: 3840x2160`）；
零售 4K 电视要开 4K UI 得厂商自己 opt-in —— **到 ATV 15 QPR1 才变成 xxxhdpi 设备的 AOSP 默认**。
来源：<https://android.googlesource.com/device/google/atv/+/6d0eee509883928a977a97229d2971a82ff022ef>

**对本案的关键推论**：长虹是 **Android 5.1，连这个钳位机制都不存在**。
所以「UI 一定是 1080p」在那台机器上**没有任何框架层保证** ——
显示的逻辑分辨率完全由厂商实现决定，会随视频起播/HDMI 模式切换而变。这使 §1.5 的 H1 成为最可能的解释。

它在框架里的消费点与**精确公式**（AOSP commit "Add configuration for maximum UI width."，
`27cec32496090efd153327f4fc5a5cecc6f59d9b`，改动 `WindowManagerService` + `DisplayContent`）：

```java
void updateBaseDisplayMetrics(int baseWidth, int baseHeight, int baseDensity) {
    mBaseDisplayWidth = baseWidth; mBaseDisplayHeight = baseHeight; mBaseDisplayDensity = baseDensity;
    if (mMaxUiWidth > 0 && mBaseDisplayWidth > mMaxUiWidth) {
        mBaseDisplayHeight  = (mMaxUiWidth * mBaseDisplayHeight)  / mBaseDisplayWidth;
        mBaseDisplayDensity = (mMaxUiWidth * mBaseDisplayDensity) / mBaseDisplayWidth;
        mBaseDisplayWidth   = mMaxUiWidth;
    }
    ...
}
```

来源：<https://android.googlesource.com/platform/frameworks/base/+/27cec32496090efd153327f4fc5a5cecc6f59d9b%5E%21/>

**⚠️ 一个我亲自比对源码发现的版本差异：Android 12 的钳位漏掉了 density 缩放。**

```java
// android12-release（DisplayContent.java，updateBaseDisplayMetrics）
if (mMaxUiWidth > 0 && mBaseDisplayWidth > mMaxUiWidth) {
    mBaseDisplayHeight = (mMaxUiWidth * mBaseDisplayHeight) / mBaseDisplayWidth;
    mBaseDisplayWidth = mMaxUiWidth;
    // ← 没有动 mBaseDisplayDensity
}
// android10 / 11 / 13 / 14 / main：有
if (!mIsDensityForced) {
    // Update the density proportionally so the size of the UI elements won't change
    // from the user's perspective.
    mBaseDisplayDensity = (int) (mBaseDisplayDensity * ratio);
}
```

来源：<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/wm/DisplayContent.java>
对比 <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/wm/DisplayContent.java>

后果：**Android 12 上，4K 面板（640dpi）会被钳成 `1920×1080 @640`，即只有 480×270 dp**
（正常应是 960×540 dp）。注意方向 —— 同样的 dp 布局塞进一半宽的 dp 画布，元素会**相对变大/溢出**，
**不是变小**；所以它对应的是"字太大/被裁"而不是本项目的"中间一小块"。
（本条**未在真实故障报告中被证实**，只证实了源码差异；列出它是为了说明"钳位 ≠ 一定安全"。）
我推荐的 `uiScale` 公式在这种组合下算出 **0.5**，即把 64sp 降到 32sp → 128px/1920 = 6.67%，
**仍然是"相同屏幕占比"，方向正确**。

代入 4K：`3840×2160 @640dpi` → **`1920×1080 @320dpi`**（高和密度同步等比缩小）。
这正是「UI 用 1080p 合成、再放大到 4K 面板」的机制，也说明 **`widthPixels/densityDpi` 的比值被刻意保持恒定**。

> ⚠️ **对提问前提的更正**：`config_supports4kUi` 这个名字在 AOSP 里**查无此资源**。
> 我在 `frameworks/base/core/res/res/values/config.xml`（android11/12/13/14-release 与 master）
> 和 `device/google/atv` 的 TV overlay 中都没有找到任何 `*4kUi*` / `*supports4k*` 资源；
> **实际生效的开关是 `config_maxUiWidth`**（=1920 即禁用 4K UI；设为 0 或 ≥3840 即开启 4K UI）。
> 该结论基于对上述仓库的直接检索；不能排除某些厂商私有 overlay 使用了别的名字。

### 1.3 版本差异：Android 5.x/6/7 vs 8~10 vs ATV 11+ vs ATV 12+ 模拟器

| 维度 | Android 5.x / 6 / 7 | 8 ~ 10 | Android TV 11+（正式产品） | Android TV 12+ 模拟器 / SDK |
| :--- | :--- | :--- | :--- | :--- |
| UI 逻辑分辨率 | 厂商决定，**无框架保证** | 厂商决定（机制默认关闭） | **强制钳到 1920×1080 @320**；**ATV 15 QPR1 起 xxxhdpi 改为 3840（= 原生 4K UI）** | 原生 4K（3840×2160 @640） |
| 平台开关 | 无 `config_maxUiWidth` | 有资源，默认 0（不限制） | overlay = **1920**（QPR1+ 按密度 1280/1920/**3840**） | `sdk_overlay` = **0** |
| `targetSdk` 影响 | 无 | 无 | `targetSdk<31` 强制降采样到 1080p；`≥31` 不降采样 | 同左 |
| `Display.Mode` (`getMode`/`getSupportedModes`) | **API 23 才有**；5.1(API 22) **没有** | 有 | 有 | 有 |
| 请求 4K 物理模式 | `preferredDisplayModeId`，**API 23+** | 有（但 UI buffer 仍是 1080p，见 §5） | 有 | 有 |
| 用户可调字体大小 | 一般无 | 一般无 | 一般无 | **Android TV 12 新增 "Accessibility settings for font sizes"** |

- Android TV 12 官方宣布支持 4K UI：
  > "**4K UI support**: For added visual fidelity, Android TV OS now officially supports UI rendering at
  > 4k resolution on compatible devices. 4K UI resolution can be tested in the upcoming Android 12 emulator for TV…"
  来源（Google 官方博客，2021-07-14）：<https://android-developers.googleblog.com/2021/07/android-12-beta-3-for-tv-is-now.html>
  另见官方发行说明：<https://developer.android.com/tv/release/12>（User interface → "4K UI support"）
- 在此之前的官方媒体表述（可作旁证）：
  > "Since its launch in 2014, Android TV and, in turn, Google TV have always rendered the homescreen and
  > apps at 1080p, only switching the signal to 4K when content starts playing."
  来源：<https://9to5google.com/2021/07/14/android-tv-12-4k-ui-beta-3/>

**所以 5.1 的长虹上「4K」大概率只体现在视频层**；Android 框架侧本应给到 1920×1080@320。

### 1.4 SoC 层面（MediaTek / MStar / Amlogic）

- 官方对这种架构有明确承认。androidx 的 `DisplayCompat` 类注释写道：
  > "On many Android TV devices, `Display.Mode` may not report the accurate width and height because these
  > devices do not have powerful enough graphics pipelines to run framework code at the same resolutions
  > supported by their video pipelines."
  > "On Android TVs it is common for **the UI to be configured for a lower resolution than SurfaceViews can
  > output**. Before API 26 the `Display` object does not provide a way to identify this case, and **up to and
  > including API 28 many devices still do not correctly set their hardware composer output size**."
  来源：<https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:core/core/src/main/java/androidx/core/view/DisplayCompat.java>
  （同文件亦有 GitHub 镜像：<https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/view/DisplayCompat.java>）
- **未验证**：长虹 43Q3T / MT5520 在 Android 5.1 上 `widthPixels` 与 `densityDpi` 的**具体取值**。
  我没有找到任何公开的该机型 dumpsys 记录，也没有真机。**必须在真机上按 §6.4 取样后才能定论**。
  MediaTek/MStar 电视 SoC「UI 平面 1080p + 视频平面 4K，由硬件叠加器合成」是业界普遍架构，
  但我**没有找到**可引用的官方 MT5520 文档来证明它。

### 1.5 三种可能的状态（决定本 bug 属于哪一种）

| `widthPixels × heightPixels` | `densityDpi` | 含义 | 期望现象 |
| :--- | :--- | :--- | :--- |
| 1920×1080 | 320 | 平台自洽（1080p UI 被放大到 4K） | 浮层正常 ✅（= 模拟器表现） |
| **3840×2160** | **320** | **平台不自洽（H1）** | **所有 dp/sp 内容相对尺寸减半 → 中间一小块** ❌ |
| 3840×2160 | 640 | 真 4K UI，平台自洽 | 浮层正常 ✅（`xxxhdpi`） |

另有第三种故障模式 **H3**：框架报 1920×1080，但 UI 层**没有被放大**到 4K 面板，
而是以 1:1 显示在屏幕中间（视频走独立的 4K 平面所以看起来是满屏的）。
此时**整个 App（含浮层的全屏底色）都会缩在中间**，而不是只有文字小。

### 1.6 `targetSdk` 会改变 TV 上的行为（对本项目 targetSdk 34 尤其重要）

AOSP 在 `CompatModePackages` 里有一条**只对 TV（leanback）生效**的兼容缩放：

```java
/**
 * On Android TV applications that target pre-S are not expecting to receive a Window larger
 * than 1080p, so if needed we are downscaling their Windows to 1080p.
 * However, applications that target S and greater release version are expected to be able to
 * handle any Window size, so we should not downscale their Windows.
 */
@ChangeId @Overridable @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
private static final long DO_NOT_DOWNSCALE_TO_1080P_ON_TV = 157629738L;

...
if (mService.mHasLeanbackFeature) {
    final Configuration config = mService.getGlobalConfiguration();
    final float density = config.densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
    final int smallestScreenWidthPx = (int) (config.smallestScreenWidthDp * density + .5f);
    if (smallestScreenWidthPx > 1080 && !CompatChanges.isChangeEnabled(
            DO_NOT_DOWNSCALE_TO_1080P_ON_TV, packageName, userHandle)) {
        return smallestScreenWidthPx / 1080f;
    }
}
```

来源：<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/CompatModePackages.java>

含义（在逻辑显示 ≥ 1080p 的 4K TV 上）：

- **`targetSdk < 31` 的老 App → 被强制降采样到 1920×1080 窗口**（例如 2160/1080 = 2.0 倍），
  于是它们"看起来正常"。
- **`targetSdk ≥ 31` 的 App（本项目 targetSdk 34）→ 不降采样，直接拿到 3840×2160 的窗口**。
  框架的假设是"targetSdk S+ 的 App 能处理任意窗口尺寸"，**也就是说密度正确性由 App 自己负责**。

这条正是"同一台 4K 电视上有的 App 正常、有的 App 变小"的机制性解释。
⚠️ 注意它**不适用于 Android 5.1**（该代码在 5.1 上不存在），所以它不是长虹这台机器的直接根因；
但它是**本项目在任何 Android 12+ 的 4K-UI 电视上都会踩到的坑**，且说明
"我 targetSdk 34 + 假设 1080p 窗口"这个组合本身就是危险的。

---

## 2. 让浮层在 1080p / 4K 上占相同比例：四种做法对比

### (a) 纯 dp/sp + 平台 density —— 平台自洽时正确，不自洽时全错

- `sp → px`：`TextView.setTextSize(SP, v)` 内部就是 `TypedValue.applyDimension(SP, v, dm)`，
  而 `applyDimension` 对 SP 返回 `value * metrics.scaledDensity`；`scaledDensity = density * fontScale`。
  源码：<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/util/TypedValue.java>（`applyDimension`）
  与 <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/ResourcesImpl.java>（`mMetrics.scaledDensity = mMetrics.density * fontScale`）
- 平台自洽（1920@320 / 3840@640 / 1280@213）时，dp/sp **自动**等比 → 无需任何代码。
- 平台不自洽（3840@320）时，`density=2.0` 但一行应有 `density=4.0` → **全部内容只剩一半相对尺寸**。
- **这是本项目当前的做法**（`OverlayScreen.kt` 的 64/32/24sp + `dp(v) = v * density`），也是 DESIGN.md
  风险 7「4K 面板 + 老系统缩放假象 / 应对：1080p 逻辑基准 + dp + 5% 安全边距」所依赖的假设。
  **该假设在真机上不成立，所以这个风险条目实际没被覆盖。**

### (b) `min(widthPx, heightPx*16/9) / 1920` 当倍率 —— 有用但**会误伤真 4K UI 设备**

如果把它当成**纯像素倍率**直接乘到 px/sp 上：

| 设备状态 | 期望倍率 | 该公式给出 | 结果 |
| :--- | :--- | :--- | :--- |
| 1920@320 | 1.0 | 1.0 | ✅ |
| 3840@320（坏） | 2.0 | 2.0 | ✅ |
| **3840@640（真 4K UI）** | **1.0** | **2.0** | ❌ **文字放大一倍，溢出屏幕** |

即：这个公式把「4K 分辨率」等同于「需要放大」，但**官方 4K UI 设备上 dp/sp 本来就是对的**。
正确用法是把它**归一化掉已有密度**（见 (e) / §6）：`scale = (公式结果 / 1920) / (densityDpi / 320)`。

**这不是纸上假设 —— 零售硬件上已经实测到 640dpi 的 4K TV**：
Chromecast with Google TV (4K) 上 `adb shell wm density` 输出 `Physical density: 640`，
WebView 的 CSS 视口正好是 **960×540**（`window.devicePixelRatio == 4`，3840/4 = 960）
—— 既证明了"真 4K UI"存在，也**独立佐证了 960×540dp 这个 TV 画布不变量**。
来源：<https://stackoverflow.com/questions/76426418/why-is-android-tv-app-having-a-web-view-with-low-resolution>（score 8 的回答）
叠加 ATV 15 QPR1 起 `values-xxxhdpi → config_maxUiWidth=3840` 成为 AOSP 默认（§1.2），
**naive 倍率在新设备上翻倍的风险只会越来越大。**

### (c) `displayMetrics.density` vs `scaledDensity` —— 不要手算

- `density = densityDpi / 160`（dp→px）；`scaledDensity = density × fontScale`（sp→px）。
- **必须用 `TextView.setTextSize(SP, …)` / `TypedValue.applyDimension(SP, …)`，不要 `sp * scaledDensity` 手算。**
  官方警告（Android 14 起字体缩放是非线性的 200% 曲线）：
  > "Avoid hardcoding equations using `Configuration.fontScale` or `DisplayMetrics.scaledDensity`.
  > Because font scaling is nonlinear, the `scaledDensity` field is no longer accurate."
  来源：<https://developer.android.com/about/versions/14/features>（Non-linear font scaling to 200%）
  以及 `Configuration.fontScale` 的官方注释：
  > "Please do not use this to hardcode font size equations… It exists for informational purposes only.
  > Please use `TypedValue.applyDimension(int,float,DisplayMetrics)`…"
  来源：<https://developer.android.com/reference/android/content/res/Configuration#fontScale>
  源码中 `applyDimension` 对 SP 会优先走 `metrics.fontScaleConverter`（非线性曲线）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/util/TypedValue.java>
- 含义：**乘倍率要乘在 sp 的"数值"上**（`setTextSize(SP, baseSp * k)`），
  这样用户的字号偏好仍然生效；不要先把 sp 转成 px 再乘。
- 另外：**Android TV 12 起有系统级字体大小设置**（见 §1.3），所以 TV 上 `fontScale != 1.0` 是真会发生的。

### (d) 资源限定符 `values-sw720dp` / `values-xxxhdpi` —— 在"坏设备"上会**反向命中**

- `smallestScreenWidthDp`、`swXXXdp` 都来自**逻辑尺寸 ÷ 密度**：

  | 设备 | 逻辑宽 | densityDpi | sw dp |
  | :--- | :--- | :--- | :--- |
  | 1080p@320 | 1920 | 320 | **540dp** |
  | 4K@640（正常） | 3840 | 640 | **540dp** |
  | 4K@320（坏） | 3840 | 320 | **1080dp** |

  所以 `values-sw720dp` 只会在**坏设备**上命中，在正常设备上不命中 —— 用它做兜底会**只在故障机上改变行为**，
  正是最难维护的那种分支。`values-xxxhdpi` 同理：只有 4K@640 才命中，4K@320 走 `xhdpi` 资源。
- 结论：**限定符适合放"不同布局结构"，不适合用来修"密度谎言"**。

### (e) 推荐：把浮层归一化到 960dp × 540dp 的 TV 画布

核心洞察（§1.1）：**自洽的 TV 一律是 960dp × 540dp**。所以只要把"像素 ↔ dp"的关系拉回这个基准即可：

```
uiScale = min(widthPx, round(heightPx × 16 / 9)) / (6 × densityDpi)
```

（因为 1920px ÷ 320dpi = 6；`densityDpi/160` 即 density，故它等于 `画布宽度px / 960dp / density`。）

| 设备 | uiScale | 说明 |
| :--- | :--- | :--- |
| 1920×1080 @320 | 1.00 | 不动 |
| 3840×2160 @320（坏） | **2.00** | **修复** |
| 3840×2160 @640（真 4K UI） | 1.00 | 不动（关键：不误伤） |
| 1280×720 @213 | ≈1.00 | 不动 |
| 2560×1080 @320（21:9） | 1.00 | 用 min 保证高度安全 |

---

## 3. API 21+ 上安全获取真实尺寸 / 刷新率

| API | 起始 API | 是否已废弃 | 返回什么 |
| :--- | :--- | :--- | :--- |
| `Display.getSize(Point)` | 13 | API 30 起废弃 | 应用可用尺寸（**逻辑**，扣装饰/兼容缩放） |
| `Display.getMetrics(DisplayMetrics)` | 1 | API 30 起废弃 | 同上，带 `densityDpi/density/scaledDensity` |
| `Display.getRealSize(Point)` | **17** | API 31 起废弃 | **逻辑**显示尺寸 |
| `Display.getRealMetrics(DisplayMetrics)` | **17** | API 31 起废弃 | 同上，带 density |
| `Display.getRefreshRate()` | **1** | 未废弃 | 当前模式的刷新率（Hz） |
| `Display.Mode` / `getMode()` / `getSupportedModes()` | **23** | 未废弃 | 物理模式（`getPhysicalWidth/Height`）——**5.1 上不存在** |
| `WindowManager.LayoutParams.preferredDisplayModeId` | **23** | 未废弃 | 请求切换物理模式（4K） |
| `WindowManager.getDefaultDisplay()` | 1 | **API 30 起废弃** | 改用 `Context.getDisplay()` |
| `DisplayCompat.getMode(ctx, display)` (androidx) | 23 | — | 修正厂商谎报的模式尺寸 |

来源：<https://developer.android.com/reference/android/view/Display>、
<https://developer.android.com/reference/android/view/WindowManager>、
<https://developer.android.com/about/versions/marshmallow/android-6.0.html>（4K Display Mode）

### 关键事实：`getRealSize/getRealMetrics` **看不到 4K 面板**

AOSP 源码里它们直接取 `DisplayInfo.logicalWidth/logicalHeight`：

```java
public void getRealSize(Point outSize) { ...
    outSize.x = mDisplayInfo.logicalWidth;
    outSize.y = mDisplayInfo.logicalHeight; ... }
public void getRealMetrics(DisplayMetrics outMetrics) { ...
    mDisplayInfo.getLogicalMetrics(outMetrics, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null); ... }
```

来源：<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/Display.java>（`getRealSize` / `getRealMetrics`）

连隐藏 API 也一样：`DisplayInfo.getNaturalWidth()` 返回的仍是 `logicalWidth`（Android 9 与 main 均如此）：
<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/DisplayInfo.java>

**所以在 1080p-UI 的 4K 电视上，App 侧没有任何公开 API 能读出 3840×2160。**
这正解释了为什么 androidx 要造 `DisplayCompat`（它靠 `sys.display-size` / `vendor.display-size` 系统属性、
Sony `com.sony.dtv.hardware.panel.qfhd` feature 等**厂商私有后门**来猜真实尺寸）。

**唯一能拿到面板分辨率的公开 API 是 `Display.Mode`（API 23+）**，而它的官方注释把整个机制讲得最清楚：

> "Returns the physical width of the display in pixels when configured in this mode's resolution.
> **Note that due to application UI scaling, the number of pixels made available to applications when the
> mode is active (as reported by `Display#getWidth()`) may differ from the mode's actual resolution**…
> For example, applications running on a 4K display may have their UI laid out and rendered in 1080p and
> then scaled up. **Applications can take advantage of the extra resolution by rendering content through a
> SurfaceView using full size buffers.**"
> — `Display.Mode.getPhysicalWidth()` 官方注释
> 来源：<https://developer.android.com/reference/android/view/Display.Mode#getPhysicalWidth()>

注意最后一句：**官方推荐的"用满 4K"方式就是 SurfaceView 全尺寸 buffer，而不是让 View 层变大。**
在 ATV 11 之前，`Display.Mode` 在很多电视上还会谎报（只给 1080p）；
Android TV 12 起官方宣称已修正（"Certified API accuracy for reporting display modes"，
<https://developer.android.com/tv/release/12>），
OEM 侧也有对应说明（androidx/media issue #1986：`vendor.display-size` 自 ATV 12 起不再可用，
应改用 `Display.getMode().getPhysicalWidth/Height`）：
<https://github.com/androidx/media/issues/1986>

### 窗口会不会比面板小？会

1. **`config_maxUiWidth` 主动钳位**（最主要，§1.2）：4K 面板 → 1920×1080 窗口 + 320dpi。
2. **`adb shell wm size` / `wm density`** 覆盖（`getRealSize` 的注释明确提到这种情形：
   "The window manager is emulating a different display size, using `adb shell wm size`"）。
3. **多窗口 / PiP**（Android TV 8+ 有 PiP）——但 `getRealSize` 返回的是「应用可访问的最大区域」而非窗口，
   官方明确警告它 **"unsuitable to use when sizing and placing UI elements"**（同上源码注释）。
   → 要拿窗口尺寸应该用 `WindowManager.getCurrentWindowMetrics()`（API 30+）。

### SurfaceView 拿到什么

- 默认（`setSizeFromLayout()`）Surface **跟随 View 的布局尺寸**（窗口像素），尺寸变化通过
  `SurfaceHolder.Callback.surfaceChanged(w,h)` 通知。
- 可用 `SurfaceHolder.setFixedSize(w,h)` 强行指定：官方文档 "Make the surface a fixed size.
  **It will never change from this size.**"
  来源：<https://developer.android.com/reference/android/view/SurfaceHolder#setFixedSize(int,%20int)>
- 实测案例（Nvidia Shield TV, Android 9）：窗口被放大到 3840×2160 输出，但
  `dumpsys SurfaceFlinger` 里应用层的 `Disp Frame = 0 0 3840 2160` 而 `Source Crop = 0 0 1920 1080`
  —— **UI 层是 1080p 被放大**；`SurfaceView` 的 `onDraw` canvas `clipBounds` 仍是 `1920×1080`，
  直到显式调用 `holder.setFixedSize(3840, 2160)`。
  来源：<https://stackoverflow.com/questions/61170081/surfaceview-canvas-rendering-at-1080p-on-4k-android-tv>
- 结论：**SurfaceView 的 surface 尺寸取决于逻辑窗口，不是面板**；视频的 4K 来自解码器输出分辨率
  与显示管线（tunnel mode / 视频平面），跟浮层的坐标空间无关。

---

## 4. 10 英尺可读性：官方建议

### 4.1 Overscan 安全边距 = 5%

> "Position screen elements that must be visible to the user at all times within the overscan-safe area.
> Adding a **5% margin of 48 dp on the left and right edges and 27 dp on the top and bottom edges** to a
> layout helps ensure that screen elements in the layout are within the overscan-safe area."

来源：<https://developer.android.com/training/tv/playback/compose/layouts>
与 <https://developer.android.com/design/ui/tv/guides/styles/layouts>

对照本项目：`OverlayScreen` 用的是 `dp(54)` 左右 / `dp(30)` 上下，**比官方的 48/27 更保守，合规** ✅

### 4.2 设计基准 & 单位

> "Always design at MDPI resolution at 960px * 540px. At MDPI 1px = 1dp. Assets need to aim for 1080p.
> This allows the Android system to downscale layout elements to 720p, if necessary."
> 来源：<https://developer.android.com/design/ui/tv/guides/styles/layouts>

> "Use density-independent pixel (dp) units instead of absolute pixel units, and scalable pixel (sp) units
> for typography."
> 来源：<https://developer.android.com/training/tv/playback/compose/layouts>

### 4.3 字号

官方 TV 设计文档只给类型阶（Display/Headline/Title/Body/Label）角色的定性描述，**没有给出 sp 数字**
（数值在客户端渲染的表格里）：
<https://developer.android.com/design/ui/tv/guides/styles/typography>

**可引用的实际数字来自 AndroidX Leanback 的官方 TV 样式**（TV 基线 = 1080p/xhdpi）：

| 样式 | 字号 |
| :--- | :--- |
| `lb_browse_title_text_size` | 44sp |
| `lb_details_description_title_text_size` | 34sp |
| `lb_control_button_text_size` | 22sp |
| `lb_browse_header_text_size` | 20sp |
| `lb_search_bar_text_size` | 18sp |
| `lb_details_description_body_text_size`（正文） | **14sp** |
| `lb_basic_card_content_text_size` / 播放时间 | **12sp（最小）** |

来源：<https://github.com/androidx/androidx/blob/androidx-main/leanback/leanback/src/main/res/values/dimens.xml>

对照本项目：64sp / 32sp / 24sp **远大于** Leanback 的 44sp / 34sp / 14sp 基准。
对老年用户是合理选择，但也意味着**一旦相对尺寸减半（32/16/12sp）就退化成"勉强及格"的普通 TV 字号**
——与「看起来没放大」的主观感受一致。
官方另有一条通用建议："Make all your view widgets large enough to be clearly visible to someone sitting
10 feet away from the screen."（同 compose/layouts 页）

### 4.4 深色浮层的对比度 / alpha

- **Leanback 官方用色**：
  - `lb_error_background_color_translucent = #E6000000`（**90% 黑**，用于错误浮层背景）
  - `lb_background_protection = #99000000`（60% 黑，用于内容保护性压暗）
  - `lb_playback_controls_background_dark = #c0000000`（75% 黑，播放控件底）
  来源：<https://github.com/androidx/androidx/blob/androidx-main/leanback/leanback/src/main/res/values/colors.xml>
- 本项目浮层用 `#E6000000` + 正文 `#B3FFFFFF`（70% 白）——**与 Leanback 的浮层底色完全一致** ✅
- **未在官方 Android TV 文档中找到**针对"视频之上压暗层"的 alpha 数值规定；
  可引用的通用无障碍标准是 WCAG 2.x 正文对比度 ≥ **4.5:1**（大字号 ≥ 3:1）：
  <https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html>
  （在 90% 黑底上，纯白文字对比度足够；`#B3FFFFFF` 合成后约为 70% 白，仍远超 4.5:1）

---

## 5. 已知案例：4K Android TV 上 UI 变小 / 只在中间

| # | 来源 | 设备 / 系统 | 现象 | 根因 / 修法 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | [SO 61170081](https://stackoverflow.com/questions/61170081/surfaceview-canvas-rendering-at-1080p-on-4k-android-tv) | Nvidia Shield TV, Android 9 | 想在 SurfaceView 上原生 4K 绘制，`onDraw` canvas `clipBounds` 只有 1920×1080；`dumpsys SurfaceFlinger` 显示 `Disp Frame 3840×2160` 但 `Source Crop 1920×1080` | **UI 层就是 1080p 放大**；修法 `holder.setFixedSize(3840, 2160)` |
| 2 | [SO 54242590](https://stackoverflow.com/questions/54242590/layout-elements-looks-blurry-on-4k-screen-android-tv) | 4K Android TV，WebView Daydream 屏保 | WebView 始终报 ~960×500，画面糊；试过 `layout-sw320/600/720dp` 均无效 | 回答引用 Android 6.0 说明：**UI 继续以 1080p 渲染再放大到 4K**，原生 UI 组件拿不到 4K；`swXXXdp` 限定符解决不了 |
| 3 | [SO 33258206](https://stackoverflow.com/questions/33258206/real-4k-in-android-tv) | Sony BRAVIA X90C | 浏览器页宽只有 1920px，问原生 App 能否真 4K | 需要设备支持 4K 输出；UI 与视频能力分离 |
| 4 | [SO 33544008](https://stackoverflow.com/questions/33544008/how-to-display-a-perfect-4k-picture-android-tv) | Android TV 4K | 4K 图片显示不清晰 | 同上游：UI 管线 1080p |
| 5 | [androidx `DisplayCompat` 类注释](https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/view/DisplayCompat.java) | 多款 Android TV | `Display.Mode` 报的尺寸不准 | **官方认可的平台缺陷**：UI 逻辑分辨率低于 SurfaceView 能输出的分辨率；API 28 及以前很多设备连 HWC 输出尺寸都没设对 |
| 6 | [AOSP commit 27cec324](https://android.googlesource.com/platform/frameworks/base/+/27cec32496090efd153327f4fc5a5cecc6f59d9b%5E%21/) | — | — | 引入 `config_maxUiWidth` 显式实现「钳到 1920 + 同步缩 density」 |
| 7 | [Google Issue Tracker 242757484](https://issuetracker.google.com/issues/242757484) | Android TV 模拟器 | "Android TV Hardware profile shows incorrect device size" | ⚠️ 需登录，**内容未能验证**，仅登记标题 |
| 8 | [SO 67998593](https://stackoverflow.com/questions/67998593/how-to-read-display-resolution-thorugh-hdmi-displaycompat-fine-on-androidtv-dir) | Android TV / TV Stick | HDMI 真实分辨率读取 | ⚠️ 直接抓取被 403 拦截，**仅从检索摘要得知**其结论与 §3 一致（"AndroidTV scales down the UI for UHD/4K … DisplayMetrics always return 1920x1080"）——**未逐字验证** |
| 9 | **[xbmc commit e64a470](https://github.com/xbmc/xbmc/commit/e64a470d294d9978869c903c294d8a3e91190fd7)**（kszaq, 2016-08-22, `EGLNativeTypeAmlogic.cpp`） | Amlogic 盒子 + 4K 输出 | commit message 原文：**"For 4K output Kodi renders GUI at 1080p and this results in GUI covering only 1/4 screen."** | 与本案**字面同款症状**：UI 层 1080p、输出 4K、平台没有放大 → 画面只占 1/4（居中）。修法是打开 framebuffer 硬件放大（`/sys/class/graphics/fb0/free_scale`、`free_scale_axis`、`window_axis`、`scale_width/height`）——**属于平台/厂商侧修复，App 无法自救**。这就是 §1.5 的 H3 |
| 10 | [SmartTube issue #5102](https://github.com/yuliskov/SmartTube/issues/5102) | Android TV 13（Waydroid）+ 4K60 | UI 糊、"像被锁在 1080p" | `dumpsys SurfaceFlinger` 证据：`geomBufferSize=[0 0 1920 1080]` + `geomLayerTransform … SCALE 2.0000`，合成帧 `Disp Frame 0 0 3840 2160 | Source Crop 0.0 0.0 1920.0 1080.0`。**App 内无解**，靠平台侧更新修复 |
| 11 | [SO 32557228](https://stackoverflow.com/questions/32557228/) "Detecting 4K UHD screens on Android" | NVIDIA Shield TV | `display.getSize()` 返回 1920×1080，而 ExoPlayer 实际输出 3840×2160 | 需用 `Display.Mode.getPhysicalWidth/Height`（API≥23）或 `DisplayCompat.getSupportedModes()`；且**UHD 可能只在视频播放期间才启用** |
| 12 | [SO 50921330](https://stackoverflow.com/questions/50921330/) | Sony KD-49XF9005 | `getSupportedModes()` 只报 1080p 一个模式，`preferredDisplayModeId` 永远选不到 4K | 厂商上报的模式列表不完整（与 `DisplayCompat` 注释所述一致） |
| 13 | [CSDN：MStar MSD8386 的 4K UI 白名单](https://blog.csdn.net/u011044707/article/details/137139591) | MStar 电视 SoC，2GB DDR | 低端方案按**固件白名单** `system/etc/4k2k_app.xml` 决定哪些 App 拿 `wm size 3840x2160`，其余拿 `1920x1080` | ⚠️ **二手博客证据**：说明"我的 App 拿到 1080p 还是 4K 窗口"可能**由你无法控制的固件 XML 决定**。名字与"4K UI 省 DDR"的动机可信，但**未在 AOSP/厂商官方文档中验证** |
| 14 | [CoreELEC 论坛 "video starts in just 1/4th of the screen"](https://discourse.coreelec.org/t/solved-sometimes-video-starts-in-just-1-4th-of-the-screen/12059) | Amlogic Odroid N2/C4 + Kodi GUI 1080p + 4K TV | 画面只在 1/4 屏 | 与 #9 同源（GUI 分辨率 < 输出分辨率且未放大） |
| 15 | **[SO 76426418](https://stackoverflow.com/questions/76426418/why-is-android-tv-app-having-a-web-view-with-low-resolution)**（score 8 回答） | **Chromecast with Google TV (4K)**，真机 `adb shell wm density` → **`Physical density: 640`** | WebView 视口只有 960×540 CSS px，看起来"低分辨率" | **这是"真 4K UI / densityDpi 640"在零售硬件上的直接证据**。它同时证明：640dpi 的 4K 设备今天就存在（naive `widthPx/1920` 倍率会在这里翻倍），且 CSS 视口 = **960×540** 正好等于 TV 画布不变量 |
| 16 | [androidx/media issue #1986](https://github.com/androidx/media/issues/1986) | OEM 盒子，ATV 11 vs ATV 12 | `vendor.display-size` 报 4K，但 `Display.getMode().getPhysicalWidth/Height` 在 ATV 11 上错报 1080p | 厂商答复：`vendor.display-size` 自 ATV 12 起废弃，改用 `Display.Mode`；**ATV 12 上 `Display.Mode` 才报对 4K** |

**与本项目症状最接近的是 #1/#2 的机制 + §1.5 的 H1**：
「框架窗口是 4K 但密度还是 320」→ 内容相对尺寸减半、居中的 `wrap_content` 竖排缩成中间一小块。

---

## 6. RECOMMENDED APPROACH

### 6.1 核心公式与常量

```kotlin
// TV 画布常量
const val TV_CANVAS_W_PX = 1920f   // 1080p 基准宽
const val TV_CANVAS_H_PX = 1080f
const val TV_CANVAS_DP   = 960f    // 官方设计基准：960 x 540 dp
const val TV_BASE_DPI    = 320f    // 1920px / 960dp * 160 = 320
// 1920 / 320 = 6  →  "每 6 个像素算 1 个 dpi 单位"

/**
 * 相对 960x540dp TV 画布的自适应倍率。
 * 平台自洽（1920@320、3840@640、1280@213）时恒为 1.0；
 * 平台不自洽（3840@320 等）时给出补偿倍率。
 */
fun uiScale(dm: DisplayMetrics): Float {
    val w = dm.widthPixels
    val h = dm.heightPixels
    val canvasW = minOf(w.toFloat(), h * 16f / 9f)   // 用 min 保证 21:9 / 竖屏时高度安全
    return (canvasW / (6f * dm.densityDpi)).coerceIn(0.5f, 4f)
}
```

等价的「密度归一」写法（同一个东西，便于解释）：

```kotlin
val targetDpi = (minOf(dm.widthPixels, (dm.heightPixels * 16f / 9f).roundToInt()) / 6f)  // 1920→320, 3840→640
val scale = targetDpi / dm.densityDpi
```

### 6.2 落地方式（二选一）

**方案 A（推荐，改动最小）：把倍率乘在 sp 数值和 dp 数值上**

```kotlin
private fun textView(baseSp: Float, color: Int) = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * uiScale(resources.displayMetrics)) // ← 乘 sp 数值
    setTextColor(color); includeFontPadding = false; gravity = Gravity.CENTER
}
private fun dp(v: Int): Int = (v * resources.displayMetrics.density *
        uiScale(resources.displayMetrics)).toInt()
```

要点：
- **乘在 sp 数值上**（`setTextSize(SP, baseSp * k)`），保留用户字号偏好（§2c）。
- **不要** `baseSp * scaledDensity * k` 手算像素。

**方案 B（更彻底）：给浮层一个"密度正确"的 Context**

```kotlin
val dm = resources.displayMetrics
val targetDpi = (minOf(dm.widthPixels, (dm.heightPixels * 16f / 9f).roundToInt()) / 6f).roundToInt()
val cfg = Configuration(resources.configuration).apply { densityDpi = targetDpi }
val overlay = OverlayScreen(createConfigurationContext(cfg))
```

`createConfigurationContext` 是 API 17+；框架会按 `density = densityDpi / 160` 重算度量
（`ResourcesImpl`：`mMetrics.density = mConfiguration.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE`），
于是浮层内**所有** dp/sp 自动正确，无需逐处乘系数。
注意副作用：该 Context 的资源限定符也会跟着变（可能命中 `values-xxxhdpi` 等），纯文字浮层无影响。

### 6.3 必须同时修的两处「时间维度」问题

1. **每次显示都重算**，不要在构造函数里算死。`OverlayScreen` 目前在 `init` 里就把 `dp(54)`/`marginTop()`
   和 64sp/32sp/24sp 全算成了像素；一旦运行中发生显示模式/密度切换（4K 视频起播、HDMI 模式切换），
   这些值全部过期。
   → 把尺寸计算搬进 `show()`（或 `onSizeChanged(w,h,...)`），每次按**当前** `resources.displayMetrics` 重算。
2. **Activity 声明了 `android:configChanges="…|density"`**：框架**不会重建** Activity，
   但 `TextView` **不会**在配置变化时重算字号 —— `setTextSize(SP,v)` 立即
   `setRawTextSize(TypedValue.applyDimension(...))` 把**像素值**缓存进 `mTextPaint`，而
   `TextView.onConfigurationChanged()` 里**没有**重设字号的逻辑。
   源码：<https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java>
   （`setTextSizeInternal` / `setRawTextSize` / `onConfigurationChanged`）
   → 声明 `density` 对播放器是好事（不会因模式切换重启播放），但**必须自己重算浮层尺寸**。
   （ActivityThread 在配置变化时仍会调用 `ResourcesManager.applyConfigurationToResources`，
   即 `resources.displayMetrics` 是**新的**，过期的只是已算好的 px 值。）

### 6.4 上真机取样（决定 H1 / H3，必须先做）

```bash
adb shell getprop ro.sf.lcd_density                 # 厂商写死的密度
adb shell wm size ; adb shell wm density            # 是否被覆盖
adb shell dumpsys window displays | grep -Ei "init=|cur=|app=|density"
adb shell dumpsys display | grep -Ei "mBaseDisplay|density|DisplayDeviceInfo"
adb shell dumpsys SurfaceFlinger | grep -A6 "HWC layers"   # 看 Disp Frame vs Source Crop
```

`SurfaceFlinger` 那一条是判定 H3 的关键：若应用层是 `Disp Frame 0 0 3840 2160` + `Source Crop 0 0 1920 1080`
→ UI 层被放大（H3 不成立）；若 `Disp Frame` 就是 `0 0 1920 1080` → UI 没被放大（H3 成立）。

App 侧加一行日志（浮层显示时打印，便于远程让用户回报）：

```kotlin
val dm = resources.displayMetrics
val real = Point().also { windowManager.defaultDisplay.getRealSize(it) }
val rm = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
Log.i(TAG, "dm=${dm.widthPixels}x${dm.heightPixels} dpi=${dm.densityDpi} density=${dm.density} " +
           "scaled=${dm.scaledDensity} fontScale=${resources.configuration.fontScale} " +
           "real=${real.x}x${real.y} realDpi=${rm.densityDpi} " +
           "refresh=${windowManager.defaultDisplay.refreshRate} uiScale=${uiScale(dm)}")
```

判定表：

| 日志 | 判定 | 处理 |
| :--- | :--- | :--- |
| `dm=1920x1080 dpi=320 uiScale=1.0` | 平台自洽，UI 被放大 | 若仍「中间一小块」→ H3，见下 |
| `dm=3840x2160 dpi=320 uiScale=2.0` | **H1** | §6.2 直接修复 |
| `dm=3840x2160 dpi=640 uiScale=1.0` | 真 4K UI，正常 | 无需改动 |
| 日志显示 `dpi` 与窗口尺寸矛盾（如 `1920x1080 dpi=160`） | 密度谎言 | `uiScale` 同样会补偿 |

**H3（UI 层没被放大）App 侧无法修复**：公开 API 不允许 App 强制放大自己的图层或改逻辑分辨率
（`preferredDisplayModeId` 只在 API 23+，5.1 没有）。属于厂商 HAL/合成器缺陷，只能记录证据后走厂商或换设备。

### 6.5 验收标准

- 同一组常量在 1080p@320 与 4K 上，**时钟文字高度 / 屏幕高度**之比应相同：
  `64sp / 540dp ≈ 11.9%`（可用截图量像素验证）。
- 关键回归点：在 `densityDpi = 640` 的 4K UI 模拟器/真机上，**文字不得比 1080p 上更大**（§2b 的陷阱）。
- 单元测试可直接测 `uiScale()`：`1920x1080/320 → 1.0`、`3840x2160/320 → 2.0`、
  `3840x2160/640 → 1.0`、`1280x720/213 → ≈1.0`、`2560x1080/320 → 1.0`。

### 6.6 用 4K UI 模拟器做回归（本项目当前缺的测试面）

- 现有 1080p 模拟器（density 320）**不可能**暴露这个 bug —— 因为它是"平台自洽"的正面样本。
  官方 TV 模拟器默认就是 1920×1080 @320：
  `device/generic/goldfish/data/etc/config.ini.tv` → `hw.lcd.density=320`、`skin.name=1920x1080`。
- **Android TV 12+ 的模拟器/SDK 产品把 `config_maxUiWidth` 设为 0**
  （提交 <https://android.googlesource.com/device/google/atv/+/6d0eee509883928a977a97229d2971a82ff022ef>），
  即**原生 4K UI**。用它跑一遍，就能同时覆盖"真 4K UI（density 应随之为 640）"这条路径。
  **Android TV 15 QPR1 起，xxxhdpi 设备的 AOSP 默认上限已改成 3840**（§1.2），
  所以"真 4K UI"会越来越常见，这条回归路径必须常态化。
- 最省事的真机验证：**Chromecast with Google TV (4K)** —— 实测 `wm density` = 640、4K 原生 UI（§5 #15），
  是验证"density 640 时不误放大"的理想设备。
- 想覆盖 H1（4K 窗口 + 320dpi）可以在任意 TV 模拟器上直接模拟：
  `adb shell wm size 3840x2160` 后**不要**改 density（`wm density` 保持 320）
  → 这正是坏设备的度量组合，改完跑一次浮层即可验证 `uiScale` 是否补偿。
  验证完 `adb shell wm size reset` 还原。
- 另外建议引入 androidx `DisplayCompat`（需 **AndroidX Core ≥ 1.5.0-beta02**，
  该版本才修好"部分面板错报 1920×1080"的问题）来判断设备是否真的 UHD，
  <https://developer.android.com/reference/androidx/core/view/DisplayCompat>；
  但要接受它在 HDMI 棒子上仍可能报 1080p（官方注释已承认，§1.4）。

---

## 7. 未验证 / 存疑清单

1. **长虹 43Q3T / MT5520 在 Android 5.1 上的实际 `widthPixels` / `densityDpi`** —— 无真机、无公开 dumpsys，**必须按 §6.4 取样**。
2. **"`getRealSize` 报 3840×2160 且 `densityDpi` 停在 320"这个精确组合，我没有找到任何一手报告**
   （中英文都找过）。它可以从「TV 基线 960×540dp @320dpi」+「厂商把密度写死在固件」推导出来，
   但**属于推断，不是已证实的观测**。→ **更凸显 §6.4 现场取样的必要性**。
   （已证实的相邻观测：Chromecast with Google TV 4K = 4K UI @ **640**dpi；各类盒子 = 160/213dpi。）
3. **Android 12 钳位漏掉 density 缩放**（§1.2）—— 源码差异我已亲自逐行比对确认，
   但**没有任何真实故障报告与之对应**，也**没有**在 ATV 12 真机上验证过 `wm size`/`wm density` 的实际输出。
   另注意它的症状方向是"元素变大/溢出"，与本项目的"变小"相反，**不要**拿它当本案根因。
4. **`config_supports4kUi` 资源不存在**（我已检索 AOSP `frameworks/base` 与 `device/google/atv` 各版本；
   不排除厂商私有 overlay 用了别的名字）。
5. **`DisplayCompat.isUhdCapable` / `MODE_1080P` / `MODE_4K` 也不存在** —— 该类只有
   `getMode` / `getSupportedModes` 与 `ModeCompat{getPhysicalWidth,getPhysicalHeight,toMode,isNative}`，
   4K 常量（`DISPLAY_SIZE_4K_WIDTH/HEIGHT`）是 **private**。
6. **`config_defaultDensity` / `DisplayMetrics.DEFAULT_DENSITY` 不存在**；
   真实默认值是 `DENSITY_DEFAULT = DENSITY_MEDIUM = 160`，
   经 `getDeviceDensity()` 读 `qemu.sf.lcd_density` → `ro.sf.lcd_density` 覆盖。
7. **MT5520 的「1080p UI 平面 + 4K 视频平面」具体架构** —— 属于业界通行说法，我未找到可引用的
   联发科官方文档。**标记为推测**。厂商侧被点名的只有 **MStar MSD8386**（固件白名单 `4k2k_app.xml`）
   与 **Amlogic**（Kodi `free_scale`），**没有任何来源点名 MT55xx**；
   且公开 AOSP 中没有 MTK/MStar 的 device tree 含 `config_maxUiWidth`（"采样未找到"，非"证明不存在"）。
8. **H3（UI 层 1:1 显示在中间）在长虹上的存在性** —— 纯假设，靠 §6.4 的 `dumpsys SurfaceFlinger` 判定。
   H3 本身有真实先例（Kodi/Amlogic commit e64a470 "GUI covering only 1/4 screen"）。
9. **Android TV 官方针对「视频之上压暗浮层」的 alpha 规定** —— 未找到；本文用 Leanback 的实际取值与 WCAG 代替。
10. **Google Issue Tracker 242757484 的内容** —— 需登录，仅确认标题存在（与模拟器硬件配置的尺寸有关）；
    Buganizer 号 25820708 / 145791247 / 189182666 / 343252722 只作为 commit message 文本可信。
11. **SO 67998593 的正文** —— 直接抓取被 403 拦截，结论仅来自检索摘要，未逐字核对。
12. **MStar `4k2k_app.xml` 白名单机制** —— 来源是 CSDN 博客（二手），未在厂商官方文档验证；
    "App 何时拿到 4K 窗口可能由固件白名单决定"这个**结论方向**可信，但**具体文件名/行为未证实**。
13. **"开发者选项强制 1080p/4K UI"** —— 未找到任何官方文档，不要采信第三方教程。
14. **Google 所说 "compatible devices" 的判定标准未定义**；ATV 15 QPR1+ 的 xxxhdpi→3840 默认路径
    是否有任何**实际出货**的 Amlogic/MTK/MStar 4K 设备启用，我未能证实。
15. 三个并行调研子代理均已交付（社区案例 `research-androidtv-4k-ui-scaling.md`、
    显示度量 `docs/research/4K-DISPLAY-METRICS-REPORT.md`）；
    本报告的**关键条目我已逐条回源复核**：`config_maxUiWidth` 各版本路径与取值、
    Android 12 vs 13 的钳位差异、ATV 11 / ATV 12 模拟器 / ATV 15 QPR1 三个提交、
    `CompatModePackages` 代码、`Display.Mode.getPhysicalWidth()` 注释、Chromecast 640dpi 实测、
    Kodi commit 元数据。子代理报告中与其结论不符处（例如把 Android 12 的钳位差异描述为"tiny UI"，
    实际方向相反）已在 §1.2 与本节 §7.3 更正。

---

## 8. 来源清单

**AOSP 源码**
- `DisplayContent.updateBaseDisplayMetrics`（钳位公式；**android12 版本漏掉 density 缩放**）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/wm/DisplayContent.java>
  对比 <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/wm/DisplayContent.java>
- `Display.java`（`getSize/getRealSize/getRealMetrics/getRefreshRate/getMode`）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/Display.java>
- `DisplayInfo.java`（`logicalWidth`、`getNaturalWidth`）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/DisplayInfo.java>
- `TypedValue.applyDimension`（SP → `scaledDensity` / 非线性 fontScaleConverter）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/util/TypedValue.java>
- `ResourcesImpl`（`density = densityDpi × 1/160`、`scaledDensity = density × fontScale`）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/res/ResourcesImpl.java>
- `TextView`（`setTextSizeInternal` → `setRawTextSize` 缓存 px；`onConfigurationChanged` 不重算字号）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java>
- `ActivityThread`（配置变化时 `applyConfigurationToResources`）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java>
- **`config_maxUiWidth` 提交（含精确缩放公式）**：
  <https://android.googlesource.com/platform/frameworks/base/+/27cec32496090efd153327f4fc5a5cecc6f59d9b%5E%21/>
- **ATV 11 首次施加 1920 上限**（Bug 145791247，"doesn't affect … apps that create native views"）：
  <https://android.googlesource.com/device/google/atv/+/92b6197>
- **Android TV overlay `config_maxUiWidth = 1920`**（ATV 11~14 的旧路径）：
  <https://android.googlesource.com/device/google/atv/+/refs/heads/android11-release/overlay/frameworks/base/core/res/res/values/config.xml>
- **ATV 15 QPR1 按密度分档**（tvdpi 1280 / xhdpi 1920 / **xxxhdpi 3840**）：
  <https://android.googlesource.com/device/google/atv/+/6b0eaafdbe7456087a97555726341efab3e2d1cc>
- **ATV 12 放开模拟器 4K UI 限制的提交**（`sdk_overlay` → `config_maxUiWidth=0`，"use the native resolution of the device's screen"）：
  <https://android.googlesource.com/device/google/atv/+/6d0eee509883928a977a97229d2971a82ff022ef>
- **`CompatModePackages.DO_NOT_DOWNSCALE_TO_1080P_ON_TV`**（TV 上 `targetSdk<31` 强制降采样到 1080p）：
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/CompatModePackages.java>
- androidx `DisplayCompat`（官方承认的 TV 分辨率谎报问题；需 Core ≥ 1.5.0-beta02）：
  <https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/view/DisplayCompat.java>
- Leanback TV 字号与配色：
  <https://github.com/androidx/androidx/blob/androidx-main/leanback/leanback/src/main/res/values/dimens.xml>、
  <https://github.com/androidx/androidx/blob/androidx-main/leanback/leanback/src/main/res/values/colors.xml>

**Google 官方文档**
- Android 6.0 发行说明「4K Display Mode」（UI 1080p 上采样 + `preferredDisplayModeId`）：
  <https://developer.android.com/about/versions/marshmallow/android-6.0.html>
- Android 12 for TV 发行说明（4K UI support）：
  <https://developer.android.com/tv/release/12>
- Google 官方博客（Android 12 Beta 3 for TV，4K UI support）：
  <https://android-developers.googleblog.com/2021/07/android-12-beta-3-for-tv-is-now.html>
- TV 布局与 overscan 5%（48dp/27dp）+ 面板密度表：
  <https://developer.android.com/training/tv/playback/compose/layouts>
- TV 设计指南 · 布局（960×540 mdpi 基准、5% 边距）：
  <https://developer.android.com/design/ui/tv/guides/styles/layouts>
- TV 设计指南 · 字体：
  <https://developer.android.com/design/ui/tv/guides/styles/typography>
- Android 14 非线性字体缩放（不要用 `scaledDensity` 手算）：
  <https://developer.android.com/about/versions/14/features>
- `Configuration.fontScale` 官方注释：
  <https://developer.android.com/reference/android/content/res/Configuration#fontScale>
- `Display` API 级别与废弃信息：
  <https://developer.android.com/reference/android/view/Display>
- `WindowManager.getDefaultDisplay()` 废弃（API 30）：
  <https://developer.android.com/reference/android/view/WindowManager>
- `SurfaceHolder.setFixedSize`：
  <https://developer.android.com/reference/android/view/SurfaceHolder>
- CDD（逻辑密度取值集合、`DENSITY_DEVICE_STABLE`）：
  <https://source.android.com/docs/compatibility/8.0/android-8.0-cdd>

**社区案例 / 新闻**
- **xbmc commit e64a470**（Amlogic，"GUI covering only 1/4 screen"）：
  <https://github.com/xbmc/xbmc/commit/e64a470d294d9978869c903c294d8a3e91190fd7>
- CoreELEC 论坛（Amlogic GUI 1080p + 4K 输出 → 1/4 屏）：
  <https://discourse.coreelec.org/t/solved-sometimes-video-starts-in-just-1-4th-of-the-screen/12059>
- SmartTube issue #5102（`geomBufferSize 1920x1080` + `SCALE 2.0000` 的 dumpsys 证据）：
  <https://github.com/yuliskov/SmartTube/issues/5102>
- SO 61170081 · SurfaceView Canvas rendering at 1080p on 4K Android TV：
  <https://stackoverflow.com/questions/61170081/surfaceview-canvas-rendering-at-1080p-on-4k-android-tv>
- SO 54242590 · Layout elements looks blurry on 4K screen Android TV：
  <https://stackoverflow.com/questions/54242590/layout-elements-looks-blurry-on-4k-screen-android-tv>
- SO 33258206 · Real 4K in Android TV：
  <https://stackoverflow.com/questions/33258206/real-4k-in-android-tv>
- SO 33544008 · how to display a perfect 4k picture android TV：
  <https://stackoverflow.com/questions/33544008/how-to-display-a-perfect-4k-picture-android-tv>
- SO 32557228 · Detecting 4K UHD screens on Android（DisplayCompat 用法）：
  <https://stackoverflow.com/questions/32557228/>
- **SO 76426418 · Chromecast with Google TV (4K) 实测 `wm density` = 640 / CSS 视口 960×540**：
  <https://stackoverflow.com/questions/76426418/why-is-android-tv-app-having-a-web-view-with-low-resolution>
- **androidx/media issue #1986 · ATV 11 vs ATV 12 的 `Display.Mode` 上报准确性**：
  <https://github.com/androidx/media/issues/1986>
- SO 50921330 · getSupportedModes() 只报 1080p（Sony）：
  <https://stackoverflow.com/questions/50921330/>
- CSDN · MStar 4K UI 白名单 `system/etc/4k2k_app.xml`（⚠️ 二手）：
  <https://blog.csdn.net/u011044707/article/details/137139591>
- 9to5Google（Android TV 12 4K UI）：
  <https://9to5google.com/2021/07/14/android-tv-12-4k-ui-beta-3/>
- WCAG 2.2 对比度最低要求：
  <https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html>

---

## 附：子代理原始调研材料

并行子代理产出的原始材料保留在 `docs/research/4K-DISPLAY-METRICS-REPORT.md`
（显示度量 / 版本沿革 / SoC 证据）。

它**未经逐条回源核对**（关键条目已复核并写入本报告 §1/§3/§5），
**引用前请以本报告 §7/§8 为准**；与子代理结论不一致处已在 §1.2、§7.3、§7.15 显式更正。

当时还散落在仓库根目录的三份草稿（`research-androidtv-4k-ui-scaling.md`、
`android-tv-4k-display-metrics-*.md`、`android-tv-design-guidance-research.md`）已删除：
它们含有本报告纠正过的错误结论（例如把 `config_maxUiWidth` 写成 `config_supports4kUi`、
把 android12 分支的密度缺陷方向判断反了），留着只会误导。
