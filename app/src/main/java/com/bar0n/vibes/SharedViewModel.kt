package com.bar0n.vibes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SharedViewModel : ViewModel() {

    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration = _recordingDuration.asStateFlow()

    private var timerJob: Job? = null
    private var startTime = 0L

    fun startListening() {
        startTime = System.currentTimeMillis()
        _isListening.value = true
        timerJob = viewModelScope.launch {
            while (true) {
                _recordingDuration.value = System.currentTimeMillis() - startTime
                delay(1000)
            }
        }
    }

    fun stopListening() {
        _isListening.value = false
        timerJob?.cancel()
    }

    fun getElapsedTime(): Long {
        return if (isListening.value) System.currentTimeMillis() - startTime else 0
    }
}