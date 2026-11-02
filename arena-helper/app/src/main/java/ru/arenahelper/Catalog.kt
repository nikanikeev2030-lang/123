package ru.arenahelper
import android.content.Context
import org.json.JSONObject
import java.util.zip.GZIPInputStream
object Catalog {
 fun read(context:Context):JSONObject = GZIPInputStream(context.assets.open("cards.bin")).bufferedReader().use { JSONObject(it.readText()) }
 fun cards(context:Context, hero:String):List<Card> {
  val rows=read(context).getJSONArray("cards")
  return (0 until rows.length()).map { i ->
   val r=rows.getJSONObject(i); val s=r.getJSONObject("scores")
   val ratings=s.keys().asSequence().associateWith { s.getInt(it) }
   DraftEngine.resolve(Card(r.getString("name"),null,if(r.isNull("cost")) null else r.getInt("cost"),r.optString("cardClass","neutral"),r.optBoolean("legendary"),ratings),hero)
  }
 }
}
