package jp.androplus.fakecheck

import android.content.Context
import android.os.Binder
import android.os.Parcel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Shizuku の UserService として shell (または root) 権限の別プロセスで動く。
 * AIDL を使わず、トランザクション番号で「コマンド実行」と「ファイル読み取り」を受け付ける。
 */
class ShellService() : Binder() {

    // Shizuku v13 以降は Context 付きコンストラクタを優先して呼ぶ
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    init {
        // Shizuku は destroy 送信時に getInterfaceDescriptor() を使う
        attachInterface(null, DESCRIPTOR)
    }

    companion object {
        const val DESCRIPTOR = "jp.androplus.fakecheck.ShellService"
        const val TX_EXEC = FIRST_CALL_TRANSACTION
        const val TX_READ = FIRST_CALL_TRANSACTION + 1

        /** Shizuku が unbindUserService(remove=true) 時に送る destroy (ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy = 16777115) */
        const val TX_DESTROY = FIRST_CALL_TRANSACTION + 16777114

        private const val MAX_OUTPUT = 512 * 1024
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TX_DESTROY -> {
                System.exit(0)
                return true
            }
            TX_EXEC -> {
                data.enforceInterface(DESCRIPTOR)
                val out = exec(data.readString().orEmpty())
                reply?.writeNoException()
                reply?.writeString(out)
                return true
            }
            TX_READ -> {
                data.enforceInterface(DESCRIPTOR)
                val out = try {
                    File(data.readString().orEmpty()).readText().trim()
                } catch (e: Exception) {
                    null
                }
                reply?.writeNoException()
                reply?.writeString(out)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    private fun exec(cmd: String): String? = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val buf = StringBuilder()
        val reader = thread {
            p.inputStream.bufferedReader().use { r ->
                val chars = CharArray(8192)
                while (true) {
                    val n = r.read(chars)
                    if (n < 0) break
                    if (buf.length < MAX_OUTPUT) buf.append(chars, 0, minOf(n, MAX_OUTPUT - buf.length))
                }
            }
        }
        if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroy()
        reader.join(2000)
        buf.toString().trim()
    } catch (e: Exception) {
        null
    }
}
