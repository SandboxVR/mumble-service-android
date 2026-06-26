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

package se.lublin.humla.audio;

/**
 * Minimal JNI wrapper around RNNoise.
 */
public class RnNoise {
    private static final int FRAME_SIZE = 480;

    private long mState;

    static {
        System.loadLibrary("jnirnnoise");
    }

    public RnNoise(int frameSize) {
        int nativeFrameSize = getFrameSizeNative();
        if (nativeFrameSize != FRAME_SIZE || frameSize != FRAME_SIZE) {
            throw new IllegalArgumentException("RNNoise requires 480-sample frames at 48000 Hz.");
        }
        mState = createNative();
    }

    /**
     * Denoises one 10 ms frame in place.
     *
     * @return RNNoise's voice probability for this frame.
     */
    public float process(short[] frame, int frameSize) {
        if (mState == 0) {
            throw new IllegalStateException("RNNoise state is destroyed.");
        }
        return processFrameNative(mState, frame, frameSize);
    }

    public void destroy() {
        if (mState != 0) {
            destroyNative(mState);
            mState = 0;
        }
    }

    private static native int getFrameSizeNative();
    private static native long createNative();
    private static native void destroyNative(long state);
    private static native float processFrameNative(long state, short[] frame, int frameSize);
}
