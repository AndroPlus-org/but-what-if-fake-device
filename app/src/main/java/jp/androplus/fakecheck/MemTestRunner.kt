package jp.androplus.fakecheck

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import jp.androplus.fakecheck.checks.MemoryCheck
import java.io.File
import kotlin.math.max

/**
 * MemTestService を起動し、/proc/meminfo を見ながら「物理RAMに実際に載せられた量」を測る。
 * 圧縮メモリ(zram)はRAM内にあるので物理RAMとして扱う。テストデータは乱数で圧縮が効かない。
 * ストレージ上のスワップ(RAM拡張の一部方式)に押し出され始めたら、物理RAMは埋まったとみなして終了する。
 */
class MemTestRunner(
    private val ctx: Context,
    private val onProgress: (String, Int) -> Unit,
    private val onDone: (Section) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val progressFile = File(ctx.filesDir, MemTestService.PROGRESS_FILE)
    private var bound = false
    private var running = false

    private var total = 0L
    private var avail0 = 0L
    private var diskSwap0 = 0L
    private var swapTotal = 0L
    private var peakAlloc = 0L
    private var peakSwapDelta = 0L
    private var minAvail = Long.MAX_VALUE
    private var startedAt = 0L

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {
            // プロセスがメモリ不足で強制終了された
            if (running) finish("KILLED")
        }
    }

    val isRunning get() = running

    fun start() {
        val mi = Sys.meminfo()
        total = (mi["MemTotal"] ?: 0) * 1024
        avail0 = (mi["MemAvailable"] ?: mi["MemFree"] ?: 0) * 1024
        swapTotal = (mi["SwapTotal"] ?: 0) * 1024
        diskSwap0 = Sys.diskSwapUsedBytes()
        peakAlloc = 0; peakSwapDelta = 0; minAvail = Long.MAX_VALUE
        progressFile.delete()

        val intent = Intent(ctx, MemTestService::class.java)
            .putExtra(MemTestService.EXTRA_LIMIT, total + swapTotal)
        ctx.startService(intent)
        // 優先度を保ちつつ、死んだときに通知を受けるためにバインドもする (自動再生成はさせない)
        bound = ctx.bindService(intent, conn, 0)
        running = true
        startedAt = SystemClock.elapsedRealtime()
        handler.post(poll)
    }

    fun stop() {
        if (running) finish("USER")
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val txt = try { progressFile.readText() } catch (e: Exception) { "" }
            val alloc = txt.substringBefore(' ').toLongOrNull() ?: 0L
            val state = txt.substringAfter(' ', "")
            peakAlloc = max(peakAlloc, alloc)

            val mi = Sys.meminfo()
            val avail = (mi["MemAvailable"] ?: 0) * 1024
            minAvail = minOf(minAvail, avail)
            peakSwapDelta = max(peakSwapDelta, Sys.diskSwapUsedBytes() - diskSwap0)

            onProgress(
                ctx.getString(R.string.memt_progress, Sys.gb(peakAlloc), Sys.gb(avail)) +
                    if (peakSwapDelta > 0) " / " + ctx.getString(R.string.memt_progress_swap, Sys.mb(peakSwapDelta)) else "",
                if (total > 0) (peakAlloc * 100 / total).toInt() else 0
            )

            when {
                state.startsWith("ERROR") -> finish(state)
                state == "LIMIT" -> finish("LIMIT")
                // 物理RAMが埋まり、ストレージ上のスワップに押し出され始めた
                peakSwapDelta > 512L * 1048576 -> finish("SWAP")
                avail in 1 until 64L * 1048576 && swapTotal == 0L -> finish("LOWMEM")
                SystemClock.elapsedRealtime() - startedAt > 10 * 60_000 -> finish("TIMEOUT")
                else -> handler.postDelayed(this, 250)
            }
        }
    }

    private fun finish(reason: String) {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        val txt = try { progressFile.readText() } catch (e: Exception) { "" }
        peakAlloc = max(peakAlloc, txt.substringBefore(' ').toLongOrNull() ?: 0L)
        if (bound) {
            try { ctx.unbindService(conn) } catch (e: Exception) {}
            bound = false
        }
        ctx.stopService(Intent(ctx, MemTestService::class.java))
        onDone(result(reason))
    }

    private fun result(reason: String): Section {
        val items = ArrayList<Item>()
        val swapDelta = max(0L, peakSwapDelta)
        val resident = max(0L, peakAlloc - swapDelta)
        val othersAtStart = max(0L, total - avail0)
        val estimate = resident + othersAtStart
        val ratio = if (total > 0) estimate.toDouble() / total else 0.0

        val reasonText = when {
            reason == "KILLED" -> ctx.getString(R.string.memt_reason_killed)
            reason == "SWAP" -> ctx.getString(R.string.memt_reason_swap)
            reason == "LOWMEM" -> ctx.getString(R.string.memt_reason_lowmem)
            reason == "LIMIT" -> ctx.getString(R.string.memt_reason_limit)
            reason == "USER" -> ctx.getString(R.string.memt_reason_user)
            reason == "TIMEOUT" -> ctx.getString(R.string.memt_reason_timeout)
            else -> ctx.getString(R.string.memt_reason_error, reason.removePrefix("ERROR "))
        }
        items += Item(ctx.getString(R.string.memt_reason_label), reasonText)
        items += Item(ctx.getString(R.string.memt_start_label), ctx.getString(R.string.memt_start_value, Sys.gb(total), Sys.gb(avail0), Sys.gb(othersAtStart)))
        items += Item(
            ctx.getString(R.string.memt_alloc_label),
            Sys.gb(peakAlloc) + if (swapDelta > 0) " " + ctx.getString(R.string.memt_alloc_swap, Sys.mb(swapDelta)) else ""
        )
        items += Item(
            ctx.getString(R.string.memt_est_label),
            ctx.getString(R.string.memt_est_value, Sys.gb(estimate), Sys.f1(ratio * 100)),
            when {
                reason == "USER" || reason == "TIMEOUT" -> Status.INFO
                ratio >= 0.7 -> Status.OK
                ratio >= 0.5 -> Status.WARN
                else -> Status.NG
            },
            when {
                reason == "USER" || reason == "TIMEOUT" -> ctx.getString(R.string.memt_note_incomplete)
                ratio >= 0.7 -> ctx.getString(R.string.memt_note_ok, MemoryCheck.nominalRamGb(total).toString())
                ratio >= 0.5 -> ctx.getString(R.string.memt_note_warn)
                else -> ctx.getString(R.string.memt_note_ng)
            }
        )
        return Section(ctx.getString(R.string.memt_section), items)
    }
}
