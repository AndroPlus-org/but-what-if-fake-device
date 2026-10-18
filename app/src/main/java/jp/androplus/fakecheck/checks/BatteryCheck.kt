package jp.androplus.fakecheck.checks

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys

object BatteryCheck {

    private fun designCapacityMah(ctx: Context): Double? {
        // 端末メーカーが設定する電力プロファイルの値 (非公開APIのため取れない端末もある)
        try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val pp = cls.getConstructor(Context::class.java).newInstance(ctx)
            val v = cls.getMethod("getBatteryCapacity").invoke(pp) as Double
            if (v > 100) return v
        } catch (e: Throwable) {
            // ignore
        }
        return null
    }

    private fun sysfsMah(name: String): Double? {
        for (dir in listOf("battery", "bms", "Battery")) {
            val v = Sys.readLong("/sys/class/power_supply/$dir/$name") ?: continue
            // µAh 表記が一般的。mAh 表記の端末もある
            val mah = if (v > 100_000) v / 1000.0 else v.toDouble()
            if (mah > 100) return mah
        }
        return null
    }

    fun run(ctx: Context): Section {
        val items = ArrayList<Item>()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100.0 / s else null
        }
        val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
        val tech = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        items += Item(
            "残量",
            (level?.let { "${Sys.f1(it)}%" } ?: "不明") + (temp?.let { " / ${Sys.f1(it / 10.0)}℃" } ?: "") + (tech?.let { " / $it" } ?: "")
        )

        val design = designCapacityMah(ctx)
        val sysDesign = sysfsMah("charge_full_design")
        val sysFull = sysfsMah("charge_full")
        items += Item("設計容量 (電力プロファイル)", design?.let { "${it.toInt()} mAh" } ?: "取得不可")
        if (sysDesign != null) items += Item("設計容量 (カーネル)", "${sysDesign.toInt()} mAh")
        if (sysFull != null) items += Item("満充電容量 (カーネル, 劣化反映)", "${sysFull.toInt()} mAh")

        // 現在の残電荷量と残量%から満充電時の容量を推定
        var estimate: Double? = null
        val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (counter > 0 && counter != Long.MIN_VALUE && level != null && level >= 20) {
            val mah = counter / 1000.0
            estimate = mah / (level / 100.0)
            items += Item(
                "推定満充電容量 (残電荷から計算)",
                "${estimate.toInt()} mAh (現在 ${mah.toInt()} mAh)",
                Status.INFO,
                "残量が多いほど精度が上がります。バッテリー残量計の精度に依存する目安です。"
            )
        } else {
            items += Item("推定満充電容量 (残電荷から計算)", if (level != null && level < 20) "残量20%以上で計算できます" else "この端末は残電荷量を報告しません")
        }

        val claimed = design ?: sysDesign
        val measured = sysFull ?: estimate
        if (claimed != null && measured != null) {
            val ratio = measured / claimed
            items += Item(
                "設計容量と実容量の比",
                "${Sys.f1(ratio * 100)}%",
                when {
                    ratio < 0.5 -> Status.NG
                    ratio < 0.75 -> Status.WARN
                    else -> Status.OK
                },
                if (ratio < 0.75) "実際に蓄えられる容量が設計値を大きく下回ります。容量の水増し表記か、バッテリーの劣化が考えられます。" else null
            )
        }
        items += Item(
            "見方",
            "仕様表のバッテリー容量 (mAh) と比べてください。",
            Status.INFO,
            "設計容量の値はメーカーが自由に設定できるため、推定満充電容量やカーネル値の方が実態に近い値です。"
        )
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ はサイクル数も取れる場合がある
            val cycles = sticky?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
            if (cycles >= 0) items += Item("充放電サイクル数", cycles.toString())
        }
        return Section("バッテリー", items)
    }
}
