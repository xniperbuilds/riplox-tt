package com.xniperbuilds.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Levels are the ones measured on real TikTok renditions (16-Nov-2026). */
class LoudnessTest {
    private fun f(lufs: Double, peak: Double) = Loudness.filterFor(Loudness.Level(lufs, peak))

    @Test fun loudFilesAreLeftAlone() {
        assertNull(f(-6.9, 1.4))   // muslimfirstofficial
        assertNull(f(-12.8, 0.1))  // zachking 2019
        assertNull(f(-14.4, -4.1)) // arslankimemes h265
        assertNull(f(-16.0, -6.0)) // exactly on the line
    }

    @Test fun quietWithHeadroomGetsPlainGain() {
        // -26.0 LUFS, peak -15.2 dB: +12 dB lands the peak at -3.2 — no limiter needed
        assertEquals("volume=12.0dB", f(-26.0, -15.2))
    }

    @Test fun quietButPeakyGetsALimiter() {
        // -21.1 LUFS, peak -5.9: +7.1 dB would push the peak to +1.2 → limiter
        assertEquals("volume=7.1dB,alimiter=limit=0.89:level=false", f(-21.1, -5.9))
    }

    @Test fun nearSilenceIsCapped() {
        assertEquals("volume=15.0dB", f(-40.0, -30.0))
    }
}
