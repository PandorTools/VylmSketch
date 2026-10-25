package com.pandortools.vylmsketch

import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var drawing: DrawingView
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri).use { output ->
                checkNotNull(output)
                check(drawing.snapshot().compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        }.onSuccess { toast("PNG сохранён") }.onFailure { toast("Не удалось сохранить PNG") }
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawing = DrawingView(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB6A1FF), surface = Color(0xFF20212B), background = Color(0xFF171820))) {
                var size by remember { mutableFloatStateOf(12f) }
                var eraser by remember { mutableStateOf(false) }
                var selected by remember { mutableIntStateOf(android.graphics.Color.rgb(40, 38, 60)) }
                var clearDialog by remember { mutableStateOf(false) }
                var revision by remember { mutableIntStateOf(0) }
                drawing.onChange = { revision++ }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("VylmSketch", style = MaterialTheme.typography.headlineSmall)
                                Text("СТУДИЯ РИСОВАНИЯ  /  01", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB6A1FF))
                            }
                            TextButton(onClick = { drawing.undo() }, enabled = revision >= 0 && drawing.canUndo()) { Text("↶ Отмена") }
                            TextButton(onClick = { drawing.redo() }, enabled = revision >= 0 && drawing.canRedo()) { Text("↷ Повтор") }
                            TextButton(onClick = { clearDialog = true }) { Text("Очистить") }
                            Button(onClick = { export.launch("VylmSketch-${System.currentTimeMillis()}.png") }) { Text("Экспорт PNG") }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.width(150.dp).fillMaxHeight()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("ИНСТРУМЕНТЫ", style = MaterialTheme.typography.labelSmall)
                                    FilterChip(selected = !eraser, onClick = { eraser = false; drawing.eraser = false }, label = { Text("✎ Кисть") })
                                    FilterChip(selected = eraser, onClick = { eraser = true; drawing.eraser = true }, label = { Text("▱ Ластик") })
                                    HorizontalDivider()
                                    Text("Размер · ${size.toInt()} px", style = MaterialTheme.typography.labelMedium)
                                    Slider(value = size, onValueChange = { size = it; drawing.brushSize = it }, valueRange = 1f..80f)
                                    Text("ПАЛИТРА", style = MaterialTheme.typography.labelSmall)
                                    val colors = listOf(0xFF28263C, 0xFF8464E8, 0xFFEB6F92, 0xFFFFB454, 0xFF55BFA3, 0xFF528FE3, 0xFFFFFFFF, 0xFF000000)
                                    colors.chunked(2).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            row.forEach { value ->
                                                val color = value.toInt()
                                                Box(Modifier.size(44.dp).background(if (selected == color) Color.White else Color.Transparent, CircleShape).padding(3.dp).background(Color(color), CircleShape).clickable { selected = color; drawing.brushColor = color; eraser = false; drawing.eraser = false })
                                            }
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text("Палец и стилус\nНажатие пера — при поддержке", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB6B5C5))
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF30313D)), contentAlignment = Alignment.Center) {
                                    AndroidView(factory = { drawing }, modifier = Modifier.fillMaxSize())
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Холст · 1600 × 1200", style = MaterialTheme.typography.labelSmall)
                                    Text("Автосохранение на устройстве", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB6B5C5))
                                }
                            }
                        }
                    }
                }
                if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Очистить холст?") }, text = { Text("Очистку можно отменить кнопкой «Отмена».") }, confirmButton = { TextButton(onClick = { drawing.clear(); clearDialog = false }) { Text("Очистить") } }, dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Оставить") } })
            }
        }
    }
    override fun onPause() { super.onPause(); drawing.save() }
}

class DrawingView(context: android.content.Context) : View(context) {
    var brushSize = 12f
    var brushColor = android.graphics.Color.rgb(40, 38, 60)
    var eraser = false
    var onChange: () -> Unit = {}
    private val document = File(context.filesDir, "draft.png")
    private var bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
    private var ink = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val undo = ArrayDeque<Bitmap>()
    private val redo = ArrayDeque<Bitmap>()
    private var pointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private val writer = Executors.newSingleThreadExecutor()
    private val saveTask = Runnable { save() }
    init {
        bitmap.eraseColor(android.graphics.Color.WHITE)
        if (document.exists()) BitmapFactory.decodeFile(document.path)?.let { restored ->
            ink.drawBitmap(restored, null, Rect(0, 0, 1600, 1200), null); restored.recycle()
        }
        isFocusable = true
        contentDescription = "Холст для рисования"
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scale = minOf((width - 32f) / 1600f, (height - 32f) / 1200f).coerceAtLeast(0.01f)
        offsetX = (width - 1600 * scale) / 2; offsetY = (height - 1200 * scale) / 2
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, null); canvas.restore()
    }
    private fun rememberUndo() {
        undo.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, true))
        if (undo.size > 12) undo.removeFirst().recycle()
        redo.forEach { it.recycle() }; redo.clear()
    }
    private fun replace(next: Bitmap) { bitmap = next; ink = Canvas(bitmap); invalidate(); onChange(); save() }
    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()
    fun undo() { if (undo.isNotEmpty()) { redo.addLast(bitmap); replace(undo.removeLast()) } }
    fun redo() { if (redo.isNotEmpty()) { undo.addLast(bitmap); replace(redo.removeLast()) } }
    fun clear() { rememberUndo(); bitmap.eraseColor(android.graphics.Color.WHITE); invalidate(); onChange(); save() }
    fun snapshot() = bitmap
    fun save() {
        removeCallbacks(saveTask)
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        writer.execute {
            runCatching {
                val temporary = File(context.filesDir, "draft.tmp")
                temporary.outputStream().use { check(copy.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                check(temporary.renameTo(document))
            }.onFailure { post { Toast.makeText(context, "Не удалось сохранить черновик", Toast.LENGTH_SHORT).show() } }
            copy.recycle()
        }
    }
    private fun drawPoint(x: Float, y: Float, pressure: Float, stylus: Boolean) {
        paint.color = if (eraser) android.graphics.Color.WHITE else brushColor
        paint.strokeWidth = brushSize * if (stylus) pressure.coerceIn(0.15f, 2f) else 1f
        ink.drawLine(lastX, lastY, x, y, paint)
        lastX = x; lastY = y
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = (event.x - offsetX) / scale; val y = (event.y - offsetY) / scale
                if (x !in 0f..1600f || y !in 0f..1200f) return false
                parent.requestDisallowInterceptTouchEvent(true)
                pointer = event.getPointerId(0); lastX = x; lastY = y; rememberUndo()
                drawPoint(x, y, event.pressure, event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
                invalidate(); onChange(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) return true
                val stylus = event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS
                for (h in 0 until event.historySize) drawPoint((event.getHistoricalX(index, h) - offsetX) / scale, (event.getHistoricalY(index, h) - offsetY) / scale, event.getHistoricalPressure(index, h), stylus)
                drawPoint((event.getX(index) - offsetX) / scale, (event.getY(index) - offsetY) / scale, event.getPressure(index), stylus)
                removeCallbacks(saveTask); postDelayed(saveTask, 1500)
                invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(pointer)
                if (index >= 0) drawPoint((event.getX(index) - offsetX) / scale, (event.getY(index) - offsetY) / scale, event.getPressure(index), event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS)
                pointer = -1; invalidate(); save(); performClick(); return true
            }
            MotionEvent.ACTION_CANCEL -> { pointer = -1; undo(); return true }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
