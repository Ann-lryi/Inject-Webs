package com.aho.streambrowser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/** Classic green "Matrix rain" backdrop behind the DevTools panel. */
class MatrixRainView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val glyphs = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉ0123456789ABCDEF<>/\\#*+".toCharArray()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1DB954"); alpha = 180
        textSize = 16f * resources.displayMetrics.density; isFakeBoldText = true
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A7F3D0"); alpha = 230
        textSize = 16f * resources.displayMetrics.density; isFakeBoldText = true
    }
    private var columns = 1
    private var drops = IntArray(0)
    private val colWidth get() = textPaint.textSize.toInt()
    private val rng = Random(System.currentTimeMillis())
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (running) { invalidate(); postOnAnimationDelayed(this, 70L) }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        columns = (w / colWidth).coerceAtLeast(1)
        drops = IntArray(columns) { rng.nextInt(0, (h / colWidth).coerceAtLeast(20)) }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.argb(40, 0, 0, 0))
        for (i in drops.indices) {
            val ch = glyphs[rng.nextInt(glyphs.size)]
            val x = i * colWidth.toFloat()
            val y = drops[i] * colWidth.toFloat()
            canvas.drawText(ch.toString(), x, y, if (rng.nextInt(15) == 0) headPaint else textPaint)
            if (y > height && rng.nextInt(10) > 7) drops[i] = 0
            drops[i]++
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postOnAnimation(tick) }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(tick); super.onDetachedFromWindow() }
}
