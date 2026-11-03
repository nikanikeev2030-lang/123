package ru.arenahelper
import kotlin.math.roundToInt
object ScoringPolicy {
 @JvmStatic fun total(strength:Int?,curve:Int?,deckCount:Int):Int? = when {
  strength==null -> null
  deckCount<14 || curve==null -> strength
  else -> (strength*0.7+curve*0.3).roundToInt()
 }
}
