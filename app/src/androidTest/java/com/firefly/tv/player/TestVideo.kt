package com.firefly.tv.player

import android.content.Context
import java.io.File

/**
 * 把打包在 androidTest 资源里的示例视频复制到应用缓存目录。
 *
 * 仓库里不放二进制媒体：这个 mp4 由 scripts/make-test-media.ps1 用 ffmpeg 生成，
 * 生成后放在 app/src/androidTest/assets/test.mp4。
 */
object TestVideo {

    private const val ASSET = "test.mp4"

    /**
     * 资源打包在**测试** APK 里，所以必须用 instrumentation 自己的 context 读；
     * 用 targetContext 读的是被测应用，那里没有这个 asset。
     */
    fun ensure(context: Context): File = copyAsset(context, ASSET, ASSET)
}

/**
 * moov 在文件末尾的 mp4（没有 +faststart）。
 * 用来记录 ijkplayer 在 IMediaDataSource 通道下的已知限制。
 */
object TailMoovVideo {

    private const val ASSET = "test_tail_moov.mp4"
    private const val CACHE_NAME = "tail_moov.mp4"

    fun ensure(context: Context): File = copyAsset(context, ASSET, CACHE_NAME)
}

private fun copyAsset(context: Context, asset: String, cacheName: String): File {
    val out = File(context.cacheDir, cacheName)
    if (out.exists() && out.length() > 1000) return out
    val testAssets = androidx.test.platform.app.InstrumentationRegistry
        .getInstrumentation().context.assets
    testAssets.open(asset).use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    }
    return out
}
