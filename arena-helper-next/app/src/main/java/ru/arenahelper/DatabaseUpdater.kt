package ru.arenahelper
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** The phone can check the published server revision, but cannot refresh upstream sources. */
object DatabaseUpdater {
 fun interface Callback {fun done(ok:Boolean,message:String)}
 @Volatile private var running=false
 @Synchronized fun update(context:Context,callback:Callback):Boolean {
  if(running)return false
  running=true
  Thread({
   var ok=false
   val message=try {
    Catalog.removeLegacy(context)
    val result=DeviceLicense.request(context,"info",JSONObject())
    Catalog.accept(result);ok=true
    "Серверная база: ${result.getInt("cardCount")} карт"
   } catch(e:Exception) {"Не удалось проверить серверную базу: ${e.message}"}
   finally {running=false}
   Handler(Looper.getMainLooper()).post {callback.done(ok,message)}
  },"arena-server-info").start()
  return true
 }
 fun readLimited(input:java.io.InputStream,limit:Int):ByteArray {
  val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
  while(true) {val n=input.read(buffer);if(n<0)break;require(out.size()+n<=limit);out.write(buffer,0,n)}
  return out.toByteArray()
 }
}
