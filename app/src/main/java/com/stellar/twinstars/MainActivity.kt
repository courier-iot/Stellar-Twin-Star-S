package com.stellar.twinstars

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Space = Color(0xFF070A14)
private val Cyan = Color(0xFF34E7FF)
private val TextColor = Color(0xFFE9ECFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Space, onBackground = TextColor)) {
                var taps by remember { mutableIntStateOf(0) }
                Box(
                    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A0F24), Space, Color(0xFF0C0718)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Twin Star S", color = TextColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Built with Stellar AutoHub", color = TextColor.copy(alpha = 0.7f))
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { taps++ }) { Text(if (taps == 0) "Launch" else "Launched $taps×") }
                    }
                }
            }
        }
    }
}