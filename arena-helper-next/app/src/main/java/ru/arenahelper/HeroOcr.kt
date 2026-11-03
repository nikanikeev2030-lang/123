package ru.arenahelper

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

class HeroOcr(private val catalog: DraftCatalog) {
 /** Three fixed slots: a missed middle hero must never move the right hero left. */
 fun recognize(api:TessBaseAPI,image:Bitmap,panelHeight:Int):List<DraftCatalog.ClassStat?> {
  val result = MutableList<DraftCatalog.ClassStat?>(3) { null }
  val boardWidth = minOf(image.width, image.height * 16 / 9)
  val boardLeft = (image.width - boardWidth) / 2
  val top = maxOf(panelHeight, image.height / 5).coerceAtMost(image.height - 1)
  for(slot in 0..2) {
   val left = boardLeft + boardWidth * slot / 3
   val right = boardLeft + boardWidth * (slot + 1) / 3
   val crop = Bitmap.createBitmap(image,left,top,right-left,image.height-top)
   // Preserve small type instead of reducing the full screenshot to 1400 pixels.
   val scaled = Bitmap.createScaledBitmap(crop,900,900*crop.height/crop.width,true)
   try {
    val w=scaled.width; val h=scaled.height
    val pixels=IntArray(w*h);scaled.getPixels(pixels,0,w,0,0,w,h)
    for(threshold in listOf(150,190,110)) {
     val binary=Bitmap.createBitmap(TitlePixels.prepare(pixels,w,h,threshold,0f),w,h,Bitmap.Config.ARGB_8888)
     try {
      api.pageSegMode=TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT;api.setImage(binary)
      result[slot] = matchText(api.utF8Text.orEmpty())
     } finally {api.clear();binary.recycle()}
     if(result[slot]!=null) break
    }
   } finally {if(scaled!==crop)scaled.recycle();crop.recycle()}
  }
  // A single recognized class is useful too; empty means this is not a hero screen.
  return if(result.any { it != null }) result else emptyList()
 }

 internal fun matchText(text:String):DraftCatalog.ClassStat? {
  val lines=text.lines().map(String::trim).filter(String::isNotEmpty)
  val pieces=lines+lines.zipWithNext {a,b -> "$a $b" }+
   lines.windowed(3).map {it.joinToString(" ")}
  val candidates=mutableMapOf<DraftCatalog.ClassStat,Double>()
  for(piece in pieces) {
   val n=TextNormalizer.normalize(piece)
   if(n.length !in 3..40)continue
   for(c in catalog.classStats()) {
    val name=TextNormalizer.normalize(c.label)
    val score=if(n==name)1.0 else 1.0-TextNormalizer.distance(n,name).toDouble()/maxOf(n.length,name.length)
    // Short class names need exact matches, and Hunter must not match Demon Hunter.
    if(score >= if(name.length<6)1.0 else .82) candidates[c]=maxOf(candidates[c]?:0.0,score)
   }
  }
  val ranked=candidates.entries.sortedByDescending {it.value}
  val best=ranked.firstOrNull()?:return null
  // Prefer the complete multi-line name over its shorter line (Охотник / на демонов).
  val complete=ranked.filter {it.value==1.0}.maxByOrNull {TextNormalizer.normalize(it.key.label).length}
  if(complete!=null)return complete.key
  return if(best.value-(ranked.getOrNull(1)?.value?:0.0)>=.06)best.key else null
 }
}
