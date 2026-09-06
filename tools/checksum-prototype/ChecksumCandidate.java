public final class ChecksumCandidate {
    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int ADDRESS_LENGTH = 16;
    private static final int SOURCE_OFFSET = 8;

    private ChecksumCandidate() {}

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
            if (u16(packet, transportOffset + checksumOffset) == 0) return false;
        }
        long sum = wordSum(packet, SOURCE_OFFSET, ADDRESS_LENGTH * 2);
        sum += transportLength >>> 16;
        sum += transportLength & 65535;
        sum += protocol & 255;
        sum += wordSum(packet, transportOffset, transportLength);
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return sum == 65535;
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
        long sum = wordSum(packet, SOURCE_OFFSET, ADDRESS_LENGTH * 2);
        sum += transportLength >>> 16;
        sum += transportLength & 65535;
        sum += protocol & 255;
        sum += wordSum(packet, transportOffset, transportLength);
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return ~((int) sum) & 65535;
    }

    private static long wordSum(byte[] data, int offset, int length) {
        long sum0 = 0;
        long sum1 = 0;
        long sum2 = 0;
        long sum3 = 0;
        int index = offset;
        int end = offset + length;
        while (index + 7 < end) {
            sum0 += ((data[index] & 255) << 8) | (data[index + 1] & 255);
            sum1 += ((data[index + 2] & 255) << 8) | (data[index + 3] & 255);
            sum2 += ((data[index + 4] & 255) << 8) | (data[index + 5] & 255);
            sum3 += ((data[index + 6] & 255) << 8) | (data[index + 7] & 255);
            index += 8;
        }
        long sum = sum0 + sum1 + sum2 + sum3;
        while (index + 1 < end) {
            sum += ((data[index] & 255) << 8) | (data[index + 1] & 255);
            index += 2;
        }
        if (index < end) sum += (data[index] & 255) << 8;
        return sum;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }
}
