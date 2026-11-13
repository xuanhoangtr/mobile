package vn.edu.hcmute.androidchat.feature.contacts

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpException
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.contacts.data.ContactsRepository
import vn.edu.hcmute.androidchat.feature.contacts.model.UserSearchPage
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsViewModel

class ContactsViewModelTest {
    @Test
    fun invalidSearchDoesNotCallRepositoryAndNoMatchesAreRepresented() {
        val repository = FakeContactsRepository()
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)

        viewModel.onQueryChanged("a")
        viewModel.searchUsers()
        assertEquals(0, repository.searchCalls)
        assertEquals("invalid_search_query", viewModel.uiState.value.errorMessage)

        repository.searchResult = UserSearchPage(emptyList(), null, false)
        viewModel.onQueryChanged("nobody")
        viewModel.searchUsers()
        assertTrue(viewModel.uiState.value.hasSearched)
        assertTrue(viewModel.uiState.value.users.isEmpty())
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun searchAppendsNextCursorPageWithoutDuplicates() {
        val first = User(2L, null, "huy_two", "Huy Two")
        val second = User(3L, null, "huy_three", "Huy Three")
        val repository = FakeContactsRepository().apply {
            pages += UserSearchPage(listOf(first), "cursor-2", true)
            pages += UserSearchPage(listOf(first, second), null, false)
        }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        viewModel.onQueryChanged("huy")

        viewModel.searchUsers()
        viewModel.loadNextPage()

        assertEquals(listOf(first, second), viewModel.uiState.value.users)
        assertFalse(viewModel.uiState.value.hasMore)
        assertEquals(listOf(null, "cursor-2"), repository.cursors)
    }

    @Test
    fun phoneSearchReportsUndiscoverableUsersWithoutLeakingDetails() {
        val repository = FakeContactsRepository().apply { phoneFailure = AuthHttpException(404) }
        val viewModel = ContactsViewModel(repository, Dispatchers.Unconfined)
        viewModel.onPhoneChanged("+84912345678")

        viewModel.findByPhone()

        assertTrue(viewModel.uiState.value.phoneSearchCompleted)
        assertNull(viewModel.uiState.value.phoneResult)
        assertEquals("user_not_found", viewModel.uiState.value.errorMessage)
    }
}

private class FakeContactsRepository : ContactsRepository {
    var searchCalls = 0
    var searchResult = UserSearchPage(emptyList(), null, false)
    var phoneFailure: Exception? = null
    val pages = ArrayDeque<UserSearchPage>()
    val cursors = mutableListOf<String?>()

    override suspend fun searchUsers(query: String, cursor: String?, limit: Int): UserSearchPage {
        searchCalls += 1
        cursors += cursor
        return if (pages.isEmpty()) searchResult else pages.removeFirst()
    }

    override suspend fun findUserByPhone(phone: String): User {
        phoneFailure?.let { throw it }
        return User(4L, null, "found_user", "Found User")
    }
}
