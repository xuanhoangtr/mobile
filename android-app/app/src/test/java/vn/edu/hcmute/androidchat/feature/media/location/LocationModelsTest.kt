package vn.edu.hcmute.androidchat.feature.media.location

import org.junit.Test

class LocationModelsTest {
    @Test
    fun validLocationPassesPolicy() {
        val location = LocationAttachment(47.3977, 8.5456, "Office")
        LocationSharePolicy.validate(location)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLatitudeIsRejected() {
        LocationSharePolicy.validate(LocationAttachment(91.0, 0.0))
    }
}
