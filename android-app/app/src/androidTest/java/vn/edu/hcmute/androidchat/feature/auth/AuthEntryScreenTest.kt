package vn.edu.hcmute.androidchat.feature.auth

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import vn.edu.hcmute.androidchat.R
import vn.edu.hcmute.androidchat.feature.auth.data.AuthRepository
import vn.edu.hcmute.androidchat.feature.auth.model.AuthResult
import vn.edu.hcmute.androidchat.feature.auth.model.User
import vn.edu.hcmute.androidchat.feature.auth.ui.AuthEntryScreen
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthViewModel
import kotlinx.coroutines.Dispatchers

class AuthEntryScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loginFormDisplaysLocalizedEmailAndPasswordFields() {
        val viewModel = AuthViewModel(FakeAuthRepository(), Dispatchers.Unconfined)

        composeRule.setContent { AuthEntryScreen(viewModel = viewModel) }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.auth_email)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.auth_password)).assertIsDisplayed()
    }
}

private class FakeAuthRepository : AuthRepository {
    override suspend fun register(email: String, password: String, displayName: String, username: String) =
        AuthResult(User(1L, email, username, displayName), "test-session")

    override suspend fun login(email: String, password: String) =
        AuthResult(User(1L, email, "test_user", "Test User"), "test-session")

    override suspend fun logout() = true

    override suspend fun currentUser() = User(1L, "test@example.com", "test_user", "Test User")

    override suspend fun updateProfile(displayName: String?, bio: String?) =
        User(1L, "test@example.com", "test_user", displayName ?: "Test User", bio = bio)
}
