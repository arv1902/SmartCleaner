package com.example.smartcleaner

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

enum class PhotoCategory(val title: String) {
    DUPLICATE("Duplicates"),
    BAD_QUALITY("Blurry / Dark"),
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    CleanerScreen()
                }
            }
        }
    }
}
suspend fun scanGallery(context: Context, onProgress: (String) -> Unit): List<ScannedPhoto> = withContext(Dispatchers.IO) {
    val results = mutableListOf<ScannedPhoto>()
    val seenHashes = mutableListOf<Long>()
    val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val paymentRegex = Regex("\\b(upi|utr|paid to|transaction|debited|credited|gpay|phonepe|paytm|₹|rs\\.?)\\b", RegexOption.IGNORE_CASE)

    val projection = arrayOf(MediaStore.Images.Media._ID)
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection, null, null, sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val total = minOf(cursor.count, 300)
        var index = 0

        while (cursor.moveToNext() && index < 300) {
            index++
            onProgress("Analyzing $index / $total...")
            val id = cursor.getLong(idColumn)
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                }
            } catch (e: Exception) { null } ?: continue

            val categories = mutableSetOf<PhotoCategory>()
            val reasons = mutableListOf<String>()

            val hash = computeDHash(bitmap)
            if (seenHashes.any { java.lang.Long.bitCount(it xor hash) <= 5 }) {
                categories.add(PhotoCategory.DUPLICATE)
                reasons.add("Duplicate")
            } else {
                seenHashes.add(hash)
            }

            val (sharpness, brightness) = evaluateQuality(bitmap)
            if (sharpness < 80.0 || brightness < 35.0) {
                categories.add(PhotoCategory.BAD_QUALITY)
                reasons.add(if (brightness < 35.0) "Dark" else "Blurry")
            }

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val labels = runCatching { labeler.process(inputImage).await() }.getOrDefault(emptyList())
            if (labels.any { it.text in listOf("Food", "Dish", "Cuisine", "Meal", "Snack") && it.confidence > 0.72f }) {
                categories.add(PhotoCategory.FOOD)
                reasons.add("Food")
            }

            val visionText = runCatching { recognizer.process(inputImage).await().text }.getOrDefault("")
            if (paymentRegex.containsMatchIn(visionText)) {
                categories.add(PhotoCategory.PAYMENT)
                reasons.add("Payment")
            } else if (visionText.length > 100 || labels.any { it.text in listOf("Document", "Paper", "Receipt") }) {
                categories.add(PhotoCategory.DOCUMENT)
                reasons.add("Doc")
            }

            if (categories.isNotEmpty()) {
                results.add(ScannedPhoto(uri, categories, reasons.joinToString(", ")))
            }
        }
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
            val c = luminance(bmp.getPixel(x, y))
            sumLum += c
            val lap = -4 * c +
                    luminance(bmp.getPixel(x - 1, y)) +
                    luminance(bmp.getPixel(x + 1, y)) +
                    luminance(bmp.getPixel(x, y - 1)) +
                    luminance(bmp.getPixel(x, y + 1))
            sumLap += lap
            sumLapSq += lap * lap
        }
    }
    val meanLap = sumLap / count
    val variance = (sumLapSq / count) - (meanLap * meanLap)
    val meanBrightness = sumLum / count
    return Pair(variance, meanBrightness)
}

fun luminance(pixel: Int): Int {
    return (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
}
