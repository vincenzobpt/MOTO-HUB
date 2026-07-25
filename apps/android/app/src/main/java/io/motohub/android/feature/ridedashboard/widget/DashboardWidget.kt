package io.motohub.android.feature.ridedashboard.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.content.res.Resources
import io.motohub.android.i18n.MotoHubStrings
import io.motohub.android.feature.ridedashboard.RideTelemetrySnapshot
import io.motohub.android.feature.settings.DistanceUnits

/**
 * Shared drawing resources passed to every widget so they don't
 * create redundant Paint/Typeface objects each frame.
 */
class WidgetDrawingContext(
    val fillPaint: Paint,
    val strokePaint: Paint,
    val bitmapPaint: Paint,
    val textPaint: Paint,
    val boldTypeface: Typeface,
    val monoTypeface: Typeface,
    val monoBoldTypeface: Typeface,
    /** Dashboard colour palette — widget colour references match these. */
    val colors: WidgetColors,
    /** Resources from the renderer's application context for localized labels. */
    val resources: Resources,
    val packageName: String,
    /** True only while the renderer is showing its synthetic startup gauge sweep. */
    @Volatile var startupSweepActive: Boolean = false,
    /** The rider's unit preference; refreshed by the renderer alongside device status. */
    @Volatile var units: DistanceUnits = DistanceUnits.KILOMETERS
)

/** Resolves a migrated dashboard label without allocating a Paint or lookup map per frame. */
fun WidgetDrawingContext.localized(source: String): String {
    val id = resources.getIdentifier(MotoHubStrings.keyFor(source), "string", packageName)
    return if (id == 0) source else resources.getString(id)
}

fun WidgetDrawingContext.localized(source: String, vararg arguments: Any?): String {
    val value = localized(source)
    return if (arguments.isEmpty()) value else {
        String.format(java.util.Locale.getDefault(), value, *arguments)
    }
}

data class WidgetColors(
    val primary: Int,
    val primaryText: Int,
    val text: Int,
    val muted: Int,
    val border: Int,
    val panel: Int,
    val background: Int,
    val success: Int,
    val warning: Int,
    val route: Int
)

/**
 * A single "widget" that draws inside a panel region of the Ride Dashboard.
 *
 * Each widget draws inside [bounds] (already translated & clipped by the
 * renderer). [isLeftPanel] tells the widget whether it sits on the left or
 * right side of the dashboard, so border-drawing conventions stay consistent.
 */
interface DashboardWidget {
    val id: String
    val title: String
    val description: String

    fun draw(
        canvas: Canvas,
        bounds: RectF,
        snapshot: RideTelemetrySnapshot,
        freshFix: Boolean,
        isLeftPanel: Boolean,
        ctx: WidgetDrawingContext
    )
}

/**
 * Shared panel-divider border every widget draws first: the edge facing the
 * other panel (right edge when this widget is on the left, left edge when on
 * the right), one pixel wide in [WidgetColors.border].
 */
fun WidgetDrawingContext.drawPanelBorder(canvas: Canvas, bounds: RectF, isLeftPanel: Boolean) {
    strokePaint.color = colors.border
    strokePaint.strokeWidth = 1f
    if (isLeftPanel) {
        canvas.drawLine(bounds.right, bounds.top, bounds.right, bounds.bottom, strokePaint)
    } else {
        canvas.drawLine(bounds.left, bounds.top, bounds.left, bounds.bottom, strokePaint)
    }
}

/** Draws a single line of text, mutating and reusing the shared [textPaint]. */
fun WidgetDrawingContext.drawText(
    canvas: Canvas,
    value: String,
    x: Float,
    y: Float,
    size: Float,
    color: Int,
    typeface: Typeface,
    align: Paint.Align = Paint.Align.LEFT
) {
    textPaint.textSize = size
    textPaint.color = color
    textPaint.textAlign = align
    textPaint.typeface = typeface
    canvas.drawText(value, x, y, textPaint)
}
