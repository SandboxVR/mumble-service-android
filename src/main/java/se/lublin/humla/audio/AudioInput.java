/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Log;

import se.lublin.humla.exception.AudioInitializationException;
import se.lublin.humla.exception.NativeAudioException;
import se.lublin.humla.protocol.AudioHandler;

/**
 * Created by andrew on 23/08/13.
 */
public class AudioInput implements Runnable {
    private static final String TAG = AudioInput.class.getName();

    public static final int[] SAMPLE_RATES = {48000, 44100, 16000, 8000};

    // AudioRecord state
    private AudioInputListener mListener;
    private AudioRecord mAudioRecord;
    private final int mFrameSize;
    private int mAudioSource;

    private Thread mRecordThread;
    private boolean mRecording;

    public AudioInput(AudioInputListener listener, int audioSource, int targetSampleRate, Context context)
            throws NativeAudioException, AudioInitializationException {
        mListener = listener;

        // Attempt to construct an AudioRecord with the target sample rate first.
        // If it fails, keep producing AudioRecord instances until we find one that initializes
        // correctly. Maybe one day Android will let us probe for supported sample rates, as we
        // aren't even guaranteed that 44100hz will work across all devices.
        int[] audioSources = getAudioSourceCandidates(audioSource);
        for (int i = 0; i < SAMPLE_RATES.length + 1; i++) {
            int sampleRate = i == 0 ? targetSampleRate : SAMPLE_RATES[i - 1];
            for (int source : audioSources) {
                try {
                    mAudioRecord = setupAudioRecord(sampleRate, source, context);
                    mAudioSource = source;
                    Log.i(TAG, "Using audio source " + audioSourceName(source) +
                            " at " + sampleRate + " Hz.");
                    break;
                } catch (AudioInitializationException e) {
                    // Continue iteration, probing for a supported source/sample rate.
                }
            }
            if (mAudioRecord != null) {
                break;
            }
        }

        if (mAudioRecord == null) {
            throw new AudioInitializationException("Unable to initialize AudioInput.");
        }

        int sampleRate = getSampleRate();
        // FIXME: does not work properly if 10ms frames cannot be represented as integers
        mFrameSize = (sampleRate * AudioHandler.FRAME_SIZE) / AudioHandler.SAMPLE_RATE;
    }

    private static int[] getAudioSourceCandidates(int audioSource) {
        if (audioSource == MediaRecorder.AudioSource.UNPROCESSED) {
            return new int[] {
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC
            };
        }

        return new int[] { audioSource };
    }

    private static AudioRecord setupAudioRecord(int sampleRate, int audioSource, Context context) throws AudioInitializationException {
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                                                                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0)
            throw new AudioInitializationException("Invalid buffer size returned (unsupported sample rate).");

        AudioRecord audioRecord;
        try {
            audioRecord = new AudioRecord(audioSource, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                                                 AudioFormat.ENCODING_PCM_16BIT, minBufferSize);
        } catch (IllegalArgumentException e) {
            throw new AudioInitializationException(e);
        }

        if(audioRecord.getState() == AudioRecord.STATE_UNINITIALIZED) {
            audioRecord.release();
            throw new AudioInitializationException("AudioRecord failed to initialize!");
        }

        setPreferredInputDevice(audioRecord, context);
        disableAndroidAudioEffects(audioRecord);

        return audioRecord;
    }

    private static void setPreferredInputDevice(AudioRecord audioRecord, Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || context == null) {
            return;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }

        AudioDeviceInfo[] inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo inputDevice : inputDevices) {
            int type = inputDevice.getType();
            if (type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                boolean selected = audioRecord.setPreferredDevice(inputDevice);
                Log.i(TAG, "Preferred input device " + inputDevice.getProductName() +
                        " selected=" + selected);
                return;
            }
        }
    }

    private static void disableAndroidAudioEffects(AudioRecord audioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return;
        }

        int sessionId = audioRecord.getAudioSessionId();
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler aec = AcousticEchoCanceler.create(sessionId);
                if (aec != null) {
                    aec.setEnabled(false);
                    aec.release();
                    Log.i(TAG, "Disabled Android AcousticEchoCanceler.");
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(sessionId);
                if (ns != null) {
                    ns.setEnabled(false);
                    ns.release();
                    Log.i(TAG, "Disabled Android NoiseSuppressor.");
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl agc = AutomaticGainControl.create(sessionId);
                if (agc != null) {
                    agc.setEnabled(false);
                    agc.release();
                    Log.i(TAG, "Disabled Android AutomaticGainControl.");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to disable Android audio effects.", t);
        }
    }

    private static String audioSourceName(int audioSource) {
        switch (audioSource) {
            case MediaRecorder.AudioSource.UNPROCESSED:
                return "UNPROCESSED";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.MIC:
                return "MIC";
            default:
                return String.valueOf(audioSource);
        }
    }

    /**
     * Starts the recording thread.
     * Not thread-safe.
     */
    public void startRecording() {
        mRecording = true;
        mRecordThread = new Thread(this);
        mRecordThread.start();
    }

    /**
     * Stops the record loop after the current iteration, joining it.
     * Not thread-safe.
     */
    public void stopRecording() {
        if(!mRecording) return;
        mRecording = false;
        try {
            mRecordThread.interrupt();
            mRecordThread.join();
            mRecordThread = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Stops the record loop and waits on it to finish.
     * Releases native audio resources.
     * NOTE: It is not safe to call startRecording after.
     */
    public void shutdown() {
        stopRecording();
        if(mAudioRecord != null) {
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }

    public boolean isRecording() {
        return mRecording;
    }

    /**
     * @return the sample rate used by the AudioRecord instance.
     */
    public int getSampleRate() {
        return mAudioRecord.getSampleRate();
    }

    /**
     * @return the frame size used, varying depending on the sample rate selected.
     */
    public int getFrameSize() {
        return mFrameSize;
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

        Log.i(TAG, "started");

        mAudioRecord.startRecording();

        if(mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED)
            return;

        final short[] mAudioBuffer = new short[mFrameSize];
        // We loop when the 'recording' instance var is true instead of checking audio record state because we want to always cleanly shutdown.
        while(mRecording) {
            int shortsRead = mAudioRecord.read(mAudioBuffer, 0, mFrameSize);
            if(shortsRead > 0) {
                mListener.onAudioInputReceived(mAudioBuffer, mFrameSize);
            } else {
                Log.e(TAG, "Error fetching audio! AudioRecord error " + shortsRead);
            }
        }

        mAudioRecord.stop();

        Log.i(TAG, "stopped");
    }

    public interface AudioInputListener {
        void onAudioInputReceived(short[] frame, int frameSize);
    }
}
