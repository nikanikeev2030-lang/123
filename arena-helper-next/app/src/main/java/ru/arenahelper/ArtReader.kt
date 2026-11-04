package ru.arenahelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Base64
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlin.math.roundToInt

/** Compares small colour fingerprints from the artwork. No text enters the matcher. */
class ArtReader(context:Context, private val catalog:DraftCatalog) {
    private data class Reference(val name:String, val rgb:ByteArray) {
        val mean=IntArray(3) { channel -> (0 until 80).sumOf { rgb[it*3+channel].toInt() and 255 } / 80 }
    }
    private val cards = mutableListOf<Reference>()
    private val heroes = mutableListOf<Reference>()

    init {
        val database = Catalog.read(context)
        val builtIn = bundled(context)
        val variants=context.assets.open("variants.json").bufferedReader().use {JSONObject(it.readText())}
        val additions = database.optJSONObject("art")
        val rows = database.getJSONArray("cards")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val id = row.getString("cardId")
            val encoded = additions?.optString(id)?.takeIf { it.isNotEmpty() } ?: builtIn.optString(id)
            if (encoded.isNotEmpty()) cards.add(Reference(row.getString("name"),decode(encoded)))
            val treatments=variants.optJSONObject(id)
            if(treatments!=null) for(kind in treatments.keys()) {
                cards.add(Reference(row.getString("name"),decode(treatments.getString(kind))))
            }
            if(builtIn.has("signature:"+id)) cards.add(Reference(row.getString("name"),decode(builtIn.getString("signature:"+id))))
        }
        val skins=context.assets.open("portraits.bin").use { input ->
            java.util.zip.GZIPInputStream(input).bufferedReader().use { JSONObject(it.readText()) }
        }
        for (key in skins.keys()) {
            val heroClass=key.substringBefore(':')
            if(catalog.stat(heroClass)!=null) heroes.add(Reference(heroClass,decode(skins.getString(key))))
        }
    }

    private fun decode(value:String) = Base64.decode(value,Base64.DEFAULT).also {require(it.size==240)}

    private fun closest(image:Bitmap, boxes:List<RectF>, references:List<Reference>, animated:Boolean=false):Pair<String,Double>? {
        val distances = IntArray(references.size) { Int.MAX_VALUE }
        for(box in boxes) {
            val query = fingerprint(image,box) ?: continue
            val levels=(0 until 80).map { cell ->
                val k=cell*3
                ((query[k].toInt() and 255)+(query[k+1].toInt() and 255)+(query[k+2].toInt() and 255))/3
            }
            if(levels.max()-levels.min()<18) continue
            val mean=IntArray(3) { c -> (0 until 80).sumOf { query[it*3+c].toInt() and 255 } / 80 }
            for(i in references.indices) {
                val rgb = references[i].rgb
                var error = 0
                if(animated) {
                    val shift=IntArray(3) { (mean[it]-references[i].mean[it]).coerceIn(-32,32) }
                    for(k in query.indices) error+=minOf(65,abs((query[k].toInt() and 255)-(rgb[k].toInt() and 255)-shift[k%3]))
                } else for(k in query.indices) error+=abs((rgb[k].toInt() and 255)-(query[k].toInt() and 255))
                if(error < distances[i]) distances[i]=error
            }
        }
        val best=distances.indices.minByOrNull { distances[it] } ?: return null
        val error=distances[best]/240.0
        if(error>(if(animated) 27 else 26)) return null
        val name=references[best].name
        val other=distances.indices.filter { references[it].name!=name }.minOfOrNull { distances[it] } ?: Int.MAX_VALUE
        if(other/240.0-error<(if(animated) 5.0 else 4.0)) return null
        return name to (1.0-error/160.0)
    }

    fun card(image:Bitmap, area:RectF):Match? {
        val boxes=listOf(0f,-.06f,.06f).map { dx -> RectF(area).apply {offset(dx*width(),0f)} }
        val result=closest(image,boxes,cards) ?: if(hasGoldFrame(image,area)) closest(image,boxes,cards,true) else null
        if(result==null) return null
        return Match(Card(result.first,null),result.second)
    }

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

    fun heroSlots(image:Bitmap):List<DraftCatalog.ClassStat?> {
        val board=minOf(image.width.toFloat(),image.height*16f/9)
        val left=(image.width-board)/2
        val thumb=if(image.width>800) Bitmap.createScaledBitmap(image,800,maxOf(1,800*image.height/image.width),true) else image
        try {
            val slots=(0..2).map { slot ->
                val x=(left+board*(.215f+slot*.267f))/image.width
                val boxes=mutableListOf<RectF>()
                for(scale in listOf(.94f,1f,1.06f)) for(y in listOf(.391f,.399f,.407f)) {
                    val w=board*.061f*scale/image.width
                    val h=.069f*scale
                    boxes.add(RectF(x-w,y-h,x+w,y+h))
                }
                closest(thumb,boxes,heroes)?.let { catalog.stat(it.first) }
            }
            return if(slots.count {it!=null}>=2) slots else emptyList()
        } finally {if(thumb!==image) thumb.recycle()}
    }

    companion object {
        fun bundled(context:Context):JSONObject {
            val base=context.assets.open("art.json").bufferedReader().use {JSONObject(it.readText())}
            val extra=context.assets.open("extra.json").bufferedReader().use {JSONObject(it.readText())}.getJSONObject("art")
            for(key in extra.keys()) base.put(key,extra.getString(key))
            return base
        }

        /** Merge newly rotated cards into the same atomic catalog as their ratings. */
        fun update(context:Context, result:JSONObject, previous:JSONObject) {
            val builtIn=bundled(context)
            val saved=previous.optJSONObject("art")
            val added=JSONObject()
            val rows=result.getJSONArray("cards")
            for(i in 0 until rows.length()) {
                val id=rows.getJSONObject(i).getString("cardId")
                if(builtIn.has(id)) continue
                require(id.matches(Regex("[A-Za-z0-9_]+")))
                val existing=saved?.optString(id)?.takeIf {it.isNotEmpty()}
                added.put(id,existing ?: fetch(id))
            }
            result.put("art",added)
        }

        internal fun fetch(id:String):String {
            val connection=URL("https://art.hearthstonejson.com/v1/render/latest/enUS/256x/$id.png").openConnection() as HttpsURLConnection
            connection.connectTimeout=15000
            connection.readTimeout=20000
            try {
                val bytes=connection.inputStream.use { DatabaseUpdater.readLimited(it,1024*1024) }
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                try {
                    require(bitmap.width==256 && bitmap.height>=192)
                    val region=RectF(78f/256,96f/bitmap.height,178f/256,160f/bitmap.height)
                    return Base64.encodeToString(requireNotNull(fingerprint(bitmap,region)), Base64.NO_WRAP)
                } finally {bitmap.recycle()}
            } finally {connection.disconnect()}
        }

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
