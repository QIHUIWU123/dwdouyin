// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dwdouyin.app.data.DouyinParser
import com.dwdouyin.app.data.DownloadEngine
import com.dwdouyin.app.data.DownloadManager
import com.dwdouyin.app.data.DownloadState
import com.dwdouyin.app.data.WorkInfo
import com.dwdouyin.app.ui.DownloadOverlay
import com.dwdouyin.app.ui.HistoryScreen
import com.dwdouyin.app.ui.HomeScreen
import com.dwdouyin.app.ui.ResultScreen
import com.dwdouyin.app.ui.SettingsScreen
import com.dwdouyin.app.ui.Teal
import com.dwdouyin.app.ui.TextSecondary
import com.dwdouyin.app.ui.bgGradient
import com.dwdouyin.app.ui.theme.DwdouyinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DwdouyinTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    var tab by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val downloadState by DownloadManager.state.collectAsState()

    // 解析结果（非空时展示结果页）
    var parsedWork by remember { mutableStateOf<WorkInfo?>(null) }

    // 全局解析器：持有 WebView 引擎，供首页解析与下载防盗链共用
    val parser = remember { DouyinParser(context) }
    DisposableEffect(Unit) {
        DownloadEngine.cookieProvider = { parser.exportCookies() }
        onDispose {
            DownloadEngine.cookieProvider = null
            parser.release()
        }
    }

    // 结果页：覆盖在底部导航之上的独立层（解析在首页，选画质在结果页）
    if (parsedWork != null) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            ResultScreen(work = parsedWork, onBack = { parsedWork = null })
            DownloadOverlay(downloadState)
        }
        return
    }

    Box(
        Modifier
            .background(bgGradient),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                // 浅色底部导航（对齐参考图二）
                NavigationBar(
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                ) {
                    val tabs = listOf(
                        Triple("首页", Icons.Filled.Home, 0),
                        Triple("历史", Icons.Filled.History, 1),
                        Triple("设置", Icons.Filled.Settings, 2),
                    )
                    tabs.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item.third,
                            onClick = { tab = item.third },
                            icon = { Icon(item.second, contentDescription = item.first) },
                            label = {
                                Text(
                                    item.first,
                                    fontWeight = if (tab == item.third) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Teal,
                                selectedTextColor = Teal,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = Color(0xFFEAF1F9),
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                // Tab 平滑转场
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "tab",
                ) { current ->
                    when (current) {
                        0 -> HomeScreen(
                            parser = parser,
                            onParsed = { parsedWork = it },
                            onOpenSettings = { tab = 2 },
                        )
                        1 -> HistoryScreen(visible = true)
                        else -> SettingsScreen(parser = parser)
                    }
                }
                DownloadOverlay(downloadState)
            }
        }
    }
}
