public interface ChecksumRuntime {
    boolean isValid(
            byte[] packet,
            int packetLength,
            int transportOffset,
            int transportLength,
            int protocol,
            Integer rejectZeroChecksumAt);

    int value(
            byte[] packet,
            int packetLength,
            int transportOffset,
            int transportLength,
            int protocol);
}
