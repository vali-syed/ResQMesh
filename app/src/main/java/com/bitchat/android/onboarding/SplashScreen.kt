package com.bitchat.android.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.ui.theme.ResQMeshFontFamily

/**
 * Clean splash screen for ResQMesh
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    
    // Animation states
    val infiniteTransition = rememberInfiniteTransition(label = "splash")
    
    // Subtle scale animation for the logo
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )
    
    // Simple fade-in for text
    val alphaAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alphaAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1000, easing = LinearOutSlowInEasing)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Logo
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(160.dp)
                    .scale(scale)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(alphaAnim.value)
            ) {
                // App Name
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = ResQMeshFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        letterSpacing = 2.sp
                    ),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Tagline
                Text(
                    text = stringResource(R.string.splash_tagline),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = ResQMeshFontFamily,
                        color = colorScheme.onSurface.copy(alpha = 0.6f),
                        letterSpacing = 1.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
