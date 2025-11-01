package com.bar0n.vibes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList
import kotlin.math.sqrt

data class VibrationDataPoint(val timestamp: Long, val value: Float)

class VibrationViewModel : ViewModel() {

    private val _vibrationData = MutableStateFlow<List<VibrationDataPoint>>(emptyList())
    val vibrationData = _vibrationData.asStateFlow()

    private val _currentVibration = MutableStateFlow(0f)
    val currentVibration = _currentVibration.asStateFlow()

    private val _overallVibrationAverage = MutableStateFlow(0f)
    val overallVibrationAverage = _overallVibrationAverage.asStateFlow()

    private val _windowVibrationAverage = MutableStateFlow(0f)
    val windowVibrationAverage = _windowVibrationAverage.asStateFlow()

    private val _maxVibration = MutableStateFlow(0f)
    val maxVibration = _maxVibration.asStateFlow()

    private val _minVibration = MutableStateFlow(0F)
    val minVibration = _minVibration.asStateFlow()

    private val windowSize = 30 // Adjust window size as needed
    private val vibrationWindow = LinkedList<Float>()

    fun onVibrationDataChanged(x: Float, y: Float, z: Float, timestamp: Long) {
        viewModelScope.launch {
            val magnitude = sqrt(x * x + y * y + z * z)
            _currentVibration.value = magnitude
            val newDataPoint = VibrationDataPoint(timestamp, magnitude)
            val updatedList = _vibrationData.value.toMutableList().apply { add(newDataPoint) }
            _vibrationData.value = updatedList

            if (magnitude > _maxVibration.value || _maxVibration.value == 0F) _maxVibration.value = magnitude
            if (magnitude < _minVibration.value || _minVibration.value == 0F) _minVibration.value = magnitude

            _overallVibrationAverage.value = updatedList.map { it.value }.average().toFloat()

            vibrationWindow.add(magnitude)
            if (vibrationWindow.size > windowSize) {
                vibrationWindow.removeFirst()
            }
            _windowVibrationAverage.value = vibrationWindow.average().toFloat()
        }
    }

    fun startListening() {
        reset()
    }

    fun stopListening() {
    }

    private fun reset() {
        _vibrationData.value = emptyList()
        vibrationWindow.clear()
        _currentVibration.value = 0f
        _overallVibrationAverage.value = 0f
        _windowVibrationAverage.value = 0f
        _maxVibration.value = 0f
        _minVibration.value = 0F
    }
}