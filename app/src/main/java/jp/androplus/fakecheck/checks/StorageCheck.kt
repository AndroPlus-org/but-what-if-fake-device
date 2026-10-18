package jp.androplus.fakecheck.checks

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import jp.androplus.fakecheck.Item
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
            "データ領域 (/data, statfs)",
            "合計 ${Sys.gb(dataTotal)} / 空き ${Sys.gb(dataFree)}",
            Status.INFO,
            "ユーザーが使える領域。OS領域を含まないので公称容量より小さくなります。"
        )

        var advertised = 0L
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val ssm = ctx.getSystemService(StorageStatsManager::class.java)!!
                advertised = ssm.getTotalBytes(StorageManager.UUID_DEFAULT)
                items += Item(
                    "内部ストレージ容量 (設定アプリ表示値)",
                    "${Sys.gbDec(advertised)} = ${Sys.gb(advertised)}",
                    Status.INFO,
                    "Android が「設定」に表示する公称容量 (2の累乗に丸めた値)。"
                )
            } catch (e: Exception) {
                items += Item("内部ストレージ容量 (設定アプリ表示値)", "取得失敗: ${e.javaClass.simpleName}", Status.WARN)
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
                "公称容量とデータ領域の比率",
                "${Sys.f2(ratio)} 倍",
                st,
                "通常は 1.1〜2.0 倍程度 (OS領域ぶんの差)。極端に大きい場合、表示容量だけが水増しされている疑いがあります。"
            )
        }

        val devs = blockDevices()
        if (devs.isNotEmpty()) {
            // UFS は複数の LU(sda, sdb...) に分かれるので合計で比較する
            val diskTotal = devs.sumOf { it.bytes }
            items += Item(
                "ストレージ物理デバイス (カーネル)",
                devs.joinToString("\n") { "${it.name}: ${Sys.gbDec(it.bytes)}${if (it.detail.isNotEmpty()) "  ${it.detail}" else ""}" },
                Status.INFO
            )
            if (advertised > 0) {
                val r = advertised.toDouble() / diskTotal
                items += if (r > 1.3) {
                    Item(
                        "公称容量と物理デバイス容量",
                        "公称 ${Sys.gbDec(advertised)} > 物理 ${Sys.gbDec(diskTotal)}",
                        Status.NG,
                        "物理デバイスより大きい容量が表示されています。容量偽装の可能性が高いです。"
                    )
                } else {
                    Item("公称容量と物理デバイス容量", "矛盾なし", Status.OK)
                }
            }
        } else {
            items += Item(
                "ストレージ物理デバイス (カーネル)",
                "読み取り不可 (新しいAndroidでは制限されています)",
                Status.INFO
            )
        }

        items += Item(
            "実書き込みテスト",
            "下の「ストレージ実書き込みテスト」で、空き容量に実際にデータを書いて読み戻して確認できます。偽装容量は書き込んだデータが壊れることで検出できます。",
            Status.INFO
        )
        return Section("ストレージ", items)
    }

    private fun blockDevices(): List<BlockDev> {
        val result = ArrayList<BlockDev>()
        val names = File("/sys/block").list()?.toList()
            ?: listOf("mmcblk0", "sda", "sdb", "sdc", "nvme0n1")
        for (n in names) {
            if (n.startsWith("loop") || n.startsWith("ram") || n.startsWith("zram") || n.startsWith("dm-") ||
                n.startsWith("mmcblk0boot") || n.endsWith("rpmb")
            ) continue
            // removable=1 は SD カード / USB なので除外
            if (Sys.read("/sys/block/$n/removable") == "1") continue
            val sectors = Sys.readLong("/sys/block/$n/size") ?: continue
            val bytes = sectors * 512
            if (bytes < 1_000_000_000L) continue
            val detail = listOfNotNull(
                Sys.read("/sys/block/$n/device/type"),
                Sys.read("/sys/block/$n/device/vendor"),
                Sys.read("/sys/block/$n/device/model"),
                Sys.read("/sys/block/$n/device/name"),
            ).joinToString(" ").trim()
            result += BlockDev(n, bytes, detail)
        }
        if (result.isEmpty()) {
            // /proc/partitions (1KiB ブロック単位) からディスク全体を探す
            Sys.read("/proc/partitions")?.lineSequence()?.forEach { line ->
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
