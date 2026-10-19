package jp.androplus.fakecheck

import android.content.Context

/** [label] は表示名の文字列リソース、[color] はバッジ背景、[darkText] はダークテーマで文字色に使う明るめの色 */
enum class Status(val label: Int, val color: Int, val darkText: Int) {
    OK(R.string.status_ok, 0xFF2E7D32.toInt(), 0xFF81C784.toInt()),
    WARN(R.string.status_warn, 0xFFEF6C00.toInt(), 0xFFFFB74D.toInt()),
    NG(R.string.status_ng, 0xFFC62828.toInt(), 0xFFEF9A9A.toInt()),
    INFO(R.string.status_info, 0xFF546E7A.toInt(), 0xFFB0BEC5.toInt()),
}

data class Item(
    val label: String,
    val value: String,
    val status: Status = Status.INFO,
    val note: String? = null,
)

data class Section(val title: String, val items: List<Item>) {
    fun worst(): Status = when {
        items.any { it.status == Status.NG } -> Status.NG
        items.any { it.status == Status.WARN } -> Status.WARN
        items.any { it.status == Status.OK } -> Status.OK
        else -> Status.INFO
    }

    fun toText(ctx: Context): String = buildString {
        append("■ ").append(title).append('\n')
        for (i in items) {
            append("  [").append(ctx.getString(i.status.label)).append("] ").append(i.label).append(": ").append(i.value).append('\n')
            i.note?.let { append("      ").append(it.replace("\n", "\n      ")).append('\n') }
        }
    }
}
