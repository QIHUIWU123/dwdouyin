// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dwdouyin.app.data.DownloadManager
import com.dwdouyin.app.data.DownloadState
import com.dwdouyin.app.data.MediaEntry
import com.dwdouyin.app.data.QualityPrefs
import com.dwdouyin.app.data.WorkInfo

/**
 * 结果页（对齐参考图一）：
 *   顶部返回栏 → 视频/图片分组 → 缩略图 → 清晰度列表（每项可选中 + 复制直链）
 *   → 绿色「保存视频」→ 「复制链接」。
 * 保存位置：系统相册，命名「日期_作者-文案」。
 */
@Composable
fun ResultScreen(
    work: WorkInfo?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val downloadState by DownloadManager.state.collectAsState()
    val running = downloadState is DownloadState.Running

    var selected by remember(work?.awemeId) { mutableStateOf(-1) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // 进入时按画质偏好预选一档
    LaunchedEffect(work?.awemeId) {
        val w = work ?: return@LaunchedEffect
        if (w.mediaList.isEmpty()) return@LaunchedEffect
        val pref = QualityPrefs.get(context)
        selected = when (pref) {
            "highest" -> 0
            "high" -> w.mediaList.indexOfFirst {
                Regex("1080|原画|无水印|4K", RegexOption.IGNORE_CASE).containsMatchIn(it.label)
            }.takeIf { it >= 0 } ?: 0
            "balanced" -> w.mediaList.indexOfFirst { it.label.contains("720") }.takeIf { it >= 0 } ?: 0
            else -> w.mediaList.indexOfFirst { it.label.contains("自动") }.takeIf { it >= 0 } ?: 0
        }
    }

    val isVideo = work?.workType == "video"

    Column(
        Modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- 顶部栏 ----
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextDark,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (isVideo) "视频 (${work?.mediaList?.size ?: 0})" else "图片 (${work?.mediaList?.size ?: 0})",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp))
        }

        Spacer(Modifier.height(8.dp))

        if (work == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📄", fontSize = 32.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("暂无解析结果", color = TextGray, fontSize = 14.sp)
                }
            }
            return@Column
        }

        // ---- 分组标题 ----
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (isVideo) Icons.Filled.Videocam else Icons.Filled.Image,
                contentDescription = null,
                tint = BluePrimary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isVideo) "视频 (1)" else "图片 (1)",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextDark,
            )
        }

        // ---- 缩略图 ----
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ThumbBg),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(work.coverUrl)
                    .addHeader("Referer", "https://www.douyin.com/")
                    .crossfade(true)
                    .build(),
                contentDescription = "封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 角标序号
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text("1", color = Color.White, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- 选择清晰度 ----
        Text(
            "选择清晰度保存",
            fontSize = 13.sp,
            color = TextGray,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))

        // 清晰度列表：每项 [清晰度] [复制]
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            work.mediaList.forEachIndexed { index, entry ->
                val isSel = index == selected
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 清晰度按钮（选中浅蓝底；显示 大小/宽高）
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) BlueLightBg else Color.White)
                            .border(
                                if (isSel) 1.5.dp else 1.dp,
                                if (isSel) BluePrimary else CardBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { selected = index }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        Column {
                            Text(
                                entry.label,
                                fontSize = 14.sp,
                                color = if (isSel) BluePrimary else TextDark,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.sizeBytes > 0 || entry.wh.isNotBlank()) {
                                Text(
                                    listOfNotNull(
                                        MediaEntry.formatSize(entry.sizeBytes).ifEmpty { null },
                                        entry.wh.ifBlank { null },
                                    ).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = TextGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    // 复制直链（每项右侧独立复制）
                    Box(
                        Modifier
                            .width(84.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                copyToClipboard(context, entry.url)
                                toastMsg = "已复制 ${entry.label} 直链"
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                tint = TextDark,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("复制", fontSize = 13.sp, color = TextDark)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---- 保存视频（主蓝实心，单色策略） ----
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (running) BluePrimary.copy(alpha = 0.5f) else BluePrimary)
                .clickable(enabled = !running && selected >= 0) {
                    if (selected >= 0) DownloadManager.start(context, work, selected)
                }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (running) "下载中…" else if (isVideo) "保存视频" else "保存图片",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 复制链接（描边） ----
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .clickable {
                    val url = work.shareUrl.ifEmpty { "https://www.douyin.com/video/${work.awemeId}" }
                    copyToClipboard(context, url)
                    toastMsg = "已复制作品链接"
                }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("复制链接", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BluePrimary)
        }

        Spacer(Modifier.height(12.dp))

        // ---- 全部下载 ----
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .clickable(enabled = !running) { DownloadManager.startAll(context, work) }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("保存全部清晰度", fontSize = 15.sp, color = BluePrimary)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "保存位置：系统相册 · 命名格式 日期_作者-文案",
            fontSize = 11.sp,
            color = TextGray,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    // ---- 轻提示 ----
    AnimatedVisibility(
        visible = toastMsg != null,
        enter = fadeIn() + slideInVertically { it / 2 },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .padding(bottom = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xE6333333))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(toastMsg ?: "", color = Color.White, fontSize = 13.sp)
            }
        }
        LaunchedEffect(toastMsg) {
            kotlinx.coroutines.delay(1800)
            toastMsg = null
        }
    }
}

/** 写剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("dwdouyin", text))
}
