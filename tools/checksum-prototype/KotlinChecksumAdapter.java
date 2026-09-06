import com.tunnelvpn.app.Ipv6TransportChecksum;

public final class KotlinChecksumAdapter implements ChecksumRuntime {
    @Override
    public boolean isValid(
            byte[] packet,
            int packetLength,
            int transportOffset,
            int transportLength,
            int protocol,
            Integer rejectZeroChecksumAt) {
        return Ipv6TransportChecksum.INSTANCE.isValid(
            packet,
            packetLength,
            transportOffset,
            transportLength,
            protocol,
            rejectZeroChecksumAt);
    }

    @Override
    public int value(
            byte[] packet,
            int packetLength,
            int transportOffset,
            int transportLength,
            int protocol) {
        return Ipv6TransportChecksum.INSTANCE.value(
            packet,
            packetLength,
            transportOffset,
            transportLength,
            protocol);
    }
}
