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
        // 高いレートが低解像度モードにしか無い端末があるので、今の解像度で選べる最大値と比べる
        val claimed = if (Build.VERSION.SDK_INT >= 23) {
            val cur = d.mode
            d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxOfOrNull { it.refreshRate }?.toDouble() ?: cur.refreshRate.toDouble()
        } else d.refreshRate.toDouble()
        val ratio = medianFps / claimed
        val items = listOf(
            Item(activity.getString(R.string.rr_claimed_label), "${Sys.f1(claimed)} Hz"),
            Item(
                activity.getString(R.string.rr_median_label),
                "${Sys.f1(medianFps)} fps (${Sys.f2(medianMs)} ms)",
                when {
                    ratio >= 0.9 -> Status.OK
                    ratio >= 0.6 -> Status.WARN
                    else -> Status.NG
                },
                activity.getString(
                    when {
                        ratio >= 0.9 -> R.string.rr_ok_note
                        else -> R.string.rr_low_note
                    }
                )
            ),
            Item(activity.getString(R.string.rr_avg_label), activity.getString(R.string.rr_avg_value, Sys.f1(avgFps), frames.size.toString())),
        )
        onDone(Section(activity.getString(R.string.rr_section), items))
    }
}
