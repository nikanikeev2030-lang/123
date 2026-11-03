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
  val api=TessBaseAPI();assertTrue(api.init(dir.parent,"rus",1))
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
 @Test fun liveRotationDownloadValidatesWithoutReplacingInstalledCatalog() {
  val downloaded=DatabaseUpdater.download()
  Catalog.validate(downloaded)
  assertTrue(downloaded.getJSONArray("cards").length()>=800)
  assertEquals("REGULAR",downloaded.getString("arenaMode"))
  android.util.Log.i("ArenaUpdateTest","Live rotation: cards=${downloaded.getJSONArray("cards").length()}, groups=${downloaded.getJSONArray("packages").length()}, heroes=${downloaded.getJSONArray("classStats").length()}, warnings=${downloaded.optString("updateWarnings")}")
 }
 @Test fun launcherOpens() {
  val i=InstrumentationRegistry.getInstrumentation();val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));i.runOnMainSync{a.finish()}
 }
}
