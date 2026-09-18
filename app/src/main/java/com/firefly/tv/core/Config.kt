package com.firefly.tv.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部配置与续播状态都存 SharedPreferences（避开 Room/DataStore 的兼容风险）。
 * 配置页保存成功后 configured = true，此后不再自动开配置服务。
 */
object Config {

    private const val FILE = "firefly"

    private const val KEY_HOST = "smb_host"
    private const val KEY_SHARE = "smb_share"
    private const val KEY_ROOT = "smb_root"
    private const val KEY_USER = "smb_user"
    private const val KEY_PASS = "smb_pass"
    private const val KEY_DOMAIN = "smb_domain"

    private const val KEY_W_KEY = "w_key"
    private const val KEY_W_HOST = "w_host"          // 旧版和风专属 Host，只用于清理
    private const val KEY_W_LOCATION = "w_location"
    private const val KEY_W_CACHE = "w_cache"
    private const val KEY_W_CACHE_AT = "w_cache_at"

    private const val KEY_CONFIGURED = "configured"

    // 观看记录（每部剧各一条，见 WatchHistory）。
    // 旧版那份「只有一条全局记忆」的四个键保留着，只为了升级时迁移一次。
    private const val KEY_HISTORY = "watch_history"
    private const val KEY_LAST_LIB = "last_lib"
    private const val KEY_LAST_SHOW = "last_show"
    private const val KEY_LAST_EP = "last_ep"
    private const val KEY_LAST_POS = "last_pos"
    private const val KEY_LAST_CH = "last_ch"

    private const val KEY_CACHE = "scan_cache"
    private const val KEY_CACHE_KEY = "scan_cache_key"
    private const val KEY_CACHE_EPOCH = "scan_cache_epoch"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 一次读取全部 SMB 配置，避免多次跨进程取 SharedPreferences。 */
    data class Smb(
        val host: String,
        val share: String,
        val root: String,
        val user: String,
        val pass: String,
        val domain: String,
    ) {
        val ready: Boolean get() = host.isNotBlank() && share.isNotBlank()

        /** 配置建议填 IP 避免 DNS；根目录统一去掉首尾斜杠。 */
        fun normalized(): Smb = Smb(host.trim(), share.trim().trim('/'), root.trim().trim('/'), user, pass, domain)
    }

    fun smb(ctx: Context): Smb {
        val p = sp(ctx)
        return Smb(
            p.getString(KEY_HOST, "").orEmpty(),
            p.getString(KEY_SHARE, "").orEmpty(),
            p.getString(KEY_ROOT, "").orEmpty(),
            p.getString(KEY_USER, "").orEmpty(),
            p.getString(KEY_PASS, "").orEmpty(),
            p.getString(KEY_DOMAIN, "").orEmpty(),
        )
    }

    fun saveSmb(ctx: Context, smb: Smb) {
        val n = smb.normalized()
        sp(ctx).edit()
            .putString(KEY_HOST, n.host)
            .putString(KEY_SHARE, n.share)
            .putString(KEY_ROOT, n.root)
            .putString(KEY_USER, n.user)
            .putString(KEY_PASS, n.pass)
            .putString(KEY_DOMAIN, n.domain)
            .apply()
    }

    /**
     * 心知天气（seniverse）只需要两样：**私钥**和**地点**。
     *
     * 原来还要一个「账号专属 Host」（和风 2026 起的规定），换到心知之后
     * 域名是固定的 `api.seniverse.com`，那一栏就删掉了 —— 少一栏就少一个填错的机会。
     *
     * 地点**不留默认值**：写死过某个具体坐标既泄露隐私，也等于替用户决定看哪儿的天气。
     * 要么私钥和地点都填，要么都留空（留空 = 电视上不显示天气）。
     * 实测地名有时会回 `AP010006 没有权限访问这个地点`，这时换成「纬度:经度」通常就能查到。
     */
    data class Weather(val key: String, val location: String) {
        val ready: Boolean get() = key.isNotBlank() && location.isNotBlank()

        fun normalized(): Weather = Weather(key.trim(), location.trim())
    }

    fun weather(ctx: Context): Weather {
        val p = sp(ctx)
        return Weather(
            p.getString(KEY_W_KEY, "").orEmpty(),
            p.getString(KEY_W_LOCATION, "").orEmpty(),
        )
    }

    fun saveWeather(ctx: Context, w: Weather) {
        val n = w.normalized()
        sp(ctx).edit()
            .putString(KEY_W_KEY, n.key)
            .putString(KEY_W_LOCATION, n.location)
            // 和风那栏留着没用，顺手清掉，免得以后有人以为它还在生效
            .remove(KEY_W_HOST)
            .apply()
    }

    /** 天气结果的落盘缓存：重启电视不该再打一次接口（见 WeatherClient 的频率说明）。 */
    fun weatherCache(ctx: Context): Pair<Long, String>? {
        val p = sp(ctx)
        val at = p.getLong(KEY_W_CACHE_AT, 0L)
        val text = p.getString(KEY_W_CACHE, null) ?: return null
        return if (at <= 0L || text.isBlank()) null else at to text
    }

    fun saveWeatherCache(ctx: Context, at: Long, text: String) {
        sp(ctx).edit().putString(KEY_W_CACHE, text).putLong(KEY_W_CACHE_AT, at).apply()
    }

    fun configured(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_CONFIGURED, false)

    fun setConfigured(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_CONFIGURED, v).apply()
    }

    // ---- 观看记录 ----
    //
    // 存成一个整体（`WatchHistory.serialize` 的文本），而不是一堆散键：
    //  - 写一次是原子的，老人直接拔电源也不会只剩半条记录；
    //  - 解析逻辑能单独测（见 WatchHistoryTest）。
    //
    // 5 秒写一次，整个文件才十几 KB，代价可以忽略。

    fun watchHistoryText(ctx: Context): String? = sp(ctx).getString(KEY_HISTORY, null)

    fun saveWatchHistoryText(ctx: Context, text: String) {
        sp(ctx).edit().putString(KEY_HISTORY, text).apply()
    }

    /**
     * 旧版那条「只有一份」的续播记忆，读过一次就清掉（升级时迁移用）。
     *
     * @return 库/剧/集/进度；没存过就返回 null
     */
    fun takeLegacySpot(ctx: Context): LegacySpot? {
        val p = sp(ctx)
        val lib = p.getString(KEY_LAST_LIB, "").orEmpty()
        val show = p.getString(KEY_LAST_SHOW, "").orEmpty()
        val ep = p.getInt(KEY_LAST_EP, 0)
        val pos = p.getLong(KEY_LAST_POS, 0L)
        p.edit()
            .remove(KEY_LAST_LIB).remove(KEY_LAST_SHOW)
            .remove(KEY_LAST_EP).remove(KEY_LAST_POS)
            .apply()
        return if (show.isBlank()) null else LegacySpot(lib, show, ep, pos)
    }

    class LegacySpot(val lib: String, val show: String, val index: Int, val posMs: Long)

    fun channel(ctx: Context): Int = sp(ctx).getInt(KEY_LAST_CH, 0)

    fun saveChannel(ctx: Context, ch: Int) {
        sp(ctx).edit().putInt(KEY_LAST_CH, ch).apply()
    }

    // ---- NAS 目录结构缓存 ----
    //
    // 缓存归属：换了 NAS / 共享 / 根目录 / 账号，旧缓存一律作废（否则会拿 A 的剧名去 B 上找）。
    // 这里不存密码本身，只存它的哈希，够用来判断「是不是同一套凭据」。
    // epoch 在配置页保存成功时 +1，用来强制丢弃（用户换过 NAS 内容时也会走这条路）。

    fun cacheKey(ctx: Context): String {
        val s = smb(ctx).normalized()
        val dup = "$s\u0000${s.pass.hashCode()}"
        return "$KEY_CACHE_KEY:${dup.hashCode()}:${sp(ctx).getInt(KEY_CACHE_EPOCH, 0)}"
    }

    /** 配置变更后调用：下一次 [cacheKey] 一定和旧的不同。 */
    fun invalidateCache(ctx: Context) {
        val p = sp(ctx)
        p.edit()
            .putInt(KEY_CACHE_EPOCH, p.getInt(KEY_CACHE_EPOCH, 0) + 1)
            .remove(KEY_CACHE)
            .apply()
    }

    /** 读缓存。key 不匹配（换了 NAS）就当没有。 */
    fun loadCache(ctx: Context): String? {
        val p = sp(ctx)
        if (p.getString(KEY_CACHE_KEY, null) != cacheKey(ctx)) return null
        return p.getString(KEY_CACHE, null)
    }

    fun saveCache(ctx: Context, text: String) {
        sp(ctx).edit()
            .putString(KEY_CACHE_KEY, cacheKey(ctx))
            .putString(KEY_CACHE, text)
            .apply()
    }
}
