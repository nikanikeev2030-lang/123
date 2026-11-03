package ru.arenahelper
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
class HeroOcr(private val catalog:DraftCatalog) {
 fun recognize(api:TessBaseAPI,image:Bitmap,panelHeight:Int):List<DraftCatalog.ClassStat> {
  val scaled=if(image.width>1400) Bitmap.createScaledBitmap(image,1400,image.height*1400/image.width,true) else image
  try {
   val w=scaled.width;val h=scaled.height;val pixels=IntArray(w*h);scaled.getPixels(pixels,0,w,0,0,w,h)
   java.util.Arrays.fill(pixels,0,(panelHeight*h/image.height).coerceIn(0,h)*w,0xff000000.toInt())
   val found=linkedMapOf<String,DraftCatalog.ClassStat>()
   for(threshold in listOf(150,190)) {
    val binary=Bitmap.createBitmap(TitlePixels.prepare(pixels,w,h,threshold,0f),w,h,Bitmap.Config.ARGB_8888)
    try {
     api.pageSegMode=TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT;api.setImage(binary)
     val lines=api.utF8Text.orEmpty().lines();val pieces=lines+lines.zipWithNext { a,b -> "$a $b" }
     for(line in pieces) {
      val n=TextNormalizer.normalize(line);if(n.length !in 3..45)continue
      val ranked=catalog.classStats().map { c ->
       val name=TextNormalizer.normalize(c.label)
       val score=if(n==name)1.0 else if(n.contains(name)&&n.length<=name.length+6)0.98 else 1.0-TextNormalizer.distance(n,name).toDouble()/maxOf(n.length,name.length)
       c to score
      }.sortedByDescending { it.second }
      val best=ranked.firstOrNull()?:continue
      if(best.second >= if(TextNormalizer.normalize(best.first.label).length<6)0.9 else 0.82) {
       if(best.second-(ranked.getOrNull(1)?.second?:0.0)>=0.05)found[best.first.id]=best.first
      }
     }
    } finally {api.clear();binary.recycle()}
    if(found.size>=2)break
   }
   return if(found.size>=2)found.values.toList() else emptyList()
  } finally {if(scaled!==image)scaled.recycle()}
 }
}
