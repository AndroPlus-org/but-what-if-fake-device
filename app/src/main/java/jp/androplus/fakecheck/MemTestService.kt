package jp.androplus.fakecheck

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.MemoryFile
import android.os.SharedMemory
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import kotlin.concurrent.thread

/**
 * 別プロセス(:memtest)で共有メモリを確保し、ランダムデータを書き込んで物理ページを実際に使わせる。
 * Java ヒープ上限の影響を受けないよう ashmem / memfd を使う。
 * 圧縮メモリ(zram)で縮まないよう中身は乱数にしている。
 * 進捗は filesDir/[PROGRESS_FILE] に "確保バイト数 状態" の形で書き出す。
 */
class MemTestService : Service() {

    companion object {
        const val EXTRA_LIMIT = "limit"
        const val PROGRESS_FILE = "memtest_progress.txt"
        private const val CHUNK = 64 shl 20
        private const val BUF = 1 shl 20
    }

    @Volatile private var stop = false
    private var worker: Thread? = null
    private val chunks = ArrayList<Closeable>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null) {
            val limit = intent?.getLongExtra(EXTRA_LIMIT, Long.MAX_VALUE) ?: Long.MAX_VALUE
            val out = File(filesDir, PROGRESS_FILE)
            worker = thread(name = "memtest") { fill(limit, out) }
        }
        // 低メモリで kill されても再起動させない
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    private fun report(out: File, total: Long, state: String) {
        try {
            val tmp = File(out.path + ".tmp")
            tmp.writeText("$total $state")
            tmp.renameTo(out)
        } catch (e: Exception) {
            // 書けなくても続行
        }
    }

    private fun fill(limit: Long, out: File) {
        val buf = ByteArray(BUF).also { Random().nextBytes(it) }
        var total = 0L
        var counter = 0L
        report(out, 0, "RUN")
        try {
            while (!stop && total < limit) {
                val chunk = allocChunk()
                var off = 0
                while (off < CHUNK && !stop) {
                    // 各4KBページの先頭を毎回変え、同一ページの重複排除でまとめられないようにする
                    var p = 0
                    while (p < BUF) {
                        val c = counter++
                        for (b in 0 until 8) buf[p + b] = (c ushr (b * 8)).toByte()
                        p += 4096
                    }
                    chunk.write(buf, off)
                    off += BUF
                }
                chunk.finish()
                chunks += chunk
                total += CHUNK
                report(out, total, "RUN")
            }
            report(out, total, if (stop) "STOPPED" else "LIMIT")
        } catch (t: Throwable) {
            report(out, total, "ERROR " + (t.message ?: t.javaClass.simpleName).replace('\n', ' '))
        }
    }

    private interface Chunk : Closeable {
        fun write(src: ByteArray, offset: Int)
        fun finish()
    }

    private fun allocChunk(): Chunk {
        if (Build.VERSION.SDK_INT >= 27) {
            val shm = SharedMemory.create("memtest", CHUNK)
            var map: ByteBuffer? = shm.mapReadWrite()
            return object : Chunk {
                override fun write(src: ByteArray, offset: Int) {
                    map!!.position(offset)
                    map!!.put(src)
                }

                override fun finish() {
                    // 書き込んだページは fd が開いている限り残る。32bit 端末でアドレス空間を使い切らないよう unmap しておく
                    map?.let { SharedMemory.unmap(it) }
                    map = null
                }

                override fun close() {
                    map?.let { SharedMemory.unmap(it) }
                    shm.close()
                }
            }
        }
        @Suppress("DEPRECATION")
        val mf = MemoryFile("memtest", CHUNK)
        return object : Chunk {
            override fun write(src: ByteArray, offset: Int) = mf.writeBytes(src, 0, offset, src.size)
            override fun finish() {}
            override fun close() = mf.close()
        }
    }

    override fun onDestroy() {
        stop = true
        worker?.join(2000)
        chunks.forEach { try { it.close() } catch (e: Exception) {} }
        chunks.clear()
        super.onDestroy()
        // 確保したメモリを確実に返すためプロセスごと終了する
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
