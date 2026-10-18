package jp.androplus.fakecheck

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import jp.androplus.fakecheck.checks.BatteryCheck
import jp.androplus.fakecheck.checks.CameraCheck
import jp.androplus.fakecheck.checks.DisplayCheck
import jp.androplus.fakecheck.checks.FeatureCheck
import jp.androplus.fakecheck.checks.MemoryCheck
import jp.androplus.fakecheck.checks.OsCheck
import jp.androplus.fakecheck.checks.SocCheck
import jp.androplus.fakecheck.checks.StorageCheck
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var summary: TextView
    private lateinit var progressBox: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var stopButton: Button
    private lateinit var spinner: SpinnerView

    /** ライト / ダークで切り替える配色 */
    private class Palette(dark: Boolean) {
        val background = if (dark) 0xFF121212.toInt() else 0xFFF3F5F7.toInt()
        val card = if (dark) 0xFF1E2327.toInt() else Color.WHITE
        val cardStroke = if (dark) 0xFF2F363B.toInt() else 0xFFDDE1E5.toInt()
        val progressCard = if (dark) 0xFF3A3220.toInt() else 0xFFFFF8E1.toInt()
        val title = if (dark) 0xFFECEFF1.toInt() else 0xFF212121.toInt()
        val sectionTitle = if (dark) 0xFFECEFF1.toInt() else 0xFF263238.toInt()
        val label = if (dark) 0xFFB0BEC5.toInt() else 0xFF37474F.toInt()
        val value = if (dark) 0xFFF5F5F5.toInt() else 0xFF111111.toInt()
        val note = if (dark) 0xFF90A4AE.toInt() else 0xFF78909C.toInt()
        val accent = if (dark) 0xFF4DB6AC.toInt() else 0xFF00897B.toInt()
        private val isDark = dark
        fun statusText(s: Status) = if (isDark) s.darkText else s.color
    }

    private lateinit var pal: Palette

    private var staticSections: List<Section> = emptyList()
    private val testSections = LinkedHashMap<String, Section>()

    private var memTest: MemTestRunner? = null
    private var storageTest: StorageTest? = null
    private var stopAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        pal = Palette(night == Configuration.UI_MODE_NIGHT_YES)
        setContentView(buildLayout())
        runStaticChecks()
    }

    override fun onDestroy() {
        memTest?.stop()
        storageTest?.stop()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildLayout(): View {
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(pal.background)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "${Build.MANUFACTURER} ${Build.MODEL}"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.title)
        })
        summary = TextView(this).apply {
            text = "チェック中…"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(summary)

        val buttons = FlowLayout(this, dp(6))
        fun btn(label: String, onClick: () -> Unit) = buttons.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        })
        btn("RAM実測テスト") { confirmMemTest() }
        btn("ストレージ実書き込みテスト") { chooseStorageTarget() }
        btn("リフレッシュレート実測") { measureRefresh() }
        btn("ディスプレイ表示テスト") { startActivity(Intent(this, DisplayTestActivity::class.java)) }
        btn("結果を共有") { share() }
        btn("再チェック") { runStaticChecks() }
        root.addView(buttons)

        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg(pal.progressCard)
        }
        progressText = TextView(this).apply { textSize = 14f; setTextColor(pal.value) }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        spinner = SpinnerView(this, pal.accent)
        stopButton = Button(this).apply {
            text = "中止"
            setOnClickListener { stopAction?.invoke() }
        }
        progressBox.addView(progressText)
        progressBox.addView(progressBar)
        progressBox.addView(spinner, LinearLayout.LayoutParams(dp(32), dp(32)))
        progressBox.addView(stopButton)
        root.addView(progressBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        return scroll
    }

    private fun cardBg(color: Int = pal.card) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), pal.cardStroke)
    }

    private fun badge(s: Status) = TextView(this).apply {
        text = s.label
        textSize = 12f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply { setColor(s.color); cornerRadius = dp(10).toFloat() }
    }

    private fun card(sec: Section): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBg()
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = sec.title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.sectionTitle)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(badge(sec.worst()))
        box.addView(head)

        for (item in sec.items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(this).apply {
                text = item.label
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(pal.label)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (item.status != Status.INFO) top.addView(badge(item.status))
            row.addView(top)
            row.addView(TextView(this).apply {
                text = item.value
                textSize = 15f
                setTextColor(pal.value)
                setTextIsSelectable(true)
            })
            item.note?.let {
                row.addView(TextView(this).apply {
                    text = it
                    textSize = 12f
                    setTextColor(if (item.status == Status.NG || item.status == Status.WARN) pal.statusText(item.status) else pal.note)
                })
            }
            box.addView(row)
        }
        return box
    }

    private fun render() {
        content.removeAllViews()
        val all = testSections.values.toList() + staticSections
        for (s in all) {
            content.addView(card(s), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val ng = all.sumOf { s -> s.items.count { it.status == Status.NG } }
        val warn = all.sumOf { s -> s.items.count { it.status == Status.WARN } }
        summary.text = when {
            staticSections.isEmpty() -> "チェック中…"
            ng > 0 -> "偽装の疑いがある項目: $ng 件 / 注意: $warn 件"
            warn > 0 -> "偽装の疑いは見つかりませんでした (注意: $warn 件)"
            else -> "偽装の疑いは見つかりませんでした"
        }
        summary.setTextColor(pal.statusText(if (ng > 0) Status.NG else if (warn > 0) Status.WARN else Status.OK))
    }

    private fun showProgress(msg: String, pct: Int, indeterminate: Boolean = false, stop: (() -> Unit)?) {
        progressBox.visibility = View.VISIBLE
        progressText.text = msg
        progressBar.isIndeterminate = indeterminate
        progressBar.progress = pct
        stopAction = stop
        stopButton.visibility = if (stop != null) View.VISIBLE else View.GONE
        spinner.visibility = View.GONE
    }

    private fun hideProgress() {
        progressBox.visibility = View.GONE
        stopAction = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun busy(): Boolean {
        if (memTest?.isRunning == true || storageTest?.isRunning == true || progressBox.visibility == View.VISIBLE) {
            Toast.makeText(this, "別のテストを実行中です", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- 静的チェック

    private fun runStaticChecks() {
        staticSections = emptyList()
        render()
        val app = applicationContext
        thread(name = "checks") {
            val list = listOf<() -> Section>(
                { MemoryCheck.run(app) },
                { StorageCheck.run(app) },
                { DisplayCheck.run(this) },
                { SocCheck.run() },
                { OsCheck.run() },
                { CameraCheck.run(app) },
                { BatteryCheck.run(app) },
                { FeatureCheck.run(app) },
            ).map { f ->
                try {
                    f()
                } catch (t: Throwable) {
                    Section("エラー", listOf(Item("チェック失敗", t.toString(), Status.WARN)))
                }
            }
            handler.post {
                staticSections = list
                render()
            }
        }
    }

    // ---------------------------------------------------------------- RAM

    private fun confirmMemTest() {
        if (busy()) return
        AlertDialog.Builder(this)
            .setTitle("RAM実測テスト")
            .setMessage(
                "別プロセスで物理メモリをいっぱいまで確保して、実際に使えるRAM量を測ります。\n\n" +
                    "・他のアプリは強制終了されることがあります\n" +
                    "・テスト中は端末が一時的に重くなります\n" +
                    "・再起動直後に実行すると精度が上がります"
            )
            .setPositiveButton("開始") { _, _ -> startMemTest() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun startMemTest() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val runner = MemTestRunner(
            this,
            onProgress = { msg, pct -> showProgress(msg, pct, stop = { memTest?.stop() }) },
            onDone = { sec ->
                hideProgress()
                testSections[sec.title] = sec
                render()
            }
        )
        memTest = runner
        showProgress("RAM実測テストを開始…", 0, stop = { runner.stop() })
        runner.start()
    }

    // ---------------------------------------------------------------- ストレージ

    private fun chooseStorageTarget() {
        if (busy()) return
        val test = StorageTest(
            this,
            onProgress = { msg, pct -> showProgress(msg, pct, stop = { storageTest?.stop() }) },
            onDone = { sec ->
                hideProgress()
                testSections[sec.title] = sec
                render()
            }
        )
        val max = test.maxTarget()
        if (max < 64L shl 20) {
            Toast.makeText(this, "空き容量が足りません", Toast.LENGTH_LONG).show()
            return
        }
        val options = listOf(
            "空き容量すべて ${Sys.gb(max)} (推奨・時間がかかります)" to max,
            "空き容量の半分 ${Sys.gb(max / 2)}" to max / 2,
            "クイック 4GB" to minOf(max, 4L shl 30),
        )
        AlertDialog.Builder(this)
            .setTitle("書き込む量")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                AlertDialog.Builder(this)
                    .setTitle("ストレージ実書き込みテスト")
                    .setMessage(
                        "${Sys.gb(options[which].second)} のテストデータを書き込み、すべて読み戻して照合します。\n\n" +
                            "・既存のデータは消えません (このアプリ専用フォルダに書き、終了後に削除)\n" +
                            "・容量偽装を確実に見つけるには「空き容量すべて」で実行してください\n" +
                            "・中止すると、そこまでに書いた分だけ照合します"
                    )
                    .setPositiveButton("開始") { _, _ ->
                        storageTest = test
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        showProgress("ストレージテストを開始…", 0, stop = { test.stop() })
                        test.start(options[which].second)
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            .show()
    }

    // ---------------------------------------------------------------- リフレッシュレート

    private fun measureRefresh() {
        if (busy()) return
        showProgress("リフレッシュレートを測定中… (約4秒、画面に触れないでください)", 0, indeterminate = true, stop = null)
        spinner.visibility = View.VISIBLE
        RefreshRateMeter(this, spinner) { sec ->
            hideProgress()
            testSections[sec.title] = sec
            render()
        }.start()
    }

    // ---------------------------------------------------------------- 共有

    private fun share() {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val text = buildString {
            append("偽装スペックチェッカー レポート ($date)\n")
            append("${Build.MANUFACTURER} ${Build.MODEL}\n")
            append(summary.text).append("\n\n")
            (testSections.values + staticSections).forEach { append(it.toText()).append('\n') }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "スペックチェック: ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "結果を共有"))
    }

    /** リフレッシュレート測定中に毎フレーム描き直される回転インジケーター */
    class SpinnerView(context: Context, color: Int) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 6f }
        override fun onDraw(canvas: Canvas) {
            val a = (SystemClock.uptimeMillis() % 1000) / 1000.0 * 2 * Math.PI
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - 4
            canvas.drawLine(cx, cy, cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat(), p)
        }
    }

    /** ボタンを横に並べて折り返す簡易レイアウト */
    class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxW = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + c.measuredWidth > maxW) {
                    x = 0; y += rowH + gap; rowH = 0
                }
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
            setMeasuredDimension(maxW, y + rowH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > maxW) {
                    x = 0; y += rowH + gap; rowH = 0
                }
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
        }
    }
}
