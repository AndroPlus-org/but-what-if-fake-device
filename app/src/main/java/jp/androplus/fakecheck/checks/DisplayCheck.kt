package jp.androplus.fakecheck.checks

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.R
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object DisplayCheck {

    @Suppress("DEPRECATION")
    fun display(ctx: Context): Display =
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay

    @Suppress("DEPRECATION")
    fun run(ctx: Context): Section {
        val items = ArrayList<Item>()
        val d = display(ctx)
        val dm = DisplayMetrics().also { d.getRealMetrics(it) }
        val w = max(dm.widthPixels, dm.heightPixels)
        val h = min(dm.widthPixels, dm.heightPixels)

        items += Item(ctx.getString(R.string.disp_render_label), "$w × $h", Status.INFO, ctx.getString(R.string.disp_render_note))

        if (Build.VERSION.SDK_INT >= 23) {
            val mode = d.mode
            val pw = max(mode.physicalWidth, mode.physicalHeight)
            val ph = min(mode.physicalWidth, mode.physicalHeight)
            items += Item(ctx.getString(R.string.disp_panel_label), "$pw × $ph")
            if (pw != w || ph != h) {
                items += Item(
                    ctx.getString(R.string.disp_diff_label),
                    ctx.getString(R.string.disp_diff_value, "$w×$h", "$pw×$ph"),
                    Status.INFO,
                    ctx.getString(R.string.disp_diff_note)
                )
            }
            val modes = d.supportedModes
            items += Item(
                ctx.getString(R.string.disp_modes_label),
                modes.joinToString("\n") {
                    "${max(it.physicalWidth, it.physicalHeight)}×${min(it.physicalWidth, it.physicalHeight)} @ ${Sys.f1(it.refreshRate.toDouble())}Hz"
                }
            )
            val maxHz = modes.maxOf { it.refreshRate }
            items += Item(
                ctx.getString(R.string.disp_maxhz_label),
                "${Sys.f1(maxHz.toDouble())} Hz",
                Status.INFO,
                ctx.getString(R.string.disp_maxhz_note)
            )
        } else {
            items += Item(ctx.getString(R.string.disp_hz_label), "${Sys.f1(d.refreshRate.toDouble())} Hz")
        }

        // xdpi / ydpi はパネルの物理ピクセル密度としてベンダーが設定する値
        val xIn = dm.widthPixels / dm.xdpi
        val yIn = dm.heightPixels / dm.ydpi
        val diag = sqrt((xIn * xIn + yIn * yIn).toDouble())
        val ppi = sqrt((dm.widthPixels.toDouble() * dm.widthPixels + dm.heightPixels.toDouble() * dm.heightPixels)) / diag
        val sane = diag in 3.0..40.0
        items += Item(
            ctx.getString(R.string.disp_size_label),
            ctx.getString(R.string.disp_size_value, Sys.f1(diag), Sys.f1(ppi), Sys.f1(dm.xdpi.toDouble()), Sys.f1(dm.ydpi.toDouble())),
            Status.INFO,
            ctx.getString(if (sane) R.string.disp_size_note_ok else R.string.disp_size_note_bad)
        )
        items += Item(ctx.getString(R.string.disp_density_label), "${dm.densityDpi} dpi (scale ${Sys.f2(dm.density.toDouble())})")

        if (Build.VERSION.SDK_INT >= 24) {
            val hdr = d.hdrCapabilities
            val types = hdr?.supportedHdrTypes?.map {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    4 -> "HDR10+"
                    else -> "type$it"
                }
            }.orEmpty()
            items += Item(ctx.getString(R.string.disp_hdr_label), if (types.isEmpty()) ctx.getString(R.string.common_none) else types.joinToString(", "))
        }
        if (Build.VERSION.SDK_INT >= 26) {
            items += Item(ctx.getString(R.string.disp_wcg_label), ctx.getString(if (d.isWideColorGamut) R.string.common_supported else R.string.common_unsupported))
        }

        items += Item(
            ctx.getString(R.string.disp_visual_label),
            ctx.getString(R.string.disp_visual_value),
            Status.INFO,
            ctx.getString(R.string.disp_visual_note)
        )
        return Section(ctx.getString(R.string.disp_section), items)
    }
}
