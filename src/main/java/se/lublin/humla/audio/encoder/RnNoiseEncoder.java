/*
 * Copyright (C) 2026
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

package se.lublin.humla.audio.encoder;

import java.nio.BufferUnderflowException;

import se.lublin.humla.audio.RnNoise;
import se.lublin.humla.exception.NativeAudioException;
import se.lublin.humla.net.PacketBuffer;

/**
 * Wrapper performing RNNoise suppression before handing frames to the nested encoder.
 */
public class RnNoiseEncoder implements IEncoder {
    // Aggressive store/headset profile: preserve speech while crushing low-speech taps/clicks.
    private static final float VOICE_OPEN_PROBABILITY = 0.62f;
    private static final float VOICE_CLOSE_PROBABILITY = 0.42f;
    private static final float NON_SPEECH_GAIN = 0.02f;
    private static final float TRANSIENT_GAIN = 0.005f;
    private static final float GAIN_ATTACK = 0.70f;
    private static final float GAIN_RELEASE = 0.25f;
    private static final int SPEECH_HANGOVER_FRAMES = 8;

    private static final float TRANSIENT_MIN_PEAK = 4500.0f;
    private static final float TRANSIENT_MIN_RMS = 550.0f;
    private static final float TRANSIENT_MIN_CREST_FACTOR = 5.5f;
    private static final float TRANSIENT_SPIKE_RATIO = 3.5f;
    private static final float TRANSIENT_MAX_VOICE_PROBABILITY = 0.50f;

    private IEncoder mEncoder;
    private RnNoise mDenoiser;
    private final int mFrameSize;
    private int mSpeechHangoverFrames;
    private boolean mGateOpen;
    private float mPostGain;
    private float mPreviousRawRms;

    public RnNoiseEncoder(IEncoder encoder, int frameSize) {
        mEncoder = encoder;
        mFrameSize = frameSize;
        mDenoiser = new RnNoise(frameSize);
        mSpeechHangoverFrames = 0;
        mGateOpen = false;
        mPostGain = NON_SPEECH_GAIN;
        mPreviousRawRms = 0.0f;
    }

    @Override
    public int encode(short[] input, int inputSize) throws NativeAudioException {
        if (inputSize != mFrameSize) {
            throw new IllegalArgumentException("RNNoise requires a constant 480-sample frame size.");
        }
        FrameStats rawStats = analyzeFrame(input, inputSize);
        float voiceProbability = mDenoiser.process(input, inputSize);
        applyVoiceGate(input, inputSize, rawStats, voiceProbability);
        return mEncoder.encode(input, inputSize);
    }

    private FrameStats analyzeFrame(short[] input, int inputSize) {
        long sumSquares = 0;
        int peak = 0;

        for (int i = 0; i < inputSize; i++) {
            int sample = input[i];
            int abs = Math.abs(sample);
            peak = Math.max(peak, abs);
            sumSquares += sample * sample;
        }

        float rms = (float) Math.sqrt(sumSquares / (float) inputSize);
        return new FrameStats(peak, rms);
    }

    private void applyVoiceGate(short[] input, int inputSize, FrameStats rawStats,
                                float voiceProbability) {
        boolean hadRecentSpeech = mSpeechHangoverFrames > 0;
        boolean speechDetected = voiceProbability >= VOICE_OPEN_PROBABILITY ||
                (mGateOpen && voiceProbability >= VOICE_CLOSE_PROBABILITY);
        boolean transientDetected = isTransient(rawStats, voiceProbability) && !hadRecentSpeech;

        if (speechDetected) {
            mSpeechHangoverFrames = SPEECH_HANGOVER_FRAMES;
        } else if (mSpeechHangoverFrames > 0) {
            mSpeechHangoverFrames--;
        }

        mGateOpen = speechDetected || mSpeechHangoverFrames > 0;

        float targetGain = mGateOpen ? 1.0f : NON_SPEECH_GAIN;
        if (transientDetected) {
            targetGain = TRANSIENT_GAIN;
            mPostGain = Math.min(mPostGain, TRANSIENT_GAIN);
        }

        float smoothing = targetGain > mPostGain ? GAIN_ATTACK : GAIN_RELEASE;
        mPostGain += (targetGain - mPostGain) * smoothing;

        if (mPostGain < 0.999f) {
            applyGain(input, inputSize, mPostGain);
        }

        mPreviousRawRms = rawStats.rms;
    }

    private boolean isTransient(FrameStats rawStats, float voiceProbability) {
        if (voiceProbability > TRANSIENT_MAX_VOICE_PROBABILITY ||
                rawStats.peak < TRANSIENT_MIN_PEAK ||
                rawStats.rms < TRANSIENT_MIN_RMS) {
            return false;
        }

        float crestFactor = rawStats.peak / Math.max(rawStats.rms, 1.0f);
        boolean sharpImpulse = crestFactor >= TRANSIENT_MIN_CREST_FACTOR;
        boolean suddenSpike = mPreviousRawRms > 0.0f &&
                rawStats.rms >= (mPreviousRawRms * TRANSIENT_SPIKE_RATIO);

        return sharpImpulse || suddenSpike;
    }

    private void applyGain(short[] input, int inputSize, float gain) {
        for (int i = 0; i < inputSize; i++) {
            input[i] = (short) (input[i] * gain);
        }
    }

    private static final class FrameStats {
        private final int peak;
        private final float rms;

        private FrameStats(int peak, float rms) {
            this.peak = peak;
            this.rms = rms;
        }
    }

    @Override
    public int getBufferedFrames() {
        return mEncoder.getBufferedFrames();
    }

    @Override
    public boolean isReady() {
        return mEncoder.isReady();
    }

    @Override
    public void getEncodedData(PacketBuffer packetBuffer) throws BufferUnderflowException {
        mEncoder.getEncodedData(packetBuffer);
    }

    @Override
    public void terminate() throws NativeAudioException {
        mEncoder.terminate();
    }

    public void setEncoder(IEncoder encoder) {
        if (mEncoder != null) {
            mEncoder.destroy();
        }
        mEncoder = encoder;
    }

    @Override
    public void destroy() {
        if (mDenoiser != null) {
            mDenoiser.destroy();
            mDenoiser = null;
        }
        if (mEncoder != null) {
            mEncoder.destroy();
            mEncoder = null;
        }
    }
}
