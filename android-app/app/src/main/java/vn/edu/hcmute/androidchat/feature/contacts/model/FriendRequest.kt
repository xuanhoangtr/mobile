package vn.edu.hcmute.androidchat.feature.contacts.model

import vn.edu.hcmute.androidchat.feature.auth.model.User

enum class FriendRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELED
}

enum class FriendRequestDirection(val queryValue: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing")
}

/** Friend request fields returned by the AUTH API; timestamps use server ISO-8601 strings. */
data class FriendRequest(
    val id: String,
    val sender: User,
    val recipient: User,
    val status: FriendRequestStatus,
    val createdAt: String,
    val respondedAt: String?
)

data class PhoneDiscoverySettings(
    val phoneVerified: Boolean,
    val discoverable: Boolean
)

data class ConnectionsSnapshot(
    val friends: List<User>,
    val incomingRequests: List<FriendRequest>,
    val outgoingRequests: List<FriendRequest>,
    val phoneDiscovery: PhoneDiscoverySettings
)
