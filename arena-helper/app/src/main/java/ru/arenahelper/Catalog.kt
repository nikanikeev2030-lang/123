package ru.arenahelper
import android.content.Context
import org.json.JSONObject
import java.util.zip.GZIPInputStream
object Catalog {
 fun read(context:Context):JSONObject = GZIPInputStream(context.assets.open("cards.json.gz")).bufferedReader().use { JSONObject(it.readText()) }
}
