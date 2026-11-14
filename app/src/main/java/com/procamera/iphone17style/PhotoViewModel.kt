package com.procamera.iphone17style

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PhotoState(
    val original: Bitmap? = null,
    val result: Bitmap? = null,
    val busy: Boolean = false,
    val status: String = "Снимите фото или откройте галерею",
    val params: ProcessingParams = ProcessingParams(),
    val appliedParams: ProcessingParams? = null,
    val saved: Boolean = false
)

class PhotoViewModel(app: Application) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(PhotoState())
    val state = mutable.asStateFlow()
    private var processingInput: Bitmap? = null
    private var operation: Job? = null
    private var sourceNotice: String? = null

    fun setParams(params: ProcessingParams) {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(params = params)
    }

    fun open(uri: Uri) = perform("Загружаем фото…") {
        val bmp = PhotoFiles.decode(getApplication(), uri)
        sourceNotice = null
        accept(CaptureResult(bmp, bmp))
    }

    // Acquisition lives in Activity's lifecycleScope so a destroyed camera is not retained.
    fun beginCapture(): Boolean {
        if (mutable.value.busy) return false
        mutable.value = mutable.value.copy(busy = true, status = "Снимаем… Держите телефон неподвижно")
        return true
    }

    fun captureFailed(error: Throwable) {
        mutable.value = mutable.value.copy(busy = false, status = "Съёмка не завершена: ${error.message ?: "ошибка камеры"}")
    }

    fun captured(photo: CaptureResult) {
        // beginCapture already claimed the busy state.
        operation = viewModelScope.launch {
            try { accept(photo) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failure(error) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private suspend fun accept(photo: CaptureResult) {
        processingInput = photo.input
        sourceNotice = photo.notice
        mutable.value = mutable.value.copy(original = photo.original, result = null,
            saved = false, appliedParams = null, status = "Обрабатываем фото…")
        render()
    }

    fun apply() = perform("Обрабатываем фото…") { render() }

    private suspend fun render() {
        val input = processingInput ?: return
        val params = mutable.value.params
        val photo = ApplePhotonicEngine.processImage(input, params)
        mutable.value = mutable.value.copy(result = photo.bitmap, appliedParams = params, saved = false,
            status = listOfNotNull(photo.notice, sourceNotice).joinToString(" · ").ifBlank {
                "Готово · ${photo.bitmap.width} × ${photo.bitmap.height}"
            })
    }

    fun save() = perform("Сохраняем в галерею…") {
        val bitmap = mutable.value.result ?: error("Сначала обработайте фото")
        PhotoFiles.save(getApplication(), bitmap)
        mutable.value = mutable.value.copy(saved = true, status = "Сохранено: Галерея → ProCam17")
    }

    fun clear() {
        if (mutable.value.busy) return
        processingInput = null
        sourceNotice = null
        mutable.value = PhotoState(params = mutable.value.params)
    }

    private fun failure(error: Exception) {
        mutable.value = mutable.value.copy(status = "Ошибка: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun perform(label: String, work: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, status = label)
        operation = viewModelScope.launch {
            try { work() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failure(error) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
