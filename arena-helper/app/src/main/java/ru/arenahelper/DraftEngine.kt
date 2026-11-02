package ru.arenahelper

import kotlin.math.abs

data class Evaluation(val strength: Double, val curve: Double?, val total: Double)
object DraftEngine {
    // Counts for a balanced 30-card deck: 0–1, 2, 3, 4, 5, 6, 7+ mana.
    val target = listOf(4, 6, 6, 5, 3, 3, 3)
    fun bucket(cost: Int) = cost.coerceIn(1,7)-1
    fun inferClass(cards: List<Card>): String? {
        val classes = cards.map { it.cardClass }.filter { it != "neutral" }.distinct()
        return classes.singleOrNull()
    }
    fun resolve(card: Card, hero: String): Card {
        if (card.ratings.isEmpty()) return card
        return card.copy(score = card.ratings[hero] ?: if (card.cardClass != "neutral") card.ratings[card.cardClass] else null)
    }
    fun evaluate(offered: List<Card>, deck: List<Card>, mixed: Boolean): Evaluation? {
        if (offered.isEmpty() || offered.any { it.score == null }) return null
        val strength = offered.map { it.score!!.toDouble() }.average()
        if (!mixed || deck.isEmpty() || (deck + offered).any { it.cost == null }) return Evaluation(strength,null,strength)
        val counts = IntArray(7)
        deck.forEach { counts[bucket(it.cost!!)]++ }
        var fit = 0.0
        offered.forEach { card ->
            val b = bucket(card.cost!!)
            // Remaining slots in the target curve: 0–100, independent of card strength.
            fit += (100.0 * (target[b] - counts[b]) / target[b]).coerceIn(0.0,100.0)
            counts[b]++
        }
        fit /= offered.size
        return Evaluation(strength,fit,0.7*strength+0.3*fit)
    }
}
object Heroes {
    val keys=listOf("death-knight","demon-hunter","druid","hunter","mage","paladin","priest","rogue","shaman","warlock","warrior")
    val names=listOf("Рыцарь смерти","Охотник на демонов","Друид","Охотник","Маг","Паладин","Жрец","Разбойник","Шаман","Чернокнижник","Воин")
    val underground=listOf(48.0,50.2,38.8,40.5,42.2,47.3,48.7,41.5,53.1,55.5,43.7)
    fun name(key:String)=names.getOrElse(keys.indexOf(key)){key}
    const val source="HSReplay.net • Подземная арена • все матчи • снимок 02.11.2026, окно Today. Для обычной Арены эти проценты неприменимы."
}
