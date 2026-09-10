# ijkplayer 内部大量使用 JNI 回调，保留其全部成员
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep interface tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.media.player.**

# smbj / bcprov 用到反射与可选依赖
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn net.engio.mbassador.**
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassador.** { *; }
-keep class org.bouncycastle.** { *; }

# zxing
-keep class com.google.zxing.** { *; }
