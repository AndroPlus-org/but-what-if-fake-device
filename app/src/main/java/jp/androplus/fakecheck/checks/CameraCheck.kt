package jp.androplus.fakecheck.checks

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.Section
import jp.androplus.fakecheck.Status
import jp.androplus.fakecheck.Sys

object CameraCheck {

    private fun mp(s: Size?): Double = if (s == null) 0.0 else s.width.toDouble() * s.height / 1e6

    fun run(ctx: Context): Section {
        val items = ArrayList<Item>()
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = try { cm.cameraIdList.toList() } catch (e: Exception) { emptyList() }
        if (ids.isEmpty()) {
            items += Item("カメラ", "検出されません", Status.INFO)
            return Section("カメラ", items)
        }
        for (id in ids) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_FRONT -> "前面"
                    CameraMetadata.LENS_FACING_BACK -> "背面"
                    else -> "外部"
                }
                val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpeg = buildList {
                    map?.getOutputSizes(ImageFormat.JPEG)?.let { addAll(it) }
                    if (Build.VERSION.SDK_INT >= 23) map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.let { addAll(it) }
                }.maxByOrNull { it.width.toLong() * it.height }

                val sensorMp = if (active != null) active.width().toDouble() * active.height() / 1e6 else mp(pixelArray)
                val jpegMp = mp(jpeg)
                val hwLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    3 -> "LEVEL_3"
                    4 -> "EXTERNAL"
                    else -> "?"
                }
                val value = "センサー ${Sys.f1(sensorMp)}MP" +
                    (pixelArray?.let { " (${it.width}×${it.height})" } ?: "") +
                    " / 最大出力 ${Sys.f1(jpegMp)}MP" + (jpeg?.let { " (${it.width}×${it.height})" } ?: "") +
                    " / HW $hwLevel"
                // LEGACY HAL ではセンサー情報自体が出力サイズから作られていることがあるので判定しない
                val interpolated = sensorMp > 0 && jpegMp > sensorMp * 1.1 && hwLevel != "LEGACY"
                items += Item(
                    "$facing カメラ (id $id)",
                    value,
                    if (interpolated) Status.NG else Status.INFO,
                    if (interpolated) "センサーの画素数より大きいサイズで出力しています。画素補間で「${Sys.f1(jpegMp)}MP」を名乗っている可能性があります。"
                    else null
                )
            } catch (e: Exception) {
                items += Item("カメラ id $id", "取得失敗: ${e.javaClass.simpleName}", Status.WARN)
            }
        }
        items += Item(
            "見方",
            "仕様表の画素数とセンサー画素数を比べてください。",
            Status.INFO,
            "4画素を1画素にまとめるセンサー(クアッドベイヤー)では、通常の出力が公称の1/4になるのは正常です。"
        )
        return Section("カメラ", items)
    }
}
