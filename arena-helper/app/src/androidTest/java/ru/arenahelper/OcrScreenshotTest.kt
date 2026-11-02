package ru.arenahelper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrScreenshotTest {
    @Test fun recognizesSyntheticRussianTitlesWithRealNativeOcr() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "ocr-test/tessdata").apply { mkdirs() }
        context.assets.open("rus.traineddata").use { input -> File(directory, "rus.traineddata").outputStream().use { input.copyTo(it) } }
        val api = TessBaseAPI()
        assertTrue(api.init(directory.parent, "rus", TessBaseAPI.OEM_LSTM_ONLY))
        val json = Catalog.read(context).getJSONArray("cards")
        val cards = (0 until json.length()).map { i ->
            val c = json.getJSONObject(i); val ratings = c.getJSONObject("scores")
            Card(c.getString("name"), if (ratings.has("mage")) ratings.getInt("mage") else null)
        }
        val matcher = CardMatcher(cards)
        val expected = listOf("Йогг-Сарон Освобожденный", "Турагент Зефрис", "Дорнозму из Конца Времен")
        try {
            val results = expected.map { name ->
                val crop = Bitmap.createBitmap(800, 100, Bitmap.Config.ARGB_8888)
                Canvas(crop).apply {
                    drawColor(Color.BLACK)
                    drawText(name, 12f, 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE; textSize = 36f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    })
                }
                val debug = mutableListOf<String>()
                val match = try { OcrEngine.recognize(api, crop, matcher) { debug.add(it) } } finally { crop.recycle() }
                assertEquals("$name: ${debug.joinToString(" | ")}", name, match?.card?.name)
                match!!
            }
            assertEquals(listOf(97, 74, 70), results.map { it.card.score })
            assertEquals(expected[0], results.maxBy { it.card.score ?: -1 }.card.name)
            val blank = Bitmap.createBitmap(260, 54, Bitmap.Config.ARGB_8888)
            try { assertNull(OcrEngine.recognize(api, blank, matcher)) } finally { blank.recycle() }
        } finally { api.recycle() }
    }

    @Test fun deathKnightRatingsSurviveWrongInitialHero() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val cards=Catalog.cards(context,"mage")
        val expected=mapOf("Ночь вурдалаков" to 79,"Посох Примаса" to 108,"Кварцитовый крушитель" to 98,"Зловещая клетка душ" to 60,"Герой в криостазисе" to 89)
        expected.forEach { (name,score) ->
            val card=cards.first { it.name==name }
            assertEquals(score,card.score);assertNotNull(card.cost)
            assertEquals("death-knight",DraftEngine.inferClass(listOf(card)))
        }
    }

    @Test fun launcherOpensWithOfflineCatalog() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull(activity)
        instrumentation.runOnMainSync { activity.finish() }
    }
}
