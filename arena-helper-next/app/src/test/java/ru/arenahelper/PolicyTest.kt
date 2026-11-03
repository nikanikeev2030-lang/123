package ru.arenahelper
import org.junit.Assert.*
import org.junit.Test
class PolicyTest {
 @Test fun firstFourteenUseStrength() {for(count in 0..13)assertEquals(60,ScoringPolicy.total(60,100,count))}
 @Test fun lastSixteenUseSeventyThirty() {for(count in 14..29)assertEquals(72,ScoringPolicy.total(60,100,count))}
 @Test fun unknownCurveIsNotFifty() {assertEquals(60,ScoringPolicy.total(60,null,20));assertNull(ScoringPolicy.total(null,100,20))}
 @Test fun longNamesWithOcrErrors() {
  val m=CardMatcher(listOf(Card("Первозданная смотрительница",80),Card("Темпоральный путешественник",70),Card("Мгновенная мультивселенная",90)))
  assertEquals("Первозданная смотрительница",m.matchLong("Перво зланная смотри! СЛЬННи.")?.card?.name)
  assertEquals("Темпоральный путешественник",m.matchLong("Темпоральный пуле ТУ т сННик")?.card?.name)
  assertNull(m.matchLong(""));assertNull(m.matchLong("Выберите карту для вашей колоды"))
 }
 @Test fun incompleteDeckUsesExplicitEstimate() {
  assertNotNull(DraftCatalog.curveScore(3,listOf(2,-1,4)))
  assertEquals(2,DraftCatalog.knownCosts(listOf(2,-1,4)))
  assertNull(DraftCatalog.curveScore(-1,listOf(2,4)))
  for(cost in 0..10) assertTrue(DraftCatalog.curveScore(cost,List(20){-1})!! in 10..100)
  assertTrue(DraftCatalog.curveScore(3,List(14){3})!! < DraftCatalog.curveScore(3,List(14){2})!!)
  assertEquals(100,DraftCatalog.curveScore(3,emptyList()))
  val curve=DraftCatalog.curveScore(3,List(14){-1})!!
  assertEquals(kotlin.math.round(60*.7+curve*.3).toInt(),ScoringPolicy.total(60,curve,14))
 }
}
