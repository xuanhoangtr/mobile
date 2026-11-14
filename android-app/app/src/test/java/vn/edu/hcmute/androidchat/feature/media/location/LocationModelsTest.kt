package vn.edu.hcmute.androidchat.feature.media.location

import org.junit.Assert.assertEquals
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

    @Test
    fun presetUteCampusIsValidAndFormatsCoordinates() {
        LocationSharePolicy.validate(LocationPresets.UTE_CAMPUS)
        assertEquals("10.85070, 106.77190", LocationPresets.UTE_CAMPUS.formattedCoordinates())
    }
}
