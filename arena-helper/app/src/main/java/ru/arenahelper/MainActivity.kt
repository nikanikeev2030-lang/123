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
  val prefs=getSharedPreferences("arena",MODE_PRIVATE)
  val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,40,24,40) }
  fun text(value:String,size:Float=16f) { column.addView(TextView(this).apply { text=value; textSize=size; setTextColor(Color.WHITE); setPadding(0,12,0,12) }) }
  fun button(value:String,action:()->Unit) { column.addView(Button(this).apply { text=value; setOnClickListener { action() } }) }
  text("ARENA HELPER RU",28f)
  text("Русский OCR • офлайн • три карты",18f)
  text("1. Выберите класс героя")
  column.addView(Spinner(this).apply {
   adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels)
   setSelection(classes.indexOf(prefs.getString("class","mage")).coerceAtLeast(0))
   onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
    override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) { prefs.edit().putString("class",classes[position]).apply() }
    override fun onNothingSelected(p:AdapterView<*>?) {}
   }
  })
  button("2. Разрешить поверх других приложений") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
  button("3. Запустить помощник") {
   if(!Settings.canDrawOverlays(this)) { Toast.makeText(this,"Сначала разрешите оверлей",Toast.LENGTH_LONG).show(); return@button }
   if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),9)
   else launchCapture()
  }
  button("Остановить") { stopService(Intent(this,CaptureService::class.java)) }
  text("Откройте выбор карт Арены горизонтально. Нажмите «Зоны» в оверлее: обведите только названия трёх карт, слева направо. Затем нажмите «Готово».")
  text("Кадры обрабатываются на телефоне, не сохраняются и не передаются в сеть. Захват нужно подтверждать при каждом запуске.")
  val catalog=Catalog.read(this)
  text("Рейтинги: HearthArena, снимок ${catalog.getString("retrievedAt").take(10)}. Базовые оценки для выбранного класса, без синергии колоды. Для неизвестных карт оценки нет. После смены класса перезапустите помощник.",14f)
  text("HyperOS: если оверлей исчезает, проверьте разрешения на всплывающие окна и ограничение фоновой работы. Совместимость с конкретной прошивкой требует проверки на телефоне.",14f)
  text("Неофициальное приложение. Не связано с Blizzard или HearthArena.",12f)
  setContentView(ScrollView(this).apply { addView(column); fitsSystemWindows=true })
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
