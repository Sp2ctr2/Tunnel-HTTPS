import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

public final class KotlinChecksumComparison {
    private static final String CHECKSUM_CLASS = "com.tunnelvpn.app.Ipv6TransportChecksum";
    private static final String CHECKSUM_CLASS_FILE = "com/tunnelvpn/app/Ipv6TransportChecksum.class";
    private static final String ADAPTER_CLASS = "KotlinChecksumAdapter";
    private static final int[] BENCHMARK_SIZES = {1200, 1201, 4096, 4097, 32768, 32769};
    private static final int[] FIXED_LENGTHS = {
        0, 1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 255, 256, 257,
        1159, 1160, 1161, 4055, 4056, 4057, 8959, 8960, 8961,
        32727, 32728, 32729, 65533, 65534, 65535
    };
    private static final int[] OFFSETS = {40, 41, 47, 48, 56, 72, 104};
    private static final int[] PROTOCOLS = {0, 6, 17, 58, 255};
    private static final long SEED = 0x1071A57A5EEDL;
    private static volatile boolean booleanSink;

    private interface BooleanOperation {
        boolean run();
    }

    private record Timing(double nanos, double allocatedBytes) {}

    private static final class RuntimeLoader extends URLClassLoader {
        RuntimeLoader(Path runtimeRoot, Path harnessRoot) throws Exception {
            super(new URL[]{runtimeRoot.toUri().toURL(), harnessRoot.toUri().toURL()},
                KotlinChecksumComparison.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(CHECKSUM_CLASS) || name.equals(ADAPTER_CLASS)) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private KotlinChecksumComparison() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "output directory, mode, baseline class root and candidate class root required");
        }
        Path output = Path.of(args[0]);
        Path baselineRoot = Path.of(args[2]).toAbsolutePath().normalize();
        Path candidateRoot = Path.of(args[3]).toAbsolutePath().normalize();
        Path baselineClass = requireChecksumClass(baselineRoot);
        Path candidateClass = requireChecksumClass(candidateRoot);
        String baselineHash = hash(baselineClass);
        String candidateHash = hash(candidateClass);
        if (baselineHash.equals(candidateHash)) {
            throw new IllegalArgumentException("baseline and candidate Kotlin class hashes are identical");
        }
        Path harnessRoot = Path.of(KotlinChecksumComparison.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        Files.createDirectories(output);
        Files.writeString(output.resolve("class-hashes.csv"),
            "class,sha256\n" +
            "baseline_kotlin," + baselineHash + "\n" +
            "candidate_kotlin," + candidateHash + "\n" +
            "reference," + hash(ChecksumReference.class) + "\n" +
            "adapter," + hash(harnessRoot.resolve("KotlinChecksumAdapter.class")) + "\n" +
            "harness," + hash(KotlinChecksumComparison.class) + "\n");
        Files.writeString(output.resolve("runtime-inputs.csv"),
            "role,class_root,class_file,sha256\n" +
            "baseline," + csv(baselineRoot) + "," + csv(baselineClass) + "," + baselineHash + "\n" +
            "candidate," + csv(candidateRoot) + "," + csv(candidateClass) + "," + candidateHash + "\n");
        try (RuntimeLoader baselineLoader = new RuntimeLoader(baselineRoot, harnessRoot);
             RuntimeLoader candidateLoader = new RuntimeLoader(candidateRoot, harnessRoot)) {
            ChecksumRuntime baseline = loadRuntime(baselineLoader);
            ChecksumRuntime candidate = loadRuntime(candidateLoader);
            if (args[1].equals("differential")) {
                long[] counts = runDifferential(
                    baseline,
                    candidate,
                    output.resolve("differential-summary.csv"));
                System.out.println("differential_cases=" + counts[0]);
                System.out.println("corruption_cases=" + counts[1]);
            } else if (args[1].equals("benchmark")) {
                runDifferential(baseline, candidate, output.resolve("differential-summary.csv"));
                runBenchmark(
                    baseline,
                    candidate,
                    baselineHash,
                    candidateHash,
                    output.resolve("paired-checksum.csv"),
                    output.resolve("paired-summary.csv"));
            } else {
                throw new IllegalArgumentException("mode must be differential or benchmark");
            }
        }
        System.out.println(output.toAbsolutePath());
    }

    private static ChecksumRuntime loadRuntime(RuntimeLoader loader) throws Exception {
        Object adapter = Class.forName(ADAPTER_CLASS, true, loader).getDeclaredConstructor().newInstance();
        return (ChecksumRuntime) adapter;
    }

    private static Path requireChecksumClass(Path root) {
        Path classFile = root.resolve(CHECKSUM_CLASS_FILE);
        if (!Files.isRegularFile(classFile)) {
            throw new IllegalArgumentException("missing Kotlin checksum class: " + classFile);
        }
        return classFile;
    }

    private static long[] runDifferential(
            ChecksumRuntime baseline,
            ChecksumRuntime candidate,
            Path output) throws Exception {
        Random random = new Random(SEED);
        long cases = 0;
        long corruptions = 0;
        for (int offset : OFFSETS) {
            for (int length : FIXED_LENGTHS) {
                for (int protocol : PROTOCOLS) {
                    verifyCase(baseline, candidate, random, offset, length, protocol);
                    cases++;
                    if (length >= 2) corruptions++;
                }
            }
        }
        for (int iteration = 0; iteration < 4096; iteration++) {
            int offset = OFFSETS[random.nextInt(OFFSETS.length)];
            int length;
            int selector = iteration & 15;
            if (selector == 0) {
                length = 65535 - random.nextInt(4);
            } else if (selector < 4) {
                length = random.nextInt(32769);
            } else {
                length = random.nextInt(4098);
            }
            if ((iteration & 1) != (length & 1)) length = Math.max(0, length - 1);
            int protocol = PROTOCOLS[random.nextInt(PROTOCOLS.length)];
            verifyCase(baseline, candidate, random, offset, length, protocol);
            cases++;
            if (length >= 2) corruptions++;
        }
        long policyCases = verifyPolicies(baseline, candidate, random);
        cases += policyCases;
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            writer.write("seed,fixed_and_random_cases,corruption_cases,policy_cases,status\n");
            writer.write(SEED + "," + (cases - policyCases) + "," + corruptions + "," +
                policyCases + ",PASS\n");
        }
        return new long[]{cases, corruptions};
    }

    private static void verifyCase(
            ChecksumRuntime baseline,
            ChecksumRuntime candidate,
            Random random,
            int transportOffset,
            int transportLength,
            int protocol) {
        int packetLength = transportOffset + transportLength;
        byte[] packet = new byte[packetLength + random.nextInt(17)];
        random.nextBytes(packet);
        packet[0] = 0x60;
        int baselineValue = baseline.value(
            packet, packetLength, transportOffset, transportLength, protocol);
        int candidateValue = candidate.value(
            packet, packetLength, transportOffset, transportLength, protocol);
        int referenceValue = ChecksumReference.value(
            packet, packetLength, transportOffset, transportLength, protocol);
        requireEqual(baselineValue, candidateValue, "candidate value");
        requireEqual(baselineValue, referenceValue, "reference value");
        if (transportLength < 2) {
            compareValidity(
                baseline,
                candidate,
                packet,
                packetLength,
                transportOffset,
                transportLength,
                protocol,
                null);
            return;
        }
        int checksumOffset = random.nextInt((transportLength - 2) / 2 + 1) * 2;
        int absoluteChecksum = transportOffset + checksumOffset;
        packet[absoluteChecksum] = 0;
        packet[absoluteChecksum + 1] = 0;
        int checksum = ChecksumReference.value(
            packet, packetLength, transportOffset, transportLength, protocol);
        put16(packet, absoluteChecksum, checksum == 0 ? 65535 : checksum);
        if (!compareValidity(
                baseline,
                candidate,
                packet,
                packetLength,
                transportOffset,
                transportLength,
                protocol,
                checksumOffset)) {
            throw new AssertionError("valid checksum rejected");
        }
        byte[] corrupted = packet.clone();
        int mutableIndex;
        if (random.nextBoolean()) {
            mutableIndex = 8 + random.nextInt(32);
        } else {
            mutableIndex = transportOffset + random.nextInt(transportLength);
        }
        corrupted[mutableIndex] ^= (byte) (1 << random.nextInt(8));
        if (compareValidity(
                baseline,
                candidate,
                corrupted,
                packetLength,
                transportOffset,
                transportLength,
                protocol,
                checksumOffset)) {
            throw new AssertionError("single-bit corruption accepted");
        }
        packet[absoluteChecksum] = 0;
        packet[absoluteChecksum + 1] = 0;
        if (compareValidity(
                baseline,
                candidate,
                packet,
                packetLength,
                transportOffset,
                transportLength,
                protocol,
                checksumOffset)) {
            throw new AssertionError("zero checksum accepted");
        }
    }

    private static boolean compareValidity(
            ChecksumRuntime baseline,
            ChecksumRuntime candidate,
            byte[] packet,
            int packetLength,
            int transportOffset,
            int transportLength,
            int protocol,
            Integer rejectZeroChecksumAt) {
        boolean baselineResult = baseline.isValid(
            packet, packetLength, transportOffset, transportLength, protocol, rejectZeroChecksumAt);
        boolean candidateResult = candidate.isValid(
            packet, packetLength, transportOffset, transportLength, protocol, rejectZeroChecksumAt);
        boolean referenceResult = ChecksumReference.isValid(
            packet, packetLength, transportOffset, transportLength, protocol, rejectZeroChecksumAt);
        if (baselineResult != candidateResult || baselineResult != referenceResult) {
            throw new AssertionError("validity mismatch");
        }
        return baselineResult;
    }

    private static long verifyPolicies(
            ChecksumRuntime baseline,
            ChecksumRuntime candidate,
            Random random) {
        byte[] packet = new byte[128];
        random.nextBytes(packet);
        int[][] cases = {
            {39, 40, -1, 17, -2},
            {129, 40, 89, 17, -2},
            {128, 39, 89, 17, -2},
            {128, 129, -1, 17, -2},
            {128, 40, -1, 17, -2},
            {128, 40, 87, 17, -2},
            {128, 40, 88, -1, -2},
            {128, 40, 88, 256, -2},
            {128, 40, 88, 17, -1},
            {128, 40, 88, 17, 87}
        };
        for (int[] test : cases) {
            Integer reject = test[4] == -2 ? null : test[4];
            compareValidity(
                baseline,
                candidate,
                packet,
                test[0],
                test[1],
                test[2],
                test[3],
                reject);
            boolean baselineThrows = valueThrows(
                baseline, packet, test[0], test[1], test[2], test[3]);
            boolean candidateThrows = valueThrows(
                candidate, packet, test[0], test[1], test[2], test[3]);
            boolean referenceThrows = valueThrowsReference(
                packet, test[0], test[1], test[2], test[3]);
            if (baselineThrows != candidateThrows || baselineThrows != referenceThrows) {
                throw new AssertionError("value policy mismatch");
            }
        }
        return cases.length;
    }

    private static boolean valueThrows(
            ChecksumRuntime runtime,
            byte[] packet,
            int packetLength,
            int offset,
            int length,
            int protocol) {
        try {
            runtime.value(packet, packetLength, offset, length, protocol);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean valueThrowsReference(
            byte[] packet,
            int packetLength,
            int offset,
            int length,
            int protocol) {
        try {
            ChecksumReference.value(packet, packetLength, offset, length, protocol);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void runBenchmark(
            ChecksumRuntime baseline,
            ChecksumRuntime candidate,
            String baselineHash,
            String candidateHash,
            Path rawOutput,
            Path summaryOutput) throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean allocationSupported = bean.isThreadAllocatedMemorySupported();
        if (allocationSupported) bean.setThreadAllocatedMemoryEnabled(true);
        String harnessHash = hash(KotlinChecksumComparison.class);
        try (BufferedWriter raw = Files.newBufferedWriter(rawOutput);
             BufferedWriter summary = Files.newBufferedWriter(summaryOutput)) {
            raw.write("scope,jvm,processors,baseline_class_sha256,candidate_class_sha256," +
                "harness_class_sha256,packet_bytes,transport_bytes,sample,warmup,batch,order," +
                "control_ns_per_operation,baseline_ns_per_operation,candidate_ns_per_operation," +
                "control_allocated_bytes_per_operation,baseline_allocated_bytes_per_operation," +
                "candidate_allocated_bytes_per_operation\n");
            summary.write("packet_bytes,samples,baseline_median_ns,baseline_p95_ns," +
                "candidate_median_ns,candidate_p95_ns,control_median_ns," +
                "paired_speedup_median,candidate_faster_pairs\n");
            for (int size : BENCHMARK_SIZES) {
                byte[] packet = fixture(baseline, candidate, size);
                int transportLength = size - 40;
                BooleanOperation control = () -> packet.length == size;
                BooleanOperation baselineOperation = () -> baseline.isValid(
                    packet, size, 40, transportLength, 6, null);
                BooleanOperation candidateOperation = () -> candidate.isValid(
                    packet, size, 40, transportLength, 6, null);
                double[] baselineMeasured = new double[30];
                double[] candidateMeasured = new double[30];
                double[] controlMeasured = new double[30];
                double[] pairedSpeedup = new double[30];
                int fasterPairs = 0;
                for (int sample = -10; sample < 30; sample++) {
                    boolean baselineFirst = (sample & 1) == 0;
                    Timing controlTiming = measure(bean, allocationSupported, control, 1000);
                    Timing baselineTiming;
                    Timing candidateTiming;
                    if (baselineFirst) {
                        baselineTiming = measure(
                            bean, allocationSupported, baselineOperation, 1000);
                        candidateTiming = measure(
                            bean, allocationSupported, candidateOperation, 1000);
                    } else {
                        candidateTiming = measure(
                            bean, allocationSupported, candidateOperation, 1000);
                        baselineTiming = measure(
                            bean, allocationSupported, baselineOperation, 1000);
                    }
                    raw.write("host-jvm-final-kotlin-checksum-paired," +
                        System.getProperty("java.version") + "," +
                        Runtime.getRuntime().availableProcessors() + "," +
                        baselineHash + "," + candidateHash + "," + harnessHash + "," +
                        size + "," + transportLength + "," + sample + "," +
                        (sample < 0) + ",1000," + (baselineFirst ? "BC" : "CB") + "," +
                        controlTiming.nanos + "," + baselineTiming.nanos + "," +
                        candidateTiming.nanos + "," + controlTiming.allocatedBytes + "," +
                        baselineTiming.allocatedBytes + "," + candidateTiming.allocatedBytes + "\n");
                    if (sample >= 0) {
                        baselineMeasured[sample] = baselineTiming.nanos;
                        candidateMeasured[sample] = candidateTiming.nanos;
                        controlMeasured[sample] = controlTiming.nanos;
                        pairedSpeedup[sample] = baselineTiming.nanos / candidateTiming.nanos;
                        if (candidateTiming.nanos < baselineTiming.nanos) fasterPairs++;
                    }
                }
                summary.write(size + ",30," + median(baselineMeasured) + "," +
                    percentile95(baselineMeasured) + "," + median(candidateMeasured) + "," +
                    percentile95(candidateMeasured) + "," + median(controlMeasured) + "," +
                    median(pairedSpeedup) + "," + fasterPairs + "\n");
            }
        }
    }

    private static Timing measure(
            com.sun.management.ThreadMXBean bean,
            boolean allocations,
            BooleanOperation operation,
            int batch) {
        long thread = Thread.currentThread().getId();
        long beforeBytes = allocations ? bean.getThreadAllocatedBytes(thread) : -1;
        long start = System.nanoTime();
        for (int index = 0; index < batch; index++) booleanSink = operation.run();
        long nanos = System.nanoTime() - start;
        long afterBytes = allocations ? bean.getThreadAllocatedBytes(thread) : -1;
        return new Timing(
            (double) nanos / batch,
            allocations ? (double) (afterBytes - beforeBytes) / batch : -1);
    }

    private static byte[] fixture(
            ChecksumRuntime baseline,
            ChecksumRuntime candidate,
            int size) {
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
        for (int index = 60; index < size; index++) packet[index] = (byte) (index % 251);
        put16(packet, 56, ChecksumReference.value(packet, size, 40, size - 40, 6));
        if (!baseline.isValid(packet, size, 40, size - 40, 6, null)) {
            throw new AssertionError("baseline fixture rejected");
        }
        if (!candidate.isValid(packet, size, 40, size - 40, 6, null)) {
            throw new AssertionError("candidate fixture rejected");
        }
        return packet;
    }

    private static double median(double[] input) {
        double[] values = input.clone();
        Arrays.sort(values);
        return (values[14] + values[15]) / 2;
    }

    private static double percentile95(double[] input) {
        double[] values = input.clone();
        Arrays.sort(values);
        return values[28];
    }

    private static String hash(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException(resource);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes());
            return hex(digest);
        }
    }

    private static String hash(Path path) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String hex(byte[] digest) {
        StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format("%02x", value & 255));
        return text.toString();
    }

    private static String csv(Path path) {
        return "\"" + path.toString().replace("\"", "\"\"") + "\"";
    }

    private static void requireEqual(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + expected + " != " + actual);
    }

    private static void put16(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 8);
        target[offset + 1] = (byte) value;
    }
}
