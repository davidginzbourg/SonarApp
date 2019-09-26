package com.dginzbourg.sonarapp

import android.Manifest
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.content.pm.PackageManager
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Process
import android.speech.tts.TextToSpeech
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.View
import android.widget.TextView
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import java.io.InputStreamReader
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.*
import android.media.AudioManager
import android.support.design.widget.Snackbar


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var mExecutor = Executors.newCachedThreadPool()
    private lateinit var mTempCalculator: TemperatureCalculator
    private val mListener = Listener()
    private val mTransmitter = Transmitter()
    private val mDistanceAnalyzer = DistanceAnalyzer()
    private val mNoiseFilter = NoiseFilter()
    private var mMLPClassifier = MutableLiveData<MLPClassifier>()
    private lateinit var mTTS: TextToSpeech
    private var mDistanceString = MutableLiveData<String>()
    private lateinit var mDistanceTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        mTempCalculator = TemperatureCalculator(this)
        mTTS = TextToSpeech(this, this)
        TTSParams.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, TTS_VOLUME)
        initMLPClassifier()
        mDistanceTextView = findViewById(R.id.distanceTextView)
        mDistanceString.observe(this, object : Observer<String> {
            override fun onChanged(t: String?) {
                mDistanceTextView.text = t ?: return
            }
        })
        findViewById<View>(R.id.fab).setOnClickListener { _ ->
            getAlertDialog(
                this,
                getString(R.string.help_msg),
                "Help",
                "Dismiss",
                { dialog, _ -> dialog.cancel() }
            )?.show()
        }
        checkVolume()
        Log.d(LOG_TAG, "App started")
    }

    private fun checkVolume() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val musicMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (musicVolume == musicMaxVolume) return

        Snackbar.make(
            findViewById(R.id.mainCoordinatorLayout),
            R.string.increase_volume,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun validatePermissionsGranted(): Boolean {
        for (permission in permissionArray) {
            if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
                continue
            }
            Log.d("permissions request", "Requested permission $permission")
            requestPermissions(permissionArray, 123)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        Log.d("onRequestPermission", "Called")
        if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            getAlertDialog(
                this,
                "Please grant the requested permissions",
                "Grant Permissions",
                "Grant",
                { dialog, _ ->
                    validatePermissionsGranted()
                    dialog.cancel()
                },
                "Close App",
                { _, _ -> finish() }
            )?.show()
        }
        initAndStartTransmissionCycle()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e("TTS", "Initilization Failed!")
            return
        }
        val locale = Locale.getDefault()
        val result = mTTS.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Shouldn't ever happen
            val errorMsg = "The specified Locale ($locale) is not supported."
            Log.e("TTS", errorMsg)
            showErrorMessage(errorMsg)
        }
    }

    private fun initMLPClassifier() {
        mExecutor.submit(
            SonarThread(Runnable {
                Log.d(LOG_TAG, "Loading MLP JSON files...")
                val weightsReader = JsonReader(InputStreamReader(resources.assets.open(MLP_WEIGHTS_FILE)))
                val biasReader = JsonReader(InputStreamReader(resources.assets.open(MLP_BIAS_FILE)))
                val json = Gson()
                val weights = json.fromJson<Array<Array<DoubleArray>>>(
                    weightsReader,
                    Array<Array<DoubleArray>>::class.java
                )
                val bias = json.fromJson<Array<DoubleArray>>(
                    biasReader,
                    Array<DoubleArray>::class.java
                )
                Log.d(LOG_TAG, "Building MLP instance...")
                val clf = MLPClassifier.buildClassifier(weights, bias)
                Log.d(LOG_TAG, "Done building MLP instance.")
                mMLPClassifier.postValue(clf)
            })
        )
    }

    private fun initAndStartTransmissionCycle() {
        try {
            try {
                mTransmitter.init()
            } catch (e: UnsupportedOperationException) {
                showErrorMessage(e.toString())
            }
            mListener.init()
        } catch (e: SonarException) {
            showErrorMessage(e.toString())
        }
        submitNextTransmissionCycle()
    }

    override fun onResume() {
        if (mExecutor.isShutdown) mExecutor = Executors.newCachedThreadPool()
        val permissionsGranted = validatePermissionsGranted()
        if (permissionsGranted) initAndStartTransmissionCycle()
        super.onResume()
    }

    override fun onPause() {
        mExecutor.shutdownNow()
        mTransmitter.stop()
        mTransmitter.mAudioPlayer.release()
        mListener.stop()
        mListener.mAudioRecorder.release()
        mTTS.stop()
        super.onPause()
    }

    override fun onDestroy() {
        mTTS.shutdown()
        super.onDestroy()
    }

    private fun submitNextTransmissionCycle() {
        transmissionCycle++
        val transmissionCycle = SonarThread(Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (mMLPClassifier.value == null || mTTS.isSpeaking) {
                continue
            }

            mListener.mAudioRecorder.startRecording()
            mTransmitter.transmit()
            mListener.listen()
            Log.d(LOG_TAG, "Stopping transmission...")
            mTransmitter.stop()
            val correlation = mNoiseFilter.filterNoise(
                recordedBuffer = mListener.mRecorderBuffer,
                pulseBuffer = mTransmitter.mPlayerBuffer
            )
            if (correlation == null) {
                submitNextTransmissionCycle()
                return@Runnable
            }

            val soundSpeed = 331.3 + 0.606 * mTempCalculator.getTemp()
            val peaksPrediction = mDistanceAnalyzer.analyze(MAX_PEAK_DIST, MIN_PEAK_DIST, correlation, soundSpeed)
            if (peaksPrediction == null) {
                submitNextTransmissionCycle()
                return@Runnable
            }

            val paddedCorrelation = correlation.copyOf(MLP_CC_SIZE)
            val mlpPredictionClass: Int? = mMLPClassifier.value?.predict(paddedCorrelation)
            val mlpPrediction = if (mlpPredictionClass != null) {
                mlpPredictionClass / 10.0
            } else {
                peaksPrediction
            }

            val distanceString = "%.1f".format(0.1 * mlpPrediction + 0.9 * peaksPrediction)
            mDistanceString.postValue(distanceString)

            if (transmissionCycle % 3 == 0) {
                mTTS.speak(distanceString, TextToSpeech.QUEUE_FLUSH, TTSParams, "")
            }

            submitNextTransmissionCycle()
        })
        mExecutor.submit(transmissionCycle)
    }

    private fun showErrorMessage(errorMsg: String) {
        getAlertDialog(
            this,
            errorMsg,
            "Error",
            "Close App",
            { _, _ -> finish() }
        )?.show()
    }

    companion object {
        val permissionArray = arrayOf(
            Manifest.permission.RECORD_AUDIO
        )
        // This was taken from the 'longest_cc' file at SonarApp_utils. This is the CC that the MLP accepts.
        const val MLP_CC_SIZE = 4346
        const val MIN_CHIRP_FREQ = 3000.0
        const val MAX_CHIRP_FREQ = MIN_CHIRP_FREQ
        const val CHIRP_DURATION = 0.01
        const val SAMPLE_RATE = 44100
        const val LOG_TAG = "sonar_app"
        // 10 meters
        const val MAX_PEAK_DIST = 2600
        // half chirp width
        val MIN_PEAK_DIST = (CHIRP_DURATION * SAMPLE_RATE * 0.5).roundToInt()
        val RECORDING_SAMPLES = (0.5 * SAMPLE_RATE).roundToInt()
        // Amount of samples to keep after chirp
        val RECORDING_CUT_OFF = (SAMPLE_RATE * (CHIRP_DURATION + 13.0 / DistanceAnalyzer.BASE_SOUND_SPEED)
                ).roundToInt() * 2
        var transmissionCycle = 0
        val TTSParams = Bundle()
        // TODO: make this customizable by the user
        const val TTS_VOLUME = 0.5f
        const val MLP_WEIGHTS_FILE = "MLPWeights.json"
        const val MLP_BIAS_FILE = "MLPbias.json"
    }
}