package com.example.smartcleaner

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

enum class PhotoCategory(val title: String) {
    DUPLICATE("Duplicates"),
    BAD_QUALITY("Blurry / dark"),
    PAYMENT("Payments"),
    FOOD("Food"),
    DOCUMENT("Documents")
}

enum class GalleryTab(val title: String) {
    ALL("All Photos"), DUPLICATES("Duplicates"), CLEANUP("Cleanup")
}

data class ScannedPhoto(
    val uri: Uri,
    val categories: Set<PhotoCategory>,
    val detail: String,
    val duplicateGroup: String? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { CleanerScreen() }
            }
        }
    }
}

@Composable
fun CleanerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(hasImagePermission(context)) }
    var allPhotos by remember { mutableStateOf(emptyList<Uri>()) }
    var scannedPhotos by remember { mutableStateOf(emptyList<ScannedPhoto>()) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var tab by remember { mutableStateOf(GalleryTab.ALL) }
    var selectedCategory by remember { mutableStateOf<PhotoCategory?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("Allow access to browse your gallery.") }
    var message by remember { mutableStateOf("") }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun refreshGallery() {
        scope.launch {
            isLoading = true
            try {
                allPhotos = loadAllGalleryPhotos(context)
                progress = "Gallery loaded: ${allPhotos.size} photos."
            } catch (e: Exception) {
                message = e.localizedMessage ?: "Could not load gallery."
            } finally {
                isLoading = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = hasImagePermission(context)
        if (hasPermission) refreshGallery()
        else message = "Photo access was not granted. Please allow access and try again."
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            allPhotos = allPhotos.filterNot { it in selectedUris }
            scannedPhotos = scannedPhotos.filterNot { it.uri in selectedUris }
            selectedUris = emptySet()
            message = "Selected photos were deleted."
        } else {
            message = "Deletion cancelled. No photos were removed."
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && allPhotos.isEmpty()) refreshGallery()
    }

    val visibleUris = when (tab) {
        GalleryTab.ALL -> allPhotos
        GalleryTab.DUPLICATES -> scannedPhotos.filter { it.duplicateGroup != null }.map { it.uri }
        GalleryTab.CLEANUP -> scannedPhotos.filter {
            it.categories.any { category -> category != PhotoCategory.DUPLICATE }
        }.let { list ->
            if (selectedCategory == null) list.map { it.uri }
            else list.filter { selectedCategory in it.categories }.map { it.uri }
        }
    }.distinct()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("SmartCleaner", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Text("Browse every accessible photo and review scan results as they arrive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(progress)
                if (isLoading || isScanning) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !isLoading && !isScanning, onClick = {
                        if (!hasPermission) {
                            val permissions = when {
                                Build.VERSION.SDK_INT >= 34 -> arrayOf(
                                    Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                                )
                                Build.VERSION.SDK_INT >= 33 ->
                                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                            permissionLauncher.launch(permissions)
                        } else refreshGallery()
                    }) { Text(if (hasPermission) "Refresh gallery" else "Allow access") }

                    OutlinedButton(enabled = hasPermission && !isLoading && !isScanning,
                        onClick = {
                            scannedPhotos = emptyList()
                            isScanning = true
                            message = "Analysis is running. New results will appear as they are found."
                            scanJob = scope.launch {
                                try {
                                    scanGallery(context,
                                        onProgress = { progress = it },
                                        onPhotoUpdate = { updated -> scannedPhotos = updated }
                                    )
                                    progress = "Analysis complete: ${scannedPhotos.size} photos analyzed."
                                    message = "All accessible photos have been analyzed."
                                } catch (e: Exception) {
                                    message = e.localizedMessage ?: "Analysis failed."
                                } finally {
                                    isScanning = false
                                    scanJob = null
                                }
                            }
                        }) { Text("Analyze all photos") }
                }
                if (isScanning) {
                    TextButton(onClick = { scanJob?.cancel() }) { Text("Stop analysis") }
                }
            }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(8.dp))
        Text("${allPhotos.size} gallery photos  •  ${selectedUris.size} selected",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        TabRow(selectedTabIndex = tab.ordinal) {
            GalleryTab.values().forEach { item ->
                Tab(selected = tab == item, onClick = {
                    tab = item
                    selectedCategory = null
                }, text = { Text(item.title) })
            }
        }

        if (tab == GalleryTab.CLEANUP) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = selectedCategory == null,
                    onClick = { selectedCategory = null }, label = { Text("All") })
                PhotoCategory.values().filter { it != PhotoCategory.DUPLICATE }.forEach { category ->
                    FilterChip(selected = selectedCategory == category,
                        onClick = { selectedCategory =
                            if (selectedCategory == category) null else category },
                        label = { Text(category.title) })
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = visibleUris.isNotEmpty(),
                onClick = { selectedUris = visibleUris.toSet() }) { Text("Select shown") }
            TextButton(enabled = selectedUris.isNotEmpty(),
                onClick = { selectedUris = emptySet() }) { Text("Clear selection") }
            Spacer(Modifier.weight(1f))
            Button(enabled = selectedUris.isNotEmpty() && !isScanning,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val request = MediaStore.createDeleteRequest(
                                context.contentResolver, selectedUris.toList())
                            deleteLauncher.launch(
                                IntentSenderRequest.Builder(request.intentSender).build())
                        } catch (e: Exception) {
                            message = "Could not request deletion: ${e.localizedMessage}"
                        }
                    } else message = "Please delete selected photos in your Gallery app."
                }) { Text("Delete") }
        }

        if (visibleUris.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(when {
                    !hasPermission -> "Tap Allow access to view your gallery."
                    isLoading -> "Loading photos…"
                    tab == GalleryTab.DUPLICATES && isScanning ->
                        "Looking for duplicates. Groups will appear as they are found."
                    tab == GalleryTab.DUPLICATES ->
                        "No duplicate groups yet. Tap Analyze all photos."
                    tab == GalleryTab.CLEANUP && isScanning ->
                        "Analyzing photos. Results will appear here as they are found."
                    tab == GalleryTab.CLEANUP ->
                        "No cleanup suggestions yet. Tap Analyze all photos."
                    else -> "No photos found in the accessible gallery."
                })
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(visibleUris, key = { it.toString() }) { uri ->
                    val selected = uri in selectedUris
                    val result = scannedPhotos.firstOrNull { it.uri == uri }
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = "Gallery photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { previewUri = uri }
                        )
                        Checkbox(checked = selected, onCheckedChange = {
                            selectedUris = if (selected) selectedUris - uri else selectedUris + uri
                        }, modifier = Modifier.align(Alignment.TopEnd))
                        if (result?.duplicateGroup != null) {
                            Surface(Modifier.align(Alignment.BottomStart).padding(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(5.dp)) {
                                Text("Duplicate", modifier = Modifier.padding(5.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    val currentPreviewIndex = previewUri?.let { visibleUris.indexOf(it) } ?: -1
    if (previewUri != null && currentPreviewIndex >= 0) {
        FullScreenPhotoViewer(
            photos = visibleUris,
            startIndex = currentPreviewIndex,
            onClose = { previewUri = null }
        )
    }
}

@Composable
private fun FullScreenPhotoViewer(
    photos: List<Uri>,
    startIndex: Int,
    onClose: () -> Unit
) {
    var index by remember(photos, startIndex) { mutableIntStateOf(startIndex) }
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var dragAmount by remember { mutableFloatStateOf(0f) }
        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
            Box(Modifier.fillMaxSize().pointerInput(index, photos.size) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, amount -> dragAmount += amount },
                    onDragEnd = {
                        if (dragAmount < -60 && index < photos.lastIndex) index++
                        else if (dragAmount > 60 && index > 0) index--
                        dragAmount = 0f
                    },
                    onDragCancel = { dragAmount = 0f }
                )
            }) {
                AsyncImage(
                    model = photos[index],
                    contentDescription = "Full-screen photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                TextButton(onClick = onClose,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    Text("Close", color = androidx.compose.ui.graphics.Color.White)
                }
                Text("${index + 1} / ${photos.size}",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                    color = androidx.compose.ui.graphics.Color.White)
                Text("Swipe left or right",
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                    color = androidx.compose.ui.graphics.Color.LightGray,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun hasImagePermission(context: Context): Boolean {
    val fullPermission = ContextCompat.checkSelfPermission(context,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val selectedPermission = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    return fullPermission || selectedPermission
}

private suspend fun loadAllGalleryPhotos(context: Context): List<Uri> =
    withContext(Dispatchers.IO) {
        val photos = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, order)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                photos.add(ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)))
            }
        }
        photos
    }

suspend fun scanGallery(
    context: Context,
    onProgress: (String) -> Unit,
    onPhotoUpdate: (List<ScannedPhoto>) -> Unit
): List<ScannedPhoto> = withContext(Dispatchers.IO) {
    data class HashEntry(val hash: Long, val groupId: String)
    val results = mutableListOf<ScannedPhoto>()
    val hashes = mutableListOf<HashEntry>()
    val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val paymentRegex = Regex(
        """\b(upi|utr|paid\s+to|transaction|debited|credited|gpay|phonepe|paytm|rs\.?|inr)\b|₹""",
        RegexOption.IGNORE_CASE)
    val foodLabels = setOf("food", "dish", "cuisine", "meal", "snack")
    val documentLabels = setOf("document", "paper", "receipt")

    try {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, order)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val total = cursor.count
            var index = 0
            while (cursor.moveToNext()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                index++
                withContext(Dispatchers.Main) { onProgress("Analyzing photo $index of $total…") }
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val bitmap = try {
                    if (Build.VERSION.SDK_INT >= 29)
                        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                    else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver,
                            id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }
                } catch (_: Exception) { null } ?: continue

                try {
                    val categories = mutableSetOf<PhotoCategory>()
                    val reasons = mutableListOf<String>()
                    val hash = computeDHash(bitmap)
                    val match = hashes.firstOrNull {
                        java.lang.Long.bitCount(it.hash xor hash) <= 5
                    }
                    val groupId = match?.groupId ?: uri.toString()
                    if (match != null) {
                        categories.add(PhotoCategory.DUPLICATE)
                        reasons.add("Similar to another photo")
                        val firstIndex = results.indexOfFirst { it.uri.toString() == groupId }
                        if (firstIndex >= 0) {
                            val first = results[firstIndex]
                            results[firstIndex] = first.copy(
                                categories = first.categories + PhotoCategory.DUPLICATE,
                                detail = (first.detail + ", Duplicate group").trim(',', ' '),
                                duplicateGroup = groupId
                            )
                        }
                    } else {
                        hashes.add(HashEntry(hash, groupId))
                    }

                    val (sharpness, brightness) = evaluateQuality(bitmap)
                    if (sharpness < 80.0 || brightness < 35.0) {
                        categories.add(PhotoCategory.BAD_QUALITY)
                        if (brightness < 35.0) reasons.add("Dark")
                        if (sharpness < 80.0) reasons.add("Possibly blurry")
                    }
                    val input = InputImage.fromBitmap(bitmap, 0)
                    val labels = runCatching { labeler.process(input).await() }.getOrDefault(emptyList())
                    if (labels.any { it.text.lowercase(Locale.ROOT) in foodLabels &&
                                it.confidence > 0.72f }) {
                        categories.add(PhotoCategory.FOOD); reasons.add("Food")
                    }
                    val text = runCatching { recognizer.process(input).await().text }.getOrDefault("")
                    if (paymentRegex.containsMatchIn(text)) {
                        categories.add(PhotoCategory.PAYMENT)
                        reasons.add("Possible payment screenshot")
                    } else if (text.length > 100 ||
                        labels.any { it.text.lowercase(Locale.ROOT) in documentLabels }) {
                        categories.add(PhotoCategory.DOCUMENT)
                        reasons.add("Possible document or receipt")
                    }

                    results.add(ScannedPhoto(uri, categories, reasons.joinToString(", "),
                        if (match != null) groupId else null))
                    // Publish every few photos, and immediately when a duplicate is found.
                    if (index % 5 == 0 || match != null) {
                        val snapshot = results.toList()
                        withContext(Dispatchers.Main) { onPhotoUpdate(snapshot) }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val finalSnapshot = results.toList()
        withContext(Dispatchers.Main) { onPhotoUpdate(finalSnapshot) }
    } finally {
        labeler.close()
        recognizer.close()
    }
    results
}

fun computeDHash(source: Bitmap): Long {
    val resized = Bitmap.createScaledBitmap(source, 9, 8, true)
    var hash = 0L
    var bit = 0
    for (y in 0 until 8) for (x in 0 until 8) {
        if (luminance(resized.getPixel(x, y)) > luminance(resized.getPixel(x + 1, y)))
            hash = hash or (1L shl bit)
        bit++
    }
    if (resized !== source) resized.recycle()
    return hash
}

fun evaluateQuality(source: Bitmap): Pair<Double, Double> {
    val bmp = Bitmap.createScaledBitmap(source, 64, 64, true)
    var sumLum = 0.0
    var sumLap = 0.0
    var sumLapSq = 0.0
    val count = 62 * 62
    for (y in 1 until 63) for (x in 1 until 63) {
        val center = luminance(bmp.getPixel(x, y))
        sumLum += center
        val lap = -4 * center + luminance(bmp.getPixel(x - 1, y)) +
            luminance(bmp.getPixel(x + 1, y)) + luminance(bmp.getPixel(x, y - 1)) +
            luminance(bmp.getPixel(x, y + 1))
        sumLap += lap
        sumLapSq += lap * lap
    }
    val meanLap = sumLap / count
    val variance = sumLapSq / count - meanLap * meanLap
    val brightness = sumLum / count
    if (bmp !== source) bmp.recycle()
    return Pair(variance, brightness)
}

fun luminance(pixel: Int): Int =
    (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) +
        0.114 * Color.blue(pixel)).toInt()
