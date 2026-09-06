import com.sun.management.ThreadMXBean;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

final class V4TcpAllocation {
    private static volatile long sink;

    private record Measurement(double nanosPerPacket, double allocatedBytesPerPacket) {}

    private static final class ChildFirstLoader extends URLClassLoader {
        ChildFirstLoader(URL url) {
            super(new URL[]{url}, ClassLoader.getSystemClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.tunnelvpn.app.")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            loaded = super.loadClass(name, false);
                        }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class Engine {
        final String label;
        final ChildFirstLoader loader;
        final Object forwarder;
        final MethodHandle handlePacket;
        final MethodHandle closeAll;
        final byte[] ack;

        Engine(
            String label,
            ChildFirstLoader loader,
            Object forwarder,
            MethodHandle handlePacket,
            MethodHandle closeAll,
            byte[] ack
        ) {
            this.label = label;
            this.loader = loader;
            this.forwarder = forwarder;
            this.handlePacket = handlePacket;
            this.closeAll = closeAll;
            this.ack = ack;
        }

        static Engine open(String label, Path classRoot) throws Throwable {
            ChildFirstLoader loader = new ChildFirstLoader(classRoot.toUri().toURL());
            Class<?> function1 = Class.forName("kotlin.jvm.functions.Function1");
            Object unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
            Class<?> scopeType = Class.forName("kotlinx.coroutines.CoroutineScope");
            Object emptyContext = Class.forName("kotlin.coroutines.EmptyCoroutineContext")
                .getField("INSTANCE")
                .get(null);
            AtomicReference<byte[]> lastTunPacket = new AtomicReference<>();
            Object protectSocket = Proxy.newProxyInstance(
                function1.getClassLoader(),
                new Class<?>[]{function1},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (method.getName().equals("invoke")) return Boolean.FALSE;
                    throw new UnsupportedOperationException(method.toString());
                }
            );
            Object writeTunPacket = Proxy.newProxyInstance(
                function1.getClassLoader(),
                new Class<?>[]{function1},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (method.getName().equals("invoke")) {
                        lastTunPacket.set((byte[]) args[0]);
                        return unit;
                    }
                    throw new UnsupportedOperationException(method.toString());
                }
            );
            Object scope = Proxy.newProxyInstance(
                scopeType.getClassLoader(),
                new Class<?>[]{scopeType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (method.getName().equals("getCoroutineContext")) return emptyContext;
                    throw new UnsupportedOperationException(method.toString());
                }
            );

            Class<?> mapperType = Class.forName("com.tunnelvpn.app.TurboDomainMapper", true, loader);
            Class<?> diagnosticsType = Class.forName("com.tunnelvpn.app.DiagnosticsState", true, loader);
            Class<?> forwarderType = Class.forName("com.tunnelvpn.app.TurboTcpForwarder", true, loader);
            Object mapper = mapperType.getDeclaredConstructor().newInstance();
            Object diagnostics = diagnosticsType.getDeclaredConstructor().newInstance();
            Constructor<?> constructor = Arrays.stream(forwarderType.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 18)
                .filter(candidate -> candidate.getParameterTypes()[17].getName()
                    .equals("kotlin.jvm.internal.DefaultConstructorMarker"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TurboTcpForwarder default constructor not found"));
            constructor.setAccessible(true);
            int defaultMask = 0;
            for (int index = 6; index <= 15; index++) {
                if (index != 10) defaultMask |= 1 << index;
            }
            Object forwarder = constructor.newInstance(
                protectSocket,
                mapper,
                diagnostics,
                scope,
                writeTunPacket,
                true,
                false,
                null,
                null,
                null,
                600_000L,
                0,
                null,
                null,
                null,
                0,
                defaultMask,
                null
            );
            Method packetMethod = forwarderType.getMethod("handleIpv4Packet", byte[].class, int.class);
            Method closeMethod = forwarderType.getMethod("closeAll");
            MethodHandle packetHandle = MethodHandles.lookup().unreflect(packetMethod).asType(
                MethodType.methodType(boolean.class, Object.class, byte[].class, int.class)
            );
            MethodHandle closeHandle = MethodHandles.lookup().unreflect(closeMethod).asType(
                MethodType.methodType(void.class, Object.class)
            );
            byte[] client = new byte[]{10, 0, 0, 5};
            byte[] destination = new byte[]{93, (byte) 184, (byte) 216, 34};
            byte[] syn = tcpPacket(client, destination, 50_001, 443, 100, 0, 0x02);
            boolean accepted = (boolean) packetHandle.invokeExact(forwarder, syn, syn.length);
            byte[] synAck = lastTunPacket.get();
            if (!accepted || synAck == null || flags(synAck) != 0x12) {
                closeHandle.invokeExact(forwarder);
                loader.close();
                throw new IllegalStateException(label + " failed to establish benchmark flow");
            }
            long serverInitial = u32(synAck, 24);
            byte[] ack = tcpPacket(
                client,
                destination,
                50_001,
                443,
                101,
                (int) ((serverInitial + 1L) & 0xffffffffL),
                0x10
            );
            lastTunPacket.set(null);
            return new Engine(label, loader, forwarder, packetHandle, closeHandle, ack);
        }

        int run(int iterations) throws Throwable {
            int accepted = 0;
            for (int index = 0; index < iterations; index++) {
                if ((boolean) handlePacket.invokeExact(forwarder, ack, ack.length)) accepted++;
            }
            sink = sink * 31L + accepted;
            return accepted;
        }

        void close() throws Throwable {
            try {
                closeAll.invokeExact(forwarder);
            } finally {
                loader.close();
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 2 || args.length > 5) {
            throw new IllegalArgumentException(
                "usage: V4TcpAllocation BASELINE_CLASSES CANDIDATE_CLASSES [WARMUPS] [SAMPLES] [BATCH]"
            );
        }
        Path baselineRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path candidateRoot = Path.of(args[1]).toAbsolutePath().normalize();
        int warmups = args.length >= 3 ? positive(args[2], "warmups") : 10;
        int samples = args.length >= 4 ? positive(args[3], "samples") : 30;
        int batch = args.length >= 5 ? positive(args[4], "batch") : 50_000;
        Path baselineClass = classFile(baselineRoot);
        Path candidateClass = classFile(candidateRoot);
        String baselineHash = sha256(baselineClass);
        String candidateHash = sha256(candidateClass);
        if (baselineHash.equals(candidateHash)) {
            throw new IllegalArgumentException("baseline and candidate TurboTcpForwarder classes are identical");
        }

        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("ThreadMXBean allocation measurement is unavailable");
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().getId();
        Engine baseline = Engine.open("baseline", baselineRoot);
        Engine candidate = Engine.open("candidate", candidateRoot);
        List<Double> baselineAllocations = new ArrayList<>();
        List<Double> candidateAllocations = new ArrayList<>();
        List<Double> baselineTimes = new ArrayList<>();
        List<Double> candidateTimes = new ArrayList<>();

        try {
            for (int index = 0; index < warmups; index++) {
                if ((index & 1) == 0) {
                    baseline.run(batch);
                    candidate.run(batch);
                } else {
                    candidate.run(batch);
                    baseline.run(batch);
                }
            }
            System.out.println("scope=actual-TurboTcpForwarder.handleIpv4Packet-established-direct-ipv4-ack-host-jvm");
            System.out.println(
                "sample,order,baseline_class_sha256,candidate_class_sha256," +
                    "baseline_handler_ack_ns_per_packet,candidate_handler_ack_ns_per_packet," +
                    "baseline_handler_ack_allocated_bytes_per_packet," +
                    "candidate_handler_ack_allocated_bytes_per_packet"
            );
            for (int sample = 0; sample < samples; sample++) {
                Measurement baselineMeasurement;
                Measurement candidateMeasurement;
                String order;
                if ((sample & 1) == 0) {
                    order = "baseline-candidate";
                    baselineMeasurement = measure(bean, threadId, baseline, batch);
                    candidateMeasurement = measure(bean, threadId, candidate, batch);
                } else {
                    order = "candidate-baseline";
                    candidateMeasurement = measure(bean, threadId, candidate, batch);
                    baselineMeasurement = measure(bean, threadId, baseline, batch);
                }
                baselineAllocations.add(baselineMeasurement.allocatedBytesPerPacket());
                candidateAllocations.add(candidateMeasurement.allocatedBytesPerPacket());
                baselineTimes.add(baselineMeasurement.nanosPerPacket());
                candidateTimes.add(candidateMeasurement.nanosPerPacket());
                System.out.printf(
                    Locale.ROOT,
                    "%d,%s,%s,%s,%.3f,%.3f,%.3f,%.3f%n",
                    sample,
                    order,
                    baselineHash,
                    candidateHash,
                    baselineMeasurement.nanosPerPacket(),
                    candidateMeasurement.nanosPerPacket(),
                    baselineMeasurement.allocatedBytesPerPacket(),
                    candidateMeasurement.allocatedBytesPerPacket()
                );
            }
        } finally {
            Throwable failure = null;
            try {
                baseline.close();
            } catch (Throwable error) {
                failure = error;
            }
            try {
                candidate.close();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }

        double baselineAllocationMedian = median(baselineAllocations);
        double candidateAllocationMedian = median(candidateAllocations);
        double baselineTimeMedian = median(baselineTimes);
        double candidateTimeMedian = median(candidateTimes);
        System.out.printf(
            Locale.ROOT,
            "SUMMARY scope=actual-handler-established-direct-ipv4-ack " +
                "baseline_allocated_bytes_per_packet=%.3f candidate_allocated_bytes_per_packet=%.3f " +
                "allocation_reduction_bytes_per_packet=%.3f allocation_ratio=%.6f " +
                "baseline_ns_per_packet=%.3f candidate_ns_per_packet=%.3f time_ratio=%.6f sink=%d%n",
            baselineAllocationMedian,
            candidateAllocationMedian,
            baselineAllocationMedian - candidateAllocationMedian,
            candidateAllocationMedian / baselineAllocationMedian,
            baselineTimeMedian,
            candidateTimeMedian,
            candidateTimeMedian / baselineTimeMedian,
            sink
        );
    }

    private static Measurement measure(ThreadMXBean bean, long threadId, Engine engine, int batch) throws Throwable {
        long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
        long started = System.nanoTime();
        int accepted = engine.run(batch);
        long elapsed = System.nanoTime() - started;
        long allocated = bean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        if (accepted != batch) throw new IllegalStateException(engine.label + " dropped benchmark packets");
        return new Measurement((double) elapsed / batch, (double) allocated / batch);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "V4TcpAllocationProxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private static Path classFile(Path root) {
        Path path = root.resolve("com/tunnelvpn/app/TurboTcpForwarder.class");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("missing class: " + path);
        return path;
    }

    private static int positive(String value, String label) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw new IllegalArgumentException(label + " must be positive");
        return parsed;
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int middle = sorted.length / 2;
        return (sorted.length & 1) == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte value : digest) output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return output.toString();
    }

    private static byte[] tcpPacket(
        byte[] source,
        byte[] destination,
        int sourcePort,
        int destinationPort,
        int sequence,
        int acknowledgement,
        int flags
    ) {
        byte[] packet = new byte[40];
        packet[0] = 0x45;
        put16(packet, 2, packet.length);
        packet[8] = 64;
        packet[9] = 6;
        System.arraycopy(source, 0, packet, 12, source.length);
        System.arraycopy(destination, 0, packet, 16, destination.length);
        put16(packet, 20, sourcePort);
        put16(packet, 22, destinationPort);
        put32(packet, 24, sequence);
        put32(packet, 28, acknowledgement);
        packet[32] = 0x50;
        packet[33] = (byte) flags;
        put16(packet, 34, 65_535);
        return packet;
    }

    private static int flags(byte[] packet) {
        return packet[33] & 0xff;
    }

    private static long u32(byte[] packet, int offset) {
        return ((packet[offset] & 0xffL) << 24) |
            ((packet[offset + 1] & 0xffL) << 16) |
            ((packet[offset + 2] & 0xffL) << 8) |
            (packet[offset + 3] & 0xffL);
    }

    private static void put16(byte[] packet, int offset, int value) {
        packet[offset] = (byte) ((value >>> 8) & 0xff);
        packet[offset + 1] = (byte) (value & 0xff);
    }

    private static void put32(byte[] packet, int offset, int value) {
        packet[offset] = (byte) ((value >>> 24) & 0xff);
        packet[offset + 1] = (byte) ((value >>> 16) & 0xff);
        packet[offset + 2] = (byte) ((value >>> 8) & 0xff);
        packet[offset + 3] = (byte) (value & 0xff);
    }
}
