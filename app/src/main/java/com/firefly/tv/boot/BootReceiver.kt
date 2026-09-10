package com.firefly.tv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.firefly.tv.ui.MainActivity

/**
 * 开机自启兜底（DESIGN §9 风险 1）。
 * 长虹「虹领金系统」可能拦截 CATEGORY_HOME 抢主屏，所以自启和默认桌面双保险。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(launch) }
    }
}
