package io.github.bert0ns.cribrum.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FoldersTest {
    @Test
    fun `any folder of the path counts, the file name does not`() {
        assertTrue(isCameraFolder("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertTrue(isMessagingFolder("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/a.jpg"))
        assertTrue(isMessagingFolder("/storage/emulated/0/Pictures/Telegram/a.jpg"))
        assertFalse(isCameraFolder("/storage/emulated/0/Download/DCIM"))
        assertFalse(isMessagingFolder("/storage/emulated/0/Download/whatsapp.jpg"))
    }

    @Test
    fun `only a messaging app's sent folder is a sent folder`() {
        assertTrue(isSentFolder("/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/Sent/a.mp4"))
        assertTrue(isSentFolder("/sdcard/WhatsApp/Media/WhatsApp Video/sent/a.mp4"))
        assertFalse(isSentFolder("/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/a.mp4"))
        assertFalse(isSentFolder("/sdcard/Movies/Sent/a.mp4"))
        assertFalse(isSentFolder("/sdcard/WhatsApp/Media/WhatsApp Video/Sent")) // a file called Sent
    }

    @Test
    fun `an excluded folder covers its subfolders but not a sibling that shares its name`() {
        val excluded = listOf("/storage/emulated/0/Pictures/Family")

        assertTrue(isExcluded("/storage/emulated/0/Pictures/Family/a.jpg", excluded))
        assertTrue(isExcluded("/storage/emulated/0/Pictures/Family/2020/a.jpg", excluded))
        assertFalse(isExcluded("/storage/emulated/0/Pictures/Family2/a.jpg", excluded))
        assertFalse(isExcluded("/storage/emulated/0/Pictures/a.jpg", excluded))
        assertFalse(isExcluded("/storage/emulated/0/Pictures/Family/a.jpg", emptyList()))
    }

    @Test
    fun `messaging folders are the lab's`() {
        val lab = File("../../lab/src/cribrum_lab/folders.py").readText()
        val block = lab.substringAfter("_MESSAGING_FOLDERS = {").substringBefore("}")

        assertEquals(Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toSet(), MESSAGING_FOLDERS)
    }
}
