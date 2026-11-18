package ru.arenahelper
import org.junit.Assert.*
import org.junit.Test
class HeroSelectionTest {
 @Test fun unknownSkinCanBeComparedWithoutPortrait() {
  val s=HeroSelection();s.begin();s.set(1,"mage")
  assertEquals(listOf("priest","mage",null),s.resolve(listOf("priest",null,null)))
  assertTrue(s.active)
 }
 @Test fun correctionSurvivesAutomaticMisclassificationButNotNextDraft() {
  val s=HeroSelection();s.set(0,"mage")
  assertEquals("mage",s.resolve(listOf("warrior"))[0])
  s.clear();assertFalse(s.active);assertEquals("warrior",s.resolve(listOf("warrior"))[0])
 }
 @Test fun allUnknownIsSafeAndNeverInventsAClass() {
  assertEquals(listOf(null,null,null),HeroSelection().resolve(emptyList()))
 }
}
