package vn.edu.hcmute.androidchat.feature.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import vn.edu.hcmute.androidchat.BuildConfig
import vn.edu.hcmute.androidchat.feature.auth.data.AuthHttpApi
import vn.edu.hcmute.androidchat.feature.auth.data.AuthRepository
import vn.edu.hcmute.androidchat.feature.auth.data.KeystoreAuthSessionStore
import vn.edu.hcmute.androidchat.feature.auth.data.RemoteAuthRepository
import vn.edu.hcmute.androidchat.feature.auth.data.UrlConnectionAuthTransport
import vn.edu.hcmute.androidchat.feature.auth.session.AuthSessionManager
import vn.edu.hcmute.androidchat.feature.auth.session.AuthTokenProvider
import vn.edu.hcmute.androidchat.feature.auth.viewmodel.AuthViewModel
import vn.edu.hcmute.androidchat.feature.contacts.data.ContactsRepository
import vn.edu.hcmute.androidchat.feature.contacts.data.RemoteContactsRepository
import vn.edu.hcmute.androidchat.feature.contacts.viewmodel.ContactsViewModel

/** AUTH dependencies are constructed here so the host supplies its environment's backend URL. */
class AuthFeature(context: Context, backendBaseUrl: String) {
    private val transport = UrlConnectionAuthTransport(
        baseUrl = backendBaseUrl,
        allowLoopbackHttp = BuildConfig.DEBUG
    )
    private val api = AuthHttpApi(transport)
    private val sessionManager = AuthSessionManager(
        store = KeystoreAuthSessionStore(context),
        api = api
    )

    val repository: AuthRepository = RemoteAuthRepository(api, sessionManager)
    val contactsRepository: ContactsRepository = RemoteContactsRepository(transport, sessionManager)
    val tokenProvider: AuthTokenProvider = sessionManager
    val viewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AuthViewModel::class.java))
            return AuthViewModel(repository) as T
        }
    }
    val contactsViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ContactsViewModel::class.java))
            return ContactsViewModel(contactsRepository) as T
        }
    }
}
