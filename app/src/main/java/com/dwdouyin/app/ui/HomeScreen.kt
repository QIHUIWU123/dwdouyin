// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dwdouyin.app.data.DouyinParser
import com.dwdouyin.app.data.DownloadManager
import com.dwdouyin.app.data.DownloadState
import com.dwdouyin.app.data.WebViewParser
import com.dwdouyin.app.data.WorkInfo
import kotlinx.coroutines.launch

/** 读剪贴板。 */
private fun readClipboard(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = manager?.primaryClip
    if (clip != null && clip.itemCount > 0) {
        clip.getItemAt(0).coerceToText(context).toString()
    } else null
}.getOrNull()

/**
 * 首页（对齐参考图二 HiviTool 浅色风）：
 *   顶部标题栏 → 浅蓝 Hero 卡片 → 输入卡片（粘贴/解析）→ 批量开关 → 功能特点。
 * 解析成功后跳转结果页选画质。
 */
@Composable
fun HomeScreen(
    parser: DouyinParser,
    onParsed: (WorkInfo) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var stageText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    var batchMode by remember { mutableStateOf(false) }
    var showBatchWarning by remember { mutableStateOf(false) }
    var bestQuality by remember { mutableStateOf(true) }
    val batchWorks = remember { mutableStateListOf<WorkInfo>() }
    val batchChecked = remember { mutableStateListOf<Boolean>() }

    // ---- 首次解析「抖音检测引擎」浮层（2.0.0 新增）----
    var showEngineCheck by remember { mutableStateOf(false) }
    var engineCheckSec by remember { mutableStateOf(0) }

    // 2.0.3：解析失败分类（network/session/risk/timeout），用于引导弹窗
    var failureKind by remember { mutableStateOf<String?>(null) }

    // ---- 2.0.6 可见登录浮层（登录态失效时弹出，登录成功自动重试）----
    var showLoginOverlay by remember { mutableStateOf(false) }
    var pendingParseInput by remember { mutableStateOf("") }

    // 引擎就绪回调 → 自动关闭浮层（WebViewParser 首次 ensureReady 完成时触发）
    DisposableEffect(Unit) {
        WebViewParser.onEngineReady = {
            showEngineCheck = false
        }
        onDispose { WebViewParser.onEngineReady = null }
    }

    // 检测计时器（每秒 +1，用于进度展示；浮层关了自然没人看）
    LaunchedEffect(showEngineCheck) {
        engineCheckSec = 0
        while (showEngineCheck) {
            kotlinx.coroutines.delay(1000)
            engineCheckSec++
            // 兜底：90 秒强制关（三通路总预算；真实回调没来也不让用户困住）
            if (engineCheckSec >= 90) showEngineCheck = false
        }
    }

    val downloadState by DownloadManager.state.collectAsState()
    val running = downloadState is DownloadState.Running

    // 回到前台自动读取剪贴板（仅输入框为空；3.2.0 任意平台链接都自动填）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && input.isBlank()) {
                val clip = readClipboard(context)
                if (!clip.isNullOrBlank() && DouyinParser.extractAnyLink(clip) != null) {
                    input = clip
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(PageBg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- 顶部标题栏 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PageBg)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "抖音解析下载",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "关于",
                    tint = TextGray,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onOpenSettings?.invoke() },
                )
            }

            Spacer(Modifier.height(4.dp))

            // ---- Hero 卡片（浅蓝渐变 + 标题） ----
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(heroGradient)
                    .padding(vertical = 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            tint = BluePrimary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "作品链接工具",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "粘贴链接，智能预览作品详情",
                        fontSize = 14.sp,
                        color = TextGray,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- 输入卡片 ----
            GlassCard(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                cornerRadius = 18,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 输入框（带链接图标 + 清除按钮）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BlueSoftBg)
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            tint = if (input.isNotBlank()) BluePrimary else TextLightGray,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (input.isBlank()) {
                                Text(
                                    if (batchMode) "粘贴主页链接或任一作品分享文本…"
                                    else "粘贴分享链接，支持抖音/B站/快手/小红书等 33+ 平台",
                                    fontSize = 14.sp,
                                    color = TextLightGray,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 14.sp,
                                    color = TextDark,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (input.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "清除",
                                tint = TextLightGray,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { input = "" },
                            )
                        }
                    }

                    // 按钮行：粘贴（描边） + 解析（蓝色实心）
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        GlassOutlineButton(
                            text = "粘贴",
                            onClick = {
                                val clip = readClipboard(context)
                                if (clip.isNullOrBlank()) error = "剪贴板为空"
                                else { input = clip; error = null }
                            },
                            modifier = Modifier.width(110.dp),
                        )
                        GradientButton(
                            text = if (parsing) "解析中…" else "解析",
                            onClick = {
                                scope.launch {
                                    parsing = true
                                    error = null
                                    // 批量模式仍走 WebView 引擎 → 弹检测浮层；单条走接口不弹
                                    if (batchMode && !WebViewParser.engineCheckDone) {
                                        showEngineCheck = true
                                    }
                                    try {
                                        if (batchMode) {
                                            val works = parser.parseAuthorPosts(input) { stage -> stageText = stage }
                                            batchWorks.clear()
                                            batchWorks.addAll(works)
                                            batchChecked.clear()
                                            repeat(works.size) { batchChecked.add(true) }
                                        } else {
                                            // 3.0.0：单条只走两个第三方接口（juhe→douyin）
                                            onParsed(parser.parse(input) { stage -> stageText = stage })
                                        }
                                    } catch (e: Exception) {
                                        error = e.message ?: "解析失败"
                                        // 2.0.3：分类失败 → 记录错误类型，供引导弹窗用
                                        failureKind = when (e) {
                                            is com.dwdouyin.app.data.ParseException.NetworkOffline -> "network"
                                            is com.dwdouyin.app.data.ParseException.NetworkSlow -> "slow"
                                            is com.dwdouyin.app.data.ParseException.SessionExpired -> "session"
                                            is com.dwdouyin.app.data.ParseException.RiskLimited -> "risk"
                                            is com.dwdouyin.app.data.ParseException.NetworkTimeout -> "timeout"
                                            else -> null
                                        }
                                        // 2.0.6：登录态失效 → 弹【可见】内置 WebView 登录页，
                                        // 登录成功后自动重试本次解析（不再只弹文字提示）
                                        if (e is com.dwdouyin.app.data.ParseException.SessionExpired) {
                                            pendingParseInput = input
                                            showLoginOverlay = true
                                        }
                                    } finally {
                                        parsing = false
                                        stageText = ""
                                        showEngineCheck = false
                                    }
                                }
                            },
                            enabled = !parsing && input.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // 批量开关
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { batchMode = !batchMode; if (batchMode) showBatchWarning = true }
                            .padding(vertical = 2.dp),
                    ) {
                        Switch(
                            checked = batchMode,
                            onCheckedChange = { batchMode = it; if (it) showBatchWarning = true },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = BluePrimary,
                                checkedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD9DEE6),
                                uncheckedThumbColor = Color.White,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("主页批量模式", fontSize = 13.sp, color = TextDark)
                        Spacer(Modifier.width(8.dp))
                        Text("仅限学习·≤20条", fontSize = 11.sp, color = WarnAmber)
                    }
                }
            }

            // ---- 解析中 ----
            AnimatedVisibility(visible = parsing, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                GlassCard(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                    cornerRadius = 14,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = BluePrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stageText.ifBlank { "解析中…" },
                            fontSize = 13.sp,
                            color = TextGray,
                        )
                    }
                }
            }

            // ---- 错误 ----
            AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
                GlassCard(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                    cornerRadius = 14,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (error != null) DangerBg else CardWhite)
                            .border(
                                1.dp,
                                if (error != null) Danger.copy(alpha = 0.25f) else CardBorder,
                                RoundedCornerShape(10.dp),
                            )
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("⚠", color = Danger, fontSize = 15.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(error ?: "", Modifier.weight(1f), color = Danger, fontSize = 13.sp)
                    }
                }
            }

            // ---- 批量结果 ----
            AnimatedVisibility(visible = batchMode && batchWorks.isNotEmpty()) {
                GlassCard(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                    cornerRadius = 16,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "共 ${batchWorks.size} 条作品",
                                fontWeight = FontWeight.Bold,
                                color = TextDark,
                                modifier = Modifier.weight(1f),
                                fontSize = 15.sp,
                            )
                            Text(
                                "全选",
                                color = BluePrimary,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable { for (i in batchWorks.indices) batchChecked[i] = true }
                                    .padding(6.dp),
                            )
                            Text(
                                "清空",
                                color = TextGray,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable { for (i in batchWorks.indices) batchChecked[i] = false }
                                    .padding(6.dp),
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerGray))
                        batchWorks.forEachIndexed { index, item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { batchChecked[index] = !batchChecked[index] }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            if (batchChecked.getOrElse(index) { false }) BluePrimary
                                            else Color(0xFFF0F2F5),
                                        )
                                        .border(1.dp, CardBorder, RoundedCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (batchChecked.getOrElse(index) { false }) {
                                        Text("✓", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "${item.author}｜${item.title}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp,
                                    color = TextDark,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerGray))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = bestQuality,
                                onCheckedChange = { bestQuality = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = BluePrimary,
                                    checkedThumbColor = Color.White,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("批量自动选最高画质", fontSize = 12.sp, color = TextGray)
                        }
                        GradientButton(
                            text = "批量下载选中作品",
                            onClick = {
                                val selected = batchWorks.filterIndexed { i, _ ->
                                    batchChecked.getOrElse(i) { false }
                                }
                                if (selected.isNotEmpty()) {
                                    DownloadManager.startBatch(context, selected, bestQuality)
                                }
                            },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ---- 功能特点 ----
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "功能特点",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                )
            }
            Spacer(Modifier.height(12.dp))
            FeatureItem("⚡", "快速处理", "智能识别链接，快速预览作品信息")
            Spacer(Modifier.height(10.dp))
            FeatureItem("🎬", "多种格式", "支持视频、图文作品解析下载")
            Spacer(Modifier.height(10.dp))
            FeatureItem("📁", "相册保存", "自动保存到系统相册，命名日期+文案")
            Spacer(Modifier.height(30.dp))
        }
    }

    // ---- 风控提示 ----
    if (showBatchWarning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBatchWarning = false },
            confirmButton = {
                Text(
                    "我已知晓",
                    color = BluePrimary,
                    modifier = Modifier
                        .clickable { showBatchWarning = false }
                        .padding(8.dp),
                )
            },
            containerColor = Color.White,
            titleContentColor = TextDark,
            textContentColor = TextGray,
            title = { Text("风控提示") },
            text = {
                Text(
                    "主页批量解析仅限个人技术学习：\n\n" +
                        "· 单次最多 20 条，已强制 2.5 秒间隔\n" +
                        "· 高频请求可能触发风控或影响账号\n" +
                        "· 请勿商用或传播，版权归属原作者",
                    fontSize = 13.sp,
                )
            },
        )
    }

    // ---- 首次解析「抖音检测引擎」浮层（2.0.0）----
    EngineCheckOverlay(
        visible = showEngineCheck,
        elapsedSec = engineCheckSec,
        onDismiss = { showEngineCheck = false },
    )

    // ---- 2.0.3 分类失败引导弹窗（网络/Cookie/风控各有明确自助操作）----
    if (failureKind != null) {
        val (title, body, actionLabel) = when (failureKind) {
            "network" -> Triple(
                "网络未连接",
                "检测到当前网络不通或信号极弱。\n\n请先连接 Wi-Fi 或移动数据后再试。",
                "知道了",
            )
            "slow" -> Triple(
                "网络速度过慢",
                "当前网速低于 50KB/s，解析大概率会失败。\n\n" +
                    "请检查网络：\n" +
                    "· 切换 Wi-Fi / 移动数据\n" +
                    "· 或稍等网络恢复后再试",
                "知道了",
            )
            "session" -> Triple(
                "登录状态已失效",
                "引擎身份 Cookie 缺失导致解析失败。\n\n" +
                    "自助解决：到【设置】页点「清除登录」，再回到首页重新解析一次，\n" +
                    "检测界面会引导你完成抖音页面访问。",
                "去设置",
            )
            "risk" -> Triple(
                "被平台限制",
                "网络与引擎均正常，但平台未返回作品数据。\n\n" +
                    "这是平台侧的限制（与你的操作无关），建议：\n" +
                    "· 稍等几分钟再试\n" +
                    "· 或切换网络（Wi-Fi ↔ 流量）后重试",
                "知道了",
            )
            else -> Triple(
                "解析超时",
                "三条通路（快速通道/分享页/浏览器）均超时。\n\n" +
                    "可先点「检测引擎」确认浏览器通路可用，再重试解析。",
                "知道了",
            )
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { failureKind = null },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (failureKind == "session" || failureKind == "timeout") {
                        Text(
                            "检测引擎",
                            color = BluePrimary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable {
                                    // 引导到设置页：检测引擎/清除登录都在那里
                                    failureKind = null
                                    onOpenSettings?.invoke()
                                }
                                .padding(6.dp),
                        )
                    }
                    Text(
                        actionLabel,
                        color = TextDark,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { failureKind = null }
                            .padding(6.dp),
                    )
                }
            },
            containerColor = Color.White,
            titleContentColor = TextDark,
            textContentColor = TextGray,
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = { Text(body, fontSize = 13.sp, lineHeight = 19.sp) },
        )
    }

    // ---- 2.0.6 可见登录浮层：登录成功自动重试刚才的解析 ----
    LoginOverlay(
        visible = showLoginOverlay,
        onLoginSuccess = {
            showLoginOverlay = false
            val text = pendingParseInput
            if (text.isNotBlank()) {
                scope.launch {
                    parsing = true
                    stageText = "登录成功，自动重试解析…"
                    try {
                        onParsed(parser.parse(text) { stage -> stageText = stage })
                        error = null
                    } catch (e: Exception) {
                        error = e.message ?: "解析失败"
                    } finally {
                        parsing = false
                        stageText = ""
                    }
                }
            }
        },
        onClose = { showLoginOverlay = false },
    )
}
