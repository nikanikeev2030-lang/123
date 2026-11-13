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
 @Test fun realServerRecognizesThreeCardsWithDeviceSignature() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val key=InstrumentationRegistry.getArguments().getString("serverLicense")!!
  val endpoint="http://127.0.0.1:8765/v1/activate"
  DeviceLicense.contact(ctx,key,endpoint);assertTrue(DeviceLicense.isValid())
  val fixture=InstrumentationRegistry.getInstrumentation().context.assets.open("server-fixture.json").bufferedReader().use {JSONArray(it.readText())}
  val catalog=DraftCatalog(ctx);val art=ArtReader(ctx,catalog,endpoint)
  for((w,h) in listOf(2048 to 945,1600 to 900)) {
   val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
   try {
    val canvas=Canvas(bitmap);val paint=Paint();val areas=Regions.defaults(w,h)
    for(slot in 0..2) {
     val bytes=android.util.Base64.decode(fixture.getJSONObject(slot).getString("fingerprint"),android.util.Base64.DEFAULT)
     val area=areas[slot]
     for(y in 0..7) for(x in 0..9) {
      val k=(y*10+x)*3;paint.color=Color.rgb(bytes[k].toInt() and 255,bytes[k+1].toInt() and 255,bytes[k+2].toInt() and 255)
      canvas.drawRect((area.left+x*area.width()/10)*w,(area.top+y*area.height()/8)*h,(area.left+(x+1)*area.width()/10)*w,(area.top+(y+1)*area.height()/8)*h,paint)
     }
    }
    art.analyze(bitmap,areas)
    for(slot in 0..2) {
     val name=fixture.getJSONObject(slot).getString("name")
     assertEquals(name,art.cardAt(slot)?.card?.name)
     assertNotNull(catalog.details(name))
    }
    assertEquals(0,Catalog.read(ctx).getJSONArray("cards").length())
    assertEquals(11,catalog.classStats().size)
   }finally {bitmap.recycle()}
  }
 }

 @Test fun noBundledDatabaseAndLegacyCacheRemoved() {
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext
  val names=ctx.assets.list("")!!.toSet()
  assertTrue(names.intersect(setOf("cards.bin","art.json","extra.json","variants.json","portraits.bin")).isEmpty())
  val old=File(ctx.filesDir,"catalog-update.bin");old.writeText("old ratings")
  Catalog.removeLegacy(ctx);assertFalse(old.exists())
  assertEquals(0,Catalog.read(ctx).getJSONArray("cards").length())
 }
 @Test fun fingerprintPreservesRgbGridAtPhoneSizes() {
  for((w,h) in listOf(2048 to 945,1600 to 900)) {
   val frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
   try {
    frame.eraseColor(Color.rgb(20,100,230))
    for(area in Regions.defaults(w,h)) {
     val bytes=ArtReader.fingerprint(frame,area)!!;assertEquals(240,bytes.size)
     for(k in bytes.indices) assertEquals(intArrayOf(20,100,230)[k%3],bytes[k].toInt() and 255)
    }
   }finally {frame.recycle()}
  }
 }

 @Test fun projectionConsentStartsOverlayAndStopsCleanly() {
  LicenseTest.authorize()
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
   var consent=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("android","button1")),3000)
   val confirm=androidx.test.uiautomator.By.pkg("com.android.systemui").text(java.util.regex.Pattern.compile("(?i)start now|start recording|share screen|start|начать|начать запись"))
   repeat(3) {
    if(consent==null) {
     consent=device.findObject(confirm)
     if(consent==null) {
      device.findObject(androidx.test.uiautomator.By.pkg("com.android.systemui").scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN,0.8f)
      device.waitForIdle()
      consent=device.findObject(androidx.test.uiautomator.By.res("android","button1")) ?: device.findObject(confirm)
     }
    }
   }
   if(consent==null) {
    val dump=java.io.ByteArrayOutputStream();device.dumpWindowHierarchy(dump)
    android.util.Log.e("ProjectionDialog",dump.toString("UTF-8"))
   }
   assertNotNull("System projection consent",consent);consent!!.click()
   assertTrue("Overlay counter appears",device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("0/30")),15000))
   assertTrue("Projection foreground service running",device.executeShellCommand("dumpsys activity services ru.arenahelper").contains("CaptureService"))
  }finally{ctx.stopService(Intent(ctx,CaptureService::class.java));device.unfreezeRotation()}
 }

 @Test fun launcherOpens() {
  val i=InstrumentationRegistry.getInstrumentation();val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));i.runOnMainSync{a.finish()}
 }
}
