package ru.arenahelper
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.roundToInt

/** Extracts only 10x8 RGB patches; all references and nearest-neighbour matching live on the server. */
class ArtReader @JvmOverloads constructor(private val context:Context,private val catalog:DraftCatalog,private val endpoint:String=BuildConfig.LICENSE_URL) {
 private var matches:List<Match?> = listOf(null,null,null)
 private var heroes:List<DraftCatalog.ClassStat?> = emptyList()
 fun analyze(image:Bitmap,areas:List<RectF>) {
  val cards=JSONArray()
  for(area in areas) {
   val boxes=listOf(0f,-.06f,.06f).map {dx->RectF(area).apply {offset(dx*width(),0f)}}
   cards.put(JSONObject().put("queries",queries(image,boxes)).put("gold",hasGoldFrame(image,area)))
  }
  val board=minOf(image.width.toFloat(),image.height*16f/9);val left=(image.width-board)/2
  val thumb=if(image.width>800) Bitmap.createScaledBitmap(image,800,maxOf(1,800*image.height/image.width),true) else image
  val heroQueries=JSONArray()
  try {
   for(slot in 0..2) {
    val x=(left+board*(.215f+slot*.267f))/image.width
    val boxes=mutableListOf<RectF>()
    for(scale in listOf(.94f,1f,1.06f)) for(y in listOf(.391f,.399f,.407f)) {
     val w=board*.061f*scale/image.width;val h=.069f*scale
     boxes.add(RectF(x-w,y-h,x+w,y+h))
    }
    heroQueries.put(queries(thumb,boxes))
   }
  } finally {if(thumb!==image)thumb.recycle()}
  val result=DeviceLicense.requestAt(context,"analyze",JSONObject().put("cards",cards).put("heroes",heroQueries),endpoint)
  Catalog.accept(result);catalog.merge(result)
  val details=result.getJSONArray("cards")
  val names=(0 until details.length()).map {details.getJSONObject(it)}.associate {it.getString("cardId") to it.getString("name")}
  val found=result.getJSONArray("matches");require(found.length()==3)
  matches=(0..2).map {i->found.optJSONObject(i)?.let {Match(Card(names.getValue(it.getString("id")),null),it.getDouble("confidence"))}}
  val hs=result.getJSONArray("heroes");require(hs.length()==3)
  val values=(0..2).map {i->hs.optJSONObject(i)?.let {catalog.stat(it.getString("id"))}}
  heroes=if(values.count {it!=null}>=2) values else emptyList()
 }
 private fun queries(image:Bitmap,boxes:List<RectF>):JSONArray=JSONArray().apply {
  for(box in boxes) put(Base64.encodeToString(fingerprint(image,box) ?: ByteArray(240),Base64.NO_WRAP))
 }
 fun cardAt(slot:Int):Match?=matches.getOrNull(slot)
 fun heroSlots():List<DraftCatalog.ClassStat?> = heroes
    private fun hasGoldFrame(image:Bitmap,area:RectF):Boolean {
        var gold=0
        val width=area.width()
        for(step in 0..16) for(side in listOf(-1,1)) for(edge in listOf(.86f,.90f,.94f)) {
            val x=((area.centerX()+side*edge*width)*image.width).roundToInt().coerceIn(0,image.width-1)
            val y=((area.centerY()+((step-8)/10f)*area.height())*image.height).roundToInt().coerceIn(0,image.height-1)
            val pixel=image.getPixel(x,y)
            val r=pixel ushr 16 and 255; val g=pixel ushr 8 and 255; val b=pixel and 255
            if(r>140 && g>102 && r>b+42 && g>b+16) gold++
        }
        return gold>=40
    }

    companion object {
        fun fingerprint(image:Bitmap, region:RectF):ByteArray? {
            val left=(region.left*image.width).roundToInt().coerceIn(0,image.width)
            val top=(region.top*image.height).roundToInt().coerceIn(0,image.height)
            val w=(region.right*image.width).roundToInt().coerceIn(0,image.width)-left
            val h=(region.bottom*image.height).roundToInt().coerceIn(0,image.height)-top
            if(w<10 || h<8) return null
            val pixels=IntArray(w*h)
            image.getPixels(pixels,0,w,left,top,w,h)
            val rgb=ByteArray(240)
            var index=0
            for(y in 0 until 8) for(x in 0 until 10) {
                var red=0;var green=0;var blue=0;var count=0
                for(row in y*h/8 until (y+1)*h/8) for(col in x*w/10 until (x+1)*w/10) {
                    val p=pixels[row*w+col];red+=p ushr 16 and 255;green+=p ushr 8 and 255;blue+=p and 255;count++
                }
                rgb[index++]=(red/count).toByte();rgb[index++]=(green/count).toByte();rgb[index++]=(blue/count).toByte()
            }
            return rgb
        }
    }
}
