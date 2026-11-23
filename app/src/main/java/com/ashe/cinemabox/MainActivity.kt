package com.ashe.cinemabox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ashe.cinemabox.telegram.AuthState
import com.ashe.cinemabox.ui.ErrorScreen
import com.ashe.cinemabox.ui.LoadingScreen
import com.ashe.cinemabox.ui.PasswordLogin
import com.ashe.cinemabox.ui.QrLogin
import com.ashe.cinemabox.ui.LibraryApp
import com.ashe.cinemabox.ui.theme.CineboxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CineboxApp
        val telegram = app.telegram
        setContent {
            val state by telegram.state.collectAsState()
            CineboxTheme {
                when (val current = state) {
                    is AuthState.Qr -> QrLogin(current)
                    is AuthState.Password -> PasswordLogin(current, telegram::submitPassword, telegram::cancelLogin)
                    AuthState.Ready -> LibraryApp(app.library, app.files, app.librarySettings, app.musicIdentifier, telegram::logOut)
                    is AuthState.Working -> LoadingScreen(current.message)
                    is AuthState.Failed -> ErrorScreen(current.message, telegram::retry)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as CineboxApp).telegram.start()
    }

    override fun onStop() {
        (application as CineboxApp).telegram.stop()
        super.onStop()
    }
}
