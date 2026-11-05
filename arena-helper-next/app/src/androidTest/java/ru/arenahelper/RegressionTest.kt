package ru.arenahelper
import android.graphics.*
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class RegressionTest {
 private fun drawArt(canvas:Canvas,area:RectF,encoded:String) {
  val bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT)
  val paint=Paint()
  for(y in 0 until 8)for(x in 0 until 10) {
   val start=3*(10*y+x)
   paint.color=Color.rgb(bytes[start].toInt() and 255,bytes[start+1].toInt() and 255,bytes[start+2].toInt() and 255)
   canvas.drawRect(area.left+x*area.width()/10,area.top+y*area.height()/8,
    area.left+(x+1)*area.width()/10,area.top+(y+1)*area.height()/8,paint)
  }
 }
 @Test fun artworkSlotsAtPhoneAspectWithoutText() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val art=ArtReader(ctx,DraftCatalog(ctx));val refs=ArtReader.bundled(ctx)
  val ids=listOf("VAC_445","TTN_736","DEEP_016")
  val names=listOf("Ночь вурдалаков","Посох Примаса","Кварцитовый крушитель")
  for((w,h) in listOf(2048 to 945,1600 to 900)) {
   val frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
   try {
    val areas=Regions.defaults(w,h);val canvas=Canvas(frame)
    for(i in 0..2) {
     assertNull(art.card(frame,areas[i]))
     val a=areas[i];drawArt(canvas,RectF(a.left*w+2,a.top*h,a.right*w+2,a.bottom*h),refs.getString(ids[i]))
    }
    assertEquals(names,areas.map {art.card(frame,it)?.card?.name})
   }finally{frame.recycle()}
  }
 }
 @Test fun specialTreatmentsUseOriginalCardIds() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val catalog=DraftCatalog(ctx);val reader=ArtReader(ctx,catalog)
  val refs=ctx.assets.open("variants.json").bufferedReader().use {JSONObject(it.readText())}
  val rows=Catalog.read(ctx).getJSONArray("cards")
  val names=(0 until rows.length()).map {rows.getJSONObject(it)}.associate {it.getString("cardId") to it.getString("name")}
  val choices=listOf("CS2_042" to "gold","GDB_310" to "signature","WORK_027" to "diamond")
  val frame=Bitmap.createBitmap(2048,945,Bitmap.Config.ARGB_8888)
  try {
   val canvas=Canvas(frame);val slots=Regions.defaults(frame.width,frame.height)
   for((index,choice) in choices.withIndex()) {
    val rect=slots[index]
    drawArt(canvas,RectF(rect.left*2048+2,rect.top*945+1,rect.right*2048+2,rect.bottom*945+1),
     refs.getJSONObject(choice.first).getString(choice.second))
    assertEquals(names[choice.first],reader.card(frame,rect)?.card?.name)
   }
  }finally{frame.recycle()}
 }
 @Test fun allHeroPortraitsKeepTheirSlot() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val catalog=DraftCatalog(ctx);val reader=ArtReader(ctx,catalog);val refs=ArtReader.bundled(ctx)
  val heroes=catalog.classStats().map {it.id}
  for(group in heroes.chunked(3)+listOf(listOf(heroes.first(),null,heroes.last()))) {
   val w=1280;val h=720;val frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
   try {
    val canvas=Canvas(frame);val areas=Regions.defaults(w,h)
    for((i,id) in group.withIndex())if(id!=null) {
     val cx=.215f+i*.267f;val a=RectF(cx-.061f,0f,cx+.061f,.138f);val cy=.399f
     drawArt(canvas,RectF(a.left*w,(cy-a.height()/2)*h,a.right*w,(cy+a.height()/2)*h),refs.getString("hero:"+id))
    }
    assertEquals((0..2).map {group.getOrNull(it)},reader.heroSlots(frame).map {it?.id})
   }finally {frame.recycle()}
  }
 }
 @Test fun missingHeroStatsKeepLastValidRates() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val complete=Catalog.read(ctx)
  val incomplete=JSONObject(complete.toString()).put("classStats",JSONArray())
  val path=File(ctx.filesDir,"catalog-update.bin")
  java.util.zip.GZIPOutputStream(path.outputStream()).use {it.write(incomplete.toString().toByteArray())}
  try {assertEquals(11,Catalog.read(ctx).getJSONArray("classStats").length())}finally {path.delete()}
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
 @Test fun downloadedReferenceMatchesBundledArt() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val actual=android.util.Base64.decode(ArtReader.fetch("VAC_445"),android.util.Base64.DEFAULT)
  val expected=android.util.Base64.decode(ArtReader.bundled(context).getString("VAC_445"),android.util.Base64.DEFAULT)
  assertEquals(240,actual.size)
  val difference=actual.indices.sumOf {kotlin.math.abs((actual[it].toInt() and 255)-(expected[it].toInt() and 255))}
  assertTrue("Downloaded card art differs from reference",difference/240.0<6)
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
  if(android.os.Build.VERSION.SDK_INT>=33) device.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
  device.setOrientationLeft()
  try {
   instrumentation.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   device.waitForIdle()
   val control=androidx.test.uiautomator.By.desc("Начать захват")
   var start=device.wait(androidx.test.uiautomator.Until.findObject(control),3500)
   if(start==null) {
    val viewport=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.scrollable(true).pkg(ctx.packageName)),10000)
    assertNotNull("Launcher viewport",viewport)
    viewport.scroll(androidx.test.uiautomator.Direction.DOWN,0.55f)
    start=device.wait(androidx.test.uiautomator.Until.findObject(control),5000)
   }
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
