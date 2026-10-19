package jp.androplus.fakecheck.checks

import android.content.Context
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.R
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.ShizukuShell
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shizuku (shell 権限) でしか読めない情報を使った追加チェック。
 * ShizukuShell に接続済みの状態で、メインスレッド以外から呼ぶ。
 */
object ShizukuCheck {

    fun run(ctx: Context): Section {
        val sh = ShizukuShell
        val items = ArrayList<Item>()

        val uid = sh.uid()
        items += Item(
            ctx.getString(R.string.shz_uid_label),
            ctx.getString(R.string.shz_uid_value, if (uid == 0) "root" else "shell", uid)
        )

        // ---- ストレージ: アプリからは読めない /sys/block を shell 権限で読む
        val devs = StorageCheck.blockDevices({ sh.read(it) }, { dir -> sh.exec("ls $dir")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } })
        if (devs.isNotEmpty()) {
            val advertised = runCatching { StorageCheck.advertisedBytes(ctx) }.getOrDefault(0L)
            items += StorageCheck.physicalItems(ctx, advertised, devs)
        } else {
            items += Item(ctx.getString(R.string.sto_dev_label), ctx.getString(R.string.shz_unreadable))
        }

        // ---- RAM拡張: zram のストレージ書き出し (writeback)
        val backing = (0..3).mapNotNull { i ->
            sh.read("/sys/block/zram$i/backing_dev")?.trim()?.takeIf { it.isNotEmpty() && it != "none" }
        }
        val wb = sh.zramWritebackBytes()
        items += when {
            backing.isNotEmpty() -> Item(
                ctx.getString(R.string.shz_zram_label),
                ctx.getString(R.string.shz_zram_value, backing.joinToString(), Sys.mb(wb)),
                Status.INFO,
                ctx.getString(R.string.shz_zram_note)
            )
            sh.read("/sys/block/zram0/disksize") != null -> Item(ctx.getString(R.string.shz_zram_label), ctx.getString(R.string.common_none))
            else -> Item(ctx.getString(R.string.shz_zram_label), ctx.getString(R.string.shz_unreadable))
        }

        // ---- RAM: システムサービスが認識している総RAM
        val kernelTotal = (Sys.meminfo()["MemTotal"] ?: 0L) * 1024
        val dumpTotal = sh.exec("dumpsys meminfo | grep -m1 'Total RAM'")
            ?.let { Regex("""Total RAM:\s*([\d,]+)K""").find(it)?.groupValues?.get(1) }
            ?.replace(",", "")?.toLongOrNull()?.times(1024)
        if (dumpTotal != null && kernelTotal > 0) {
            val diff = abs(dumpTotal - kernelTotal).toDouble() / kernelTotal
            items += if (diff > 0.05) {
                Item(
                    ctx.getString(R.string.shz_meminfo_label),
                    ctx.getString(R.string.shz_meminfo_bad, Sys.gb(dumpTotal), Sys.gb(kernelTotal)),
                    Status.NG,
                    ctx.getString(R.string.shz_meminfo_bad_note)
                )
            } else {
                Item(ctx.getString(R.string.shz_meminfo_label), "${Sys.gb(dumpTotal)} (${ctx.getString(R.string.common_match)})", Status.OK)
            }
        }

        // ---- ディスプレイ: ウィンドウマネージャ / ディスプレイサービスの物理解像度
        val wm = sh.exec("wm size")
        val wmPhys = wm?.let { Regex("""Physical size:\s*(\d+)x(\d+)""").find(it) }
            ?.let { val a = it.groupValues[1].toInt(); val b = it.groupValues[2].toInt(); max(a, b) to min(a, b) }
        if (wmPhys != null) {
            @Suppress("DEPRECATION")
            val mode = DisplayCheck.display(ctx).mode
            val api = max(mode.physicalWidth, mode.physicalHeight) to min(mode.physicalWidth, mode.physicalHeight)
            items += if (wmPhys == api) {
                Item(ctx.getString(R.string.shz_wm_label), "${wm.trim()}\n(${ctx.getString(R.string.common_match)})", Status.OK)
            } else {
                Item(
                    ctx.getString(R.string.shz_wm_label),
                    ctx.getString(R.string.shz_wm_mismatch, "${wmPhys.first}×${wmPhys.second}", "${api.first}×${api.second}"),
                    Status.WARN,
                    ctx.getString(R.string.shz_wm_mismatch_note)
                )
            }
        }
        val dispDevs = sh.exec("dumpsys display | grep 'DisplayDeviceInfo{'")
            ?.lineSequence()
            ?.mapNotNull { Regex("""DisplayDeviceInfo\{"([^"]*)".*?(\d+) x (\d+)""").find(it) }
            ?.map { "${it.groupValues[1]}: ${it.groupValues[2]}×${it.groupValues[3]}" }
            ?.distinct()?.toList().orEmpty()
        if (dispDevs.isNotEmpty()) {
            items += Item(ctx.getString(R.string.shz_dispdev_label), dispDevs.joinToString("\n"))
        }

        // ---- バッテリー: カーネルの設計容量と満充電容量
        fun mah(name: String): Double? {
            for (dir in listOf("battery", "bms", "Battery")) {
                val v = sh.read("/sys/class/power_supply/$dir/$name")?.trim()?.toLongOrNull() ?: continue
                val m = if (v > 100_000) v / 1000.0 else v.toDouble()
                if (m > 100) return m
            }
            return null
        }
        val design = mah("charge_full_design")
        val full = mah("charge_full")
        val cycles = listOf("battery", "bms").firstNotNullOfOrNull { sh.read("/sys/class/power_supply/$it/cycle_count")?.trim() }
        if (design != null || full != null) {
            items += Item(
                ctx.getString(R.string.shz_batt_label),
                ctx.getString(
                    R.string.shz_batt_value,
                    design?.toInt()?.toString() ?: "-",
                    full?.toInt()?.toString() ?: "-",
                    cycles ?: "-"
                )
            )
        }
        if (design != null && full != null) {
            val ratio = full / design
            items += Item(
                ctx.getString(R.string.shz_batt_ratio_label),
                "${Sys.f1(ratio * 100)}%",
                if (ratio < 0.7) Status.WARN else Status.OK,
                if (ratio < 0.7) ctx.getString(R.string.shz_batt_ratio_note) else null
            )
        }
        sh.exec("dumpsys batterystats | grep -m1 -E 'Capacity: [0-9]+'")
            ?.let { Regex("""Capacity:\s*(\d+)""").find(it)?.groupValues?.get(1) }
            ?.let { items += Item(ctx.getString(R.string.shz_bstats_label), "$it mAh") }

        // ---- CPU: アプリから読めない端末もある最大クロック
        val freqs = sh.exec("for c in /sys/devices/system/cpu/cpu[0-9]*; do echo \${c##*/} \$(cat \$c/cpufreq/cpuinfo_max_freq 2>/dev/null); done")
            ?.lineSequence()
            ?.mapNotNull { line ->
                val p = line.trim().split(Regex("\\s+"))
                val khz = p.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                "${p[0]}: ${Sys.f2(khz / 1e6)} GHz"
            }?.toList().orEmpty()
        if (freqs.isNotEmpty()) {
            items += Item(ctx.getString(R.string.shz_cpufreq_label), freqs.joinToString("\n"))
        }

        return Section(ctx.getString(R.string.shz_section), items)
    }
}
