package com.firefly.tv.ui

/**
 * 起播失败：**先等一会儿，别急着说「无法播放」**。
 *
 * ## 用户实测的现象
 *
 * 「偶尔打开会显示这个视频无法播放，等一段时间正常」—— 已经查清：
 * NAS 的硬盘休眠了，第一次打开一集要等硬盘转起来。这段时间里 SMB 的读请求会超时
 * （`SmbConfig.withTimeout` 是 20 秒一刀），ijkplayer 把这个读失败报成一次播放错误（`onError`），
 * 界面层原来**立刻**就把「这个视频无法播放」弹出来 —— 而十几秒后它自己明明能播。
 *
 * 对一个给老人用的电视应用来说，这条错误信息是最糟的反馈：它会让用户直接放弃这一集。
 * 所以规则改成：**没出过画面之前，失败先当"还没加载好"**，自动重试几次，
 * 真的超过 [WINDOW_MS] 还不行，才把原因摆到屏幕上（见 [shouldRetry]）。
 *
 * ## 为什么"没出过画面"是必须的条件
 *
 * 已经在放的片子中途断了（SMB 掉线、解码器报错），用户需要立刻知道原因，
 * 而不是看着黑屏等一分钟。只有"从来没出过画面"这一次才算起播失败。
 *
 * 抽成纯函数是为了能用单元测试钉住：这里多等或少等一秒都不报错，
 * 只会表现成"电视有时好有时坏"，事后没法复盘。
 */
object StartupGrace {

    /**
     * 从这次起播开始，最多宽限多久（毫秒）。
     *
     * 取 40 秒：硬盘休眠的盘转起来一般 5~20 秒，而 SMB 的读超时是 20 秒一刀 ——
     * 也就是说这个窗口至少要容得下「超时一次 + 再试一次」。
     * 再长就不像话了：真打不开的片子（编码不支持之类）让老人对着黑屏等一分多钟，
     * 比看一句「这个视频无法播放」更糟。
     */
    const val WINDOW_MS = 40_000L

    /** 宽限期内最多自动重试几次（不含第一次失败）。 */
    const val MAX_ATTEMPTS = 3

    /**
     * 每次重试前等多久（毫秒）。越等越久：头一次可能只是 NAS 打个盹，
     * 后面就该把时间留给硬盘转起来，而不是高频敲它。
     */
    private val BACKOFF_MS = longArrayOf(3_000L, 8_000L, 15_000L)

    /**
     * 这次起播失败还要不要再试。
     *
     * @param rendered 这次起播**出过画面**没有（首帧）。出过就一律不再宽限
     * @param attempts 已经重试过几次（见 [MAX_ATTEMPTS]）
     * @param elapsedMs 从这次起播开始到现在过了多久（见 [WINDOW_MS]）
     */
    fun shouldRetry(rendered: Boolean, attempts: Int, elapsedMs: Long): Boolean =
        !rendered && attempts < MAX_ATTEMPTS && elapsedMs < WINDOW_MS

    /** 第 [attempt] 次重试（从 1 开始）之前等多久；越界时取最近的那一档。 */
    fun backoffMs(attempt: Int): Long =
        BACKOFF_MS[(attempt - 1).coerceIn(0, BACKOFF_MS.size - 1)]
}
