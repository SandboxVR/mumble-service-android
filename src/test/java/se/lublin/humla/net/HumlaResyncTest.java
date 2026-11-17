package se.lublin.humla.net;

import android.os.Handler;

import com.google.protobuf.Message;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayDeque;
import java.util.Queue;

import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.protocol.HumlaUDPMessageListener;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class HumlaResyncTest {
    @Test public void connectedResyncSendsEmptyCryptSetup() throws Exception {
        HumlaConnection connection = connected();
        HumlaTCP tcp = mock(HumlaTCP.class);
        setField(connection, "mTCP", tcp);

        connection.resyncCryptState();

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(tcp).sendMessage(message.capture(), eq(HumlaTCPMessageType.CryptSetup));
        assertEquals(Mumble.CryptSetup.getDefaultInstance(), message.getValue());
    }

    @Test public void queuedUdpResyncIsDroppedAfterDisconnect() throws Exception {
        HumlaConnection connection = connected();
        HumlaTCP tcp = mock(HumlaTCP.class);
        setField(connection, "mTCP", tcp);
        Queue<Runnable> callbacks = new ArrayDeque<>();
        Handler handler = mock(Handler.class);
        when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return true;
        });
        CryptState crypt = mock(CryptState.class);
        when(crypt.isValid()).thenReturn(true);
        when(crypt.getLastGoodElapsed()).thenReturn(6000000L);
        when(crypt.getLastRequestElapsed()).thenReturn(6000000L);
        HumlaUDP udp = new HumlaUDP(crypt, connection, handler);
        setField(udp, "mHost", "127.0.0.1");

        // Run the real UDP receive/decrypt/resync path with one injected datagram, no sockets.
        try (MockedConstruction<DatagramSocket> sockets = mockConstruction(DatagramSocket.class,
                (socket, context) -> doAnswer(invocation -> {
                    DatagramPacket packet = invocation.getArgument(0);
                    packet.setLength(5);
                    udp.disconnect(); // End the receive loop after this datagram.
                    return null;
                }).when(socket).receive(any(DatagramPacket.class)))) {
            udp.run();
            assertEquals(1, sockets.constructed().size());
            assertEquals(1, callbacks.size());
        }

        connection.disconnect();
        callbacks.remove().run();

        verify(tcp).disconnect();
        verify(tcp, never()).sendMessage(any(Message.class), any(HumlaTCPMessageType.class));
    }

    @Test public void absentOrCancelledTransportDropsResync() throws Exception {
        HumlaConnection connection = connected();
        connection.resyncCryptState(); // Startup/teardown can leave no TCP object.
        HumlaTCP tcp = mock(HumlaTCP.class);
        setField(connection, "mTCP", tcp);
        setField(connection, "mDisconnectRequested", true);
        connection.resyncCryptState();
        verifyNoInteractions(tcp);
    }

    @Test public void staleAndEmptyUdpPacketsAreNotDispatched() throws Exception {
        HumlaConnection connection = connected();
        HumlaUDPMessageListener listener = mock(HumlaUDPMessageListener.class);
        connection.addUDPMessageHandlers(listener);
        connection.onUDPDataReceived(null);
        connection.onUDPDataReceived(new byte[0]);
        connection.disconnect();
        connection.onUDPDataReceived(new byte[] {(byte) (HumlaUDPMessageType.UDPVoiceOpus.ordinal() << 5)});
        verifyNoInteractions(listener);
    }

    private static HumlaConnection connected() throws Exception {
        HumlaConnection connection = new HumlaConnection(mock(HumlaConnection.HumlaConnectionListener.class));
        setField(connection, "mConnected", true);
        return connection;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
