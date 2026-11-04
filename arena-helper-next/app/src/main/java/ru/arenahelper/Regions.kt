package ru.arenahelper

import android.content.Context
import android.graphics.*
import android.view.*
import android.widget.*

object Regions {
    fun defaults(width: Int, height: Int): List<RectF> {
        val contentWidth = minOf(width.toFloat(), height * 16f / 9f)
        val left = (width - contentWidth) / 2f
        return (0..2).map { i ->
            val center = left + contentWidth * (0.26f + i * 0.238f)
            RectF((center - contentWidth * 0.0405f) / width, 0.3515f,
                (center + contentWidth * 0.0405f) / width, 0.4435f)
        }
    }
    fun load(context: Context, width: Int, height: Int): List<RectF> {
        val defaults = defaults(width, height)
        val p = context.getSharedPreferences("arena", 0)
        if (p.getInt("regionVersion", 0) != 3) return defaults
        return defaults.mapIndexed { i, d ->
            val r = RectF(p.getFloat("l$i", d.left), p.getFloat("t$i", d.top), p.getFloat("r$i", d.right), p.getFloat("b$i", d.bottom))
            if (listOf(r.left, r.top, r.right, r.bottom).all { it.isFinite() && it in 0f..1f } && r.width() >= 0.025f && r.height() >= 0.015f) r else d
        }
    }
    fun save(context: Context, regions: List<RectF>) {
        require(regions.size == 3)
        val e = context.getSharedPreferences("arena", 0).edit().putInt("regionVersion", 3)
        regions.forEachIndexed { i, r -> e.putFloat("l$i", r.left).putFloat("t$i", r.top).putFloat("r$i", r.right).putFloat("b$i", r.bottom) }
        e.apply()
    }
}

class CalibrationView(context: Context, private val done: () -> Unit) : FrameLayout(context) {
    private val drawing = RegionCanvas(context)
    init {
        addView(drawing, LayoutParams(-1, -1))
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4); setBackgroundColor(0xF0102332.toInt())
        }
        toolbar.addView(TextView(context).apply {
            text = "Выделите центр рисунка карты"; setTextColor(Color.WHITE); textSize = 14f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        for (i in 0..2) toolbar.addView(Button(context).apply {
            text = "${i + 1}"; contentDescription = "Выбрать рамку ${i + 1}"
            setOnClickListener { drawing.active = i; drawing.invalidate() }
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        fun button(label: String, action: () -> Unit) {
            toolbar.addView(Button(context).apply { text = label; textSize = 12f; setOnClickListener { action() } }, LinearLayout.LayoutParams(-2, 48.dp))
        }
        button("Сброс") { drawing.reset() }
        button("Отмена") { done() }
        button("Готово") { if (drawing.boxes.size == 3) Regions.save(context, drawing.boxes); done() }
        addView(toolbar, LayoutParams(-1, -2, Gravity.TOP))
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private class RegionCanvas(context: Context) : View(context) {
        var boxes: MutableList<RectF> = mutableListOf()
        var active = 0
        private var sx = 0f; private var sy = 0f
        private var original = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w > 0 && h > 0) boxes = Regions.load(context, w, h).toMutableList()
        }
        fun reset() { if (width > 0 && height > 0) boxes = Regions.defaults(width, height).toMutableList(); active = 0; invalidate() }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x22000000)
            boxes.forEachIndexed { i, r ->
                rect.set(r.left * width, r.top * height, r.right * width, r.bottom * height)
                paint.color = if (i == active) Color.YELLOW else Color.CYAN
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; canvas.drawRect(rect, paint)
                paint.style = Paint.Style.FILL; paint.textSize = 26f; canvas.drawText("${i + 1}", rect.left, rect.top - 6f, paint)
            }
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (boxes.size != 3 || width <= 0 || height <= 0) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = event.x / width; sy = event.y / height; original = RectF(boxes[active]) }
                MotionEvent.ACTION_MOVE -> {
                    val x = (event.x / width).coerceIn(0f, 1f); val y = (event.y / height).coerceIn(0f, 1f)
                    boxes[active] = RectF(minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y)); invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (boxes[active].width() < 0.025f || boxes[active].height() < 0.015f) boxes[active] = original
                    else active = (active + 1) % 3
                    performClick(); invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { boxes[active] = original; invalidate() }
            }
            return true
        }
    }
}
