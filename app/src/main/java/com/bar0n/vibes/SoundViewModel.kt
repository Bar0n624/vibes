package com.bar0n.vibes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList
import kotlin.math.log10

data class SoundDataPoint(val timestamp: Long, val value: Float)

class SoundViewModel : ViewModel() {

    private val _decibelData = MutableStateFlow<List<SoundDataPoint>>(emptyList())
    val decibelData = _decibelData.asStateFlow()

    private val _currentDecibel = MutableStateFlow(0f)
    val currentDecibel = _currentDecibel.asStateFlow()

    private val _overallDecibelAverage = MutableStateFlow(0f)
    val overallDecibelAverage = _overallDecibelAverage.asStateFlow()

    private val _windowDecibelAverage = MutableStateFlow(0f)
    val windowDecibelAverage = _windowDecibelAverage.asStateFlow()

    private val _maxDecibel = MutableStateFlow(0f)
    val maxDecibel = _maxDecibel.asStateFlow()

    private val _minDecibel = MutableStateFlow(0F)
    val minDecibel = _minDecibel.asStateFlow()

    private val windowSize = 30 // Adjust window size as needed
    private val decibelWindow = LinkedList<Float>()

    fun onAudioDataChanged(amplitude: Int, timestamp: Long) {
        viewModelScope.launch {
            if (amplitude > 0 && timestamp > 0) { // Avoid log10(0)
                val decibel = (90 + 20 * log10(amplitude / 32767.0)).toFloat()
                _currentDecibel.value = decibel
                val newDataPoint = SoundDataPoint(timestamp, decibel)
                val updatedList = _decibelData.value.toMutableList().apply { add(newDataPoint) }
                _decibelData.value = updatedList

                if (decibel > _maxDecibel.value || _maxDecibel.value == 0f) _maxDecibel.value = decibel
                if (decibel < _minDecibel.value || _minDecibel.value == 0f) _minDecibel.value = decibel

                _overallDecibelAverage.value = updatedList.map { it.value }.average().toFloat()

                decibelWindow.add(decibel)
                if (decibelWindow.size > windowSize) {
                    decibelWindow.removeFirst()
                }
                _windowDecibelAverage.value = decibelWindow.average().toFloat()
            }
        }
    }

    fun startListening() {
        reset()
    }

    fun stopListening() {
    }

    private fun reset() {
        _decibelData.value = emptyList()
        decibelWindow.clear()
        _currentDecibel.value = 0f
        _overallDecibelAverage.value = 0f
        _windowDecibelAverage.value = 0f
        _maxDecibel.value = 0f
        _minDecibel.value = 0F
    }
}