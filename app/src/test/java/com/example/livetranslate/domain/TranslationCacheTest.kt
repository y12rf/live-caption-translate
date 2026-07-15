package com.example.livetranslate.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCacheTest {

    @Test
    fun languageIsolation_preventsWrongHit() {
        val cache = TranslationCache(50)
        val enZh = TranslationCache.Key.of("Hello", "en", "zh", "gpt")!!
        val enJa = TranslationCache.Key.of("Hello", "en", "ja", "gpt")!!
        cache.put(enZh, "你好")
        assertEquals("你好", cache.get(enZh))
        assertNull("must not return Chinese for Japanese target", cache.get(enJa))
    }

    @Test
    fun modelIsolation() {
        val cache = TranslationCache(50)
        val a = TranslationCache.Key.of("Hi", "en", "zh", "model-a")!!
        val b = TranslationCache.Key.of("Hi", "en", "zh", "model-b")!!
        cache.put(a, "嗨")
        assertNull(cache.get(b))
    }

    @Test
    fun normalizeSource_trimsAndCollapsesSpace() {
        val k1 = TranslationCache.Key.of("  Hello   world ", "EN", "ZH", "M")!!
        val k2 = TranslationCache.Key.of("Hello world", "en", "zh", "m")!!
        assertEquals(k1, k2)
    }

    @Test
    fun maxSize_evictsOldest() {
        val cache = TranslationCache(3)
        fun key(i: Int) = TranslationCache.Key.of("s$i", "en", "zh", "m")!!
        cache.put(key(1), "一")
        cache.put(key(2), "二")
        cache.put(key(3), "三")
        assertEquals(3, cache.size)
        cache.put(key(4), "四")
        assertEquals(3, cache.size)
        assertNull(cache.get(key(1)))
        assertEquals("二", cache.get(key(2)))
        assertEquals("四", cache.get(key(4)))
    }

    @Test
    fun rePut_movesToNewest_andEvictsOlder() {
        val cache = TranslationCache(2)
        val a = TranslationCache.Key.of("a", "en", "zh", "m")!!
        val b = TranslationCache.Key.of("b", "en", "zh", "m")!!
        val c = TranslationCache.Key.of("c", "en", "zh", "m")!!
        cache.put(a, "A")
        cache.put(b, "B")
        cache.put(a, "A2") // a becomes newest; eldest is b
        cache.put(c, "C")  // evict b
        assertEquals("A2", cache.get(a))
        assertNull(cache.get(b))
        assertEquals("C", cache.get(c))
    }

    @Test
    fun clear_discardsAll() {
        val cache = TranslationCache(50)
        val k = TranslationCache.Key.of("x", "en", "zh", "m")!!
        cache.put(k, "某")
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get(k))
    }

    @Test
    fun emptySource_noKey() {
        assertNull(TranslationCache.Key.of("   ", "en", "zh", "m"))
        assertNull(TranslationCache.Key.of("", "en", "zh", "m"))
    }

    @Test
    fun putEmptyTranslation_ignored() {
        val cache = TranslationCache(10)
        val k = TranslationCache.Key.of("x", "en", "zh", "m")!!
        cache.put(k, "  ")
        assertTrue(!cache.contains(k))
    }
}
