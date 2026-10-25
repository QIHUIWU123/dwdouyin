// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dwdouyin.app.data.DouyinParser
import com.dwdouyin.app.data.Prefs
import com.dwdouyin.app.data.QualityPrefs
import kotlinx.coroutines.launch

/** 设置：玻璃分组卡片 · 保存路径 / 画质偏好 / 引擎状态。 */
@Composable
fun SettingsScreen(parser: DouyinParser) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var treeUri by remember { mutableStateOf(Prefs.getTreeUri(context)?.toString()) }
    var qualityPref by remember { mutableStateOf(QualityPrefs.get(context)) }
    var engineStatus by remember { mutableStateOf("未检测") }
    var checking by remember { mutableStateOf(false) }
    var relogin by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            Prefs.setTreeUri(context, uri)
            treeUri = uri.toString()
        }
    }

    val displayPath = treeUri?.let { Uri.parse(it).lastPathSegment ?: it }
        ?: defaultSaveDir(context)

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
                    "设置",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
            }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 保存路径 ----
            GlassCard(cornerRadius = 18) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("下载保存路径", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Text(displayPath, fontSize = 12.sp, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton(text = "选择目录", onClick = { picker.launch(null) })
                        GlassOutlineButton(
                            text = "恢复默认",
                            onClick = {
                                Prefs.setTreeUri(context, null)
                                treeUri = null
                            },
                        )
                    }
                    Text(
                        "默认保存到系统相册（DCIM/dwdouyin）；选择目录后文件将保存到该文件夹。",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
            }

            // ---- 画质偏好 ----
            GlassCard(cornerRadius = 18) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("画质偏好", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Spacer(Modifier.padding(top = 6.dp))
                    QualityPrefs.OPTIONS.forEach { (key, label) ->
                        val isSelected = qualityPref == key
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) BlueLightBg else Color.Transparent)
                                .clickable {
                                    qualityPref = key
                                    QualityPrefs.set(context, key)
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .background(
                                        brush = if (isSelected) {
                                            primaryGradient
                                        } else {
                                            androidx.compose.ui.graphics.Brush.linearGradient(
                                                listOf(Color(0xFFD9DEE6), Color(0xFFD9DEE6)),
                                            )
                                        },
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
                                    ),
                            )
                            Spacer(Modifier.padding(start = 12.dp))
                            Text(
                                label,
                                fontSize = 13.sp,
                                color = if (isSelected) BluePrimary else TextSecondary,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                    Text(
                        "解析完成后自动预选对应清晰度；胶囊仍可手动多选。",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
            }

            // ---- 解析引擎 ----
            GlassCard(cornerRadius = 18) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("解析引擎", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Text(
                        "抖音已启用 Argus 风控，应用通过内置浏览器内核解析。\n" +
                            "首次解析稍慢（约 10-20 秒预热），之后约 3-6 秒/条。",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                    Text("状态：$engineStatus", fontSize = 12.sp, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton(
                            text = if (checking) "检测中…" else "检测引擎",
                            onClick = {
                                checking = true
                                scope.launch {
                                    engineStatus = runCatching {
                                        val cookies = parser.exportCookies()
                                        "就绪 · Cookie ${cookies.size} 条"
                                    }.getOrElse { "不可用：${it.message}" }
                                    checking = false
                                }
                            },
                            enabled = !checking,
                        )
                        // 2.0.6：重新登录（显眼主按钮）——弹出可见登录 WebView
                        GradientButton(
                            text = if (relogin) "登录中…" else "重新登录",
                            onClick = {
                                relogin = true
                                scope.launch {
                                    engineStatus = runCatching {
                                        parser.reloginForSession()
                                        "已打开登录页面，完成后 Cookie 自动保存"
                                    }.getOrElse { "登录失败：${it.message}" }
                                    relogin = false
                                }
                            },
                            enabled = !relogin,
                        )
                        GlassOutlineButton(
                            text = "清除登录",
                            onClick = {
                                parser.clearSession()
                                engineStatus = "登录状态已清除"
                            },
                        )
                    }
                }
            }

            // ---- 2.0.8 解析次数显示框（本地计数，2.0.9 起不限次）----
            GlassCard(cornerRadius = 18) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("解析次数（第三方接口统计）", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Text(
                        "统计第三方接口的今日调用次数（本地记录、每日零点重置）。\n" +
                            "不再硬性限制次数——接口用到不能解析时自动切换下一个可用接口。",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                    val used = remember { parser.getSuxunTodayCount() }
                    Text(
                        "今日接口调用：$used 次（不限次，接口耗尽自动切换）",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = BluePrimary,
                    )
                }
            }

            // ---- 2.0.9 API 接口管理（多通道：勾选启停 + 自定义增删）----
            GlassCard(cornerRadius = 18) {
                var channels by remember { mutableStateOf(parser.listApiChannels()) }
                var apiStatus by remember { mutableStateOf("") }
                var newName by remember { mutableStateOf("") }
                var newUrl by remember { mutableStateOf("") }
                var newType by remember { mutableStateOf("suxun") }

                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("API 解析接口管理", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Text(
                        "解析时按列表顺序自动尝试：一个接口失败自动切换下一个。\n" +
                            "勾选 = 启用；自定义接口的 URL 模板需含 {URL} 占位符。",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                    // 通道列表（3.2.0：只显示名称与启停，不显示接口 URL）
                    channels.forEach { ch ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(
                                    checked = ch.enabled,
                                    onCheckedChange = { checked ->
                                        parser.setApiChannelEnabled(ch.id, checked)
                                        channels = parser.listApiChannels()
                                    },
                                )
                                Column {
                                    Text(ch.name, fontSize = 13.sp, color = TextDark)
                                    Text(
                                        if (ch.builtin) "内置通道" else "自定义通道",
                                        fontSize = 10.sp,
                                        color = TextDim,
                                    )
                                }
                            }
                            if (!ch.builtin) {
                                Text(
                                    "删除",
                                    fontSize = 12.sp,
                                    color = Color(0xFFC66199),
                                    modifier = Modifier
                                        .clickable {
                                            parser.removeApiChannel(ch.id)
                                            channels = parser.listApiChannels()
                                        }
                                        .padding(6.dp),
                                )
                            } else {
                                Text("内置", fontSize = 10.sp, color = TextDim)
                            }
                        }
                    }
                    // 新增表单
                    androidx.compose.material3.OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("接口名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("URL 模板（含 {URL}）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.FilterChip(
                            selected = newType == "suxun",
                            onClick = { newType = "suxun" },
                            label = { Text("标准格式") },
                        )
                        androidx.compose.material3.FilterChip(
                            selected = newType == "bugpk",
                            onClick = { newType = "bugpk" },
                            label = { Text("完整画质格式") },
                        )
                        Spacer(Modifier.weight(1f))
                        GradientButton(
                            text = "添加接口",
                            onClick = {
                                val err = parser.addApiChannel(newName, newUrl, newType)
                                if (err == null) {
                                    newName = ""
                                    newUrl = ""
                                    apiStatus = "✅ 已添加"
                                } else {
                                    apiStatus = err
                                }
                                channels = parser.listApiChannels()
                            },
                        )
                    }
                    if (apiStatus.isNotEmpty()) {
                        Text(apiStatus, fontSize = 11.sp, color = BluePrimary)
                    }
                }
            }

            // ---- 关于 ----
            GlassCard(cornerRadius = 18) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("关于", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    Text("dwdouyin 抖音作品解析下载工具 v3.2.0", fontSize = 12.sp, color = TextSecondary)
                    Text(
                        "仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规。",
                        fontSize = 11.sp,
                        color = TextDim,
                    )
                }
            }
        }
        }
    }
}

fun defaultSaveDir(context: Context): String {
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    return dir.absolutePath
}
