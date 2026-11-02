package ru.arenahelper
import org.junit.Assert.*
import org.junit.Test
class DraftEngineTest {
 private fun card(score:Int?=80,cost:Int?=2,cls:String="neutral")=Card("Карта",score,cost,cls)
 @Test fun wrongSelectedHeroStillGetsClassCardRating() {
  val c=Card("Посох Примаса",null,1,"death-knight",false,mapOf("death-knight" to 108))
  assertEquals(108,DraftEngine.resolve(c,"mage").score)
  assertEquals("death-knight",DraftEngine.inferClass(listOf(c,card())))
 }
 @Test fun doesNotGuessFromConflictingClasses() {
  assertNull(DraftEngine.inferClass(listOf(card(cls="mage"),card(cls="priest"))))
 }
 @Test fun missingNeutralRatingIsNotInvented() {
  assertNull(DraftEngine.resolve(Card("Нейтральная",null,2,"neutral",false,mapOf("mage" to 70)),"priest").score)
 }
 @Test fun groupIncludesAllFourCards() {
  val result=DraftEngine.evaluate(listOf(card(100),card(40),card(60),card(80)),emptyList(),true)!!
  assertEquals(70.0,result.total,0.001);assertNull(result.curve)
 }
 @Test fun noGroupScoreIfAnyCardIsUnknown() {
  assertNull(DraftEngine.evaluate(listOf(card(),card(null)),emptyList(),true))
 }
 @Test fun underfilledCurveBeatsOversaturatedAtEqualStrength() {
  val deck=List(6){card(cost=2)}
  val two=DraftEngine.evaluate(listOf(card(cost=2)),deck,true)!!
  val three=DraftEngine.evaluate(listOf(card(cost=3)),deck,true)!!
  assertEquals(56.0,two.total,0.001);assertEquals(86.0,three.total,0.001)
 }
 @Test fun missingManaDisablesCurveInsteadOfGuessing() {
  assertNull(DraftEngine.evaluate(listOf(card()),listOf(card(cost=null)),true)!!.curve)
 }
 @Test fun rawModePreservesRatingsAboveHundred() {
  assertEquals(108.0,DraftEngine.evaluate(listOf(card(108)),listOf(card()),false)!!.total,0.001)
 }
}
