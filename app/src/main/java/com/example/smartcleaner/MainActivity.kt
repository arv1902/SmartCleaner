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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

private const val MAX_SCAN_COUNT = 300

enum class PhotoCategory(val title: String) {
    DUPLICATE("Duplicates"),
    BAD_QUALITY("Blurry / dark"),
    PAYMENT("Payments"),
    FOOD("Food"),
    DOCUMENT("Documents")
}

data class ScannedPhoto(
    val uri: Uri,
    val categories: Set<PhotoCategory>,
    val detail: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    CleanerScreen()
                }
            }
        }
    }
}

@Composable
fun CleanerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(hasImagePermission(context)) }
    var photos by remember { mutableStateOf(emptyList<ScannedPhoto>()) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var selectedCategory by remember { mutableStateOf<PhotoCategory?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("Ready to scan your gallery") }
    var message by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        message = if (granted) {
            "Photo access granted. Tap Scan gallery."
        } else {
            "Permission denied. Allow photo access in Android settings to scan."
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            photos = photos.filterNot { it.uri in selectedUris }
            selectedUris = emptySet()
            message = "Selected photos were deleted."
        } else {
            message = "Deletion was cancelled. No photos were removed."
        }
    }

    val visiblePhotos = remember(photos, selectedCategory) {
        if (selectedCategory == null) photos
        else photos.filter { selectedCategory in it.categories }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "SmartCleaner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Find photos that may be worth reviewing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(progress)
                if (isScanning) {
                    Spacer(Modifier.height(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isScanning,
                        onClick = {
                            if (!hasPermission) {
                                permissionLauncher.launch(requiredImagePermission())
                            } else {
                                isScanning = true
                                message = ""
                                selectedUris = emptySet()
                                scope.launch {
                                    try {
                                        photos = scanGallery(context) { progress = it }
                                        progress = "Scan complete: ${photos.size} items found."
                                        if (photos.isEmpty()) {
                                            message = "No matching photos were found in the first $MAX_SCAN_COUNT images."
                                        }
                                    } catch (e: Exception) {
                                        progress = "Scan stopped."
                                        message = e.localizedMessage ?: "An error occurred while scanning."
                                    } finally {
                                        isScanning = false
                                    }
                                }
                            }
                        }
                    ) {
                        Text(if (hasPermission) "Scan gallery" else "Allow photo access")
                    }
                    OutlinedButton(
                        enabled = !isScanning,
                        onClick = {
                            photos = emptyList()
                            selectedUris = emptySet()
                            selectedCategory = null
                            progress = "Ready to scan your gallery"
                            message = ""
                        }
                    ) {
                        Text("Reset")
                    }
                }
            }
        }

        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Found ${photos.size} items · ${selectedUris.size} selected",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        // Horizontal scrolling keeps all category filters accessible on narrow phones.
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All") }
                )
            }
            items(PhotoCategory.values().toList()) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = {
                        selectedCategory = if (selectedCategory == category) null else category
                    },
                    label = { Text(category.title) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(
                enabled = visiblePhotos.isNotEmpty(),
                onClick = { selectedUris = visiblePhotos.map { it.uri }.toSet() }
            ) { Text("Select shown") }

            TextButton(
                enabled = selectedUris.isNotEmpty(),
                onClick = { selectedUris = emptySet() }
            ) { Text("Clear") }

            Spacer(Modifier.weight(1f))

            Button(
                enabled = selectedUris.isNotEmpty() && !isScanning,
                onClick = {
                    val uris = selectedUris.toList()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val pendingIntent = MediaStore.createDeleteRequest(
                                context.contentResolver, uris
                            )
                            deleteLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            )
                        } catch (e: Exception) {
                            message = "Could not request deletion: ${e.localizedMessage}"
                        }
                    } else {
                        message = "On Android 10 or earlier, remove selected items from your Gallery app."
                    }
                }
            ) { Text("Delete") }
        }

        Divider()

        if (!hasPermission && photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Allow photo access to scan your device gallery.")
            }
        } else if (visiblePhotos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (photos.isEmpty()) "Tap Scan gallery to begin."
                    else "No photos in this category."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visiblePhotos, key = { it.uri.toString() }) { photo ->
                    PhotoResultCard(
                        photo = photo,
                        selected = photo.uri in selectedUris,
                        onToggle = {
                            selectedUris = if (photo.uri in selectedUris) {
                                selectedUris - photo.uri
                            } else {
                                selectedUris + photo.uri
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoResultCard(
    photo: ScannedPhoto,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(photo.uri),
                contentDescription = "Photo preview",
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            ) {
                Text(
                    photo.categories.joinToString(" · ") { it.title },
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    photo.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

private fun requiredImagePermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun hasImagePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, requiredImagePermission()
    ) == PackageManager.PERMISSION_GRANTED

suspend fun scanGallery(
    context: Context,
    onProgress: (String) -> Unit
): List<ScannedPhoto> = withContext(Dispatchers.IO) {
    val results = mutableListOf<ScannedPhoto>()
    val seenHashes = mutableListOf<Long>()
    val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val paymentRegex = Regex(
        """\b(upi|utr|paid\s+to|transaction|debited|credited|gpay|phonepe|paytm|rs\.?|inr)\b|₹""",
        RegexOption.IGNORE_CASE
    )
    val foodLabels = setOf("food", "dish", "cuisine", "meal", "snack")
    val documentLabels = setOf("document", "paper", "receipt")

    try {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val total = minOf(cursor.count, MAX_SCAN_COUNT)
            var index = 0

            while (cursor.moveToNext() && index < MAX_SCAN_COUNT) {
                index++
                onProgress("Analyzing $index / $total…")
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

                val bitmap = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver, id,
                            MediaStore.Images.Thumbnails.MINI_KIND, null
                        )
                    }
                } catch (_: Exception) {
                    null
                } ?: continue

                try {
                    val categories = mutableSetOf<PhotoCategory>()
                    val reasons = mutableListOf<String>()

                    val hash = computeDHash(bitmap)
                    if (seenHashes.any { java.lang.Long.bitCount(it xor hash) <= 5 }) {
                        categories.add(PhotoCategory.DUPLICATE)
                        reasons.add("Looks similar to another photo")
                    } else {
                        seenHashes.add(hash)
                    }

                    val (sharpness, brightness) = evaluateQuality(bitmap)
                    if (sharpness < 80.0 || brightness < 35.0) {
                        categories.add(PhotoCategory.BAD_QUALITY)
                        if (brightness < 35.0) reasons.add("Dark")
                        if (sharpness < 80.0) reasons.add("Possibly blurry")
                    }

                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val labels = runCatching {
                        labeler.process(inputImage).await()
                    }.getOrDefault(emptyList())

                    if (labels.any {
                            it.text.lowercase(Locale.ROOT) in foodLabels &&
                                it.confidence > 0.72f
                        }) {
                        categories.add(PhotoCategory.FOOD)
                        reasons.add("Food")
                    }

                    val recognizedText = runCatching {
                        recognizer.process(inputImage).await().text
                    }.getOrDefault("")

                    if (paymentRegex.containsMatchIn(recognizedText)) {
                        categories.add(PhotoCategory.PAYMENT)
                        reasons.add("Possible payment screenshot")
                    } else if (
                        recognizedText.length > 100 ||
                        labels.any { it.text.lowercase(Locale.ROOT) in documentLabels }
                    ) {
                        categories.add(PhotoCategory.DOCUMENT)
                        reasons.add("Possible document or receipt")
                    }

                    if (categories.isNotEmpty()) {
                        results.add(
                            ScannedPhoto(uri, categories, reasons.joinToString(", "))
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
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

    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val left = luminance(resized.getPixel(x, y))
            val right = luminance(resized.getPixel(x + 1, y))
            if (left > right) hash = hash or (1L shl bit)
            bit++
        }
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

    for (y in 1 until 63) {
        for (x in 1 until 63) {
            val center = luminance(bmp.getPixel(x, y))
            sumLum += center
            val laplacian = -4 * center +
                luminance(bmp.getPixel(x - 1, y)) +
                luminance(bmp.getPixel(x + 1, y)) +
                luminance(bmp.getPixel(x, y - 1)) +
                luminance(bmp.getPixel(x, y + 1))
            sumLap += laplacian
            sumLapSq += laplacian * laplacian
        }
    }

    val meanLap = sumLap / count
    val variance = (sumLapSq / count) - meanLap * meanLap
    val meanBrightness = sumLum / count

    if (bmp !== source) bmp.recycle()
    return Pair(variance, meanBrightness)
}

fun luminance(pixel: Int): Int =
    (0.299 * Color.red(pixel) +
        0.587 * Color.green(pixel) +
        0.114 * Color.blue(pixel)).toInt()
