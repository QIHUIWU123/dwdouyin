// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 全局下载状态，供首页与悬浮进度展示。 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Running(
        val filename: String,
        val received: Long,
        val total: Long,
        val speedBps: Long,
        val status: String,
    ) : DownloadState()

    data class Done(val filename: String, val path: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

/** 下载管理器：单任务串行；保存到系统相册（MediaStore），命名「日期_文案」。 */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state
    private var job: Job? = null

    val isRunning: Boolean
        get() = job?.isActive == true

    /** 下载单条媒体（index 为空时取第一项：最高画质 / 第一张图）。 */
    fun start(context: Context, work: WorkInfo, index: Int? = null) {
        val entry = work.mediaList.getOrNull(index ?: 0) ?: return
        start(context, work, listOf(entry))
    }

    /** 全部下载：顺序下载单条作品的媒体列表。 */
    fun startAll(context: Context, work: WorkInfo) {
        if (work.mediaList.isEmpty()) return
        start(context, work, work.mediaList)
    }

    /** 主页批量下载（可选开关）：bestQuality 为真取最高画质，否则默认画质。 */
    fun startBatch(context: Context, works: List<WorkInfo>, bestQuality: Boolean) {
        val tasks = works.mapNotNull { work ->
            val entry = if (bestQuality) {
                work.mediaList.firstOrNull()
            } else {
                work.mediaList.firstOrNull { it.label.contains("自动") }
                    ?: work.mediaList.lastOrNull()
            } ?: return@mapNotNull null
            Triple(work, entry, if (bestQuality) "批量·最高画质" else "批量·默认画质")
        }
        if (tasks.isNotEmpty()) start(context, tasks)
    }

    fun cancel() {
        job?.cancel()
        _state.value = DownloadState.Idle
    }

    fun dismiss() {
        if (!isRunning) _state.value = DownloadState.Idle
    }

    private fun start(context: Context, work: WorkInfo, entries: List<MediaEntry>) {
        val tasks = entries.map { Triple(work, it, "") }
        start(context, tasks)
    }

    private fun start(context: Context, tasks: List<Triple<WorkInfo, MediaEntry, String>>) {
        if (isRunning) return
        val appContext = context.applicationContext
        job = scope.launch {
            val total = tasks.size
            for ((offset, task) in tasks.withIndex()) {
                val (taskWork, entry, note) = task
                val status = when {
                    total > 1 && note.startsWith("批量") -> "批量下载中 (${offset + 1}/$total)"
                    total > 1 -> "下载中 (${offset + 1}/$total)"
                    else -> "下载中"
                }
                try {
                    val saved = saveEntry(appContext, taskWork, entry, status)
                    _state.value = DownloadState.Done(saved.first, saved.second)
                } catch (e: DownloadException.Cancelled) {
                    _state.value = DownloadState.Idle
                    return@launch
                } catch (e: Exception) {
                    _state.value = DownloadState.Failed(e.message ?: "下载失败")
                    return@launch
                }
            }
        }
    }

    /**
     * 保存单个媒体。
     * 默认保存到系统相册（MediaStore）：
     *   视频 → DCIM/dwdouyin（相册直接可见，与相机视频同区）
     *   图片 → DCIM/dwdouyin
     * 用户设置了 SAF 目录时优先用 SAF 目录。
     * Android 10+ MediaStore 不需要存储权限。
     */
    private suspend fun saveEntry(
        context: Context,
        work: WorkInfo,
        entry: MediaEntry,
        status: String,
    ): Pair<String, String> {
        val displayName = buildDisplayName(work, entry)
        val mime = guessMime(work.workType, entry.url)

        // 1) 用户自定义 SAF 目录
        val treeUri = Prefs.getTreeUri(context)
        val tree = treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (tree != null && tree.canWrite()) {
            val doc = tree.findFile(displayName)
                ?: tree.createFile(mime, displayName)
                ?: throw IOException("无法在所选目录创建文件")
            val savedPath = doc.uri.toString()
            val received = downloadTo(context, work, entry, doc.uri, displayName, status)
            return displayName to savedPath
        }

        // 2) 系统相册（默认）：DCIM/dwdouyin —— 相册 App 直接可见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val savedPath = saveToMediaStore(context, work, entry, displayName, mime, status)
            return displayName to savedPath
        }

        // 3) Android 8/9 回退：直接写公共 DCIM（需要 WRITE_EXTERNAL_STORAGE，
        //    已在 Manifest 声明 maxSdkVersion=28）
        @Suppress("DEPRECATION")
        val publicDcim = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "dwdouyin",
        )
        if (publicDcim.isDirectory || publicDcim.mkdirs()) {
            if (publicDcim.canWrite()) {
                val file = uniqueFile(File(publicDcim, displayName))
                DownloadEngine.download(entry.url, { FileOutputStream(file) }) { recv, totalBytes, speed ->
                    _state.value = DownloadState.Running(displayName, recv, totalBytes, speed, status)
                }
                recordHistory(context, work)
                // 扫描进相册索引
                kotlin.runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    )
                    intent.data = android.net.Uri.fromFile(file)
                    context.sendBroadcast(intent)
                }
                return displayName to file.absolutePath
            }
        }

        // 4) 最终回退：应用私有目录（不进相册但保证能存下）
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val file = uniqueFile(File(dir, displayName))
        DownloadEngine.download(entry.url, { FileOutputStream(file) }) { recv, totalBytes, speed ->
            _state.value = DownloadState.Running(displayName, recv, totalBytes, speed, status)
        }
        recordHistory(context, work)
        return displayName to file.absolutePath
    }

    /** MediaStore 保存：进 DCIM/dwdouyin，系统相册直接可见，无需权限（Android 10+）。 */
    private suspend fun saveToMediaStore(
        context: Context,
        work: WorkInfo,
        entry: MediaEntry,
        displayName: String,
        mime: String,
        status: String,
    ): String {
        val isVideo = work.workType == "video"
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            // DCIM：系统相册 App（相册/图库/Google Photos）都会索引此目录
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/dwdouyin")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("无法写入相册（MediaStore 创建失败）")

        try {
            DownloadEngine.download(entry.url, {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("无法打开相册输出流")
            }) { recv, totalBytes, speed ->
                _state.value = DownloadState.Running(displayName, recv, totalBytes, speed, status)
            }
            // 写入完成，解除 pending → 相册立即可见
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
            recordHistory(context, work)
            return "相册/DCIM/dwdouyin/$displayName"
        } catch (e: Exception) {
            // 失败时清理 pending 条目，不留垃圾
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }
    }

    /** SAF 目录下载（进度回调统一）。 */
    private suspend fun downloadTo(
        context: Context,
        work: WorkInfo,
        entry: MediaEntry,
        uri: android.net.Uri,
        displayName: String,
        status: String,
    ): Long {
        val received = DownloadEngine.download(entry.url, {
            context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("无法打开输出流")
        }) { recv, totalBytes, speed ->
            _state.value = DownloadState.Running(displayName, recv, totalBytes, speed, status)
        }
        recordHistory(context, work)
        return received
    }

    /** 下载完成回调（挂起上下文里安全写历史）。 */
    private suspend fun recordHistory(context: Context, work: WorkInfo) {
        HistoryDb.upsert(
            context,
            HistoryEntity(
                awemeId = work.awemeId,
                title = work.title,
                author = work.author,
                coverUrl = work.coverUrl,
                workType = work.workType,
                mediaJson = work.toJson(),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 命名规则（按用户要求）：保存日期 + 视频文案。
     * 形如：20261016_莫白电影-豺狼的日子解说第3期.mp4
     * 多图作品加序号后缀。
     */
    private fun buildDisplayName(work: WorkInfo, entry: MediaEntry): String {
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        val ext = if (work.workType == "video") {
            ".mp4"
        } else if (entry.url.contains(".png")) {
            ".png"
        } else {
            ".jpg"
        }
        val suffix = if (work.workType == "images") "_${entry.label}" else ""
        // 文案中的非法字符替换；# 开头的标签保留为可读文本
        val titleText = work.title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n#]"), " ")
            .trim()
        val raw = "${dateStr}_${work.author}-${titleText}$suffix"
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .trim('.', ' ', '_')
        val body = when {
            raw.length > 90 -> raw.substring(0, 90).trim('_', ' ')
            raw.isEmpty() -> "${dateStr}_作品"
            else -> raw
        }
        return body + ext
    }

    private fun guessMime(workType: String, url: String): String {
        if (workType == "video") return "video/mp4"
        return if (url.contains(".png")) "image/png" else "image/jpeg"
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile ?: return file
        val name = file.nameWithoutExtension
        val ext = file.extension
        for (i in 1 until 999) {
            val candidate = File(parent, "${name}($i).$ext")
            if (!candidate.exists()) return candidate
        }
        return File(parent, "${name}-${System.currentTimeMillis()}.$ext")
    }
}
