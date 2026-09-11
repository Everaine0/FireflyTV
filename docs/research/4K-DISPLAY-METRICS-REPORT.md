# How Android TV reports display metrics on 4K (3840×2160) panels

**Evidence-based research report.** Every factual claim carries a source link. Claims I could not
verify are marked **[UNVERIFIED]** and collected in the final section.

Scope: AOSP/Android TV behaviour, the `config_maxUiWidth` mechanism, density defaults, vendor
(Amlogic / MediaTek / MStar) evidence, developer-facing APIs, and the 1080p-UI-upscaled-to-4K
mechanism.

---

## TL;DR — the five things that matter

1. **The resource you are looking for is `config_maxUiWidth`, not `config_supports4kUi`.** I could
   not find `config_supports4kUi` anywhere in AOSP — see §2.0. **[UNVERIFIED / apparently non-existent]**
2. **AOSP's Android TV builds cap the UI at 1920 px wide** and scale height *and density*
   proportionally. So on a 3840×2160 panel a third-party app typically sees
   **`widthPixels = 1920`, `heightPixels = 1080`, `densityDpi = panelDensity / 2`** (e.g. 640 → 320).
3. **The 1920×1080 layer stack is scaled onto the 3840×2160 panel by SurfaceFlinger/HWC** — that is
   the transparent upscale, implemented as a *display projection*
   (`layerStackSpaceRect` → `orientedDisplaySpaceRect`).
4. **`getRealSize()` / `getRealMetrics()` return the *capped* logical size, not the panel size.**
   The only reliable panel-resolution API is `Display.Mode.getPhysicalWidth()/getPhysicalHeight()`
   (API 23+) or `androidx.core.view.DisplayCompat` (older).
5. **Android TV 12 added official 4K UI support**, implemented in AOSP as an overlay that sets
   `config_maxUiWidth = 0` — but that overlay ships only with the **TV emulator/SDK** products, and
   the normal TV product overlay still caps at 1920 even on `main`. From **Android 15 QPR1** the cap
   became **density-selected**: `tvdpi`→1280, `xhdpi`→1920, `xxxhdpi`→3840.

---

## §1. What `DisplayMetrics` reports on a 3840×2160 Android TV panel

### 1.1 The controlling mechanism

`config_maxUiWidth` is a framework integer resource. AOSP's own default is **no constraint**:

```xml
<!-- Maximum size, specified in pixels, to restrain the display space width to. Height and
     density will be scaled accordingly to maintain aspect ratio. A value of 0 indicates no
     constraint will be enforced. -->
<integer name="config_maxUiWidth">0</integer>
```
— `frameworks/base/core/res/res/values/config.xml`
([android13-release, line 4010](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/res/res/values/config.xml);
[refs/heads/main](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/values/config.xml))

The Android TV product overlay overrides it to **1920**:

```xml
<!-- Maximum size, specified in pixels, to restrain the display space width to. Height and
     density will be scaled accordingly to maintain aspect ratio. A value of 0 indicates no
     constraint will be enforced.
     ATV default dpis limits the UI graphics width to 1920 because higher resolution is
     unnecessary and causes too much overhead on the GPU for Android TV devices. -->
<integer name="config_maxUiWidth">1920</integer>
```
— `device/google/atv/overlay/TvFrameworkOverlay/res/values/config.xml`
([refs/heads/main](https://android.googlesource.com/device/google/atv/+/refs/heads/main/overlay/TvFrameworkOverlay/res/values/config.xml);
[android-16.0.0_r1, line 173](https://android.googlesource.com/device/google/atv/+/refs/tags/android-16.0.0_r1/overlay/TvFrameworkOverlay/res/values/config.xml))

### 1.2 What the flag does at runtime — exact code

`WindowManagerService` reads the resource once:
```java
mMaxUiWidth = context.getResources().getInteger(
        com.android.internal.R.integer.config_maxUiWidth);
...
if (mMaxUiWidth > 0) {
    mRoot.forAllDisplays(displayContent -> displayContent.setMaxUiWidth(mMaxUiWidth));
}
```
— [WindowManagerService.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/wm/WindowManagerService.java)

`DisplayContent.updateBaseDisplayMetrics()` then clamps the **base (app-visible) metrics**:

```java
if (mMaxUiWidth > 0 && mBaseDisplayWidth > mMaxUiWidth) {
    final float ratio = mMaxUiWidth / (float) mBaseDisplayWidth;
    mBaseDisplayHeight = (int) (mBaseDisplayHeight * ratio);
    mBaseDisplayWidth = mMaxUiWidth;
    mBaseDisplayPhysicalXDpi = mBaseDisplayPhysicalXDpi * ratio;
    mBaseDisplayPhysicalYDpi = mBaseDisplayPhysicalYDpi * ratio;
    if (!mIsDensityForced) {
        // Update the density proportionally so the size of the UI elements won't change
        // from the user's perspective.
        mBaseDisplayDensity = (int) (mBaseDisplayDensity * ratio);
    }
    ...
}
```
— [DisplayContent.java, android13-release, ~line 2852](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/wm/DisplayContent.java)

The introducing commit described the intent exactly:

> "This changelist adds config_maxUiWidth, a new system resource configuration which specifies the
> maximum width the user interface can operate in. **If the physical or specified width is greater
> than this value, dimensions and density are scaled down accordingly. The native mode resolution
> can be still discovered through Display.Mode#getPhysicalWidth/getPhysicalHeight.**"
— commit `27cec32496090efd153327f4fc5a5cecc6f59d9b`, "Add configuration for maximum UI width",
Bryce Lee, 2017-03-21, `Fixes: 25820708`
([android.googlesource.com](https://android.googlesource.com/platform/frameworks/base/+/27cec32496090efd153327f4fc5a5cecc6f59d9b))

### 1.3 Concrete reported numbers

| Panel (physical) | Panel density | `config_maxUiWidth` | `widthPixels` / `heightPixels` | `densityDpi` | dp canvas |
|---|---|---|---|---|---|
| 3840×2160 | 640 (xxxhdpi) | 1920 (ATV ≤15 default) | **1920 × 1080** | **320** | 960×540 |
| 3840×2160 | 320 (xhdpi) | 1920 | **1920 × 1080** | **160** | 1920×1080 |
| 3840×2160 | 640 (xxxhdpi) | 3840 (ATV 15 QPR1+ on xxxhdpi) | **3840 × 2160** | **640** | 960×540 |
| 3840×2160 | 240 | 1920 | 1920 × 1080 | 120 | 2560×1440 |
| 3840×2160 | 640 | **forced density** (`wm density`) | 1920 × 1080 | **640 (not scaled)** | 480×270 ← "tiny UI" |
| 1920×1080 | 320 (xhdpi) | 1920 (no clamp) | 1920 × 1080 | 320 | 960×540 |

The density numbers follow directly from the `ratio` arithmetic above. The `mIsDensityForced` guard
is real: if density has been forced via `wm density` / `Settings.Secure.display_density_forced`, the
clamp does **not** rescale density (see `setForcedDensity()` in the same file), which is a
source-level explanation for a "tiny UI" symptom on a clamped 4K panel. **[this specific symptom is
an inference from source, not a confirmed bug report — UNVERIFIED]**

### 1.4 `Display.getRealSize()` / `getRealMetrics()` do NOT return 3840

```java
public void getRealSize(Point outSize) {
    ...
    outSize.x = mDisplayInfo.logicalWidth;      // the CAPPED value
    outSize.y = mDisplayInfo.logicalHeight;
}
public void getRealMetrics(DisplayMetrics outMetrics) {
    ...
    mDisplayInfo.getLogicalMetrics(outMetrics, CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null);
}
```
— [Display.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/view/Display.java) ·
identical structure in [android10](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/core/java/android/view/Display.java),
[android11](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/android/view/Display.java),
[android12](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/view/Display.java)

`DisplayInfo.getLogicalMetrics()` uses `logicalWidth/logicalHeight`, and even
`getNaturalWidth()` returns `logicalWidth`:
```java
public void getLogicalMetrics(DisplayMetrics outMetrics, CompatibilityInfo compatInfo,
        Configuration configuration) {
    getMetricsWithSize(outMetrics, compatInfo, configuration, logicalWidth, logicalHeight);
}
public int getNaturalWidth() {
    return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180 ?
            logicalWidth : logicalHeight;
}
```
— [DisplayInfo.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/view/DisplayInfo.java)

`LogicalDisplay.getDisplayInfoLocked()` is where the override lands — `logicalWidth` is taken from
`mOverrideDisplayInfo` (the WM-clamped value) rather than from the physical device:
```java
info.copyFrom(mBaseDisplayInfo);
if (mOverrideDisplayInfo != null) {
    ...
    info.logicalWidth = mOverrideDisplayInfo.logicalWidth;
    info.logicalHeight = mOverrideDisplayInfo.logicalHeight;
    ...
    info.logicalDensityDpi = mOverrideDisplayInfo.logicalDensityDpi;
}
```
— [LogicalDisplay.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/display/LogicalDisplay.java)

**Therefore: a report of "`getRealSize()` returns 3840" requires `config_maxUiWidth` to be 0 or the
panel width to be ≤ the cap.** With the stock AOSP ATV overlay, `getRealSize()` returns 1920×1080.
The 3840 figure is reachable through `Display.Mode`:

> "Returns the physical width of the display in pixels when configured in this mode's resolution.
> Note that due to application UI scaling, the number of pixels made available to applications when
> the mode is active (as reported by `Display#getWidth()`) may differ from the mode's actual
> resolution (as reported by this function). **For example, applications running on a 4K display may
> have their UI laid out and rendered in 1080p and then scaled up.** Applications can take advantage
> of the extra resolution by rendering content through a `SurfaceView` using full size buffers."
— `Display.Mode.getPhysicalWidth()`, [Display.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/view/Display.java)

### 1.5 Is the UI composited at 1920×1080 and upscaled, or rendered at native 3840×2160?

**Both are possible, and the switch is the overlay resource.** The canonical statement of the
legacy behaviour is the Android 6.0 (API 23) release note:

> **4K Display Mode** — The platform now allows apps to request that the display resolution be
> upgraded to 4K rendering on compatible hardware. To query the current physical resolution, use the
> new Display.Mode APIs. **If the UI is drawn at a lower logical resolution and is upscaled to a
> larger physical resolution, be aware that the physical resolution the getPhysicalWidth() method
> returns may differ from the logical resolution reported by getSize().** You can request the system
> to change the physical resolution in your app as it runs, by setting the preferredDisplayModeId
> property of your app's window. [...] **While in 4K display mode, the UI continues to be rendered at
> the original resolution (such as 1080p) and is upscaled to 4K, but SurfaceView objects may show
> content at the native resolution.**
— [developer.android.com/about/versions/marshmallow/android-6.0](https://developer.android.com/about/versions/marshmallow/android-6.0)

Android 12 for TV changed this, officially:

> **4K UI support:** For added visual fidelity, **Android TV OS now officially supports UI rendering
> at 4k resolution on compatible devices.** 4K UI resolution can be tested in the upcoming Android 12
> emulator for TV to allow app developers to prepare their app for devices with the higher resolution.
— [Android Developers Blog, "Android 12 Beta 3 for TV is now available", 14 July 2021, Wolfram Klein, PM Android TV OS](https://android-developers.googleblog.com/2021/07/android-12-beta-3-for-tv-is-now.html)

Also listed as "4K UI support" under *User interface* in the
[Android 12 for TV release notes](https://developer.android.com/tv/release/12).

Journalistic confirmation of the "before" state:

> "Since its launch in 2014, Android TV and, in turn, Google TV **have always rendered the homescreen
> and apps at 1080p, only switching the signal to 4K when content starts playing.** In Android TV 12,
> UI elements will be able to render at 4K on 'compatible devices.'"
— [9to5Google, 14 July 2021](https://9to5google.com/2021/07/14/android-tv-12-4k-ui-beta-3/)

> "While Android TV has always supported 4K content playback, Android 12 will be the first time that
> the UI itself will be rendered in 4K. **Previously, the UI was rendered at 1080p maximum and then
> upscaled to fit your 4K TV.**"
— [XDA Developers](https://www.xda-developers.com/android-12-android-tv-native-4k-refresh-rate/)

---

## §2. The overlay resource: `config_supports4kUi` vs the real `config_maxUiWidth`

### 2.0 `config_supports4kUi` — could NOT be verified; appears not to exist

I specifically searched for the token `config_supports4kUi` (and `supports4k`, `4kUi`) and found **no
match** in:

* `device/google/atv` — full-repo archive at `refs/heads/main` and at tag `android-12.0.0_r1`
  ([gitiles](https://android.googlesource.com/device/google/atv/+/refs/heads/main/))
* `frameworks/base/core/res` — full `core/res` archive at `android13-release`
  ([gitiles](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/res/))
* `packages/apps/TvSettings` at `android13-release`
  ([gitiles](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android13-release/))
* Web searches returned no AOSP hit for the identifier.

**Conclusion: `config_supports4kUi` is most likely a misremembered name; the real resource is
`config_maxUiWidth`. [UNVERIFIED as an AOSP resource — treat as non-existent.]**

### 2.1 The three relevant overlay declarations (verbatim)

**(a) Framework default — no cap, Android 8.0 → present**
```xml
<!-- Maximum size, specified in pixels, to restrain the display space width to. Height and
     density will be scaled accordingly to maintain aspect ratio. A value of 0 indicates no
     constraint will be enforced. -->
<integer name="config_maxUiWidth">0</integer>
```
[android13-release config.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/res/res/values/config.xml)

**(b) Android TV product overlay — 1920 (the "1080p UI" default)**
```xml
<integer name="config_maxUiWidth">1920</integer>
```
[device/google/atv TvFrameworkOverlay, refs/heads/main](https://android.googlesource.com/device/google/atv/+/refs/heads/main/overlay/TvFrameworkOverlay/res/values/config.xml)
· included via `PRODUCT_PACKAGES += TvFrameworkOverlay`
([atv_product.mk](https://android.googlesource.com/device/google/atv/+/refs/heads/main/products/atv_product.mk))

**(c) Android TV SDK/emulator overlay — 0 = native resolution (the actual "4K UI" switch)**
```xml
<!-- Do not restrict the UI resolution on the WM side; use the native resolution of the device's
     screen -->
<integer name="config_maxUiWidth">0</integer>
```
— `device/google/atv/sdk_overlay/frameworks/base/core/res/res/values/config.xml`
([android-12.0.0_r1](https://android.googlesource.com/device/google/atv/+/refs/tags/android-12.0.0_r1/sdk_overlay/frameworks/base/core/res/res/values/config.xml))
· included only by the emulator vendor makefile:
```
DEVICE_PACKAGE_OVERLAYS += \
    device/generic/goldfish/overlay \
    device/google/atv/sdk_overlay \
    development/sdk_overlay
```
([atv_emulator_vendor.mk](https://android.googlesource.com/device/google/atv/+/refs/tags/android-12.0.0_r1/products/atv_emulator_vendor.mk))

### 2.2 Version history (verified by fetching each release tag/archive)

| Android TV version | AOSP ATV overlay `config_maxUiWidth` | Evidence |
|---|---|---|
| 5.1.1, 6.0.1, 7.1.2, 8.0.0, 8.1.0 | **absent** (resource declaration itself only lands in Android 8.0) | tag archives of `device/google/atv` |
| 9.0.0, 10.0.0 | **absent** from the ATV overlay | tag archives |
| **11.0.0** | **1920** (first ATV release with the cap) | [commit `92b6197`, 2020-04-21](https://android.googlesource.com/device/google/atv/+/92b6197) |
| **12.0.0** | TvFrameworkOverlay = **1920**; `sdk_overlay` = **0** (new) | [tag android-12.0.0_r1](https://android.googlesource.com/device/google/atv/+/refs/tags/android-12.0.0_r1/) |
| 12.1, 13, 14, 15.0.0_r1 | TvFrameworkOverlay = **1920** | tag archives |
| **15 QPR1**, 15 QPR2, 16, 17 | TvFrameworkOverlay default = **1920**, but **per-density overrides**: `values-tvdpi`→**1280**, `values-xhdpi`→**1920**, `values-xxxhdpi`→**3840** | [commit `6b0eaafd`, 2024-06-26](https://android.googlesource.com/device/google/atv/+/6b0eaafdbe7456087a97555726341efab3e2d1cc) |

The ATV 11 decision, verbatim:

> "**Set config_maxUiWidth to 1920** — We limit the UI graphics resolution to 1080p because higher
> resolution is unnecessary and causes too much overhead on the GPU. This doesn't affect hardware
> composed video streams as well as apps that create native views or intentionally render into a
> different-sized Surface. Test: Manually - built and flashed a device. Verified that UI runs at low
> resolution and YouTube videos run are not affected. Bug: 145791247"
— commit `92b6197e647fcb7339d00e57fd179bc5a3d26c99`, Marin Shalamanov, 2020-04-21
([android.googlesource.com](https://android.googlesource.com/device/google/atv/+/92b6197))

The Android TV 12 "4K UI" enablement commit for the emulator, verbatim:

> "**Lift maxUiWidth restiction on TV emulator** — Bug: 189182666 — Test: `adb shell wm size` >
> `Physical size: 3840x2160`"
— commit `6d0eee509883928a977a97229d2971a82ff022ef`, Sergey Nikolaienkov, 2021-06-15/16,
adding `<integer name="config_maxUiWidth">0</integer>` to `sdk_overlay`
([android.googlesource.com](https://android.googlesource.com/device/google/atv/+/6d0eee509883928a977a97229d2971a82ff022ef))

The Android 15 QPR1 per-density commit, verbatim:

> "**Configure config_maxUiWidth per dpi** — Fix: 343252722 — Flag: EXEMPT bugfix — Test: `wm size` on
> both 2K and 4K TVs"
— commit `6b0eaafdbe7456087a97555726341efab3e2d1cc`, Hongguang Chen, 2024-06-26
([android.googlesource.com](https://android.googlesource.com/device/google/atv/+/6b0eaafdbe7456087a97555726341efab3e2d1cc))
It adds `values-tvdpi` (`"ATV tvdpi devices limits the UI graphics width to 1280"`),
`values-xhdpi` (1920) and `values-xxxhdpi`
(`"ATV xxxhdpi devices limits the UI graphics width to 3840"`).
Present on `android15-qpr1-release`, `android15-qpr2-release`, `android16-release`;
absent on `android15-release` and `android-15.0.0_r1` (verified per-branch).

**Practical consequence:** on Android 15 QPR1+ the cap is chosen by the device's declared density —
a 4K TV that declares **xxxhdpi (640)** gets a **native 3840-wide UI**; one that declares
**xhdpi (320)** still gets a 1920-wide UI upscaled.

### 2.3 What `config_maxUiWidth` does and does not control

* It restrains **UI graphics** only. The ATV 11 commit states it "doesn't affect hardware composed
  video streams as well as apps that create native views or intentionally render into a
  different-sized Surface" ([commit 92b6197](https://android.googlesource.com/device/google/atv/+/92b6197)).
* The native mode remains discoverable through `Display.Mode#getPhysicalWidth/getPhysicalHeight`
  ([commit 27cec324](https://android.googlesource.com/platform/frameworks/base/+/27cec32496090efd153327f4fc5a5cecc6f59d9b)).
* It is a **resource overlay**, so it must be changed per device/vendor; there is no public
  developer-facing documentation for it. **[UNVERIFIED: no developer.android.com page documents
  `config_maxUiWidth`.]**

---

## §3. Default density for TV devices

### 3.1 There is no `config_defaultDensity` and no `DisplayMetrics.DEFAULT_DENSITY`

Grepping the whole `frameworks/base/core/res` archive at `android13-release` for
`config_defaultDensity` and `defaultDensity` returns **no matches**
([core/res tree](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/res/)).
`DisplayMetrics.java` likewise contains no `DEFAULT_DENSITY` symbol.

**Both names in the question are misremembered.** The actual constants are `DENSITY_DEFAULT` and
`DENSITY_DEVICE_STABLE`, and the real default is **160 (mdpi)** sourced from a system property:

```java
/**
 * The reference density used throughout the system.
 */
public static final int DENSITY_DEFAULT = DENSITY_MEDIUM;   // == 160
...
public static final int DENSITY_DEVICE_STABLE = getDeviceDensity();
...
private static int getDeviceDensity() {
    // qemu.sf.lcd_density can be used to override ro.sf.lcd_density
    // when running in the emulator, allowing for dynamic configurations.
    // The reason for this is that ro.sf.lcd_density is write-once and is
    // set by the init process when it parses build.prop before anything else.
    return SystemProperties.getInt("qemu.sf.lcd_density",
            SystemProperties.getInt("ro.sf.lcd_density", DENSITY_DEFAULT));
}
```
— [DisplayMetrics.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/util/DisplayMetrics.java)

`ro.sf.lcd_density` is read on the native side by SurfaceFlinger
(`getDensityFromProperty`), see
[SurfaceFlinger.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android13-release/services/surfaceflinger/SurfaceFlinger.cpp).

### 3.2 Is Android TV's density 320 (xhdpi) or 213 (tvdpi)? — **320 for 1080p, 213 for 720p**

`DENSITY_TV = 213` exists, but its own javadoc says it is the **720p** density:

> "This density was original introduced to correspond with a **720p TV screen: the density for 1080p
> televisions is `DENSITY_XHIGH`**, and the value here provides the same UI size for a TV running at
> 720p."
— `DisplayMetrics.DENSITY_TV = 213`
([DisplayMetrics.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/util/DisplayMetrics.java))

And `DENSITY_XXXHIGH = 640` is documented as the 4K TV density:

> "A typical use of this density would be **4K television screens -- 3840x2160, which is 2x a
> traditional HD 1920x1080 screen which runs at DENSITY_XHIGH.**"
— `DisplayMetrics.DENSITY_XXXHIGH = 640`
([DisplayMetrics.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/util/DisplayMetrics.java))

AOSP's Android TV SDK device profiles confirm 320/213:

| Profile | Resolution | `pixel-density` | diagonal | xdpi |
|---|---|---|---|---|
| Android TV (1080p) | 1920×1080 | **xhdpi** | 55.0" | 40.05 |
| Android TV (720p) | 1280×720 | **tvdpi** | 55.0" | 26.70 |
— [`device/google/atv/sdk/devices.xml`](https://android.googlesource.com/device/google/atv/+/refs/heads/main/sdk/devices.xml)
(identical in [android-12.0.0_r1](https://android.googlesource.com/device/google/atv/+/refs/tags/android-12.0.0_r1/sdk/devices.xml))

The TV emulator config sets 320 explicitly:
```
hw.lcd.density=320
skin.name=1920x1080
skin.path=1920x1080
```
— `device/generic/goldfish/data/etc/config.ini.tv`
([gitiles](https://android.googlesource.com/device/generic/goldfish/+/refs/heads/android13-release/data/etc/config.ini.tv))

Real-world density values found:

| Device / vendor tree | Value | Source |
|---|---|---|
| AOSP `device/amlogic/yukawa` (official ATV reference device) | `ro.sf.lcd_density=320` | [device.mk line 208](https://android.googlesource.com/device/amlogic/yukawa/+/refs/heads/main/device.mk) |
| Amazon Fire TV (all outputs incl. 1080p/720p/480p) | **320 / xhdpi / render surface fixed 1920×1080** | [Amazon Fire TV display-and-layout docs](https://developer.amazon.com/docs/fire-tv/display-and-layout.html) |
| Chromecast with Google TV (4K), ATV 12+ | `adb shell wm density` → **Physical density: 640** | [SO 76426418 answer](https://stackoverflow.com/questions/76426418/why-is-android-tv-app-having-a-web-view-with-low-resolution) |
| Amlogic S905X3 X96 Max+ (Android 9) | 240 | [mpv-android #293](https://github.com/mpv-android/mpv-android/issues/293) / [4K mod repo](https://github.com/MorphyHu/android-4k-mod-amlogic-s905x3-x96max-plus) |
| Amlogic Khadas VIM1 (S905X) | 240 | [Khadas forum](https://forum.khadas.com/t/how-to-use-real-hdmi-resolutions/521) |
| MStar Amber3 (ICS-era vendor tree) | `ro.sf.lcd_density=240` → 160 | [cnblogs vendor notes](https://www.cnblogs.com/jiangzhaowei/p/11812845.html) |

Amazon's Fire TV table is a particularly clean primary source — it shows the **render surface is
1920×1080 at 320 dpi regardless of the 720p/1080p/480p output**, i.e. 960×540 dp:

| TV setting | Output resolution | Render surface | Density | Display resolution (dp) |
|---|---|---|---|---|
| 1080p | 1920×1080 | 1920×1080 | xhdpi, 320 | 960×540 |
| 720p | 1280×720 | **1920×1080** | xhdpi, 320 | 960×540 |
| 480p | 640×480 | **1920×1080** | xhdpi, 320 | 960×540 |
— [developer.amazon.com/docs/fire-tv/display-and-layout.html](https://developer.amazon.com/docs/fire-tv/display-and-layout.html)

---

## §4. SoC-level evidence: Amlogic, MediaTek, MStar

**Headline: the SoC does not choose the UI resolution — the platform overlay resource plus the
declared density do.** All SoC-specific evidence below is about which values each vendor/box ships.

### 4.1 Amlogic

* `device/amlogic/yukawa` (AOSP's official Amlogic ATV reference device) inherits
  `device/google/atv/products/atv_base.mk` (line 58), sets `PRODUCT_IS_ATV := true` (line 60) and
  **`PRODUCT_PROPERTY_OVERRIDES += ro.sf.lcd_density=320`** (line 208) with **no** `config_maxUiWidth`
  of its own — so it inherits the AOSP TV 1920 cap.
  [device.mk](https://android.googlesource.com/device/amlogic/yukawa/+/refs/heads/main/device.mk)
* Amlogic's own ATV tree (BayLibre mirror) likewise inherits `atv_base.mk` and sets
  `ro.sf.lcd_density=320`
  ([device-common.mk](https://gitlab.baylibre.com/baylibre/amlogic/atv/aosp/device/amlogic/yukawa/-/raw/master/device-common.mk)).
* Real box report — **X96Max+2 TV Box, stock Android 9** (mpv-android issue #293, 2020-08-03):
  > "Though 720p@60Hz was set as system resolution and 'getprop' output was
  > `[vendor.display-size]: [1280x720]`, mpv-android logcat shows `android-surface-size=1920x1080`
  > and that video is resized to 1920x1080, and it seems to be a bit blurry."
  [github.com/mpv-android/mpv-android/issues/293](https://github.com/mpv-android/mpv-android/issues/293)
  → concrete proof that on an Amlogic box the **app-visible surface size (1920×1080) is decoupled
  from the HDMI output/display mode (1280×720)**, and that the mismatch causes blur.
* **Amlogic S905X3 / X96 Max+ (Android 9)** community mod documents exactly the "make the UI 4K"
  procedure: patch `dtbo` + a modified `hwcomposer.amlogic-4k.so`; afterwards `wm size` reports
  `Physical size: 3840x2160` and `wm density` reports `Physical density: 240`, with
  `/proc/device-tree/fb/sceext_4k` used as the switch.
  [github.com/MorphyHu/android-4k-mod-amlogic-s905x3-x96max-plus](https://github.com/MorphyHu/android-4k-mod-amlogic-s905x3-x96max-plus)
  → at 240 dpi, a 1920-wide UI is 1280 dp but a 3840-wide UI is **2560 dp**, i.e. enabling a native
  4K UI doubles the dp canvas and halves apparent element size unless density is doubled too. This
  is the mechanism behind "UI too small on 4K".
* **Amlogic Khadas VIM1 (S905X)** raw dumpsys (Khadas forum, 2017):
  `app 1920 x 1080, real 1920 x 1080, modes [{id=1,width=1920,height=1080,fps=60.000004}],
  density 240 (159.895 x 160.421) dpi`, with the complaint "The image is always displayed
  1920x1080 regardless of the selected resolution!"
  [forum.khadas.com/t/how-to-use-real-hdmi-resolutions/521](https://forum.khadas.com/t/how-to-use-real-hdmi-resolutions/521)
* **`ro.amlogic.*` display properties: none found. [UNVERIFIED]** Amlogic uses u-boot env vars, the
  meson-fb device-tree node, `/proc/device-tree/fb/*` and `mesondisplay.cfg` instead.

### 4.2 MediaTek (MT55xx / MT58xx / MT96xx)

* **Amazon Fire TV** (MediaTek-based) is the cleanest vendor documentation: render surface fixed at
  **1920×1080 / xhdpi / 320 dpi / 960×540 dp** across 480p, 720p and 1080p outputs —
  [Amazon docs](https://developer.amazon.com/docs/fire-tv/display-and-layout.html).
* **Sony Bravia 4K (MediaTek)** — ExoPlayer issue #6083: "On many Android TV devices, such as Sony
  Bravia 4K televisions, the only display resolution reported to the application is 1080/60."
  [github.com/google/ExoPlayer/issues/6083](https://github.com/google/ExoPlayer/issues/6083)
* **An OEM Android TV box, ATV 11 vs ATV 12** (androidx/media issue #1986, 2024-12-18) — the single
  best vendor-behaviour datapoint in this report:

  | Signal | Previous box (ATV 11) | New box (ATV 12) |
  |---|---|---|
  | `vendor.display-size` | OK, 4K | Error (access denied) |
  | `vendor.nx.display-size` | OK, 4K | OK, 4K |
  | `Display.getMode().getPhysicalWidth/Height` | **wrong — 1080p** | **OK — 4K** |

  > "Our OEM tells us that `vendor.display-size` won't be used anymore since ATV 12 and we should
  > refer on `Display.getMode().physicalWidth/Height` instead."
  [github.com/androidx/media/issues/1986](https://github.com/androidx/media/issues/1986)

  This is direct evidence that on ATV 11 the panel's 4K resolution was **not** visible through
  `Display.Mode` (only through a vendor property), and that ATV 12 fixed mode reporting — matching
  Google's "Better display mode reporting" claim and the `config_maxUiWidth` change.
* **`device/mediatek/wembley` is not public** in AOSP; GitHub searches for `mt5895`, `mt9652`,
  `mt9602` device trees returned no results. **[UNVERIFIED: MediaTek ATV device-tree contents.]**
  MediaTek's own Pentonic 800 product page advertises "Max Display Resolution 3840 x 2160 (4K)" and
  describes "模擬 4K 使用者介面" (simulated 4K user interface)
  ([mediatek.com/zh-tw/products/pentonic/800](https://www.mediatek.com/zh-tw/products/pentonic/800));
  the English URL 404s. **[UNVERIFIED as a technical specification.]**

### 4.3 MStar

* `device/mstar` does not exist in AOSP. The best available evidence is ICS-era vendor source notes:
  `android/ics/device/mstar/mstaramber3/device.mk`, `PRODUCT_PROPERTY_OVERRIDES +=
  ro.sf.lcd_density=240`, later changed to 160 —
  [cnblogs.com/jiangzhaowei/p/11812845.html](https://www.cnblogs.com/jiangzhaowei/p/11812845.html)
* MStar TWRP device trees show `TARGET_SCREEN_DENSITY := 240` (6A938), 213 (JVC ATV LT-42M690) and
  320 (Yandex TV Harper 43F750TS, android-11, with a commented-out
  `#DEVICE_RESOLUTION := 1920x1080`). **[UNVERIFIED: these are TWRP variables, not
  `ro.sf.lcd_density`, and TWRP trees are third-party.]**
* **No vendor tree from Amlogic, MediaTek or MStar containing `config_maxUiWidth` was found.
  [UNVERIFIED — "not found by sampling", not proven absent: GitHub code search requires auth,
  and grep.app / Sourcegraph were blocked from this environment.]**

### 4.4 Additional real-world reports

* **TCL 85Q10K, Android 11** (Chinese, 2024-07-27): "TCL 85q10k电视的Android 11系统和UI分辨率是1080p"
  — the TV's Android 11 system/UI resolution is 1080p, and 4K/HDR content is downsampled to 1080p.
  [github.com/aaa1115910/bv/issues/145](https://github.com/aaa1115910/bv/issues/145)
* **Kodi forum** (2024-02-23, Xiaomi TV Box S 2nd Gen): "limited to only 1080p resolution output for
  its apps"; "Whitelisted is 1080p only. I connected my keyboard and saw it was doing a 2160 > 1080
  conversion."
  [forum.kodi.tv/showthread.php?tid=376405](https://forum.kodi.tv/showthread.php?tid=376405)
* **Channels DVR forum** (2023-09-09): "The overlay renders at 1080 but the actual video is
  displaying in 4K"; community claim "ALL android TV, including Fire TV, renders their user
  interface at 1080p, not 4K" **[community claim, not authoritative]**.
  [community.getchannels.com](https://community.getchannels.com/t/channels-says-scaled-resolution-1920-x-1080-for-a-4k-stream-on-a-4k-tv/37451)

---

## §5. Issue Tracker, Stack Overflow, and `DisplayCompat`

### 5.1 Google Issue Tracker — mostly behind a sign-in wall

`issuetracker.google.com` serves a JavaScript SPA; unauthenticated fetches return only a sign-in
shell, and its search API rejects unauthenticated POSTs. Consequently:

* **Verified:** issue **242757484 "Android TV Hardware profile shows incorrect device size"**
  (component 192727) — reporter: *"When I create an Android TV emulator (for example Android TV
  (720p)) I see that size and density for this profile are: size - xlarge; density - xhdpi; Then I
  launch my application and from the code I see that this device has `large` screen."*
  [issuetracker.google.com/issues/242757484](https://issuetracker.google.com/issues/242757484)
  **[UNVERIFIED: issue status could not be decoded.]**
* **Buganizer IDs cited in verified AOSP commit messages but not publicly readable:**
  **25820708** (`config_maxUiWidth` introduction),
  **145791247** (ATV 11 `config_maxUiWidth=1920`), **189182666** (ATV 12 lift on emulator),
  **343252722** (Android 15 QPR1 per-dpi). These IDs are trustworthy *as commit-message text*; their
  bodies are not public. **[UNVERIFIED]**
* **Not found [UNVERIFIED, not proof of absence]:** a public issue for tiny UI on 4K Android TV,
  `getRealSize` vs `getSize` on Android TV, or `densityDpi` on 4K Android TV.

### 5.2 Stack Overflow — key threads with exact numbers

| Question | Score | Key content |
|---|---|---|
| [Display.getSupportedModes() returns only 1080p display mode on 4K Android TV](https://stackoverflow.com/questions/50921330/display-getsupportedmodes-returns-only-1080p-display-mode-on-4k-android-tv) (2018-06-19) | 6 | Sony KD-49XF9005: *"the API constantly returns me one and only one display mode i.e. 1080p"*, even though built-in apps show 4K |
| [SurfaceView Canvas rendering at 1080p on 4K Android TV](https://stackoverflow.com/questions/61170081/surfaceview-canvas-rendering-at-1080p-on-4k-android-tv) (2020-04-12) | 1 | Nvidia Shield TV, Android 9: *"the Canvas provided to the onDraw method of my view is only 1920x1080"*; `canvas.clipBounds is Rect(0, 0 - 1920, 1080)`; `preferredDisplayModeId` set to a 4K mode did **not** help |
| [Why is Android TV app having a Web View with low resolution](https://stackoverflow.com/questions/76426418/why-is-android-tv-app-having-a-web-view-with-low-resolution) (2023-06-07) | 4 (+8 accepted answer) | Chromecast with Google TV 4K: WebView reports 540×960 CSS px; `window.devicePixelRatio == 4`; `adb shell wm density` → **`Physical density: 640`**; "The density is the same (640) on Android 4K TV emulator" |
| [Layout elements looks blurry on 4K screen Android TV](https://stackoverflow.com/questions/54242590/layout-elements-looks-blurry-on-4k-screen-android-tv) (2019-01-17) | 1 | Answer quotes the Android 6.0 release note: *"While in 4K display mode, the UI continues to be rendered at the original resolution (such as 1080p) and is upscaled to 4K, but SurfaceView objects may show content at the native resolution."* |
| [How to read display resolution through HDMI? DisplayCompat fine on AndroidTV directly, but not on an AndroidTV Stick](https://stackoverflow.com/questions/67998593/how-to-read-display-resolution-thorugh-hdmi-displaycompat-fine-on-androidtv-dir) (2021-06-16) | 1, 0 answers | *"Since AndroidTV scales down the UI for UHD/4K resolution TV devices, DisplayMetrics always return 1920x1080 for these higher resolution devices."*; quotes a commit as saying *"We've learned a lot about Android TV architecture in this area over the last year and the original concept is flawed. HDMI offers no way to determine what the 'native' mode is."* **[the commit quote itself UNVERIFIED]** |
| [Android application always at 1280x720 pixels while display is 1080p, why?](https://stackoverflow.com/questions/29464375/android-application-always-at-1280x720-pixels-while-display-is-1080p-why) (2015-04-06) | 2 | Android 4.4 set-top box: *"Device can output 480p 720p 1080p and 4K resolution on HDMI output, but my application is always rendered to 1280x720 and up-scaled to HDMI resolution."* All of `getRealMetrics`, `getSize`, `getRealSize` returned 1280×720 |
| [How to create TV 4k resolution emulator in Android Studio?](https://stackoverflow.com/questions/78954113/how-to-create-tv-4k-resolution-emulator-in-android-studio) (2024-09-05) | 2, 0 answers | AVD configured 3840×2160 / xxxhdpi 640, but the app reports different values — the emulator confusion around 4K TV profiles |
| [How to get screen dimensions as pixels in Android](https://stackoverflow.com/questions/1016896/how-to-get-screen-dimensions-as-pixels-in-android) | 1973 | Top answer: *"For API Level 30, `WindowMetrics.getBounds` is to be used."* [answer](https://stackoverflow.com/a/1016941) |
| [How to get real screen height and width?](https://stackoverflow.com/questions/14341041/) | 50 | Accepted answer: *"The trick is you must use: `display.getRealSize(size);` **not** `display.getSize(size);`"* [answer](https://stackoverflow.com/a/14342326) |

**Honest note:** there is **no highly-voted (>100) Stack Overflow answer** specifically about Android
TV 4K UI scaling. The highest-scored 4K-TV-specific question found is score 6.

### 5.3 Official API documentation and deprecation

Deprecation status, verified both on developer.android.com and by diffing AOSP release branches:

| API | Deprecated in |
|---|---|
| `Display.getSize` / `getMetrics` | **API 30** (Android 11) |
| `Display.getRealSize` / `getRealMetrics` | **API 31** (Android 12) |
| `Display.getWidth` / `getHeight` | API 15 |

Confirmed by diffing: in
[android11-release Display.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/android/view/Display.java)
`getRealSize`/`getRealMetrics` carry **no** `@Deprecated`, while in
[android12-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/view/Display.java)
they do.

`getRealSize` javadoc + deprecation text:
> "Gets the size of the largest region of the display accessible to an app in the current system
> state, without subtracting any window decor or applying scaling factors. [...] The returned value
> is **unsuitable to use when sizing and placing UI elements**, since it does not reflect the
> application window size in any of these scenarios. `WindowManager#getCurrentWindowMetrics()` is an
> alternative [...]
> @deprecated Use `WindowManager#getCurrentWindowMetrics()` to identify the current size of the
> activity window. UI-related work, such as choosing UI layouts, should rely upon
> `WindowMetrics#getBounds()`."
— [Display.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/view/Display.java) ·
[developer.android.com/reference/android/view/Display](https://developer.android.com/reference/android/view/Display)

> Note the trap: **"without [...] applying scaling factors" does not cover the window-manager UI
> clamp.** Because the implementation returns `mDisplayInfo.logicalWidth/Height`, `getRealSize()`
> returns the *capped* 1920×1080 on a stock capped 4K ATV build.

### 5.4 `androidx.core.view.DisplayCompat`

Introduced by AOSP/AndroidX commit `0d08d5a2491d407409970d890e87800c6c129699`, "Add DisplayCompat to
get physical display size", Alan Viverette, 2019-12-02:

> "This is necessary to get the display size for 4k displays, because **current atv devices do not
> correctly handle / identify / add modes which results in no way for apps to identify the 4k
> capability of a display.** [...] The issue will be fixed by adding CTS tests and enforcing the
> correct behaviour in future Android versions. Bug: 138275089"
— [android.googlesource.com/platform/frameworks/support](https://android.googlesource.com/platform/frameworks/support/+/0d08d5a2491d407409970d890e87800c6c129699)

Verified API surface
([reference](https://developer.android.com/reference/androidx/core/view/DisplayCompat),
[source](https://raw.githubusercontent.com/androidx/androidx/androidx-main/core/core/src/main/java/androidx/core/view/DisplayCompat.java)):

```java
public static @NonNull ModeCompat getMode(@NonNull Context context, @NonNull Display display)
public static @NonNull ModeCompat[] getSupportedModes(@NonNull Context context, @NonNull Display display)
public static @Nullable Point parseDisplaySize(@NonNull String displaySize)
```
`ModeCompat`: `getPhysicalWidth()`, `getPhysicalHeight()`, `toMode()`, `isNative()`.

Class doc, verbatim:
> "**On many Android TV devices, `Display.Mode` may not report the accurate width and height because
> these devices do not have powerful enough graphics pipelines to run framework code at the same
> resolutions supported by their video pipelines.**"

Vendor workarounds inside `DisplayCompat` (verbatim from source):
```java
// From API 28 treble may prevent the system from writing sys.display-size so we check
// vendor.display-size instead.
Point displaySize = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
        ? parsePhysicalDisplaySizeFromSystemProperties("sys.display-size", display)
        : parsePhysicalDisplaySizeFromSystemProperties("vendor.display-size", display);
if (displaySize != null) {
    return displaySize;
} else if (isSonyBravia4kTv(context)) {
    // Sony Android TVs advertise support for 4k output via a system feature.
    ...
    return isCurrentModeTheLargestMode(display)
            ? new Point(DISPLAY_SIZE_4K_WIDTH, DISPLAY_SIZE_4K_HEIGHT)
            : null;
}
```
plus:
> "On Android TVs it is common for the UI to be configured for a lower resolution than SurfaceViews
> can output. Before API 26 the Display object does not provide a way to identify this case, and up
> to and including API 28 many devices still do not correctly set their hardware composer output size."

The Sony special case uses the feature string `com.sony.dtv.hardware.panel.qfhd`; the 4K constants
are **private**: `DISPLAY_SIZE_4K_WIDTH = 3840`, `DISPLAY_SIZE_4K_HEIGHT = 2160`.

**Do not code against `DisplayCompat.isUhdCapable`, `MODE_1080P` or `MODE_4K`.** Three independent
checks (the reference page has **no constants section**; `ModeCompat` lists only four methods; the
androidx-main source contains no such identifiers) indicate these **do not exist**. **[UNVERIFIED as
existing APIs — treat as non-existent.]**

Related workaround churn: `androidx/media` issue #1986 (see §4.2) documents an OEM telling a
developer that `vendor.display-size` is no longer usable from ATV 12, and
[an ExoPlayer commit "Remove max API level for reading TV resolution from system properties"](http://git.dev.7890it.com/SDK/exoplayer/commit/e7a7235a475c4cc25eabf9e3c870e30628dbf753).

---

## §6. Does Android TV upscale a 1920×1080 app window to a 4K panel? Surface size vs panel size

### 6.1 Yes — and the mechanism is a "display projection"

`LogicalDisplay.configureDisplayLocked()` builds two rectangles and hands them to SurfaceFlinger:

```java
// Set the viewport.
// This is the area of the logical display that we intend to show on the
// display device.  For now, it is always the full size of the logical display.
mTempLayerStackRect.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);
...
// Set the frame.
// The frame specifies the rotated physical coordinates into which the viewport
// is mapped.  We need to take care to preserve the aspect ratio of the viewport.
// Currently we maximize the area to fill the display, but we could try to be
// more clever and match resolutions.
boolean rotated = (orientation == Surface.ROTATION_90
        || orientation == Surface.ROTATION_270);
int physWidth = rotated ? displayDeviceInfo.height : displayDeviceInfo.width;
int physHeight = rotated ? displayDeviceInfo.width : displayDeviceInfo.height;
...
device.setProjectionLocked(t, orientation, mTempLayerStackRect, mTempDisplayRect);
```
— [LogicalDisplay.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/display/LogicalDisplay.java)

`displayInfo.logicalWidth` is the **capped** (1920) value; `displayDeviceInfo.width` is the
**physical** (3840) value. `DisplayDevice.setProjectionLocked()` documents the two rects:

> "@param layerStackRect defines which area of the window manager coordinate space will be used
> @param displayRect defines where on the display will layerStackRect be mapped to."
— [DisplayDevice.java, android13-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/display/DisplayDevice.java)

which calls the hidden `SurfaceControl.setDisplayProjection(displayToken, orientation,
layerStackRect, displayRect)`
([SurfaceControl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/view/SurfaceControl.java)),
and on the native side `DisplayDevice::setProjection(orientation, layerStackSpaceRect,
orientedDisplaySpaceRect)` → `CompositionDisplay::setProjection(...)`
([DisplayDevice.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android13-release/services/surfaceflinger/DisplayDevice.cpp),
[SurfaceFlinger.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android13-release/services/surfaceflinger/SurfaceFlinger.cpp)).

**So: the app's window lives in a 1920×1080 "layer stack space" that is mapped onto a 3840×2160
"oriented display space" — the transparent upscale.** Aspect-ratio mismatch produces letterbox or
pillarbox (`mTempDisplayRect` centring logic in the code above).

### 6.2 The `dumpsys SurfaceFlinger` signature of an upscaled UI

Two independent real-device artifacts show the same signature —
`Disp Frame 0 0 3840 2160` with `Source Crop 0.0 0.0 1920.0 1080.0`:

* **Nvidia Shield TV, Android 9** (SO 61170081, 2020-04-12):
  ```
  Layer name                Z | Comp Type | Disp Frame (LTRB) | Source Crop (LTRB)
  com.google.android.tvlauncher/...MainActivity#0 rel 0 | Device | 0 0 3840 2160 | 0.0 0.0 1920.0 1080.0
  com.android.tv.settings/...MainSettings#0       rel 0 | Client | 0 0 3840 2160 | 0.0 0.0 1920.0 1080.0
  ```
  [stackoverflow.com/questions/61170081](https://stackoverflow.com/questions/61170081/surfaceview-canvas-rendering-at-1080p-on-4k-android-tv)
* **SmartTube issue #5102** (2025-11-15, Android TV 13 via Waydroid on a 4K60 display):
  > "Apps like VLC, Android TV's homescreen, and NewPipe all render their UI at the full 4K
  > resolution. Meanwhile, SmartTube's UI is fuzzy, like it's capped to rendering the UI at 1080p.
  > [...] I checked `wm size`, and it correctly showed 4K."
  ```
  geomLayerTransform (ROT_0) (SCALE) 2.0000 0.0000 0.0000 0.0000 2.0000 0.0000 0.0000 0.0000 1.0000
  geomBufferSize=[0 0 1920 1080]   geomLayerBounds=[0.000000 0.000000 1920.000000 1080.000000]
  ...
  Disp Frame (LTRB) 0 0 3840 2160 | Source Crop (LTRB) 0.0 0.0 1920.0 1080.0
  ```
  [github.com/yuliskov/SmartTube/issues/5102](https://github.com/yuliskov/SmartTube/issues/5102)

**Practical diagnostic:** `dumpsys SurfaceFlinger` showing `geomBufferSize=[0 0 1920 1080]` (or
`SCALE 2.0000`) with a `3840 2160` display frame is the definitive fingerprint of a 1080p UI
upscaled to a 4K panel. Note SmartTube #5102 also proves 4K UI is **not** universal post-12 — the
platform rendered 4K there while that one app did not.

### 6.3 Surface size vs panel size — the three independent numbers

| Concept | Where it comes from | Typical 4K ATV value |
|---|---|---|
| App UI size (`DisplayMetrics.widthPixels`, `getSize`, `getRealSize`) | `DisplayInfo.logicalWidth/Height` = WM base metrics, **capped** by `config_maxUiWidth` | **1920×1080** |
| App density (`densityDpi`) | `DisplayInfo.logicalDensityDpi`, scaled by the same `ratio` | **320** (when panel is 640) |
| Physical panel / active mode | `DisplayDeviceInfo.width/height`; `Display.Mode.getPhysicalWidth/Height()`; `dumpsys display` | **3840×2160** |
| SurfaceFlinger layer stack → display projection | `layerStackSpaceRect` → `orientedDisplaySpaceRect` | 1920×1080 → 3840×2160 |

`wm size` prints the **logical** size, because `printInitialDisplaySize` uses
`getInitialDisplaySize` → `mInitialDisplayWidth = mDisplayInfo.logicalWidth`
([WindowManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/wm/WindowManagerShellCommand.java),
[DisplayContent.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/wm/DisplayContent.java)).
That is consistent with the AOSP commit whose test was `"adb shell wm size" > "Physical size:
3840x2160"` after lifting the cap
([commit 6d0eee5](https://android.googlesource.com/device/google/atv/+/6d0eee509883928a977a97229d2971a82ff022ef)).
**[UNVERIFIED: exact `wm size` output on a retail capped 4K TV — I had no device to test.]**

### 6.4 HDMI output mode switching (UI vs 4K video)

Confirmed by Google's own announcement and by the ATV 11 commit's "doesn't affect hardware composed
video streams" note:

* > "Since its launch in 2014, Android TV and, in turn, Google TV have always rendered the homescreen
  > and apps at 1080p, **only switching the signal to 4K when content starts playing**."
  — [9to5Google](https://9to5google.com/2021/07/14/android-tv-12-4k-ui-beta-3/)
* Apps can request the switch themselves via `WindowManager.LayoutParams.preferredDisplayModeId` and
  inspect modes via `Display.getSupportedModes()`
  ([Android 6.0 release notes](https://developer.android.com/about/versions/marshmallow/android-6.0)).
* Android 12 for TV improved this area:
  > "**Refresh Rate Switching Settings:** For a smoother viewing experience, Android 12 now supports
  > seamless and non-seamless refresh rate switching. [...] apps can call `Display.getMode` to know if
  > a user's device supports seamless rate switching."
  > "**Better display mode reporting:** We are improving how TV devices report display modes and
  > making hotplugging behavior more consistent. **App developers no longer need to use workarounds
  > for accurately detecting display modes** or for handling HDMI hotplug events."
  — [Android Developers Blog, 14 July 2021](https://android-developers.googleblog.com/2021/07/android-12-beta-3-for-tv-is-now.html)
  and "Certified API accuracy for reporting display modes, HDR formats, and surround sound formats"
  in the [Android 12 for TV release notes](https://developer.android.com/tv/release/12).
* The Fire TV table is the clearest statement that output resolution and render surface are
  **independent**: a 3840×2160-capable device set to 720p or 480p still renders apps to a
  **1920×1080** surface ([Amazon docs](https://developer.amazon.com/docs/fire-tv/display-and-layout.html)).

---

## CONFIDENCE / UNVERIFIED

### High confidence (primary AOSP source, fetched and read directly)

* `config_maxUiWidth` exists in `frameworks/base` (default `0`) and is overridden to `1920` by
  `device/google/atv/overlay/TvFrameworkOverlay`.
* The exact clamp arithmetic in `DisplayContent.updateBaseDisplayMetrics()` and the
  `mIsDensityForced` guard, in android10/11/12/13/14-release and main.
* **Android 12-release omits the density scaling; android10, 11, 13, 14 and main include it.** This
  is a verified source diff, and is a plausible cause of "tiny UI" on a clamped 4K panel running
  Android 12. **[The causal link to real-world bug reports is UNVERIFIED — I found no bug report.]**
* Version history: cap absent ATV 5.1.1–10.0.0; `1920` from ATV 11.0.0; `sdk_overlay` = `0` from ATV
  12.0.0; per-density overrides (1280/1920/3840) from **Android 15 QPR1**.
* `getRealSize`/`getRealMetrics` read `DisplayInfo.logicalWidth/Height` and therefore return the
  capped value.
* The display-projection upscale path (`layerStackSpaceRect` → `orientedDisplaySpaceRect`).
* MVPs: commit `27cec324` (2017), `92b6197` (2020-04-21), `6d0eee5` (2021-06-15), `6b0eaafd`
  (2024-06-26).
* Google's Android 12 "4K UI support" statement, and the Android 6.0 "UI continues to be rendered at
  the original resolution (such as 1080p) and is upscaled to 4K" statement.
* Amazon's Fire TV render-surface table (1920×1080 / 320 dpi at every output resolution).
* `DisplayCompat` source: `vendor.display-size` / `sys.display-size` workarounds, Sony BRAVIA
  special case, private 3840/2160 constants, and its class-level "Display.Mode may not report the
  accurate width and height" caveat.
* Deprecation split: `getSize`/`getMetrics` API 30; `getRealSize`/`getRealMetrics` API 31.

### UNVERIFIED / could not confirm

1. **`config_supports4kUi` — no such resource found** in `device/google/atv` (main + android-12),
   `frameworks/base/core/res` (android13), or `packages/apps/TvSettings` (android13), and no web
   hit. Treated as **non-existent**; the real resource is `config_maxUiWidth`.
2. **`DisplayCompat.isUhdCapable`, `MODE_1080P`, `MODE_4K` — no such symbols found.** The reference
   page has no constants section, `ModeCompat` has only four methods, and the androidx-main source
   contains none of them (only private `DISPLAY_SIZE_4K_WIDTH/HEIGHT`). Treated as **non-existent**.
3. **`config_defaultDensity` and `DisplayMetrics.DEFAULT_DENSITY` — no such symbols.** Neither
   appears in `frameworks/base/core/res`. The real path is
   `ro.sf.lcd_density` → `DENSITY_DEVICE_STABLE`, defaulting to `DENSITY_DEFAULT = 160`.
4. **No verbatim report of `getRealSize() == 3840` together with `densityDpi == 320` was found.**
   The closest real-device pairs are 1920×1080 @ 240 dpi (Amlogic) and 3840×2160 @ 240 dpi (after a
   4K UI mod). This exact combination is *derivable* from the source (cap inactive + `ro.sf.lcd_density=320`)
   but I found no field report.
5. **No vendor (Amlogic / MediaTek / MStar) device tree containing `config_maxUiWidth` was found.**
   GitHub code search requires authentication and grep.app / Sourcegraph were blocked from this
   environment — so this is **"not found by sampling", not proven absent.**
6. **`device/mediatek/wembley` is not public**, and no MT5895 / MT9652 / MT9602 device trees were
   found. MediaTek's Pentonic 800 page is a marketing page (English URL 404s).
7. **MStar evidence is ICS-era vendor notes and third-party TWRP trees only** (`TARGET_SCREEN_DENSITY`
   is a TWRP variable, not `ro.sf.lcd_density`).
8. **`ro.amlogic.*` and `persist.sys.lcd_density` display properties: none found** for any of the
   three vendors.
9. **Google Issue Tracker**: only issue **242757484** was verified; its status could not be decoded.
   Buganizer IDs 25820708 / 145791247 / 189182666 / 343252722 are cited only as they appear in
   verified AOSP commit messages — their bodies are not public. A genuine Issue Tracker search was
   impossible (search API rejects unauthenticated POSTs), so absence of an issue there means
   "not search-engine-indexed", not "does not exist".
10. **Reddit r/AndroidTV yielded nothing** (403 on every route); XDA direct fetches were partly 403.
11. **What makes a device "4K-UI compatible"** per Google's blog wording is not defined anywhere I
    could find.
12. **The Brian Lindahl commit quote** in SO 67998593 ("the original concept is flawed…") — the
    surrounding reasoning is attributed to a commit I could not locate verbatim.
13. **The Android 12 density-scaling omission as the cause of a real "tiny UI" bug** — source
    verified, real-world symptom not confirmed. I found **no bug report** matching it.
14. **`wm size` output on a retail, capped 4K Android TV** — inferred from `printInitialDisplaySize`
    → `getInitialDisplaySize` → `mDisplayInfo.logicalWidth`, and consistent with the AOSP commit's
    test line, but not observed on hardware here.
15. **Whether any shipping Amlogic / MediaTek / MStar 4K device actually enables the Android 15
    QPR1+ xxxhdpi → 3840 native-4K-UI path.**

---

## Practical guidance for an Android TV app developer

1. **Never use `getRealSize()` / `getRealMetrics()` to infer panel resolution.** On a stock capped
   ATV build they return 1920×1080 — the *same* as `getSize()`. Use
   `Display.Mode.getPhysicalWidth()/getPhysicalHeight()` (API 23+) or
   `DisplayCompat.getMode()/getSupportedModes()` for older devices.
2. **Use `WindowManager.getCurrentWindowMetrics().getBounds()` (API 30+) for UI sizing** and
   `Configuration.densityDpi` for density — that is what the platform's own deprecation notice
   recommends.
3. **Do not assume a fixed dp canvas.** Real TVs in the field yield 960×540 dp (1920 ui @ 320),
   1920×1080 dp (cap inactive @ 320), 2560×1440 dp (native 4K @ 240 — tiny UI), or 960×540 dp
   (native 4K @ 640). Always measure at runtime.
4. **If you need native-resolution pixels, use a `SurfaceView` with full-size buffers** — that is what
   both the Android 6.0 note and the `Display.Mode.getPhysicalWidth()` javadoc explicitly recommend;
   a normal View hierarchy is drawn at the (possibly capped) UI resolution.
5. **Diagnostics that reveal the real state:** `adb shell wm size` (prints the *logical* size),
   `adb shell wm density`, `adb shell dumpsys display`, and `adb shell dumpsys SurfaceFlinger` —
   look for `geomBufferSize=[0 0 1920 1080]` plus a `3840 2160` display frame (or `SCALE 2.0000`)
   to confirm a 1080p UI being upscaled.
