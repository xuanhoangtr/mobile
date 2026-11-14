package vn.edu.hcmute.androidchat.feature.media.location

import android.content.Intent
import android.net.Uri

data class LocationAttachment(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
)

object LocationSharePolicy {
    fun validate(location: LocationAttachment) {
        require(location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0)
        require(location.latitude.isFinite() && location.longitude.isFinite())
    }
}

fun LocationAttachment.toMapIntent(): Intent {
    LocationSharePolicy.validate(this)
    val labelPart = label?.takeIf { it.isNotBlank() }?.let { "(${Uri.encode(it)})" } ?: ""
    return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude$labelPart?q=$latitude,$longitude"))
}
