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
 private var allCards=emptyList<Card>()
 private var hero="mage"
 private var current=emptyList<Match?>()
 private var currentConfirmed=false
 private var mixed=true
 private var lastAdded=""
 private val picks=mutableListOf<List<Card>>()
 private val packages=mutableMapOf<String,List<Card>>()
 private var dialog:Dialog?=null
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
     hero=getSharedPreferences("arena",0).getString("class","mage")!!
     allCards=Catalog.cards(this,hero)
     matcher=CardMatcher(allCards)
     main.post { restoreDraft() }
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
    val p=image.planes.firstOrNull() ?: return@setOnImageAvailableListener
    val pixels=FramePixels.decode(p.buffer, image.width, image.height, p.rowStride, p.pixelStride) ?: return@setOnImageAvailableListener
    val padded=Bitmap.createBitmap(pixels,image.width,image.height,Bitmap.Config.ARGB_8888)
    try { recognize(padded,image.width,image.height) } finally { padded.recycle() }
   } catch(e:Exception) { android.util.Log.e("ArenaHelper", "Frame recognition failed", e); main.post { clear("Не удалось прочитать кадр • повторяю") } }
   finally { image.close() }
  },worker)
  if(display==null) display=projection?.createVirtualDisplay("ArenaCapture",w,h,resources.displayMetrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,next.surface,null,worker)
  else { display?.resize(w,h,resources.displayMetrics.densityDpi); display?.surface=next.surface }
 }
 private fun recognize(bitmap:Bitmap,w:Int,h:Int) {
  val frameGeneration=generation
  val results=Regions.load(this,w,h).map { r ->
   val x=(r.left*w).toInt().coerceIn(0,w-1); val y=(r.top*h).toInt().coerceIn(0,h-1)
   val cw=(r.width()*w).toInt().coerceIn(1,w-x); val ch=(r.height()*h).toInt().coerceIn(1,h-y)
   val crop=Bitmap.createBitmap(bitmap,x,y,cw,ch)
   try { OcrEngine.recognize(tess!!,crop,matcher!!) } finally { crop.recycle() }
  }
  val names=results.map { it?.card?.name }
  stable=if(names==previous) stable+1 else 1; previous=names
  val confirmed=stable>=2
  main.post {
   if(stopped || paused || calibrating || frameGeneration!=generation) return@post
   current=results; currentConfirmed=confirmed
   if (confirmed) DraftEngine.inferClass(results.mapNotNull { it?.card })?.let { inferred ->
    if (hero != inferred) { hero=inferred; getSharedPreferences("arena",0).edit().putString("class",hero).apply() }
   }
   render()

  }
 }

 private fun resolved(c:Card)=DraftEngine.resolve(c,hero)
 private fun offered(c:Card):List<Card>? = if(c.legendary && picks.isEmpty()) packages[c.name]?.let { listOf(resolved(c))+it.map(::resolved) } else listOf(resolved(c))
 private fun render() {
  val deck=picks.flatten().map(::resolved)
  val options=current.map { it?.card?.let(::offered) }
  val evaluations=options.map { it?.let { cards -> DraftEngine.evaluate(cards,deck,mixed) } }
  val complete=currentConfirmed && evaluations.size==3 && evaluations.all { it!=null }
  val best=if(complete) evaluations.maxOf { it!!.total } else null
  val group=current.any { it?.card?.legendary==true } && picks.isEmpty()
  status?.text="${Heroes.name(hero)} • ${deck.size}/30 • ${if(group) "Легендарные группы: укажите +3" else if(!currentConfirmed) "Проверяю…" else "Нажмите карту после выбора"}"
  current.forEachIndexed { i,m ->
   val card=m?.card?.let(::resolved); val e=evaluations.getOrNull(i); val winner=best!=null && e?.total==best
   val value=when {
    card==null -> "Не распознано"
    group && card.legendary && options.getOrNull(i)==null -> "Карта ${card.score ?: "?"} • +3: нажмите"
    e==null -> "Нет рейтинга в базе"
    e.curve!=null -> "%.1f = 70%% × %.0f + 30%% × %.0f".format(java.util.Locale.ROOT,e.total,e.strength,e.curve)
    else -> "Сила %.1f".format(java.util.Locale.ROOT,e.total)
   }
   cells.getOrNull(i)?.apply { text="${i+1}. ${card?.name ?: "—"}\n$value${if(winner) " ★" else ""}";setTextColor(if(winner) Color.rgb(86,224,180) else Color.WHITE) }
  }
 }
 private fun openDialog(builder:AlertDialog.Builder) {
  dialog?.dismiss()
  val d=builder.create(); d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
  d.setOnDismissListener { dialog=null }; dialog=d; d.show()
 }
 private fun chooseHero(reset:Boolean) {
  val items=Heroes.keys.indices.sortedByDescending { Heroes.underground[it] }
  val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12) }
  column.addView(TextView(this).apply { text=Heroes.source+"\nВыберите класс, предложенный игрой. Для новой арены сохранённая колода будет очищена.";setTextColor(Color.WHITE) })
  items.forEach { i -> column.addView(Button(this).apply {
   text="${Heroes.names[i]} — ${Heroes.underground[i]}% (Подземная)"
   setOnClickListener {
    hero=Heroes.keys[i];getSharedPreferences("arena",0).edit().putString("class",hero).apply()
    if(reset) { picks.clear();packages.clear();lastAdded="";saveDraft() }
    dialog?.dismiss();render()
   }
  }) }
  openDialog(AlertDialog.Builder(this).setTitle(if(reset) "Новая арена • выбор героя" else "Герой и винрейты").setView(ScrollView(this).apply { addView(column) }).setNegativeButton("Закрыть",null))
 }
 private fun cardAction(index:Int) {
  val c=current.getOrNull(index)?.card ?: return
  if(paused || calibrating || !currentConfirmed) { Toast.makeText(this,"Дождитесь подтверждения OCR",Toast.LENGTH_SHORT).show(); return }
  if(c.legendary && picks.isEmpty()) { editPackage(c);return }
  openDialog(AlertDialog.Builder(this).setTitle(c.name).setMessage("Добавить выбранную карту в учёт колоды? В самой игре карту выбирайте отдельно.")
   .setPositiveButton("Выбрал в игре") { _,_ -> record(listOf(resolved(c))) }.setNegativeButton("Отмена",null))
 }
 private fun editPackage(legend:Card) {
  val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12) }
  column.addView(TextView(this).apply { text="Укажите три карты, которые игра добавит вместе с легендарной. Повторы разрешены. Оценка группы — средняя сила четырёх карт." })
  val names=allCards.map { it.name }.distinct().sorted()
  val fields=(0..2).map { i -> AutoCompleteTextView(this).apply {
   hint="Дополнительная карта ${i+1}";threshold=1;setSingleLine();setAdapter(ArrayAdapter(this@CaptureService,android.R.layout.simple_dropdown_item_1line,names))
   setText(packages[legend.name]?.getOrNull(i)?.name.orEmpty());column.addView(this)
  } }
  fun read():List<Card>? {
   val selected=fields.map { f -> allCards.firstOrNull { CardMatcher.normalize(it.name)==CardMatcher.normalize(f.text.toString()) } }
   if(selected.any { it==null }) { Toast.makeText(this,"Выберите все три точных названия из списка",Toast.LENGTH_LONG).show();return null }
   return selected.filterNotNull()
  }
  column.addView(Button(this).apply { text="Сохранить группу и сравнить";setOnClickListener { read()?.let { packages[legend.name]=it;dialog?.dismiss();render() } } })
  column.addView(Button(this).apply { text="Эту группу выбрал в игре (+4)";setOnClickListener { read()?.let { packages[legend.name]=it;dialog?.dismiss();record(listOf(resolved(legend))+it.map(::resolved)) } } })
  openDialog(AlertDialog.Builder(this).setTitle(legend.name+" + 3 карты").setView(ScrollView(this).apply { addView(column) }).setNegativeButton("Отмена",null))
 }
 private fun record(cards:List<Card>) {
  val fingerprint=current.joinToString("|"){it?.card?.name.orEmpty()}
  if(fingerprint==lastAdded || picks.flatten().size+cards.size>30) { Toast.makeText(this,"Этот выбор уже записан или колода заполнена",Toast.LENGTH_LONG).show();return }
  picks.add(cards);lastAdded=fingerprint;saveDraft();render()
 }
 private fun saveDraft() {
  val data=org.json.JSONArray();picks.forEach { group -> data.put(org.json.JSONArray(group.map { it.name })) }
  getSharedPreferences("arena",0).edit().putString("draft",data.toString()).putString("lastAdded",lastAdded).apply()
 }
 private fun restoreDraft() {
  runCatching {
   val pref=getSharedPreferences("arena",0); val data=org.json.JSONArray(pref.getString("draft","[]"))
   picks.clear();for(i in 0 until data.length()) { val group=data.getJSONArray(i);val cards=(0 until group.length()).mapNotNull { j -> allCards.firstOrNull { it.name==group.getString(j) } };if(cards.size==group.length())picks.add(cards) }
   lastAdded=pref.getString("lastAdded","").orEmpty()
  };render()
 }
 private fun addManual() {
  val field=AutoCompleteTextView(this).apply { threshold=1;hint="Название ранее выбранной карты";setAdapter(ArrayAdapter(this@CaptureService,android.R.layout.simple_dropdown_item_1line,allCards.map { it.name }.distinct().sorted())) }
  val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12);addView(field) }
  column.addView(Button(this).apply { text="Добавить одну копию";setOnClickListener {
   val c=allCards.firstOrNull { CardMatcher.normalize(it.name)==CardMatcher.normalize(field.text.toString()) }
   if(c==null || picks.flatten().size>=30) Toast.makeText(this@CaptureService,"Выберите название из списка; максимум 30 карт",Toast.LENGTH_SHORT).show()
   else { picks.add(listOf(c));saveDraft();dialog?.dismiss();render() }
  } })
  openDialog(AlertDialog.Builder(this).setTitle("Восстановить колоду").setView(column).setNegativeButton("Отмена",null))
 }
 private fun showDeck() {
  val deck=picks.flatten();val counts=IntArray(7);deck.forEach { c -> c.cost?.let { counts[DraftEngine.bucket(it)]++ } }
  val text="Манакривая (0–1/2/3/4/5/6/7+): ${counts.joinToString(" / ")}\nЦель: ${DraftEngine.target.joinToString(" / ")}\n70/30 — эвристика. Без записанной колоды используется только сила.\n\n"+deck.mapIndexed { i,c -> "${i+1}. ${c.name} (${c.cost ?: "?"})" }.joinToString("\n")
  openDialog(AlertDialog.Builder(this).setTitle("Колода ${deck.size}/30").setMessage(text).setNegativeButton("Закрыть",null).setPositiveButton("Добавить вручную") { _,_ -> addManual() }.setNeutralButton("Отменить последний") { _,_ -> if(picks.isNotEmpty())picks.removeAt(picks.lastIndex);lastAdded="";saveDraft();render() })
 }

 private fun params(w:Int,h:Int,gravity:Int)=WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
  this.gravity=gravity; layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; setFitInsetsTypes(0)
 }
 private fun showPanel() {
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,4,12,8); setBackgroundColor(0xED102332.toInt()) }
  val bar=LinearLayout(this)
  status=TextView(this).apply { text="Загрузка русского OCR…"; setTextColor(Color.WHITE); textSize=12f; maxLines=2 }
  bar.addView(status,LinearLayout.LayoutParams(0,-2,1f))
  fun control(label:String,action:(Button)->Unit) { bar.addView(Button(this).apply { text=label; textSize=11f; minWidth=0; minimumWidth=0; setOnClickListener { action(this) } },LinearLayout.LayoutParams(-2,40.dp)) }
  control("Герой") { chooseHero(false) }
  control("Новая") { chooseHero(true) }
  control("Колода") { showDeck() }
  control("70/30") { b -> mixed=!mixed; b.text=if(mixed) "70/30" else "Сила"; render() }
  control("Пауза") { b -> paused=!paused; generation++; b.text=if(paused) "Пуск" else "Пауза"; worker.post { previous=emptyList(); stable=0 }; clear(if(paused) "Приостановлено" else "Распознавание…") }
  control("Зоны") { calibrate() }
  control("×") { stopSelf() }
  root.addView(bar)
  val row=LinearLayout(this)
  cells=(0..2).map { TextView(this).apply { text="${it+1}. —"; textSize=13f; setTextColor(Color.WHITE); setPadding(6,2,6,2); val index=it; setOnClickListener { cardAction(index) }; row.addView(this,LinearLayout.LayoutParams(0,-2,1f)) } }
  root.addView(row); panel=root
  wm.addView(root,params(-1,-2,Gravity.TOP))
 }
 private fun calibrate() {
  if(calibrating) return
  calibrating=true; generation++; panel?.visibility=View.GONE
  calibration=CalibrationView(this) {
   calibration?.let { wm.removeView(it) }; calibration=null; calibrating=false
   generation++; worker.post { previous=emptyList(); stable=0 }; panel?.visibility=View.VISIBLE; clear("Распознавание…")
  }
  wm.addView(calibration,params(-1,-1,Gravity.TOP or Gravity.START))
 }
 private fun clear(message:String) { status?.text=message; cells.forEachIndexed { i,v -> v.text="${i+1}. —"; v.setTextColor(Color.WHITE) } }
 private val Int.dp get()=(this*resources.displayMetrics.density).toInt()
 override fun onDestroy() {
  stopped=true
  dialog?.dismiss(); dialog=null
  calibration?.let { runCatching { wm.removeView(it) } }; panel?.let { runCatching { wm.removeView(it) } }
  worker.post { display?.release(); display=null; reader?.close(); reader=null; projection?.stop(); projection=null; tess?.recycle(); tess=null; thread.quitSafely() }
  stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
 }
}
