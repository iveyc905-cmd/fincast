package com.fincast.tv.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fincast.tv.data.db.ChannelWithNow
import com.fincast.tv.ui.player.PlayerUiState
import com.fincast.tv.ui.player.PlayerViewModel

@Composable
fun SearchScreen(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    contentPadding: PaddingValues,
    onPlay: (ChannelWithNow) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            placeholder = { Text("Search channels") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Filled.Close, "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .focusRequester(focus),
        )

        when {
            state.searchQuery.isBlank() -> EmptyState(
                title = "Find a channel",
                detail = "Type part of a channel name.",
            )
            state.searchResults.isEmpty() -> EmptyState(title = "No channels match")
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                items(state.searchResults, key = { it.channel.id }) { row ->
                    ChannelRow(
                        row = row,
                        playing = row.channel.id == state.currentChannel?.id,
                        showNumber = false,
                        onClick = { keyboard?.hide(); onPlay(row) },
                        onFavorite = { viewModel.toggleFavorite(row.channel.id) },
                    )
                }
            }
        }
    }
}
