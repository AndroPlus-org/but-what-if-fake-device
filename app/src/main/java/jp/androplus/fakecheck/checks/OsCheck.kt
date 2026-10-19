package jp.androplus.fakecheck.checks

import android.content.Context
import android.os.Build
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.R
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys

object OsCheck {

    /** Android バージョン(メジャー) → API レベル */
    private val RELEASE_TO_SDK = mapOf(
        "5.0" to 21..21, "5.1" to 22..22, "6" to 23..23, "7.0" to 24..24, "7.1" to 25..25,
        "8.0" to 26..26, "8.1" to 27..27, "9" to 28..28, "10" to 29..29, "11" to 30..30,
        "12" to 31..32, "13" to 33..33, "14" to 34..34, "15" to 35..35, "16" to 36..36,
    )

    /**
     * その API レベルで追加された(偽装では足せない)フレームワークのクラス。
     * OEM が省けるメインラインモジュールのクラスは誤判定の元になるので使わない。
     */
    private val MARKER_CLASSES = listOf(
        21 to "android.app.job.JobScheduler",
        23 to "android.hardware.fingerprint.FingerprintManager",
        24 to "android.icu.util.Calendar",
        26 to "android.app.NotificationChannel",
        28 to "android.graphics.ImageDecoder",
        29 to "android.graphics.BlendMode",
        30 to "android.view.WindowInsets\$Type",
        31 to "android.os.VibratorManager",
        33 to "android.app.LocaleConfig",
        34 to "android.credentials.CredentialManager",
        35 to "android.app.ComponentCaller",
    )

    private fun classExists(name: String): Boolean = try {
        Class.forName(name)
        true
    } catch (e: Throwable) {
        false
    }

    private fun expectedSdk(release: String): IntRange? {
        val r = release.trim()
        RELEASE_TO_SDK[r]?.let { return it }
        val major = r.substringBefore('.')
        RELEASE_TO_SDK[major]?.let { return it }
        val mm = r.split('.').take(2).joinToString(".")
        return RELEASE_TO_SDK[mm]
    }

    fun run(ctx: Context): Section {
        val items = ArrayList<Item>()
        val sdk = Build.VERSION.SDK_INT
        val release = Build.VERSION.RELEASE
        items += Item(ctx.getString(R.string.os_version_label), "Android $release")
        items += Item(ctx.getString(R.string.os_sdk_label), sdk.toString())

        val exp = expectedSdk(release)
        if (exp != null) {
            items += if (sdk in exp) {
                Item(ctx.getString(R.string.os_consistency_label), ctx.getString(R.string.os_consistent), Status.OK)
            } else {
                Item(
                    ctx.getString(R.string.os_consistency_label),
                    ctx.getString(R.string.os_inconsistent, release, exp.first.toString(), sdk.toString()),
                    Status.NG,
                    ctx.getString(R.string.os_inconsistent_note, sdk.toString(), sdkName(sdk))
                )
            }
        }

        // フレームワークが知っている最大のバージョンコード。build.prop 改変では変えられない
        val maxCode = try {
            Build.VERSION_CODES::class.java.fields
                .mapNotNull { f -> (f.get(null) as? Int)?.takeIf { it < 1000 } }
                .maxOrNull()
        } catch (e: Throwable) {
            null
        }
        if (maxCode != null) {
            items += Item(
                ctx.getString(R.string.os_fw_label),
                ctx.getString(R.string.os_fw_value, maxCode.toString(), sdkName(maxCode)),
                if (maxCode < sdk) Status.NG else Status.OK,
                if (maxCode < sdk) ctx.getString(R.string.os_fw_note, maxCode.toString(), sdk.toString()) else null
            )
        }

        val missing = MARKER_CLASSES.filter { (level, cls) -> level <= sdk && !classExists(cls) }
        items += if (missing.isEmpty()) {
            Item(ctx.getString(R.string.os_marker_label), ctx.getString(R.string.os_marker_ok, MARKER_CLASSES.last { it.first <= sdk }.first.toString()), Status.OK)
        } else {
            val realLevel = MARKER_CLASSES.lastOrNull { (_, cls) -> classExists(cls) }?.first
            // 最上位の1つだけ欠けている場合はOEMの独自ビルドの可能性もあるので「注意」に留める
            val topLevel = MARKER_CLASSES.last { it.first <= sdk }.first
            val onlyTop = missing.size == 1 && missing[0].first == topLevel
            Item(
                ctx.getString(R.string.os_marker_label),
                ctx.getString(R.string.os_marker_missing, missing.joinToString { "API${it.first}" }),
                if (onlyTop) Status.WARN else Status.NG,
                ctx.getString(R.string.os_marker_note, sdk.toString(), realLevel?.toString() ?: "?")
            )
        }

        items += Item(ctx.getString(R.string.os_patch_label), if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else ctx.getString(R.string.common_unknown))
        items += Item(ctx.getString(R.string.os_kernel_label), Sys.read("/proc/version") ?: System.getProperty("os.version").orEmpty())

        val vendorRelease = Sys.prop("ro.vendor.build.version.release")
        val vendorSdk = Sys.prop("ro.vendor.build.version.sdk")
        val firstApi = Sys.prop("ro.product.first_api_level").ifEmpty { Sys.prop("ro.board.first_api_level") }
        val extra = listOfNotNull(
            vendorRelease.takeIf { it.isNotEmpty() }?.let { "vendor: Android $it" + (vendorSdk.takeIf { s -> s.isNotEmpty() }?.let { s -> " (API $s)" } ?: "") },
            firstApi.takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.os_first_api, it) },
        )
        if (extra.isNotEmpty()) {
            items += Item(ctx.getString(R.string.os_vendor_label), extra.joinToString("\n"), Status.INFO,
                ctx.getString(R.string.os_vendor_note))
        }

        // 型番・ブランドの書き換えチェック: 表示用の値だけ書き換えて vendor/odm 側が元のまま、という例が多い。
        // system / product などは共通イメージ(qssi, mssi など)の汎用名が入るのが普通なので比較しない
        val fields = listOf("brand", "manufacturer", "model", "device", "name")
        val partitions = listOf("", "system.", "vendor.", "odm.", "product.", "system_ext.")
        val compared = listOf("", "vendor.", "odm.")
        val generic = Regex("""(?i)^(qssi.*|mssi.*|sys_mssi.*|missi.*|ossi.*|generic.*|aosp.*|full_.*|android|qti|qcom|mtk|mediatek|unisoc|sprd|unknown|alps)$""")
        val diffs = ArrayList<String>()
        val table = StringBuilder()
        for (f in fields) {
            val values = partitions.associateWith { p -> Sys.prop("ro.product.$p$f") }.filterValues { it.isNotEmpty() }
            if (values.isEmpty()) continue
            table.append(f).append(": ").append(values.entries.joinToString(" / ") { "${it.key.ifEmpty { "ro." }.trimEnd('.')}=${it.value}" }).append('\n')
            if (f !in listOf("brand", "manufacturer", "model")) continue
            val distinct = values.filterKeys { it in compared }.values
                .map { it.trim().lowercase() }
                .filterNot { generic.matches(it) }
                .distinct()
            if (distinct.size > 1) diffs += f
        }
        items += Item(ctx.getString(R.string.os_device_label), "${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND} / ${Build.DEVICE})")
        if (table.isNotEmpty()) {
            items += Item(
                ctx.getString(R.string.os_partitions_label),
                table.toString().trim(),
                if (diffs.isEmpty()) Status.INFO else Status.WARN,
                if (diffs.isEmpty()) null
                else ctx.getString(R.string.os_partitions_note, diffs.joinToString())
            )
        }
        items += Item(ctx.getString(R.string.os_fingerprint_label), Build.FINGERPRINT)
        return Section(ctx.getString(R.string.os_section), items)
    }

    fun sdkName(sdk: Int): String = when (sdk) {
        21 -> "Android 5.0"; 22 -> "Android 5.1"; 23 -> "Android 6"; 24 -> "Android 7.0"; 25 -> "Android 7.1"
        26 -> "Android 8.0"; 27 -> "Android 8.1"; 28 -> "Android 9"; 29 -> "Android 10"; 30 -> "Android 11"
        31 -> "Android 12"; 32 -> "Android 12L"; 33 -> "Android 13"; 34 -> "Android 14"; 35 -> "Android 15"
        36 -> "Android 16"; else -> "API $sdk"
    }
}
