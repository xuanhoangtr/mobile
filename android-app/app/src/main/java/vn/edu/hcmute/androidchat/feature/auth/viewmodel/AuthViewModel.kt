package vn.edu.hcmute.androidchat.feature.auth.viewmodel

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
import vn.edu.hcmute.androidchat.feature.auth.data.AuthRepository
import vn.edu.hcmute.androidchat.feature.auth.model.AuthResult
import vn.edu.hcmute.androidchat.feature.auth.validation.AuthValidation

enum class AuthFormMode { LOGIN, REGISTER }

data class AuthUiState(
    val mode: AuthFormMode = AuthFormMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val username: String = "",
    val displayName: String = "",
    val isLoading: Boolean = false,
    val isRestoringSession: Boolean = true,
    val errorMessage: String? = null,
    val validationError: String? = null,
    val authenticatedUser: AuthResult? = null,
    val profileDisplayName: String = "",
    val profileBio: String = "",
    val profileSaved: Boolean = false
)

class AuthViewModel(
    private val repository: AuthRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val viewModelScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        restoreSession()
    }

    fun onEmailChanged(value: String) = _uiState.update {
        it.copy(email = value, errorMessage = null, validationError = null)
    }

    fun onPasswordChanged(value: String) = _uiState.update {
        it.copy(password = value, errorMessage = null, validationError = null)
    }

    fun onUsernameChanged(value: String) = _uiState.update {
        it.copy(username = value, errorMessage = null, validationError = null)
    }

    fun onDisplayNameChanged(value: String) = _uiState.update {
        it.copy(displayName = value, errorMessage = null, validationError = null)
    }

    fun onProfileDisplayNameChanged(value: String) = _uiState.update {
        it.copy(profileDisplayName = value, errorMessage = null, validationError = null, profileSaved = false)
    }

    fun onProfileBioChanged(value: String) = _uiState.update {
        it.copy(profileBio = value, errorMessage = null, validationError = null, profileSaved = false)
    }

    fun setMode(mode: AuthFormMode) = _uiState.update {
        it.copy(mode = mode, errorMessage = null, validationError = null)
    }

    fun submit() {
        val submitted = _uiState.value
        if (submitted.isLoading) return

        val validationError = validate(submitted)
        if (validationError != null) {
            _uiState.update { it.copy(validationError = validationError, errorMessage = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, validationError = null) }
            try {
                val result = if (submitted.mode == AuthFormMode.REGISTER) {
                    repository.register(
                        email = submitted.email.trim(),
                        password = submitted.password,
                        displayName = submitted.displayName.trim(),
                        username = submitted.username.trim()
                    )
                } else {
                    repository.login(submitted.email.trim(), submitted.password)
                }
                _uiState.update {
                    it.copy(
                        password = "",
                        isLoading = false,
                        isRestoringSession = false,
                        authenticatedUser = result,
                        profileDisplayName = result.user.displayName,
                        profileBio = result.user.bio.orEmpty(),
                        profileSaved = false,
                        errorMessage = null
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = exception.toUiError()
                    )
                }
            }
        }
    }

    fun saveProfile() {
        val submitted = _uiState.value
        val currentUser = submitted.authenticatedUser?.user ?: return
        if (submitted.isLoading) return

        AuthValidation.displayNameError(submitted.profileDisplayName)?.let { error ->
            _uiState.update { it.copy(validationError = error, errorMessage = null) }
            return
        }
        AuthValidation.bioError(submitted.profileBio)?.let { error ->
            _uiState.update { it.copy(validationError = error, errorMessage = null) }
            return
        }

        val displayName = submitted.profileDisplayName.trim().takeIf { it != currentUser.displayName }
        val bio = submitted.profileBio.trim().takeIf { it != currentUser.bio.orEmpty() }
        if (displayName == null && bio == null) {
            _uiState.update { it.copy(profileSaved = true, validationError = null, errorMessage = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, validationError = null) }
            try {
                val updatedUser = repository.updateProfile(displayName, bio)
                _uiState.update { state ->
                    val current = state.authenticatedUser ?: return@update state.copy(isLoading = false)
                    state.copy(
                        authenticatedUser = current.copy(user = updatedUser),
                        profileDisplayName = updatedUser.displayName,
                        profileBio = updatedUser.bio.orEmpty(),
                        profileSaved = true,
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

    fun logout() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, validationError = null) }
            try {
                if (!repository.logout()) {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "logout_failed")
                    }
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        email = "",
                        password = "",
                        isLoading = false,
                        authenticatedUser = null,
                        profileDisplayName = "",
                        profileBio = "",
                        profileSaved = false,
                        errorMessage = null
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = exception.toUiError()) }
            }
        }
    }

    private fun restoreSession() {
        viewModelScope.launch {
            try {
                val restored = repository.restoreSession()
                _uiState.update {
                    it.copy(
                        isRestoringSession = false,
                        authenticatedUser = restored,
                        profileDisplayName = restored?.user?.displayName.orEmpty(),
                        profileBio = restored?.user?.bio.orEmpty()
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isRestoringSession = false,
                        authenticatedUser = null,
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

    private fun validate(state: AuthUiState): String? {
        AuthValidation.emailError(state.email)?.let { return it }
        AuthValidation.passwordError(state.password)?.let { return it }
        if (state.mode == AuthFormMode.REGISTER) {
            AuthValidation.usernameError(state.username)?.let { return it }
            AuthValidation.displayNameError(state.displayName)?.let { return it }
        }
        return null
    }

    private fun Exception.toUiError(): String = when (this) {
        is AuthHttpException -> when (statusCode) {
            401 -> "invalid_credentials"
            409 -> "account_already_exists"
            503 -> "service_unavailable"
            else -> "request_failed"
        }
        is AuthNetworkException -> "network_unavailable"
        else -> "request_failed"
    }
}
