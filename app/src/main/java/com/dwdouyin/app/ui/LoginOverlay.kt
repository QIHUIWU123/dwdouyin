// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup

/**
 * 2.0.6 可见登录浮层：解析失败（登录态失效）时弹出内置 WebView 打开抖音，
 * 用户在页面里登录成功（轮询到 sessionid Cookie）→ onLoginSuccess 回调
 * → 调用方自动重试解析。
 *
 * - WebView 真实可见（用户可操作登录表单）；
 * - Cookie 由系统 CookieManager 持久化，登录后所有解析通路共享；
 * - 「完成」按钮兜底（Cookie 回调慢的机型可手动确认）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginOverlay(
    visible: Boolean,
    onLoginSuccess: () -> Unit,
    onClose: () -> Unit,
) {
    var statusText by remember { mutableStateOf("在下方页面登录抖音，成功后自动继续") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var pollRunnable by remember { mutableStateOf<Runnable?>(null) }

    fun stopPoll() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    DisposableEffect(visible) {
        if (!visible) stopPoll()
        onDispose { stopPoll() }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xF20A081E)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 顶部栏：说明 + 完成按钮
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "抖音登录",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDCDBE9),
                        )
                        Text(statusText, fontSize = 11.sp, color = Color(0xFF8B8A9E))
                    }
                    Text(
                        "完成",
                        fontSize = 14.sp,
                        color = BluePrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                stopPoll()
                                val cookies = CookieManager.getInstance()
                                    .getCookie("https://www.douyin.com/") ?: ""
                                if (cookies.contains("sessionid")) onLoginSuccess() else onClose()
                            }
                            .padding(10.dp),
                    )
                }

                // 可见 WebView 登录页（圆角卡片）
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString =
                                    "Mozilla/5.0 (Linux; Android 13; SM-G991B) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/125.0.0.0 Mobile Safari/537.36"
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean = false
                                }
                                loadUrl("https://www.douyin.com/")

                                // 登录态轮询：每 2s 查 sessionid Cookie
                                val poll = object : Runnable {
                                    override fun run() {
                                        val cookies = CookieManager.getInstance()
                                            .getCookie("https://www.douyin.com/") ?: ""
                                        if (cookies.contains("sessionid")) {
                                            statusText = "✅ 登录成功，正在保存…"
                                            CookieManager.getInstance().flush()
                                            stopPoll()
                                            onLoginSuccess()
                                            return
                                        }
                                        mainHandler.postDelayed(this, 2000)
                                    }
                                }
                                pollRunnable = poll
                                mainHandler.postDelayed(poll, 2000)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
