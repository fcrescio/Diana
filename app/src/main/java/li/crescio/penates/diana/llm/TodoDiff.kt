package li.crescio.penates.diana.llm

import java.util.Locale

object TodoDiff {
    fun diff(before: List<TodoItem>, after: List<TodoItem>): List<TodoAction> {
        if (before.isEmpty() && after.isEmpty()) return emptyList()
        val beforeByKey = keyedItems(before)
        val afterByKey = keyedItems(after)
        val actions = mutableListOf<TodoAction>()
        for (item in after) {
            val key = stableKey(item)
            val prior = beforeByKey[key]
            if (prior == null) {
                actions.add(TodoAction(op = "add", before = null, after = item))
            } else if (prior != item) {
                actions.add(TodoAction(op = "update", before = prior, after = item))
            }
        }
        for (item in before) {
            val key = stableKey(item)
            if (!afterByKey.containsKey(key)) {
                actions.add(TodoAction(op = "delete", before = item, after = null))
            }
        }
        return actions
    }

    fun stableKey(item: TodoItem): String {
        val trimmedId = item.id.trim()
        if (trimmedId.isNotEmpty()) {
            return trimmedId
        }
        return normalizeText(item.text)
    }

    private fun keyedItems(items: List<TodoItem>): LinkedHashMap<String, TodoItem> {
        val keyed = LinkedHashMap<String, TodoItem>()
        for (item in items) {
            val key = stableKey(item)
            if (!keyed.containsKey(key)) {
                keyed[key] = item
            }
        }
        return keyed
    }

    private fun normalizeText(text: String): String {
        return text.trim()
            .lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), " ")
    }
}
