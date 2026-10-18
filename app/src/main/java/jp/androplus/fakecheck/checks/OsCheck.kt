package jp.androplus.fakecheck.checks

import android.os.Build
import jp.androplus.fakecheck.Item
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

    /** その API レベルで追加された(偽装では足せない)フレームワークのクラス */
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
        34 to "android.health.connect.HealthConnectManager",
        35 to "android.os.ProfilingManager",
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

    fun run(): Section {
        val items = ArrayList<Item>()
        val sdk = Build.VERSION.SDK_INT
        val release = Build.VERSION.RELEASE
        items += Item("Android バージョン (表示値)", "Android $release")
        items += Item("API レベル (SDK_INT)", sdk.toString())

        val exp = expectedSdk(release)
        if (exp != null) {
            items += if (sdk in exp) {
                Item("バージョンとAPIレベルの整合性", "整合", Status.OK)
            } else {
                Item(
                    "バージョンとAPIレベルの整合性",
                    "Android $release なら API ${exp.first} のはずが API $sdk",
                    Status.NG,
                    "表示上の Android バージョンだけが書き換えられている可能性が高いです (実際は API $sdk = ${sdkName(sdk)})。"
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
                "フレームワーク実体のバージョン",
                "API $maxCode (${sdkName(maxCode)}) まで定義",
                if (maxCode < sdk) Status.NG else Status.OK,
                if (maxCode < sdk) "OS本体は API $maxCode なのに API $sdk を名乗っています。OSバージョン偽装の疑いがあります。" else null
            )
        }

        val missing = MARKER_CLASSES.filter { (level, cls) -> level <= sdk && !classExists(cls) }
        items += if (missing.isEmpty()) {
            Item("API レベル固有機能の存在確認", "API ${MARKER_CLASSES.last { it.first <= sdk }.first} までの機能を確認", Status.OK)
        } else {
            val realLevel = MARKER_CLASSES.lastOrNull { (_, cls) -> classExists(cls) }?.first
            Item(
                "API レベル固有機能の存在確認",
                "不足: " + missing.joinToString { "API${it.first}" },
                Status.NG,
                "名乗っている API $sdk に必要な機能がありません。実際は API ${realLevel ?: "?"} 程度の OS と推定されます。"
            )
        }

        items += Item("セキュリティパッチ", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "不明")
        items += Item("カーネル", Sys.read("/proc/version") ?: System.getProperty("os.version").orEmpty())

        val vendorRelease = Sys.prop("ro.vendor.build.version.release")
        val vendorSdk = Sys.prop("ro.vendor.build.version.sdk")
        val firstApi = Sys.prop("ro.product.first_api_level").ifEmpty { Sys.prop("ro.board.first_api_level") }
        val extra = listOfNotNull(
            vendorRelease.takeIf { it.isNotEmpty() }?.let { "vendor: Android $it" + (vendorSdk.takeIf { s -> s.isNotEmpty() }?.let { s -> " (API $s)" } ?: "") },
            firstApi.takeIf { it.isNotEmpty() }?.let { "初期搭載 API: $it" },
        )
        if (extra.isNotEmpty()) {
            items += Item("ベンダー領域のバージョン", extra.joinToString("\n"), Status.INFO,
                "ベンダー領域は OS 本体より古いことがありますが、system 側と大きく離れている場合は参考にしてください。")
        }

        // 型番・ブランドの書き換えチェック: system 側だけ書き換えて vendor/odm 側が元のまま、という例が多い
        val fields = listOf("brand", "manufacturer", "model", "device", "name")
        val partitions = listOf("", "system.", "vendor.", "odm.", "product.", "system_ext.")
        val diffs = ArrayList<String>()
        val table = StringBuilder()
        for (f in fields) {
            val values = partitions.associateWith { p -> Sys.prop("ro.product.$p$f") }.filterValues { it.isNotEmpty() }
            if (values.isEmpty()) continue
            table.append(f).append(": ").append(values.entries.joinToString(" / ") { "${it.key.ifEmpty { "ro." }.trimEnd('.')}=${it.value}" }).append('\n')
            if (values.values.map { it.lowercase() }.distinct().size > 1) diffs += f
        }
        items += Item("端末", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND} / ${Build.DEVICE})")
        if (table.isNotEmpty()) {
            items += Item(
                "パーティション別の型番情報",
                table.toString().trim(),
                if (diffs.isEmpty()) Status.OK else Status.WARN,
                if (diffs.isEmpty()) null
                else "${diffs.joinToString()} がパーティションごとに異なります。OEM品の再ブランドで正常な場合もありますが、" +
                    "型番やメーカー名を書き換えている痕跡のこともあります。"
            )
        }
        items += Item("ビルド フィンガープリント", Build.FINGERPRINT)
        return Section("OS / 端末情報", items)
    }

    fun sdkName(sdk: Int): String = when (sdk) {
        21 -> "Android 5.0"; 22 -> "Android 5.1"; 23 -> "Android 6"; 24 -> "Android 7.0"; 25 -> "Android 7.1"
        26 -> "Android 8.0"; 27 -> "Android 8.1"; 28 -> "Android 9"; 29 -> "Android 10"; 30 -> "Android 11"
        31 -> "Android 12"; 32 -> "Android 12L"; 33 -> "Android 13"; 34 -> "Android 14"; 35 -> "Android 15"
        36 -> "Android 16"; else -> "API $sdk"
    }
}
