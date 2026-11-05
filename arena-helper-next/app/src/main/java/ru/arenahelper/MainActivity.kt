package ru.arenahelper
import android.app.*
import android.os.*
import android.content.*
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.view.*
import android.widget.*

class MainActivity : Activity() {
 private val classes=listOf("death-knight","demon-hunter","druid","hunter","mage","paladin","priest","rogue","shaman","warlock","warrior")
 private val labels=listOf("Рыцарь смерти","Охотник на демонов","Друид","Охотник","Маг","Паладин","Жрец","Разбойник","Шаман","Чернокнижник","Воин")
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,36,24,24) }
  fun text(s:String,size:Float=17f){column.addView(TextView(this).apply{text=s;textSize=size;setTextColor(Color.WHITE);setPadding(0,12,0,12)})}
  fun button(s:String,action:()->Unit):Button {val b=Button(this).apply{text=s;setOnClickListener{action()}};column.addView(b);return b}
  text("Arena Helper RU 1.2.6",26f)
  text("1. После ротации нажмите «Обновить базы».\n2. Нажмите «Начать», разрешите оверлей и захват экрана.\n3. Откройте обычную Арену Hearthstone.\n4. Первые 14 карт: только сила. С 15-й: 70% сила + 30% манакривая. Смотрите счётчик колоды.\n5. Если автоматический выбор учтён неверно, коснитесь выбранной карты на панели. «↺» начинает новый драфт.")
  button("Начать") {
   if(!Settings.canDrawOverlays(this)){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")));return@button}
   if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),9) else launchCapture()
  }.contentDescription="Начать захват"
  val info=TextView(this).apply {setTextColor(Color.WHITE);setPadding(0,12,0,12)}
  lateinit var update:Button
  update=button("Обновить базы") {
   update.isEnabled=false;info.text="Загрузка рейтингов, русских названий, групп и статистики…"
   val started=DatabaseUpdater.update(this) { _,message -> runOnUiThread {update.isEnabled=true;info.text=message} }
   if(!started){update.isEnabled=true;info.text="Обновление уже выполняется"}
  }
  button("Остановить") {stopService(Intent(this,CaptureService::class.java))}
  column.addView(info)
  runCatching {Catalog.read(this)}.onSuccess {info.text="База: ${it.optString("retrievedAt").take(10)} • ${it.getJSONArray("cards").length()} карт"}.onFailure{info.text="Ошибка базы: ${it.message}"}
  text("Карты и герои определяются по рисункам на телефоне. Интернет используется только для обновления публичных данных. Перезапустите захват после обновления. Коснитесь счётчика, чтобы исправить размер колоды или выбрать класс.",14f)
  setContentView(ScrollView(this).apply{addView(column);fitsSystemWindows=true})
 }
 private fun launchCapture() {
  val manager=getSystemService(MediaProjectionManager::class.java)
  val capture=if(Build.VERSION.SDK_INT>=34) manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent()
  startActivityForResult(capture,100)
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==9) launchCapture()
 }
 @Deprecated("Platform result API")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==100 && resultCode==RESULT_OK && data!=null) {
   stopService(Intent(this,CaptureService::class.java))
   startForegroundService(Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("data",data))
   Toast.makeText(this,"Откройте Hearthstone → Арена",Toast.LENGTH_LONG).show()
   moveTaskToBack(true)
  }
 }
}
