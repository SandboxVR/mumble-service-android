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
    private IEncoder mEncoder;
    private RnNoise mDenoiser;
    private final int mFrameSize;

    public RnNoiseEncoder(IEncoder encoder, int frameSize) {
        mEncoder = encoder;
        mFrameSize = frameSize;
        mDenoiser = new RnNoise(frameSize);
    }

    @Override
    public int encode(short[] input, int inputSize) throws NativeAudioException {
        if (inputSize != mFrameSize) {
            throw new IllegalArgumentException("RNNoise requires a constant 480-sample frame size.");
        }
        mDenoiser.process(input, inputSize);
        return mEncoder.encode(input, inputSize);
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
