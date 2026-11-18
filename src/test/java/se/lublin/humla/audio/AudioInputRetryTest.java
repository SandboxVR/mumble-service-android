package se.lublin.humla.audio;

import android.media.AudioRecord;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AudioInputRetryTest {
    @Test(timeout = 5000) public void failedReadsBackOffAndSamplingResumesBeforeShutdown() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicLong firstFailure = new AtomicLong();
        CountDownLatch recovered = new CountDownLatch(1);
        try (MockedStatic<AudioRecord> api = mockStatic(AudioRecord.class);
             MockedConstruction<AudioRecord> records = mockConstruction(AudioRecord.class, (record, ctx) -> {
                 when(record.getState()).thenReturn(AudioRecord.STATE_INITIALIZED);
                 when(record.getSampleRate()).thenReturn(48000);
                 when(record.read(any(short[].class), eq(0), anyInt())).thenAnswer(call -> {
                     int attempt = reads.incrementAndGet();
                     if (attempt == 1) firstFailure.set(System.nanoTime());
                     if (attempt == 3) return (Integer) call.getArgument(2);
                     return attempt == 1 ? 0 : AudioRecord.ERROR_INVALID_OPERATION;
                 });
             })) {
            api.when(() -> AudioRecord.getMinBufferSize(anyInt(), anyInt(), anyInt())).thenReturn(1024);
            AudioInput input = new AudioInput((frame, size) -> recovered.countDown(), 1, 48000, null);
            try {
                input.startRecording();
                assertTrue(recovered.await(2, TimeUnit.SECONDS));
                assertTrue(System.nanoTime() - firstFailure.get() >= TimeUnit.MILLISECONDS.toNanos(50));
            } finally {
                input.shutdown();
            }
            assertFalse(input.isRecording());
            verify(records.constructed().get(0)).stop();
            verify(records.constructed().get(0)).release();
        }
    }
}
