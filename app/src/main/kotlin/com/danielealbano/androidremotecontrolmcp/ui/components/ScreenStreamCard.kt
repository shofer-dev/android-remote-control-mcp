@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R

/**
 * The holder's switch for remote screen viewing.
 *
 * It exists because Android will not let this be automatic: a screen capture needs the holder's own
 * consent through a system dialog, and the dialog can only be raised while this app is on screen.
 * So the platform cannot arm a phone from the outside, and this card is where a person does it.
 *
 * The copy says what the two states MEAN to whoever is holding the phone, rather than naming the
 * mechanism: on, an operator sees live video; off, they still see the screen, one still image at a
 * time. Turning it off is not a way to stop being driven, and the wording must not imply it is.
 */
@Composable
fun ScreenStreamCard(
    armed: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.screen_stream_notification_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(if (armed) R.string.screen_stream_armed else R.string.screen_stream_idle),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (armed) {
                OutlinedButton(onClick = onDisarm, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.screen_stream_disable))
                }
            } else {
                Button(onClick = onArm, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.screen_stream_enable))
                }
            }
        }
    }
}
