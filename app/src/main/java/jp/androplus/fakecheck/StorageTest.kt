package jp.androplus.fakecheck

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.concurrent.thread

/**
 * H2testw / F3 と同じ考え方の容量偽装チェック。
 * 空き領域にファイル位置ごとに異なる疑似乱数データを書き込み、全部書き終えてから読み戻して照合する。
 * 実容量を超えた分が捨てられたり先頭に回り込んで上書きされたりする偽装ストレージでは、データの破損として現れる。
 */
class StorageTest(
    private val ctx: Context,
    private val onProgress: (String, Int) -> Unit,
    private val onDone: (Section) -> Unit,
) {
    companion object {
        private const val BLOCK = 1 shl 20
        private const val FILE_SIZE = 256L shl 20
        private const val MARGIN = 512L shl 20
        private val TEST_FILE = Regex("""chunk_\d{5}\.bin""")

        /** 画面の再生成などで別インスタンスが掃除を走らせても、実行中のテストのファイルは消さない */
        @Volatile private var activeTests = 0

        private fun testDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "h2test")

        /**
         * テスト用ファイルだけを削除する。アプリ専用フォルダ内の h2test/ にある
         * chunk_NNNNN.bin 以外には触れない。中断で残ったファイルの掃除にも使う。
         */
        fun cleanup(ctx: Context) {
            if (activeTests > 0) return
            testDir(ctx).listFiles()?.forEach { f ->
                if (f.isFile && TEST_FILE.matches(f.name)) f.delete()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    /** 書き込み中に止めたら書いた分だけ照合し、照合中に止めたら照合も打ち切る */
    @Volatile private var cancel = false
    @Volatile private var cancelVerify = false
    @Volatile private var verifying = false
    @Volatile var isRunning = false
        private set

    fun dir(): File = testDir(ctx)

    fun freeBytes(): Long {
        val d = dir().apply { mkdirs() }
        val st = StatFs(d.path)
        return st.availableBlocksLong * st.blockSizeLong
    }

    /** 書き込み可能な最大量 (端末を完全に満杯にしないよう余白を残す) */
    fun maxTarget(): Long = maxOf(0L, freeBytes() - MARGIN)

    fun stop() {
        if (verifying) cancelVerify = true else cancel = true
    }

    fun start(targetBytes: Long) {
        cancel = false
        cancelVerify = false
        verifying = false
        isRunning = true
        synchronized(StorageTest::class.java) { activeTests++ }
        thread(name = "storagetest") {
            val section = try {
                runTest(targetBytes)
            } catch (t: Throwable) {
                Section("ストレージ実書き込みテスト結果", listOf(Item("エラー", t.toString(), Status.WARN)))
            } finally {
                synchronized(StorageTest::class.java) { activeTests-- }
                cleanup(ctx)
            }
            handler.post {
                isRunning = false
                onDone(section)
            }
        }
    }

    private fun post(msg: String, pct: Int) = handler.post { onProgress(msg, pct) }

    private fun fill(seed: Long, globalBlock: Long, arr: LongArray) {
        // 位置から一意に決まる splitmix64 系列
        var x = seed + globalBlock * (BLOCK / 8) * -0x61c8864680b583ebL
        for (i in arr.indices) {
            x += -0x61c8864680b583ebL
            var z = x
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            arr[i] = z xor (z ushr 31)
        }
    }

    private fun runTest(targetBytes: Long): Section {
        val d = dir()
        d.mkdirs()
        d.listFiles()?.forEach { f -> if (f.isFile && TEST_FILE.matches(f.name)) f.delete() }
        val seed = Random().nextLong()
        val freeBefore = freeBytes()
        val target = (minOf(targetBytes, maxTarget()) / BLOCK) * BLOCK
        if (target < BLOCK * 16L) {
            return Section("ストレージ実書き込みテスト結果", listOf(Item("エラー", "空き容量が足りません", Status.WARN)))
        }

        val buf = ByteBuffer.allocateDirect(BLOCK).order(ByteOrder.LITTLE_ENDIAN)
        val longs = buf.asLongBuffer()
        val arr = LongArray(BLOCK / 8)
        val files = ArrayList<Pair<File, Long>>()
        var written = 0L
        var writeError: String? = null
        val t0 = SystemClock.elapsedRealtime()
        var lastPost = 0L

        // ---- 書き込み ----
        var fileIndex = 0
        writeLoop@ while (written < target && !cancel) {
            val f = File(d, "chunk_%05d.bin".format(fileIndex++))
            var inFile = 0L
            try {
                FileOutputStream(f).use { fos ->
                    val ch = fos.channel
                    while (inFile < FILE_SIZE && written < target && !cancel) {
                        fill(seed, written / BLOCK, arr)
                        longs.clear(); longs.put(arr)
                        buf.clear()
                        while (buf.hasRemaining()) ch.write(buf)
                        inFile += BLOCK
                        written += BLOCK
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPost > 300) {
                            lastPost = now
                            val speed = written / 1048576.0 / ((now - t0) / 1000.0).coerceAtLeast(0.001)
                            post("書き込み中… ${Sys.gb(written)} / ${Sys.gb(target)}  (${Sys.f1(speed)} MB/s)", (written * 50 / target).toInt())
                        }
                    }
                    // キャッシュではなくストレージ本体に書かせる
                    fos.fd.sync()
                }
            } catch (e: IOException) {
                writeError = e.message ?: e.toString()
                files += f to (minOf(f.length(), inFile) / BLOCK) * BLOCK
                written = files.sumOf { it.second }
                break@writeLoop
            }
            files += f to inFile
        }
        val writeMs = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
        val freeAfterWrite = freeBytes()

        // ---- 読み戻し・照合 ----
        var verified = 0L
        var badBytes = 0L
        var firstBad = -1L
        var readOffset = 0L
        val t1 = SystemClock.elapsedRealtime()
        verifying = true
        for ((f, len) in files) {
            if (cancelVerify) break
            try {
                FileInputStream(f).use { fis ->
                    val ch = fis.channel
                    var pos = 0L
                    while (pos < len && !cancelVerify) {
                        buf.clear()
                        var n = 0
                        while (buf.hasRemaining()) {
                            val r = ch.read(buf)
                            if (r < 0) break
                            n += r
                        }
                        fill(seed, (readOffset + pos) / BLOCK, arr)
                        var ok = n == BLOCK
                        if (ok) {
                            longs.clear()
                            for (i in arr.indices) if (longs.get(i) != arr[i]) { ok = false; break }
                        }
                        if (ok) verified += BLOCK else {
                            badBytes += BLOCK
                            if (firstBad < 0) firstBad = readOffset + pos
                        }
                        pos += BLOCK
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPost > 300) {
                            lastPost = now
                            val done = readOffset + pos
                            val speed = done / 1048576.0 / ((now - t1) / 1000.0).coerceAtLeast(0.001)
                            post("照合中… ${Sys.gb(done)} / ${Sys.gb(written)}  (${Sys.f1(speed)} MB/s)  破損 ${Sys.mb(badBytes)}", 50 + (done * 50 / written.coerceAtLeast(1)).toInt())
                        }
                    }
                }
            } catch (e: IOException) {
                badBytes += len
                if (firstBad < 0) firstBad = readOffset
            }
            readOffset += len
        }
        val readMs = (SystemClock.elapsedRealtime() - t1).coerceAtLeast(1)

        // ---- 結果 ----
        val items = ArrayList<Item>()
        items += Item("テスト前の空き容量", Sys.gb(freeBefore))
        items += Item(
            "書き込んだ量",
            Sys.gb(written) + if (cancel) " (中断)" else "",
            if (writeError != null) Status.WARN else Status.INFO,
            writeError?.let { "書き込み中にエラー: $it" }
        )
        val consumed = freeBefore - freeAfterWrite
        if (written > 1L shl 30 && consumed < written / 4) {
            items += Item(
                "空き容量の減り方",
                "書き込み ${Sys.gb(written)} に対し空きは ${Sys.gb(consumed)} しか減っていない",
                Status.WARN,
                "書いたデータが保存されていない可能性があります。ただし空き容量が減るとシステムが他アプリのキャッシュを削除するため、正常な端末でも起こることがあります。破損データの有無で判断してください。"
            )
        }
        items += Item("照合OK", Sys.gb(verified) + if (cancelVerify) " (照合を中断)" else "")
        items += if (badBytes > 0) {
            Item(
                "破損データ",
                "${Sys.gb(badBytes)} (最初の破損位置 ${Sys.gb(firstBad)} 付近)",
                Status.NG,
                "書き込んだデータが正しく読み戻せませんでした。表示容量が実際より大きい「容量偽装」か、ストレージの故障の疑いが強いです。" +
                    "最初の破損位置あたりが実際の書き込み可能容量の目安です。"
            )
        } else if (written > 0) {
            Item(
                "破損データ",
                "なし",
                Status.OK,
                if (cancel || cancelVerify) "途中までの範囲では問題ありません。全容量を確認するには最後まで実行してください。"
                else "書き込んだ ${Sys.gb(written)} 分はすべて正しく保存されていました。"
            )
        } else {
            Item("破損データ", "照合データなし", Status.INFO)
        }
        items += Item("書き込み速度", "${Sys.f1(written / 1048576.0 / (writeMs / 1000.0))} MB/s")
        items += Item(
            "読み込み速度",
            "${Sys.f1((verified + badBytes) / 1048576.0 / (readMs / 1000.0))} MB/s",
            Status.INFO,
            "書き込み量が少ない場合はRAMキャッシュから読むため実際より速く出ます。"
        )
        items += Item("後片付け", "テスト用ファイルは削除しました")
        return Section("ストレージ実書き込みテスト結果", items)
    }
}
