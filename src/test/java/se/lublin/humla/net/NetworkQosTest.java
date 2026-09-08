package se.lublin.humla.net;

import org.junit.Test;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import static org.junit.Assert.assertEquals;

public class NetworkQosTest {
    @Test public void udpVoiceSetsDscpAndPreservesEcn() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setTrafficClass(2);
            NetworkQos.voice(socket);
            assertEquals((46 << 2) | 2, socket.getTrafficClass());
        }
    }

    @Test public void tcpSwitchesClassForVoiceFallbackAndRecovery() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket accepted = listener.accept()) {
            NetworkQos.tcp(client, false);
            assertEquals(40, client.getTrafficClass() >> 2);
            NetworkQos.tcp(client, true);
            assertEquals(46, client.getTrafficClass() >> 2);
            NetworkQos.tcp(client, false);
            assertEquals(40, client.getTrafficClass() >> 2);
        }
    }

    @Test public void closedSocketsDoNotTurnQosFailureIntoConnectionFailure() throws Exception {
        DatagramSocket udp = new DatagramSocket();
        udp.close();
        NetworkQos.voice(udp);
        Socket tcp = new Socket();
        tcp.close();
        NetworkQos.tcp(tcp, true);
    }
}
