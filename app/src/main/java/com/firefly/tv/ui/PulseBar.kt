package com.firefly.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 一条左右移动的细进度条：只表示“还在动”，不表示百分比。
 *
 * NAS 硬盘唤醒没有可读的进度，写一个假百分比只会误导；所以开机页和换库过渡页
 * 都用这条移动色块，具体在等什么由上面的状态句说明。只在页面可见时由调用方推进
 * [phase]，自己不持定时器。
 */
class PulseBar(context: Context) : View(context) {

    var phase = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
    private val segment = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, track)

        val segW = w * 0.28f
        val x = (w - segW) * phase.coerceIn(0f, 1f)
        segment.shader = LinearGradient(
            x, 0f, x + segW, 0f,
            intArrayOf(0x00FFD54F, 0xFFFFD54F.toInt(), 0xFFFFD54F.toInt(), 0x00FFD54F),
            floatArrayOf(0f, 0.25f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(RectF(x, 0f, x + segW, h), r, r, segment)
    }

    private companion object {
        const val TRACK = 0x24FFFFFF
    }
}
