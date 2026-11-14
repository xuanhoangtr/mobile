package vn.edu.hcmute.androidchat.feature.media.sticker

data class Sticker(
    val id: String,
    val unicode: String,
    val label: String
)

data class StickerAttachment(
    val stickerId: String,
    val messageType: String = "STICKER"
)

object StickerCatalog {
    val stickers = listOf(
        Sticker("smile", "🙂", "Smile"),
        Sticker("heart", "❤️", "Heart"),
        Sticker("laugh", "😂", "Laugh"),
        Sticker("party", "🎉", "Party"),
        Sticker("thumbs-up", "👍", "Thumbs up"),
        Sticker("fire", "🔥", "Fire"),
        Sticker("love-eyes", "😍", "Love"),
        Sticker("cry-laugh", "🤣", "ROFL"),
        Sticker("wink", "😉", "Wink"),
        Sticker("cool", "😎", "Cool"),
        Sticker("hug", "🤗", "Hug"),
        Sticker("sad", "😢", "Sad"),
        Sticker("angry", "😡", "Angry"),
        Sticker("surprised", "😮", "Wow"),
        Sticker("clap", "👏", "Clap"),
        Sticker("pray", "🙏", "Thanks"),
        Sticker("star", "⭐", "Star"),
        Sticker("coffee", "☕", "Coffee")
    )

    fun find(id: String): Sticker? = stickers.firstOrNull { it.id == id }
}
