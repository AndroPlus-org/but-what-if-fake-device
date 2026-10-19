package jp.androplus.fakecheck.checks

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.R
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
            ctx.getString(R.string.bat_level_label),
            (level?.let { "${Sys.f1(it)}%" } ?: ctx.getString(R.string.common_unknown)) + (temp?.let { " / ${Sys.f1(it / 10.0)}℃" } ?: "") + (tech?.let { " / $it" } ?: "")
        )

        val design = designCapacityMah(ctx)
        val sysDesign = sysfsMah("charge_full_design")
        val sysFull = sysfsMah("charge_full")
        items += Item(ctx.getString(R.string.bat_design_label), design?.let { "${it.toInt()} mAh" } ?: ctx.getString(R.string.common_unavailable))
        if (sysDesign != null) items += Item(ctx.getString(R.string.bat_design_kernel_label), "${sysDesign.toInt()} mAh")
        if (sysFull != null) items += Item(ctx.getString(R.string.bat_full_label), "${sysFull.toInt()} mAh")

        // 現在の残電荷量と残量%から満充電時の容量を推定
        var estimate: Double? = null
        val counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val est = if (counter > 0 && counter != Long.MIN_VALUE && level != null && level >= 20) {
            counter / 1000.0 / (level / 100.0)
        } else null
        // 単位を誤って報告する端末があるので、ありえない値は使わない
        if (est != null && est in 500.0..50_000.0) {
            val mah = counter / 1000.0
            estimate = est
            items += Item(
                ctx.getString(R.string.bat_est_label),
                ctx.getString(R.string.bat_est_value, estimate.toInt().toString(), mah.toInt().toString()),
                Status.INFO,
                ctx.getString(R.string.bat_est_note)
            )
        } else {
            items += Item(
                ctx.getString(R.string.bat_est_label),
                ctx.getString(
                    when {
                        level != null && level < 20 -> R.string.bat_est_low
                        est != null -> R.string.bat_est_bad
                        else -> R.string.bat_est_none
                    }
                )
            )
        }

        val claimed = design ?: sysDesign
        val measured = sysFull ?: estimate
        if (claimed != null && measured != null) {
            val ratio = measured / claimed
            items += Item(
                ctx.getString(R.string.bat_ratio_label),
                "${Sys.f1(ratio * 100)}%",
                when {
                    ratio < 0.35 -> Status.NG
                    ratio < 0.7 -> Status.WARN
                    else -> Status.OK
                },
                if (ratio < 0.7) ctx.getString(R.string.bat_ratio_note) else null
            )
        }
        items += Item(
            ctx.getString(R.string.common_how_to_read),
            ctx.getString(R.string.bat_howto_value),
            Status.INFO,
            ctx.getString(R.string.bat_howto_note)
        )
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ はサイクル数も取れる場合がある
            val cycles = sticky?.getIntExtra("android.os.extra.CYCLE_COUNT", -1) ?: -1
            if (cycles >= 0) items += Item(ctx.getString(R.string.bat_cycles_label), cycles.toString())
        }
        return Section(ctx.getString(R.string.bat_section), items)
    }
}
