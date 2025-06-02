package com.github.khangnt.mcp.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.github.khangnt.mcp.annotation.JobStatus
import com.github.khangnt.mcp.db.job.Job
import com.github.khangnt.mcp.db.job.JobRepository
import com.github.khangnt.mcp.getKnownReasonOf
import com.github.khangnt.mcp.reportNonFatal
import com.github.khangnt.mcp.util.deleteRecursiveIgnoreError
import com.liulishuo.filedownloader.BaseDownloadTask
import com.liulishuo.filedownloader.FileDownloadSampleListener
import com.liulishuo.filedownloader.FileDownloader
import com.liulishuo.filedownloader.model.FileDownloadStatus
import java.io.BufferedOutputStream
import java.io.File
import java.lang.Exception
import timber.log.Timber

private const val DOWNLOAD_TASK_UPDATE_INTERVAL = 500

/**
 * Some inputs with scheme http://, https://, content:// can't be handled by FFmpeg internally (only
 * support file and pipe protocol). These inputs need to copy to temp folder and will be deleted
 * after job completed.
 *
 * JobPrepareThread was created to download/copy inputs to temp folder, where ffmpeg can read
 * directly.
 */
class JobPrepareThread(
    val appContext: Context,
    var job: Job,
    private val jobRepository: JobRepository,
    private val onCompleteListener: (Job) -> Unit,
    private val onErrorListener: (Job, Throwable?) -> Unit,
    private val workingPaths: WorkingPaths = makeWorkingPaths(appContext),
) : Thread() {
    private var jobTempDir: File? = null

    override fun run() {
        // start preparing
        Timber.d("Start preparing job: ${job.title}")

        jobTempDir =
            try {
                workingPaths.getTempDirForJob(job.id)
            } catch (error: Throwable) {
                onError(error, "Error: ${error.message}")
                return
            }

        val contentResolver = appContext.contentResolver

        val inputs = job.command.inputs
        inputs.forEachIndexed { index, input ->
            val inputUri = Uri.parse(input)
            when (inputUri.scheme?.toLowerCase()) {
                ContentResolver.SCHEME_CONTENT -> {
                    // content:// can't be recognized by any ffmpeg protocol
                    val inputCopyTo = makeInputTempFile(jobTempDir!!, index)
                    Timber.d("Copy input $index to $inputCopyTo")
                    updateJob(statusDetail = "Copying input $index", block = true)
                    try {
                        contentResolver.openInputStream(inputUri).use { inputStream ->
                            val outputStream =
                                contentResolver.openOutputStream(Uri.fromFile(inputCopyTo))
                            BufferedOutputStream(outputStream).use { bufferedOutputStream ->
                                inputStream.copyTo(bufferedOutputStream)
                            }
                        }
                    } catch (anyError: Throwable) {
                        onError(anyError, "Prepare input $index failed: ${anyError.message}")
                        return
                    }
                }
                "http",
                "https" -> { // ffmpeg not compiled to support http/https protocol
                    val inputDownloadTo = makeInputTempFile(jobTempDir!!, index).absolutePath
                    Timber.d("Download input $index to $inputDownloadTo")
                    updateJob(statusDetail = "Downloading input $index", block = true)
                    val downloadTask =
                        FileDownloader.getImpl()
                            .create(input)
                            .setForceReDownload(true)
                            .setPath(inputDownloadTo)
                            .setCallbackProgressMinInterval(DOWNLOAD_TASK_UPDATE_INTERVAL)
                            .setSyncCallback(true)
                            .setListener(
                                object : FileDownloadSampleListener() {
                                    override fun progress(
                                        task: BaseDownloadTask,
                                        soFarBytes: Int,
                                        totalBytes: Int,
                                    ) {
                                        val speed = task.speed
                                        val percent =
                                            if (totalBytes > 0) (soFarBytes * 100 / totalBytes)
                                            else -1
                                        val percentString = if (percent > 0) "$percent%" else ""
                                        val status =
                                            "Downloading input $index\n${speed}KB/s $percentString"
                                        updateJob(statusDetail = status, block = false)
                                    }
                                }
                            )
                    downloadTask.start()

                    while (true) {
                        try {
                            Thread.sleep(DOWNLOAD_TASK_UPDATE_INTERVAL.toLong())
                        } catch (interruptedException: InterruptedException) {
                            onError(interruptedException, "Job was cancelled")
                            FileDownloader.getImpl().clear(downloadTask.id, inputDownloadTo)
                            return
                        }
                        if (FileDownloadStatus.isOver(downloadTask.status.toInt())) {
                            if (downloadTask.status == FileDownloadStatus.error) {
                                val error =
                                    downloadTask.errorCause
                                        ?: Exception("Download input $index failed")
                                onError(error, "Download input $index failed: ${error.message}")
                                return
                            } else {
                                Timber.d("Download input $index completed")
                                break
                            }
                        }
                    }
                }
                "file" -> Unit // needn't prepare
                else -> {
                    val message = "Unsupported input scheme ${inputUri.scheme}"
                    onError(Exception(message), message)
                    return
                }
            }
        }

        // prepared -> ready to convert
        Timber.d("Prepared ${job.id} - ${job.title}")
        updateJob(status = JobStatus.READY, block = true)
        onCompleteListener(job)
    }

    private fun updateJob(
        status: Int = JobStatus.PREPARING,
        statusDetail: String? = null,
        block: Boolean,
    ) {
        job = job.copy(status = status, statusDetail = statusDetail)
        if (block) {
            jobRepository.updateJob(job, ignoreError = false).blockingAwait()
        } else {
            jobRepository.updateJob(job, ignoreError = true).subscribe()
        }
    }

    private fun onError(error: Throwable, message: String) {
        val errorDetail = getKnownReasonOf(error, appContext, message)
        updateJob(status = JobStatus.FAILED, statusDetail = errorDetail, block = true)
        onErrorListener(job, error)

        // clean up temp dir
        jobTempDir?.deleteRecursiveIgnoreError()

        Timber.d(error, "%s", message)
        reportNonFatal(error, "JobPrepareThread#onError", message)
    }
}
