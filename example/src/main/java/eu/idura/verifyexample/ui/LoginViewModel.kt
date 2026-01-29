package eu.idura.verifyexample.ui

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.idura.verify.IduraVerify
import eu.idura.verify.eid.EID
import eu.idura.verifyexample.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class LoginState {
  class LoggedIn(
    val idToken: String,
    val name: String?,
    val sub: String,
    val identityscheme: String,
  ) : LoginState()

  class NotLoggedIn(
    var errorMessage: String? = null,
  ) : LoginState()

  class Loading : LoginState()
}

class LoginViewModel(
  initialState: LoginState,
  activity: ComponentActivity?,
) : ViewModel() {
  // The Idura verify instance cannot be instantiated until we have an activity. However, we don't have an activity in compose previews. In order to avoid null checks, we make it a lateinit property
  private lateinit var iduraVerify: IduraVerify
  private val _uiState = MutableStateFlow(initialState)
  val uiState: StateFlow<LoginState> = _uiState.asStateFlow()

  init {
    if (activity != null) {
      iduraVerify =
        IduraVerify(
          BuildConfig.IDURA_CLIENT_ID,
          BuildConfig.IDURA_DOMAIN,
          activity = activity,
        )
    }
  }

  fun login(eid: EID<*>) =
    viewModelScope.launch {
      _uiState.update { LoginState.Loading() }
      try {
        val jwt = iduraVerify.login(eid)

        _uiState.update {
          LoginState.LoggedIn(
            jwt.token,
            jwt.getClaimAsString("name"),
            jwt.subject,
            jwt.identityScheme,
          )
        }
      } catch (ex: Throwable) {
        _uiState.update { LoginState.NotLoggedIn(ex.localizedMessage) }
      }
    }

  fun logout() =
    viewModelScope.launch {
      val idToken = (_uiState.value as? LoginState.LoggedIn)?.idToken
      _uiState.update { LoginState.Loading() }
      try {
        iduraVerify.logout(idToken)
        _uiState.update { LoginState.NotLoggedIn() }
      } catch (ex: Exception) {
        _uiState.update { LoginState.NotLoggedIn(ex.localizedMessage) }
      }
    }
}
