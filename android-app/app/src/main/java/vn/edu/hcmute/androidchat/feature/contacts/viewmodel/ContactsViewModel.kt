package vn.edu.hcmute.androidchat.feature.contacts.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpException
import vn.edu.hcmute.androidchat.feature.auth.data.AuthNetworkException
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.contacts.data.ContactsRepository

data class ContactsUiState(
    val query: String = "",
    val phone: String = "",
    val users: List<User> = emptyList(),
    val phoneResult: User? = null,
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val phoneSearchCompleted: Boolean = false,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val errorMessage: String? = null
)

class ContactsViewModel(
    private val repository: ContactsRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val viewModelScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    fun onQueryChanged(value: String) = _uiState.update {
        it.copy(query = value, users = emptyList(), nextCursor = null, hasMore = false, hasSearched = false, errorMessage = null)
    }

    fun onPhoneChanged(value: String) = _uiState.update {
        it.copy(phone = value, phoneResult = null, phoneSearchCompleted = false, errorMessage = null)
    }

    fun searchUsers() {
        val query = _uiState.value.query.trim()
        if (query.length !in 2..80) {
            _uiState.update { it.copy(errorMessage = "invalid_search_query", hasSearched = false) }
            return
        }
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    users = emptyList(),
                    nextCursor = null,
                    hasMore = false,
                    hasSearched = false,
                    errorMessage = null
                )
            }
            try {
                val page = repository.searchUsers(query)
                _uiState.update {
                    it.copy(
                        users = page.items,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore,
                        hasSearched = true,
                        isLoading = false
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, hasSearched = true, errorMessage = exception.toUiError())
                }
            }
        }
    }

    fun loadNextPage() {
        val snapshot = _uiState.value
        val cursor = snapshot.nextCursor ?: return
        if (!snapshot.hasMore || snapshot.isLoading || snapshot.query.trim().length !in 2..80) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val page = repository.searchUsers(snapshot.query.trim(), cursor)
                _uiState.update {
                    it.copy(
                        users = (it.users + page.items).distinctBy(User::id),
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore,
                        isLoading = false
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = exception.toUiError()) }
            }
        }
    }

    fun findByPhone() {
        val phone = _uiState.value.phone.trim()
        if (phone.isBlank()) {
            _uiState.update { it.copy(errorMessage = "invalid_phone", phoneSearchCompleted = false) }
            return
        }
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, phoneResult = null, phoneSearchCompleted = false, errorMessage = null) }
            try {
                val user = repository.findUserByPhone(phone)
                _uiState.update {
                    it.copy(phoneResult = user, phoneSearchCompleted = true, isLoading = false)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        phoneSearchCompleted = true,
                        isLoading = false,
                        errorMessage = exception.toUiError()
                    )
                }
            }
        }
    }

    override fun onCleared() {
        viewModelScope.cancel()
        super.onCleared()
    }

    private fun Exception.toUiError(): String = when (this) {
        is AuthHttpException -> when (statusCode) {
            401 -> "session_expired"
            400 -> "invalid_phone"
            404 -> "user_not_found"
            503 -> "service_unavailable"
            else -> "request_failed"
        }
        is AuthNetworkException -> "network_unavailable"
        else -> "request_failed"
    }
}
