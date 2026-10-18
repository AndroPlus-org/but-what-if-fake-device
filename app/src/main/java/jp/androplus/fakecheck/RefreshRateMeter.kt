package jp.androplus.fakecheck

import android.app.Activity
import android.os.Build
import android.view.Choreographer
import android.view.View
import jp.androplus.fakecheck.checks.DisplayCheck

/**
 * Choreographer のフレームコールバック間隔から実際の描画 fps を測る。
 * 可変リフレッシュレート端末は描画が止まると Hz を下げるので、測定中は毎フレーム invalidate する。
 */
class RefreshRateMeter(
    private val activity: Activity,
    private val animView: View,
    private val onDone: (Section) -> Unit,
) {
    private val frames = ArrayList<Long>()
    private var running = false
    private var oldModeId = 0
    private var oldRefresh = 0f

    fun start() {
        if (running) return
        running = true
        frames.clear()
        val lp = activity.window.attributes
        oldModeId = if (Build.VERSION.SDK_INT >= 23) lp.preferredDisplayModeId else 0
        oldRefresh = lp.preferredRefreshRate
        val d = DisplayCheck.display(activity)
        if (Build.VERSION.SDK_INT >= 23) {
            // 現在の解像度のまま、最もリフレッシュレートの高いモードを要求
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: cur
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
        } else {
            lp.preferredRefreshRate = d.refreshRate
        }
        activity.window.attributes = lp
        // モード切替を待ってから測定開始
        animView.postDelayed({ Choreographer.getInstance().postFrameCallback(cb) }, 800)
    }

    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            frames += frameTimeNanos
            animView.invalidate()
            if (frames.size > 1 && frames.last() - frames.first() >= 3_000_000_000L) finish()
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun finish() {
        running = false
        val lp = activity.window.attributes
        if (Build.VERSION.SDK_INT >= 23) lp.preferredDisplayModeId = oldModeId
        lp.preferredRefreshRate = oldRefresh
        activity.window.attributes = lp

        val intervals = frames.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
        val avgFps = (frames.size - 1) * 1e9 / (frames.last() - frames.first())
        // 取りこぼしフレームの影響を除くため中央値の間隔も見る
        val medianMs = intervals[intervals.size / 2]
        val medianFps = 1000.0 / medianMs
        val d = DisplayCheck.display(activity)
        val claimed = if (Build.VERSION.SDK_INT >= 23) d.supportedModes.maxOf { it.refreshRate }.toDouble() else d.refreshRate.toDouble()
        val ratio = medianFps / claimed
        val items = listOf(
            Item("申告最大リフレッシュレート", "${Sys.f1(claimed)} Hz"),
            Item(
                "実測 (フレーム間隔の中央値)",
                "${Sys.f1(medianFps)} fps (${Sys.f2(medianMs)} ms)",
                when {
                    ratio >= 0.9 -> Status.OK
                    ratio >= 0.7 -> Status.WARN
                    else -> Status.NG
                },
                when {
                    ratio >= 0.9 -> "申告どおりのリフレッシュレートで描画されています。"
                    else -> "申告より低いレートでしか描画されていません。省電力モードや設定の「滑らかな表示」を確認のうえ再測定してください。" +
                        "それでも低い場合、高リフレッシュレート表記が実態と異なる可能性があります。"
                }
            ),
            Item("実測 (平均)", "${Sys.f1(avgFps)} fps (${frames.size} フレーム / 3秒)"),
        )
        onDone(Section("リフレッシュレート実測結果", items))
    }
}
