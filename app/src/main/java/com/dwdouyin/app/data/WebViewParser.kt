// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 基于 WebView 的解析引擎。
 *
 * 背景：抖音已上线 Argus 风控，OkHttp / HttpURLConnection（即使带 Cookie、模拟 UA）
 * 请求详情接口一律返回 403 "Uifid Not Found"。经实测（PC 端 Playwright + 本机 Edge），
 * 只有真实浏览器内核环境内发起的 fetch 才能通过风控。
 *
 * 关键工程细节（都是实测踩过的坑）：
 *  1. WebView 必须挂载到视图树，且必须保持「可渲染」：1x1 + alpha=0 会被系统判定
 *     不可见而冻结渲染与定时器，evaluateJavascript 回调永远不回来 —— 这是历史上
 *     「网络请求超时，请检查网络后重试」的根因。现在的做法是挂到 android.R.id.content
 *     的 index 0、MATCH_PARENT、alpha=0.01、VISIBLE，完全被上层 UI 覆盖。
 *  2. evaluateJavascript 不支持返回 Promise：async IIFE 会序列化成 "{}"。
 *     因此 JS 侧把结果写入 window.__dwdyResult__，再轮询读取。
 *  3. 身份 Cookie 判定不能查 "UIFID"：实测只有 UIFID_TEMP，没有 UIFID。
 *     真正标志身份就绪的是 __ac_signature 且（web_sign_token 或 x-web-secsdk-uid）。
 *  4. 不能关图片（loadsImagesAutomatically=false / blockNetworkImage=true 已删除）：
 *     secsdk 的指纹计算会用到图片渲染结果，关图会导致身份 Cookie 一直不生成。
 *
 * 三条解析通路：
 *  通路 1  页面内 fetch /aweme/v1/web/aweme/detail/（内含 3 轮「重新预热 + 重试」）
 *  通路 2  直接导航 https://www.douyin.com/video/{id}，从 DOM / resource timing
 *          里抠出真实播放地址，合成一条兜底 MediaEntry（画质列表只有一条）
 *  通路 3  Parser 层的引擎级重试（超时 → release + delay + 再来一次）
 *
 * 全程 Log tag = "dwdy-parser"，便于用户回传 logcat 定位。
 */
class WebViewParser(private val context: Context) {

    companion object {
        private const val TAG = "dwdy-parser"
        private const val HOMEPAGE = "https://www.douyin.com/"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        /** 单次页面导航（主页/详情页）的硬上限。 */
        private const val LOAD_TIMEOUT_SEC = 40L

        /** 首页冷启动等身份 Cookie 的上限（2.0.2：外层 parse 有 40s 通路限时，这里压到 45s）。 */
        private const val WARMUP_TIMEOUT_SEC = 45L
        private const val WARMUP_POLL_MS = 500L

        /** 首次检测回调：引擎就绪时通知 UI 关闭检测浮层。 */
        @Volatile
        var onEngineReady: (() -> Unit)? = null

        /** 首次检测是否已进行过（进程级一次性）。 */
        @Volatile
        var engineCheckDone: Boolean = false

        /** 兜底通路里页面加载完再等 DOM 播放器填充的上限。 */
        private const val DOM_TIMEOUT_SEC = 40L

        private const val EVAL_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 400L
        private const val FETCH_TIMEOUT_MS = 45_000L

        /** 页面内 fetch 最多 3 轮，每轮失败都重新预热。 */
        private const val MAX_FETCH_ROUNDS = 3

        /** 3 轮重试的总时间预算，防止极端情况下单次解析拖到十几分钟。 */
        private const val RETRY_BUDGET_MS = 150_000L

        /** 每次 fetch 最多打多少条轮询日志（避免 logcat 刷屏）。 */
        private const val POLL_LOG_LIMIT = 5

        /** 兜底通路合成的条目名。 */
        private const val FALLBACK_LABEL = "默认（页面兜底）"

        /** 兜底通路探针：桌面详情页里的视频地址来源，按可靠性排列。 */
        private val DOM_PROBE_JS = """
            (function(){
              try{
                var v=document.querySelector('video')||{};
                var res=[];
                try{
                  res=performance.getEntriesByType('resource').map(function(e){return e.name})
                    .filter(function(n){return n.indexOf('.mp4')>=0||n.indexOf('mime_type=video_mp4')>=0||n.indexOf('douyinvod')>=0})
                    .slice(0,8);
                }catch(e){}
                var html='';
                if(!res.length){
                  try{
                    var h=document.documentElement.innerHTML||'';
                    var m=h.match(/https?:\/\/[^"'\s]+(?:\.mp4|mime_type=video_mp4)[^"'\s]*/);
                    if(m){html=m[0];}
                  }catch(e){}
                }
                return JSON.stringify({
                  src:v.src||'',
                  currentSrc:v.currentSrc||'',
                  poster:v.poster||'',
                  sources:Array.prototype.slice.call(document.querySelectorAll('video source')).map(function(s){return s.src}).slice(0,5),
                  res:res,
                  html:html,
                  title:document.title||'',
                  text:(document.body?document.body.innerText:'').slice(0,300)
                });
              }catch(e){
                return JSON.stringify({src:'',currentSrc:'',poster:'',sources:[],res:[],html:'',title:document.title||'',text:'',err:String(e)});
              }
            })()
        """.trimIndent()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var hostLayout: FrameLayout? = null

    @Volatile
    private var domainReady = false

    /** 身份 Cookie 是否就绪（仅用于错误信息与日志，不阻断流程）。 */
    @Volatile
    private var identityReady = false

    /** 身份未就绪时缺哪些 Cookie。 */
    @Volatile
    private var lastMissingIdentity = ""

    /** 详情接口（与 PC 端完全一致的参数）。 */
    private fun detailUrl(awemeId: String): String = buildString {
        append("https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=")
        append(awemeId)
        append("&device_platform=webapp&aid=6383&channel=channel_pc_web")
        append("&pc_client_type=1&version_code=290300&browser_language=zh-CN")
        append("&browser_platform=Win32&browser_name=Chrome&browser_version=125.0.0.0")
        append("&cookie_enabled=true&screen_width=2560&screen_height=1440")
        append("&browser_online=true&engine_name=Blink&engine_version=125.0.0.0")
        append("&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8")
        append("&platform=PC&downlink=10&effective_type=4g&round_trip_time=50")
    }

    /** 主页作品接口。 */
    private fun postUrl(secUid: String, count: Int, cursor: Long): String = buildString {
        append("https://www.douyin.com/aweme/v1/web/aweme/post/?sec_user_id=")
        append(secUid)
        append("&count=").append(count)
        append("&max_cursor=").append(cursor)
        append("&locate_query=false&show_live_replay_strategy=1&need_time_list=1")
        append("&time_list_query=0&whale_cut_token=&cut_version=1")
        append("&publish_video_strategy_type=2&device_platform=webapp&aid=6383")
        append("&channel=channel_pc_web&cookie_enabled=true&platform=PC")
    }

    // ---------------- 生命周期 ----------------

    /** 确保 WebView 已创建（并挂载到视图树）且处于 douyin.com 域。 */
    suspend fun ensureReady() {
        if (domainReady && webView != null && hostLayout != null) return
        withContext(Dispatchers.Main) {
            val view = webView ?: createAndAttachWebView().also {
                webView = it
                Log.i(TAG, "WebView 已创建并挂载：android.R.id.content index=0, " +
                    "MATCH_PARENT, alpha=0.01, visibility=VISIBLE")
            }
            if (!domainReady) {
                loadUrlSync(view, HOMEPAGE)
                Log.i(TAG, "首页加载完成：$HOMEPAGE")
                identityReady = waitForIdentity(view, WARMUP_TIMEOUT_SEC)
                domainReady = true
                Log.i(TAG, "预热结束：domainReady=true, identityReady=$identityReady")
            }
        }
        // 首次检测成功 → 通知 UI 关闭检测浮层（进程级一次性）
        if (!engineCheckDone) {
            engineCheckDone = true
            Log.i(TAG, "首次引擎检测完成（domainReady=$domainReady identityReady=$identityReady）")
            onEngineReady?.invoke()
        }
    }

    /**
     * 创建 WebView 并挂载到 Activity 根视图。
     *
     * 修复「网络请求超时」的三个关键点：
     *  1. 必须挂载到视图树（裸 WebView 在多数机型上 JS 不执行）
     *  2. 不能用 1x1 + alpha=0 —— 不可见视图会被系统冻结渲染/定时器，
     *     evaluateJavascript 回调不触发。改用与根视图同尺寸 + 置于
     *     内容视图最底层 + 完全不拦截触摸，肉眼不可见但保持可渲染状态。
     *  3. 页面加载完成必须有硬校验，失败即报错，不带病进入轮询。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createAndAttachWebView(): WebView {
        val activity = context as? android.app.Activity
            ?: run {
                var ctx = context
                while (ctx is android.content.ContextWrapper) {
                    if (ctx is android.app.Activity) break
                    ctx = ctx.baseContext
                }
                ctx as? android.app.Activity
            }
            ?: throw ParseException.ParseFailed(
                "解析引擎初始化失败：缺少界面上下文，请回到App首页后重试"
            )

        val root = activity.window.decorView as? ViewGroup
            ?: throw ParseException.ParseFailed("无法获取窗口根视图，请重启应用后重试")

        val layout = FrameLayout(activity).apply {
            // 与内容视图同尺寸，垫在最底层；不响应任何触摸
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
            // 关键：保持可渲染。alpha=0 会被判定不可见而冻结 JS 定时器，
            // 0.01 肉眼同样看不见，但不会被冻结。
            alpha = 0.01f
            visibility = View.VISIBLE
            isClickable = false
            isFocusable = false
        }
        val view = WebView(activity).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = UA_DESKTOP
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportMultipleWindows(false)
                // 注意：不要关图、不要 blockNetworkImage —— secsdk 的指纹会用到
                // 图片渲染结果，关图会导致 __ac_signature / web_sign_token 不生成。
                loadsImagesAutomatically = true
                blockNetworkImage = false
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.TRANSPARENT)
            // 不拦截触摸：点击穿透回正常 UI
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = WebViewClient()
            // 必须给 WebChromeClient：否则页面里的 alert/confirm 会卡住 JS 线程，
            // 表现就是「页面无响应」。这里只记日志，不弹任何窗。
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d(
                        TAG,
                        "console[${msg.messageLevel()}] ${msg.message()} " +
                            "@${msg.sourceId()}:${msg.lineNumber()}",
                    )
                    return true
                }

                override fun onJsAlert(
                    v: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    Log.d(TAG, "js alert 已忽略：${message?.take(160)}")
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(
                    v: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    Log.d(TAG, "js confirm 已忽略：${message?.take(160)}")
                    result?.cancel()
                    return true
                }
            }
        }
        layout.addView(view)
        // 垫到内容视图之下（index 0），完全被上层 UI 覆盖
        val contentParent = root.findViewById<FrameLayout>(android.R.id.content) ?: root
        contentParent.addView(layout, 0)
        hostLayout = layout
        return view
    }

    /** 释放资源（从视图树摘除并销毁）。 */
    fun release() {
        Log.i(TAG, "release：摘除并销毁 WebView")
        mainHandler.post {
            try {
                hostLayout?.removeAllViews()
                (hostLayout?.parent as? ViewGroup)?.removeView(hostLayout)
            } catch (_: Exception) {
            }
            webView?.let {
                it.stopLoading()
                it.destroy()
            }
            hostLayout = null
            webView = null
            domainReady = false
            identityReady = false
        }
    }

    /** 清除登录状态。 */
    fun clearSession() {
        mainHandler.post {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            webView?.clearCache(true)
            domainReady = false
            identityReady = false
        }
    }

    /** 导出当前 Cookie，供下载器做防盗链校验。 */
    fun exportCookies(): Map<String, String> {
        val raw = CookieManager.getInstance().getCookie("https://www.douyin.com/") ?: return emptyMap()
        return raw.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
        }.toMap()
    }

    private suspend fun loadUrlSync(view: WebView, url: String) {
        val latch = CountDownLatch(1)
        val loadFailed = AtomicReference<String?>(null)
        withContext(Dispatchers.Main) {
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    Log.i(TAG, "onPageFinished：$u")
                    latch.countDown()
                }

                override fun onPageCommitVisible(v: WebView?, u: String?) {
                    // 页面真正渲染出来的信号，提前放行
                    Log.i(TAG, "onPageCommitVisible：$u")
                    latch.countDown()
                }

                override fun onReceivedError(
                    v: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    // 主文档失败才算死；子资源（图片/统计）失败忽略
                    if (request?.isForMainFrame == true) {
                        val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            error?.description?.toString() ?: "?"
                        } else {
                            "?"
                        }
                        Log.w(TAG, "主文档加载失败：$desc @${request.url}")
                        // 2.0.1：ERR_NAME_NOT_RESOLVED 等域名错误给中文可操作提示
                        val friendly = when {
                            desc.contains("NAME_NOT_RESOLVED") ->
                                "无法解析域名（当前网络无法访问抖音服务器，请切换网络后重试）"
                            desc.contains("ERR_TIMED_OUT") || desc.contains("Timeout") ->
                                "连接抖音服务器超时，请检查网络后重试"
                            desc.contains("ERR_CONNECTION_REFUSED") ->
                                "连接被拒绝，请检查网络或代理设置"
                            desc.contains("ERR_INTERNET_DISCONNECTED") ->
                                "网络已断开，请检查网络连接"
                            else -> "页面加载失败($desc)，请检查网络后重试"
                        }
                        loadFailed.set(friendly)
                        latch.countDown()
                    }
                }

                override fun shouldInterceptRequest(
                    v: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? = null
            }
            view.loadUrl(url)
        }
        val ok = withContext(Dispatchers.IO) {
            latch.await(LOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        }
        if (!ok) {
            Log.w(TAG, "页面加载超时（${LOAD_TIMEOUT_SEC}s）：$url")
            throw timeoutError()
        }
        loadFailed.get()?.let { throw ParseException.ParseFailed(it) }
    }

    /**
     * 等待身份 Cookie 就绪。
     *
     * 实测结论（Playwright + 本机 Edge 抓取首页 Cookie）：首页加载后出现
     * __ac_signature / ttwid / x-web-secsdk-uid / web_sign_token / UIFID_TEMP /
     * s_v_web_id / odin_tt / __ac_nonce ...。
     * **没有名为「UIFID」的 Cookie** —— 旧代码判断 cookie.contains("UIFID") 实际
     * 永远不满足，白等满超时。真正标志 Argus 身份就绪的是：
     *   __ac_signature 且（web_sign_token 或 x-web-secsdk-uid）。
     *
     * 超时不抛错（部分环境 Cookie 为 HttpOnly，读不到就靠 fetch 结果说话），
     * 只记日志并把「缺什么」留给最终错误信息。
     */
    private suspend fun waitForIdentity(view: WebView, timeoutSec: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        var logged = false
        while (System.currentTimeMillis() < deadline) {
            val names = cookieNames()
            if (identityConditionMet(names)) {
                Log.i(TAG, "身份已就绪（${System.currentTimeMillis() - (deadline - timeoutSec * 1000)}ms）" +
                    "，Cookie 名单：${names.sorted()}")
                return true
            }
            if (!logged) {
                Log.i(TAG, "等待身份中，当前 Cookie 名单：${names.sorted()}，" +
                    "缺少：${missingIdentity(names)}")
                logged = true
            }
            withContext(Dispatchers.IO) { Thread.sleep(WARMUP_POLL_MS) }
        }
        val names = cookieNames()
        lastMissingIdentity = missingIdentity(names)
        Log.w(
            TAG,
            "身份未在 ${timeoutSec}s 内就绪，缺少：$lastMissingIdentity；" +
                "当前 Cookie 名单：${names.sorted()}；当前页面：${view.url}",
        )
        return false
    }

    /** __ac_signature 且（web_sign_token 或 x-web-secsdk-uid）。 */
    private fun identityConditionMet(names: Set<String>): Boolean =
        names.contains("__ac_signature") &&
            (names.contains("web_sign_token") || names.contains("x-web-secsdk-uid"))

    private fun missingIdentity(names: Set<String>): String {
        val missing = mutableListOf<String>()
        if (!names.contains("__ac_signature")) missing.add("__ac_signature")
        if (!names.contains("web_sign_token") && !names.contains("x-web-secsdk-uid")) {
            missing.add("web_sign_token/x-web-secsdk-uid")
        }
        return if (missing.isEmpty()) "" else missing.joinToString("、")
    }

    private fun cookieNames(): Set<String> {
        val raw = CookieManager.getInstance().getCookie("https://www.douyin.com/") ?: ""
        return raw.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx).trim()
        }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * 统一构造超时异常：把「重试轮次 + 身份是否就绪」写进错误信息，
     * 让「身份未就绪」和「网络真的不通」在弹窗里可区分。
     */
    private fun timeoutError(retries: Int = 0): ParseException.NetworkTimeout {
        val parts = mutableListOf<String>()
        if (retries > 0) parts.add("已重试 ${retries} 次")
        if (!identityReady) {
            parts.add("身份未就绪：缺少 ${lastMissingIdentity.ifEmpty { "__ac_signature/web_sign_token" }}")
        } else {
            parts.add("身份已就绪")
        }
        return ParseException.NetworkTimeout(parts.joinToString("；"))
    }

    // ---------------- 页面内执行 ----------------

    /** 在页面上下文执行 JS 并返回值（主线程 evaluateJavascript）。 */
    private suspend fun evaluate(script: String, timeoutMs: Long = EVAL_TIMEOUT_MS): String =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                val view = webView
                if (view == null) {
                    cont.resumeWithException(ParseException.ParseFailed("解析引擎未初始化"))
                    return@post
                }
                val done = AtomicReference(false)
                view.evaluateJavascript(script) { value ->
                    if (done.compareAndSet(false, true)) {
                        cont.resume(value ?: "null")
                    }
                }
                mainHandler.postDelayed({
                    if (done.compareAndSet(false, true)) {
                        Log.w(TAG, "evaluateJavascript 回调超时（${timeoutMs}ms）：${script.take(120)}")
                        cont.resumeWithException(ParseException.NetworkTimeout())
                    }
                }, timeoutMs)
            }
        }

    /** 打 evaluate 返回值的头 200 字符。 */
    private fun logEval(label: String, value: String) {
        Log.i(TAG, "$label → ${value.take(200)}")
    }

    /**
     * 在页面内 fetch 并解析 JSON，返回 (状态码, JSONObject?)。
     *
     * 含 3 轮「重新预热 + 重试」：单轮超时 / 403 都不直接判死，先把 domainReady
     * 置 false 重新 ensureReady()（重新等身份）再发一次，3 轮全失败才抛错。
     * 「引擎未就绪 / 请求发起失败」这类时序问题同样重试。
     */
    private suspend fun fetchJson(url: String): Pair<Int, JSONObject?> {
        var lastError: ParseException? = null
        val budgetStart = System.currentTimeMillis()
        for (round in 1..MAX_FETCH_ROUNDS) {
            val roundStart = System.currentTimeMillis()
            Log.i(TAG, "in-page fetch 第 $round/$MAX_FETCH_ROUNDS 轮：${url.take(160)}")
            try {
                val result = fetchJsonOnce(url)
                Log.i(
                    TAG,
                    "in-page fetch 第 $round 轮成功，耗时 ${System.currentTimeMillis() - roundStart}ms，" +
                        "status=${result.first}",
                )
                return result
            } catch (e: ParseException.WorkGone) {
                // 404：作品确实不存在，重试没意义
                throw e
            } catch (e: ParseException.ParseFailed) {
                if (!isTransient(e)) throw e
                lastError = e
                Log.w(TAG, "in-page fetch 第 $round 轮时序失败：${e.message}")
            } catch (e: ParseException) {
                lastError = e
                Log.w(
                    TAG,
                    "in-page fetch 第 $round 轮失败（${e.javaClass.simpleName}）：${e.message}，" +
                        "耗时 ${System.currentTimeMillis() - roundStart}ms",
                )
            }

            if (round < MAX_FETCH_ROUNDS) {
                if (System.currentTimeMillis() - budgetStart > RETRY_BUDGET_MS) {
                    Log.w(TAG, "重试时间预算（${RETRY_BUDGET_MS}ms）已用尽，停止重试")
                    break
                }
                // 2.0.5 会话复用修正：超时只「同会话刷新页面」再重试，
                // 不销毁 WebView、不重置 domainReady（避免每次超时都当冷启动重来）。
                // 前几轮轻量刷新；最后一轮前才做一次完整重新预热（会话可能真退化）。
                kotlinx.coroutines.delay(1200L * round)
                try {
                    if (round < MAX_FETCH_ROUNDS - 1) {
                        Log.i(TAG, "同会话刷新页面后重试（round $round）")
                        refreshInSession()
                    } else {
                        Log.i(TAG, "连续失败，做一次完整重新预热")
                        domainReady = false
                        ensureReady()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "重试前刷新/预热失败：${e.message}")
                }
            }
        }
        throw lastError ?: timeoutError(MAX_FETCH_ROUNDS)
    }

    /** 「引擎未就绪 / 请求发起失败 / 轮询中断」属于可恢复的时序问题。 */
    private fun isTransient(e: ParseException.ParseFailed): Boolean {
        val m = e.message ?: return false
        return m.contains("未就绪") || m.contains("请求发起失败") || m.contains("响应中断")
    }

    /** 2.0.5 同会话轻量刷新：reload 当前页面。
     *  与 ensureReady() 的区别：不销毁 WebView、不清 Cookie、不重置
     *  domainReady —— 会话保活复用，只让页面重新加载一遍（相当于用户
     *  在同一浏览器标签里按了 F5）。超时重试的默认动作。 */
    private fun refreshInSession() {
        mainHandler.post {
            try {
                webView?.reload()
                Log.i(TAG, "refreshInSession: 同会话 reload 完成")
            } catch (e: Exception) {
                Log.w(TAG, "refreshInSession 失败：${e.message}")
            }
        }
        // 等页面回到可交互状态（轻量等待，不动 domainReady）
        Thread.sleep(2500)
    }

    /** 单轮 in-page fetch：JS 预检 → 发起 → 轮询 window.__dwdyResult__。 */
    private suspend fun fetchJsonOnce(url: String): Pair<Int, JSONObject?> {
        // 0) JS 可用性预检：页面没真正活起来就立刻报错，不带病轮询
        val jsAlive = try {
            evaluate("1+1", timeoutMs = 6_000)
        } catch (e: Exception) {
            throw ParseException.ParseFailed("解析引擎未就绪（页面无响应），请重试一次")
        }
        logEval("JS 预检 1+1", jsAlive)
        if (decodeEvalResult(jsAlive) != "2") {
            throw ParseException.ParseFailed("解析引擎未就绪（JS 未执行），请重试一次")
        }

        val requestScript = buildString {
            append("window.__dwdyResult__=null;")
            append("(async()=>{try{")
            append("const r=await fetch(").append(JSONObject.quote(url))
            append(",{credentials:'include',headers:{'accept':'application/json, text/plain, */*'}});")
            append("const t=await r.text();")
            append("window.__dwdyResult__=JSON.stringify({s:r.status,t:t});")
            append("}catch(e){window.__dwdyResult__=JSON.stringify({s:-1,t:'ERR:'+String(e)});}})();")
            append("null;")
        }
        // 1) 发起请求（立即返回，Promise 在页面内自行消化）
        try {
            val dispatched = evaluate(requestScript, timeoutMs = 8_000)
            logEval("fetch 已发起", dispatched)
        } catch (e: Exception) {
            Log.w(TAG, "请求发起失败：${e.message}")
            throw ParseException.ParseFailed("请求发起失败，请重试一次")
        }

        // 2) 轮询 window.__dwdyResult__
        val deadline = System.currentTimeMillis() + FETCH_TIMEOUT_MS
        var pollFailCount = 0
        var pollCount = 0
        var pollLogged = 0
        while (System.currentTimeMillis() < deadline) {
            pollCount++
            val raw = try {
                evaluate("window.__dwdyResult__", timeoutMs = 8_000)
            } catch (e: Exception) {
                // 单次轮询超时不致命：连续 3 次才放弃
                pollFailCount++
                Log.w(TAG, "第 $pollCount 次轮询失败（$pollFailCount/3）：${e.message}")
                if (pollFailCount >= 3) {
                    throw ParseException.ParseFailed("解析引擎响应中断，请重试一次")
                }
                withContext(Dispatchers.IO) { Thread.sleep(500) }
                continue
            }
            val decoded = decodeEvalResult(raw)
            if (pollLogged < POLL_LOG_LIMIT) {
                pollLogged++
                Log.i(TAG, "轮询 #$pollCount → ${decoded.take(200)}")
            } else if (pollLogged == POLL_LOG_LIMIT) {
                pollLogged++
                Log.i(TAG, "轮询 #$pollCount → ${decoded.take(200)}（后续轮询日志已省略）")
            }
            if (decoded != "null" && decoded.isNotEmpty() && decoded != "{}") {
                val payload = try {
                    JSONObject(decoded)
                } catch (e: Exception) {
                    throw ParseException.ParseFailed("页面数据异常")
                }
                val status = payload.optInt("s", -1)
                val body = payload.optString("t")
                Log.i(TAG, "in-page fetch 得到状态码 $status，body 长度 ${body.length}")
                return when {
                    status == -1 -> throw ParseException.NetworkTimeout("页面内请求异常：${body.take(80)}")
                    status == 403 -> throw ParseException.Forbidden()
                    status == 404 -> throw ParseException.WorkGone()
                    status >= 500 -> throw ParseException.NetworkTimeout("HTTP $status")
                    status >= 400 -> throw ParseException.ParseFailed("HTTP $status 请求失败")
                    else -> {
                        evaluate("window.__dwdyResult__=null", timeoutMs = 3_000)
                        status to runCatching { JSONObject(body) }.getOrNull()
                    }
                }
            }
            withContext(Dispatchers.IO) { Thread.sleep(POLL_INTERVAL_MS) }
        }
        Log.w(TAG, "in-page fetch 轮询超时（${FETCH_TIMEOUT_MS}ms，共 $pollCount 次轮询）")
        throw timeoutError()
    }

    /** evaluateJavascript 回调值统一解码为原始字符串。 */
    private fun decodeEvalResult(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return "null"
        return if (trimmed.startsWith("\"")) {
            runCatching { JSONObject("{\"v\":$trimmed}").getString("v") }.getOrDefault(trimmed)
        } else {
            trimmed
        }
    }

    // ---------------- 对外 API ----------------

    /**
     * 解析单条作品详情节点。
     *
     * 通路 1：in-page fetch 详情接口（内部已含 3 轮预热重试）。
     * 通路 2：通路 1 全失败 → 直接打开桌面详情页，从 DOM 抠真实播放地址并合成节点。
     */
    suspend fun fetchDetail(awemeId: String): JSONObject {
        ensureReady()
        var lastError: ParseException? = null
        var filterReason: String? = null

        // ---- 通路 1：页面内 fetch ----
        try {
            val (_, json) = fetchJson(detailUrl(awemeId))
            val detail = json?.optJSONObject("aweme_detail")
            if (detail != null) {
                Log.i(TAG, "通路1 命中：响应顶层含 aweme_detail")
                return detail
            }
            val reason = json?.optJSONObject("filter_detail")?.optString("filter_reason").orEmpty()
            if (reason.isNotEmpty()) {
                filterReason = reason
                lastError = ParseException.ParseFailed("作品已被平台下架或限流（$reason）")
            } else {
                lastError = ParseException.WorkGone()
            }
            Log.w(
                TAG,
                "通路1 返回 200 但没有 aweme_detail（filter=${reason.ifEmpty { "无" }}），转通路2",
            )
        } catch (e: ParseException.WorkGone) {
            throw e
        } catch (e: ParseException.ParseFailed) {
            if (isGoneOrLimited(e)) throw e
            lastError = e
        } catch (e: ParseException) {
            lastError = e
        }
        Log.w(TAG, "通路1 失败：${lastError?.message}，转通路2（桌面详情页兜底）")

        // ---- 通路 2：桌面详情页 DOM 兜底 ----
        try {
            return fetchDetailViaDom(awemeId)
        } catch (e: ParseException) {
            Log.w(TAG, "通路2（DOM 兜底）失败：${e.message}")
            lastError = e
        }

        if (filterReason != null) {
            throw ParseException.ParseFailed("作品已被平台下架或限流（$filterReason）")
        }
        throw lastError ?: timeoutError()
    }

    private fun isGoneOrLimited(e: ParseException.ParseFailed): Boolean =
        e.message?.contains("下架或限流") == true

    /**
     * 兜底通路：用桌面 UA 直接打开 https://www.douyin.com/video/{id}
     * （桌面 UA 下这是真实可渲染的桌面详情页），等页面加载完成后从
     * DOM / resource timing 里读出真实播放地址，合成一个最小可用的
     * aweme_detail 节点：画质列表只有一条，标注为「默认（页面兜底）」。
     */
    private suspend fun fetchDetailViaDom(awemeId: String): JSONObject {
        val view = webView ?: throw ParseException.ParseFailed("解析引擎未初始化")
        val url = "https://www.douyin.com/video/$awemeId"
        Log.i(TAG, "通路2：导航到桌面详情页 $url")
        loadUrlSync(view, url)

        val deadline = System.currentTimeMillis() + DOM_TIMEOUT_SEC * 1000
        var lastPayload = ""
        var probeCount = 0
        while (System.currentTimeMillis() < deadline) {
            probeCount++
            val raw = try {
                evaluate(DOM_PROBE_JS, timeoutMs = 12_000)
            } catch (e: Exception) {
                Log.w(TAG, "通路2 探针 #$probeCount 执行失败：${e.message}")
                withContext(Dispatchers.IO) { Thread.sleep(POLL_INTERVAL_MS) }
                continue
            }
            val decoded = decodeEvalResult(raw)
            lastPayload = decoded
            Log.i(TAG, "通路2 探针 #$probeCount → ${decoded.take(200)}")
            val mediaUrl = pickDomMediaUrl(decoded)
            if (mediaUrl != null) {
                Log.i(TAG, "通路2 命中播放地址：$mediaUrl")
                return synthesizeDetail(awemeId, decoded, mediaUrl)
            }
            withContext(Dispatchers.IO) { Thread.sleep(POLL_INTERVAL_MS) }
        }
        Log.w(TAG, "通路2 超时（${DOM_TIMEOUT_SEC}s，$probeCount 次探针），最后一次：${lastPayload.take(200)}")
        throw timeoutError()
    }

    /** 从兜底探针 JSON 里挑一个可直接下载的 http(s) 媒体地址（排除 blob:/data:）。 */
    private fun pickDomMediaUrl(payload: String): String? {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val candidates = mutableListOf<String>()
        candidates.add(obj.optString("src"))
        candidates.add(obj.optString("currentSrc"))
        obj.optJSONArray("res")?.let { arr ->
            for (i in 0 until arr.length()) candidates.add(arr.optString(i))
        }
        obj.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) candidates.add(arr.optString(i))
        }
        candidates.add(obj.optString("html"))
        return candidates.firstOrNull { isDownloadableUrl(it) }
    }

    /** 只接受 http(s)，blob: 和 data: 无法离线下载。 */
    private fun isDownloadableUrl(url: String): Boolean {
        val u = url.trim()
        return (u.startsWith("http://") || u.startsWith("https://")) && u.length > 16
    }

    /** 把兜底地址合成为一个最小 aweme_detail 结构（交给 Parser.buildWorkInfo 统一转换）。 */
    private fun synthesizeDetail(awemeId: String, payload: String, mediaUrl: String): JSONObject {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: JSONObject()
        val poster = obj.optString("poster").trim()
        val title = obj.optString("title").removeSuffix(" - 抖音").trim()
        val video = JSONObject().apply {
            // 只放一条兜底条目：避免出现「无水印 原画」这种与事实不符的标签
            put(
                "bit_rate",
                JSONArray().put(
                    JSONObject().apply {
                        put("gear_name", FALLBACK_LABEL)
                        put("bit_rate", 0)
                        put("play_addr", JSONObject().put("url_list", JSONArray().put(mediaUrl)))
                    },
                ),
            )
            if (poster.isNotEmpty()) {
                put("cover", JSONObject().put("url_list", JSONArray().put(poster)))
            }
        }
        return JSONObject().apply {
            put("aweme_id", awemeId)
            put("desc", title.ifEmpty { "无标题" })
            put(
                "author",
                JSONObject().apply {
                    put("nickname", "")
                    put("sec_uid", "")
                },
            )
            put("video", video)
        }
    }

    /** 获取作者主页作品列表原始 JSON 数组。 */
    suspend fun fetchAuthorPosts(secUid: String, count: Int): List<JSONObject> {
        ensureReady()
        val (_, json) = fetchJson(postUrl(secUid, count, 0))
        val list = json?.optJSONArray("aweme_list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.let { add(it) }
            }
        }
    }
}
