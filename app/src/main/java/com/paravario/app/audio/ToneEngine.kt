package com.paravario.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process

/** Streams [ToneSynth] output to a low-latency AudioTrack on its own thread. */
class ToneEngine(private val table: ToneTable = ToneTable.DEFAULT) {
    @Volatile var vario = 0f
    @Volatile var active = false
    @Volatile var alarm = false
    @Volatile var volume = 1f
    @Volatile var climbOn = 0.2
    @Volatile var sinkOn = -2.5
    @Volatile var forcedZone = ToneSynth.ZONE_AUTO

    /** Set when audio could not start, so the caller can show it instead of failing silently. */
    @Volatile var error: String? = null
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        error = null
        thread = Thread({ loop() }, "vario-audio").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    private fun loop() {
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val native = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            val sr = if (native in 8000..96000) native else 48000
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = if (minBuf > 0) minBuf else sr / 5
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            val synth = ToneSynth(sr, table)
            val buf = ShortArray(FRAMES)
            track.play()
            while (running) {
                synth.climbOn = climbOn
                synth.sinkOn = sinkOn
                synth.forcedZone = forcedZone
                synth.render(buf, buf.size, vario.toDouble(), active, alarm, volume.toDouble())
                if (track.write(buf, 0, buf.size) < 0) throw IllegalStateException("AudioTrack write failed")
            }
        } catch (e: Exception) {
            error = e.toString()
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val FRAMES = 256 // about 5 ms per block
    }
}
