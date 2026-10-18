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
 * スワップ(仮想RAM)が使われ始めた時点で物理RAMは埋まったとみなして終了する。
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
    private var swapFree0 = 0L
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
        swapFree0 = (mi["SwapFree"] ?: 0) * 1024
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
            val swapFree = (mi["SwapFree"] ?: 0) * 1024
            minAvail = minOf(minAvail, avail)
            peakSwapDelta = max(peakSwapDelta, swapFree0 - swapFree)

            onProgress(
                "RAM実測中… 確保 ${Sys.gb(peakAlloc)} / 空き ${Sys.gb(avail)} / スワップ使用増 ${Sys.mb(max(0, peakSwapDelta))}",
                if (total > 0) (peakAlloc * 100 / total).toInt() else 0
            )

            when {
                state.startsWith("ERROR") -> finish(state)
                state == "LIMIT" -> finish("LIMIT")
                // 物理RAMが埋まり、スワップに押し出され始めた
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
            reason == "KILLED" -> "メモリ不足でテストプロセスが強制終了 (正常な終わり方)"
            reason == "SWAP" -> "物理RAMが埋まりスワップが使われ始めた"
            reason == "LOWMEM" -> "空きRAMがほぼゼロになった"
            reason == "LIMIT" -> "上限まで確保できた"
            reason == "USER" -> "ユーザーが中断"
            reason == "TIMEOUT" -> "時間切れ"
            else -> "確保エラー: ${reason.removePrefix("ERROR ")}"
        }
        items += Item("終了理由", reasonText)
        items += Item("テスト開始時", "MemTotal ${Sys.gb(total)} / 空き ${Sys.gb(avail0)} / 使用中 ${Sys.gb(othersAtStart)}")
        items += Item("確保して書き込めた量", "${Sys.gb(peakAlloc)} (うちスワップ行き ${Sys.mb(swapDelta)})")
        items += Item(
            "推定物理RAM",
            "${Sys.gb(estimate)} (MemTotal の ${Sys.f1(ratio * 100)}%)",
            when {
                reason == "USER" || reason == "TIMEOUT" -> Status.INFO
                ratio >= 0.7 -> Status.OK
                ratio >= 0.5 -> Status.WARN
                else -> Status.NG
            },
            when {
                reason == "USER" || reason == "TIMEOUT" -> "テストが最後まで完了していないため判定しません。"
                ratio >= 0.7 -> "申告どおりの物理RAMが実際に使えています (公称 ${MemoryCheck.nominalRamGb(total)}GB 相当)。"
                ratio >= 0.5 -> "申告より少ない量しか確保できませんでした。他アプリが多く動いていると低めに出るので、再起動直後に再テストしてください。"
                else -> "申告されている RAM 容量の半分も実際には使えません。RAM 容量が偽装されている疑いがあります。"
            }
        )
        return Section("RAM実測テスト結果", items)
    }
}
