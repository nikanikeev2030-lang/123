package ru.arenahelper
import org.junit.Assert.*
import org.junit.Test
class CardMatcherTest {
 private val matcher=CardMatcher(listOf(Card("Огненный шар",81),Card("Ледяная стрела",70),Card("Неизвестный страж",null)))
 @Test fun exactAndPunctuation() { assertEquals(81,matcher.match("ОГНЕННЫЙ\nШАР!")?.card?.score) }
 @Test fun oneOcrError() { assertEquals("Огненный шар",matcher.match("Огненный шао")?.card?.name) }
 @Test fun rejectsGarbageAndShortText() { assertNull(matcher.match("Выберите карту")); assertNull(matcher.match("шар")); assertNull(matcher.match("")) }
 @Test fun unknownScoreRemainsUnknown() { assertNull(matcher.match("Неизвестный страж")!!.card.score) }
 @Test fun rejectsAmbiguousNames() { val m=CardMatcher(listOf(Card("Злобный страж",1),Card("Злобный страх",2))); assertNull(m.match("Злобный страп")) }
 @Test fun normalizesYo() { assertEquals("елка",CardMatcher.normalize("Ёлка!")) }
}
