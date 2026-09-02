package com.xs.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xs.chat.theme.XSChatTheme
import com.xs.chat.ui.ChatScreen
import com.xs.chat.ui.ChatUiState
import com.xs.chat.ui.ChatViewModel
import com.xs.chat.ui.LocalLanguage
import com.xs.chat.ui.SettingsScreen

import com.xs.chat.ui.PluginScreen
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val vm: ChatViewModel = viewModel()
            val state by vm.ui.collectAsState()
            val darkTheme = when (state.darkMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            val appLang = when (state.language) {
                "en" -> "en"
                "system" -> if (Locale.getDefault().language == "en") "en" else "zh"
                else -> "zh"
            }
            CompositionLocalProvider(LocalLanguage provides appLang) {
                XSChatTheme(darkTheme = darkTheme) {
                    AppRoot(state = state, vm = vm)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(state: ChatUiState, vm: ChatViewModel) {
    var screen by rememberSaveable { mutableStateOf("chat") }
    when (screen) {
        "plugins" -> {
            BackHandler { screen = "settings" }
            PluginScreen(state = state, vm = vm, onBack = { screen = "settings" })
        }
        "settings" -> {
            BackHandler { screen = "chat" }
            SettingsScreen(
                state = state,
                vm = vm,
                onBack = { screen = "chat" },
                onOpenPlugins = { screen = "plugins" }
            )
        }
        else -> ChatScreen(state = state, vm = vm, onOpenSettings = { screen = "settings" })
    }
}



