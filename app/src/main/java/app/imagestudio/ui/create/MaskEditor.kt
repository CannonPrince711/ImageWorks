package app.imagestudio.ui.create

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale

/**
 * Shows [source] and lets the user paint the inpaint mask with a finger.
 * Painted areas show as a translucent red overlay. Strokes are stored normalised (0..1).
 */
@Composable
fun MaskEditor(
    source: Bitmap,
    strokes: List<Stroke>,
    brush: Float,
    erasing: Boolean,
    onStroke: (Stroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = remember { mutableStateListOf<Offset>() }
    val image = remember(source) { source.asImageBitmap() }

    Box(modifier.fillMaxWidth().aspectRatio(source.width.toFloat() / source.height)) {
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(brush, erasing) {
                    fun norm(o: Offset) = Offset(o.x / size.width, o.y / size.height)
                    detectDragGestures(
                        onDragStart = { live.clear(); live.add(norm(it)) },
                        onDrag = { change, _ -> live.add(norm(change.position)) },
                        onDragEnd = { onStroke(Stroke(live.toList(), brush, erasing)); live.clear() },
                        onDragCancel = { live.clear() },
                    )
                }
                .pointerInput(brush, erasing) {
                    detectTapGestures { o -> onStroke(Stroke(listOf(Offset(o.x / size.width, o.y / size.height)), brush, erasing)) }
                },
        ) {
            val longSide = maxOf(size.width, size.height)
            // Draw into an offscreen layer so erase strokes cut holes in the overlay.
            drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), androidx.compose.ui.graphics.Paint())
            (strokes + listOfNotNull(live.takeIf { it.isNotEmpty() }?.let { Stroke(it.toList(), brush, erasing) })).forEach { s ->
                val path = Path()
                s.points.forEachIndexed { i, p ->
                    val x = p.x * size.width; val y = p.y * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (s.points.size == 1) path.lineTo(s.points[0].x * size.width + 0.1f, s.points[0].y * size.height)
                drawPath(
                    path,
                    color = if (s.erase) Color.Transparent else Color(0x99FF3B5C),
                    style = DrawStroke(width = s.width * longSide, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    blendMode = if (s.erase) androidx.compose.ui.graphics.BlendMode.Clear else androidx.compose.ui.graphics.BlendMode.Src,
                )
            }
            drawContext.canvas.restore()
        }
    }
}
