package com.nuvio.app.features.casting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.casting.model.*

@Composable
fun TranscodingProgressScreen(
    job: TranscodingJob,
    progress: TranscodingProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .align(Alignment.Center)
                .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transcoding Stream",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.Gray
                    )
                }
            }

            Divider(modifier = Modifier
                .fillMaxWidth()
                .height(1.dp), color = Color(0xFF333333))

            // Status info
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusRow("Device", "Preparing for cast...")
                StatusRow("Target Codec", job.targetCodec.name)
                StatusRow("Target Bitrate", "${job.targetBitrate / 1_000_000} Mbps")
                StatusRow(
                    "Hardware Acceleration",
                    if (job.useHardwareAcceleration) "Enabled" else "Software"
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { progress.percentComplete.toFloat() / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = Color(0xFF4CAF50),
                    trackColor = Color(0xFF333333)
                )
                Text(
                    text = "${progress.percentComplete}% Complete",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            // Progress details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A2A), shape = RoundedCornerShape(8.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Time Elapsed", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        formatDuration(progress.timeElapsed),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Column {
                    Text("Est. Remaining", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        formatDuration(progress.estimatedTimeRemaining),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Column {
                    Text("Bitrate", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        "${progress.bitrate / 1_000_000}M",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Status message
            Text(
                text = when (progress.state) {
                    TranscodingState.INITIALIZING -> "Initializing transcoding..."
                    TranscodingState.DECODING -> "Decoding video stream..."
                    TranscodingState.ENCODING -> "Encoding to target codec..."
                    TranscodingState.MUXING -> "Finalizing output file..."
                    TranscodingState.COMPLETED -> "Transcoding completed!"
                    TranscodingState.FAILED -> "Transcoding failed"
                    TranscodingState.CANCELLED -> "Transcoding cancelled"
                    TranscodingState.PENDING -> "Preparing..."
                },
                fontSize = 12.sp,
                color = when (progress.state) {
                    TranscodingState.COMPLETED -> Color(0xFF4CAF50)
                    TranscodingState.FAILED, TranscodingState.CANCELLED -> Color(0xFFf44336)
                    else -> Color.Gray
                }
            )

            // Cancel button
            if (progress.state !in listOf(
                TranscodingState.COMPLETED,
                TranscodingState.FAILED,
                TranscodingState.CANCELLED
            )) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF333333)
                    )
                ) {
                    Text("Cancel Transcoding", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Text(value, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60

    return when {
        hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)
        minutes > 0 -> String.format("%02d:%02d", minutes, seconds % 60)
        else -> String.format("%02ds", seconds)
    }
}
