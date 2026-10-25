// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** 解析异常分类，与界面弹窗一一对应。 */
sealed class ParseException(message: String) : Exception(message) {
    class NoLinkFound : ParseException("未从粘贴文本中提取到抖音链接")

    /** 网络不通（预检阶段直接抛，不进 45s 等待）。 */
    class NetworkOffline : ParseException("网络未连接或信号极弱，请先连接网络后重试")

    /** 网络极慢（<50KB/s，测速后抛；提示检查网络而不是误报平台限制）。 */
    class NetworkSlow(detail: String) : ParseException(detail)

    /**
     * 超时。可带 detail，用于区分「身份未就绪」和「网络真的不通」。
     */
    class NetworkTimeout(detail: String? = null) : ParseException(
        if (detail.isNullOrBlank()) {
            "网络请求超时，请检查网络后重试"
        } else {
            "网络请求超时，请检查网络后重试（$detail）"
        },
    )

    /** Cookie/登录态失效（WebView 身份未就绪且非网络原因）。 */
    class SessionExpired(detail: String = "") : ParseException(
        if (detail.isBlank()) {
            "登录状态已失效，请在【设置】页点「清除登录」后重新进入抖音页面登录"
        } else {
            "登录状态已失效（$detail），请在【设置】页点「清除登录」后重新登录"
        },
    )

    /** 被平台风控限制（区别于网络问题：明确告知是平台侧限制）。 */
    class RiskLimited(detail: String = "") : ParseException(
        if (detail.isBlank()) {
            "当前网络/环境被平台限制，可稍后重试或更换网络（如切换 Wi-Fi/流量）"
        } else {
            "当前网络/环境被平台限制（$detail），可稍后重试或更换网络"
        },
    )

    class Forbidden : ParseException("403 风控拦截，请稍后重试")
    class WorkGone : ParseException("作品失效或已被删除")
    class ParseFailed(reason: String) : ParseException("解析失败：$reason")
}

/** 单个可下载媒体条目（2.0.0：携带文件大小与宽高；2.0.8：加画质档位）。 */
data class MediaEntry(
    val label: String,
    val url: String,
    val sizeBytes: Long = 0L,
    val wh: String = "",
    val grade: String = "",
) {
    /** 胶囊/列表显示文案（2.0.8 加档位）：
     *  `1080P · 1080p · 105.94 MB · 1920x1080`（档位与 gear 重复时只显一次，缺哪段省哪段）。 */
    val displayLabel: String
        get() {
            val parts = mutableListOf<String>()
            if (grade.isNotBlank() && !grade.lowercase().contains(label.lowercase())) {
                parts.add(grade)
            }
            parts.add(label)
            val size = formatSize(sizeBytes)
            if (size.isNotEmpty()) parts.add(size)
            if (wh.isNotBlank()) parts.add(wh)
            return parts.joinToString(" · ")
        }

    companion object {
        /** 文件大小格式化（2.0.8：统一保留 2 位小数，与 PC 端一致）。 */
        fun formatSize(bytes: Long): String = when {
            bytes <= 0L -> ""
            bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format("%.2f MB", bytes / 1048576.0)
            bytes >= 1L shl 10 -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

        /** 2.0.8 统一画质档位标准（与 PC 端 quality_grade 完全一致）：
         *  按长边判：≥3840→4K；≥2560→2K；≥1920→1080P；≥1280→720P；低于→标清。
         *  长边 = max(宽,高)：抖音以竖屏为主（1080x1920），纯按宽判会把竖屏
         *  全标成「标清」，长边判法与平台显示习惯一致。 */
        fun qualityGrade(width: Int, height: Int): String {
            val major = maxOf(width, height)
            if (major <= 0) return ""
            return when {
                major >= 3840 -> "4K"
                major >= 2560 -> "2K"
                major >= 1920 -> "1080P"
                major >= 1280 -> "720P"
                else -> "标清"
            }
        }
    }
}

/** 解析结果作品信息。 */
data class WorkInfo(
    val awemeId: String,
    val shareUrl: String,
    val workType: String, // video / images
    val title: String,
    val author: String,
    val secUid: String,
    val coverUrl: String,
    val mediaList: List<MediaEntry>,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("awemeId", awemeId)
        root.put("shareUrl", shareUrl)
        root.put("workType", workType)
        root.put("title", title)
        root.put("author", author)
        root.put("secUid", secUid)
        root.put("coverUrl", coverUrl)
        val arr = JSONArray()
        mediaList.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("label", entry.label)
                    .put("url", entry.url)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("wh", entry.wh)
                    .put("grade", entry.grade),
            )
        }
        root.put("mediaList", arr)
        return root.toString()
    }

    companion object {
        fun fromJson(text: String): WorkInfo {
            val root = JSONObject(text)
            val arr = root.optJSONArray("mediaList") ?: JSONArray()
            val media = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        MediaEntry(
                            label = obj.optString("label"),
                            url = obj.optString("url"),
                            sizeBytes = obj.optLong("sizeBytes", 0L),
                            wh = obj.optString("wh", ""),
                            grade = obj.optString("grade", ""),
                        ),
                    )
                }
            }
            return WorkInfo(
                awemeId = root.optString("awemeId"),
                shareUrl = root.optString("shareUrl"),
                workType = root.optString("workType", "video"),
                title = root.optString("title"),
                author = root.optString("author"),
                secUid = root.optString("secUid"),
                coverUrl = root.optString("coverUrl"),
                mediaList = media,
            )
        }
    }
}

/**
 * 抖音解析器。
 *
 * 数据获取统一走 [WebViewParser]：抖音已启用 Argus 风控，
 * OkHttp / HttpURLConnection 直连详情接口会被 403 拦截，
 * 只有 WebView 真实浏览器环境内发起请求才能通过。
 *
 * 注意：必须传入 Activity 上下文（WebView 需挂载到 Activity 视图树，
 * 传 applicationContext 会导致「解析引擎需要 Activity 上下文」错误）。
 * 短链跳转仍用 HttpURLConnection（无风控，快）。
 */
class DouyinParser(private val context: Context) {

    private val webEngine = WebViewParser(context)

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
        /** 2.0.2：单请求超时 10s → 6s（配合通路级总限时）。 */
        private const val TIMEOUT_MS = 6_000
        const val BATCH_MAX_COUNT = 20
        /** 批量间隔下限；2.1.1 实际取 U(2.5s, 5.0s) 随机值（合规节奏，不固定）。 */
        private const val BATCH_INTERVAL_MS = 2500L
        private const val BATCH_INTERVAL_JITTER_MS = 2500L

        /** SSR 分享页重试上限（外层已有总限时）。 */
        private const val SSR_MAX_ATTEMPTS = 3
        /** 重试退避基数：300ms * attempt。 */
        private const val SSR_RETRY_BASE_MS = 300L

        /** 2.0.3 并行竞速预算（总超时放宽：feed/SSR 并行 30s + WebView 45s）。 */
        private const val FEED_PARALLEL_TIMEOUT_MS = 30_000L
        private const val SSR_PARALLEL_TIMEOUT_MS = 30_000L
        private const val WEB_PARALLEL_TIMEOUT_MS = 45_000L

        /** 2.0.7 suxun API 兜底限时（第三方接口，每日限 20 次，仅兜底）。 */
        private const val SUXUN_TIMEOUT_MS = 12_000L

        /** 2.0.8 suxun 系接口每日配额（douyin 明示 20；juhe 按同池保守计数）。 */
        const val SUXUN_DAILY_LIMIT = 20

        /** 2.1.1 网速阈值（KB/s）：低于此值提示「网络极慢」而非误报平台限制。 */
        private const val MIN_SPEED_KBPS = 50.0

        private val SHORT_LINK = Regex("https?://v\\.(?:douyin|iesdouyin)\\.com/[A-Za-z0-9_-]+/?")
        private val WEB_LINK = Regex(
            "https?://(?:www\\.)?douyin\\.com/(?:video|note|slides|gallery)/(\\d+)",
        )

        /** 用户主页模态页 ?modal_id=xxx（借鉴 douyin-downloader：优先按单作品处理）。 */
        private val MODAL_ID = Regex("[?&]modal_id=(\\d+)")
        private val USER_LINK = Regex("https?://(?:www\\.)?douyin\\.com/user/([A-Za-z0-9_-]+)")
        private val AWEME_IN_PATH = Regex("/(?:share/)?(?:video|note|slides|gallery)/(\\d+)")

        /** 从混杂文本中提取第一条抖音链接。 */
        fun extractLink(text: String): String? {
            SHORT_LINK.find(text)?.let { return it.value.trimEnd('/') }
            // modal_id 优先（用户主页弹窗查看单作品）
            MODAL_ID.find(text)?.let {
                return "https://www.douyin.com/video/${it.groupValues[1]}"
            }
            WEB_LINK.find(text)?.let { return "https://www.douyin.com/video/${it.groupValues[1]}" }
            return null
        }

        /** 3.2.0 通用链接提取：不限抖音——检测到任意 http(s) 链接就返回（聚合解析 33+ 平台）。
         *  抖音链接仍走 extractLink 归一化；其它平台（B站/快手/小红书/YouTube…）原样返回。 */
        private val ANY_LINK = Regex("https?://[\\w.-]+(?:/[\\w./?%&=-]*)?")

        fun extractAnyLink(text: String): String? {
            extractLink(text)?.let { return it }
            // 非抖音：取第一条 http(s) 链接（过滤常见干扰域）
            val candidates = ANY_LINK.findAll(text)
                .map { it.value.trimEnd('.', ')', '，', '。') }
                .filter { it.startsWith("http") && !it.contains("douyin.com") }
            return candidates.firstOrNull()
        }

        /** 批量模式：从混杂文本中提取主页链接的 sec_uid。 */
        fun extractUserSecUid(text: String): String? =
            USER_LINK.find(text)?.groupValues?.get(1)
    }

    /** 释放 WebView 资源，界面销毁时调用。 */
    fun release() = webEngine.release()

    /** 清除登录状态。 */
    fun clearSession() = webEngine.clearSession()

    /** 导出 Cookie 供下载器使用。 */
    fun exportCookies(): Map<String, String> = webEngine.exportCookies()

    /**
     * 2.0.6 重新登录（设置页「重新登录」按钮调用）：
     * 清会话 → 重新预热 WebView（用户可见地重载抖音首页）→ 轮询登录态。
     * 登录成功（sessionid 出现）即返回；最多等 3 分钟。
     */
    suspend fun reloginForSession() {
        clearSession()
        // 触发可见预热（WebView 已挂 Activity 视图树上，重载首页即用户可见）
        webEngine.ensureReady()
        // 轮询登录 Cookie，最多 3 分钟
        repeat(90) {
            val cookies = webEngine.exportCookies()
            if (cookies.containsKey("sessionid") || cookies.containsKey("sessionid_ss")) {
                return
            }
            kotlinx.coroutines.delay(2000)
        }
        // 3 分钟未登录不抛错——用户可能稍后自己登；Cookie 检测在下次解析时照常
    }

    /** 解析入口：单条作品。
     *
     * 3.2.0 双路由（用户需求）：
     *   - 抖音链接：归一化 awemeId 后走全通道（dyjx 全画质 → svparse → juhe → douyin）；
     *   - 非抖音链接（B站/快手/小红书/YouTube 等 33+ 平台）：直接透传给 svparse 聚合通道。
     * 画质列表给全档位（原画 + 1080P/720P/540P…），不只给单档。
     */
    suspend fun parse(
        text: String,
        onStage: ((String) -> Unit)? = null,
    ): WorkInfo {
        val link = extractAnyLink(text) ?: throw ParseException.NoLinkFound()

        // ---- ① 网络预检：3s 内快速探测，不通直接报，不进解析等待 ----
        onStage?.invoke("检查网络连接…")
        ensureNetworkAlive()
        onStage?.invoke("网络正常，解析链接…")

        // ---- ② 非抖音链接：直接透传 svparse 聚合通道（33+ 平台）----
        if (!link.contains("douyin.com")) {
            onStage?.invoke("聚合解析中（支持 33+ 平台）…")
            val item = try {
                withTimeoutOrNull(20_000L) { fetchViaAggregator(link) }
            } catch (e: ParseException) {
                null
            } catch (e: Exception) {
                null
            }
            if (item != null) {
                return buildWorkInfo(item, link)
            }
            throw ParseException.ParseFailed(
                "聚合解析接口未返回数据（支持抖音/B站/快手/小红书/YouTube 等 33+ 平台）。" +
                    "请确认链接为视频/图文分享链接后重试",
            )
        }

        // ---- ③ 抖音链接：归一化后走全通道 ----
        val awemeId = resolveAwemeId(link)
        val startTime = System.currentTimeMillis()

        onStage?.invoke("正在解析作品…")
        val item = try {
            withTimeoutOrNull(SUXUN_TIMEOUT_MS * 2) { fetchFromSuxun(awemeId) }
        } catch (e: ParseException) {
            if (e is ParseException.WorkGone) throw e
            null
        } catch (e: Exception) {
            null
        }
        if (item != null) {
            Log.i("dwdy-parser", "接口解析命中 (${System.currentTimeMillis() - startTime}ms)")
            return buildWorkInfo(item, link)
        }

        // ---- ④ 全失败：分类报错 ----
        throw ParseException.ParseFailed(
            "解析接口均未返回数据（已按 dyjx → svparse → juhe → douyin 顺序尝试）。" +
                "请稍后重试，或到【设置】检查接口开关",
        )
    }

    /** 网络预检（2.1.1 增强版）：
     *  ① 连通性：3s HEAD 探测；
     *  ② 速度：下载 64KB 样本测速，< 50KB/s 视为「极慢」——
     *     直接提示检查网络，不空等 50 秒后误报「被平台限制」。
     *  全部合规操作，不涉及指纹/签名绕过。 */
    private suspend fun ensureNetworkAlive() = withContext(Dispatchers.IO) {
        val probes = listOf(
            "https://www.douyin.com/",       // 目标域（同域探测，结果对解析有代表性）
            "http://connect.rom.miui.com/generate_204",  // 轻量连通性探测点
            "http://www.baidu.com/",         // 兜底
        )
        var reached = false
        for (url in probes) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                conn.disconnect()
                // 2xx/3xx/4xx 都算网络通（4xx 说明能到服务器，只是资源问题）
                if (code in 200..499) {
                    reached = true
                    break
                }
            } catch (e: Exception) {
                // 换下一个探测点
            }
        }
        if (!reached) throw ParseException.NetworkOffline()

        // ---- 速度检测：下载 64KB（8 × 8KB 块）估算吞吐 ----
        val speedTestUrl = "https://www.douyin.com/"
        try {
            val conn = URL(speedTestUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 3_000
            conn.readTimeout = 4_000
            conn.setRequestProperty("User-Agent", UA)
            val started = System.currentTimeMillis()
            val buf = ByteArray(8192)
            var downloaded = 0L
            conn.inputStream.use { stream ->
                while (downloaded < 65_536) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    downloaded += n
                }
            }
            conn.disconnect()
            val elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(1)
            val kbps = downloaded / 1024.0 / (elapsedMs / 1000.0)
            Log.i("dwdy-parser", "网速检测: ${"%.0f".format(kbps)} KB/s（样本 ${downloaded / 1024}KB / ${elapsedMs}ms）")
            if (kbps < MIN_SPEED_KBPS) {
                throw ParseException.NetworkSlow(
                    "网络极慢（约 ${"%.0f".format(kbps)} KB/s，低于 ${MIN_SPEED_KBPS.toInt()}KB/s）。" +
                        "请先检查网络（切换 Wi-Fi/流量或稍后再试）",
                )
            }
        } catch (e: ParseException) {
            throw e
        } catch (e: Exception) {
            // 速度检测失败不阻塞解析（连通性已确认），只记日志
            Log.w("dwdy-parser", "网速检测失败（跳过）：${e.javaClass.simpleName}")
        }
    }

    /** 三通路全失败后的分类诊断：
     *  重新探测网络 → 通 → 看身份 Cookie → 有 → 平台限制；无 → Cookie 失效。
     *  不通 → 网络问题。绝不把三类混成一个「解析超时」。 */
    private suspend fun classifyFailure(awemeId: String, startTime: Long): ParseException {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000

        // 1) 网络层面：再探一次（可能解析期间网络掉了）
        val netAlive = try {
            withTimeoutOrNull(3_000L) { ensureNetworkAliveQuiet() }
            true
        } catch (e: Exception) {
            false
        }
        if (!netAlive) {
            return ParseException.NetworkOffline()
        }

        // 2) Cookie/身份层面：WebView 通路是否拿到过身份
        val cookies = webEngine.exportCookies()
        val hasIdentity = cookies.containsKey("__ac_signature") &&
            (cookies.containsKey("web_sign_token") || cookies.containsKey("x-web-secsdk-uid"))
        // 2.0.6：有登录态（sessionid）也能区分「匿名被限」与「登录被限」
        val hasLogin = cookies.containsKey("sessionid") || cookies.containsKey("sessionid_ss")

        // 3) 综合分类（登录态缺失优先引导登录）
        return when {
            !hasIdentity -> ParseException.SessionExpired(
                "三通路未取到数据且引擎身份 Cookie 缺失（网络本身是通的）",
            )
            !hasLogin -> ParseException.SessionExpired(
                "引擎身份正常但未登录抖音；登录后解析成功率更高",
            )
            else -> ParseException.RiskLimited(
                "网络畅通、引擎身份正常，但 feed/分享页/浏览器均未返回作品数据（${elapsed}s）",
            )
        }
    }

    /** 静默网络探测（不抛异常，返回 Boolean）。 */
    private suspend fun ensureNetworkAliveQuiet() {
        try {
            ensureNetworkAlive()
        } catch (e: Exception) {
            throw e
        }
    }

    /** SSR 只有单档 play_addr 时，尝试用 feed 的 bit_rate 补全画质列表。
     *  feed 失败/没数据就原样返回（单档也比空强）。 */
    private suspend fun mergeQualityFromFeed(ssrItem: JSONObject, awemeId: String): JSONObject {
        try {
            val ssrVideo = ssrItem.optJSONObject("video") ?: return ssrItem
            val hasBitRate = (ssrVideo.optJSONArray("bit_rate")?.length() ?: 0) > 0
            if (hasBitRate) return ssrItem

            val feedItem = withTimeoutOrNull(15_000L) { fetchFromFeed(awemeId) }
                ?: return ssrItem
            val feedVideo = feedItem.optJSONObject("video") ?: return ssrItem
            val bitRate = feedVideo.optJSONArray("bit_rate")
            if (bitRate != null && bitRate.length() > 0) {
                // 只搬 bit_rate（多档直链），保留 SSR 的封面/作者等字段
                val merged = JSONObject(ssrItem.toString())
                val mergedVideo = merged.optJSONObject("video") ?: return ssrItem
                mergedVideo.put("bit_rate", bitRate)
                // feed 的 play_addr 通常也是高清无水印，一并替换
                feedVideo.optJSONObject("play_addr")?.let { mergedVideo.put("play_addr", it) }
                feedVideo.optJSONObject("download_addr")?.let { mergedVideo.put("download_addr", it) }
                Log.i("dwdy-parser", "feed 补画质成功：${bitRate.length()} 档")
                return merged
            }
        } catch (e: Exception) {
            Log.w("dwdy-parser", "feed 补画质失败（不影响单档结果）：${e.message}")
        }
        return ssrItem
    }

    /**
     * amemv feed 免签名通路（借鉴 VideoDownLoad-master）：App 端推荐流接口，
     * 目标作品命中时在列表前几位，必须按 aweme_id 精确匹配——未命中不得
     * 回落到首条（会静默给错视频）。
     * PC 端实测 0.7s 返回 916KB 完整数据（5 档 bit_rate 全带 data_size）。
     *
     * 2.0.1 修复 ERR_NAME_NOT_RESOLVED：amemv 有多个镜像域，部分运营商
     * DNS 对 api3-normal-c-lf 解析失败。按顺序轮换域名，全失败返回 null
     * （交给 SSR/WebView 通路，而不是抛异常把解析判死）。
     */
    private suspend fun fetchFromFeed(awemeId: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val feedHosts = listOf(
                "api3-normal-c-lf.amemv.com",
                "api3-normal-c-use.amemv.com",
                "aweme.snssdk.com",
            )
            var lastException: Exception? = null
            for (host in feedHosts) {
                val feedUrl = (
                    "https://$host/aweme/v1/feed/?aweme_id=$awemeId" +
                        "&version_code=26.2.0&app_name=aweme&channel=App+Store" +
                        "&device_type=iPhone&device_platform=iphone&os_version=16.0&aid=1128"
                    )
                try {
                    val conn = openConnection(feedUrl, followRedirects = true)
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                    conn.disconnect()
                    val root = JSONObject(body)
                    val list = root.optJSONArray("aweme_list") ?: return@withContext null
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        if (item.optString("aweme_id") == awemeId) {
                            return@withContext item
                        }
                    }
                    // 域名通了但没命中目标（推荐流不含该作品）→ 不必再试其它域名
                    return@withContext null
                } catch (e: SocketTimeoutException) {
                    lastException = e
                    // 超时换下一个域名
                } catch (e: java.io.IOException) {
                    // UnknownHostException / 连接失败 → 换下一个域名
                    lastException = e
                } catch (e: ParseException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                }
            }
            // 全部域名失败：返回 null 交给下一通路（SSR/WebView），不判死
            Log.w("dwdy-parser", "feed 全域名失败：${lastException?.javaClass?.simpleName}: ${lastException?.message}")
            null
        }

    /**
     * suxun 系 API 兜底通路（第三方解析接口）。
     *
     * ★ 配额警告：api.suxun.site 的 /api/douyin 明示「每日限制 20 次调用」——
     *   仅兜底，绝不作主解析入口；feed/SSR 竞速全空才调它。
     * 2.0.8：双接口顺序兜底——先 /api/juhe（无明示配额），失败再 /api/douyin，
     *   把 20 次/日的 douyin 配额留给 juhe 失败时用（相当于配额翻倍）。
     *   每次真实调用（含失败）计入本地次数（SharedPreferences，按自然日重置），
     *   供设置页「解析次数」显示。
     *
     * 返回合成 item（与抖音原生结构对齐，buildWorkInfo 直接消费）：
     * code==200 且 data.url 非空才有货；两个接口全失败返回 null（落 WebView）。
     */
    /**
     * 第三方 API 多通道兜底（2.0.9 重构，与 PC 端 api_channels.py 对齐）。
     *
     * 通道顺序：bugpk（画质全）→ juhe → suxun/douyin → 用户自定义…
     * ★ 不再硬限 20 次——接口失败（网络/配额尽）自动切下一个。
     * 通道可在【设置】勾选启停/新增/删除（存 SharedPreferences JSON）。
     * 每次调用计入本地统计（展示用，不拦截）。
     *
     * parser_type：
     *   "suxun" — code/data{url 单档,images[]}（suxun 系）
     *   "bugpk" — code/data{url,width,height,size,video_backup[{url,quality,width,height,size}]}
     *             （画质信息完整：档位/大小/宽高）
     *
     * 全通道失败返回 null（落 WebView）。
     */
    private suspend fun fetchFromSuxun(awemeId: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val channels = enabledApiChannels()
            for (ch in channels) {
                val apiUrl = ch.urlTemplate.replace(
                    "{URL}", "https://www.douyin.com/video/$awemeId",
                )
                try {
                    val item = fetchFromApiChannel(ch, apiUrl)
                    if (item != null) {
                        Log.i("dwdy-parser", "API[${ch.name}] 命中")
                        return@withContext item
                    }
                } catch (e: java.io.IOException) {
                    Log.w("dwdy-parser", "API[${ch.name}] 网络失败：${e.javaClass.simpleName}")
                } catch (e: org.json.JSONException) {
                    Log.w("dwdy-parser", "API[${ch.name}] JSON 异常：${e.message}")
                } catch (e: Exception) {
                    Log.w("dwdy-parser", "API[${ch.name}] 失败：${e.javaClass.simpleName}")
                }
            }
            null
        }

    /** 3.2.0 非抖音链接聚合解析：只走 svparse 通道（其余通道仅支持抖音）。 */
    private suspend fun fetchViaAggregator(link: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val aggregator = listApiChannels().firstOrNull {
                it.id == "builtin_bugpk_svparse" && it.enabled
            } ?: return@withContext null
            try {
                val apiUrl = aggregator.urlTemplate.replace("{URL}", encodeUrl(link))
                fetchFromApiChannel(aggregator, apiUrl)
            } catch (e: Exception) {
                Log.w("dwdy-parser", "聚合解析失败：${e.javaClass.simpleName}")
                null
            }
        }

    /** 3.2.0 通用通道请求（带可选 API Key 头）+ 响应合成 item。 */
    private fun fetchFromApiChannel(ch: ApiChannel, apiUrl: String): JSONObject? {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("User-Agent", UA)
        if (ch.apiKeyHeader.isNotBlank()) {
            conn.setRequestProperty("X-API-Key", ch.apiKeyHeader)
        }
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        bumpSuxunCount()
        val root = JSONObject(body)
        if (root.optInt("code") != 200) {
            Log.w("dwdy-parser", "API[${ch.name}] code=${root.optInt("code")}: ${root.optString("msg")}")
            return null
        }
        val data = root.optJSONObject("data") ?: return null
        // 3.2.0：aweme_id 尽量从响应 extra 里取（非抖音平台没有则留空）
        val awemeId = data.optJSONObject("extra")?.optString("aweme_id")
            ?: data.optJSONObject("aweme_id")?.toString()
            ?: ""
        return when (ch.parserType) {
            "bugpk" -> buildBugpkItem(awemeId, data)
            else -> buildSuxunItem(awemeId, data)
        }
    }

    /** URL-encode（聚合通道要求 encode，suxun 通道实测不 encode 也通）。 */
    private fun encodeUrl(url: String): String =
        java.net.URLEncoder.encode(url, "UTF-8")

    /** bugpk 通道合成 item：画质信息完整（原画主档 + video_backup 全部档位）。
     *  3.2.0：awemeId 允许为空（非抖音平台无 aweme_id，用 link 兜底）。 */
    private fun buildBugpkItem(awemeId: String, data: JSONObject): JSONObject? {
        val videoUrl = data.optString("url").orEmpty()
        val imagesArr = data.optJSONArray("images")
        val isGallery = imagesArr != null && imagesArr.length() > 0
        if (videoUrl.isBlank() && !isGallery) return null

        val item = JSONObject()
        item.put("aweme_id", awemeId.ifBlank { "agg_${System.currentTimeMillis()}" })
        item.put("desc", data.optString("title").ifBlank { "无标题" })
        val authorObj = data.optJSONObject("author")
        val secUid = data.optJSONObject("extra")
            ?.optJSONObject("author_extra")?.optString("sec_uid").orEmpty()
        item.put(
            "author",
            JSONObject().apply {
                put("nickname", authorObj?.optString("name").orEmpty().ifBlank { "未知作者" })
                put("sec_uid", secUid)
            },
        )

        if (isGallery) {
            val images = JSONArray()
            for (i in 0 until imagesArr.length()) {
                val u = imagesArr.optString(i)
                if (u.isNotBlank()) images.put(JSONObject().put("url_list", JSONArray().put(u)))
            }
            if (images.length() == 0) return null
            item.put("images", images)
            return item
        }

        // ---- 全档位画质列表（3.2.0 用户需求：所有画质都给出，不只原画）----
        // 主档（data.url）用响应的 label（如「原画」），档位值 quality=original → 高度 9998
        val bitRate = JSONArray()
        val mainLabel = data.optString("label").ifBlank { "原画" }
        val mainQuality = data.optString("quality").orEmpty()
        val mainHeight = when {
            mainQuality.equals("original", true) -> 9998
            data.optInt("height", 0) > 0 -> data.optInt("height", 0)
            else -> 9998
        }
        bitRate.put(
            JSONObject().apply {
                put("gear_name", mainQuality.ifBlank { "original" })
                put("bit_rate", data.optLong("bit_rate", 0L))
                put(
                    "play_addr",
                    JSONObject().apply {
                        put("url_list", JSONArray().put(videoUrl))
                        put("data_size", data.optLong("size", 0L))
                        put("width", data.optInt("width", 0))
                        put("height", data.optInt("height", 0))
                    },
                )
            },
        )
        // video_backup 各档（1080P/720P/540P…）全部加入
        val backup = data.optJSONArray("video_backup")
        if (backup != null) {
            for (i in 0 until backup.length()) {
                val vb = backup.optJSONObject(i) ?: continue
                val vUrl = vb.optString("url").orEmpty()
                if (vUrl.isBlank()) continue
                bitRate.put(
                    JSONObject().apply {
                        put("gear_name", vb.optString("quality").ifBlank { "backup" })
                        put("bit_rate", vb.optLong("bit_rate", 0L))
                        put(
                            "play_addr",
                            JSONObject().apply {
                                put("url_list", JSONArray().put(vUrl))
                                put("data_size", vb.optLong("size", 0L))
                                put("width", vb.optInt("width", 0))
                                put("height", vb.optInt("height", 0))
                            },
                        )
                    },
                )
            }
        }

        item.put(
            "video",
            JSONObject().apply {
                // play_addr 也放主档（首屏封面/兼容旧消费方）
                put(
                    "play_addr",
                    JSONObject().apply {
                        put("url_list", JSONArray().put(videoUrl))
                        put("data_size", data.optLong("size", 0L))
                        put("width", data.optInt("width", 0))
                        put("height", data.optInt("height", 0))
                    },
                )
                put("cover", JSONObject().apply {
                    put("url_list", JSONArray().put(data.optString("cover")))
                })
                put("bit_rate", bitRate)
                // 3.2.0：label 存到 video 节点（buildVideoMedia 用它做主档名）
                put("main_label", mainLabel)
            },
        )
        return item
    }

    /** suxun 系通道合成 item：单档直链。 */
    private fun buildSuxunItem(awemeId: String, data: JSONObject): JSONObject? {
        val videoUrl = data.optString("url").orEmpty()
        val imagesArr = data.optJSONArray("images")
        val isGallery = imagesArr != null && imagesArr.length() > 0
        if (videoUrl.isBlank() && !isGallery) return null

        val item = JSONObject()
        item.put("aweme_id", awemeId)
        item.put("desc", data.optString("title").ifBlank { "无标题" })
        val authorObj = data.optJSONObject("author")
        item.put(
            "author",
            JSONObject().apply {
                put("nickname", authorObj?.optString("name").orEmpty().ifBlank { "未知作者" })
                put("sec_uid", authorObj?.optString("sec_uid").orEmpty())
            },
        )

        if (isGallery) {
            val images = JSONArray()
            for (i in 0 until imagesArr.length()) {
                val u = imagesArr.optString(i)
                if (u.isNotBlank()) images.put(JSONObject().put("url_list", JSONArray().put(u)))
            }
            if (images.length() == 0) return null
            item.put("images", images)
            return item
        }

        item.put(
            "video",
            JSONObject().apply {
                put("play_addr", JSONObject().apply {
                    put("url_list", JSONArray().put(videoUrl))
                    put("data_size", 0L)
                })
                put("cover", JSONObject().apply {
                    put("url_list", JSONArray().put(data.optString("cover")))
                })
                put("bit_rate", JSONArray())
            },
        )
        return item
    }

    // ---------------- 2.0.9 通道管理（SharedPreferences JSON，与设置页共用）----------------

    /** 通道数据类（3.2.0：加 apiKeyHeader，bugpk-live 通道鉴权用）。 */
    data class ApiChannel(
        val id: String,
        val name: String,
        val urlTemplate: String,
        val parserType: String,
        val enabled: Boolean,
        val builtin: Boolean,
        val apiKeyHeader: String = "",
    )

    /** 内置通道（3.2.0：新增 dyjx 抖音全画质 + svparse 聚合解析，均带 Key；保留原有 suxun 双通道）。
     *  Key 从 assets/api_keys.properties 读取（该文件不进 git，见 .gitignore）。 */
    private fun apiKeys(): Map<String, String> = runCatching {
        context.assets.open("api_keys.properties").bufferedReader().use { reader ->
            val props = java.util.Properties()
            props.load(reader)
            mapOf(
                "dyjx" to (props.getProperty("dyjx") ?: ""),
                "svparse" to (props.getProperty("svparse") ?: ""),
            )
        }
    }.getOrDefault(mapOf("dyjx" to "", "svparse" to ""))

    private val builtinChannels by lazy {
        val keys = apiKeys()
        listOf(
            ApiChannel(
                "builtin_bugpk_dyjx", "抖音全画质（原画+多档）",
                "https://api-new.ifphp.com/api/dyjx?url={URL}", "bugpk", true, true,
                keys["dyjx"].orEmpty(),
            ),
            ApiChannel(
                "builtin_bugpk_svparse", "聚合解析（33+平台）",
                "https://api-new.ifphp.com/api/svparse?url={URL}", "bugpk", true, true,
                keys["svparse"].orEmpty(),
            ),
            ApiChannel(
                "builtin_juhe", "juhe 解析接口",
                "https://api.suxun.site/api/juhe?url={URL}", "suxun", true, true,
            ),
            ApiChannel(
                "builtin_suxun_douyin", "douyin 解析接口（备用）",
                "https://api.suxun.site/api/douyin?url={URL}", "suxun", true, true,
            ),
        )
    }

    /** 通道持久化 key（设置页与本类共用）。 */
    private val channelsPrefKey = "api_channels_json"

    private fun channelsPrefs() =
        context.getSharedPreferences("dwdouyin_prefs", Context.MODE_PRIVATE)

    /** 读全部通道（内置 + 自定义），enabled 状态以持久化为准。 */
    fun listApiChannels(): List<ApiChannel> {
        return try {
            val raw = channelsPrefs().getString(channelsPrefKey, null)
            val custom = mutableListOf<ApiChannel>()
            val enabledMap = mutableMapOf<String, Boolean>()
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (o.optBoolean("builtin")) {
                        enabledMap[id] = o.optBoolean("enabled", true)
                    } else {
                        custom.add(
                            ApiChannel(
                                id, o.optString("name"), o.optString("url_template"),
                                o.optString("parser_type", "suxun"),
                                o.optBoolean("enabled", true), false,
                                o.optString("api_key_header", ""),
                            ),
                        )
                    }
                }
            }
            builtinChannels.map { it.copy(enabled = enabledMap[it.id] ?: true) } + custom
        } catch (e: Exception) {
            Log.w("dwdy-parser", "读通道失败：${e.message}")
            builtinChannels
        }
    }

    /** 当前启用的通道（解析时按此顺序）。 */
    private fun enabledApiChannels(): List<ApiChannel> =
        listApiChannels().filter { it.enabled }

    /** 设置页调用：写全量通道列表。 */
    fun saveApiChannels(channels: List<ApiChannel>) {
        try {
            val arr = JSONArray()
            channels.forEach { ch ->
                arr.put(
                    JSONObject().apply {
                        put("id", ch.id)
                        put("name", ch.name)
                        put("url_template", ch.urlTemplate)
                        put("parser_type", ch.parserType)
                        put("enabled", ch.enabled)
                        put("builtin", ch.builtin)
                        if (ch.apiKeyHeader.isNotBlank()) put("api_key_header", ch.apiKeyHeader)
                    },
                )
            }
            channelsPrefs().edit().putString(channelsPrefKey, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w("dwdy-parser", "写通道失败：${e.message}")
        }
    }

    /** 设置页调用：切换单个通道启停。 */
    fun setApiChannelEnabled(channelId: String, enabled: Boolean) {
        val all = listApiChannels().map {
            if (it.id == channelId) it.copy(enabled = enabled) else it
        }
        saveApiChannels(all)
    }

    /** 设置页调用：新增自定义通道。返回错误信息（null=成功）。 */
    fun addApiChannel(name: String, urlTemplate: String, parserType: String): String? {
        if (!urlTemplate.contains("{URL}")) {
            return "URL 模板必须包含 {URL} 占位符"
        }
        val all = listApiChannels().toMutableList()
        all.add(
            ApiChannel(
                "custom_${System.currentTimeMillis()}",
                name.ifBlank { "自定义接口" }.take(30),
                urlTemplate,
                if (parserType in listOf("suxun", "bugpk")) parserType else "suxun",
                true, false,
            ),
        )
        saveApiChannels(all)
        return null
    }

    /** 设置页调用：删除自定义通道（内置不可删）。 */
    fun removeApiChannel(channelId: String) {
        saveApiChannels(listApiChannels().filter { !(it.id == channelId && !it.builtin) })
    }

    /** 2.0.8 suxun 系解析次数（SharedPreferences 本地计数，自然日重置）。 */
    private fun bumpSuxunCount() {
        try {
            val prefs = context.getSharedPreferences("dwdouyin_prefs", Context.MODE_PRIVATE)
            val today = android.text.format.DateFormat.format("yyyy-MM-dd", java.util.Date()).toString()
            val savedDate = prefs.getString("suxun_date", "") ?: ""
            val current = if (savedDate == today) prefs.getInt("suxun_count", 0) else 0
            prefs.edit()
                .putString("suxun_date", today)
                .putInt("suxun_count", current + 1)
                .apply()
        } catch (e: Exception) {
            Log.w("dwdy-parser", "计数失败：${e.message}")
        }
    }

    /** 2.0.8 读取今日已用次数（设置页显示框用）。 */
    fun getSuxunTodayCount(): Int {
        return try {
            val prefs = context.getSharedPreferences("dwdouyin_prefs", Context.MODE_PRIVATE)
            val today = android.text.format.DateFormat.format("yyyy-MM-dd", java.util.Date()).toString()
            val savedDate = prefs.getString("suxun_date", "") ?: ""
            if (savedDate == today) prefs.getInt("suxun_count", 0) else 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * SSR 分享页通路：GET https://www.iesdouyin.com/share/video/{id}/
     * 解析 window._ROUTER_DATA → loaderData["video_(id)/page"]["videoInfoRes"]["item_list"][0]。
     *
     * 实测该 SSR 数据约 2/3 概率出现（服务端 A/B），重试最多 6 次后成功率极高；
     * 必须用移动 UA（PC UA 会跳转到需要 Argus 的桌面壳页）。
     */
    private suspend fun fetchSharePageItem(awemeId: String): JSONObject? =
        withContext(Dispatchers.IO) {
            // 双域名容错：www.iesdouyin.com DNS 失败时换 m.douyin.com（同源内容）
            val urls = listOf(
                "https://www.iesdouyin.com/share/video/$awemeId/",
                "https://m.douyin.com/share/video/$awemeId/",
            )
            var lastException: Exception? = null
            outer@ for (url in urls) {
                for (attempt in 1..SSR_MAX_ATTEMPTS) {
                    try {
                        val conn = openConnection(url, followRedirects = true)
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use {
                            it.readText()
                        }
                        conn.disconnect()
                        val item = extractRouterItem(body)
                        if (item != null) return@withContext item
                    } catch (e: SocketTimeoutException) {
                        lastException = e
                        if (attempt == SSR_MAX_ATTEMPTS) break@outer
                    } catch (e: ParseException) {
                        throw e
                    } catch (e: IOException) {
                        // UnknownHostException（ERR_NAME_NOT_RESOLVED）→ 立刻换域名，不重试当前域名
                        lastException = e
                        break
                    } catch (e: Exception) {
                        lastException = e
                        if (attempt == SSR_MAX_ATTEMPTS) break@outer
                    }
                    kotlinx.coroutines.delay(SSR_RETRY_BASE_MS * attempt)
                }
            }
            // SSR 全部失败：返回 null 走 WebView 兜底，而不是抛错判死
            Log.w("dwdy-parser", "SSR 全域名失败：${lastException?.javaClass?.simpleName}: ${lastException?.message}")
            null
        }

    /** 从分享页 HTML 里抠 _ROUTER_DATA.item_list[0]；未命中返回 null（调用方重试）。 */
    private fun extractRouterItem(html: String): JSONObject? {
        val marker = "window._ROUTER_DATA"
        val start = html.indexOf(marker)
        if (start < 0) return null
        val braceStart = html.indexOf("{", start)
        if (braceStart < 0) return null
        // 括号配平提取完整 JSON（含字符串里的花括号）
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (i in braceStart until html.length) {
            val ch = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end < 0) return null
        val raw = html.substring(braceStart, end + 1)
        return try {
            val data = JSONObject(raw)
            val page = data.optJSONObject("loaderData")
                ?.optJSONObject("video_(id)/page") ?: return null
            page.optJSONObject("videoInfoRes")
                ?.optJSONArray("item_list")?.takeIf { it.length() > 0 }
                ?.optJSONObject(0)
        } catch (e: org.json.JSONException) {
            null
        }
    }

    /**
     * 主页批量模式（可选开关）：解析作者最近作品，≤20 条，强制间隔防高频。
     * onStage 回调用于界面状态展示。
     */
    suspend fun parseAuthorPosts(
        text: String,
        maxCount: Int = BATCH_MAX_COUNT,
        onStage: (String) -> Unit,
    ): List<WorkInfo> {
        val count = minOf(maxCount, BATCH_MAX_COUNT)
        val secUid = extractUserSecUid(text) ?: run {
            onStage("解析分享文本，定位作者主页…")
            val link = extractLink(text) ?: throw ParseException.NoLinkFound()
            val awemeId = resolveAwemeId(link)
            // 批量也优先走 SSR 通路（限时 30s），失败换 WebView（限时 40s）
            val item = try {
                withTimeoutOrNull(30_000L) { fetchSharePageItem(awemeId) }
                    ?: withTimeoutOrNull(40_000L) { webEngine.fetchDetail(awemeId) }
            } catch (e: ParseException.WorkGone) {
                throw e
            } catch (e: Exception) {
                withTimeoutOrNull(40_000L) { webEngine.fetchDetail(awemeId) }
            }
            item?.optJSONObject("author")?.optString("sec_uid").orEmpty().ifEmpty {
                throw ParseException.ParseFailed("未能获取作者主页ID，无法批量解析")
            }
        }
        onStage("获取主页作品列表…")
        val posts = withTimeoutOrNull(45_000L) { webEngine.fetchAuthorPosts(secUid, count) }
            ?: throw ParseException.ParseFailed("获取主页作品列表超时，请重试")
        if (posts.isEmpty()) throw ParseException.WorkGone()

        val works = mutableListOf<WorkInfo>()
        val total = minOf(posts.size, count)
        for (index in 0 until total) {
            onStage("解析主页作品 ${index + 1}/$total…")
            try {
                works.add(buildWorkInfo(posts[index]))
            } catch (e: ParseException) {
                // 单条失败跳过，不中断批量
            }
            if (index < total - 1) {
                // 2.1.1 合规节奏：2.5~5.0 秒随机间隔（不固定，降低风控误伤）
                val jitter = (0..BATCH_INTERVAL_JITTER_MS).random()
                kotlinx.coroutines.delay(BATCH_INTERVAL_MS + jitter)
            }
        }
        if (works.isEmpty()) throw ParseException.ParseFailed("主页作品全部解析失败（可能触发风控）")
        return works
    }

    /** 只解析 aweme_id：短链用 HttpURLConnection 读 302（无风控）。
     *  必须切到 IO 线程：主线程网络请求会抛 NetworkOnMainThreadException。 */
    private suspend fun resolveAwemeId(link: String): String = withContext(Dispatchers.IO) {
        WEB_LINK.find(link)?.let { return@withContext it.groupValues[1] }
        try {
            val conn = openConnection(link, followRedirects = false)
            val code = conn.responseCode
            if (code == 403) throw ParseException.Forbidden()
            if (code == 404) throw ParseException.WorkGone()
            val location = conn.getHeaderField("Location") ?: link
            conn.disconnect()
            val match = AWEME_IN_PATH.find(location)
                ?: throw ParseException.WorkGone()
            return@withContext match.groupValues[1]
        } catch (e: SocketTimeoutException) {
            throw ParseException.NetworkTimeout()
        } catch (e: ParseException) {
            throw e
        } catch (e: Exception) {
            // NetworkOnMainThreadException 等 Android 系统异常 message 常为 null，
            // 直接用类名避免「null」出现在用户面前
            throw ParseException.ParseFailed("网络连接失败：${e.javaClass.simpleName}")
        }
    }

    private fun openConnection(url: String, followRedirects: Boolean = true): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.instanceFollowRedirects = followRedirects
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://www.douyin.com/")
        return conn
    }

    /** 把接口返回的作品节点转成统一结构（单条与批量共用）。 */
    private fun buildWorkInfo(item: JSONObject, link: String = ""): WorkInfo {
        val authorObj = item.optJSONObject("author")
        val author = authorObj?.optString("nickname").orEmpty().ifEmpty { "未知作者" }
        val secUid = authorObj?.optString("sec_uid").orEmpty()
        val title = item.optString("desc").ifEmpty { "无标题" }
        val images = item.optJSONArray("images")
        val video = item.optJSONObject("video")

        val workType: String
        val media: List<MediaEntry>
        val cover: String
        if (images != null && images.length() > 0) {
            workType = "images"
            media = buildImageMedia(images)
            cover = images.optJSONObject(0)?.optJSONArray("url_list")
                ?.let { if (it.length() > 0) it.optString(0) else "" }.orEmpty()
        } else if (video != null) {
            workType = "video"
            media = buildVideoMedia(video)
            cover = firstUrl(video.optJSONObject("cover")).orEmpty()
        } else {
            throw ParseException.WorkGone()
        }
        if (media.isEmpty()) throw ParseException.WorkGone()

        return WorkInfo(
            awemeId = item.optString("aweme_id"),
            shareUrl = link,
            workType = workType,
            title = title,
            author = author,
            secUid = secUid,
            coverUrl = cover,
            mediaList = media,
        )
    }

    /** 收集视频多清晰度，按分辨率+码率降序，列表首项为最高画质。
     *  2.0.0：每档携带 data_size（文件大小）与宽高。
     *  2.0.8：每档加 grade（统一画质档位：4K/2K/1080P/720P/标清，按长边判）。 */
    private fun buildVideoMedia(video: JSONObject): List<MediaEntry> {
        data class Ranked(
            val label: String,
            val url: String,
            val rank: Pair<Int, Long>,
            val sizeBytes: Long,
            val wh: String,
            val grade: String,
        )

        fun nodeSize(node: JSONObject?): Long {
            if (node == null) return 0L
            return node.optLong("data_size", 0L)
        }

        fun nodeWh(node: JSONObject?): String {
            if (node == null) return ""
            val w = node.optInt("width", 0)
            val h = node.optInt("height", 0)
            return if (w > 0 || h > 0) "${if (w > 0) w else ""}x${if (h > 0) h else ""}".trim('x') else ""
        }

        /** 节点宽高 → 档位；宽高缺失时按 gear 高度映射。 */
        fun nodeGrade(node: JSONObject?, gearHeight: Int = 0): String {
            if (node != null) {
                val g = MediaEntry.qualityGrade(node.optInt("width", 0), node.optInt("height", 0))
                if (g.isNotBlank()) return g
            }
            // gear 高度映射（与 PC 端 _grade_from_height 一致）
            return when {
                gearHeight >= 2160 -> "4K"
                gearHeight >= 1440 -> "2K"
                gearHeight >= 1080 -> "1080P"
                gearHeight >= 720 -> "720P"
                gearHeight > 0 -> "标清"
                else -> ""
            }
        }

        val entries = mutableListOf<Ranked>()
        val playAddr = video.optJSONObject("play_addr")
        // 3.2.0：主档名用接口给的 label（如「原画」），不再硬编码「无水印 原画」
        val mainLabel = video.optString("main_label").ifBlank { "无水印 原画" }
        firstUrl(playAddr)?.let {
            entries.add(
                Ranked(
                    mainLabel, it, 9999 to 0L,
                    nodeSize(playAddr), nodeWh(playAddr), nodeGrade(playAddr),
                ),
            )
        }

        val bitRates = video.optJSONArray("bit_rate")
        if (bitRates != null) {
            for (i in 0 until bitRates.length()) {
                val rate = bitRates.optJSONObject(i) ?: continue
                val playNode = rate.optJSONObject("play_addr")
                val url = firstUrl(playNode) ?: continue
                // 主档已在 play_addr 列出，bit_rate 里若含同 URL 跳过（避免重复）
                if (entries.any { it.url == url }) continue
                val gear = rate.optString("gear_name")
                val bitrate = rate.optLong("bit_rate", 0L)
                val resMatch = Regex("(\\d{3,4})").find(gear)
                val (height, label) = when {
                    gear.contains("4k", ignoreCase = true) -> 2160 to "4K"
                    resMatch != null -> resMatch.groupValues[1].toInt() to gear
                    gear.contains("origin", ignoreCase = true) -> 9998 to "原画"
                    else -> {
                        val kbps = (bitrate / 1000).toInt()
                        0 to when {
                            kbps > 0 -> "${kbps}kbps"
                            // 没有档位信息的 gear_name 直接当标签用
                            gear.isNotBlank() -> gear
                            else -> "标准清晰度"
                        }
                    }
                }
                entries.add(
                    Ranked(
                        label, url, height to bitrate,
                        nodeSize(playNode), nodeWh(playNode), nodeGrade(playNode, height),
                    ),
                )
            }
        }

        val dlAddr = video.optJSONObject("download_addr")
        firstUrl(dlAddr)?.let {
            entries.add(
                Ranked(
                    "自动适配（带水印）", it, -1 to 0L,
                    nodeSize(dlAddr), nodeWh(dlAddr), nodeGrade(dlAddr),
                ),
            )
        }

        return entries
            .sortedByDescending { it.rank.first * 10_000_000L + it.rank.second }
            .distinctBy { it.label }
            .map { MediaEntry(it.label, it.url, it.sizeBytes, it.wh, it.grade) }
    }

    /** 图文作品：每个图片取 url_list 最高画质（末位）。 */
    private fun buildImageMedia(images: JSONArray): List<MediaEntry> = buildList {
        for (i in 0 until images.length()) {
            val node = images.optJSONObject(i) ?: continue
            val urls = node.optJSONArray("url_list") ?: continue
            if (urls.length() > 0) {
                add(MediaEntry("图片${i + 1}", urls.optString(urls.length() - 1)))
            }
        }
    }

    private fun firstUrl(node: JSONObject?): String? {
        val urls = node?.optJSONArray("url_list") ?: return null
        return if (urls.length() > 0) urls.optString(0) else null
    }
}
