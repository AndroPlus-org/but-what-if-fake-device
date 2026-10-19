package jp.androplus.fakecheck

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku

/**
 * Shizuku 経由で ShellService を起動し、shell 権限でコマンドを実行する窓口。
 * Shizuku が無い・許可されていない端末では何もしない (アプリの他の機能はそのまま動く)。
 */
object ShizukuShell {

    enum class State { UNAVAILABLE, OLD_VERSION, NO_PERMISSION, READY }

    private const val REQUEST_CODE = 4231

    @Volatile private var binder: IBinder? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var conn: ServiceConnection? = null

    val isConnected: Boolean get() = binder?.pingBinder() == true

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() -> State.UNAVAILABLE
            Shizuku.isPreV11() -> State.OLD_VERSION
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.NO_PERMISSION
        }
    } catch (t: Throwable) {
        State.UNAVAILABLE
    }

    /** 0 = root, 2000 = shell */
    fun uid(): Int = try { Shizuku.getUid() } catch (t: Throwable) { -1 }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** ShellService に接続する。接続済みならすぐ onReady を呼ぶ。 */
    fun bind(ctx: Context, onReady: (Boolean) -> Unit) {
        if (isConnected) {
            onReady(true)
            return
        }
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service?.takeIf { it.pingBinder() }
                onReady(binder != null)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
        @Suppress("DEPRECATION")
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        } catch (e: Exception) {
            1
        }
        val a = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .version(version)
        args = a
        conn = c
        try {
            Shizuku.bindUserService(a, c)
        } catch (t: Throwable) {
            onReady(false)
        }
    }

    /** shell プロセスを終了して接続を切る */
    fun unbind() {
        val a = args ?: return
        val c = conn ?: return
        try {
            Shizuku.unbindUserService(a, c, true)
        } catch (t: Throwable) {
            // Shizuku 側が先に終了している
        }
        binder = null
        args = null
        conn = null
    }

    private fun call(code: Int, arg: String): String? {
        val b = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR)
            data.writeString(arg)
            b.transact(code, data, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** shell 権限でコマンドを実行する (時間がかかるのでメインスレッドで呼ばない) */
    fun exec(cmd: String): String? = call(ShellService.TX_EXEC, cmd)

    /** shell 権限でファイルを読む。プロセスを起動しないので短時間で返る */
    fun read(path: String): String? = call(ShellService.TX_READ, path)?.takeIf { it.isNotEmpty() }

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
