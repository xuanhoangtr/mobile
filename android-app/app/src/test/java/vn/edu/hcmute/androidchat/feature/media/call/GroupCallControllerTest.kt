package vn.edu.hcmute.androidchat.feature.media.call

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupCallControllerTest {
    @Test
    fun participantsCanJoinAndLeaveExperimentalCall() {
        val controller = GroupCallController()
        controller.join("group-call", 7L)
        controller.join("group-call", 8L)
        controller.join("group-call", 9L)
        assertEquals(setOf(7L, 8L, 9L), controller.state.participantIds)

        controller.leave(8L)
        assertEquals(setOf(7L, 9L), controller.state.participantIds)
        controller.leave(7L)
        controller.leave(9L)
        assertEquals(GroupCallPhase.LEFT, controller.state.phase)
    }
}
