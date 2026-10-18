package jp.androplus.fakecheck.checks

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys
import java.io.File

object SocCheck {

    /** ARM の CPU part 番号 → コア名 (implementer 0x41 = ARM) */
    private val ARM_PARTS = mapOf(
        0xd01 to "Cortex-A32", 0xd02 to "Cortex-A34", 0xd03 to "Cortex-A53", 0xd04 to "Cortex-A35",
        0xd05 to "Cortex-A55", 0xd06 to "Cortex-A65", 0xd07 to "Cortex-A57", 0xd08 to "Cortex-A72",
        0xd09 to "Cortex-A73", 0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76", 0xd0c to "Neoverse-N1",
        0xd0d to "Cortex-A77", 0xd0e to "Cortex-A76AE", 0xd41 to "Cortex-A78", 0xd44 to "Cortex-X1",
        0xd46 to "Cortex-A510", 0xd47 to "Cortex-A710", 0xd48 to "Cortex-X2", 0xd4d to "Cortex-A715",
        0xd4e to "Cortex-X3", 0xd80 to "Cortex-A520", 0xd81 to "Cortex-A720", 0xd82 to "Cortex-X4",
        0xd85 to "Cortex-X925", 0xd87 to "Cortex-A725", 0xc07 to "Cortex-A7", 0xc0f to "Cortex-A15",
        0xc0d to "Cortex-A17", 0xc09 to "Cortex-A9",
    )

    data class Gpu(val vendor: String, val renderer: String, val version: String)

    fun run(): Section {
        val items = ArrayList<Item>()

        val claims = linkedMapOf<String, String>()
        if (Build.VERSION.SDK_INT >= 31) {
            claims["Build.SOC_MANUFACTURER"] = Build.SOC_MANUFACTURER
            claims["Build.SOC_MODEL"] = Build.SOC_MODEL
        }
        claims["Build.HARDWARE"] = Build.HARDWARE
        claims["Build.BOARD"] = Build.BOARD
        for (k in listOf("ro.board.platform", "ro.hardware", "ro.soc.manufacturer", "ro.soc.model", "ro.hardware.chipname", "ro.chipname", "ro.mediatek.platform")) {
            Sys.prop(k).takeIf { it.isNotEmpty() }?.let { claims[k] = it }
        }
        val cpuinfo = Sys.read("/proc/cpuinfo").orEmpty()
        Regex("(?im)^Hardware\\s*:\\s*(.+)$").find(cpuinfo)?.let { claims["/proc/cpuinfo Hardware"] = it.groupValues[1].trim() }
        items += Item("SoC 識別情報", claims.entries.joinToString("\n") { "${it.key} = ${it.value}" })

        // コア構成
        val cores = cpuCores(cpuinfo)
        if (cores.isNotEmpty()) {
            val grouped = cores.groupBy { it.first to it.second }
                .entries.sortedByDescending { it.key.second }
                .joinToString("\n") { (k, v) -> "${k.first} × ${v.size}  最大 ${if (k.second > 0) Sys.f2(k.second / 1e6) + " GHz" else "不明"}" }
            items += Item("CPU コア構成 (実測)", "${cores.size} コア\n$grouped", Status.INFO,
                "カーネルが認識している実際のコア。仕様表のコア数・コア種類・クロックと比べてください。")
        } else {
            items += Item("CPU コア数", "${Runtime.getRuntime().availableProcessors()} (詳細読み取り不可)")
        }
        items += Item("CPU ABI", Build.SUPPORTED_ABIS.joinToString(", "))

        // GPU
        val gpu = try { queryGpu() } catch (e: Throwable) { null }
        if (gpu != null) {
            items += Item("GPU (OpenGL ES)", "${gpu.renderer}\n${gpu.vendor} / ${gpu.version}")
        } else {
            items += Item("GPU (OpenGL ES)", "取得失敗")
        }

        // 整合性チェック: 名乗っている SoC ベンダーと GPU が合っているか
        val claimText = claims.values.joinToString(" ").lowercase()
        val claimsQcom = Regex("qcom|\\bqti\\b|qualcomm|snapdragon|\\bsdm\\d|\\bsm\\d{4}|msm\\d|\\b(kona|lahaina|taro|kalama|pineapple|holi|bengal|lito|trinket|parrot|crow|blair|sun)\\b").containsMatchIn(claimText)
        val claimsMtk = Regex("mediatek|\\bmt\\d{4}|helio|dimensity").containsMatchIn(claimText)
        val claimsUnisoc = Regex("unisoc|spreadtrum|sprd|\\bums\\d|\\bsc98|\\bsp98|\\bt6\\d\\d|\\bt7\\d\\d|\\bt8\\d\\d|tiger").containsMatchIn(claimText)
        val claimsRockchip = Regex("rockchip|\\brk3\\d{3}").containsMatchIn(claimText)
        val claimsAllwinner = Regex("allwinner|\\bsun\\d+iw?\\d*").containsMatchIn(claimText)
        val claimsExynos = Regex("exynos|samsung|\\bs5e\\d").containsMatchIn(claimText)
        val claimsTensor = Regex("tensor|gs101|gs201|zuma").containsMatchIn(claimText)

        val vendors = listOfNotNull(
            "Qualcomm".takeIf { claimsQcom }, "MediaTek".takeIf { claimsMtk }, "Unisoc".takeIf { claimsUnisoc },
            "Rockchip".takeIf { claimsRockchip }, "Allwinner".takeIf { claimsAllwinner },
            "Samsung Exynos".takeIf { claimsExynos }, "Google Tensor".takeIf { claimsTensor },
        )
        if (vendors.size >= 2) {
            items += Item(
                "SoC 識別情報の一貫性",
                "複数ベンダーが混在: ${vendors.joinToString(" / ")}",
                Status.WARN,
                "識別情報の一部だけが書き換えられている可能性があります。cpuinfo / ro.board.platform などカーネル・ベンダー由来の値の方が信頼できます。"
            )
        } else if (vendors.size == 1) {
            items += Item("SoC 識別情報の一貫性", "${vendors[0]} で一貫", Status.OK)
        }

        if (gpu != null) {
            val r = gpu.renderer.lowercase() + " " + gpu.vendor.lowercase()
            val isAdreno = r.contains("adreno")
            val isMali = r.contains("mali") || r.contains("immortalis")
            val isImg = r.contains("powervr") || r.contains("imagination") || r.contains("img ")
            val gpuOk: Boolean? = when {
                claimsQcom -> isAdreno
                isAdreno -> claimsQcom
                claimsUnisoc || claimsRockchip || claimsAllwinner -> isMali || isImg
                claimsMtk -> isMali || isImg
                else -> null
            }
            when (gpuOk) {
                true -> items += Item("SoC と GPU の整合性", "矛盾なし", Status.OK)
                false -> items += Item(
                    "SoC と GPU の整合性",
                    "名乗っている SoC と GPU (${gpu.renderer}) が合いません",
                    Status.NG,
                    "例: Snapdragon を名乗っているのに GPU が Adreno 以外。SoC 名が偽装されている可能性が高いです。"
                )
                null -> {}
            }
            if (r.contains("swiftshader") || r.contains("llvmpipe") || r.contains("android emulator")) {
                items += Item("GPU 種別", "ソフトウェアレンダラ / エミュレータ", Status.WARN)
            }
        }
        return Section("CPU / SoC / GPU", items)
    }

    /** (コア名, 最大周波数kHz) のリスト */
    private fun cpuCores(cpuinfo: String): List<Pair<String, Long>> {
        val parts = HashMap<Int, String>()
        var cur = -1
        var impl = 0
        for (line in cpuinfo.lineSequence()) {
            val kv = line.split(":", limit = 2).map { it.trim() }
            if (kv.size != 2) continue
            when (kv[0].lowercase()) {
                "processor" -> cur = kv[1].toIntOrNull() ?: -1
                "cpu implementer" -> impl = kv[1].removePrefix("0x").toIntOrNull(16) ?: 0
                "cpu part" -> if (cur >= 0) {
                    val part = kv[1].removePrefix("0x").toIntOrNull(16) ?: -1
                    parts[cur] = if (impl == 0x41) ARM_PARTS[part] ?: "ARM part 0x%x".format(part)
                    else "impl 0x%x part 0x%x".format(impl, part)
                }
            }
        }
        val possible = Sys.read("/sys/devices/system/cpu/possible")
        val count = possible?.let { parseRangeCount(it) }
            ?: File("/sys/devices/system/cpu").list()?.count { Regex("cpu\\d+").matches(it) }
            ?: 0
        if (count == 0) return emptyList()
        return (0 until count).map { i ->
            val freq = Sys.readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: 0L
            (parts[i] ?: "不明なコア") to freq
        }
    }

    private fun parseRangeCount(s: String): Int? = try {
        s.split(",").sumOf { r ->
            val p = r.trim().split("-")
            if (p.size == 2) p[1].toInt() - p[0].toInt() + 1 else 1
        }
    } catch (e: Exception) {
        null
    }

    /** オフスクリーン EGL コンテキストを作って GPU 名を取る */
    private fun queryGpu(): Gpu? {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return null
            val ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surf = EGL14.eglCreatePbufferSurface(dpy, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
                return Gpu(
                    GLES20.glGetString(GLES20.GL_VENDOR).orEmpty(),
                    GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
                    GLES20.glGetString(GLES20.GL_VERSION).orEmpty(),
                )
            } finally {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf)
                EGL14.eglDestroyContext(dpy, ctx)
            }
        } finally {
            EGL14.eglTerminate(dpy)
        }
    }
}
