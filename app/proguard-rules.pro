# ijkplayer 内部大量使用 JNI 回调，保留其全部成员
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep interface tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.media.player.**

# smbj / bcprov / mbassy 用到反射与可选依赖。
#
# ⚠️ 两处是开 R8 之后才暴露出来的老毛病（debug 包不压缩，所以一直没有症状）：
#  1) 包名写错了一个字母：是 `net.engio.mbassy`（smbj 用它做事件总线），
#     原来写成 `net.engio.mbassador` —— keep 规则等于没写，
#     正式包里这些类会被改名/裁剪，症状会是「装上能开、一连 NAS 就崩」。
#  2) `javax.el.**` 与 `org.ietf.jgss.**` 是 Java SE 专有的可选依赖，Android 上不存在，
#     必须 dontwarn，否则 R8 直接以「Missing class」失败。
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn net.engio.mbassy.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }

# zxing
-keep class com.google.zxing.** { *; }

# 正式包里去掉 v/d/i 三档日志：这台电视 IO 慢，起播路径上每帧写日志是白给的抖动。
# 保留 w/e —— 真出事时要能从 logcat 里看见。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
