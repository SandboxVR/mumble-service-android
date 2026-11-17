package se.lublin.humla.net;

import android.os.Handler;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;

import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.protocol.HumlaTCPMessageListener;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class HumlaConnectionSynchronizationTest {
    @Test public void serverSyncWaitsForModelHandlersBeforeNotifyingService() throws Exception {
        Fixture f = new Fixture();
        final boolean[] modelReady = {false};
        f.connection.addTCPMessageHandlers(new HumlaTCPMessageListener.Stub() {
            @Override public void messageServerSync(Mumble.ServerSync message) { modelReady[0] = true; }
        });
        doAnswer(invocation -> {
            assertTrue(modelReady[0]);
            assertEquals(42, f.connection.getSession());
            return null;
        }).when(f.listener).onConnectionSynchronized();

        f.receiveServerSync();
        verifyNoInteractions(f.listener);
        assertTrue(modelReady[0]);
        f.queue.remove().run();
        verify(f.listener).onConnectionSynchronized();
    }

    @Test public void disconnectDropsAlreadyQueuedSynchronization() throws Exception {
        Fixture f = new Fixture();
        f.receiveServerSync();
        assertEquals(1, f.queue.size());

        f.connection.disconnect();
        f.queue.remove().run();

        assertFalse(f.connection.isConnected());
        assertFalse(f.connection.isSynchronized());
        verifyNoInteractions(f.listener);
    }

    @Test public void lateTcpCallbacksCannotReviveCancelledConnection() throws Exception {
        Fixture f = new Fixture();
        HumlaTCPMessageListener handler = mock(HumlaTCPMessageListener.class);
        f.connection.addTCPMessageHandlers(handler);
        f.connection.disconnect();

        f.connection.onTCPConnectionEstablished();
        f.receiveServerSync();

        assertFalse(f.connection.isConnected());
        assertFalse(f.connection.isSynchronized());
        assertTrue(f.queue.isEmpty());
        verifyNoInteractions(f.listener, handler);
    }

    private static final class Fixture {
        final HumlaConnection.HumlaConnectionListener listener = mock(HumlaConnection.HumlaConnectionListener.class);
        final HumlaConnection connection = new HumlaConnection(listener);
        final Queue<Runnable> queue = new ArrayDeque<>();

        Fixture() throws Exception {
            Handler handler = mock(Handler.class);
            when(handler.post(any(Runnable.class))).thenAnswer(invocation -> {
                queue.add(invocation.getArgument(0));
                return true;
            });
            setField("mMainHandler", handler);
            setField("mPingExecutorService", mock(ScheduledExecutorService.class));
            setField("mConnected", true);
        }

        void receiveServerSync() {
            byte[] message = Mumble.ServerSync.newBuilder().setSession(42).setMaxBandwidth(64000)
                    .build().toByteArray();
            connection.onTCPMessageReceived(HumlaTCPMessageType.ServerSync, message.length, message);
        }

        void setField(String name, Object value) throws Exception {
            Field field = HumlaConnection.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(connection, value);
        }
    }
}
