package vn.edu.hcmute.androidchat.feature.media.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StickerCatalogTest {
    @Test
    fun catalogUsesStableIdsAndKnownRenderingData() {
        val sticker = StickerCatalog.find("thumbs-up")
        assertNotNull(sticker)
        assertEquals("👍", sticker?.unicode)
        assertEquals("STICKER", StickerAttachment(sticker!!.id).messageType)
    }

    @Test
    fun catalogContainsEighteenUniqueStickersIncludingOriginalSix() {
        assertEquals(18, StickerCatalog.stickers.size)
        assertEquals(18, StickerCatalog.stickers.map { it.id }.toSet().size)
        listOf("smile", "heart", "laugh", "party", "thumbs-up", "fire").forEach { id ->
            assertNotNull("Missing original sticker id=$id", StickerCatalog.find(id))
        }
    }
}
