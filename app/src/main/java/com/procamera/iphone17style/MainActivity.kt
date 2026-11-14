package com.procamera.iphone17style

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFD60A), // Apple Pro Camera Gold Accent
                    background = Color(0xFF000000),
                    surface = Color(0xFF141414),
                    onPrimary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    ProCam17Screen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProCam17Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showOriginalHold by remember { mutableStateOf(false) }

    var params by remember { mutableStateOf(ProcessingParams()) }

    fun runProcessing(bmp: Bitmap, currentParams: ProcessingParams) {
        scope.launch {
            isProcessing = true
            val res = ApplePhotonicEngine.processImage(context, bmp, currentParams)
            processedBitmap = res
            isProcessing = false
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                isProcessing = true
                val loader = ImageLoader(context)
                val req = ImageRequest.Builder(context)
                    .data(it)
                    .allowHardware(false)
                    .build()
                val res = (loader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
                withContext(Dispatchers.Main) {
                    if (res != null) {
                        originalBitmap = res
                        params = ProcessingParams() // Default iPhone 17 Pro Auto-Masterpiece preset
                        runProcessing(res, params)
                    } else {
                        isProcessing = false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ProCam 17", fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFFFFD60A),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "PHOTONIC AI",
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (originalBitmap != null) {
                        IconButton(onClick = { pickerLauncher.launch("image/*") }) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Новое фото", tint = Color.White)
                        }
                        IconButton(onClick = {
                            processedBitmap?.let { bmp ->
                                scope.launch {
                                    saveBitmapToGallery(context, bmp)
                                }
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "Сохранить", tint = Color(0xFFFFD60A))
                        }
                    }
                }
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Image Preview Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val displayBmp = if (showOriginalHold) originalBitmap else (processedBitmap ?: originalBitmap)
                if (displayBmp != null) {
                    Image(
                        bitmap = displayBmp.asImageBitmap(),
                        contentDescription = "Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        showOriginalHold = true
                                        tryAwaitRelease()
                                        showOriginalHold = false
                                    }
                                )
                            }
                    )

                    // Badge showing Original vs Processed
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                    ) {
                        Text(
                            text = if (showOriginalHold) "ОРИГИНАЛ" else "iPHONE 17 PRO STYLE (удерживайте для сравнения)",
                            color = if (showOriginalHold) Color.LightGray else Color(0xFFFFD60A),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    if (isProcessing) {
                        CircularProgressIndicator(color = Color(0xFFFFD60A))
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFFFFD60A),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Превратите любое фото в снимок на iPhone 17 Pro",
                            color = Color.LightGray,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { pickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD60A))
                        ) {
                            Text("Выбрать фото из галереи", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Control Panel
            if (originalBitmap != null) {
                Surface(
                    color = Color(0xFF141414),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Presets Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PresetChip("Авто Шедевр", Modifier.weight(1f)) {
                                val p = ProcessingParams(0.75f, 0.65f, 0.70f, 0.0f)
                                params = p
                                originalBitmap?.let { runProcessing(it, p) }
                            }
                            PresetChip("Портрет 17 Pro", Modifier.weight(1f)) {
                                val p = ProcessingParams(0.70f, 0.55f, 0.75f, 0.75f)
                                params = p
                                originalBitmap?.let { runProcessing(it, p) }
                            }
                            PresetChip("Максимум HDR", Modifier.weight(1f)) {
                                val p = ProcessingParams(1.0f, 0.85f, 0.85f, 0.0f)
                                params = p
                                originalBitmap?.let { runProcessing(it, p) }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        ProSlider("Smart HDR 5 (Тени и свет)", params.smartHdr) { v ->
                            params = params.copy(smartHdr = v)
                        }
                        ProSlider("Deep Fusion (Детализация)", params.deepFusion) { v ->
                            params = params.copy(deepFusion = v)
                        }
                        ProSlider("Photonic Color (Цвет и кожа)", params.photonicColor) { v ->
                            params = params.copy(photonicColor = v)
                        }
                        ProSlider("Portrait Bokeh (ИИ размытие фона)", params.portraitBokeh) { v ->
                            params = params.copy(portraitBokeh = v)
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = { originalBitmap?.let { runProcessing(it, params) } },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD60A))
                        ) {
                            Text("Применить настройки", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PresetChip(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(title, fontSize = 11.sp, color = Color.White, maxLines = 1)
    }
}

@Composable
fun ProSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = Color.LightGray)
            Text("${(value * 100).toInt()}%", fontSize = 12.sp, color = Color(0xFFFFD60A), fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFD60A),
                activeTrackColor = Color(0xFFFFD60A),
                inactiveTrackColor = Color.DarkGray
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap) = withContext(Dispatchers.IO) {
    val filename = "ProCam17_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProCam17")
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Сохранено в Галерею (папка ProCam17)!", Toast.LENGTH_LONG).show()
        }
    }
}
