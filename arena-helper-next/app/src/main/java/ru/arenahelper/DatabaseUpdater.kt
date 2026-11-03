package ru.arenahelper
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPOutputStream

object DatabaseUpdater {
 fun interface Callback { fun done(ok:Boolean,message:String) }
 @Volatile private var running=false
 @Synchronized fun update(context:Context,callback:Callback):Boolean {
  if(running)return false
  running=true
  val app=context.applicationContext
  Thread({
   var ok=false
   val message=try {
    val result=download()
    val atomic=AtomicFile(File(app.filesDir,"catalog-update.bin"))
    val stream=atomic.startWrite()
    try {
     val zipped=java.io.ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(result.toString().toByteArray(Charsets.UTF_8)) } }.toByteArray()
     stream.write(zipped);atomic.finishWrite(stream)
    }catch(e:Exception){atomic.failWrite(stream);throw e}
    ok=true
    "Обновлено: ${result.getJSONArray("cards").length()} карт. ${result.optString("updateWarnings")} Перезапустите захват для применения."
   }catch(e:Exception){ "Обновление не выполнено: ${e.message}. Прежняя база сохранена." }
   finally {running=false}
   Handler(Looper.getMainLooper()).post {callback.done(ok,message)}
  },"arena-db-update").start()
  return true
 }
 fun download():JSONObject {
  val tier=text("https://www.heartharena.com/en/tierlist",4*1024*1024)
  val index=text("https://api.hearthstonejson.com/v1/latest/",512*1024)
  val build=Regex("href=\"/v1/(\\d+)\"").find(index)?.groupValues?.get(1) ?: error("Версия каталога не найдена")
  val source="https://api.hearthstonejson.com/v1/$build/ruRU/cards.json"
  val metadata=JSONArray(text(source,32*1024*1024))
  val warnings=mutableListOf<String>()
  val stats=runCatching { JSONObject(text(STATS,1024*1024)).also(::requireRegular) }.getOrElse { warnings.add("Статистика героев недоступна.");null }
  val groups=runCatching { JSONObject(text(GROUPS,4*1024*1024)).also(::requireRegular) }.getOrElse { warnings.add("Состав групп недоступен.");null }
  var result=buildCatalog(tier,metadata,null,null)
  if(groups!=null) runCatching {buildCatalog(tier,metadata,null,groups)}.onSuccess{result=it}.onFailure{warnings.add("Состав групп не прошёл проверку.")}
  if(stats!=null) runCatching {buildCatalog(tier,metadata,stats,null).getJSONArray("classStats")}.onSuccess{result.put("classStats",it)}.onFailure{warnings.add("Статистика героев не прошла проверку.")}
  return result.put("metadataBuild",build).put("namesSource",source).put("updateWarnings",warnings.joinToString(" "))
 }
 const val STATS="https://hsreplay.net/api/v1/arena/classes_stats/?game_type=BGT_ARENA"
 const val GROUPS="https://hsreplay.net/api/v1/arena/card_packages/free/?game_type=BGT_ARENA"
 fun requireRegular(json:JSONObject) {
  val params=json.getJSONArray("selected_params")
  require((0 until params.length()).any { params.optString(it)=="ArenaGameTypeFilter.BGT_ARENA" }){"Неверный режим Арены"}
 }
 private fun attr(tag:String,name:String)=Regex("\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)
 fun parseScores(html:String):Map<String,Map<String,Int>> {
  val result=linkedMapOf<String,MutableMap<String,Int>>()
  val section=Regex("<section\\b([^>]*)>(.*?)</section>",setOf(RegexOption.DOT_MATCHES_ALL,RegexOption.IGNORE_CASE))
  for(s in section.findAll(html)) {
   val cls=attr(s.groupValues[1],"id")?:continue
   if(cls !in heroKeys || !(attr(s.groupValues[1],"class")?:"").contains("tierlist"))continue
   for(dl in Regex("<dl\\b[^>]*>(.*?)</dl>",RegexOption.DOT_MATCHES_ALL).findAll(s.groupValues[2])) {
    val dt=Regex("<dt\\b([^>]*)>").find(dl.value)?:continue
    val image=attr(dt.groupValues[1],"data-card-image")?:continue
    val id=image.substringAfterLast('/').substringBeforeLast(".webp","");if(id.isEmpty())continue
    val dd=Regex("<dd\\b[^>]*>(.*?)</dd>",RegexOption.DOT_MATCHES_ALL).find(dl.value)?.groupValues?.get(1)?:continue
    val score=Regex("\\d{1,3}").find(dd)?.value?.toInt()?:continue
    require(score in 0..200);result.getOrPut(id){linkedMapOf()}[cls]=score
   }
  }
  require(result.size>=800 && result.values.sumOf{it.size}>=3000){"Источник вернул неполную таблицу рейтингов"}
  return result
 }
 val heroKeys=listOf("death-knight","druid","hunter","mage","paladin","priest","rogue","shaman","warlock","warrior","demon-hunter")
 val heroNames=listOf("Рыцарь смерти","Друид","Охотник","Маг","Паладин","Жрец","Разбойник","Шаман","Чернокнижник","Воин","Охотник на демонов")
 fun buildCatalog(tier:String,metadata:JSONArray,stats:JSONObject?,groups:JSONObject?):JSONObject {
  val ratings=parseScores(tier)
  val byId=(0 until metadata.length()).map {metadata.getJSONObject(it)}.associateBy {it.optString("id")}
  val ids=ratings.keys.toMutableSet()
  val packages=JSONArray()
  if(groups!=null) {
   requireRegular(groups)
   val all=groups.getJSONObject("data").getJSONArray("ALL");require(all.length()>=50){"Неполный список групп"}
   for(i in 0 until all.length()) {
    val g=all.getJSONObject(i);val key=g.getString("package_key_card_id");val extras=g.getJSONArray("package_card_ids");ids.add(key)
    val cards=JSONArray()
    for(j in 0 until extras.length()) {
     val id=extras.getString(j);ids.add(id);val m=byId[id]?:error("Нет метаданных $id")
     cards.put(JSONObject().put("name",m.getString("name")).put("cost",m.optInt("cost",-1)))
    }
    packages.put(JSONObject().put("key",(byId[key]?:error("Нет метаданных $key")).getString("name")).put("cards",cards))
   }
  }
  val cards=JSONArray()
  for(id in ids.sorted()) {
   val m=byId[id]?:error("Нет русских метаданных $id")
   require(m.optString("name").isNotBlank() && m.optInt("cost",-1)>=0){"Неполные метаданные $id"}
   cards.put(JSONObject().put("name",m.getString("name")).put("cost",m.getInt("cost")).put("cardId",id).put("rarity",m.optString("rarity")).put("scores",JSONObject(ratings[id]?:emptyMap<String,Int>())))
  }
  val classStats=JSONArray()
  if(stats!=null) {
   requireRegular(stats);val data=stats.getJSONArray("data");val seen=mutableSetOf<Int>()
   for(i in 0 until data.length()) {
    val row=data.getJSONObject(i);val id=row.getInt("deck_class");val index=if(id==14)10 else id-1
    if(index !in heroKeys.indices)continue
    require(seen.add(id));val rate=row.getDouble("win_rate");require(rate.isFinite()&&rate in 0.0..100.0)
    classStats.put(JSONObject().put("id",heroKeys[index]).put("label",heroNames[index]).put("winRate",rate).put("drafts",row.optInt("num_drafts",0)))
   };require(classStats.length()>=8){"Неполная статистика героев"}
  }
  val now=Instant.now().toString()
  return JSONObject().put("source","https://www.heartharena.com/tierlist").put("retrievedAt",now).put("cards",cards).put("packages",packages).put("packageSource",GROUPS).put("classStats",classStats).put("classStatsSource",STATS).put("classStatsPeriod","LAST_1_DAY").put("classStatsRetrievedAt",now).put("arenaMode","REGULAR").also {Catalog.validate(it)}
 }
 fun readLimited(input:java.io.InputStream,limit:Int):ByteArray {
  val out=java.io.ByteArrayOutputStream();val buf=ByteArray(16384)
  while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=limit){"Ответ слишком большой"};out.write(buf,0,n)}
  return out.toByteArray()
 }
 fun text(url:String,limit:Int):String {
  require(url.startsWith("https://"))
  val c=URL(url).openConnection() as HttpURLConnection
  c.connectTimeout=20000;c.readTimeout=60000;c.setRequestProperty("User-Agent","ArenaHelperRU/1.1.4");c.setRequestProperty("Accept-Encoding","identity")
  try {
   check(c.responseCode==200){"HTTP ${c.responseCode} (${URL(url).host})"}
   require(c.contentLengthLong<=limit){"Ответ слишком большой"}
   val data=c.inputStream.use {readLimited(it,limit)};require(data.size<=limit)
   return data.toString(Charsets.UTF_8)
  }finally{c.disconnect()}
 }
}
