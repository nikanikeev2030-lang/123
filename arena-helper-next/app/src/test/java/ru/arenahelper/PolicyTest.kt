package ru.arenahelper
import org.junit.Assert.*
import org.junit.Test
class PolicyTest {
 @Test fun lastOfferMustBeSelectedAndLeaveTheBoard() {
  val state=LastPick()
  assertFalse(state.observe(29,3,false,true,0));assertFalse(state.observe(29,3,false,true,650))
  for(t in listOf(1300L,1950L,2600L)) assertFalse(state.observe(29,0,false,true,t))
  assertFalse(state.observe(29,3,true,true,3200))
  assertFalse(state.observe(29,0,false,true,3850));assertFalse(state.observe(29,0,false,true,4500))
  assertTrue(state.observe(29,0,false,true,5150))
  assertFalse(state.observe(29,0,false,true,5800))
  for(t in listOf(0L,650L))state.observe(29,3,true,true,t)
  assertFalse(state.observe(29,0,false,false,1300))
  assertFalse(state.observe(29,0,false,true,3000))
  assertFalse(state.observe(28,3,true,true,4000))
 }
 @Test fun firstFourteenUseStrength() {for(count in 0..13)assertEquals(60,ScoringPolicy.total(60,100,count))}
 @Test fun lastSixteenUseSeventyThirty() {for(count in 14..29)assertEquals(72,ScoringPolicy.total(60,100,count))}
 @Test fun unknownCurveIsNotFifty() {assertEquals(60,ScoringPolicy.total(60,null,20));assertNull(ScoringPolicy.total(null,100,20))}
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
