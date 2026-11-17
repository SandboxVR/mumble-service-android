package se.lublin.humla.net;

import org.junit.Test;

import java.io.IOException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import se.lublin.humla.util.HumlaException;

import static org.junit.Assert.*;

public class HumlaTLSFailureTest {
    @Test public void missingTrustWrapperHasNoCapturedChain() throws Exception {
        HumlaSSLSocketFactory factory = new HumlaSSLSocketFactory(null, null, null, null, null);
        assertNull(factory.getServerChain());
    }

    @Test public void handshakeFailurePreservesErrorAndClosesConnection() throws Exception {
        final SSLHandshakeException original = new SSLHandshakeException("injected handshake failure");
        final FailingSocket socket = new FailingSocket(original);
        HumlaSSLSocketFactory factory = new HumlaSSLSocketFactory(null, null, null, null, null) {
            @Override public SSLSocket createSocket(String host, int port) { return socket; }
        };
        TestTCP tcp = new TestTCP(factory);
        RecordingListener listener = new RecordingListener();
        tcp.setTCPConnectionListener(listener);

        tcp.run();

        assertTrue(socket.handshakeAttempted);
        assertSame(original, listener.failure.getCause());
        assertEquals(1, listener.failures);
        assertEquals(1, listener.disconnects);
        assertTrue(socket.closed);
        assertTrue(tcp.stopped);
        assertFalse(tcp.isRunning());
    }

    private static class TestTCP extends HumlaTCP {
        boolean stopped;
        TestTCP(HumlaSSLSocketFactory factory) { super(factory); }
        @Override protected void executeOnMainThread(Runnable task) { task.run(); }
        @Override protected void stopThreads() { stopped = true; }
    }

    private static class RecordingListener implements HumlaTCP.TCPConnectionListener {
        HumlaException failure;
        int failures, disconnects;
        @Override public void onTCPConnectionFailed(HumlaException error) {
            failure = error;
            failures++;
        }
        @Override public void onTCPConnectionDisconnect() { disconnects++; }
        @Override public void onTCPConnectionEstablished() { fail("handshake did not succeed"); }
        @Override public void onTLSHandshakeFailed(X509Certificate[] chain) {
            fail("no certificate chain was captured");
        }
        @Override public void onTCPMessageReceived(HumlaTCPMessageType type, int length, byte[] data) {
            fail("no connection was established");
        }
    }

    // No network or TLS server is needed: exercise the real HumlaTCP.run error
    // handler, including socket cleanup, after startHandshake throws.
    private static class FailingSocket extends SSLSocket {
        final SSLHandshakeException failure;
        boolean handshakeAttempted, closed;
        FailingSocket(SSLHandshakeException failure) { this.failure = failure; }
        @Override public void startHandshake() throws IOException {
            handshakeAttempted = true;
            throw failure;
        }
        @Override public void close() { closed = true; }
        @Override public void setKeepAlive(boolean enabled) {}
        @Override public void setTrafficClass(int value) {}
        @Override public int getTrafficClass() { return 0; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) {}
        @Override public String[] getSupportedProtocols() { return new String[0]; }
        @Override public String[] getEnabledProtocols() { return new String[0]; }
        @Override public void setEnabledProtocols(String[] protocols) {}
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {}
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {}
        @Override public void setUseClientMode(boolean mode) {}
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean need) {}
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) {}
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean enabled) {}
        @Override public boolean getEnableSessionCreation() { return true; }
    }
}
