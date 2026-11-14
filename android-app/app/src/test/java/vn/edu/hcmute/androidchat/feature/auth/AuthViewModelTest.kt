package vn.edu.hcmute.androidchat.feature.auth

import kotlinx.coroutines.Dispatchers
import vn.edu.hcmute.androidchat.feature.auth.data.AuthRepository
import vn.edu.hcmute.androidchat.feature.auth.data.AuthNetworkException
import vn.edu.hcmute.androidchat.feature.auth.model.AuthResult
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.auth.validation.AuthValidation
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthFormMode
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthViewModelTest {
    @Test
    fun validationMatchesBackendEmailUsernameAndPasswordRules() {
        assertNull(AuthValidation.emailError(" Huy@example.com "))
        assertEquals("invalid_email", AuthValidation.emailError("not-an-email"))
        assertNull(AuthValidation.passwordError("strong-pass-123"))
        assertEquals("password_too_short", AuthValidation.passwordError("short"))
        assertEquals("password_too_long", AuthValidation.passwordError("é".repeat(37)))
        assertNull(AuthValidation.usernameError("huy_dc7"))
        assertEquals("invalid_username", AuthValidation.usernameError("ab"))
    }

    @Test
    fun invalidLoginIsRejectedBeforeRepositoryCall() {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository, Dispatchers.Unconfined)

        viewModel.onEmailChanged("bad")
        viewModel.onPasswordChanged("short")
        viewModel.submit()

        assertEquals("invalid_email", viewModel.uiState.value.validationError)
        assertEquals(0, repository.loginCalls)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun loginSuccessClearsPasswordAndEmitsAuthenticatedUser() {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository, Dispatchers.Unconfined)
        val result = AuthResult(User(7L, "huy@example.com", "huydc7", "Huy"), "session-1")
        repository.loginResult = result

        viewModel.onEmailChanged("huy@example.com")
        viewModel.onPasswordChanged("strong-password-123")
        viewModel.submit()

        assertEquals(1, repository.loginCalls)
        assertEquals(result, viewModel.uiState.value.authenticatedUser)
        assertEquals("", viewModel.uiState.value.password)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isRestoringSession)
    }

    @Test
    fun registerModeValidatesProfileFieldsAndKeepsFormStateWhenModeChanges() {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository, Dispatchers.Unconfined)
        viewModel.onEmailChanged("huy@example.com")
        viewModel.onPasswordChanged("strong-password-123")
        viewModel.setMode(AuthFormMode.REGISTER)
        viewModel.submit()

        assertEquals("invalid_username", viewModel.uiState.value.validationError)
        assertEquals("huy@example.com", viewModel.uiState.value.email)
        assertEquals(AuthFormMode.REGISTER, viewModel.uiState.value.mode)
        assertEquals(0, repository.registerCalls)
    }

    @Test
    fun restoredSessionOpensAccountWithoutShowingLoginForm() {
        val repository = FakeAuthRepository().apply {
            restoredResult = AuthResult(User(5L, "saved@example.com", "saved_user", "Saved User"), "saved-session")
        }
        val viewModel = AuthViewModel(repository, Dispatchers.Unconfined)

        assertFalse(viewModel.uiState.value.isRestoringSession)
        assertEquals(repository.restoredResult, viewModel.uiState.value.authenticatedUser)
        assertEquals("Saved User", viewModel.uiState.value.profileDisplayName)
    }

    @Test
    fun profileChangesAreValidatedAndSavedThroughRepository() {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository, Dispatchers.Unconfined)
        viewModel.onEmailChanged("test@example.com")
        viewModel.onPasswordChanged("strong-password-123")
        viewModel.submit()
        viewModel.onProfileDisplayNameChanged("Updated Name")
        viewModel.onProfileBioChanged("AUTH owner")

        viewModel.saveProfile()

        assertEquals("Updated Name", viewModel.uiState.value.authenticatedUser?.user?.displayName)
        assertEquals("AUTH owner", viewModel.uiState.value.authenticatedUser?.user?.bio)
        assertTrue(viewModel.uiState.value.profileSaved)
        assertEquals(1, repository.profileUpdateCalls)
    }

    @Test
    fun logoutClearsAuthenticatedUserAfterServerRevokesSession() {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository, Dispatchers.Unconfined)
        viewModel.onEmailChanged("test@example.com")
        viewModel.onPasswordChanged("strong-password-123")
        viewModel.submit()

        viewModel.logout()

        assertNull(viewModel.uiState.value.authenticatedUser)
        assertEquals("", viewModel.uiState.value.password)
    }

    @Test
    fun logoutFailureKeepsAuthenticatedUserAndShowsFailureState() {
        val repository = FakeAuthRepository().apply { logoutResult = false }
        val viewModel = authenticatedViewModel(repository)

        viewModel.logout()

        assertEquals(repository.loginResult, viewModel.uiState.value.authenticatedUser)
        assertEquals("logout_failed", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun logoutNetworkFailureKeepsAuthenticatedUserAndShowsNetworkError() {
        val repository = FakeAuthRepository().apply {
            logoutFailure = AuthNetworkException("Connection lost", IllegalStateException("socket closed"))
        }
        val viewModel = authenticatedViewModel(repository)

        viewModel.logout()

        assertEquals(repository.loginResult, viewModel.uiState.value.authenticatedUser)
        assertEquals("network_unavailable", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    private fun authenticatedViewModel(repository: FakeAuthRepository): AuthViewModel {
        val viewModel = AuthViewModel(repository, Dispatchers.Unconfined)
        viewModel.onEmailChanged("test@example.com")
        viewModel.onPasswordChanged("strong-password-123")
        viewModel.submit()
        return viewModel
    }
}

private class FakeAuthRepository : AuthRepository {
    var loginCalls = 0
    var registerCalls = 0
    var profileUpdateCalls = 0
    var loginResult = AuthResult(User(1L, "test@example.com", "test_user", "Test"), "test-session")
    var restoredResult: AuthResult? = null
    var logoutResult = true
    var logoutFailure: Throwable? = null

    override suspend fun register(email: String, password: String, displayName: String, username: String): AuthResult {
        registerCalls += 1
        return loginResult
    }

    override suspend fun login(email: String, password: String): AuthResult {
        loginCalls += 1
        return loginResult
    }

    override suspend fun logout(): Boolean {
        logoutFailure?.let { throw it }
        return logoutResult
    }

    override suspend fun currentUser() = loginResult.user

    override suspend fun restoreSession() = restoredResult

    override suspend fun updateProfile(displayName: String?, bio: String?): User {
        profileUpdateCalls += 1
        val user = loginResult.user.copy(
            displayName = displayName ?: loginResult.user.displayName,
            bio = if (bio == null) loginResult.user.bio else bio.ifEmpty { null }
        )
        loginResult = loginResult.copy(user = user)
        return user
    }
}
