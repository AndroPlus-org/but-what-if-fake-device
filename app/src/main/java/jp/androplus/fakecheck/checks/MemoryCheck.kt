package jp.androplus.fakecheck.checks

import android.app.ActivityManager
import android.content.Context
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.R
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys
import kotlin.math.abs

object MemoryCheck {

    /** 市販端末で使われる RAM 容量(GB)。MemTotal はカーネル予約分だけ公称値より少なく出る。 */
    private val STANDARD_RAM_GB = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 18.0, 20.0, 24.0, 32.0)

    fun nominalRamGb(memTotalBytes: Long): Double {
        val gb = memTotalBytes / 1073741824.0
        return STANDARD_RAM_GB.firstOrNull { it >= gb } ?: Math.ceil(gb)
    }

    fun run(ctx: Context): Section {
        val items = ArrayList<Item>()
        val mi = Sys.meminfo()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val amInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val kernelTotal = (mi["MemTotal"] ?: 0L) * 1024
        val swapTotal = (mi["SwapTotal"] ?: 0L) * 1024
        val swapFree = (mi["SwapFree"] ?: 0L) * 1024
        val avail = (mi["MemAvailable"] ?: 0L) * 1024

        if (kernelTotal > 0) {
            val nominal = nominalRamGb(kernelTotal)
            items += Item(
                ctx.getString(R.string.mem_kernel_label),
                ctx.getString(R.string.mem_kernel_value, Sys.gb(kernelTotal), nominal.fmtGb()),
                Status.INFO,
                ctx.getString(R.string.mem_kernel_note)
            )
        } else {
            items += Item(ctx.getString(R.string.mem_kernel_label), ctx.getString(R.string.common_unreadable), Status.WARN)
        }

        items += Item(ctx.getString(R.string.mem_api_label), Sys.gb(amInfo.totalMem))
        if (kernelTotal > 0) {
            val diff = abs(amInfo.totalMem - kernelTotal).toDouble() / kernelTotal
            items += if (diff > 0.05) {
                Item(
                    ctx.getString(R.string.mem_match_label),
                    ctx.getString(R.string.mem_mismatch, Sys.f1(diff * 100)),
                    Status.NG,
                    ctx.getString(R.string.mem_mismatch_note)
                )
            } else {
                Item(ctx.getString(R.string.mem_match_label), ctx.getString(R.string.common_match), Status.OK)
            }
        }

        items += Item(ctx.getString(R.string.mem_avail_label), "${Sys.gb(avail)} (lowMemory=${amInfo.lowMemory})")

        if (swapTotal > 0) {
            val combined = nominalRamGb(kernelTotal) + Math.round(swapTotal / 1073741824.0)
            items += Item(
                ctx.getString(R.string.mem_swap_label),
                ctx.getString(R.string.mem_swap_value, Sys.gb(swapTotal), Sys.gb(swapTotal - swapFree)),
                Status.INFO,
                ctx.getString(R.string.mem_swap_note, combined.fmtGb(), nominalRamGb(kernelTotal).fmtGb())
            )
        } else {
            items += Item(ctx.getString(R.string.mem_swap_label), ctx.getString(R.string.common_none))
        }

        // ROM 改造で「設定」アプリに表示する容量を差し替えているケースがあるため、容量関連プロパティを列挙
        val suspiciousKeys = Regex("""(?i)(ram|rom|mem|storage|flash|emmc|ufs|disk).*(size|total|cap|fake)|fake.*(ram|rom|mem|storage)""")
        val props = Sys.props().filterKeys { suspiciousKeys.containsMatchIn(it) }
            .filterKeys { !it.contains("dalvik") && !it.contains("heap") }
        if (props.isNotEmpty()) {
            items += Item(
                ctx.getString(R.string.mem_props_label),
                props.entries.joinToString("\n") { "${it.key} = ${it.value}" },
                Status.INFO,
                ctx.getString(R.string.mem_props_note)
            )
        }

        items += Item(
            ctx.getString(R.string.mem_test_label),
            ctx.getString(R.string.mem_test_value),
            Status.INFO
        )
        return Section(ctx.getString(R.string.mem_section), items)
    }

    private fun Double.fmtGb(): String =
        if (this == Math.floor(this)) "${this.toInt()}GB" else "${this}GB"
}
