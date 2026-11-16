package com.chordflow.prompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chordflow.prompter.ui.theme.Phosphor

/**
 * "Add to Playlist" chooser, shared by the song list and the teleprompter.
 * Tap a playlist to add the song; tap one it is already in to take it out again.
 * A new playlist can be typed in and created on the spot.
 */
@Composable
fun PlaylistPickerDialog(
    songTitle: String,
    playlists: List<String>,
    memberOf: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Phosphor.Black,
        titleContentColor = Phosphor.Green,
        textContentColor = Phosphor.Green,
        title = { Text("ADD TO PLAYLIST", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = songTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Phosphor.GreenDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (playlists.isEmpty()) {
                    StatusLine("No playlists yet. Name one below to create it.")
                }
                playlists.forEach { name ->
                    val isIn = name in memberOf
                    GreenButton(
                        text = if (isIn) "[x] $name  (tap to remove)" else "[ ] $name",
                        filled = isIn,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (isIn) onRemove(name) else onAdd(name) }
                    )
                }
                Spacer(Modifier.height(4.dp))
                GreenTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = "New playlist name",
                    modifier = Modifier.fillMaxWidth()
                )
                GreenButton(
                    text = "Create & Add",
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onAdd(newName.trim())
                        newName = ""
                    }
                )
            }
        },
        confirmButton = { GreenButton(text = "Done", filled = true, onClick = onDismiss) }
    )
}
