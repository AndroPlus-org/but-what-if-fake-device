package jp.androplus.fakecheck.checks

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status

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
        "android.software.widevine" to "Widevine",
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
}
