package jp.androplus.fakecheck

enum class Status(val label: String, val color: Int) {
    OK("OK", 0xFF2E7D32.toInt()),
    WARN("注意", 0xFFEF6C00.toInt()),
    NG("疑い", 0xFFC62828.toInt()),
    INFO("情報", 0xFF546E7A.toInt()),
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

    fun toText(): String = buildString {
        append("■ ").append(title).append('\n')
        for (i in items) {
            append("  [").append(i.status.label).append("] ").append(i.label).append(": ").append(i.value).append('\n')
            i.note?.let { append("      ").append(it.replace("\n", "\n      ")).append('\n') }
        }
    }
}
