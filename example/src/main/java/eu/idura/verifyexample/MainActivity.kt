package eu.idura.verifyexample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.idura.verify.eid.DanishMitID
import eu.idura.verify.eid.EID
import eu.idura.verify.eid.Mock
import eu.idura.verify.eid.NorwegianBankID
import eu.idura.verify.eid.SwedishBankID
import eu.idura.verifyexample.ui.LoginState
import eu.idura.verifyexample.ui.LoginViewModel
import eu.idura.verifyexample.ui.theme.IduraVerifyAndroidTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
  val loginViewModel = LoginViewModel(LoginState.NotLoggedIn(), this)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      IduraVerifyAndroidTheme {
        MainScreen(loginViewModel)
      }
    }
  }
}

@Composable
fun MainScreen(loginViewModel: LoginViewModel) {
  val loginState by loginViewModel.uiState.collectAsState()

  return when (loginState) {
    is LoginState.LoggedIn -> {
      LoggedInScreen(loginState as LoginState.LoggedIn) {
        loginViewModel.logout()
      }
    }

    is LoginState.NotLoggedIn -> {
      LoginScreen(loginState as LoginState.NotLoggedIn) { eid ->
        loginViewModel.login(eid)
      }
    }

    is LoginState.Loading -> {
      LoadingScreen()
    }
  }
}

@Preview(showBackground = true)
@Composable
fun LoadingScreen() {
  Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
    Box(
      modifier =
        Modifier
          .padding(innerPadding)
          .fillMaxSize(),
    ) {
      Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Spacer(modifier = Modifier.height(300.dp))
          CircularProgressIndicator(modifier = Modifier.width(100.dp))
        }
      }
    }
  }
}

class LoginStateViewModelLoggedInProvider : PreviewParameterProvider<LoginState.LoggedIn> {
  override val values =
    listOf(
      LoginState.LoggedIn(
        idToken = "",
        name = "Foo bar",
        sub = "{ab4f92c7-0bba-4b94-b1e6-a7694386c247}",
        identityscheme = "mock",
      ),
    ).asSequence()
}

@Preview(showBackground = true)
@Composable
fun LoggedInScreen(
  @PreviewParameter(LoginStateViewModelLoggedInProvider::class) loginState: LoginState.LoggedIn,
  onLogout: (() -> Unit)? = null,
) {
  Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
    Box(
      modifier =
        Modifier
          .padding(innerPadding)
          .fillMaxSize(),
    ) {
      Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
      ) {
        Column(
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Spacer(modifier = Modifier.height(300.dp))
          Text(text = "Logged in!", fontSize = 30.sp)
          Spacer(modifier = Modifier.height(50.dp))

          Text(
            text = "Identityscheme:",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(10.dp),
          )
          Text(text = loginState.identityscheme)

          Text(
            text = "Sub:",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(10.dp),
          )
          Text(text = loginState.sub)

          Spacer(modifier = Modifier.height(10.dp))

          Text(
            text = "Name:",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(10.dp),
          )
          Text(text = loginState.name ?: "")
        }
      }
      Button(
        { onLogout?.invoke() },
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 10.dp),
      ) {
        Text("Log out")
      }
    }
  }
}

class LoginStateViewModelNotLoggedInProvider : PreviewParameterProvider<LoginState.NotLoggedIn> {
  override val values =
    listOf(
      LoginState.NotLoggedIn(),
    ).asSequence()
}

@Preview(showBackground = true)
@Composable
fun LoginScreen(
  @PreviewParameter(LoginStateViewModelNotLoggedInProvider::class) loginState:
    LoginState.NotLoggedIn,
  onLogin: ((EID<*>) -> Unit)? = null,
) {
  Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
    Box(
      modifier =
        Modifier
          .padding(innerPadding)
          .fillMaxSize(),
    ) {
      Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
      ) {
        Column(
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.width(300.dp),
        ) {
          Spacer(modifier = Modifier.height(300.dp))
          val buttonModifier = Modifier.fillMaxWidth()

          Text(text = "Login", fontSize = 30.sp)
          Text(text = "Using ${BuildConfig.TAB_TYPE}")
          Spacer(modifier = Modifier.height(50.dp))

          val errorMessage = loginState.errorMessage
          if (errorMessage != null) {
            Text(text = errorMessage)
            Spacer(modifier = Modifier.height(50.dp))
          }

          @Serializable
          data class MockData(
            val name: String,
          )
          Button(onClick = {
            onLogin?.invoke(Mock().withMockData(MockData(name = "foobar")))
          }, modifier = buttonModifier) {
            Text(text = "Login with Mock")
          }

          Button(onClick = {
            onLogin?.invoke(
              DanishMitID
                .substantial()
                .withMessage("hello!"),
            )
          }, modifier = buttonModifier) {
            Text(text = "Login with MitID")
          }

          Button(onClick = {
            onLogin?.invoke(SwedishBankID.sameDevice().withMessage("hello!"))
          }, modifier = buttonModifier) {
            Text(text = "Login with SE BankID")
          }

          Button(onClick = {
            onLogin?.invoke(NorwegianBankID.substantial())
          }, modifier = buttonModifier) {
            Text(text = "Login with NO BankID")
          }
        }
      }
    }
  }
}
