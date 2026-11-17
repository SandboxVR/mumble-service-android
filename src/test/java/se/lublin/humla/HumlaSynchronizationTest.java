package se.lublin.humla;

import android.os.PowerManager;

import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import se.lublin.humla.audio.BluetoothScoReceiver;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.exception.NotSynchronizedException;
import se.lublin.humla.model.Server;
import se.lublin.humla.model.User;
import se.lublin.humla.model.WhisperTargetList;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.protocol.AudioHandler;
import se.lublin.humla.protocol.ModelHandler;
import se.lublin.humla.util.HumlaCallbacks;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real service callbacks without Android audio hardware or network sockets. */
public class HumlaSynchronizationTest {
    @Test public void oldSyncCannotInitializeReplacementConnection() throws Exception {
        try (Fixture f = new Fixture()) {
            HumlaConnection old = f.connect();
            HumlaConnection.HumlaConnectionListener oldListener = f.listener();
            HumlaConnection current = f.connect();
            when(current.isConnected()).thenReturn(true);

            // The replacement has TCP, but has not received ServerSync: the reported crash case.
            oldListener.onConnectionSynchronized();
            f.assertNotConnected();

            // Even a synchronized replacement must wait for its own callback.
            f.ready(current);
            oldListener.onConnectionSynchronized();
            f.assertNotConnected();
            verifyNoInteractions(f.audioBuilder);
            verify(current, never()).getSession();
            verify(old).disconnect();

            f.listener().onConnectionSynchronized();
            assertEquals(HumlaService.ConnectionState.CONNECTED, f.service.getConnectionState());
            verify(f.callbacks).onConnected();
        }
    }

    @Test public void oldLifecycleCallbacksCannotTearDownReplacementAudio() throws Exception {
        try (Fixture f = new Fixture()) {
            f.connect();
            HumlaConnection.HumlaConnectionListener oldListener = f.listener();
            HumlaConnection current = f.connect();
            f.ready(current);
            f.listener().onConnectionSynchronized();
            clearInvocations(f.callbacks, f.wakeLock, f.audio, current);

            oldListener.onConnectionEstablished();
            oldListener.onConnectionSynchronized();
            oldListener.onConnectionHandshakeFailed(new X509Certificate[0]);
            oldListener.onConnectionWarning("obsolete warning");
            oldListener.onConnectionDisconnected(null);

            assertEquals(HumlaService.ConnectionState.CONNECTED, f.service.getConnectionState());
            verifyNoInteractions(f.callbacks, f.wakeLock, f.audio, current);
        }
    }

    @Test public void invalidationBetweenGuardAndSessionReadDoesNotPublishConnected() throws Exception {
        try (Fixture f = new Fixture()) {
            HumlaConnection connection = f.connect();
            f.ready(connection);
            when(connection.getSession()).thenThrow(
                    new NotSynchronizedException("Session is set during synchronization"));

            f.listener().onConnectionSynchronized();

            f.assertNotConnected();
            verifyNoInteractions(f.audioBuilder);
        }
    }

    @Test public void cancellationDuringAudioInitializationDisposesUnpublishedAudio() throws Exception {
        try (Fixture f = new Fixture()) {
            HumlaConnection connection = f.connect();
            f.ready(connection);
            when(f.audioBuilder.initialize(f.self, 64000, HumlaUDPMessageType.UDPVoiceOpus, (byte) 0))
                    .thenAnswer(invocation -> {
                        when(connection.isSynchronized()).thenReturn(false);
                        return f.audio;
                    });

            f.listener().onConnectionSynchronized();

            f.assertNotConnected();
            verify(f.audio).shutdown();
            verify(connection, never()).addTCPMessageHandlers(f.audio);
            verify(connection, never()).addUDPMessageHandlers(f.audio);
        }
    }

    @Test public void reconnectDuringAudioInitializationDisposesOnlyOldAudio() throws Exception {
        try (Fixture f = new Fixture()) {
            HumlaConnection connection = f.connect();
            f.ready(connection);
            when(f.audioBuilder.initialize(f.self, 64000, HumlaUDPMessageType.UDPVoiceOpus, (byte) 0))
                    .thenAnswer(invocation -> {
                        f.connect();
                        return f.audio;
                    });

            f.listener().onConnectionSynchronized();

            f.assertNotConnected();
            verify(f.audio).shutdown();
            verify(connection, never()).addTCPMessageHandlers(f.audio);
            verify(connection, never()).addUDPMessageHandlers(f.audio);
        }
    }

    @Test public void validSynchronizationPublishesOnceAndDisconnectCleansUpOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            HumlaConnection connection = f.connect();
            f.ready(connection);
            HumlaConnection.HumlaConnectionListener listener = f.listener();

            listener.onConnectionSynchronized();
            listener.onConnectionSynchronized();

            InOrder order = inOrder(connection, f.audioBuilder, f.wakeLock, f.callbacks);
            order.verify(connection).getSession();
            order.verify(connection).getMaxBandwidth();
            order.verify(connection).getCodec();
            order.verify(f.audioBuilder).initialize(f.self, 64000, HumlaUDPMessageType.UDPVoiceOpus, (byte) 0);
            order.verify(connection).addTCPMessageHandlers(f.audio);
            order.verify(connection).addUDPMessageHandlers(f.audio);
            order.verify(f.wakeLock).acquire();
            order.verify(f.callbacks).onConnected();
            verify(f.wakeLock, times(1)).acquire();
            verify(f.callbacks, times(1)).onConnected();

            when(f.wakeLock.isHeld()).thenReturn(true);
            listener.onConnectionDisconnected(null);
            listener.onConnectionDisconnected(null);
            listener.onConnectionSynchronized();

            assertEquals(HumlaService.ConnectionState.DISCONNECTED, f.service.getConnectionState());
            verify(f.wakeLock, times(1)).release();
            verify(f.audio, times(1)).shutdown();
            verify(connection).removeTCPMessageHandler(f.audio);
            verify(connection).removeUDPMessageHandler(f.audio);
            verify(f.callbacks, times(1)).onDisconnected(null);
        }
    }

    @Test public void replacingActiveConnectionReleasesItsResources() throws Exception {
        try (Fixture f = new Fixture()) {
            HumlaConnection old = f.connect();
            f.ready(old);
            f.listener().onConnectionSynchronized();
            when(f.wakeLock.isHeld()).thenReturn(true);

            f.connect();

            assertEquals(HumlaService.ConnectionState.CONNECTING, f.service.getConnectionState());
            verify(old).disconnect();
            verify(old).removeTCPMessageHandler(f.audio);
            verify(old).removeUDPMessageHandler(f.audio);
            verify(f.audio).shutdown();
            verify(f.wakeLock).release();
        }
    }

    @Test public void audioFailureStillConnectsWhileSessionRemainsValid() throws Exception {
        try (Fixture f = new Fixture()) {
            HumlaConnection connection = f.connect();
            f.ready(connection);
            when(f.audioBuilder.initialize(f.self, 64000, HumlaUDPMessageType.UDPVoiceOpus, (byte) 0))
                    .thenThrow(new AudioException("injected audio failure"));

            f.listener().onConnectionSynchronized();

            assertEquals(HumlaService.ConnectionState.CONNECTED, f.service.getConnectionState());
            verify(f.callbacks).onLogWarning("injected audio failure");
            verify(f.callbacks).onConnected();
            verify(f.wakeLock).acquire();
            verify(connection, never()).addUDPMessageHandlers(any(AudioHandler.class));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HumlaService service = new HumlaService();
        final HumlaCallbacks callbacks = mock(HumlaCallbacks.class);
        final PowerManager.WakeLock wakeLock = mock(PowerManager.WakeLock.class);
        final AudioHandler.Builder audioBuilder = mock(AudioHandler.Builder.class);
        final AudioHandler audio = mock(AudioHandler.class);
        final User self = new User(42, "self");
        final List<HumlaConnection.HumlaConnectionListener> listeners = new ArrayList<>();
        final MockedConstruction<HumlaConnection> connections;

        Fixture() throws Exception {
            setField(service, "mCallbacks", callbacks);
            setField(service, "mWakeLock", wakeLock);
            setField(service, "mAudioBuilder", audioBuilder);
            setField(service, "mWhisperTargetList", new WhisperTargetList());
            setField(service, "mBluetoothReceiver", mock(BluetoothScoReceiver.class));
            setField(service, "mServer", mock(Server.class));
            connections = mockConstruction(HumlaConnection.class, (connection, context) ->
                    listeners.add((HumlaConnection.HumlaConnectionListener) context.arguments().get(0)));
        }

        HumlaConnection connect() {
            service.connect();
            return connections.constructed().get(connections.constructed().size() - 1);
        }

        HumlaConnection.HumlaConnectionListener listener() {
            return listeners.get(listeners.size() - 1);
        }

        void ready(HumlaConnection connection) throws Exception {
            when(connection.isConnected()).thenReturn(true);
            when(connection.isSynchronized()).thenReturn(true);
            when(connection.getSession()).thenReturn(42);
            when(connection.getMaxBandwidth()).thenReturn(64000);
            when(connection.getCodec()).thenReturn(HumlaUDPMessageType.UDPVoiceOpus);
            ModelHandler model = mock(ModelHandler.class);
            when(model.getUser(42)).thenReturn(self);
            setField(service, "mModelHandler", model);
            when(audioBuilder.initialize(self, 64000, HumlaUDPMessageType.UDPVoiceOpus, (byte) 0))
                    .thenReturn(audio);
        }

        void assertNotConnected() {
            assertEquals(HumlaService.ConnectionState.CONNECTING, service.getConnectionState());
            verify(wakeLock, never()).acquire();
            verify(callbacks, never()).onConnected();
        }

        @Override public void close() { connections.close(); }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = HumlaService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
