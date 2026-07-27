package com.nuvio.app.features.casting.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.app.features.casting.model.*
import com.nuvio.app.features.casting.transcoding.TranscodingService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TranscodingViewModel(
    private val transcodingService: TranscodingService
) : ViewModel() {

    private val _currentJob = MutableStateFlow<TranscodingJob?>(null)
    val currentJob = _currentJob.asStateFlow()

    private val _progress = MutableStateFlow<TranscodingProgress?>(null)
    val progress = _progress.asStateFlow()

    private val _activeJobs = MutableStateFlow<List<TranscodingJob>>(emptyList())
    val activeJobs = _activeJobs.asStateFlow()

    private val _estimatedTime = MutableStateFlow<Long>(0L)
    val estimatedTime = _estimatedTime.asStateFlow()

    private val _supportsHardwareAccel = MutableStateFlow<Boolean>(false)
    val supportsHardwareAccel = _supportsHardwareAccel.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun startTranscoding(
        sourceUrl: String,
        targetCodec: VideoCodec,
        targetResolution: Resolution,
        targetBitrate: Long,
    ) {
        viewModelScope.launch {
            try {
                val result = transcodingService.startTranscoding(
                    sourceUrl = sourceUrl,
                    targetCodec = targetCodec,
                    targetResolution = targetResolution,
                    targetBitrate = targetBitrate
                )

                result.onSuccess { job ->
                    _currentJob.value = job
                    observeProgress(job.id)
                }

                result.onFailure { exception ->
                    _error.value = exception.message
                }
            } catch (e: Exception) {
                _error.value = "Failed to start transcoding: ${e.message}"
            }
        }
    }

    fun cancelTranscoding(jobId: String) {
        viewModelScope.launch {
            try {
                transcodingService.cancelTranscoding(jobId)
                _currentJob.value = null
                _progress.value = null
            } catch (e: Exception) {
                _error.value = "Failed to cancel: ${e.message}"
            }
        }
    }

    fun checkHardwareSupport(codec: VideoCodec) {
        viewModelScope.launch {
            _supportsHardwareAccel.value = transcodingService.supportsHardwareAcceleration(codec)
        }
    }

    fun estimateTranscodingTime(
        sourceFile: String,
        targetCodec: VideoCodec,
        targetResolution: Resolution,
    ) {
        viewModelScope.launch {
            val estimatedMs = transcodingService.estimateTranscodingTime(
                sourceFile = sourceFile,
                targetCodec = targetCodec,
                targetResolution = targetResolution
            )
            _estimatedTime.value = estimatedMs
        }
    }

    fun loadActiveJobs() {
        viewModelScope.launch {
            try {
                _activeJobs.value = transcodingService.listActiveJobs()
            } catch (e: Exception) {
                _error.value = "Failed to load jobs: ${e.message}"
            }
        }
    }

    private fun observeProgress(jobId: String) {
        viewModelScope.launch {
            try {
                transcodingService.getProgressUpdates(jobId)
                    .collect { progress ->
                        _progress.value = progress
                    }
            } catch (e: Exception) {
                _error.value = "Progress tracking error: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
