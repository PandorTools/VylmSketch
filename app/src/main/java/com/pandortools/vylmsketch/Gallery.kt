package com.pandortools.vylmsketch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

data class SketchProject(val id: String, val name: String, val width: Int, val height: Int, val directory: File) {
    val image get() = File(directory, "canvas.png")
    val modified get() = image.lastModified()
}

class ProjectStore(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    fun list(): List<SketchProject> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { directory ->
        runCatching {
            val json = JSONObject(File(directory, "project.json").readText())
            SketchProject(directory.name, json.getString("name"), json.getInt("width"), json.getInt("height"), directory)
                .also { require(it.image.exists()); validateSize(it.width, it.height) }
        }.getOrNull()
    }.sortedByDescending { it.modified }

    fun create(name: String, width: Int, height: Int): SketchProject {
        validateSize(width, height)
        require(name.trim().isNotEmpty() && name.length <= 80)
        val id = UUID.randomUUID().toString()
        val project = SketchProject(id, name.trim(), width, height, File(root, id))
        check(project.directory.mkdirs())
        val blank = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            blank.eraseColor(android.graphics.Color.WHITE)
            project.image.outputStream().use { check(blank.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            writeMetadata(project)
        } finally { blank.recycle() }
        return project
    }

    private fun writeMetadata(project: SketchProject) {
        File(project.directory, "project.json").writeText(JSONObject().put("name", project.name).put("width", project.width).put("height", project.height).put("version", 1).toString())
    }

    fun migrateDraft() {
        val old = File(context.filesDir, "draft.png")
        val folder = File(root, "legacy-draft")
        if (!old.exists() || File(folder, "project.json").exists()) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(old.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        folder.mkdirs()
        old.copyTo(File(folder, "canvas.png"), overwrite = true)
        writeMetadata(SketchProject(folder.name, "Мой первый рисунок", bounds.outWidth, bounds.outHeight, folder))
        // Keep the original draft as a migration backup.
    }

    companion object {
        fun validateSize(width: Int, height: Int) {
            require(width in 256..3072 && height in 256..3072 && width.toLong() * height <= 6_000_000) { "Стороны от 256 до 3072 px, не более 6 млн пикселей" }
        }
    }
}

@Composable
fun Gallery(store: ProjectStore, revision: Int, onOpen: (SketchProject) -> Unit) {
    var projects by remember { mutableStateOf<List<SketchProject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        runCatching { withContext(Dispatchers.IO) { store.list() } }
            .onSuccess { projects = it }.onFailure { error = "Не удалось открыть галерею" }
        loading = false
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.systemBarsPadding().padding(28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("VylmSketch", style = MaterialTheme.typography.headlineLarge)
                    Text("Место для твоих идей", color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = { creating = true }) { Text("+ Создать холст") }
            }
            Spacer(Modifier.height(32.dp))
            Text("Мои работы", style = MaterialTheme.typography.headlineSmall)
            Text("Хранятся на этом планшете · открывай и продолжай рисовать", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB6B5C5))
            Spacer(Modifier.height(20.dp))
            if (loading) CircularProgressIndicator()
            else if (projects.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Начнём с чистого листа", style = MaterialTheme.typography.headlineSmall)
                        Text("Создай холст — твои рисунки появятся здесь.")
                        FilledTonalButton(onClick = { creating = true }) { Text("Создать первую работу") }
                    }
                }
            } else LazyVerticalGrid(columns = GridCells.Adaptive(240.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                items(projects, key = { it.id }) { project ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(project) }) {
                        ProjectPreview(project)
                        Column(Modifier.padding(16.dp)) {
                            Text(project.name, maxLines = 2, style = MaterialTheme.typography.titleMedium)
                            Text("${project.width} × ${project.height} px", style = MaterialTheme.typography.bodySmall)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(project.modified)), style = MaterialTheme.typography.labelSmall, color = Color(0xFFB6B5C5))
                        }
                    }
                }
            }
        }
    }
    if (creating) CreateCanvasDialog(busy, error, onDismiss = { if (!busy) { creating = false; error = null } }, onCreate = { name, width, height ->
        busy = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.create(name, width, height) } }
                .onSuccess { creating = false; onOpen(it) }
                .onFailure { error = it.message ?: "Не удалось создать холст" }
            busy = false
        }
    })
}

@Composable
private fun ProjectPreview(project: SketchProject) {
    val preview by produceState<Bitmap?>(null, project.id, project.modified) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(project.image.path, BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(project.width, project.height) / 500) })
        }
    }
    Box(Modifier.fillMaxWidth().height(190.dp).background(Color(0xFF30313D)), contentAlignment = Alignment.Center) {
        preview?.let { Image(it.asImageBitmap(), "Превью: ${project.name}", Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun CreateCanvasDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onCreate: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("Новая работа") }
    var width by remember { mutableStateOf("1600") }
    var height by remember { mutableStateOf("1200") }
    val w = width.toIntOrNull()
    val h = height.toIntOrNull()
    val valid = name.isNotBlank() && w != null && h != null && runCatching { ProjectStore.validateSize(w, h) }.isSuccess
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Новый холст") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Название") }, singleLine = true, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(width, { width = it.filter(Char::isDigit).take(4) }, label = { Text("Ширина, px") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                OutlinedTextField(height, { height = it.filter(Char::isDigit).take(4) }, label = { Text("Высота, px") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { width = "1600"; height = "1200" }, enabled = !busy) { Text("Альбом") }
                TextButton(onClick = { width = "1200"; height = "1600" }, enabled = !busy) { Text("Портрет") }
                TextButton(onClick = { width = "1600"; height = "1600" }, enabled = !busy) { Text("Квадрат") }
            }
            Text("Белый фон · 256–3072 px на сторону · до 6 млн пикселей", style = MaterialTheme.typography.bodySmall)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = { onCreate(name.trim(), w!!, h!!) }, enabled = valid && !busy) { Text(if (busy) "Создаём…" else "Создать") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } })
}
