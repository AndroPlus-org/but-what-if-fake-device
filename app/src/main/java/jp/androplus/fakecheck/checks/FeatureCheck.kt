package jp.androplus.fakecheck.checks

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.MediaDrm
import android.os.Build
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import java.util.UUID

object FeatureCheck {

    private val FEATURES = listOf(
        PackageManager.FEATURE_LOCATION_GPS to "GPS",
        PackageManager.FEATURE_NFC to "NFC",
        PackageManager.FEATURE_BLUETOOTH_LE to "Bluetooth LE",
        PackageManager.FEATURE_WIFI to "Wi-Fi",
        "android.hardware.wifi.direct" to "Wi-Fi Direct",
        PackageManager.FEATURE_TELEPHONY to "モバイル通信 (SIM)",
        PackageManager.FEATURE_FINGERPRINT to "指紋センサー",
        PackageManager.FEATURE_USB_HOST to "USB ホスト (OTG)",
        PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND to "5点以上マルチタッチ",
    )

    private val SENSORS = listOf(
        Sensor.TYPE_ACCELEROMETER to "加速度",
        Sensor.TYPE_GYROSCOPE to "ジャイロ",
        Sensor.TYPE_MAGNETIC_FIELD to "地磁気 (コンパス)",
        Sensor.TYPE_LIGHT to "照度",
        Sensor.TYPE_PROXIMITY to "近接",
    )

    fun run(ctx: Context): Section {
        val items = ArrayList<Item>()
        val pm = ctx.packageManager
        val have = FEATURES.filter { pm.hasSystemFeature(it.first) }.map { it.second }
        val lack = FEATURES.filterNot { pm.hasSystemFeature(it.first) }.map { it.second }
        items += Item("対応機能", have.joinToString(", ").ifEmpty { "なし" })
        items += Item("非対応機能", lack.joinToString(", ").ifEmpty { "なし" })

        items += widevine()

        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensorText = SENSORS.joinToString("\n") { (type, name) ->
            val s = sm.getDefaultSensor(type)
            "$name: " + (s?.let { "${it.name} (${it.vendor})" } ?: "なし")
        }
        items += Item(
            "主要センサー",
            sensorText,
            Status.INFO,
            "仕様表に書かれているセンサーが「なし」になっていないか確認してください。"
        )
        return Section("機能 / センサー", items)
    }

    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    /** Widevine はシステム機能フラグではなく MediaDrm で確認する */
    private fun widevine(): Item {
        if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) {
            return Item("Widevine DRM", "非対応", Status.WARN, "動画配信サービスで再生できない、または低画質になります。")
        }
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE_UUID)
            val level = drm.getPropertyString("securityLevel")
            val hdcp = if (Build.VERSION.SDK_INT >= 28) {
                when (drm.maxHdcpLevel) {
                    MediaDrm.HDCP_NONE -> "なし"
                    MediaDrm.HDCP_V1 -> "1.x"
                    MediaDrm.HDCP_V2 -> "2.0"
                    MediaDrm.HDCP_V2_1 -> "2.1"
                    MediaDrm.HDCP_V2_2 -> "2.2"
                    MediaDrm.HDCP_V2_3 -> "2.3"
                    MediaDrm.HDCP_NO_DIGITAL_OUTPUT -> "デジタル出力なし"
                    else -> "不明"
                }
            } else null
            val version = runCatching { drm.getPropertyString("version") }.getOrNull()
            Item(
                "Widevine DRM",
                "対応 ($level)" + (version?.let { " / version $it" } ?: "") + (hdcp?.let { " / 最大HDCP $it" } ?: ""),
                if (level == "L1") Status.OK else Status.INFO,
                if (level == "L1") null else "L1 でない場合、動画配信サービスの HD / 4K 再生ができないことがあります。仕様表の「Widevine L1」と比べてください。"
            )
        } catch (e: Exception) {
            Item("Widevine DRM", "対応 (詳細取得失敗: ${e.javaClass.simpleName})")
        } finally {
            if (Build.VERSION.SDK_INT >= 28) drm?.close() else @Suppress("DEPRECATION") drm?.release()
        }
    }
}
