package lytso.code

import androidx.compose.runtime.Composable
import lytso.code.ui.chat.ChatScreen
import lytso.code.ui.chat.ChatViewModel

@Composable
fun MainNavigation(viewModel: ChatViewModel? = null) {
    if (viewModel != null) {
        ChatScreen(viewModel = viewModel)
    } else {
        ChatScreen()
    }
}
