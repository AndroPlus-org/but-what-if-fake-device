package jp.androplus.fakecheck.checks

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.MediaDrm
import android.os.Build
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.R
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import java.util.UUID

object FeatureCheck {

    private val FEATURES = listOf(
        PackageManager.FEATURE_LOCATION_GPS to R.string.feat_gps,
        PackageManager.FEATURE_NFC to R.string.feat_nfc,
        PackageManager.FEATURE_BLUETOOTH_LE to R.string.feat_ble,
        PackageManager.FEATURE_WIFI to R.string.feat_wifi,
        "android.hardware.wifi.direct" to R.string.feat_wifi_direct,
        PackageManager.FEATURE_TELEPHONY to R.string.feat_telephony,
        PackageManager.FEATURE_FINGERPRINT to R.string.feat_fingerprint,
        PackageManager.FEATURE_USB_HOST to R.string.feat_usb_host,
        PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND to R.string.feat_multitouch,
    )

    private val SENSORS = listOf(
        Sensor.TYPE_ACCELEROMETER to R.string.sensor_accel,
        Sensor.TYPE_GYROSCOPE to R.string.sensor_gyro,
        Sensor.TYPE_MAGNETIC_FIELD to R.string.sensor_mag,
        Sensor.TYPE_LIGHT to R.string.sensor_light,
        Sensor.TYPE_PROXIMITY to R.string.sensor_prox,
    )

    fun run(ctx: Context): Section {
        val items = ArrayList<Item>()
        val pm = ctx.packageManager
        val have = FEATURES.filter { pm.hasSystemFeature(it.first) }.map { ctx.getString(it.second) }
        val lack = FEATURES.filterNot { pm.hasSystemFeature(it.first) }.map { ctx.getString(it.second) }
        val none = ctx.getString(R.string.common_none)
        items += Item(ctx.getString(R.string.feat_supported_label), have.joinToString(", ").ifEmpty { none })
        items += Item(ctx.getString(R.string.feat_unsupported_label), lack.joinToString(", ").ifEmpty { none })

        items += widevine(ctx)

        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensorText = SENSORS.joinToString("\n") { (type, name) ->
            val s = sm.getDefaultSensor(type)
            "${ctx.getString(name)}: " + (s?.let { "${it.name} (${it.vendor})" } ?: none)
        }
        items += Item(
            ctx.getString(R.string.feat_sensors_label),
            sensorText,
            Status.INFO,
            ctx.getString(R.string.feat_sensors_note)
        )
        return Section(ctx.getString(R.string.feat_section), items)
    }

    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    /** Widevine はシステム機能フラグではなく MediaDrm で確認する */
    private fun widevine(ctx: Context): Item {
        if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) {
            return Item("Widevine DRM", ctx.getString(R.string.common_unsupported), Status.WARN, ctx.getString(R.string.wv_unsupported_note))
        }
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE_UUID)
            val level = drm.getPropertyString("securityLevel")
            val hdcp = if (Build.VERSION.SDK_INT >= 28) {
                when (drm.maxHdcpLevel) {
                    MediaDrm.HDCP_NONE -> ctx.getString(R.string.common_none)
                    MediaDrm.HDCP_V1 -> "1.x"
                    MediaDrm.HDCP_V2 -> "2.0"
                    MediaDrm.HDCP_V2_1 -> "2.1"
                    MediaDrm.HDCP_V2_2 -> "2.2"
                    MediaDrm.HDCP_V2_3 -> "2.3"
                    MediaDrm.HDCP_NO_DIGITAL_OUTPUT -> ctx.getString(R.string.wv_no_digital)
                    else -> ctx.getString(R.string.common_unknown)
                }
            } else null
            val version = runCatching { drm.getPropertyString("version") }.getOrNull()
            Item(
                "Widevine DRM",
                ctx.getString(R.string.wv_value, level) + (version?.let { " / version $it" } ?: "") +
                    (hdcp?.let { " / " + ctx.getString(R.string.wv_max_hdcp, it) } ?: ""),
                if (level == "L1") Status.OK else Status.INFO,
                if (level == "L1") null else ctx.getString(R.string.wv_note)
            )
        } catch (e: Exception) {
            Item("Widevine DRM", ctx.getString(R.string.wv_detail_failed, e.javaClass.simpleName))
        } finally {
            if (Build.VERSION.SDK_INT >= 28) drm?.close() else @Suppress("DEPRECATION") drm?.release()
        }
    }
}
