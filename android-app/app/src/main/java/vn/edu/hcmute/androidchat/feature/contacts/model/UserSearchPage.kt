package vn.edu.hcmute.androidchat.feature.contacts.model

import vn.edu.hcmute.androidchat.feature.auth.model.User

data class UserSearchPage(
    val items: List<User>,
    val nextCursor: String?,
    val hasMore: Boolean
)
