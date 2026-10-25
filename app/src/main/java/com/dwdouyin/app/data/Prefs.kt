// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.data

import android.content.Context
import android.net.Uri

/** 偏好设置：保存目录（SAF）+ 画质偏好。 */
object Prefs {
    private const val FILE = "dwdouyin_prefs"
    private const val KEY_TREE_URI = "tree_uri"

    fun getTreeUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setTreeUri(context: Context, uri: Uri?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri?.toString())
            .apply()
    }
}

/** 画质偏好：highest / high / balanced / auto，持久化到 SharedPreferences。 */
object QualityPrefs {
    private const val FILE = "dwdouyin_prefs"
    private const val KEY = "quality_pref"

    /** 可选项：key → 展示文案。 */
    val OPTIONS = listOf(
        "highest" to "最高画质（推荐）",
        "high" to "1080p 及以上优先",
        "balanced" to "720p 平衡（省流量）",
        "auto" to "自动适配",
    )

    fun get(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "highest") ?: "highest"

    fun set(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
