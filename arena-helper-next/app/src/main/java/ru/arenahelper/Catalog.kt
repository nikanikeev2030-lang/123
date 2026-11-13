package ru.arenahelper
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

/** Only server metadata and the current offer are held in memory. No catalog on disk. */
object Catalog {
 @Volatile private var metadata=JSONObject().put("retrievedAt", "—").put("cardCount",0).put("classStats",JSONArray())
 fun read(context:Context):JSONObject = JSONObject(metadata.toString()).put("cards",JSONArray()).put("packages",JSONArray())
 @Synchronized fun accept(result:JSONObject) {
  metadata=JSONObject().put("retrievedAt",result.getString("retrievedAt")).put("cardCount",result.getInt("cardCount"))
   .put("classStats",result.getJSONArray("classStats")).put("revision",result.optString("revision"))
 }
 @JvmStatic fun clear() {metadata=JSONObject().put("retrievedAt","—").put("cardCount",0).put("classStats",JSONArray())}
 fun removeLegacy(context:Context) {
  for(name in listOf("catalog-update.bin","catalog-update.bin.bak","catalog-update.bin.new")) java.io.File(context.filesDir,name).delete()
 }
}
