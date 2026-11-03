package ru.arenahelper
import android.graphics.*
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class RegressionTest {
 @Test fun catalogAndLongNativeOcr() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val catalog=DraftCatalog(ctx);val matcher=CardMatcher(catalog.matcherCards())
  val dir=File(ctx.cacheDir,"test-ocr/tessdata").apply{mkdirs()}
  ctx.assets.open("rus.traineddata").use{input->File(dir,"rus.traineddata").outputStream().use{input.copyTo(it)}}
  val api=TessBaseAPI();assertTrue(api.init(dir.parent,"rus",TessBaseAPI.OEM_LSTM_ONLY))
  try {
   for(name in listOf("Мгновенная мультивселенная","Первозданная смотрительница","Темпоральный путешественник")){
    assertNotNull(catalog.details(name));assertNotNull(catalog.score(name,"shaman"))
    val b=Bitmap.createBitmap(300,54,Bitmap.Config.ARGB_8888)
    Canvas(b).apply{drawColor(Color.BLACK);drawText(name,5f,29f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=16f;typeface=Typeface.create("serif",Typeface.BOLD)})}
    try{assertEquals(name,OcrEngine.recognize(api,b,matcher)?.card?.name)}finally{b.recycle()}
   }
   val b=Bitmap.createBitmap(300,54,Bitmap.Config.ARGB_8888)
   try{assertNull(OcrEngine.recognize(api,b,matcher))}finally{b.recycle()}
  }finally{api.recycle()}
 }
 @Test fun allElevenHeroesAndMissingMiddleSlot() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val catalog=DraftCatalog(ctx);val ocr=HeroOcr(catalog)
  assertEquals(11,catalog.classStats().size)
  for(hero in catalog.classStats())assertEquals(hero.id,ocr.matchText(hero.label.replace(" ","\n\n"))?.id)
  assertNull(ocr.matchText("Выберите карту для вашей колоды"))
  val dir=File(ctx.cacheDir,"hero-test/tessdata").apply {mkdirs()}
  ctx.assets.open("rus.traineddata").use {input->File(dir,"rus.traineddata").outputStream().use {input.copyTo(it)}}
  val api=TessBaseAPI();assertTrue(api.init(dir.parent,"rus",TessBaseAPI.OEM_LSTM_ONLY))
  val heroes=catalog.classStats().toList()
  try {
   for(group in heroes.chunked(3)+listOf(listOf(heroes[0],null,heroes.last()))) {
    val b=Bitmap.createBitmap(1600,900,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(b);canvas.drawColor(Color.BLACK)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=27f;typeface=Typeface.create("serif",Typeface.BOLD)}
    for((slot,hero) in group.withIndex())hero?.label?.split(" ")?.forEachIndexed {line,word ->
     canvas.drawText(word,slot*1600f/3+80f,540f+line*40,paint)
    }
    try {
     val found=ocr.recognize(api,b,100)
     assertEquals(3,found.size)
     for(slot in 0..2)assertEquals("Slot $slot of $group",group.getOrNull(slot)?.id,found[slot]?.id)
    }finally {b.recycle()}
   }
  }finally {api.recycle()}
 }
 @Test fun missingHeroStatsKeepLastValidRates() {
  val previous=JSONObject().put("classStats",JSONArray().put(JSONObject().put("id","mage").put("winRate",51.2)))
  val result=JSONObject().put("classStats",JSONArray())
  DatabaseUpdater.retainMissingHeroStats(result,previous)
  val row=result.getJSONArray("classStats").getJSONObject(0)
  assertEquals(51.2,row.getDouble("winRate"),.001);assertTrue(row.getBoolean("cached"))
 }
 @Test fun updateParsersAndRollbackGuard() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val old=Catalog.read(ctx)
  val path=File(ctx.filesDir,"catalog-update.bin")
  path.writeText("broken download")
  try{assertEquals(old.getJSONArray("cards").length(),Catalog.read(ctx).getJSONArray("cards").length())}finally{path.delete()}
  assertTrue(runCatching{DatabaseUpdater.parseScores("<html>maintenance</html>")}.isFailure)
  assertTrue(runCatching{DatabaseUpdater.requireRegular(JSONObject("{\"selected_params\":[\"ArenaGameTypeFilter.BGT_UNDERGROUND_ARENA\"]}"))}.isFailure)
  val fake=java.io.ByteArrayInputStream(ByteArray(20));assertTrue(runCatching{DatabaseUpdater.readLimited(fake,10)}.isFailure)
 }
 @Test fun liveRotationDownloadWritesValidatedCatalog() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val finished=java.util.concurrent.CountDownLatch(1)
  val success=java.util.concurrent.atomic.AtomicBoolean(false)
  val message=java.util.concurrent.atomic.AtomicReference("")
  val file=File(ctx.filesDir,"catalog-update.bin")
  try {
   assertTrue(DatabaseUpdater.update(ctx){ok,text -> success.set(ok);message.set(text);finished.countDown()})
   assertTrue("Update timeout",finished.await(180,java.util.concurrent.TimeUnit.SECONDS))
   assertTrue(message.get(),success.get());assertTrue(file.isFile)
   val downloaded=Catalog.read(ctx);Catalog.validate(downloaded)
   assertEquals("REGULAR",downloaded.getString("arenaMode"))
   android.util.Log.i("ArenaUpdateTest","Live rotation written: cards=${downloaded.getJSONArray("cards").length()}, groups=${downloaded.getJSONArray("packages").length()}, heroes=${downloaded.getJSONArray("classStats").length()}, warnings=${downloaded.optString("updateWarnings")}")
  }finally{file.delete()}
 }
 @Test fun projectionConsentStartsOverlayAndStopsCleanly() {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val ctx=instrumentation.targetContext
  val device=androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
  device.executeShellCommand("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
  device.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
  device.setOrientationLeft()
  try {
   instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   device.waitForIdle()
   if(device.findObject(androidx.test.uiautomator.By.desc("Начать захват"))==null) {
    androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollIntoView(androidx.test.uiautomator.UiSelector().description("Начать захват"))
   }
   val start=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc("Начать захват")),10000)
   assertNotNull("Launcher button",start);start.click()
   val consent=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("android","button1")),10000)
   assertNotNull("System projection consent",consent);consent.click()
   assertTrue("Overlay counter appears",device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("0/30")),15000))
   assertTrue("Projection foreground service running",device.executeShellCommand("dumpsys activity services ru.arenahelper").contains("CaptureService"))
  }finally{ctx.stopService(Intent(ctx,CaptureService::class.java));device.unfreezeRotation()}
 }

 @Test fun launcherOpens() {
  val i=InstrumentationRegistry.getInstrumentation();val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));i.runOnMainSync{a.finish()}
 }
}
