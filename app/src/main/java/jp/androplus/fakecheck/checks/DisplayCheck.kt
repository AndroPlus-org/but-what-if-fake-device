package jp.androplus.fakecheck.checks

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import jp.androplus.fakecheck.Item
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

        items += Item("描画解像度", "$w × $h", Status.INFO, "アプリが実際に描画しているピクセル数。")

        if (Build.VERSION.SDK_INT >= 23) {
            val mode = d.mode
            val pw = max(mode.physicalWidth, mode.physicalHeight)
            val ph = min(mode.physicalWidth, mode.physicalHeight)
            items += Item("パネル解像度 (ディスプレイモード)", "$pw × $ph")
            if (pw != w || ph != h) {
                items += Item(
                    "解像度の差",
                    "描画 $w×$h ≠ パネル $pw×$ph",
                    Status.WARN,
                    "設定の「画面解像度」で下げているか、システムが拡大表示しています。設定で最高解像度にしてから再確認してください。"
                )
            }
            val modes = d.supportedModes
            items += Item(
                "対応ディスプレイモード",
                modes.joinToString("\n") {
                    "${max(it.physicalWidth, it.physicalHeight)}×${min(it.physicalWidth, it.physicalHeight)} @ ${Sys.f1(it.refreshRate.toDouble())}Hz"
                }
            )
            val maxHz = modes.maxOf { it.refreshRate }
            items += Item(
                "最大リフレッシュレート (申告)",
                "${Sys.f1(maxHz.toDouble())} Hz",
                Status.INFO,
                "下の「リフレッシュレート実測」で実際の描画fpsを測れます。"
            )
        } else {
            items += Item("リフレッシュレート (申告)", "${Sys.f1(d.refreshRate.toDouble())} Hz")
        }

        // xdpi / ydpi はパネルの物理ピクセル密度としてベンダーが設定する値
        val xIn = dm.widthPixels / dm.xdpi
        val yIn = dm.heightPixels / dm.ydpi
        val diag = sqrt((xIn * xIn + yIn * yIn).toDouble())
        val ppi = sqrt((dm.widthPixels.toDouble() * dm.widthPixels + dm.heightPixels.toDouble() * dm.heightPixels)) / diag
        val sane = diag in 3.0..40.0
        items += Item(
            "画面サイズ (dpi値からの計算)",
            "${Sys.f1(diag)} インチ / ${Sys.f1(ppi)} ppi (xdpi=${Sys.f1(dm.xdpi.toDouble())}, ydpi=${Sys.f1(dm.ydpi.toDouble())})",
            if (sane) Status.INFO else Status.WARN,
            if (sane) "定規で測った画面の対角と比べてください。大きく違う場合、ベンダー設定値が不正確です。"
            else "dpi値が不自然で、画面サイズを正しく計算できません。"
        )
        items += Item("論理密度", "${dm.densityDpi} dpi (scale ${Sys.f2(dm.density.toDouble())})")

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
            items += Item("HDR対応", if (types.isEmpty()) "なし" else types.joinToString(", "))
        }
        if (Build.VERSION.SDK_INT >= 26) {
            items += Item("広色域 (Wide Color Gamut)", if (d.isWideColorGamut) "対応" else "非対応")
        }

        items += Item(
            "目視テスト",
            "「ディスプレイ表示テスト」で 1ピクセル縞模様・グラデーション・単色・マルチタッチを確認できます。",
            Status.INFO,
            "1px の縞模様をスマホのカメラ等で拡大して、縞がきれいに見えずグレーやモアレになる場合、" +
                "実際のパネル解像度は申告より低い(縮小表示されている)可能性があります。"
        )
        return Section("ディスプレイ", items)
    }
}
