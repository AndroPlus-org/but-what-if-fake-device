package jp.androplus.fakecheck

/**
 * shell / root 権限でコマンド実行とファイル読み取りを行う窓口。
 * Shizuku (shell または root) と su (Magisk / KernelSU など) の両方がこれを実装する。
 * どれもプロセス間通信を伴うので、メインスレッドから呼ばない。
 */
interface PrivShell {
    /** コマンドを実行して標準出力と標準エラーをまとめて返す。実行できなければ null */
    fun exec(cmd: String): String?

    /** ファイルを読む。読めない・空なら null */
    fun read(path: String): String?

    /** 0 = root, 2000 = shell, 取得できなければ -1 */
    fun uid(): Int

    val isRoot: Boolean get() = uid() == 0

    /**
     * zram が「RAM拡張」としてストレージに書き出している量。
     * bd_stat の1列目が書き出し済みページ数 (4KiB 単位)。読めない場合は 0。
     */
    fun zramWritebackBytes(): Long {
        var total = 0L
        for (i in 0..3) {
            val s = read("/sys/block/zram$i/bd_stat") ?: continue
            total += (s.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L) * 4096
        }
        return total
    }
}
