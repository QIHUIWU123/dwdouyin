// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** 下载引擎异常分类。 */
sealed class DownloadException(message: String) : IOException(message) {
    class Forbidden : DownloadException("403 防盗链拦截，下载失败")
    class Gone : DownloadException("文件地址失效(404)")
    class Timeout : DownloadException("下载超时，请检查网络后重试")
    class Network(reason: String) : DownloadException("网络连接失败：$reason")
    class Cancelled : DownloadException("已取消")
}

/** 下载引擎：流式写入、进度/速度回调、最多3次自动重试。 */
object DownloadEngine {

    /** 使用与解析引擎一致的桌面 UA，避免直链防盗链拒绝。 */
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private const val MAX_RETRY = 3
    private const val BUFFER_SIZE = 256 * 1024
    private const val TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * Cookie 提供者：由界面注入 WebView 导出的 Cookie，
     * 视频直链需要防盗链校验，缺少 Cookie 会 403。
     */
    @Volatile
    var cookieProvider: (() -> Map<String, String>)? = null

    private fun cookieHeader(): String? {
        val map = cookieProvider?.invoke() ?: return null
        if (map.isEmpty()) return null
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 下载到 openSink() 提供的输出流（每次重试重新获取，以便覆盖截断），返回写入字节数。 */
    suspend fun download(
        url: String,
        openSink: () -> OutputStream,
        onProgress: (received: Long, total: Long, speedBps: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(MAX_RETRY) { attempt ->
            if (attempt > 0) delay(1500L * attempt)
            try {
                return@withContext downloadOnce(url, openSink, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                if (e is DownloadException.Forbidden || e is DownloadException.Gone) throw e
                lastError = e
            } catch (e: Exception) {
                lastError = DownloadException.Network(e.message ?: "未知错误")
            }
        }
        throw lastError ?: DownloadException.Network("下载失败")
    }

    private fun downloadOnce(
        url: String,
        openSink: () -> OutputStream,
        onProgress: (Long, Long, Long) -> Unit,
    ): Long {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", UA)
            // 3.2.0 多平台兼容：抖音 CDN 需要抖音 Referer；其它平台（B站/快手等）
            // 各自校验不同，用来源站点根域兜底，抖音直链仍走原逻辑
            val host = runCatching { URL(url).host }.getOrDefault("")
            val referer = when {
                host.contains("douyin") || host.contains("douyinpic") || host.contains("douyinvod") ->
                    "https://www.douyin.com/"
                host.contains("ixigua") || host.contains("usergrowth") || host.contains("snssdk") ||
                    host.contains("amemv") || host.contains("bytedance") || host.contains("zjcdn") ->
                    "https://www.douyin.com/"
                host.contains("bilivideo") || host.contains("bilibili") || host.contains("hdslb") ->
                    "https://www.bilibili.com/"
                host.contains("kuaishou") || host.contains("gifshow") || host.contains("yxixg") ->
                    "https://www.kuaishou.com/"
                else -> ""  // 未知平台不发 Referer（多数 CDN 不校验）
            }
            if (referer.isNotBlank()) conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            cookieHeader()?.let { conn.setRequestProperty("Cookie", it) }
            val code = conn.responseCode
            if (code == 403) throw DownloadException.Forbidden()
            if (code == 404) throw DownloadException.Gone()
            if (code !in 200..299) throw DownloadException.Network("HTTP $code")
            val total = conn.contentLengthLong
            var received = 0L
            var lastReport = System.currentTimeMillis()
            var lastBytes = 0L
            conn.inputStream.use { input ->
                openSink().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250) {
                            val speed = (received - lastBytes) * 1000 / (now - lastReport).coerceAtLeast(1)
                            onProgress(received, total, speed)
                            lastReport = now
                            lastBytes = received
                        }
                    }
                }
            }
            onProgress(received, total, 0)
            return received
        } catch (e: SocketTimeoutException) {
            throw DownloadException.Timeout()
        } finally {
            conn.disconnect()
        }
    }
}
