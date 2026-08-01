package com.bitchat.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.nostr.NostrClient
import com.bitchat.android.ui.theme.ResQMeshFontFamily

/**
 * ResQMesh Home Screen - The primary emergency dashboard.
 */
@Composable
fun HomeScreen(
    viewModel: ChatViewModel,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMeshConnected by viewModel.isConnected.collectAsState()
    val context = LocalContext.current
    val nostrClient = remember { NostrClient.getInstance(context) }
    val isInternetOnline by nostrClient.relayConnectionStatus.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val nickname by viewModel.nickname.collectAsState()

    // Pulse animation for the SOS button
    val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
    val sosScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- TOP SECTION ---
        HeaderSection()

        Spacer(modifier = Modifier.height(24.dp))

        // --- STATUS INDICATORS ---
        StatusRow(isMeshConnected, isInternetOnline)

        Spacer(modifier = Modifier.weight(1f))

        // --- CENTER SECTION: SOS BUTTON ---
        SOSButton(
            scale = sosScale,
            onClick = {
                viewModel.sendSOS()
            }
        )

        Spacer(modifier = Modifier.weight(1f))

        // --- BOTTOM SECTION: ALERTS & NAVIGATION ---
        AlertsSection(
            messages = messages,
            nickname = nickname,
            viewModel = viewModel,
            onOpenChat = onOpenChat
        )
    }
}

@Composable
private fun HeaderSection() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(80.dp)
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = ResQMeshFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.splash_tagline),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = ResQMeshFontFamily,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        )
    }
}

@Composable
private fun StatusRow(isMeshConnected: Boolean, isInternetOnline: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusCard(
            modifier = Modifier.weight(1.0f),
            title = stringResource(R.string.home_mesh_status),
            status = if (isMeshConnected) stringResource(R.string.home_status_connected) else stringResource(R.string.home_status_disconnected),
            icon = Icons.Default.Bluetooth,
            active = isMeshConnected,
            activeColor = Color(0xFF4CAF50)
        )
        StatusCard(
            modifier = Modifier.weight(1.0f),
            title = stringResource(R.string.home_internet_status),
            status = if (isInternetOnline) stringResource(R.string.home_status_online) else stringResource(R.string.home_status_offline),
            icon = if (isInternetOnline) Icons.Default.Cloud else Icons.Default.CloudOff,
            active = isInternetOnline,
            activeColor = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun StatusCard(
    title: String,
    status: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (active) activeColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SOSButton(scale: Float, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .size(200.dp)
            .scale(scale),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 8.dp,
            pressedElevation = 2.dp
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.home_send_sos),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = ResQMeshFontFamily
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AlertsSection(
    messages: List<BitchatMessage>,
    nickname: String,
    viewModel: ChatViewModel,
    onOpenChat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_recent_alerts),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = ResQMeshFontFamily,
                    fontWeight = FontWeight.Bold
                )
            )
            TextButton(onClick = onOpenChat) {
                Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = stringResource(R.string.home_open_chat))
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            if (messages.isEmpty()) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_conversations_yet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                MessagesList(
                    messages = messages.takeLast(10),
                    currentUserNickname = nickname,
                    meshService = viewModel.meshServiceFacade,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}
