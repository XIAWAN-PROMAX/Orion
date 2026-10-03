package com.orion.assistant.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 少量自绘图标。material-icons-core 只有基础图标（没有暂停 / 停止 / 无障碍 / 投屏 / 闪光），
 * 为了不引入体积巨大的 material-icons-extended，这里用矢量路径手写这几个。
 */
object OrionIcons {

    /** 暂停：两根圆角竖条 */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "OrionPause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.5f, 5f)
                lineTo(9.75f, 5f)
                lineTo(9.75f, 19f)
                lineTo(6.5f, 19f)
                close()
                moveTo(14.25f, 5f)
                lineTo(17.5f, 5f)
                lineTo(17.5f, 19f)
                lineTo(14.25f, 19f)
                close()
            }
        }.build()
    }

    /** 停止：圆角方块 */
    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "OrionStop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 7f)
                lineTo(17f, 7f)
                lineTo(17f, 17f)
                lineTo(7f, 17f)
                close()
            }
        }.build()
    }

    /** 闪光：品牌感的小星星 */
    val Sparkles: ImageVector by lazy {
        ImageVector.Builder(
            name = "OrionSparkles",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // 主星（四角星）
                moveTo(10f, 2.4f)
                lineTo(11.7f, 8.3f)
                lineTo(17.6f, 10f)
                lineTo(11.7f, 11.7f)
                lineTo(10f, 17.6f)
                lineTo(8.3f, 11.7f)
                lineTo(2.4f, 10f)
                lineTo(8.3f, 8.3f)
                close()
                // 副星
                moveTo(18.2f, 13.6f)
                lineTo(19.1f, 16.5f)
                lineTo(22f, 17.4f)
                lineTo(19.1f, 18.3f)
                lineTo(18.2f, 21.2f)
                lineTo(17.3f, 18.3f)
                lineTo(14.4f, 17.4f)
                lineTo(17.3f, 16.5f)
                close()
            }
        }.build()
    }

    /** 无障碍：一个小人张开双臂 */
    val Accessibility: ImageVector by lazy {
        ImageVector.Builder(
            name = "OrionAccessibility",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // 头
                moveTo(12f, 2.2f)
                arcToRelative(2.3f, 2.3f, 0f, true, true, 0f, 4.6f)
                arcToRelative(2.3f, 2.3f, 0f, true, true, 0f, -4.6f)
                close()
                // 双臂
                moveTo(4.2f, 9.2f)
                lineTo(19.8f, 9.2f)
                lineTo(19.8f, 11.4f)
                lineTo(4.2f, 11.4f)
                close()
                // 躯干 + 双腿
                moveTo(9.3f, 8f)
                lineTo(14.7f, 8f)
                lineTo(14.7f, 15.2f)
                lineTo(13.3f, 15.2f)
                lineTo(13.3f, 21.8f)
                lineTo(10.9f, 21.8f)
                lineTo(10.9f, 15.2f)
                lineTo(9.3f, 15.2f)
                close()
            }
        }.build()
    }

    /** 投屏 / 截屏：一块屏幕加底座 */
    val ScreenCast: ImageVector by lazy {
        ImageVector.Builder(
            name = "OrionScreenCast",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd
            ) {
                // 屏幕外框
                moveTo(2.6f, 4f)
                lineTo(21.4f, 4f)
                lineTo(21.4f, 17.2f)
                lineTo(2.6f, 17.2f)
                close()
                // 内屏（EvenOdd 挖空）
                moveTo(4.8f, 6.2f)
                lineTo(4.8f, 15f)
                lineTo(19.2f, 15f)
                lineTo(19.2f, 6.2f)
                close()
                // 底座
                moveTo(9f, 18.6f)
                lineTo(15f, 18.6f)
                lineTo(15f, 20.6f)
                lineTo(9f, 20.6f)
                close()
            }
        }.build()
    }

    /** 历史：一个表盘 + 指针 */
    val History: ImageVector by lazy {
        ImageVector.Builder(
            name = "OrionHistory",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // 圆环（两段弧拼成整圆）
                moveTo(3.4f, 12f)
                arcToRelative(8.6f, 8.6f, 0f, false, true, 17.2f, 0f)
                arcToRelative(8.6f, 8.6f, 0f, false, true, -17.2f, 0f)
                close()
                // 指针
                moveTo(12f, 7.2f)
                lineTo(12f, 12.4f)
                lineTo(16f, 14.6f)
            }
        }.build()
    }
}