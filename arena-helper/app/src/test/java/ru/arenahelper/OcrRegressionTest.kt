package ru.arenahelper

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

class OcrRegressionTest {
    private val matcher = CardMatcher(listOf(Card("Йогг-Сарон Освобожденный",97),Card("Турагент Зефрис",74),Card("Дорнозму из Конца Времен",70)))
    @Test fun emptySingleAndMultilineInputsNeverIndexEmptyLists() {
        for (text in listOf("", " ", "\n\n", ".", "а", "abc", "Турагент Зефрис", "Турагент\nЗефрис")) matcher.match(text)
        assertEquals(emptySet<String>(), CardMatcher.segments("\n \n"))
        assertNull(matcher.match(""))
        assertEquals("Турагент Зефрис", matcher.match("Турагент\nЗефрис")?.card?.name)
    }
    @Test fun emptyCatalogAndDuplicateNamesAreSafe() {
        assertNull(CardMatcher(emptyList()).match("Огненный шар"))
        val m = CardMatcher(listOf(Card("Турагент Зефрис",null),Card("Турагент Зефрис",74)))
        assertEquals(74,m.match("Турагент Зефрис")?.card?.score)
    }
    @Test fun screenshotOcrTyposResolveToFullNames() {
        assertEquals("Йогг-Сарон Освобожденный",matcher.match("Могг.Сарон освобожаЕнны")?.card?.name)
        assertEquals("Дорнозму из Конца Времен",matcher.match("Дорнозму из коннаВремев,")?.card?.name)
        assertEquals("Турагент Зефрис",matcher.match("Турагент Зефрис")?.card?.name)
    }
    @Test fun processesEveryPixelAndUsesActualNumericThreshold() {
        val input = intArrayOf(-1,0xff000000.toInt(),0xffbbbbbb.toInt(),0xffeeeeee.toInt())
        assertArrayEquals(intArrayOf(0xff000000.toInt(),-1,-1,0xff000000.toInt()),TitlePixels.prepare(input,2,2,200,0f))
    }
    @Test fun frameDecoderHandlesPaddingAndIncompleteImages() {
        assertNull(FramePixels.decode(ByteBuffer.allocate(0),1,1,4,4))
        assertNull(FramePixels.decode(ByteBuffer.allocate(3),1,1,4,4))
        assertNull(FramePixels.decode(ByteBuffer.allocate(4),1,1,4,0))
        val buffer = ByteBuffer.wrap(byteArrayOf(-1,0,0,-1, 9,9,9,9, 0,-1,0,-1))
        assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt()),FramePixels.decode(buffer,1,2,8,4))
        buffer.position(8)
        assertArrayEquals(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt()),FramePixels.decode(buffer,1,2,8,4))
    }
    @Test fun fuzzEmptyAndNoisyText() {
        val random = Random(123)
        repeat(250) {
            val s = buildString { repeat(random.nextInt(0,70)) { append(" \n\r.,!?абвXYZ123"[random.nextInt(16)]) } }
            matcher.match(s)
        }
    }
}
