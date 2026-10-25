// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.dwdouyin.app.data.HistoryDb
import com.dwdouyin.app.data.HistoryEntity
import kotlinx.coroutines.launch

/** 历史卡片：封面 + 信息 + 操作，整体可点下载。 */
@Composable
fun HistoryScreen(visible: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<HistoryEntity>>(emptyList()) }
    val downloadState by DownloadManager.state.collectAsState()
    val running = downloadState is DownloadState.Running

    LaunchedEffect(visible) {
        if (visible) records = HistoryDb.all(context)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(bgGradient),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部标题栏（浅色风，与首页统一）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PageBg)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "历史记录",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(18.dp),
            ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已解析 ${records.size} 条",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    scope.launch { records = HistoryDb.all(context) }
                }) { Text("刷新", color = Teal) }
                TextButton(onClick = {
                    scope.launch {
                        HistoryDb.clear(context)
                        records = emptyList()
                    }
                }) { Text("清空", color = TextSecondary) }
            }

            if (records.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🗂", fontSize = 36.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("暂无历史记录", color = TextDim, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "解析过的作品会出现在这里",
                            color = TextLightGray,
                            fontSize = 12.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    items(records, key = { it.id }) { record ->
                        GlassHistoryCard(
                            record = record,
                            running = running,
                            onDownload = {
                                if (!running) DownloadManager.start(context, record.toWorkInfo())
                            },
                            onDelete = {
                                scope.launch {
                                    HistoryDb.delete(context, record.id)
                                    records = HistoryDb.all(context)
                                }
                            },
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun GlassHistoryCard(
    record: HistoryEntity,
    running: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(cornerRadius = 16) {
        Row(
            Modifier
                .padding(12.dp)
                .clickable(enabled = !running) { onDownload() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(record.coverUrl)
                    .addHeader("Referer", "https://www.douyin.com/")
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp, 116.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ThumbBg),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("作者：${record.author}", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Badge(if (record.workType == "video") "视频" else "图文")
            }
            Column {
                IconButton(
                    onClick = onDownload,
                    enabled = !running,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = BluePrimary),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = "下载")
                }
                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = Danger),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
        }
    }
}
