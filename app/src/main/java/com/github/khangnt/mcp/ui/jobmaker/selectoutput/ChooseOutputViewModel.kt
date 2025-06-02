package com.github.khangnt.mcp.ui.jobmaker.selectoutput

import android.annotation.SuppressLint
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.support.annotation.WorkerThread
import android.support.v4.provider.DocumentFile
import com.github.khangnt.mcp.SingletonInstances
import com.github.khangnt.mcp.ui.jobmaker.cmdbuilder.CommandConfig
import com.github.khangnt.mcp.util.DistinctLiveData
import com.github.khangnt.mcp.util.listFilesNotNull
import com.github.khangnt.mcp.util.toUri
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Created by Simon Pham on 5/12/18. Email: simonpham.dn@gmail.com */
class ChooseOutputViewModel : ViewModel() {
    companion object {
        val DEFAULT_OUTPUT_FOLDER: Uri
            get() =
                Uri.fromFile(File(Environment.getExternalStorageDirectory(), "MediaConverterPro"))
    }

    @SuppressLint("StaticFieldLeak") private val appContext = SingletonInstances.getAppContext()
    private val sharedPrefs = SingletonInstances.getSharedPrefs()
    private lateinit var outputFolderUri: Uri
    private val outputFolderUriLiveData = MutableLiveData<Uri>()

    private val outputFolderFiles = mutableSetOf<String>()
    private val reservedOutputFiles = mutableSetOf<String>()

    private val listOutputFileModel = MutableLiveData<List<OutputFileAdapterModel>>()
    private val processing = DistinctLiveData<Boolean>()

    private lateinit var commandConfig: CommandConfig

    private val pendingCount = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor()

    init {
        listOutputFileModel.value = emptyList()

        setOutputFolderUri(sharedPrefs.lastOutputFolderUri?.toUri())
    }

    private fun executeAsync(action: () -> Unit) {
        pendingCount.incrementAndGet()
        executor.execute {
            action()
            // update processing status
            processing.postValue(pendingCount.decrementAndGet() != 0)
        }
    }

    fun setOutputFolderUri(outputFolderUri: Uri?) {
        if (!this::outputFolderUri.isInitialized || outputFolderUri != this.outputFolderUri) {
            processing.setValue(true)
            executeAsync {
                this.outputFolderUri =
                    if (
                        outputFolderUri != null &&
                            (outputFolderUri.scheme == ContentResolver.SCHEME_CONTENT &&
                                DocumentFile.fromTreeUri(appContext, outputFolderUri).exists() ||
                                File(outputFolderUri.path).exists())
                    ) {
                        outputFolderUri
                    } else {
                        DEFAULT_OUTPUT_FOLDER
                    }
                outputFolderUriLiveData.postValue(this.outputFolderUri)
                sharedPrefs.lastOutputFolderUri = this.outputFolderUri.toString()
                updateFolderFiles()
                if (this::commandConfig.isInitialized) {
                    updateListOutputFileModel()
                }
            }
        }
    }

    fun getOutputFolderUri(): LiveData<Uri> = outputFolderUriLiveData

    fun getListFolderFileNames(): Set<String> = outputFolderFiles

    fun getListOutputFileNames(): Set<String> = reservedOutputFiles

    fun getListOutputFile(): LiveData<List<OutputFileAdapterModel>> = listOutputFileModel

    fun getProcessingStatus(): LiveData<Boolean> = processing

    fun updateOutput(index: Int, newName: String) {
        val newList = ArrayList(checkNotNull(listOutputFileModel.value))
        val oldModel = newList[index]
        if (oldModel.fileName == newName) {
            // nothing change
            return
        }

        // ensure new file name not cause conflict
        check(!outputFolderFiles.contains(newName) && !reservedOutputFiles.contains(newName))

        reservedOutputFiles.remove(oldModel.fileName)
        reservedOutputFiles.add(newName)
        newList[index] =
            oldModel.copy(fileName = newName, isConflict = false, isOverrideAllowed = false)
        listOutputFileModel.value = newList
    }

    fun updateOutput(index: Int, allowOverride: Boolean) {
        val newList = ArrayList(checkNotNull(listOutputFileModel.value))
        newList[index] = newList[index].copy(isOverrideAllowed = allowOverride)
        listOutputFileModel.value = newList
    }

    fun setCommandConfig(commandConfig: CommandConfig) {
        if (!this::commandConfig.isInitialized || this.commandConfig != commandConfig) {
            this.commandConfig = commandConfig
            processing.setValue(true)
            executeAsync { updateListOutputFileModel(reset = true) }
        }
    }

    fun getCommandConfig(): CommandConfig = commandConfig

    @WorkerThread
    private fun updateListOutputFileModel(reset: Boolean = false) {
        val currentList = checkNotNull(listOutputFileModel.value)
        val newList =
            if (reset || currentList.isEmpty()) {
                reservedOutputFiles.clear()
                val autoGenFiles = commandConfig.generateOutputFiles()
                autoGenFiles.map {
                    var i = 0
                    var fullName = "${it.fileName}.${it.fileExt}"
                    while (
                        outputFolderFiles.contains(fullName) ||
                            reservedOutputFiles.contains(fullName)
                    ) {
                        fullName = "${it.fileName} (${++i}).${it.fileExt}"
                    }
                    reservedOutputFiles.add(fullName)
                    OutputFileAdapterModel(fullName)
                }
            } else {
                val outputFiles = mutableSetOf<String>()
                currentList.map {
                    // ensure after generated list file name,
                    // user can't set new name that cause conflict
                    check(!outputFiles.contains(it.fileName))
                    outputFiles.add(it.fileName)

                    // if folder changed -> update is conflict
                    it.copy(isConflict = outputFolderFiles.contains(it.fileName))
                }
            }
        listOutputFileModel.postValue(newList)
    }

    @WorkerThread
    private fun updateFolderFiles() {
        outputFolderFiles.clear()
        val listFiles =
            if (outputFolderUri.scheme == ContentResolver.SCHEME_CONTENT) {
                DocumentFile.fromTreeUri(SingletonInstances.getAppContext(), outputFolderUri)
                    .listFiles()
                    .map { it.name }
            } else {
                File(outputFolderUri.path).listFilesNotNull().map { it.name }
            }
        outputFolderFiles.addAll(listFiles)
    }
}
