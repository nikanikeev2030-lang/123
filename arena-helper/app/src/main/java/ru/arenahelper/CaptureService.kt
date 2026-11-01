package ru.arenahelper
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.*
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.view.*
import android.widget.*
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

class CaptureService:Service() {
 private val main=Handler(Looper.getMainLooper())
 private val thread=HandlerThread("arena-ocr")
 private lateinit var worker:Handler
 private var projection:MediaProjection?=null
 private var display:VirtualDisplay?=null
 private var reader:ImageReader?=null
 private var tess:TessBaseAPI?=null
 private var matcher:CardMatcher?=null
 private lateinit var wm:WindowManager
 private var panel:LinearLayout?=null
 private var calibration:View?=null
 private var status:TextView?=null
 private var cells=emptyList<TextView>()
 @Volatile private var stopped=false
 @Volatile private var paused=false
 @Volatile private var calibrating=false
 @Volatile private var generation=0
 private var lastFrame=0L
 private var width=0; private var height=0
 private var previous=emptyList<String?>()
 private var stable=0
 override fun onBind(intent:Intent?):IBinder?=null
 override fun onCreate() {
  super.onCreate(); wm=getSystemService(WindowManager::class.java)
  thread.start(); worker=Handler(thread.looper)
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("capture","Захват Арены",NotificationManager.IMPORTANCE_LOW))
 }
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="STOP") { stopSelf(); return START_NOT_STICKY }
  if(projection!=null) return START_NOT_STICKY
  val stop=PendingIntent.getService(this,1,Intent(this,CaptureService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
  val notice=Notification.Builder(this,"capture").setContentTitle("Arena Helper RU").setContentText("Локальное распознавание экрана включено")
   .setSmallIcon(android.R.drawable.ic_menu_view).addAction(Notification.Action.Builder(null,"Остановить",stop).build()).setOngoing(true).build()
  startForeground(1,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
  val data=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
  if(data==null) { stopSelf(); return START_NOT_STICKY }
  try {
   projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent!!.getIntExtra("resultCode",Activity.RESULT_CANCELED),data)
   projection!!.registerCallback(object:MediaProjection.Callback() {
    override fun onStop() { stopSelf() }
    override fun onCapturedContentResize(w:Int,h:Int) { worker.post { resize(w,h) } }
   },main)
   showPanel()
   worker.post {
    try {
     val dir=File(filesDir,"ocr/tessdata").apply { mkdirs() }
     val model=File(dir,"rus.traineddata")
     if(!model.exists()) assets.open("rus.traineddata").use { input -> model.outputStream().use { input.copyTo(it) } }
     val api=TessBaseAPI(); tess=api
     check(api.init(dir.parent,"rus",TessBaseAPI.OEM_LSTM_ONLY)) { "Не удалось загрузить русский OCR" }
     api.pageSegMode=TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
     val cls=getSharedPreferences("arena",0).getString("class","mage")!!
     val json=Catalog.read(this).getJSONArray("cards")
     val cards=(0 until json.length()).map { i -> val c=json.getJSONObject(i); val scores=c.getJSONObject("scores"); Card(c.getString("name"),if(scores.has(cls)) scores.getInt(cls) else null) }
     matcher=CardMatcher(cards)
     val bounds=wm.maximumWindowMetrics.bounds
     resize(bounds.width(),bounds.height())
    } catch(e:Exception) { fail(e) }
   }
  } catch(e:Exception) { fail(e) }
  return START_NOT_STICKY
 }
 private fun fail(e:Exception) { main.post { Toast.makeText(this,"Ошибка: ${e.message}",Toast.LENGTH_LONG).show(); stopSelf() } }
 private fun resize(w:Int,h:Int) {
  if(stopped || w<2 || h<2 || (w==width && h==height && reader!=null)) return
  width=w; height=h; previous=emptyList(); stable=0
  display?.surface=null; reader?.close()
  val next=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2); reader=next
  next.setOnImageAvailableListener({ r ->
   val image=try { r.acquireLatestImage() } catch(_:Exception) { null } ?: return@setOnImageAvailableListener
   try {
    val now=SystemClock.elapsedRealtime()
    if(stopped || paused || calibrating || tess==null || matcher==null || now-lastFrame<1600) return@setOnImageAvailableListener
    lastFrame=now
    if(w<h) { main.post { clear("Поверните телефон горизонтально") }; return@setOnImageAvailableListener }
    val p=image.planes[0]
    val padded=Bitmap.createBitmap(p.rowStride/p.pixelStride,h,Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(p.buffer)
    try { recognize(padded,w,h) } finally { padded.recycle() }
   } catch(e:Exception) { main.post { clear("Ошибка OCR: ${e.message?.take(60)}") } }
   finally { image.close() }
  },worker)
  if(display==null) display=projection?.createVirtualDisplay("ArenaCapture",w,h,resources.displayMetrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,next.surface,null,worker)
  else { display?.resize(w,h,resources.displayMetrics.densityDpi); display?.surface=next.surface }
 }
 private fun recognize(bitmap:Bitmap,w:Int,h:Int) {
  val frameGeneration=generation
  val results=Regions.load(this).map { r ->
   val x=(r.left*w).toInt().coerceIn(0,w-1); val y=(r.top*h).toInt().coerceIn(0,h-1)
   val cw=(r.width()*w).toInt().coerceIn(1,w-x); val ch=(r.height()*h).toInt().coerceIn(1,h-y)
   val crop=Bitmap.createBitmap(bitmap,x,y,cw,ch)
   val scaled=Bitmap.createScaledBitmap(crop,maxOf(cw,600),(ch*maxOf(cw,600).toFloat()/cw).toInt().coerceAtLeast(1),true)
   try {
    tess!!.setImage(scaled)
    val raw=tess!!.utF8Text ?: ""
    if(tess!!.meanConfidence()<40) null else matcher!!.match(raw)
   } finally { tess!!.clear(); if(scaled!==crop) scaled.recycle(); crop.recycle() }
  }
  val names=results.map { it?.card?.name }
  stable=if(names==previous) stable+1 else 1; previous=names
  val confirmed=stable>=2
  main.post {
   if(stopped || paused || calibrating || frameGeneration!=generation) return@post
   val complete=confirmed && results.all { it?.card?.score!=null }
   val best=if(complete) results.maxOf { it!!.card.score!! } else null
   status?.text=if(complete) "HearthArena • базовый рейтинг" else if(!confirmed) "Проверяю распознавание…" else "Нет всех оценок • проверьте зоны"
   results.forEachIndexed { i,match ->
    val card=match?.card
    val winner=best!=null && card?.score==best
    cells[i].text="${i+1}. ${card?.name ?: "Не распознано"}\n${card?.score?.toString() ?: "Нет оценки"}${if(winner) " ★ ЛУЧШИЙ" else ""}"
    cells[i].setTextColor(if(winner) Color.rgb(86,224,180) else Color.WHITE)
   }
  }
 }
 private fun params(w:Int,h:Int,gravity:Int)=WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
  this.gravity=gravity; layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; setFitInsetsTypes(0)
 }
 private fun showPanel() {
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,4,12,8); setBackgroundColor(0xED102332.toInt()) }
  val bar=LinearLayout(this)
  status=TextView(this).apply { text="Загрузка русского OCR…"; setTextColor(Color.WHITE); textSize=12f }
  bar.addView(status,LinearLayout.LayoutParams(0,-2,1f))
  fun control(label:String,action:(Button)->Unit) { bar.addView(Button(this).apply { text=label; textSize=11f; minWidth=0; minimumWidth=0; setOnClickListener { action(this) } },LinearLayout.LayoutParams(-2,40.dp)) }
  control("Пауза") { b -> paused=!paused; generation++; b.text=if(paused) "Пуск" else "Пауза"; worker.post { previous=emptyList(); stable=0 }; clear(if(paused) "Приостановлено" else "Распознавание…") }
  control("Зоны") { calibrate() }
  control("×") { stopSelf() }
  root.addView(bar)
  val row=LinearLayout(this)
  cells=(0..2).map { TextView(this).apply { text="${it+1}. —"; textSize=13f; setTextColor(Color.WHITE); setPadding(6,2,6,2); row.addView(this,LinearLayout.LayoutParams(0,-2,1f)) } }
  root.addView(row); panel=root
  wm.addView(root,params(-1,-2,Gravity.BOTTOM))
 }
 private fun calibrate() {
  if(calibrating) return
  calibrating=true; generation++; panel?.visibility=View.GONE
  calibration=CalibrationView(this) {
   calibration?.let { wm.removeView(it) }; calibration=null; calibrating=false
   generation++; worker.post { previous=emptyList(); stable=0 }; panel?.visibility=View.VISIBLE; clear("Зоны сохранены • распознавание…")
  }
  wm.addView(calibration,params(-1,-1,Gravity.TOP or Gravity.START))
 }
 private fun clear(message:String) { status?.text=message; cells.forEachIndexed { i,v -> v.text="${i+1}. —"; v.setTextColor(Color.WHITE) } }
 private val Int.dp get()=(this*resources.displayMetrics.density).toInt()
 override fun onDestroy() {
  stopped=true
  calibration?.let { runCatching { wm.removeView(it) } }; panel?.let { runCatching { wm.removeView(it) } }
  worker.post { display?.release(); display=null; reader?.close(); reader=null; projection?.stop(); projection=null; tess?.recycle(); tess=null; thread.quitSafely() }
  stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
 }
}
