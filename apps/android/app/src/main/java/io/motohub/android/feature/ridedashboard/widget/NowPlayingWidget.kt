package io.motohub.android.feature.ridedashboard.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import java.util.Locale
import kotlin.math.min

/**
 * Now Playing widget with album artwork as its primary visual element.
 *
 * The cover is center-cropped into the largest square that fits above the
 * track metadata, while the progress timeline remains readable below it.
 */
class NowPlayingWidget : DashboardWidget {

    override val id: String = DashboardWidgetIDs.NOW_PLAYING
    override val title: String = "Now Playing"
    override val description: String = "Album cover, current track and progress"

    override fun draw(
        canvas: Canvas,
        bounds: RectF,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        isLeftPanel: Boolean,
        ctx: WidgetDrawingContext
    ) {
        val left = bounds.left
        val right = bounds.right
        val top = bounds.top
        val bottom = bounds.bottom
        val centerX = (left + right) / 2f
        val panelWidth = bounds.width()

        ctx.drawPanelBorder(canvas, bounds, isLeftPanel)
        ctx.drawText(canvas, ctx.localized("NOW PLAYING // MEDIA SESSION"), left + 18f, top + 25f, 10f,
            ctx.colors.muted, ctx.monoTypeface)

        // Reserve the maximum practical square above the metadata and timeline.
        val artworkSize = min(panelWidth - 30f, bounds.height() * 0.50f).coerceAtLeast(100f)
        val artwork = RectF(
            centerX - artworkSize / 2f,
            top + 38f,
            centerX + artworkSize / 2f,
            top + 38f + artworkSize
        )
        drawArtwork(canvas, artwork, snapshot.mediaArtwork, ctx)

        val hasMedia = snapshot.mediaTitle.isNotEmpty()
        if (!hasMedia) {
            ctx.drawText(canvas, ctx.localized("-- NO MEDIA SESSION --"), centerX, artwork.bottom + 22f, 11f,
                ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
            return
        }

        val titleY = artwork.bottom + 22f
        val maxTextWidth = panelWidth - 28f
        ctx.drawText(
            canvas,
            truncateToWidth(snapshot.mediaTitle, maxTextWidth, ctx.textPaint, 17f, ctx.boldTypeface),
            centerX,
            titleY,
            17f,
            ctx.colors.text,
            ctx.boldTypeface,
            Paint.Align.CENTER
        )
        if (snapshot.mediaArtist.isNotEmpty()) {
            ctx.drawText(
                canvas,
                truncateToWidth(snapshot.mediaArtist, maxTextWidth, ctx.textPaint, 12f, ctx.monoTypeface),
                centerX,
                titleY + 18f,
                12f,
                ctx.colors.muted,
                ctx.monoTypeface,
                Paint.Align.CENTER
            )
        }

        val barLeft = left + 14f
        val barRight = right - 14f
        val barTop = bottom - 32f
        val barBottom = barTop + 5f
        val duration = snapshot.mediaDurationMs
        val position = snapshot.mediaPositionMs.coerceAtLeast(0L)
        val fillRatio = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

        ctx.fillPaint.style = Paint.Style.FILL
        ctx.fillPaint.color = ctx.colors.panel
        canvas.drawRoundRect(barLeft, barTop, barRight, barBottom, 3f, 3f, ctx.fillPaint)
        if (fillRatio > 0f) {
            ctx.fillPaint.color = if (snapshot.mediaIsPlaying) ctx.colors.success else ctx.colors.route
            canvas.drawRoundRect(barLeft, barTop, barLeft + (barRight - barLeft) * fillRatio, barBottom, 3f, 3f, ctx.fillPaint)
        }
        ctx.drawText(canvas, formatDuration(position), barLeft, bottom - 13f, 10f,
            ctx.colors.muted, ctx.monoTypeface)
        ctx.drawText(canvas, formatDuration(duration), barRight, bottom - 13f, 10f,
            ctx.colors.muted, ctx.monoTypeface, Paint.Align.RIGHT)
    }

    private fun drawArtwork(
        canvas: Canvas,
        destination: RectF,
        artwork: Bitmap?,
        ctx: WidgetDrawingContext
    ) {
        ctx.fillPaint.style = Paint.Style.FILL
        ctx.fillPaint.color = COLOR_ARTWORK_PLACEHOLDER
        canvas.drawRoundRect(destination, 14f, 14f, ctx.fillPaint)

        if (artwork != null && !artwork.isRecycled && artwork.width > 0 && artwork.height > 0) {
            val source = centerCropSource(artwork, destination)
            val saveCount = canvas.save()
            canvas.clipRect(destination)
            ctx.bitmapPaint.alpha = 255
            ctx.bitmapPaint.isFilterBitmap = true
            canvas.drawBitmap(artwork, source, destination, ctx.bitmapPaint)
            canvas.restoreToCount(saveCount)
        } else {
            ctx.drawText(canvas, "♪", destination.centerX(), destination.centerY() + 12f, 48f,
                ctx.colors.muted, ctx.boldTypeface, Paint.Align.CENTER)
            ctx.drawText(canvas, ctx.localized("NO ARTWORK"), destination.centerX(), destination.bottom - 14f, 9f,
                ctx.colors.muted, ctx.monoTypeface, Paint.Align.CENTER)
        }

        ctx.strokePaint.style = Paint.Style.STROKE
        ctx.strokePaint.strokeWidth = 1.5f
        ctx.strokePaint.color = ctx.colors.primary.copyAlpha(0x70)
        canvas.drawRoundRect(destination, 14f, 14f, ctx.strokePaint)
    }

    private fun centerCropSource(bitmap: Bitmap, destination: RectF): Rect {
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val destinationRatio = destination.width() / destination.height()
        return if (sourceRatio > destinationRatio) {
            val width = (bitmap.height * destinationRatio).toInt().coerceAtLeast(1)
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / destinationRatio).toInt().coerceAtLeast(1)
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun truncateToWidth(
        text: String,
        maxWidth: Float,
        paint: Paint,
        size: Float,
        typeface: android.graphics.Typeface
    ): String {
        paint.textSize = size
        paint.typeface = typeface
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }

    private fun Int.copyAlpha(alpha: Int): Int =
        (this and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    companion object {
        private val COLOR_ARTWORK_PLACEHOLDER = 0xFF172A35.toInt()
    }
}
