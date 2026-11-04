package ru.arenahelper
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
object Catalog {
 fun read(context:Context):JSONObject {
  val result=readBase(context)
  val rows=result.getJSONArray("cards")
  val ids=(0 until rows.length()).mapTo(HashSet()) {rows.getJSONObject(it).getString("cardId")}
  val extras=context.assets.open("extra.json").bufferedReader().use {JSONObject(it.readText())}.getJSONArray("cards")
  for(i in 0 until extras.length()) {
   val row=extras.getJSONObject(i)
   if(ids.add(row.getString("cardId"))) rows.put(row)
  }
  return result
 }
 private fun readBase(context:Context):JSONObject {
  val update=File(context.filesDir,"catalog-update.bin")
  if(update.isFile || File(update.path+".bak").isFile) runCatching { android.util.AtomicFile(update).openRead().use(::decode) }.getOrNull()?.let {
   val bundled=context.assets.open("cards.bin").use(::decode)
   DatabaseUpdater.retainMissingHeroStats(it,bundled)
   return it
  }
  return context.assets.open("cards.bin").use(::decode)
 }
 private fun decode(input:InputStream):JSONObject {
  val bytes=GZIPInputStream(input).use { DatabaseUpdater.readLimited(it,32*1024*1024) }
  require(bytes.size<=32*1024*1024){"Catalog too large"}
  return JSONObject(bytes.toString(Charsets.UTF_8)).also { validate(it) }
 }
 fun validate(json:JSONObject) {
  val cards=json.getJSONArray("cards");require(cards.length()>=800)
  var rated=0
  for(i in 0 until cards.length()) {
   val c=cards.getJSONObject(i);require(c.getString("name").isNotBlank());require(c.optInt("cost",-1)>=-1)
   val scores=c.getJSONObject("scores");if(scores.length()>0)rated++
   scores.keys().forEach { require(scores.getInt(it) in 0..200) }
  }
  require(rated>=800){"Incomplete ratings"}
 }
}
