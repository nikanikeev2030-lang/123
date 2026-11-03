package ru.arenahelper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.googlecode.tesseract.android.TessBaseAPI

object OcrEngine {
 fun recognize(api:TessBaseAPI,crop:Bitmap,matcher:CardMatcher):Match? {
  val candidates=linkedMapOf<String,MutableList<Match>>()
  fun accept(raw:String,relaxed:Boolean):Match? {
   val match=matcher.match(raw) ?: if(relaxed) matcher.matchLong(raw) else null
   if(match!=null)candidates.getOrPut(match.card.name){mutableListOf()}.add(match)
   return match
  }
  val pixels=IntArray(crop.width*crop.height);crop.getPixels(pixels,0,crop.width,0,0,crop.width,crop.height)
  val passes=listOf(140 to .20f,140 to .15f,160 to .20f,120 to .20f,180 to 0f,170 to .10f,150 to .12f,180 to .08f,140 to .04f,210 to 0f)
  for((threshold,curve) in passes) {
   val binary=Bitmap.createBitmap(TitlePixels.prepare(pixels,crop.width,crop.height,threshold,curve),crop.width,crop.height,Bitmap.Config.ARGB_8888)
   val scaled=Bitmap.createScaledBitmap(binary,(crop.width*4).coerceIn(600,1600),crop.height*4,true)
   try {
    api.pageSegMode=if(curve>0)7 else 6;api.setImage(scaled)
    val m=accept(api.utF8Text.orEmpty(),false)
    if(m!=null && m.confidence>=.999)return m
   } finally {api.clear();if(scaled!==binary)scaled.recycle();binary.recycle()}
  }
  val strong=candidates.values.flatten().maxByOrNull {it.confidence}
  if(strong!=null && strong.confidence>=.90)return strong
  // Interpolate the original antialiased letters before thresholding, not a jagged binary mask.
  val up=Bitmap.createScaledBitmap(crop,crop.width*5,crop.height*5,true)
  try {
   val w=up.width;val h=up.height;val rgb=IntArray(w*h);up.getPixels(rgb,0,w,0,0,w,h)
   for((curve,center,threshold) in listOf(Triple(.28f,.5f,160),Triple(.28f,.62f,160),Triple(.20f,.5f,190),Triple(.12f,.72f,130),Triple(.12f,.5f,130),Triple(.20f,.62f,160))) {
    val out=IntArray(w*h){Color.WHITE}
    for(y in (h*.20f).toInt() until (h*.72f).toInt())for(x in 0 until w){
     val u=x.toFloat()/w-center;val sy=(y+(w*curve*u*u).toInt()).coerceIn(0,h-1);val p=rgb[sy*w+x]
     if(minOf((p ushr 16)and 255,(p ushr 8)and 255,p and 255)>threshold)out[y*w+x]=Color.BLACK
    }
    val binary=Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888)
    val padded=Bitmap.createBitmap(w+40,h+40,Bitmap.Config.ARGB_8888)
    Canvas(padded).apply {drawColor(Color.WHITE);drawBitmap(binary,20f,20f,null)}
    try {api.pageSegMode=7;api.setImage(padded);accept(api.utF8Text.orEmpty(),true)}finally{api.clear();padded.recycle();binary.recycle()}
   }
  }finally{up.recycle()}
  val ranked=candidates.values.filter { it.maxOf {m->m.confidence}>=.82 || it.size>=2 }.map {it.maxBy {m->m.confidence}}.sortedByDescending {it.confidence}
  val best=ranked.firstOrNull()?:return null
  if(ranked.getOrNull(1)?.let {best.confidence-it.confidence<.08}==true)return null
  return best
 }
}
