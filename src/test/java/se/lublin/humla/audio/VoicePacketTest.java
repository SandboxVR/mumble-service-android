package se.lublin.humla.audio;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import se.lublin.humla.audio.javacpp.Speex;
import se.lublin.humla.model.User;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.net.PacketBuffer;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class VoicePacketTest {
    private static final HumlaUDPMessageType OPUS = HumlaUDPMessageType.UDPVoiceOpus;
    private static final byte FLAGS = (byte) (OPUS.ordinal() << 5);

    @Test public void truncatedSessionAndSequenceDoNotCrashOrPoisonNextPacket() throws Exception {
        AudioOutput.AudioOutputListener listener = mock(AudioOutput.AudioOutputListener.class);
        User user = mock(User.class);
        when(listener.getUser(42)).thenReturn(user);
        AudioOutput output = new AudioOutput(listener);
        AudioOutputSpeech speech = mock(AudioOutputSpeech.class);
        when(speech.getCodec()).thenReturn(OPUS);
        setField(output, "mRunning", true);
        @SuppressWarnings("unchecked")
        Map<Integer, AudioOutputSpeech> outputs = (Map<Integer, AudioOutputSpeech>) getField(output, "mAudioOutputs");
        outputs.put(42, speech);
        try {
            byte[][] truncated = {
                    {}, {FLAGS}, {FLAGS, (byte) 0xf4, 0, 0}, {FLAGS, (byte) 0xf8},
                    {FLAGS, 42}, {FLAGS, 42, (byte) 0xf4, 0, 0, 0},
                    {FLAGS, 42, (byte) 0xf8, (byte) 0xf4, 0}
            };
            output.queueVoiceData(null, OPUS);
            for (byte[] packet : truncated) output.queueVoiceData(packet, OPUS);
            verify(speech, never()).addFrameToBuffer(any(PacketBuffer.class), anyByte(), anyInt());

            output.queueVoiceData(new byte[] {FLAGS, 42, 7, 2, 1, 2}, OPUS);
            ArgumentCaptor<PacketBuffer> frame = ArgumentCaptor.forClass(PacketBuffer.class);
            verify(speech).addFrameToBuffer(frame.capture(), eq((byte) 0), eq(7));
            assertArrayEquals(new byte[] {2, 1, 2}, frame.getValue().dataBlock(3));
        } finally {
            ((ExecutorService) getField(output, "mDecodeExecutorService")).shutdownNow();
        }
    }

    @Test public void malformedOpusHeadersAndLengthsNeverReachNativeInspection() throws Exception {
        AudioOutputSpeech speech = speech();
        byte[][] malformed = {
                {}, {1}, {(byte) 0xf4, 0}, {(byte) 0xf8, (byte) 0xf4}, // Truncated varints.
                {5, 1}, // Declared payload is longer than the datagram.
                {(byte) 0xfc, 0}, // Negative header.
                {(byte) 0xc0, 0x40, 0, 1}, // Header has undefined bits (0x4000).
                {0, 1}, {(byte) 0xa0, 0} // Preserve existing drop of empty/terminator-only payloads.
        };
        for (byte[] packet : malformed) {
            speech.addFrameToBuffer(new PacketBuffer(packet, packet.length), (byte) 0, 1);
        }
        verify(speech, never()).getPacketSamples(any(byte[].class));
        verifyNoInteractions((Speex.JitterBuffer) getField(speech, "mJitterBuffer"));
    }

    @Test public void invalidNativeSampleCountsAreDroppedBeforeJitterBuffer() throws Exception {
        AudioOutputSpeech speech = speech();
        for (int samples : new int[] {-1, -4, 0, 5761, Integer.MAX_VALUE}) {
            doReturn(samples).when(speech).getPacketSamples(any(byte[].class));
            speech.addFrameToBuffer(new PacketBuffer(new byte[] {1, 0}, 2), (byte) 0, 1);
        }
        verifyNoInteractions((Speex.JitterBuffer) getField(speech, "mJitterBuffer"));
    }

    @Test public void validOpusPayloadAndTerminatorReachJitterBufferUnchanged() throws Exception {
        AudioOutputSpeech speech = speech();
        doReturn(5760).when(speech).getPacketSamples(any(byte[].class));
        byte[] packet = {(byte) 0xa0, 2, 11, 12}; // Header 0x2002: terminator + two bytes.
        List<List<?>> arguments = new ArrayList<>();
        try (MockedConstruction<Speex.JitterBufferPacket> packets = mockConstruction(Speex.JitterBufferPacket.class,
                (created, context) -> arguments.add(new ArrayList<>(context.arguments())))) {
            speech.addFrameToBuffer(new PacketBuffer(packet, packet.length), (byte) 3, 7);

            verify(speech).getPacketSamples(new byte[] {11, 12});
            assertEquals(1, packets.constructed().size());
            verify((Speex.JitterBuffer) getField(speech, "mJitterBuffer")).put(packets.constructed().get(0));
            assertArrayEquals(packet, (byte[]) arguments.get(0).get(0));
            assertEquals(packet.length, arguments.get(0).get(1));
            assertEquals(480 * 7, arguments.get(0).get(2));
            assertEquals(5760, arguments.get(0).get(3));
            assertEquals((byte) 3, ((Integer) arguments.get(0).get(5)).byteValue());
        }
    }

    private static AudioOutputSpeech speech() throws Exception {
        // Exercise the actual parser, replacing only JNI inspection/storage operations.
        AudioOutputSpeech speech = mock(AudioOutputSpeech.class, CALLS_REAL_METHODS);
        setField(speech, "mJitterLock", new Object());
        setField(speech, "mJitterBuffer", mock(Speex.JitterBuffer.class));
        return speech;
    }

    private static Object getField(Object target, String name) throws Exception {
        Class<?> type = target instanceof AudioOutput ? AudioOutput.class : AudioOutputSpeech.class;
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target instanceof AudioOutput ? AudioOutput.class : AudioOutputSpeech.class;
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
