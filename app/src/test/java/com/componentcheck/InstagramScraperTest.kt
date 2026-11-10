package com.componentcheck

import com.componentcheck.data.remote.InstagramScraper
import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramScraperTest {

    @Test
    fun testParseFormattedCount() {
        assertEquals(1234L, InstagramScraper.parseFormattedCount("1,234"))
        assertEquals(15000L, InstagramScraper.parseFormattedCount("15K"))
        assertEquals(15500L, InstagramScraper.parseFormattedCount("15.5k"))
        assertEquals(2000000L, InstagramScraper.parseFormattedCount("2M"))
        assertEquals(2400000L, InstagramScraper.parseFormattedCount("2.4m"))
        assertEquals(1000000000L, InstagramScraper.parseFormattedCount("1B"))
        assertEquals(0L, InstagramScraper.parseFormattedCount(""))
        assertEquals(0L, InstagramScraper.parseFormattedCount(null))
    }
}
