public final class ChecksumReference {
    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int SOURCE_OFFSET = 8;
    private static final int ADDRESS_BYTES = 32;

    private ChecksumReference() {}

    public static boolean isValid(
            byte[] packet,
            int packetLength,
            int transportOffset,
            int transportLength,
            int protocol,
            Integer rejectZeroChecksumAt) {
        if (packetLength < IPV6_HEADER_LENGTH || packetLength > packet.length) return false;
        if (transportOffset < IPV6_HEADER_LENGTH || transportOffset > packetLength) return false;
        if (transportLength < 0 || transportLength != packetLength - transportOffset) return false;
        if (protocol < 0 || protocol > 255) return false;
        if (rejectZeroChecksumAt != null) {
            int checksumOffset = rejectZeroChecksumAt;
            if (checksumOffset < 0 || transportLength < 2 || checksumOffset > transportLength - 2) return false;
            int absolute = transportOffset + checksumOffset;
            if ((packet[absolute] & 255) == 0 && (packet[absolute + 1] & 255) == 0) return false;
        }
        return checksumSum(packet, transportOffset, transportLength, protocol) == 65535;
    }

    public static int value(
            byte[] packet,
            int packetLength,
            int transportOffset,
            int transportLength,
            int protocol) {
        if (packetLength < IPV6_HEADER_LENGTH || packetLength > packet.length) throw new IllegalArgumentException();
        if (transportOffset < IPV6_HEADER_LENGTH || transportOffset > packetLength) throw new IllegalArgumentException();
        if (transportLength < 0 || transportLength != packetLength - transportOffset) throw new IllegalArgumentException();
        if (protocol < 0 || protocol > 255) throw new IllegalArgumentException();
        return checksumSum(packet, transportOffset, transportLength, protocol) ^ 65535;
    }

    private static int checksumSum(byte[] packet, int transportOffset, int transportLength, int protocol) {
        int sum = addBytes(0, packet, SOURCE_OFFSET, ADDRESS_BYTES);
        sum = addByte(sum, transportLength >>> 24, true);
        sum = addByte(sum, transportLength >>> 16, false);
        sum = addByte(sum, transportLength >>> 8, true);
        sum = addByte(sum, transportLength, false);
        sum = addByte(sum, 0, true);
        sum = addByte(sum, 0, false);
        sum = addByte(sum, 0, true);
        sum = addByte(sum, protocol, false);
        sum = addBytes(sum, packet, transportOffset, transportLength);
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return sum;
    }

    private static int addBytes(int sum, byte[] data, int offset, int length) {
        for (int index = 0; index < length; index++) {
            sum = addByte(sum, data[offset + index], (index & 1) == 0);
        }
        return sum;
    }

    private static int addByte(int sum, int value, boolean high) {
        sum += (value & 255) << (high ? 8 : 0);
        return (sum & 65535) + (sum >>> 16);
    }
}
