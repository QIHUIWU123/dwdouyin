// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dwdouyin.app.data.WebViewParser

/**
 * 首次解析时的「抖音检测引擎」浮层（用户要求 2.0.0 新增）：
 *   - 首次点解析时弹出（进程级一次性，engineCheckDone 之后不再弹）；
 *   - 展示检测进度（加载抖音首页 → 等待风控身份 Cookie 就绪）；
 *   - WebViewParser.ensureReady() 完成时通过 onEngineReady 回调自动关闭；
 *   - 全屏遮罩 + 中央毛玻璃卡片，60 秒兜底超时也自动关闭。
 */
@Composable
fun EngineCheckOverlay(
    visible: Boolean,
    elapsedSec: Int,
    onDismiss: () -> Unit,
) {
    // 进度条：90 秒总预算内平滑推进（不到 100%，交给真实回调关闭）
    val progress by animateFloatAsState(
        targetValue = (elapsedSec / 90f).coerceIn(0f, 0.98f),
        animationSpec = tween(600),
        label = "engineCheck",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xCC0A081E)),
            contentAlignment = Alignment.Center,
        ) {
            GlassCard(cornerRadius = 20) {
                Column(
                    Modifier
                        .padding(horizontal = 32.dp, vertical = 28.dp)
                        .width(280.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("🎵", fontSize = 34.sp)
                    Text(
                        "抖音检测引擎",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "正在解析作品（多通路自动切换）\n最长 90 秒，成功后自动继续",
                        fontSize = 12.sp,
                        color = TextGray,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(Color(0xFF353349)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.coerceIn(0.03f, 1f))
                                .height(8.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .background(BluePrimary),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp),
                            color = BluePrimary,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            if (elapsedSec > 0) "已检测 $elapsedSec 秒…" else "正在检测…",
                            fontSize = 12.sp,
                            color = TextGray,
                        )
                    }
                }
            }
        }
    }
}
