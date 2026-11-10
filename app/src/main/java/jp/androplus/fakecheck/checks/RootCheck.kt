package jp.androplus.fakecheck.checks

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.PrivShell
import jp.androplus.fakecheck.R
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys
import kotlin.math.max
import kotlin.math.min

/**
 * root 権限でしか読めない情報を使った追加チェック。
 * ブートローダーが渡した値 (デバイスツリー・カーネルコマンドライン)、物理アドレス空間、
 * ストレージチップの識別情報、カーネルログなど、ROM の書き換えだけでは揃えにくい値と突き合わせる。
 */
object RootCheck {

    /** よく使われるカメラセンサーの画素数 (MP)。型番から実画素数を推定する */
    private val SENSOR_MP = mapOf(
        "gc02m1" to 2.0, "gc2385" to 2.0, "gc2375" to 2.0, "gc2053" to 2.0, "sc202cs" to 2.0, "ov2680" to 2.0,
        "gc5035" to 5.0, "gc5025" to 5.0, "hi556" to 5.0, "ov5675" to 5.0, "ov5648" to 5.0, "s5k5e9" to 5.0, "s5k4h5" to 8.0,
        "gc8034" to 8.0, "gc08a3" to 8.0, "hi846" to 8.0, "ov8856" to 8.0, "ov8858" to 8.0, "s5k4h7" to 8.0, "imx219" to 8.0,
        "ov13b10" to 13.0, "ov13850" to 13.0, "s5k3l6" to 13.0, "s5k3l8" to 13.0, "imx258" to 13.0, "imx214" to 13.0, "hi1336" to 13.0,
        "s5k3p9" to 16.0, "imx519" to 16.0, "imx471" to 16.0, "s5k3t1" to 20.0, "imx576" to 24.0, "imx616" to 32.0,
        "imx582" to 48.0, "imx586" to 48.0, "s5kgm1" to 48.0, "s5kgm2" to 48.0, "ov48b" to 48.0,
        "imx766" to 50.0, "imx890" to 50.0, "s5kjn1" to 50.0, "ov50c40" to 50.0, "ov50h40" to 50.0, "s5kgn1" to 50.0,
        "imx682" to 64.0, "imx686" to 64.0, "ov64b" to 64.0, "s5kgw1" to 64.0, "s5kgw3" to 64.0,
        "s5khm2" to 108.0, "s5khm3" to 108.0, "s5khm6" to 108.0, "s5khp3" to 200.0,
    )

    private val SENSOR_RE = Regex(
        """(?i)(?<![a-z0-9])(imx\d{3}|s5k[a-z0-9]{2,4}|ov\d{2}[a-z]\d{2}|ov\d{4,5}|gc\d{2}[a-z]\d{1,2}|gc\d{4}|hi\d{3,4}|sc\d{3}[a-z]{2})(?![a-z0-9])"""
    )

    /** eMMC の CID manfid → メーカー名 (確度の高いものだけ) */
    private val EMMC_VENDOR = mapOf(
        0x15 to "Samsung", 0x90 to "SK hynix", 0x13 to "Micron", 0xfe to "Micron", 0x45 to "SanDisk / WD",
        0x11 to "Kioxia (Toshiba)", 0x70 to "Kingston", 0x88 to "Foresee (Longsys)",
    )

    fun items(ctx: Context, sh: PrivShell): List<Item> {
        val items = ArrayList<Item>()
        runCatching { deviceTree(ctx, sh, items) }
        runCatching { physicalRam(ctx, sh, items) }
        runCatching { storageChip(ctx, sh, items) }
        runCatching { display(ctx, sh, items) }
        runCatching { cameraSensors(ctx, sh, items) }
        runCatching { propOverrides(ctx, sh, items) }
        runCatching { modules(ctx, sh, items) }
        return items
    }

    // ---------------------------------------------------------------- SoC (デバイスツリー)

    /** /proc/device-tree の文字列 (NUL 区切り) を読む */
    private fun dtStrings(sh: PrivShell, name: String): List<String> =
        sh.exec("tr '\\000' '\\n' < /proc/device-tree/$name 2>/dev/null")
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun deviceTree(ctx: Context, sh: PrivShell, items: MutableList<Item>) {
        val model = dtStrings(sh, "model")
        val compat = dtStrings(sh, "compatible")
        val soc0 = listOf("machine", "soc_id", "family")
            .mapNotNull { k -> sh.read("/sys/devices/soc0/$k")?.let { "soc0/$k = $it" } }
        if (model.isEmpty() && compat.isEmpty() && soc0.isEmpty()) return
        items += Item(
            ctx.getString(R.string.root_dt_label),
            (listOfNotNull(
                model.takeIf { it.isNotEmpty() }?.let { "model = ${it.joinToString(" ")}" },
                compat.takeIf { it.isNotEmpty() }?.let { "compatible = ${it.joinToString(", ")}" },
            ) + soc0).joinToString("\n"),
            Status.INFO,
            ctx.getString(R.string.root_dt_note)
        )
        val real = SocCheck.vendorsIn((compat + model + soc0).joinToString(" "))
        val claimed = SocCheck.vendorsIn(SocCheck.claims().values.joinToString(" "))
        if (real.isEmpty() || claimed.isEmpty()) return
        items += if (real.intersect(claimed.toSet()).isEmpty()) {
            Item(
                ctx.getString(R.string.root_dt_match_label),
                ctx.getString(R.string.root_dt_mismatch, real.joinToString(" / "), claimed.joinToString(" / ")),
                Status.NG,
                ctx.getString(R.string.root_dt_mismatch_note)
            )
        } else {
            Item(ctx.getString(R.string.root_dt_match_label), ctx.getString(R.string.root_dt_match, real.joinToString(" / ")), Status.OK)
        }
    }

    // ---------------------------------------------------------------- RAM (物理アドレス空間)

    private fun physicalRam(ctx: Context, sh: PrivShell, items: MutableList<Item>) {
        // root でなければアドレスが 0 に伏せられる。トップレベルの "System RAM" だけを合計する
        var iomem = 0L
        sh.exec("grep -E '^[0-9a-fA-F]+-[0-9a-fA-F]+ : System RAM' /proc/iomem")?.lineSequence()?.forEach { line ->
            val m = Regex("""^([0-9a-fA-F]+)-([0-9a-fA-F]+)""").find(line) ?: return@forEach
            val a = m.groupValues[1].toULongOrNull(16) ?: return@forEach
            val b = m.groupValues[2].toULongOrNull(16) ?: return@forEach
            if (b > a) iomem += (b - a + 1u).toLong()
        }

        // ブートローダーがデバイスツリーの memory ノードに書き込んだ DRAM 範囲
        val dt = dtMemoryBytes(sh)

        val boot = sh.exec("dmesg 2>/dev/null | grep -m3 -iE 'Memory: [0-9]+K/[0-9]+K|dram size|ddr (size|type)|lpddr'")
            ?.lines()?.map { it.replace(Regex("""^\[[^]]*]\s*"""), "").trim() }?.filter { it.isNotEmpty() }.orEmpty()

        if (iomem <= 0 && dt <= 0 && boot.isEmpty()) return
        val lines = listOfNotNull(
            if (iomem > 0) ctx.getString(R.string.root_ram_iomem, Sys.gb(iomem)) else null,
            if (dt > 0) ctx.getString(R.string.root_ram_dt, Sys.gb(dt)) else null,
        ) + boot.map { "dmesg: $it" }
        items += Item(ctx.getString(R.string.root_ram_label), lines.joinToString("\n"), Status.INFO, ctx.getString(R.string.root_ram_note))

        val phys = max(iomem, dt)
        val memTotal = (Sys.meminfo()["MemTotal"] ?: 0L) * 1024
        if (phys > 0 && memTotal > 0) {
            val physNominal = MemoryCheck.nominalRamGb(phys)
            items += if (memTotal > phys * 1.01) {
                Item(
                    ctx.getString(R.string.root_ram_match_label),
                    ctx.getString(R.string.root_ram_bad, Sys.gb(memTotal), Sys.gb(phys)),
                    Status.NG,
                    ctx.getString(R.string.root_ram_bad_note)
                )
            } else {
                Item(ctx.getString(R.string.root_ram_match_label), ctx.getString(R.string.root_ram_ok, fmtGb(physNominal)), Status.OK)
            }
        }
    }

    private fun dtMemoryBytes(sh: PrivShell): Long {
        fun cells(name: String): Int =
            sh.exec("od -An -v -tx1 /proc/device-tree/$name 2>/dev/null")?.let { hexBytes(it) }
                ?.takeIf { it.size == 4 }?.fold(0) { acc, b -> (acc shl 8) or b } ?: 2
        val ac = cells("#address-cells")
        val sc = cells("#size-cells")
        if (ac !in 1..2 || sc !in 1..2) return 0
        val out = sh.exec("for f in /proc/device-tree/memory*/reg; do [ -f \"\$f\" ] && od -An -v -tx1 \"\$f\" && echo '--'; done 2>/dev/null")
            ?: return 0
        var total = 0L
        for (block in out.split("--")) {
            val bytes = hexBytes(block)
            val stride = (ac + sc) * 4
            if (bytes.isEmpty() || bytes.size % stride != 0) continue
            for (i in bytes.indices step stride) {
                var size = 0L
                for (j in 0 until sc * 4) size = (size shl 8) or bytes[i + ac * 4 + j].toLong()
                total += size
            }
        }
        // 未初期化のプレースホルダー (0 や数 MB) は捨てる
        return if (total >= 256L shl 20) total else 0
    }

    private fun hexBytes(s: String): List<Int> =
        s.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16) }

    // ---------------------------------------------------------------- ストレージチップ

    private fun storageChip(ctx: Context, sh: PrivShell, items: MutableList<Item>) {
        // eMMC
        val mmc = "/sys/block/mmcblk0/device"
        val type = sh.read("$mmc/type")
        if (type != null && type.equals("MMC", ignoreCase = true)) {
            val manfid = sh.read("$mmc/manfid")?.removePrefix("0x")?.toIntOrNull(16)
            val vendor = manfid?.let { EMMC_VENDOR[it] ?: String.format("0x%02x", it) } ?: "-"
            val lines = listOfNotNull(
                "eMMC: ${sh.read("$mmc/name") ?: "-"} / $vendor",
                sh.read("$mmc/date")?.let { ctx.getString(R.string.root_chip_date, it) },
                sh.read("$mmc/fwrev")?.let { "FW: $it" },
            )
            items += Item(ctx.getString(R.string.root_chip_label), lines.joinToString("\n"), Status.INFO, ctx.getString(R.string.root_chip_note))
            // life_time は "0x01 0x01" (タイプA / タイプB)、0x01 = 使用 0〜10%
            val life = sh.read("$mmc/life_time")?.split(Regex("\\s+"))?.mapNotNull { it.removePrefix("0x").toIntOrNull(16) }.orEmpty()
            val eol = sh.read("$mmc/pre_eol_info")?.removePrefix("0x")?.toIntOrNull(16)
            lifeItem(ctx, life, eol)?.let { items += it }
            return
        }

        // UFS: string_descriptors を持つコントローラーを探す
        val dirs = sh.exec("find /sys/devices -maxdepth 7 -type d -name string_descriptors 2>/dev/null | head -2")
            ?.lines()?.map { it.trim().removeSuffix("/string_descriptors") }?.filter { it.isNotEmpty() }.orEmpty()
        for (d in dirs) {
            val maker = sh.read("$d/string_descriptors/manufacturer_name")
            val product = sh.read("$d/string_descriptors/product_name")
            val rev = sh.read("$d/string_descriptors/product_revision")
            val spec = sh.read("$d/device_descriptor/specification_version")?.removePrefix("0x")?.toIntOrNull(16)
            if (maker == null && product == null) continue
            val ver = spec?.let { "UFS ${it shr 8}.${(it shr 4) and 0xf}" } ?: "UFS"
            items += Item(
                ctx.getString(R.string.root_chip_label),
                listOfNotNull("$ver: ${maker ?: "-"} ${product ?: ""}".trim(), rev?.let { "FW: $it" }).joinToString("\n"),
                Status.INFO,
                ctx.getString(R.string.root_chip_note)
            )
            val life = listOf("life_time_estimation_a", "life_time_estimation_b")
                .mapNotNull { sh.read("$d/health_descriptor/$it")?.removePrefix("0x")?.toIntOrNull(16) }
            val eol = sh.read("$d/health_descriptor/eol_info")?.removePrefix("0x")?.toIntOrNull(16)
            lifeItem(ctx, life, eol)?.let { items += it }
            return
        }
    }

    /** eMMC / UFS 共通の寿命表記: 1 = 0〜10% 使用, 10 = 90〜100%, 11 = 超過 */
    private fun lifeItem(ctx: Context, life: List<Int>, eol: Int?): Item? {
        val used = life.filter { it in 1..11 }.maxOrNull() ?: return null
        val pct = if (used >= 11) ">100%" else "${(used - 1) * 10}-${used * 10}%"
        val eolText = when (eol) {
            1 -> ctx.getString(R.string.root_eol_normal)
            2 -> ctx.getString(R.string.root_eol_warning)
            3 -> ctx.getString(R.string.root_eol_urgent)
            else -> "-"
        }
        val worn = used >= 3 || (eol ?: 0) >= 2
        return Item(
            ctx.getString(R.string.root_life_label),
            ctx.getString(R.string.root_life_value, pct, eolText),
            if (worn) Status.WARN else Status.OK,
            if (worn) ctx.getString(R.string.root_life_note) else null
        )
    }

    // ---------------------------------------------------------------- ディスプレイ

    private fun display(ctx: Context, sh: PrivShell, items: MutableList<Item>) {
        // ブートローダーがカーネルに渡すパネル名 (MediaTek: lcm=, Qualcomm: mdss_mdp.panel= / dsi_display0=)
        val cmdline = listOfNotNull(sh.read("/proc/cmdline"), sh.read("/proc/bootconfig")).joinToString(" ")
        val panels = Regex("""(?i)(lcm=\S+|mdss_mdp\.panel=\S+|msm_drm\.dsi_display\d=\S+|androidboot\.(lcm|panel)\S*\s*=\s*\S+)""")
            .findAll(cmdline).map { it.value.take(120) }.distinct().toList()

        val drm = sh.exec("for d in /sys/class/drm/card*-*; do [ -f \"\$d/modes\" ] && echo \"\${d##*/} \$(cat \$d/status 2>/dev/null) \$(head -1 \$d/modes 2>/dev/null)\"; done")
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        if (panels.isEmpty() && drm.isEmpty()) return
        items += Item(ctx.getString(R.string.root_panel_label), (panels + drm.map { "drm: $it" }).joinToString("\n"), Status.INFO, ctx.getString(R.string.root_panel_note))

        // 内蔵パネル (DSI / eDP) の解像度とアプリ向けのディスプレイモードを比較
        val native = drm.firstOrNull { Regex("(?i)-(DSI|eDP)").containsMatchIn(it) }
            ?.let { Regex("""(\d{3,5})x(\d{3,5})""").find(it) }
            ?.let { val a = it.groupValues[1].toInt(); val b = it.groupValues[2].toInt(); max(a, b) to min(a, b) }
            ?: return
        @Suppress("DEPRECATION")
        val mode = DisplayCheck.display(ctx).mode
        val api = max(mode.physicalWidth, mode.physicalHeight) to min(mode.physicalWidth, mode.physicalHeight)
        items += if (native == api) {
            Item(ctx.getString(R.string.root_panel_match_label), ctx.getString(R.string.common_match), Status.OK)
        } else {
            Item(
                ctx.getString(R.string.root_panel_match_label),
                ctx.getString(R.string.root_panel_mismatch, "${native.first}×${native.second}", "${api.first}×${api.second}"),
                Status.WARN,
                ctx.getString(R.string.root_panel_mismatch_note)
            )
        }
    }

    // ---------------------------------------------------------------- カメラ

    private fun cameraSensors(ctx: Context, sh: PrivShell, items: MutableList<Item>) {
        // Qualcomm はセンサーモジュールの設定ファイル名、MediaTek などはカーネルログにセンサー型番が出る
        val text = listOfNotNull(
            sh.exec("ls /vendor/lib64/camera /vendor/lib/camera /odm/lib64/camera 2>/dev/null"),
            sh.read("/proc/driver/camera_info"),
            sh.exec("dmesg 2>/dev/null | grep -iE 'sensor|imgsensor|cam' | tail -400"),
        ).joinToString("\n")
        val found = SENSOR_RE.findAll(text).map { it.value.lowercase() }.distinct().take(12).toList()
        if (found.isEmpty()) return
        items += Item(
            ctx.getString(R.string.root_cam_label),
            found.joinToString("\n") { n -> SENSOR_MP[n]?.let { "$n (${fmtMp(it)})" } ?: n },
            Status.INFO,
            ctx.getString(R.string.root_cam_note)
        )

        // 検出した型番がすべて既知なら、アプリに報告される最大画素数と比べる
        val known = found.mapNotNull { SENSOR_MP[it] }
        if (known.isEmpty() || known.size != found.size) return
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val reported = runCatching {
            cm.cameraIdList.maxOfOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    ?.let { it.width.toDouble() * it.height / 1e6 } ?: 0.0
            } ?: 0.0
        }.getOrDefault(0.0)
        val maxKnown = known.max()
        if (reported > maxKnown * 1.3) {
            items += Item(
                ctx.getString(R.string.root_cam_match_label),
                ctx.getString(R.string.root_cam_mismatch, Sys.f1(reported), fmtMp(maxKnown)),
                Status.WARN,
                ctx.getString(R.string.root_cam_mismatch_note)
            )
        }
    }

    // ---------------------------------------------------------------- プロパティの実行時書き換え

    private val WATCH_PROPS = listOf(
        "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.incremental", "ro.build.display.id",
        "ro.product.model", "ro.product.brand", "ro.product.manufacturer", "ro.product.device",
        "ro.product.system.model", "ro.product.vendor.model", "ro.product.odm.model", "ro.build.fingerprint",
    )

    private fun propOverrides(ctx: Context, sh: PrivShell, items: MutableList<Item>) {
        val files = listOf(
            "/system/build.prop", "/system/system/build.prop", "/system_ext/etc/build.prop", "/vendor/build.prop",
            "/vendor/odm/etc/build.prop", "/odm/etc/build.prop", "/product/etc/build.prop", "/product/build.prop",
        )
        val inFiles = HashMap<String, MutableSet<String>>()
        for (f in files) {
            sh.read(f)?.lineSequence()?.forEach { line ->
                val t = line.trim()
                if (t.startsWith("#")) return@forEach
                val i = t.indexOf('=')
                if (i <= 0) return@forEach
                val k = t.substring(0, i).trim()
                if (k in WATCH_PROPS) inFiles.getOrPut(k) { HashSet() } += t.substring(i + 1).trim()
            }
        }
        if (inFiles.isEmpty()) return
        val changed = WATCH_PROPS.mapNotNull { k ->
            val vals = inFiles[k] ?: return@mapNotNull null
            val now = Sys.prop(k)
            if (now.isNotEmpty() && now !in vals) "$k\n  build.prop: ${vals.joinToString(" | ")}\n  getprop: $now" else null
        }
        items += if (changed.isEmpty()) {
            Item(ctx.getString(R.string.root_prop_label), ctx.getString(R.string.root_prop_ok), Status.OK)
        } else {
            Item(ctx.getString(R.string.root_prop_label), changed.joinToString("\n"), Status.WARN, ctx.getString(R.string.root_prop_note))
        }

        // init スクリプトで型番・バージョン・容量系の ro プロパティを上書きしていないか
        val initSets = sh.exec(
            "grep -rhE '^[[:space:]]*setprop[[:space:]]+ro\\.' /system/etc/init /system_ext/etc/init /vendor/etc/init /odm/etc/init /product/etc/init 2>/dev/null" +
                " | grep -iE 'model|brand|manufacturer|version|ram|rom|storage|mem|lcd|display|camera' | head -20"
        )?.lines()?.map { it.trim() }?.filter { it.startsWith("setprop") }.orEmpty()
        if (initSets.isNotEmpty()) {
            items += Item(ctx.getString(R.string.root_initprop_label), initSets.joinToString("\n"), Status.INFO, ctx.getString(R.string.root_initprop_note))
        }
    }

    // ---------------------------------------------------------------- root 環境

    private fun modules(ctx: Context, sh: PrivShell, items: MutableList<Item>) {
        val list = sh.exec(
            "for d in /data/adb/modules/*/; do [ -f \"\$d/module.prop\" ] || continue; [ -f \"\$d/disable\" ] && continue;" +
                " n=\$(grep -m1 '^name=' \"\$d/module.prop\" | cut -d= -f2-); echo \"\${n:-\$(basename \$d)}\"; done"
        )?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        items += Item(
            ctx.getString(R.string.root_modules_label),
            if (list.isEmpty()) ctx.getString(R.string.common_none) else list.joinToString("\n"),
            Status.INFO,
            if (list.isEmpty()) null else ctx.getString(R.string.root_modules_note)
        )
    }

    private fun fmtGb(v: Double): String = if (v == Math.floor(v)) "${v.toInt()}GB" else "${v}GB"
    private fun fmtMp(v: Double): String = if (v == Math.floor(v)) "${v.toInt()}MP" else "${v}MP"
}
