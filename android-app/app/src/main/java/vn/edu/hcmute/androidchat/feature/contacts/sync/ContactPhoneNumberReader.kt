package vn.edu.hcmute.androidchat.feature.contacts.sync

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads only phone-number values after the UI has obtained consent and READ_CONTACTS permission. */
object ContactPhoneNumberReader {
    suspend fun readPhoneNumbers(context: Context): List<String> = withContext(Dispatchers.IO) {
        val projection = arrayOf(Phone.NUMBER)
        val cursor = context.contentResolver.query(
            Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        ) ?: return@withContext emptyList()

        cursor.use { result ->
            val numberColumn = result.getColumnIndexOrThrow(Phone.NUMBER)
            val numbers = linkedSetOf<String>()
            while (result.moveToNext()) {
                result.getString(numberColumn)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(numbers::add)
            }
            numbers.toList()
        }
    }
}
