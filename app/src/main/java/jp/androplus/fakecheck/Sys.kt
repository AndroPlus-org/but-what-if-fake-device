package jp.androplus.fakecheck

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** /proc や /sys、getprop を読むための小物。読めない環境では null / 空を返す。 */
object Sys {

    fun read(path: String): String? = try {
        File(path).takeIf { it.canRead() }?.readText()?.trim()
    } catch (e: Exception) {
        null
    }

    fun readLong(path: String): Long? = read(path)?.trim()?.toLongOrNull()

    private var propCache: Map<String, String>? = null

    /** getprop の全出力を [key] -> value で返す。 */
    @Synchronized
    fun props(): Map<String, String> {
        propCache?.let { return it }
        val map = LinkedHashMap<String, String>()
        try {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val re = Regex("""^\[(.+?)]: \[(.*)]$""")
            p.inputStream.bufferedReader().forEachLine { line ->
                re.find(line)?.let { map[it.groupValues[1]] = it.groupValues[2] }
            }
            p.waitFor(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // getprop が使えない端末もある
        }
        propCache = map
        return map
    }

    fun prop(key: String): String = props()[key].orEmpty()

    /** /proc/meminfo を kB 単位で返す。 */
    fun meminfo(): Map<String, Long> {
        val map = HashMap<String, Long>()
        read("/proc/meminfo")?.lineSequence()?.forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val v = line.substring(idx + 1).trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()
                if (v != null) map[line.substring(0, idx).trim()] = v
            }
        }
        return map
    }

    /**
     * ストレージ上のスワップ(zram 以外)の使用量。/proc/swaps は KiB 単位。
     * zram は RAM を圧縮して使うだけなので物理RAMの外には出ない。
     */
    fun diskSwapUsedBytes(): Long {
        var used = 0L
        read("/proc/swaps")?.lineSequence()?.drop(1)?.forEach { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size >= 4 && !c[0].contains("zram")) used += (c[3].toLongOrNull() ?: 0L) * 1024
        }
        return used
    }

    fun gb(bytes: Long): String = String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    fun gbDec(bytes: Long): String = String.format(Locale.US, "%.1f GB(10進)", bytes / 1e9)
    fun mb(bytes: Long): String = String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
    fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
    fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)
}
