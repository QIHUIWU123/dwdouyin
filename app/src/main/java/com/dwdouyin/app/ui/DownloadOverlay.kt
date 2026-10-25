// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dwdouyin.app.data.DownloadManager
import com.dwdouyin.app.data.DownloadState

/** 玻璃质感下载进度浮窗：底部悬浮，动画入场。 */
@Composable
fun BoxScope.DownloadOverlay(state: DownloadState) {
    val visible = state !is DownloadState.Idle
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(16.dp),
    ) {
        GlassCard(cornerRadius = 18) {
            Column(Modifier.padding(14.dp)) {
                when (state) {
                    is DownloadState.Running -> {
                        val progress = if (state.total > 0) {
                            (state.received.toFloat() / state.total).coerceIn(0f, 1f)
                        } else 0f
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.filename,
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                color = TextPrimary,
                            )
                            TextButton(onClick = { DownloadManager.cancel() }) {
                                Text("取消", color = Danger, fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.padding(top = 8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = BluePrimary,
                            trackColor = Color(0xFFE6EAF0),
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(Modifier.padding(top = 6.dp))
                        Text(
                            "${state.status} · ${formatPercent(state.received, state.total)} · ${formatSpeed(state.speedBps)}",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                    }

                    is DownloadState.Done -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✓", color = Success, fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "下载完成：${state.filename}",
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                                color = TextPrimary,
                            )
                            TextButton(onClick = { DownloadManager.dismiss() }) {
                                Text("知道了", color = Teal, fontSize = 13.sp)
                            }
                        }
                        Text(state.path, fontSize = 11.sp, color = TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }

                    is DownloadState.Failed -> {
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("⚠", color = Danger, fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "下载失败：${state.message}",
                                Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = Color(0xFFC62828),
                            )
                            TextButton(onClick = { DownloadManager.dismiss() }) {
                                Text("知道了", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }

                    is DownloadState.Idle -> Unit
                }
            }
        }
    }
}

private fun formatPercent(received: Long, total: Long): String {
    if (total <= 0) return "大小未知"
    val percent = (received * 100 / total).coerceIn(0, 100)
    return "$percent%"
}

private fun formatSpeed(speedBps: Long): String = when {
    speedBps >= 1024 * 1024 -> String.format("%.1f MB/s", speedBps / 1048576.0)
    speedBps >= 1024 -> String.format("%.0f KB/s", speedBps / 1024.0)
    speedBps > 0 -> "$speedBps B/s"
    else -> "等待中"
}
