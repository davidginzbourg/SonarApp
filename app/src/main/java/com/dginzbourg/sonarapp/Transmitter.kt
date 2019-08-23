package com.dginzbourg.sonarapp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.floor
import kotlin.math.sin

class Transmitter {

    lateinit var mAudioPlayer: AudioTrack
    // t1 < 1 second
    var mPlayerBuffer = ShortArray(MainActivity.SAMPLE_RATE)

    private fun applyFade(framesToFade: Int) {
        var fadeFactor: Double
        for (i in 0..framesToFade) {
            fadeFactor = i.toDouble() / framesToFade
            mPlayerBuffer[i] = (mPlayerBuffer[i] * fadeFactor).toShort()
            mPlayerBuffer[mPlayerBuffer.size - i - 1] = (mPlayerBuffer[mPlayerBuffer.size - i - 1]
                    * fadeFactor).toShort()
        }
    }

    fun transmit() {
        Log.d(MainActivity.LOG_TAG, "Transmitting...")
        mAudioPlayer.write(mPlayerBuffer, 0, mPlayerBuffer.size)
        mAudioPlayer.play()
    }

    fun init() {
        Log.d(MainActivity.LOG_TAG, "Initializing the Transmitter...")
        mAudioPlayer = AudioTrack.Builder()
                .setAudioAttributes(
                        AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                )
                .setAudioFormat(
                        AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(MainActivity.SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(MainActivity.PLAYER_BUFFER_SIZE * 2) // This is in bytes and we use Short
                .build()

        // TODO: check that the audio player has been initialized properly

        // TODO: pad the beginning of the buffer with enough 0's so it'll fit in the listener's recording
//        for (sampleIndex in mPlayerBuffer.indices) {
//            mPlayerBuffer[sampleIndex] = (
//                    sin(MainActivity.MAIN_FREQUENCY * 2 * Math.PI * sampleIndex / MainActivity.SAMPLE_RATE) // The percentage of the max value
//                            * Short.MAX_VALUE).toShort()
//        }

        val numSamples = Math.round(MainActivity.CHIRP_DURATION * MainActivity.SAMPLE_RATE).toInt()
        mPlayerBuffer = convertToShort(hanningWindow(chirp(0.0, MainActivity.MIN_CHIRP_FREQ,
                MainActivity.MAX_CHIRP_FREQ, MainActivity.CHIRP_DURATION, MainActivity.SAMPLE_RATE.toDouble()), numSamples))
        //applyFade(floor(mPlayerBuffer.size * MainActivity.FADE_PERCENT).toInt())

        // TODO: check return value
        mAudioPlayer.setVolume(AudioTrack.getMaxVolume())
        //val F = FloatFFT_1D(1)
        //F.complexForward()
    }

    private fun chirp(phase: Double, f0: Double, f1: Double, t1: Double, samplingFreq: Double) : DoubleArray {
        val k = (f1 - f0) / t1
        val samples = Math.ceil(t1 * samplingFreq).toInt() + 1
        val chirp = DoubleArray(samples)
        val inc = 1 / samplingFreq
        var t = 0.0
        for (index in chirp.indices) {
            if (t <= t1) {
                chirp[index] = Math.sin(phase + 2.0 * Math.PI * (f0 * t + k / 2 * t * t))
                t += inc
            }
        }

        return chirp
    }

    private fun hanningWindow(signal_in: DoubleArray, size: Int): DoubleArray {
        for (i in 0 until size) {
            signal_in[i] = signal_in[i] * 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i.toDouble() / size))
        }
        return signal_in
    }

    private fun padSignal(signal: DoubleArray, length: Int, position: Int): DoubleArray {
        val newSignal = DoubleArray(length)
        val siglen = signal.size
        for (i in 0 until siglen) {
            newSignal[i + position] = signal[i]
        }
        return newSignal

    }

    private fun convertToShort(signal_in: DoubleArray): ShortArray {
        val generatedSnd = ShortArray(signal_in.size)
        for ((idx, dVal) in signal_in.withIndex()) {
            val `val` = (dVal * 32767).toShort()
            generatedSnd[idx] = `val`
        }
        return generatedSnd
    }
}