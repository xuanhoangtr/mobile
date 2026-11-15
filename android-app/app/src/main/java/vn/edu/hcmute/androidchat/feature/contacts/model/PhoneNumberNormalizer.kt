package vn.edu.hcmute.androidchat.feature.contacts.model

/** Normalizes Vietnamese national numbers and validates the resulting E.164 value. */
object PhoneNumberNormalizer {
    private val formattingCharacters = Regex("[\\s().-]")
    private val e164Pattern = Regex("^\\+[1-9][0-9]{7,14}$")

    fun toE164(rawPhone: String): String? {
        val compact = rawPhone.trim().replace(formattingCharacters, "")
        if (compact.isEmpty()) return null

        val candidate = when {
            compact.startsWith("00") -> "+${compact.drop(2)}"
            compact.startsWith("0") -> "+84${compact.drop(1)}"
            else -> compact
        }
        return candidate.takeIf(e164Pattern::matches)
    }
}
