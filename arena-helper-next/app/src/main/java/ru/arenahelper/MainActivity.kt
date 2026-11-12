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
  text(getString(R.string.app_name),26f)
  text("1. После ротации нажмите «Обновить базы».\n2. Нажмите «Начать», разрешите оверлей и захват экрана.\n3. Откройте обычную Арену Hearthstone.\n4. Первые 14 карт: только сила. С 15-й: 70% сила + 30% манакривая. Смотрите счётчик колоды.")
  val licenseInfo=TextView(this).apply {setTextColor(Color.WHITE);text="Лицензия на один телефон"}
  column.addView(licenseInfo)
  val licenseInput=EditText(this).apply {
   hint="Код лицензии AH-…";isSingleLine=true
   inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
   setText(DeviceLicense.savedKey(this@MainActivity));setTextColor(Color.WHITE)
  }
  column.addView(licenseInput)
  lateinit var activate:Button
  activate=button("Активировать лицензию") {
   activate.isEnabled=false;licenseInfo.text="Проверяем лицензию…"
   val enteredKey=licenseInput.text.toString()
   Thread {
    val result=DeviceLicense.activate(this,enteredKey)
    runOnUiThread {activate.isEnabled=true;licenseInfo.text=result}
   }.start()
  }
  text("Одна лицензия — один телефон. Для запуска и периодической проверки нужен интернет. После удаления приложения или замены телефона обратитесь к продавцу для переноса.",14f)
  button("Начать") {
   if(!DeviceLicense.isValid()) {licenseInfo.text="Сначала активируйте лицензию.";return@button}
   if(!Settings.canDrawOverlays(this)){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")));return@button}
   if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),9) else launchCapture()
  }.contentDescription="Начать захват"
  val info=TextView(this).apply {setTextColor(Color.WHITE);setPadding(0,12,0,12);setSingleLine(true);ellipsize=android.text.TextUtils.TruncateAt.END}
  fun showDatabaseInfo() {
   runCatching {Catalog.read(this)}.onSuccess {info.text="База: ${it.optString("retrievedAt").take(10)} • ${it.getJSONArray("cards").length()} карт"}.onFailure{info.text="База: недоступна"}
  }
  lateinit var update:Button
  update=button("Обновить базы") {
   update.isEnabled=false;update.text="Обновление…"
   val started=DatabaseUpdater.update(this) { _,message -> runOnUiThread {update.isEnabled=true;update.text="Обновить базы";showDatabaseInfo();Toast.makeText(this,message,Toast.LENGTH_LONG).show()} }
   if(!started){update.isEnabled=true;update.text="Обновить базы";Toast.makeText(this,"Обновление уже выполняется",Toast.LENGTH_SHORT).show()}
  }
  button("Остановить") {stopService(Intent(this,CaptureService::class.java))}
  column.addView(info)
  showDatabaseInfo()
  setContentView(ScrollView(this).apply{addView(column);fitsSystemWindows=true})
 }
 private fun launchCapture() {
  if(!DeviceLicense.isValid()) {Toast.makeText(this,"Сначала активируйте лицензию",Toast.LENGTH_LONG).show();return}
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
  if(requestCode==100 && resultCode==RESULT_OK && data!=null && DeviceLicense.isValid()) {
   stopService(Intent(this,CaptureService::class.java))
   startForegroundService(Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("data",data))
   Toast.makeText(this,"Откройте Hearthstone → Арена",Toast.LENGTH_LONG).show()
   moveTaskToBack(true)
  }
 }
}
