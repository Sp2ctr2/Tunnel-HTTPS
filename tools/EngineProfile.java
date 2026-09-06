import com.tunnelvpn.app.Ipv4PacketNormalizer;
import com.tunnelvpn.app.Ipv6PacketNormalizer;
import com.tunnelvpn.app.Ipv6TransportChecksum;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

public final class EngineProfile {
    private static volatile Object sink;
    private static volatile boolean validSink;
    private interface Operation { void run(); }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("output CSV path required");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean allocationSupported = bean.isThreadAllocatedMemorySupported();
        if (allocationSupported) bean.setThreadAllocatedMemoryEnabled(true);
        Path output = Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            writer.write("scope,jvm,class_sha256,operation,packet_bytes,sample,warmup,batch,ns_per_operation,allocated_bytes_per_operation\n");
            for (int size : new int[]{1200, 4096, 32768}) {
                byte[] v4 = ipv4(size);
                byte[] v6 = ipv6(size);
                var normalizer4 = new Ipv4PacketNormalizer();
                var normalizer6 = new Ipv6PacketNormalizer();
                if (!(normalizer4.process(v4, size) instanceof Ipv4PacketNormalizer.Result.Ready)) {
                    throw new AssertionError("IPv4 fixture rejected");
                }
                if (!(normalizer6.process(v6, size) instanceof Ipv6PacketNormalizer.Result.Ready)) {
                    throw new AssertionError("IPv6 fixture rejected");
                }
                if (!Ipv6TransportChecksum.INSTANCE.isValid(v6, size, 40, size - 40, 6, null)) {
                    throw new AssertionError("IPv6 checksum fixture rejected");
                }
                measure(writer, bean, allocationSupported, "ipv4_normalize", size,
                    hash(Ipv4PacketNormalizer.class), () -> sink = normalizer4.process(v4, size));
                measure(writer, bean, allocationSupported, "ipv6_normalize", size,
                    hash(Ipv6PacketNormalizer.class), () -> sink = normalizer6.process(v6, size));
                measure(writer, bean, allocationSupported, "ipv6_checksum", size,
                    hash(Ipv6TransportChecksum.class), () -> validSink =
                        Ipv6TransportChecksum.INSTANCE.isValid(v6, size, 40, size - 40, 6, null));
                measure(writer, bean, allocationSupported, "array_copy", size,
                    hash(EngineProfile.class), () -> sink = Arrays.copyOf(v6, size));
            }
        }
        System.out.println(output.toAbsolutePath());
    }

    private static void measure(BufferedWriter writer, com.sun.management.ThreadMXBean bean,
            boolean allocations, String name, int size, String classHash, Operation operation) throws Exception {
        int batch = 1000;
        long thread = Thread.currentThread().getId();
        for (int sample = -10; sample < 30; sample++) {
            long beforeBytes = allocations ? bean.getThreadAllocatedBytes(thread) : -1;
            long start = System.nanoTime();
            for (int i = 0; i < batch; i++) operation.run();
            long nanos = System.nanoTime() - start;
            long afterBytes = allocations ? bean.getThreadAllocatedBytes(thread) : -1;
            writer.write("host-jvm-component," + System.getProperty("java.version") + "," + classHash + "," +
                name + "," + size + "," + sample + "," + (sample < 0) + "," + batch + "," +
                ((double) nanos / batch) + "," +
                (allocations ? ((double) (afterBytes - beforeBytes) / batch) : -1) + "\n");
        }
    }

    private static String hash(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException(resource);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes());
            var text = new StringBuilder();
            for (byte value : digest) text.append(String.format("%02x", value & 255));
            return text.toString();
        }
    }

    private static byte[] ipv4(int size) {
        byte[] packet = new byte[size];
        packet[0] = 0x45;
        put16(packet, 2, size);
        packet[8] = 64;
        packet[9] = 6;
        packet[12] = 10;
        packet[15] = 2;
        packet[16] = 93;
        packet[17] = (byte) 184;
        packet[18] = (byte) 216;
        packet[19] = 34;
        put16(packet, 20, 45678);
        put16(packet, 22, 443);
        packet[32] = 0x50;
        packet[33] = 0x10;
        for (int i = 40; i < size; i++) packet[i] = (byte) (i % 251);
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += ((packet[i] & 255) << 8) | (packet[i + 1] & 255);
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        put16(packet, 10, ~sum & 65535);
        return packet;
    }

    private static byte[] ipv6(int size) {
        byte[] packet = new byte[size];
        packet[0] = 0x60;
        put16(packet, 4, size - 40);
        packet[6] = 6;
        packet[7] = 64;
        packet[8] = 0x20;
        packet[9] = 1;
        packet[23] = 2;
        packet[24] = 0x26;
        packet[25] = 6;
        packet[39] = 1;
        put16(packet, 40, 45678);
        put16(packet, 42, 443);
        packet[52] = 0x50;
        packet[53] = 0x10;
        for (int i = 60; i < size; i++) packet[i] = (byte) (i % 251);
        put16(packet, 56, Ipv6TransportChecksum.INSTANCE.value(packet, size, 40, size - 40, 6));
        return packet;
    }

    private static void put16(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 8);
        target[offset + 1] = (byte) value;
    }
}
