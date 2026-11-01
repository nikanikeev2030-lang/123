package ru.arenahelper
import java.util.Locale

data class Card(val name: String, val score: Int?)
data class Match(val card: Card, val confidence: Double)
class CardMatcher(cards: List<Card>) {
 companion object {
  fun normalize(s: String) = s.lowercase(Locale.ROOT).replace('ё','е').filter { it in 'а'..'я' || it.isDigit() }
  fun distance(a: String,b: String): Int {
   var row=IntArray(b.length+1) { it }
   for(i in a.indices) {
    val next=IntArray(b.length+1); next[0]=i+1
    for(j in b.indices) next[j+1]=minOf(next[j]+1,row[j+1]+1,row[j]+if(a[i]==b[j]) 0 else 1)
    row=next
   }
   return row.last()
  }
 }
 private val entries=cards.map { it to normalize(it.name) }
 fun match(text: String): Match? {
  val s=normalize(text)
  if(s.length<4) return null
  val ranked=entries.mapNotNull { (c,n) ->
   if(kotlin.math.abs(n.length-s.length)>maxOf(2,n.length/5)) null
   else Match(c,1.0-distance(s,n).toDouble()/maxOf(s.length,n.length))
  }.sortedByDescending { it.confidence }
  val first=ranked.firstOrNull() ?: return null
  if(first.confidence<0.84) return null
  if(ranked.size>1 && first.confidence-ranked[1].confidence<0.07) return null
  return first
 }
}
