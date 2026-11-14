package com.procamera.iphone17style

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: PhotoViewModel by viewModels()
    private lateinit var controller: CameraController
    private lateinit var preview: PreviewView
    private lateinit var liveImage: ImageView
    private lateinit var image: ImageView
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private lateinit var badge: TextView
    private lateinit var progress: ProgressBar
    private lateinit var shutter: Button
    private lateinit var flip: Button
    private lateinit var gallery: Button
    private lateinit var newCamera: Button
    private lateinit var save: Button
    private lateinit var apply: Button
    private lateinit var permission: Button
    private lateinit var bracket: CheckBox
    private val sliders = mutableListOf<SeekBar>()
    private val labels = mutableListOf<TextView>()
    private val presets = mutableListOf<Button>()
    private var syncing = false
    private var bound = false
    private var binding = false
    private var comparing = false
    private val gold = Color.rgb(255, 214, 10)
    private val muted = Color.rgb(162, 168, 179)
    private val panel = Color.rgb(24, 27, 33)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showCameraUnavailable("Нет доступа к камере. Галерея доступна.")
    }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.save() else message("Для сохранения на Android 8–9 разрешите доступ к хранилищу")
    }
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.open(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        controller = CameraController(this, this, preview)
        controller.previewParams = { model.state.value.params }
        controller.shouldAnalyze = {
            model.state.value.original == null && !model.state.value.busy
        }
        controller.onFrame = { bitmap ->
            liveImage.setImageBitmap(bitmap)
            liveImage.visibility = View.VISIBLE
            badge.text = "LIVE · СВЕТ / ДЕТАЛИ / ЦВЕТ"
        }
        controller.onPreviewFailure = { reason ->
            liveImage.visibility = View.GONE
            liveImage.setImageDrawable(null)
            badge.text = "PHOTO · ОБРАБОТКА ПОСЛЕ СЪЁМКИ"
            status.text = reason
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (model.state.value.busy) { message("Дождитесь завершения операции"); return }
                if (model.state.value.original != null) model.clear() else finish()
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
        if (!hasCameraPermission() && !getPreferences(MODE_PRIVATE).getBoolean("cameraAsked", false)) {
            requestCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized && model.state.value.original == null && hasCameraPermission()) startCamera()
    }

    private fun buildScreen() {
        val root = column().apply {
            setBackgroundColor(Color.rgb(8, 9, 11))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val header = row()
        header.addView(text("ProCam 17", 25f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("О проекте") { help() }, LinearLayout.LayoutParams(dp(112), dp(44)))
        root.addView(header)
        root.addView(text("СВЕТ  /  ДЕТАЛИ  /  ЦВЕТ", 10f, gold).apply { letterSpacing = 0.16f })

        status = text("Снимите фото или откройте галерею", 12f, muted)
        status.minLines = 2
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })

        val viewport = FrameLayout(this).apply { background = rounded(panel, 18); clipToOutline = true }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            contentDescription = "Предпросмотр камеры. Коснитесь для фокусировки"
        }
        viewport.addView(preview, FrameLayout.LayoutParams(-1, -1))
        liveImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(panel)
            visibility = View.GONE
            contentDescription = "Камера с обработкой в реальном времени. Коснитесь для фокусировки"
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    controller.focus(event.x, event.y)
                    view.performClick()
                }
                true
            }
        }
        viewport.addView(liveImage, FrameLayout.LayoutParams(-1, -1))
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Обработанное фото. Удерживайте, чтобы увидеть оригинал"
            visibility = View.GONE
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { comparing = true; showBitmap(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        comparing = false; showBitmap(); view.performClick(); true
                    }
                    else -> true
                }
            }
        }
        viewport.addView(image, FrameLayout.LayoutParams(-1, -1))
        empty = text("Камера загружается…\nМожно выбрать фото из галереи", 15f, muted).apply { gravity = Gravity.CENTER }
        viewport.addView(empty, FrameLayout.LayoutParams(-1, -1))
        badge = text("PHOTO", 10f, gold).apply {
            background = rounded(Color.argb(180, 0, 0, 0), 12)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        viewport.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12)
        })
        progress = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(gold); visibility = View.GONE }
        viewport.addView(progress, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = column().apply { setPadding(0, dp(8), 0, 0) }
        val actions = row()
        gallery = button("Галерея") { picker.launch("image/*") }
        newCamera = button("Камера") { model.clear(); startCamera() }
        save = button("Сохранить", true) { requestSave() }
        addEqual(actions, gallery, newCamera, save)
        controls.addView(actions)

        val captureRow = row()
        flip = button("Сменить") { startCamera(true) }
        shutter = button("●  СНЯТЬ", true) { capturePhoto() }
        permission = button("Доступ") { requestCamera() }
        addEqual(captureRow, flip, shutter, permission)
        controls.addView(captureRow)

        bracket = CheckBox(this).apply {
            text = "HDR · 3 экспозиции (неподвижная сцена)"
            textSize = 12f; setTextColor(muted)
            buttonTintList = ColorStateList.valueOf(gold)
            isChecked = getPreferences(MODE_PRIVATE).getBoolean("bracket", false)
            setOnCheckedChangeListener { _, checked ->
                getPreferences(MODE_PRIVATE).edit().putBoolean("bracket", checked).apply()
            }
        }
        controls.addView(bracket)

        val presetRow = row()
        listOf("Естественно" to ProcessingParams(),
            "Портрет" to ProcessingParams(0.4f, 0.2f, 0.3f, 0.7f),
            "Контраст" to ProcessingParams(0.8f, 0.6f, 0.65f, 0f)).forEach { (name, params) ->
            val b = button(name) { model.setParams(params); if (model.state.value.original != null) model.apply() }
            presets += b
            presetRow.addView(b, equalParams())
        }
        controls.addView(presetRow)
        listOf("Свет и тени", "Детализация", "Цвет", "Размытие фона · люди").forEachIndexed { index, title ->
            val label = text(title, 12f, muted)
            labels += label
            controls.addView(label, LinearLayout.LayoutParams(-1, dp(22)))
            val slider = SeekBar(this).apply {
                max = 100; progressTintList = ColorStateList.valueOf(gold)
                thumbTintList = ColorStateList.valueOf(gold)
                contentDescription = title
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        if (syncing || !fromUser) return
                        val old = model.state.value.params
                        val v = value / 100f
                        model.setParams(when (index) {
                            0 -> old.copy(smartHdr = v)
                            1 -> old.copy(deepFusion = v)
                            2 -> old.copy(photonicColor = v)
                            else -> old.copy(portraitBokeh = v)
                        })
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            sliders += slider
            controls.addView(slider, LinearLayout.LayoutParams(-1, dp(30)))
        }
        apply = button("Применить настройки", true) { model.apply() }
        controls.addView(apply, LinearLayout.LayoutParams(-1, dp(48)))
        controls.addView(text("Удерживайте фото для сравнения с оригиналом", 11f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
        })
        val scroll = ScrollView(this).apply { addView(controls); isFillViewport = false }
        // Controls scroll on compact displays; preview retains roughly 40% of usable height.
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1.15f))
        setContentView(root)
    }

    private fun render(state: PhotoState) {
        val editing = state.original != null
        status.text = state.status
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        gallery.isEnabled = !state.busy
        newCamera.isEnabled = !state.busy
        save.isEnabled = !state.busy && state.result != null && state.appliedParams == state.params
        save.text = if (state.saved) "Сохранено ✓" else "Сохранить"
        apply.isEnabled = !state.busy && editing
        apply.text = if (editing && state.params != state.appliedParams) "Применить изменения" else "Применить настройки"
        shutter.visibility = if (editing) View.GONE else View.VISIBLE
        flip.visibility = if (editing) View.GONE else View.VISIBLE
        permission.visibility = if (!editing && !hasCameraPermission()) View.VISIBLE else View.GONE
        bracket.visibility = if (editing) View.GONE else View.VISIBLE
        bracket.isEnabled = !state.busy && bound
        shutter.isEnabled = !state.busy && bound
        flip.isEnabled = !state.busy && bound && controller.hasAlternateCamera
        presets.forEach { it.isEnabled = !state.busy }
        syncing = true
        val values = listOf(state.params.smartHdr, state.params.deepFusion, state.params.photonicColor, state.params.portraitBokeh)
        val titles = listOf("Свет и тени", "Детализация", "Цвет", "Размытие фона · люди")
        sliders.forEachIndexed { i, slider ->
            slider.isEnabled = !state.busy
            slider.progress = (values[i] * 100).toInt()
            labels[i].text = "${titles[i]}   ${(values[i] * 100).toInt()}%"
        }
        syncing = false
        image.visibility = if (editing) View.VISIBLE else View.GONE
        preview.visibility = if (editing) View.GONE else View.VISIBLE
        if (editing) {
            liveImage.visibility = View.GONE
            liveImage.setImageDrawable(null)
            empty.visibility = View.GONE
            if (bound) { controller.stop(); bound = false }
        } else if (hasCameraPermission() && !bound && !binding) startCamera()
        showBitmap()
    }

    private fun showBitmap() {
        val state = model.state.value
        image.setImageBitmap(if (comparing) state.original else state.result ?: state.original)
        badge.text = when {
            state.original == null -> if (liveImage.visibility == View.VISIBLE) "LIVE · ПРЕДПРОСМОТР" else "PHOTO"
            comparing -> "ОРИГИНАЛ"
            state.result == null -> "ОБРАБОТКА"
            else -> "PROCAM · УДЕРЖИВАЙТЕ ДЛЯ СРАВНЕНИЯ"
        }
    }

    private fun startCamera(switch: Boolean = false) {
        if (!hasCameraPermission() || binding || model.state.value.original != null || model.state.value.busy) return
        if (bound && !switch) return
        binding = true
        liveImage.visibility = View.GONE
        liveImage.setImageDrawable(null)
        lifecycleScope.launch {
            try {
                controller.start(switch)
                if (model.state.value.original != null || model.state.value.busy) {
                    controller.stop()
                    return@launch
                }
                bound = true
                empty.visibility = View.GONE
                shutter.isEnabled = true
                flip.isEnabled = controller.hasAlternateCamera
                bracket.isEnabled = true
                if (!controller.supportsBracket()) bracket.text = "HDR-серия недоступна на этой камере"
                else bracket.text = "HDR · 3 экспозиции (неподвижная сцена)"
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { showCameraUnavailable(error.message ?: "Не удалось открыть камеру") }
            finally { binding = false }
        }
    }

    private fun capturePhoto() {
        if (!bound || !model.beginCapture()) return
        lifecycleScope.launch {
            try { model.captured(controller.takePhoto(bracket.isChecked)) }
            catch (cancel: CancellationException) {
                model.captureFailed(IllegalStateException("камера закрыта")); throw cancel
            }
            catch (error: Exception) { model.captureFailed(error) }
        }
    }

    private fun requestCamera() {
        if (hasCameraPermission()) { startCamera(); return }
        val prefs = getPreferences(MODE_PRIVATE)
        if (prefs.getBoolean("cameraAsked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            prefs.edit().putBoolean("cameraAsked", true).apply()
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestSave() {
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else model.save()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this,
        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showCameraUnavailable(reason: String) {
        bound = false; shutter.isEnabled = false; flip.isEnabled = false
        empty.visibility = View.VISIBLE; empty.text = "$reason\nОткройте фото из галереи"
    }

    private fun help() = AlertDialog.Builder(this).setTitle("ProCam 17 Studio")
        .setMessage("Съёмка и обработка в стиле современной мобильной фотографии.\n\n" +
            "Коснитесь превью для фокусировки. Для HDR-серии держите телефон неподвижно: снимаются три JPEG с разной экспозицией. При движении приложение может использовать один кадр.\n\n" +
            "В LIVE видны свет, детали, цвет и приближённое портретное размытие. Превью уменьшено и обновляется до 10 раз/с; итоговый снимок обрабатывается отдельно. На слабых устройствах live может быть медленнее или недоступен.\n\n" +
            "Портретное размытие предназначено для людей. Фото обрабатываются на устройстве. Максимальная длинная сторона результата — 2048 пикселей.\n\n" +
            "Это независимые эффекты, а не алгоритмы Apple Smart HDR / Deep Fusion. Приложение не связано с Apple.")
        .setPositiveButton("Понятно", null).show()

    private fun message(value: String) { android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_LONG).show() }
    override fun onDestroy() { if (::controller.isInitialized) controller.close(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(48, 48, 48), if (primary) gold else panel))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(105, 105, 105), if (primary) Color.BLACK else Color.WHITE)))
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun equalParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }
    private fun addEqual(row: LinearLayout, vararg views: View) { views.forEach { row.addView(it, equalParams()) } }
}
