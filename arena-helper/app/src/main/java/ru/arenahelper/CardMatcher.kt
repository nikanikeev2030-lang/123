package ru.arenahelper

import java.util.Locale
import kotlin.math.abs

data class Card(val name: String, val score: Int?, val cost: Int? = null, val cardClass: String = "neutral", val legendary: Boolean = false, val ratings: Map<String,Int> = emptyMap())
data class Match(val card: Card, val confidence: Double)

/** No unchecked access to OCR lists: blank and one-line OCR are normal inputs. */
class CardMatcher(cards: List<Card>) {
    companion object {
        fun normalize(text: String): String = buildString {
            for (c in text.lowercase(Locale.ROOT).replace('ё', 'е')) {
                val mapped = when (c) {
                    'a' -> 'а'; 'b' -> 'в'; 'c' -> 'с'; 'e' -> 'е'; 'h' -> 'н'
                    'k' -> 'к'; 'm' -> 'м'; 'o' -> 'о'; 'p' -> 'р'; 't' -> 'т'
                    'x' -> 'х'; 'y' -> 'у'; else -> c
                }
                if (mapped in 'а'..'я' || mapped.isDigit()) append(mapped)
            }
        }
        fun distance(a: String, b: String): Int {
            var row = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val next = IntArray(b.length + 1); next[0] = i + 1
                for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
                row = next
            }
            return row.last()
        }
        fun segments(text: String): Set<String> = buildSet {
            fun addText(s: String) { normalize(s).takeIf { it.length >= 4 }?.let { add(it) } }
            addText(text)
            val lines = text.lineSequence().map { normalize(it) }.filter { it.length >= 4 }.take(12).toList()
            lines.forEach { addText(it) }
            lines.zipWithNext().forEach { (a, b) -> addText(a + b) }
        }
    }
    private val entries = cards.groupBy { normalize(it.name) }
        .filterKeys { it.length >= 4 }
        .map { (key, values) -> key to (values.firstOrNull { it.score != null } ?: values.first()) }

    fun match(text: String): Match? {
        val pieces = segments(text)
        if (pieces.isEmpty()) return null
        val ranked = entries.mapNotNull { (name, card) ->
            val confidence = pieces.maxOf { s ->
                if (abs(s.length - name.length) > maxOf(3, name.length / 4)) 0.0
                else 1.0 - distance(s, name).toDouble() / maxOf(s.length, name.length)
            }
            if (confidence < if (name.length < 8) 0.90 else 0.82) null else Match(card, confidence)
        }.sortedWith(compareByDescending<Match> { it.confidence }.thenByDescending { it.card.name.length })
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (second != null && best.confidence - second.confidence < 0.06) return null
        return best
    }
}
