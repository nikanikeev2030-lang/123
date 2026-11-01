package ru.arenahelper
import android.content.Context
import android.graphics.*
import android.view.*

object Regions {
 fun load(context:Context):List<RectF> {
  val p=context.getSharedPreferences("arena",0)
  return (0..2).map { i ->
   val center=0.28f+i*0.22f
   RectF(p.getFloat("l$i",center-0.09f),p.getFloat("t$i",0.39f),p.getFloat("r$i",center+0.09f),p.getFloat("b$i",0.49f))
  }
 }
 fun save(context:Context,regions:List<RectF>) {
  val e=context.getSharedPreferences("arena",0).edit()
  regions.forEachIndexed { i,r -> e.putFloat("l$i",r.left).putFloat("t$i",r.top).putFloat("r$i",r.right).putFloat("b$i",r.bottom) }; e.apply()
 }
}
class CalibrationView(context:Context,private val done:()->Unit):View(context) {
 private val boxes=Regions.load(context).toMutableList()
 private var active=0
 private var sx=0f; private var sy=0f
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
 private val drawnRect=RectF()
 override fun onDraw(c:Canvas) {
  c.drawColor(0x33000000)
  paint.style=Paint.Style.FILL; paint.color=0xEE102332.toInt(); c.drawRect(0f,0f,width.toFloat(),90f,paint)
  paint.color=Color.WHITE; paint.textSize=28f
  c.drawText("Обведите название ${active+1}/3 • справа вверху: ГОТОВО",20f,55f,paint)
  boxes.forEachIndexed { i,r ->
   val rect=drawnRect.apply { set(r.left*width,r.top*height,r.right*width,r.bottom*height) }
   paint.color=if(i==active) Color.YELLOW else Color.CYAN; paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; c.drawRect(rect,paint)
   paint.style=Paint.Style.FILL; c.drawText("${i+1}",rect.left,rect.top-8,paint)
  }
 }
 override fun performClick():Boolean { super.performClick(); Regions.save(context,boxes); done(); return true }
 override fun onTouchEvent(e:MotionEvent):Boolean {
  if(e.action==MotionEvent.ACTION_DOWN && e.y<90 && e.x>width*0.70f) { performClick(); return true }
  when(e.action) {
   MotionEvent.ACTION_DOWN -> { sx=e.x/width; sy=e.y/height }
   MotionEvent.ACTION_MOVE -> {
    boxes[active]=RectF(minOf(sx,e.x/width).coerceIn(0f,0.98f),minOf(sy,e.y/height).coerceIn(0f,0.98f),maxOf(sx,e.x/width).coerceIn(0.02f,1f),maxOf(sy,e.y/height).coerceIn(0.02f,1f)); invalidate()
   }
   MotionEvent.ACTION_UP -> { if(boxes[active].width()>0.02f && boxes[active].height()>0.02f) active=(active+1)%3; invalidate() }
  }
  return true
 }
}
