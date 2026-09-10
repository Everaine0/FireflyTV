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
    private const val KEY_W_HOST = "w_host"
    private const val KEY_W_LOCATION = "w_location"

    private const val KEY_CONFIGURED = "configured"

    private const val KEY_LAST_LIB = "last_lib"
    private const val KEY_LAST_SHOW = "last_show"
    private const val KEY_LAST_EP = "last_ep"
    private const val KEY_LAST_POS = "last_pos"
    private const val KEY_LAST_CH = "last_ch"

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

    data class Weather(val key: String, val host: String, val location: String) {
        val ready: Boolean get() = key.isNotBlank() && host.isNotBlank() && location.isNotBlank()

        /** 和风自 2026 起每账号专属 Host，用户可能连 https:// 一起粘进来。 */
        fun normalized(): Weather = Weather(
            key.trim(),
            host.trim().removePrefix("https://").removePrefix("http://").trim('/'),
            location.trim(),
        )
    }

    fun weather(ctx: Context): Weather {
        val p = sp(ctx)
        return Weather(
            p.getString(KEY_W_KEY, "").orEmpty(),
            p.getString(KEY_W_HOST, "").orEmpty(),
            p.getString(KEY_W_LOCATION, "").orEmpty(),
        )
    }

    fun saveWeather(ctx: Context, w: Weather) {
        val n = w.normalized()
        sp(ctx).edit()
            .putString(KEY_W_KEY, n.key)
            .putString(KEY_W_HOST, n.host)
            .putString(KEY_W_LOCATION, n.location)
            .apply()
    }

    fun configured(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_CONFIGURED, false)

    fun setConfigured(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_CONFIGURED, v).apply()
    }

    /** 续播位置：库 + 剧 + 集序号 + 精确进度。 */
    class Spot(val lib: String, val show: String, val index: Int, val posMs: Long)

    fun spot(ctx: Context): Spot {
        val p = sp(ctx)
        return Spot(
            p.getString(KEY_LAST_LIB, "").orEmpty(),
            p.getString(KEY_LAST_SHOW, "").orEmpty(),
            p.getInt(KEY_LAST_EP, 0),
            p.getLong(KEY_LAST_POS, 0L),
        )
    }

    fun saveSpot(ctx: Context, lib: String, show: String, index: Int, posMs: Long) {
        sp(ctx).edit()
            .putString(KEY_LAST_LIB, lib)
            .putString(KEY_LAST_SHOW, show)
            .putInt(KEY_LAST_EP, index)
            .putLong(KEY_LAST_POS, posMs)
            .apply()
    }

    /** 5 秒防抖写盘时只更新进度，不动库/剧/集。 */
    fun savePosition(ctx: Context, posMs: Long) {
        sp(ctx).edit().putLong(KEY_LAST_POS, posMs).apply()
    }

    fun channel(ctx: Context): Int = sp(ctx).getInt(KEY_LAST_CH, 0)

    fun saveChannel(ctx: Context, ch: Int) {
        sp(ctx).edit().putInt(KEY_LAST_CH, ch).apply()
    }
}
