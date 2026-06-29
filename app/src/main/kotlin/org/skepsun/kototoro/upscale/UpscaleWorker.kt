package org.skepsun.kototoro.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toFile
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.reader.translate.data.OnnxModelManager
import org.skepsun.kototoro.reader.translate.data.RealCuganNcnnEngine
import org.skepsun.kototoro.reader.translate.data.RealEsrganNcnnEngine
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.UUID

@HiltWorker
class UpscaleWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val localMangaRepository: LocalMangaRepository,
    private val appSettings: AppSettings,
    private val onnxModelManager: OnnxModelManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mangaUrl = inputData.getString(KEY_URL) ?: return@withContext Result.failure()


        val mangaUrlUri = android.net.Uri.parse(mangaUrl)


        // Local manga can be single CBZ or dir containing CBZ/images. Let's find files.
        val baseDir = mangaUrlUri.path?.let { java.io.File(it) } ?: java.io.File(mangaUrl)
        val filesToProcess = mutableListOf<File>()

        if (baseDir.isFile && (baseDir.name.endsWith(".cbz", true) || baseDir.name.endsWith(".zip", true))) {
            filesToProcess.add(baseDir)
        } else if (baseDir.isDirectory) {
            baseDir.walkTopDown().filter { it.isFile && (it.name.endsWith(".cbz", true) || it.name.endsWith(".zip", true)) }.forEach {
                filesToProcess.add(it)
            }
        }

        if (filesToProcess.isEmpty()) {
            return@withContext Result.failure()
        }

        val engine = appSettings.upscaleEngine
        val modelId = appSettings.upscaleNcnnModel

        var realesrganEngine: RealEsrganNcnnEngine? = null
        var realcuganEngine: RealCuganNcnnEngine? = null

        if (engine == "NCNN") {
            try {
                if (!onnxModelManager.isModelDownloaded(modelId)) {
                    Log.e("UpscaleWorker", "SR model not downloaded: $modelId")
                    return@withContext Result.failure()
                }

                val modelsDir = onnxModelManager.getModelDir(modelId)
                val expectedParamName = if (modelId.contains("realesrgan", ignoreCase = true))
                    "realesrgan-x4plus-anime.param" else "up2x-conservative.param"
                val expectedBinName = if (modelId.contains("realesrgan", ignoreCase = true))
                    "realesrgan-x4plus-anime.bin" else "up2x-conservative.bin"

                val paramFile = modelsDir.walkTopDown().firstOrNull { it.name == expectedParamName }
                    ?: throw IllegalStateException("No parameter file $expectedParamName found for model $modelId")
                val binFile = modelsDir.walkTopDown().firstOrNull { it.name == expectedBinName }
                    ?: throw IllegalStateException("No binary file $expectedBinName found for model $modelId")

                if (modelId.contains("realesrgan", ignoreCase = true)) {
                    realesrganEngine = RealEsrganNcnnEngine()
                    realesrganEngine.initialize(paramFile.absolutePath, binFile.absolutePath, ttaMode = false)
                } else {
                    realcuganEngine = RealCuganNcnnEngine()
                    realcuganEngine.initialize(paramFile.absolutePath, binFile.absolutePath, ttaMode = false)
                }
            } catch (e: Exception) {
                Log.e("UpscaleWorker", "Failed to initialize engine", e)
                return@withContext Result.failure()
            }
        } else {
            // Only NCNN is supported for background upscale currently
            return@withContext Result.failure()
        }

        try {
            for (file in filesToProcess) {
                processZipFile(file, realesrganEngine, realcuganEngine)
            }
        } catch (e: Exception) {
            Log.e("UpscaleWorker", "Failed to process files", e)
            return@withContext Result.failure()
        } finally {
            realesrganEngine?.release()
            realcuganEngine?.release()
        }

        Result.success()
    }

    private suspend fun processZipFile(
        zipFile: File,
        realesrganEngine: RealEsrganNcnnEngine?,
        realcuganEngine: RealCuganNcnnEngine?
    ) = withContext(Dispatchers.IO) {
        val tempFile = File(zipFile.parent, zipFile.name + ".tmp")

        ZipFile(zipFile).use { zf ->
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    if (entry.isDirectory) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        zos.closeEntry()
                        continue
                    }

                    val isImage = entry.name.endsWith(".jpg", true) ||
                                 entry.name.endsWith(".jpeg", true) ||
                                 entry.name.endsWith(".png", true) ||
                                 entry.name.endsWith(".webp", true)

                    if (isImage) {
                        zf.getInputStream(entry).use { inputStream ->
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            if (bitmap != null) {
                                val upscaledBitmap = if (realesrganEngine != null) {
                                    realesrganEngine.process(bitmap)
                                } else {
                                    realcuganEngine?.process(bitmap)
                                }

                                if (upscaledBitmap != null) {
                                    val newEntry = ZipEntry(entry.name)
                                    zos.putNextEntry(newEntry)
                                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                                    val format = when (ext) {
                                        "png" -> Bitmap.CompressFormat.PNG
                                        "webp" -> Bitmap.CompressFormat.WEBP
                                        else -> Bitmap.CompressFormat.JPEG
                                    }
                                    upscaledBitmap.compress(format, 90, zos)
                                    upscaledBitmap.recycle()
                                    zos.closeEntry()
                                } else {
                                    // Fallback to original
                                    copyEntry(zf, entry, zos)
                                }
                                bitmap.recycle()
                            } else {
                                copyEntry(zf, entry, zos)
                            }
                        }
                    } else {
                        copyEntry(zf, entry, zos)
                    }
                }
            }
        }

        // Replace original with temp
        if (tempFile.exists()) {
            zipFile.delete()
            tempFile.renameTo(zipFile)
        }
    }

    private fun copyEntry(zf: ZipFile, entry: ZipEntry, zos: ZipOutputStream) {
        val newEntry = ZipEntry(entry.name)
        zos.putNextEntry(newEntry)
        zf.getInputStream(entry).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"

        fun enqueue(context: Context, url: String, title: String) {
            val inputData = Data.Builder()
                .putString(KEY_URL, url)
                .putString(KEY_TITLE, title)
                .build()

            val request = OneTimeWorkRequestBuilder<UpscaleWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "UpscaleWorker_${url.hashCode()}",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
