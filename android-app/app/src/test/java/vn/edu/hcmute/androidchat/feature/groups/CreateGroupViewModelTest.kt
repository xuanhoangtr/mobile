package vn.edu.hcmute.androidchat.feature.groups

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.groups.viewmodel.CreateGroupViewModel

class CreateGroupViewModelTest {
    @Test
    fun groupCreationRequiresNonBlankNameAndAtLeastTwoSelectedPeers() {
        val vm = CreateGroupViewModel()
        assertFalse(vm.uiState.value.canCreateGroup)

        vm.onGroupNameChanged("Nhom Android")
        vm.toggleMemberSelection(1L)
        assertFalse(vm.uiState.value.canCreateGroup)

        vm.toggleMemberSelection(2L)
        assertTrue(vm.uiState.value.canCreateGroup)

        vm.toggleMemberSelection(1L)
        assertFalse(vm.uiState.value.canCreateGroup)
    }
}
