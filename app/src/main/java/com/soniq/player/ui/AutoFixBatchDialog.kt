package com.soniq.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.soniq.player.ui.theme.*

@Composable
fun AutoFixBatchDialog(
    isRunning: Boolean,
    current: Int,
    total: Int,
    currentTrackTitle: String,
    summaryMessage: String?,
    onDismissSummary: () -> Unit
) {
    if (isRunning) {
        Dialog(onDismissRequest = {}) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = NeoDialog),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = "Auto-Fixing",
                        tint = NeoAccent,
                        modifier = Modifier.size(46.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Auto-Fixing Unknown Songs",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = NeoInk
                    )

                    Spacer(Modifier.height(14.dp))

                    val progressFraction = if (total > 0) current.toFloat() / total else 0f
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = NeoAccent,
                        trackColor = NeoInset
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "$current / $total",
                        color = NeoMuted,
                        fontSize = 13.sp
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = currentTrackTitle,
                        color = NeoInk,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "Matching clean titles & high-res covers...",
                        color = NeoAccent,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = summaryMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissSummary,
            containerColor = NeoDialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Finished",
                        tint = NeoAccent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Auto-Fix Complete", color = NeoInk)
                }
            },
            text = {
                Text(
                    text = summaryMessage ?: "",
                    color = NeoMuted
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissSummary) {
                    Text("OK", color = NeoAccent)
                }
            }
        )
    }
}
