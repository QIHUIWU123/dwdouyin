// 抖音作品解析下载工具 - dwdouyin（仅用于个人技术学习，禁止商用、批量爬取，遵守版权法规）
package com.dwdouyin.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计系统 v2（UI/UX 优化版）：
 *   单主色策略——品牌蓝为唯一强调色，保存/成功等不再另起绿色（旧版蓝绿琥珀混用）；
 *   全部中性色统一冷灰调；所有可点元素带按压反馈（scale 0.97）；
 *   圆角分级：容器 20 / 卡片 16 / 控件 12；字重引入 SemiBold 层级。
 */

// ============ 色彩令牌（单一冷灰体系 + 单一品牌蓝） ============
val PageBg = Color(0xFFF6F7F9)          // 页面底：冷调浅灰
val CardWhite = Color(0xFFFFFFFF)        // 卡片
val CardBorder = Color(0xFFE9ECF1)       // 卡片描边
val BluePrimary = Color(0xFF2F6398)      // 主蓝：降饱和品牌色
val BlueDeep = Color(0xFF234E7E)
val BlueLightBg = Color(0xFFEAF1F9)      // 浅蓝底（选中态）
val BlueSoftBg = Color(0xFFF3F7FB)      // 更浅蓝底（输入框等）
val HeroTop = Color(0xFFE8F0FA)          // Hero 渐变起
val HeroBottom = Color(0xFFF6FAFE)       // Hero 渐变止
val GreenSave = BluePrimary              // 兼容旧引用：保存色并入主蓝
val TextDark = Color(0xFF1B2733)         // 主文字：冷调深灰
val TextGray = Color(0xFF74818F)        // 次级文字
val TextLightGray = Color(0xFFAAB4C0)    // 占位文字
val ThumbBg = Color(0xFFEDF0F4)
val DividerGray = Color(0xFFF0F2F5)

// 兼容旧引用的别名
val BgDeep = PageBg
val GlassCard = CardWhite
val GlassCardHover = Color(0xFFF7FAFD)
val GlassStroke = CardBorder
val Teal = BluePrimary
val SkyBlue = Color(0xFF5B9BD5)
val TextPrimary = TextDark
val TextSecondary = TextGray
val TextDim = TextLightGray
val Success = Color(0xFF3E8E5A)
val Danger = Color(0xFFD64550)
val DangerBg = Color(0xFFFDF1F2)
val WarnAmber = Color(0xFFC58A17)

val primaryGradient = Brush.verticalGradient(listOf(Color(0xFF3570AE), BluePrimary))
val heroGradient = Brush.verticalGradient(listOf(HeroTop, HeroBottom))
val bgGradient = Brush.verticalGradient(listOf(PageBg, PageBg))

/** 白色圆角卡片。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    hover: Boolean = false,
    cornerRadius: Int = 16,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    Box(
        modifier
            .clip(shape)
            .background(CardWhite, shape)
            .border(1.dp, CardBorder, shape),
    ) {
        content()
    }
}

/** 主按钮：主蓝实心 + 按压反馈。 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 15.sp,
) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(120),
        label = "btnScale",
    )
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(
                if (enabled) {
                    if (pressed) BlueDeep else BluePrimary
                } else {
                    BluePrimary.copy(alpha = 0.35f)
                },
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = fontSize)
    }
}

/** 描边按钮：白底描边 + 按压变浅蓝。 */
@Composable
fun GlassOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = TextDark,
) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(120),
        label = "outlineScale",
    )
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(
                if (pressed && enabled) BlueSoftBg else CardWhite,
                shape,
            )
            .border(
                1.dp,
                if (pressed && enabled) BluePrimary.copy(alpha = 0.45f) else CardBorder,
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) contentColor else TextLightGray, fontSize = 14.sp)
    }
}

/** 清晰度胶囊（选中浅蓝底 + 蓝字，带按压反馈）。 */
@Composable
fun QualityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120),
        label = "chipScale",
    )
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(if (selected) BlueLightBg else CardWhite, shape)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) BluePrimary else CardBorder,
                shape,
            )
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) BluePrimary else TextDark,
        )
    }
}

/** 小徽标（10% 透明度品牌色底）。 */
@Composable
fun Badge(text: String, color: Color = BluePrimary) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** 功能特点条目：图标容器 + 标题 + 描述。 */
@Composable
fun FeatureItem(
    emoji: String,
    title: String,
    desc: String,
) {
    GlassCard(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        cornerRadius = 16,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BlueLightBg),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 18.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextDark,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    desc,
                    fontSize = 12.sp,
                    color = TextGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
