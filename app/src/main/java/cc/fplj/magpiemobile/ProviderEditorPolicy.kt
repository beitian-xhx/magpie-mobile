package cc.fplj.magpiemobile

/** Pure selection policy: discovery is metadata, not permission to rewrite saved picks. */
class ProviderEditorPolicy(
    private val creating: Boolean,
    initialIds: List<String> = emptyList(),
    private val initiallyDisabled: Boolean = false,
) {
    private val selected = initialIds.toMutableSet()
    private var catalog = emptyList<String>()
    private var touched = false
    private var discovered = false
    private var selectAllIntent = creating || (initialIds.isEmpty() && !initiallyDisabled)

    fun applyCatalog(ids: List<String>): List<String> {
        catalog = ids.filter { it.isNotBlank() }.distinct()
        if (selectAllIntent) { selected.clear(); selected.addAll(catalog) }
        else if (creating && discovered) selected.retainAll(catalog.toSet())
        discovered = true
        return selected.toList()
    }
    fun toggle(id: String, checked: Boolean): List<String> {
        touched = true; selectAllIntent = false
        if (checked) selected.add(id) else selected.remove(id)
        return selected.toList()
    }
    fun chooseAll(): List<String> {
        touched = true; selectAllIntent = true; selected.clear(); selected.addAll(catalog)
        return selected.toList()
    }
    fun chooseNone(): List<String> {
        touched = true; selectAllIntent = false; selected.clear(); return emptyList()
    }
    fun manual(ids: List<String>): List<String> {
        touched = true; selectAllIntent = false; selected.clear(); selected.addAll(ids); return selected.toList()
    }
    fun visibleIds(): List<String> = (catalog + selected).distinct()
    fun isAvailable(id: String): Boolean = id in catalog
    fun selectedIds(): List<String> = selected.toList()
    fun editedModels(): List<String>? = if (touched) selected.toList() else null
}
