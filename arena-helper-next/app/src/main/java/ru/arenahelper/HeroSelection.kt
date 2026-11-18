package ru.arenahelper

/** Per-draft corrections never train the shared database or survive the next offer. */
class HeroSelection {
    var active = false
        private set
    private val chosen = arrayOfNulls<String>(3)
    fun begin() { active = true }
    fun set(slot: Int, id: String) {
        require(slot in 0..2 && id.isNotBlank())
        active = true
        // Arena offers distinct classes; don't leave the same manual class twice.
        for (i in 0..2) if (chosen[i] == id) chosen[i] = null
        chosen[slot] = id
    }
    fun resolve(automatic: List<String?>): List<String?> = (0..2).map { chosen[it] ?: automatic.getOrNull(it) }
    fun clear() { active = false; chosen.fill(null) }
}
