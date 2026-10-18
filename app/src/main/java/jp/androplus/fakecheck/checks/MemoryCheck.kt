package jp.androplus.fakecheck.checks

import android.app.ActivityManager
import android.content.Context
import jp.androplus.fakecheck.Item
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
                "物理RAM (カーネル /proc/meminfo)",
                "${Sys.gb(kernelTotal)}  → 公称 ${nominal.fmtGb()} 相当",
                Status.INFO,
                "MemTotal は公称値より数%少なく表示されるのが普通です。"
            )
        } else {
            items += Item("物理RAM (カーネル /proc/meminfo)", "読み取り不可", Status.WARN)
        }

        items += Item("物理RAM (Android API)", Sys.gb(amInfo.totalMem))
        if (kernelTotal > 0) {
            val diff = abs(amInfo.totalMem - kernelTotal).toDouble() / kernelTotal
            items += if (diff > 0.05) {
                Item(
                    "カーネル値とAPI値の一致",
                    "不一致 (${Sys.f1(diff * 100)}% 差)",
                    Status.NG,
                    "Android フレームワークが返す RAM 容量がカーネルの値と食い違っています。フレームワーク改造による容量水増しの典型的な痕跡です。"
                )
            } else {
                Item("カーネル値とAPI値の一致", "一致", Status.OK)
            }
        }

        items += Item("現在の空きRAM", "${Sys.gb(avail)} (lowMemory=${amInfo.lowMemory})")

        if (swapTotal > 0) {
            val combined = nominalRamGb(kernelTotal) + Math.round(swapTotal / 1073741824.0)
            items += Item(
                "仮想RAM / RAM拡張 (スワップ)",
                "${Sys.gb(swapTotal)} (使用中 ${Sys.gb(swapTotal - swapFree)})",
                Status.WARN,
                "ストレージや圧縮メモリ(zram)を使った仮想メモリで、物理RAMではありません。" +
                    "「RAM ${combined.fmtGb()}」のように物理+仮想の合計で宣伝されている場合、実際の物理RAMは ${nominalRamGb(kernelTotal).fmtGb()} です。"
            )
        } else {
            items += Item("仮想RAM / RAM拡張 (スワップ)", "なし")
        }

        // ROM 改造で「設定」アプリに表示する容量を差し替えているケースがあるため、容量関連プロパティを列挙
        val suspiciousKeys = Regex("""(?i)(ram|rom|mem|storage|flash|emmc|ufs|disk).*(size|total|cap|fake)|fake.*(ram|rom|mem|storage)""")
        val props = Sys.props().filterKeys { suspiciousKeys.containsMatchIn(it) }
            .filterKeys { !it.contains("dalvik") && !it.contains("heap") }
        if (props.isNotEmpty()) {
            items += Item(
                "容量関連のシステムプロパティ",
                props.entries.joinToString("\n") { "${it.key} = ${it.value}" },
                Status.INFO,
                "設定アプリの表示容量がここの値から作られている場合があります。物理値と大きく違う値が無いか確認してください。"
            )
        }

        items += Item(
            "実測テスト",
            "下の「RAM実測テスト」で実際にメモリを確保して確認できます",
            Status.INFO
        )
        return Section("メモリ (RAM)", items)
    }

    private fun Double.fmtGb(): String =
        if (this == Math.floor(this)) "${this.toInt()}GB" else "${this}GB"
}
