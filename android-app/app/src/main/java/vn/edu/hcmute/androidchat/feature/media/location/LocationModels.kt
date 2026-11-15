package vn.edu.hcmute.androidchat.feature.media.location

import android.content.Intent
import android.net.Uri
import java.util.Locale

data class LocationAttachment(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
) {
    fun formattedCoordinates(): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
}

object LocationPresets {
    val UTE_CAMPUS = LocationAttachment(
        latitude = 10.8507,
        longitude = 106.7719,
        label = "ĐH Sư phạm Kỹ thuật TP.HCM"
    )
}

object LocationSharePolicy {
    fun validate(location: LocationAttachment) {
        require(location.latitude.isFinite() && location.longitude.isFinite())
        require(location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0)
    }
}

fun LocationAttachment.toMapIntent(): Intent {
    LocationSharePolicy.validate(this)
    val labelPart = label?.takeIf { it.isNotBlank() }?.let { "(${Uri.encode(it)})" } ?: ""
    return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude$labelPart?q=$latitude,$longitude"))
}
