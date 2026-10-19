package jp.androplus.fakecheck

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * 目視確認用の全画面テストパターン。タップで次へ、最後のページ(マルチタッチ)で戻るボタンかダブルタップで終了。
 */
class DisplayTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(PatternView(this))
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private class PatternView(context: Context) : View(context) {

        private data class Page(val title: Int, val hint: Int)

        private val pages = listOf(
            Page(R.string.dt_vstripe1, R.string.dt_vstripe1_hint),
            Page(R.string.dt_hstripe1, R.string.dt_hstripe1_hint),
            Page(R.string.dt_checker, R.string.dt_checker_hint),
            Page(R.string.dt_vstripe2, R.string.dt_vstripe2_hint),
            Page(R.string.dt_gray, R.string.dt_gray_hint),
            Page(R.string.dt_rgb, R.string.dt_rgb_hint),
            Page(R.string.dt_white, R.string.dt_white_hint),
            Page(R.string.dt_black, R.string.dt_black_hint),
            Page(R.string.dt_red, R.string.dt_dead_hint),
            Page(R.string.dt_green, R.string.dt_dead_hint),
            Page(R.string.dt_blue, R.string.dt_dead_hint),
            Page(R.string.dt_touch, R.string.dt_touch_hint),
        )
        private var page = 0
        private val paint = Paint()
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics) }
        private val textBg = Paint().apply { color = 0xB0000000.toInt() }
        private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E5FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f }
        private var showLabel = true
        private var maxPointers = 0
        private val pointers = HashMap<Int, Pair<Float, Float>>()

        private fun shaderOf(w: Int, h: Int, pixels: IntArray): BitmapShader {
            val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        private val B = Color.BLACK
        private val W = Color.WHITE
        private val vStripe = shaderOf(2, 1, intArrayOf(W, B))
        private val hStripe = shaderOf(1, 2, intArrayOf(W, B))
        private val checker = shaderOf(2, 2, intArrayOf(W, B, B, W))
        private val vStripe2 = shaderOf(4, 1, intArrayOf(W, W, B, B))

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.shader = null
            // ビットマップの拡大補間を無効化して 1px をそのまま描く
            paint.isFilterBitmap = false
            paint.isAntiAlias = false
            when (page) {
                0 -> { paint.shader = vStripe; canvas.drawRect(0f, 0f, w, h, paint) }
                1 -> { paint.shader = hStripe; canvas.drawRect(0f, 0f, w, h, paint) }
                2 -> { paint.shader = checker; canvas.drawRect(0f, 0f, w, h, paint) }
                3 -> { paint.shader = vStripe2; canvas.drawRect(0f, 0f, w, h, paint) }
                4 -> {
                    paint.shader = LinearGradient(0f, 0f, w, 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, w, h, paint)
                }
                5 -> {
                    val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
                    val band = h / colors.size
                    colors.forEachIndexed { i, c ->
                        paint.shader = LinearGradient(0f, 0f, w, 0f, Color.BLACK, c, Shader.TileMode.CLAMP)
                        canvas.drawRect(0f, i * band, w, (i + 1) * band, paint)
                    }
                }
                6 -> canvas.drawColor(Color.WHITE)
                7 -> canvas.drawColor(Color.BLACK)
                8 -> canvas.drawColor(Color.RED)
                9 -> canvas.drawColor(Color.GREEN)
                10 -> canvas.drawColor(Color.BLUE)
                11 -> {
                    canvas.drawColor(0xFF101418.toInt())
                    val r = 40f * resources.displayMetrics.density
                    pointers.values.forEach { (x, y) -> canvas.drawCircle(x, y, r, touchPaint) }
                    val big = Paint(text).apply { textSize = text.textSize * 3 }
                    canvas.drawText(context.getString(R.string.dt_touch_count, pointers.size.toString(), maxPointers.toString()), 40f, h / 2, big)
                }
            }
            if (showLabel || page == 11) drawLabel(canvas)
        }

        private fun drawLabel(canvas: Canvas) {
            val p = pages[page]
            val dm = resources.displayMetrics
            var panel = ""
            if (Build.VERSION.SDK_INT >= 23) {
                @Suppress("DEPRECATION")
                val mode = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.mode
                panel = "  " + context.getString(R.string.dt_panel, "${max(mode.physicalWidth, mode.physicalHeight)}×${min(mode.physicalWidth, mode.physicalHeight)}")
            }
            val lines = listOf(
                context.getString(
                    R.string.dt_header, (page + 1).toString(), pages.size.toString(), context.getString(p.title),
                    "${width}×${height}", panel, dm.densityDpi.toString()
                ),
                context.getString(p.hint),
                context.getString(R.string.dt_controls),
            )
            val lh = text.textSize * 1.4f
            val maxW = lines.maxOf { text.measureText(it) }
            canvas.drawRect(0f, 0f, min(width.toFloat(), maxW + 32f), lh * lines.size + 16f, textBg)
            lines.forEachIndexed { i, s -> canvas.drawText(s, 16f, lh * (i + 1), text) }
        }

        private var downTime = 0L
        private var downX = 0f
        private var downY = 0f
        private var multi = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (page == 11) {
                pointers.clear()
                if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                    for (i in 0 until e.pointerCount) {
                        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                        pointers[e.getPointerId(i)] = e.getX(i) to e.getY(i)
                    }
                }
                maxPointers = max(maxPointers, pointers.size)
                invalidate()
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = e.eventTime; downX = e.x; downY = e.y; multi = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(e.x - downX) + kotlin.math.abs(e.y - downY) > 50
                    if (!multi && !moved) {
                        if (e.eventTime - downTime > 500) showLabel = !showLabel
                        else page = (page + 1) % pages.size
                        invalidate()
                    }
                }
            }
            return true
        }
    }
}
