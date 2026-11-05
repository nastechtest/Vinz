package com.vinz.music

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TvBannerTest {
    @Test
    fun bannerUsesVinzLauncherArt() {
        val banner = File("src/main/res/drawable/tv_banner.xml")
        assertTrue("Vinz TV banner resource must be checked in", banner.isFile)
        val xml = banner.readText()
        assertTrue("banner keeps the 320dp by 180dp TV aspect ratio", xml.contains("320dp") && xml.contains("180dp"))
        assertTrue("banner uses the new Vinz logo", xml.contains("@drawable/vinz_logo"))
    }
}
