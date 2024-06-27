package com.example.myislam.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.myislam.Constants
import com.example.myislam.Constants.CHANNEL_ID
import com.example.myislam.Constants.CHANNEL_NAME
import com.example.myislam.Constants.CLOSE_ACTION
import com.example.myislam.Constants.INIT_SERVICE
import com.example.myislam.Constants.NEXT_ACTION
import com.example.myislam.Constants.PLAY_ACTION
import com.example.myislam.Constants.PREVIOUS_ACTION
import com.example.myislam.Constants.RADIO_SERVICE_ID
import com.example.myislam.Constants.START_ACTION
import com.example.myislam.R
import com.example.myislam.api.ApiManager
import com.example.myislam.api.Radio
import com.example.myislam.api.RadioResponse
import com.example.myislam.home.HomeActivity
import com.example.myislam.radio.NotificationRemoteViewHelper.setupClickActions
import com.example.myislam.radio.NotificationRemoteViewHelper.showLoadingProgress
import com.example.myislam.radio.NotificationRemoteViewHelper.showPauseButton
import com.example.myislam.radio.NotificationRemoteViewHelper.showPlayButton
import com.example.myislam.radio.NotificationRemoteViewHelper.showPlayPauseButton
import dagger.hilt.android.AndroidEntryPoint
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

const val LOGGING_TAG = "RadioService"

@AndroidEntryPoint
class RadioPlayerService : Service() {

    private lateinit var notificationRV: RemoteViews
    private var _mediaPlayer: MediaPlayer? = null
    private val mediaPlayer: MediaPlayer get() = _mediaPlayer!!
    private var mediaPlayerAvailable = false
    private lateinit var radiosList: List<Radio>
    private var isCurrentlyPlaying = false
    private var currentRadioIndex = 0
    private var currentRadio: Radio = Radio()

    inner class LocalBinder : Binder() {
        fun getService(): RadioPlayerService {
            return this@RadioPlayerService
        }
    }

    private val iBinder: IBinder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = iBinder


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(LOGGING_TAG, "radio service started")
        intent?.getIntExtra(START_ACTION, -1)?.let { clickAction ->
            when (clickAction) {
                INIT_SERVICE -> {
                    startForegroundServiceWithNotification() // in case started after stopped
                    loadRadios()
                }

                PLAY_ACTION -> playOrPauseRadio()
                NEXT_ACTION -> playNextRadio()
                PREVIOUS_ACTION -> playPreviousRadio()
                CLOSE_ACTION -> stopService()
                else -> Log.d(LOGGING_TAG, "unknown action with code $clickAction")
            }
        }

        return START_STICKY
    }

    private fun stopService() {
        this.stopForeground(true)
        this.stopSelfResult(RADIO_SERVICE_ID)
        radioMediaPlayerContract?.onServiceStopped()
    }

    override fun onCreate() {
        super.onCreate()
        // initialization
        Log.d(LOGGING_TAG, "radio service created")
        startForegroundServiceWithNotification()
    }

    private fun startForegroundServiceWithNotification() {
        try {
            createNotificationChannel()

            notificationRV = createNotificationRemoteView()
            updateNotification()

            startForeground(RADIO_SERVICE_ID, createNotification(notificationRV))
        } catch (e: Exception) {
            Toast.makeText(
                this, "Error occurred: ${e.message}", Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun createNotification(customContent: RemoteViews): Notification {
        val intent = Intent(this, HomeActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this.applicationContext,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.radio)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(customContent)
            .setOnlyAlertOnce(true)

        return notificationBuilder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )

            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RADIO_SERVICE_ID, createNotification(notificationRV))
    }

    private fun createNotificationRemoteView(): RemoteViews {
        RemoteViews(this.packageName, R.layout.notification_collapsed_content).apply {
            val defaultRadioTitle = resources.getString(R.string.radio_default_title)
            setTextViewText(R.id.notification_title, defaultRadioTitle)
            setupClickActions(this@RadioPlayerService)
            return this
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(LOGGING_TAG, "radio service destroyed")
        // releasing resources
        mediaPlayer.release()
        _mediaPlayer = null
    }

    fun playOrPauseRadio() {
        if (isCurrentlyPlaying) {
            mediaPlayer.pause()
            radioMediaPlayerContract?.onPaused(currentRadio)
            isCurrentlyPlaying = false
            notificationRV.showPauseButton()
        } else {

            if (!mediaPlayerAvailable) {
                Toast
                    .makeText(this, "media player not available, refreshing...", Toast.LENGTH_SHORT)
                    .show()
                return
            }

            mediaPlayer.start()
            radioMediaPlayerContract?.onPlayed(currentRadio)
            isCurrentlyPlaying = true
            notificationRV.showPlayButton()
        }

        updateNotification()
    }

    fun playPreviousRadio() {
        mediaPlayerAvailable = false
        notificationRV.showLoadingProgress()
        isCurrentlyPlaying = false
        notificationRV.showPauseButton()
        updateNotification()
        radioMediaPlayerContract?.onLoading()

        currentRadioIndex = if (currentRadioIndex == 0) radiosList.size - 1 else --currentRadioIndex
        playRadioAtCurrentIndex(false)
    }

    fun playNextRadio() {
        mediaPlayerAvailable = false
        notificationRV.showLoadingProgress()
        isCurrentlyPlaying = false
        notificationRV.showPauseButton()
        updateNotification()
        radioMediaPlayerContract?.onLoading()

        currentRadioIndex = if (currentRadioIndex == radiosList.size - 1) 0 else ++currentRadioIndex
        playRadioAtCurrentIndex(true)
    }

    private fun playRadioAtCurrentIndex(isPlayingNext: Boolean) {
        currentRadio = radiosList[currentRadioIndex]
        mediaPlayer.apply {
            reset()
            setDataSource(currentRadio.url)
            prepareAsync()
            setOnPreparedListener {
                mediaPlayerAvailable = true
                notificationRV.setTextViewText(R.id.notification_title, currentRadio.name)
                notificationRV.showPlayPauseButton()

                start()
                if (isPlayingNext) radioMediaPlayerContract?.onNextPlayed(currentRadio)
                else radioMediaPlayerContract?.onPreviousPlayed(currentRadio)

                isCurrentlyPlaying = true
                notificationRV.showPlayButton()
                updateNotification()
            }
        }
    }

    private fun getCurrentLanguageCode(): String {
        return when (resources.configuration.locales[0].language) {
            Constants.ARABIC_LANG_CODE -> Constants.ARABIC_LANG_CODE
            else -> Constants.API_ENGLISH_LANG_CODE
        }
    }

    private fun loadRadios() {
        notificationRV.showLoadingProgress()
        updateNotification()

        ApiManager.getRadiosService()
            .getRadios(language = getCurrentLanguageCode())
            .enqueue(object : Callback<RadioResponse> {
                override fun onResponse(
                    call: Call<RadioResponse>,
                    response: Response<RadioResponse>
                ) {
                    if (response.isSuccessful) {
                        radiosList = response.body()?.radios ?: emptyList()
                        currentRadio = radiosList[currentRadioIndex]
                        notificationRV.showPlayPauseButton()
                        updateNotification()
                        if (_mediaPlayer == null) initMediaPlayer()
                    } else {
                        Log.d(
                            LOGGING_TAG,
                            "radio service error: ${response.errorBody().toString()}"
                        )
                    }
                }

                override fun onFailure(p0: Call<RadioResponse>, p1: Throwable) {
                    Log.d(LOGGING_TAG, p1.message ?: "radio service unknown error")
                }
            })
    }

    private fun initMediaPlayer(name: String? = null, url: String? = null) {
        _mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(url ?: currentRadio.url)
            prepareAsync()
            setOnPreparedListener {
                mediaPlayerAvailable = true

                notificationRV.setTextViewText(R.id.notification_title, name ?: currentRadio.name)
                notificationRV.showPlayPauseButton()

                isCurrentlyPlaying = true
                notificationRV.showPlayButton()
                updateNotification()

                start()
                isCurrentlyPlaying = true
                radioMediaPlayerContract?.onPlayed(currentRadio)
            }
        }
    }

    private var radioMediaPlayerContract: RadioMediaPlayerContract? = null

    fun defineRadioMediaPlayerContract(contract: RadioMediaPlayerContract) {
        radioMediaPlayerContract = contract
    }

    interface RadioMediaPlayerContract {
        fun onPlayed(radio: Radio)
        fun onPaused(radio: Radio)
        fun onNextPlayed(radio: Radio)
        fun onPreviousPlayed(radio: Radio)
        fun onLoading()
        fun onServiceStopped()
    }
}
