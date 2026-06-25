/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.net;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Arrays;

import se.lublin.humla.protobuf.MumbleUDP;

/**
 * Bridges Mumble 1.5 protobuf UDP packets to Humla's legacy packet pipeline.
 */
public final class MumbleUDPProtocol {
    public static final byte MESSAGE_AUDIO = 0;
    public static final byte MESSAGE_PING = 1;

    private static final int OPUS_TERMINATOR_MASK = 1 << 13;
    private static final int OPUS_SIZE_MASK = OPUS_TERMINATOR_MASK - 1;

    private MumbleUDPProtocol() {
    }

    public static byte[] encodePing(long timestamp) {
        MumbleUDP.Ping ping = MumbleUDP.Ping.newBuilder()
                .setTimestamp(timestamp)
                .build();
        return prefix(MESSAGE_PING, ping.toByteArray());
    }

    public static byte[] encodeAudioFromLegacy(byte[] legacyPacket, int length) {
        PacketBuffer packet = new PacketBuffer(legacyPacket, length);
        int flags = packet.next();
        int codec = (flags >> 5) & 0x7;
        if (codec != HumlaUDPMessageType.UDPVoiceOpus.ordinal()) {
            return null;
        }

        int target = flags & 0x1F;
        long frameNumber = packet.readLong();
        long opusHeader = packet.readLong();
        int opusSize = (int) (opusHeader & OPUS_SIZE_MASK);
        boolean terminator = (opusHeader & OPUS_TERMINATOR_MASK) != 0;
        if (opusSize <= 0 || opusSize > packet.left()) {
            return null;
        }

        byte[] opusData = packet.dataBlock(opusSize);
        return encodeAudio(target, frameNumber, opusData, terminator);
    }

    public static byte[] decodeAudioToLegacy(byte[] protobufPacket) throws InvalidProtocolBufferException {
        MumbleUDP.Audio audio = MumbleUDP.Audio.parseFrom(unprefix(protobufPacket));
        ByteString opusData = audio.getOpusData();
        if (opusData.isEmpty()) {
            return null;
        }

        byte[] legacyPacket = new byte[1 + 10 + 10 + 10 + opusData.size()];
        PacketBuffer packet = new PacketBuffer(legacyPacket, legacyPacket.length);
        int context = audio.getContext() & 0x1F;
        int flags = (HumlaUDPMessageType.UDPVoiceOpus.ordinal() << 5) | context;
        packet.append(flags);
        packet.writeLong(audio.getSenderSession() & 0xFFFFFFFFL);
        packet.writeLong(audio.getFrameNumber());
        int opusHeader = opusData.size();
        if (audio.getIsTerminator()) {
            opusHeader |= OPUS_TERMINATOR_MASK;
        }
        packet.writeLong(opusHeader);
        packet.append(opusData.toByteArray(), opusData.size());
        int size = packet.size();
        packet.rewind();
        return packet.dataBlock(size);
    }

    public static byte[] decodePingToLegacy(byte[] protobufPacket) throws InvalidProtocolBufferException {
        MumbleUDP.Ping ping = MumbleUDP.Ping.parseFrom(unprefix(protobufPacket));
        byte[] legacyPacket = new byte[9];
        PacketBuffer packet = new PacketBuffer(legacyPacket, legacyPacket.length);
        packet.append((HumlaUDPMessageType.UDPPing.ordinal() << 5) & 0xFF);
        writeBigEndianLong(packet, ping.getTimestamp());
        return legacyPacket;
    }

    private static byte[] encodeAudio(int target, long frameNumber, byte[] opusData, boolean terminator) {
        MumbleUDP.Audio.Builder audio = MumbleUDP.Audio.newBuilder()
                .setTarget(target)
                .setFrameNumber(frameNumber)
                .setOpusData(ByteString.copyFrom(opusData));
        if (terminator) {
            audio.setIsTerminator(true);
        }
        return prefix(MESSAGE_AUDIO, audio.build().toByteArray());
    }

    private static byte[] prefix(byte type, byte[] protobufData) {
        byte[] packet = new byte[protobufData.length + 1];
        packet[0] = type;
        System.arraycopy(protobufData, 0, packet, 1, protobufData.length);
        return packet;
    }

    private static byte[] unprefix(byte[] packet) {
        return Arrays.copyOfRange(packet, 1, packet.length);
    }

    private static void writeBigEndianLong(PacketBuffer packet, long value) {
        packet.append((value >> 56) & 0xFF);
        packet.append((value >> 48) & 0xFF);
        packet.append((value >> 40) & 0xFF);
        packet.append((value >> 32) & 0xFF);
        packet.append((value >> 24) & 0xFF);
        packet.append((value >> 16) & 0xFF);
        packet.append((value >> 8) & 0xFF);
        packet.append(value & 0xFF);
    }
}
