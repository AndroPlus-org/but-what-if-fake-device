package jp.androplus.fakecheck

import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * su (Magisk / KernelSU / APatch など) で root シェルを1本起動し、使い回す。
 * コマンドごとに su を起動すると root 管理アプリの通知やログが大量に出るため、
 * 標準入力にコマンドを流し込み、終端マーカーで出力を区切る。
 */
object RootShell : PrivShell {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/vendor/bin/su",
    )
    private const val MAX_OUTPUT = 512 * 1024
    private const val TIMEOUT_SEC = 20L
    private val EOF = Any()

    private var proc: Process? = null
    private var writer: OutputStreamWriter? = null
    private var lines: LinkedBlockingQueue<Any>? = null

    /** su バイナリがありそうか (許可を求めずに確認できる範囲) */
    fun suExists(): Boolean =
        SU_PATHS.any { File(it).exists() } ||
            (System.getenv("PATH") ?: "").split(':').any { it.isNotEmpty() && File(it, "su").exists() }

    val isConnected: Boolean
        @Synchronized get() = proc?.let { isAlive(it) } == true

    /**
     * root シェルを起動して uid 0 になれたか確かめる。初回は root 管理アプリの許可ダイアログが出る。
     * ダイアログの応答待ちがあるので時間がかかる。メインスレッドで呼ばない。
     */
    @Synchronized
    fun open(): Boolean {
        if (isConnected) return true
        close()
        // PATH 上の su を優先し、見つからなければ既知の場所を順に試す
        val p = (listOf("su") + SU_PATHS.filter { File(it).exists() }).firstNotNullOfOrNull { bin ->
            try {
                ProcessBuilder(bin).redirectErrorStream(true).start()
            } catch (e: IOException) {
                null
            }
        } ?: return false
        val q = LinkedBlockingQueue<Any>()
        thread(name = "su-reader", isDaemon = true) {
            try {
                p.inputStream.bufferedReader().forEachLine { q.put(it) }
            } catch (e: IOException) {
                // シェル終了
            }
            q.put(EOF)
        }
        proc = p
        writer = OutputStreamWriter(p.outputStream)
        lines = q
        // 許可ダイアログの応答を待つため、最初のコマンドだけ長めに待つ
        val id = run("id -u", 60)
        if (id?.trim() != "0") {
            close()
            return false
        }
        return true
    }

    @Synchronized
    fun close() {
        try {
            writer?.apply { write("exit\n"); flush() }
        } catch (e: IOException) {
            // 既に終了している
        }
        proc?.destroy()
        proc = null
        writer = null
        lines = null
    }

    @Synchronized
    private fun run(cmd: String, timeoutSec: Long): String? {
        val w = writer ?: return null
        val q = lines ?: return null
        val marker = "__FAKECHECK_END_${System.nanoTime()}__"
        try {
            // サブシェルにしておくと、コマンド内の exit や cd が後のコマンドに影響しない
            w.write("( $cmd ) </dev/null 2>&1; echo; echo $marker\n")
            w.flush()
        } catch (e: IOException) {
            close()
            return null
        }
        val out = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
        while (true) {
            val left = deadline - System.nanoTime()
            val line = if (left > 0) q.poll(left, TimeUnit.NANOSECONDS) else null
            when {
                line == null -> {
                    // 応答が無い。シェルを作り直さないと後のコマンドの出力が混ざる
                    close()
                    return out.toString().trim().ifEmpty { null }
                }
                line === EOF -> {
                    close()
                    return out.toString().trim().ifEmpty { null }
                }
                line == marker -> return out.toString().trim()
                out.length < MAX_OUTPUT -> out.append(line as String).append('\n')
            }
        }
    }

    override fun exec(cmd: String): String? = run(cmd, TIMEOUT_SEC)

    override fun read(path: String): String? =
        run("cat ${quote(path)} 2>/dev/null", TIMEOUT_SEC)?.trim()?.takeIf { it.isNotEmpty() }

    override fun uid(): Int = if (isConnected) 0 else -1

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue()
        false
    } catch (e: IllegalThreadStateException) {
        true
    }
}
