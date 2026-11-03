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
 @Test fun incompleteDeckDoesNotInventMana() {assertNull(DraftCatalog.curveScore(3,listOf(2,-1,4)))}
}
