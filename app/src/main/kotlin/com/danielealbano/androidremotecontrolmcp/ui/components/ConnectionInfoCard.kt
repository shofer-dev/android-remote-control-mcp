@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "UnusedPrivateMember")

package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme

/**
 * Builds the Copy-all / Share connection string for the device's local MCP address.
 */
internal fun buildConnectionString(serverUrl: String): String = "URL: $serverUrl"

/**
 * Card showing the device's local MCP server address (binding address, port and derived URL) with
 * copy/share affordances. The public tunnel URL and bearer-token surfaces were removed together with
 * the public-network layer; the platform connector dials outbound and exposes no inbound URL here.
 */
@Composable
fun ConnectionInfoCard(
    bindingAddress: BindingAddress,
    ipAddress: String,
    port: Int,
    onCopyAll: (String) -> Unit,
    onShare: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val displayAddress =
        when (bindingAddress) {
            BindingAddress.LOCALHOST -> "127.0.0.1"
            BindingAddress.NETWORK -> ipAddress
        }
    val serverUrl = "http://$displayAddress:$port/mcp"

    val labelStyle = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val ipLabel = stringResource(R.string.connection_info_ip)
    val portLabel = stringResource(R.string.connection_info_port)
    val urlLabel = stringResource(R.string.connection_info_url)

    val visibleLabels = listOf(ipLabel, portLabel, urlLabel)
    val labelColumnWidth: Dp =
        remember(visibleLabels, labelStyle, density) {
            with(density) {
                visibleLabels.maxOf { measurer.measure(it, labelStyle).size.width }.toDp()
            }
        }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.connection_info_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(modifier = Modifier.height(12.dp))

            ConnectionInfoRow(label = ipLabel, labelWidth = labelColumnWidth) {
                Text(text = displayAddress, style = MaterialTheme.typography.bodyMedium)
            }
            ConnectionInfoRow(label = portLabel, labelWidth = labelColumnWidth) {
                Text(text = port.toString(), style = MaterialTheme.typography.bodyMedium)
            }
            ConnectionInfoRow(label = urlLabel, labelWidth = labelColumnWidth) {
                Text(text = serverUrl, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            val connectionString = buildConnectionString(serverUrl = serverUrl)
            Row(
                modifier = Modifier.align(Alignment.End),
            ) {
                TextButton(onClick = { onCopyAll(connectionString) }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.connection_info_copy_all),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                TextButton(onClick = { onShare(connectionString) }) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(R.string.connection_info_share),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionInfoRow(
    label: String,
    labelWidth: Dp,
    modifier: Modifier = Modifier,
    value: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(labelWidth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        value()
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionInfoCardPreview() {
    AndroidRemoteControlMcpTheme {
        ConnectionInfoCard(
            bindingAddress = BindingAddress.LOCALHOST,
            ipAddress = "192.168.1.100",
            port = 8080,
            onCopyAll = {},
            onShare = {},
        )
    }
}
