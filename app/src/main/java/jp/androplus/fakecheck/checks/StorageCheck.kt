package jp.androplus.fakecheck.checks

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.R
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys
import java.io.File

object StorageCheck {

    data class BlockDev(val name: String, val bytes: Long, val detail: String)

    fun run(ctx: Context): Section {
        val items = ArrayList<Item>()

        val data = StatFs(Environment.getDataDirectory().path)
        val dataTotal = data.blockCountLong * data.blockSizeLong
        val dataFree = data.availableBlocksLong * data.blockSizeLong
        items += Item(
            ctx.getString(R.string.sto_data_label),
            ctx.getString(R.string.sto_data_value, Sys.gb(dataTotal), Sys.gb(dataFree)),
            Status.INFO,
            ctx.getString(R.string.sto_data_note)
        )

        var advertised = 0L
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                advertised = advertisedBytes(ctx)
                items += Item(
                    ctx.getString(R.string.sto_adv_label),
                    "${Sys.gbDec(ctx, advertised)} = ${Sys.gb(advertised)}",
                    Status.INFO,
                    ctx.getString(R.string.sto_adv_note)
                )
            } catch (e: Exception) {
                items += Item(ctx.getString(R.string.sto_adv_label), ctx.getString(R.string.common_failed, e.javaClass.simpleName), Status.WARN)
            }
        }

        if (advertised > 0 && dataTotal > 0) {
            val ratio = advertised.toDouble() / dataTotal
            val st = when {
                ratio > 3.0 -> Status.NG
                ratio > 2.2 -> Status.WARN
                else -> Status.OK
            }
            items += Item(
                ctx.getString(R.string.sto_ratio_label),
                ctx.getString(R.string.sto_ratio_value, Sys.f2(ratio)),
                st,
                ctx.getString(R.string.sto_ratio_note)
            )
        }

        val devs = blockDevices({ Sys.read(it) }, { File(it).list()?.toList() })
        if (devs.isNotEmpty()) {
            items += physicalItems(ctx, advertised, devs)
        } else {
            items += Item(
                ctx.getString(R.string.sto_dev_label),
                ctx.getString(R.string.sto_dev_unreadable),
                Status.INFO
            )
        }

        items += Item(
            ctx.getString(R.string.sto_write_label),
            ctx.getString(R.string.sto_write_value),
            Status.INFO
        )
        return Section(ctx.getString(R.string.sto_section), items)
    }

    /** 設定アプリに表示される公称容量 (API 26+) */
    fun advertisedBytes(ctx: Context): Long =
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(StorageStatsManager::class.java)!!.getTotalBytes(StorageManager.UUID_DEFAULT)
        else 0L

    /** 物理デバイス一覧と、公称容量との比較結果 */
    fun physicalItems(ctx: Context, advertised: Long, devs: List<BlockDev>): List<Item> {
        val items = ArrayList<Item>()
        // UFS は複数の LU(sda, sdb...) に分かれるので合計で比較する
        val diskTotal = devs.sumOf { it.bytes }
        items += Item(
            ctx.getString(R.string.sto_dev_label),
            devs.joinToString("\n") { "${it.name}: ${Sys.gbDec(ctx, it.bytes)}${if (it.detail.isNotEmpty()) "  ${it.detail}" else ""}" },
            Status.INFO
        )
        if (advertised > 0) {
            val r = advertised.toDouble() / diskTotal
            items += if (r > 1.3) {
                Item(
                    ctx.getString(R.string.sto_phys_label),
                    ctx.getString(R.string.sto_phys_bad, Sys.gbDec(ctx, advertised), Sys.gbDec(ctx, diskTotal)),
                    Status.NG,
                    ctx.getString(R.string.sto_phys_bad_note)
                )
            } else {
                Item(ctx.getString(R.string.sto_phys_label), ctx.getString(R.string.common_consistent), Status.OK)
            }
        }
        return items
    }

    /**
     * カーネルが認識しているディスク。[read] / [list] を差し替えると Shizuku (shell 権限) 経由でも読める。
     */
    fun blockDevices(read: (String) -> String?, list: (String) -> List<String>?): List<BlockDev> {
        val result = ArrayList<BlockDev>()
        val names = list("/sys/block")
            ?: listOf("mmcblk0", "sda", "sdb", "sdc", "nvme0n1")
        for (n in names) {
            if (n.startsWith("loop") || n.startsWith("ram") || n.startsWith("zram") || n.startsWith("dm-") ||
                n.startsWith("mmcblk0boot") || n.endsWith("rpmb")
            ) continue
            // removable=1 は SD カード / USB なので除外
            if (read("/sys/block/$n/removable")?.trim() == "1") continue
            val sectors = read("/sys/block/$n/size")?.trim()?.toLongOrNull() ?: continue
            val bytes = sectors * 512
            if (bytes < 1_000_000_000L) continue
            val detail = listOfNotNull(
                read("/sys/block/$n/device/type"),
                read("/sys/block/$n/device/vendor"),
                read("/sys/block/$n/device/model"),
                read("/sys/block/$n/device/name"),
            ).joinToString(" ") { it.trim() }.trim()
            result += BlockDev(n, bytes, detail)
        }
        if (result.isEmpty()) {
            // /proc/partitions (1KiB ブロック単位) からディスク全体を探す
            read("/proc/partitions")?.lineSequence()?.forEach { line ->
                val c = line.trim().split(Regex("\\s+"))
                if (c.size == 4 && c[2].toLongOrNull() != null) {
                    val n = c[3]
                    if (Regex("^(mmcblk\\d+|sd[a-z]|nvme\\d+n\\d+)$").matches(n)) {
                        val bytes = c[2].toLong() * 1024
                        if (bytes >= 1_000_000_000L) result += BlockDev(n, bytes, "")
                    }
                }
            }
        }
        return result
    }
}
