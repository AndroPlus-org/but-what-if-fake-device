package jp.androplus.fakecheck.checks

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import jp.androplus.fakecheck.Item
import jp.androplus.fakecheck.R
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
            items += Item(ctx.getString(R.string.cam_section), ctx.getString(R.string.cam_not_found), Status.INFO)
            return Section(ctx.getString(R.string.cam_section), items)
        }
        for (id in ids) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_FRONT -> ctx.getString(R.string.cam_front)
                    CameraMetadata.LENS_FACING_BACK -> ctx.getString(R.string.cam_back)
                    else -> ctx.getString(R.string.cam_external)
                }
                val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val jpeg = buildList {
                    map?.getOutputSizes(ImageFormat.JPEG)?.let { addAll(it) }
                    if (Build.VERSION.SDK_INT >= 23) map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.let { addAll(it) }
                }.maxByOrNull { it.width.toLong() * it.height }

                // 高画素センサー(クアッドベイヤー等)は最大解像度モード用の配列を別に持つので、大きい方を採る
                var sensorMp = maxOf(
                    if (active != null) active.width().toDouble() * active.height() / 1e6 else 0.0,
                    mp(pixelArray)
                )
                if (Build.VERSION.SDK_INT >= 31) {
                    c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.let { sensorMp = maxOf(sensorMp, mp(it)) }
                    c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)?.let {
                        sensorMp = maxOf(sensorMp, it.width().toDouble() * it.height() / 1e6)
                    }
                }
                // 複数の物理カメラを束ねた論理カメラは、別センサーの解像度で出力することがあるので判定しない
                val logical = Build.VERSION.SDK_INT >= 28 &&
                    c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                val jpegMp = mp(jpeg)
                val hwLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    3 -> "LEVEL_3"
                    4 -> "EXTERNAL"
                    else -> "?"
                }
                val value = ctx.getString(
                    R.string.cam_value,
                    Sys.f1(sensorMp), pixelArray?.let { " (${it.width}×${it.height})" } ?: "",
                    Sys.f1(jpegMp), jpeg?.let { " (${it.width}×${it.height})" } ?: "",
                    hwLevel, if (logical) " / " + ctx.getString(R.string.cam_logical) else ""
                )
                // LEGACY HAL ではセンサー情報自体が出力サイズから作られていることがあるので判定しない
                val interpolated = sensorMp > 0 && jpegMp > sensorMp * 1.1 && hwLevel != "LEGACY" && !logical
                items += Item(
                    ctx.getString(R.string.cam_item_label, facing, id),
                    value,
                    if (interpolated) Status.NG else Status.INFO,
                    if (interpolated) ctx.getString(R.string.cam_interp_note, Sys.f1(jpegMp))
                    else null
                )
            } catch (e: Exception) {
                items += Item(ctx.getString(R.string.cam_error_label, id), ctx.getString(R.string.common_failed, e.javaClass.simpleName), Status.WARN)
            }
        }
        items += Item(
            ctx.getString(R.string.common_how_to_read),
            ctx.getString(R.string.cam_howto_value),
            Status.INFO,
            ctx.getString(R.string.cam_howto_note)
        )
        return Section(ctx.getString(R.string.cam_section), items)
    }
}
