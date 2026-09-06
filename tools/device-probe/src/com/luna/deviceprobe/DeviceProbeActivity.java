package com.luna.deviceprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class DeviceProbeActivity extends Activity {
    private static final String TAG = "LunaDeviceProbe";
    private static final int DEFAULT_TIMEOUT_MS = 5_000;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_MAX_BODY_BYTES = 128 * 1024 * 1024;
    private static final long MAX_STREAM_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_UDP_BYTES = 65_507;
    private static final int DEFAULT_DURATION_CAP_MS = 120_000;
    private static final int MAX_DURATION_CAP_MS = 120_000;
    private static final int MAX_WARMUP_RUNS = 1_000;
    private static final int MAX_MEASURED_RUNS = 100;
    private static final int MAX_CONCURRENCY = 32;
    private static final String DEFAULT_FIXTURE_HOST = "10.0.2.2";
    private static final String DEFAULT_HTTP_URL =
            "http://10.0.2.2:18080/bytes?size={size}";
    private static final String DEFAULT_DNS_HOST = "";
    private static final String DEFAULT_TLS_HOST = "cloudflare-dns.com";
    private static final String DEFAULT_TLS_ADDRESS = "1.1.1.1";
    private static final String DEFAULT_TLS_PATH = "/";
    private static final int DEFAULT_TLS_TIMEOUT_MS = 1_500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService activityExecutor = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("luna-probe"));
    private TextView output;
    private volatile boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        output = new TextView(this);
        output.setTextSize(13.0f);
        output.setTextIsSelectable(true);
        output.setPadding(24, 24, 24, 24);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(output);
        setContentView(scrollView);
        runFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        runFromIntent(intent);
    }

    @Override
    protected void onDestroy() {
        activityExecutor.shutdownNow();
        super.onDestroy();
    }

    private void runFromIntent(final Intent intent) {
        if (started) {
            append("A probe is already running; ignoring duplicate launch");
            return;
        }
        started = true;
        final Config config = Config.from(intent);
        append("Luna device probe package=" + getPackageName());
        append("args=" + config.describeArgs());
        activityExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runSuite(config);
                } finally {
                    started = false;
                }
            }
        });
    }

    private void runSuite(Config config) {
        Reporter reporter = new Reporter();
        long suiteStart = System.nanoTime();
        reporter.log("START runId=" + safe(config.runId) + " probe=" + config.probes
                + " timeoutMs=" + config.timeoutMs + " durationCapMs=" + config.durationCapMs
                + " warmupRuns=" + config.warmupRuns + " measuredRuns=" + config.measuredRuns
                + " concurrency=" + config.concurrency
                + " allowPublic=" + config.allowPublic);
        logActiveNetworks(reporter, config.runId);

        LinkedHashSet<String> requested = expandProbes(config.probes);
        if (requested.isEmpty() || requested.contains("help")) {
            reporter.log(usage());
            reporter.log("BENCH_DONE runId=" + safe(config.runId) + " status=no-op");
            return;
        }

        int attempted = 0;
        int passed = 0;
        for (String probe : requested) {
            try {
                validateFixtureSize(config.fixtureSize);
                RepeatedCounts counts = runRepeatedProbe(config, reporter, probe);
                attempted += counts.attempted;
                passed += counts.passed;
            } catch (Throwable error) {
                attempted++;
                reporter.error("FAIL " + probe + " error=" + describeThrowable(error));
            }
        }
        reporter.log("SUMMARY runId=" + safe(config.runId) + " attempted=" + attempted
                + " passed=" + passed
                + " failed=" + (attempted - passed)
                + " warmupRuns=" + config.warmupRuns
                + " measuredRuns=" + config.measuredRuns
                + " concurrency=" + config.concurrency
                + " durationMs=" + elapsedMs(suiteStart));
        reporter.log("BENCH_DONE runId=" + safe(config.runId)
                + " status=" + (attempted == passed ? "pass" : "fail"));
    }

    private RepeatedCounts runRepeatedProbe(final Config config, final Reporter reporter,
                                            final String probe) throws Exception {
        int totalIterations = config.warmupRuns + config.measuredRuns;
        int attempted = 0;
        int passed = 0;
        reporter.log(BenchmarkSample.csvHeader(config.runId));
        ExecutorService executor = Executors.newFixedThreadPool(config.concurrency,
                new NamedThreadFactory("luna-bench-probe"));
        try {
            for (int iteration = 0; iteration < totalIterations; iteration++) {
                final String phase = iteration < config.warmupRuns ? "warmup" : "measured";
                long batchStart = System.nanoTime();
                List<Future<BenchmarkSample>> futures = new ArrayList<>();
                for (int slot = 0; slot < config.concurrency; slot++) {
                    final int sampleIteration = iteration;
                    final int sampleSlot = slot;
                    futures.add(executor.submit(new Callable<BenchmarkSample>() {
                        @Override
                        public BenchmarkSample call() {
                            long startedAt = System.currentTimeMillis();
                            long start = System.nanoTime();
                            try {
                                runProbe(config, reporter, probe);
                                return BenchmarkSample.pass(config, probe, phase,
                                        sampleIteration, sampleSlot, startedAt,
                                        elapsedMs(start));
                            } catch (Throwable error) {
                                return BenchmarkSample.fail(config, probe, phase,
                                        sampleIteration, sampleSlot, startedAt,
                                        elapsedMs(start), describeThrowable(error));
                            }
                        }
                    }));
                }

                int batchPassed = 0;
                for (Future<BenchmarkSample> future : futures) {
                    BenchmarkSample sample;
                    try {
                        sample = future.get(config.durationCapMs, TimeUnit.MILLISECONDS);
                    } catch (CancellationException error) {
                        sample = BenchmarkSample.fail(config, probe, phase, iteration,
                                -1, System.currentTimeMillis(), config.durationCapMs,
                                "cancelled");
                    } catch (TimeoutException error) {
                        future.cancel(true);
                        sample = BenchmarkSample.fail(config, probe, phase, iteration,
                                -1, System.currentTimeMillis(), config.durationCapMs,
                                "duration-cap-exceeded");
                    } catch (ExecutionException error) {
                        sample = BenchmarkSample.fail(config, probe, phase, iteration,
                                -1, System.currentTimeMillis(), elapsedMs(batchStart),
                                describeThrowable(error));
                    }
                    attempted++;
                    if (sample.isPass()) {
                        passed++;
                        batchPassed++;
                    }
                    reporter.benchmarkSample(sample);
                }
                reporter.log("BENCH_BATCH_JSON {\"schema\":1,\"record\":\"batch\","
                        + "\"runId\":" + jsonString(config.runId)
                        + ",\"probe\":" + jsonString(probe)
                        + ",\"phase\":" + jsonString(phase)
                        + ",\"iteration\":" + iteration
                        + ",\"concurrency\":" + config.concurrency
                        + ",\"passed\":" + batchPassed
                        + ",\"failed\":" + (config.concurrency - batchPassed)
                        + ",\"durationMs\":" + elapsedMs(batchStart) + "}");
                if (iteration + 1 < totalIterations && config.repeatDelayMs > 0) {
                    Thread.sleep(config.repeatDelayMs);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return new RepeatedCounts(attempted, passed);
    }

    private void runProbe(Config config, Reporter reporter, String probe) throws Exception {
        if ("dns".equals(probe)) {
            probeDns(config, reporter);
        } else if ("dns-wire".equals(probe)) {
            probeDnsWire(config, reporter);
        } else if ("http".equals(probe)) {
            probeHttp(config, reporter);
        } else if ("tls-head".equals(probe)) {
            probeTlsHead(config, reporter);
        } else if ("tcp4".equals(probe)) {
            probeTcp(config, reporter, Family.IPV4);
        } else if ("tcp6".equals(probe)) {
            probeTcp(config, reporter, Family.IPV6);
        } else if ("tcp".equals(probe)) {
            probeTcp(config, reporter, Family.ANY);
        } else if ("udp4".equals(probe)) {
            probeUdp(config, reporter, Family.IPV4);
        } else if ("udp6".equals(probe)) {
            probeUdp(config, reporter, Family.IPV6);
        } else if ("udp".equals(probe)) {
            probeUdp(config, reporter, Family.ANY);
        } else {
            throw new ProbeException("unknown probe token: " + probe);
        }
    }

    private static LinkedHashSet<String> expandProbes(String probes) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (probes == null || probes.trim().isEmpty()) {
            return result;
        }
        for (String raw : probes.split(",")) {
            String token = raw.trim().toLowerCase(Locale.US);
            if (token.isEmpty()) {
                continue;
            }
            if ("all".equals(token)) {
                result.add("dns");
                result.add("http");
                result.add("tcp4");
                result.add("tcp6");
                result.add("udp4");
                result.add("udp6");
            } else {
                result.add(token);
            }
        }
        return result;
    }

    private void probeDns(final Config config, Reporter reporter) throws Exception {
        final String host = require(config.dnsHost, "dns-host");
        requireAllowedHost(host, config.allowPublic, "DNS");
        long start = System.nanoTime();
        List<InetAddress> addresses = timed(new Callable<List<InetAddress>>() {
            @Override
            public List<InetAddress> call() throws Exception {
                return Arrays.asList(InetAddress.getAllByName(host));
            }
        }, config.timeoutMs, "DNS lookup");
        if (addresses.isEmpty()) {
            throw new ProbeException("DNS returned no addresses host=" + safe(host));
        }
        StringBuilder line = new StringBuilder("DNS PASS host=").append(safe(host))
                .append(" addresses=");
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(addresses.get(i).getHostAddress());
        }
        line.append(" durationMs=").append(elapsedMs(start));
        reporter.log(line.toString());
    }

    private void probeDnsWire(Config config, Reporter reporter) throws Exception {
        String host = require(config.dnsWireHost, "dns-wire-host");
        int port = requirePort(config.dnsWirePort, "dns-wire-port");
        String name = require(config.dnsWireName, "dns-wire-name");
        int qtype = dnsTypeCode(config.dnsWireType);
        if (config.expectedRcode < -1 || config.expectedRcode > 15) {
            throw new ProbeException("expected-rcode must be -1 or 0..15");
        }
        requireAllowedHost(host, config.allowPublic, "DNS wire");
        List<InetAddress> addresses = resolveAddresses(host, Family.ANY, config.timeoutMs);
        if (addresses.isEmpty()) {
            throw new ProbeException("DNS wire has no address host=" + safe(host));
        }

        int queryId = (int) ((System.nanoTime() >>> 8) & 0xffff);
        byte[] query = buildDnsQuery(name, qtype, queryId);
        Exception last = null;
        for (InetAddress address : addresses) {
            long start = System.nanoTime();
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(config.timeoutMs);
                socket.connect(address, port);
                socket.send(new DatagramPacket(query, query.length, address, port));
                byte[] buffer = new byte[4096];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                if (response.getLength() < 12) {
                    throw new ProbeException("DNS wire response too short bytes="
                            + response.getLength());
                }
                int responseId = readU16(response.getData(), response.getOffset());
                if (responseId != queryId) {
                    throw new ProbeException("DNS wire transaction id mismatch");
                }
                int flags = readU16(response.getData(), response.getOffset() + 2);
                if ((flags & 0x8000) == 0) {
                    throw new ProbeException("DNS wire response flag not set");
                }
                int rcode = flags & 0x000f;
                String rcodeName = dnsRcodeName(rcode);
                reporter.log("DNS_WIRE OBSERVED host=" + safe(host) + ":" + port
                        + " name=" + safe(name) + " type=" + config.dnsWireType
                        + " rcode=" + rcode + "(" + rcodeName + ")"
                        + " responseBytes=" + response.getLength());
                if (config.expectedRcode >= 0 && config.expectedRcode != rcode) {
                    throw new ProbeException("DNS wire rcode=" + rcode + "(" + rcodeName
                            + ") expected=" + config.expectedRcode + "("
                            + dnsRcodeName(config.expectedRcode) + ")");
                }
                reporter.log("DNS_WIRE PASS rcode=" + rcode + "(" + rcodeName + ")"
                        + " durationMs=" + elapsedMs(start));
                return;
            } catch (Exception error) {
                last = error;
                reporter.log("DNS_WIRE attempt failed remote=" + address.getHostAddress()
                        + ":" + port + " error=" + describeThrowable(error));
            }
        }
        throw new ProbeException("DNS wire failed for all addresses host=" + safe(host), last);
    }

    private static byte[] buildDnsQuery(String name, int qtype, int queryId)
            throws ProbeException {
        ByteArrayOutputStream query = new ByteArrayOutputStream(64);
        writeU16(query, queryId);
        writeU16(query, 0x0100);
        writeU16(query, 1);
        writeU16(query, 0);
        writeU16(query, 0);
        writeU16(query, 0);

        String normalized = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (normalized.isEmpty()) {
            throw new ProbeException("dns-wire-name must not be root");
        }
        String[] labels = normalized.split("\\.", -1);
        int encodedLength = 0;
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                throw new ProbeException("invalid DNS label in dns-wire-name");
            }
            encodedLength += 1 + label.length();
            if (encodedLength > 253) {
                throw new ProbeException("dns-wire-name is too long");
            }
            query.write(label.length());
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            query.write(bytes, 0, bytes.length);
        }
        query.write(0);
        writeU16(query, qtype);
        writeU16(query, 1);
        return query.toByteArray();
    }

    private static int dnsTypeCode(String type) throws ProbeException {
        if ("A".equalsIgnoreCase(type)) {
            return 1;
        }
        if ("AAAA".equalsIgnoreCase(type)) {
            return 28;
        }
        throw new ProbeException("dns-wire-type must be A or AAAA");
    }

    private static String dnsRcodeName(int rcode) {
        switch (rcode) {
            case 0:
                return "NOERROR";
            case 1:
                return "FORMERR";
            case 2:
                return "SERVFAIL";
            case 3:
                return "NXDOMAIN";
            case 4:
                return "NOTIMP";
            case 5:
                return "REFUSED";
            default:
                return "RCODE_" + rcode;
        }
    }

    private static void writeU16(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private void probeHttp(Config config, Reporter reporter) throws Exception {
        String urlText = config.httpUrl;
        if (config.fixtureSize >= 0) {
            urlText = urlText.replace("{size}", Long.toString(config.fixtureSize));
        } else if (urlText.contains("{size}")) {
            throw new ProbeException("http-url contains {size}; provide --ei fixture-size N");
        }
        URL url = new URL(urlText);
        String protocol = url.getProtocol().toLowerCase(Locale.US);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new ProbeException("HTTP probe requires http:// or https:// URL");
        }
        requireAllowedHost(url.getHost(), config.allowPublic, "HTTP");

        long start = System.nanoTime();
        URLConnection raw = url.openConnection();
        if (!(raw instanceof HttpURLConnection)) {
            throw new ProbeException("URL is not an HTTP connection");
        }
        HttpURLConnection connection = (HttpURLConnection) raw;
        connection.setConnectTimeout(config.timeoutMs);
        connection.setReadTimeout(config.timeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Accept", "application/octet-stream,*/*;q=0.1");
        try {
            int status = connection.getResponseCode();
            long contentLength = connection.getContentLengthLong();
            if (status < 200 || status >= 300) {
                throw new ProbeException("HTTP status=" + status + " url=" + redactUrl(url));
            }
            if (config.fixtureSize >= 0 && contentLength >= 0
                    && contentLength != config.fixtureSize) {
                throw new ProbeException("HTTP Content-Length=" + contentLength
                        + " expected=" + config.fixtureSize);
            }

            MessageDigest digest = sha256();
            long bytes = 0;
            InputStream source = new BufferedInputStream(connection.getInputStream());
            byte[] buffer = new byte[16 * 1024];
            try {
                int read;
                while ((read = source.read(buffer)) != -1) {
                    bytes += read;
                    if (bytes > config.maxBodyBytes) {
                        throw new ProbeException("HTTP body exceeded max-body-bytes="
                                + config.maxBodyBytes);
                    }
                    digest.update(buffer, 0, read);
                }
            } finally {
                source.close();
            }
            String actualSha = hex(digest.digest());
            verifyTransfer(config, bytes, actualSha, "HTTP");
            reporter.log("HTTP PASS scheme=" + protocol + " url=" + redactUrl(url)
                    + " status=" + status + " contentLength=" + contentLength
                    + " bytes=" + bytes + " sha256=" + actualSha
                    + " durationMs=" + elapsedMs(start));
        } finally {
            connection.disconnect();
        }
    }

    private void probeTlsHead(Config config, Reporter reporter) throws Exception {
        if (!config.allowPublic) {
            throw new ProbeException("TLS HEAD requires --ez allow-public true");
        }
        String host = require(config.tlsHost, "tls-host");
        String addressText = require(config.tlsAddress, "tls-address");
        String path = require(config.tlsPath, "tls-path");
        validateTlsToken(host, "tls-host", false);
        validateTlsToken(addressText, "tls-address", true);
        if (!path.startsWith("/") || path.length() > 256
                || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new ProbeException("tls-path must be /-prefixed, <=256 bytes, and have no CR/LF");
        }
        requireAllowedHost(host, true, "TLS");
        String addressValue = addressText;
        if (addressValue.startsWith("[") && addressValue.endsWith("]")) {
            addressValue = addressValue.substring(1, addressValue.length() - 1);
        }

        int timeoutMs = Math.min(config.timeoutMs, DEFAULT_TLS_TIMEOUT_MS);
        long start = System.nanoTime();
        String stage = "resolve";
        try {
            InetAddress address = InetAddress.getByName(addressValue);
            reporter.log("TLS_HEAD stage=resolve host=" + safe(host)
                    + " address=" + safe(address.getHostAddress())
                    + " timeoutMs=" + timeoutMs);
            stage = "tcp-connect";
            try (Socket raw = new Socket()) {
                raw.setSoTimeout(timeoutMs);
                raw.connect(new InetSocketAddress(address, 443), timeoutMs);
                reporter.log("TLS_HEAD stage=tcp-connected address="
                        + safe(address.getHostAddress()));

                stage = "tls-handshake";
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket tls = (SSLSocket) factory.createSocket(raw, host, 443, true)) {
                    tls.setSoTimeout(timeoutMs);
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(parameters);
                    reporter.log("TLS_HEAD stage=handshake-start host=" + safe(host));
                    tls.startHandshake();
                    reporter.log("TLS_HEAD stage=handshake-pass protocol="
                            + safe(tls.getSession().getProtocol()));

                    stage = "head-write";
                    String request = "HEAD " + path + " HTTP/1.1\r\n"
                            + "Host: " + host + "\r\n"
                            + "Connection: close\r\n"
                            + "User-Agent: LunaDeviceProbe-TLS/1\r\n\r\n";
                    OutputStream out = tls.getOutputStream();
                    out.write(request.getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    stage = "status-read";
                    int status = readHttpStatusCode(tls.getInputStream());
                    reporter.log("TLS_HEAD PASS host=" + safe(host)
                            + " address=" + safe(address.getHostAddress())
                            + " path=" + safe(path) + " status=" + status
                            + " durationMs=" + elapsedMs(start));
                }
            }
        } catch (Exception error) {
            reporter.log("TLS_HEAD FAIL stage=" + stage + " host=" + safe(host)
                    + " address=" + safe(addressText) + " exception="
                    + describeThrowable(error) + " durationMs=" + elapsedMs(start));
            throw error;
        }
    }

    private static void validateTlsToken(String value, String name, boolean allowColon)
            throws ProbeException {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf(' ') >= 0 || value.indexOf('/') >= 0
                || (!allowColon && value.indexOf(':') >= 0)) {
            throw new ProbeException(name + " contains an unsafe header character");
        }
    }

    private static int readHttpStatusCode(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int value = input.read();
            if (value < 0) {
                break;
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                if (line.size() >= 4096) {
                    throw new IOException("HTTP status line exceeded 4096 bytes");
                }
                line.write(value);
            }
        }
        String statusLine = new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
        int firstSpace = statusLine.indexOf(' ');
        int secondSpace = firstSpace < 0 ? -1 : statusLine.indexOf(' ', firstSpace + 1);
        if (!statusLine.startsWith("HTTP/") || firstSpace < 0) {
            throw new IOException("invalid HTTP status line");
        }
        String codeText = statusLine.substring(firstSpace + 1,
                secondSpace < 0 ? statusLine.length() : secondSpace);
        try {
            return Integer.parseInt(codeText);
        } catch (NumberFormatException error) {
            throw new IOException("invalid HTTP status code", error);
        }
    }

    private void probeTcp(Config config, Reporter reporter, Family family) throws Exception {
        String host = require(config.tcpHost, "tcp-host");
        int port = requirePort(config.tcpPort, "tcp-port");
        requireAllowedHost(host, config.allowPublic, "TCP");
        List<InetAddress> addresses = resolveAddresses(host, family, config.timeoutMs);
        if (addresses.isEmpty()) {
            throw new ProbeException("TCP has no address for family=" + family);
        }

        byte[] payload = config.tcpPayload;
        long payloadBytes = payload == null ? 0 : payload.length;
        if (config.fixtureSize >= 0) {
            payload = null;
            payloadBytes = config.fixtureSize;
        }
        long expectedReplyBytes = config.tcpReadBytes;
        if (config.fixtureSize >= 0) {
            expectedReplyBytes = config.fixtureSize;
        }

        if (config.tcpSlowRead) {
            probeTcpSlowRead(config, reporter, family, addresses, payload, payloadBytes,
                    expectedReplyBytes);
            return;
        }

        Exception last = null;
        for (InetAddress address : addresses) {
            long start = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.setSoTimeout(config.timeoutMs);
                socket.connect(new InetSocketAddress(address, port), config.timeoutMs);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                if (payload != null && payload.length > 0) {
                    out.write(payload);
                    out.flush();
                } else if (config.fixtureSize >= 0) {
                    writeFixture(out, payloadBytes);
                    out.flush();
                }
                if (config.tcpShutdownOutput) {
                    socket.shutdownOutput();
                }

                long replyBytes = 0;
                String replySha = "";
                if (expectedReplyBytes > 0) {
                    MessageDigest digest = sha256();
                    InputStream in = new BufferedInputStream(socket.getInputStream());
                    byte[] buffer = new byte[16 * 1024];
                    while (replyBytes < expectedReplyBytes) {
                        int want = (int) Math.min(buffer.length,
                                expectedReplyBytes - replyBytes);
                        int read = in.read(buffer, 0, want);
                        if (read == -1) {
                            throw new IOException("TCP EOF after " + replyBytes
                                    + " of " + expectedReplyBytes + " bytes");
                        }
                        replyBytes += read;
                        digest.update(buffer, 0, read);
                    }
                    replySha = hex(digest.digest());
                    if (config.fixtureSize >= 0) {
                        verifyTransfer(config, replyBytes, replySha, "TCP echo");
                    }
                }
                reporter.log("TCP PASS family=" + family.name().toLowerCase(Locale.US)
                        + " remote=" + address.getHostAddress() + ":" + port
                        + " sentBytes=" + payloadBytes
                        + " replyBytes=" + replyBytes
                        + " shutdownOutput=" + config.tcpShutdownOutput
                        + (replySha.isEmpty() ? "" : " sha256=" + replySha)
                        + " durationMs=" + elapsedMs(start));
                return;
            } catch (Exception error) {
                last = error;
                reporter.log("TCP attempt failed remote=" + address.getHostAddress()
                        + ":" + port + " error=" + describeThrowable(error));
            }
        }
        throw new ProbeException("TCP failed for all addresses host=" + safe(host), last);
    }

    private void probeTcpSlowRead(Config config, Reporter reporter, Family family,
                                  List<InetAddress> addresses, byte[] payload, long payloadBytes,
                                  long expectedReplyBytes) throws Exception {
        if (config.fixtureSize < 0 || payloadBytes <= 0 || expectedReplyBytes <= 0) {
            throw new ProbeException("tcp-slow-read requires --ei fixture-size N > 0");
        }
        Exception last = null;
        for (InetAddress address : addresses) {
            long start = System.nanoTime();
            Socket socket = null;
            ExecutorService writerExecutor = Executors.newSingleThreadExecutor(
                    new NamedThreadFactory("luna-probe-tcp-writer"));
            Future<Void> writer = null;
            try {
                socket = new Socket();
                socket.setSoTimeout(config.timeoutMs);
                socket.connect(new InetSocketAddress(address, config.tcpPort), config.timeoutMs);
                final Socket connected = socket;
                final byte[] sendBytes = payload;
                final long sendByteCount = payloadBytes;
                writer = writerExecutor.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        OutputStream out = new BufferedOutputStream(
                                connected.getOutputStream());
                        if (sendBytes != null) {
                            out.write(sendBytes);
                        } else {
                            writeFixture(out, sendByteCount);
                        }
                        out.flush();
                        if (config.tcpShutdownOutput) {
                            connected.shutdownOutput();
                        }
                        return null;
                    }
                });

                MessageDigest digest = sha256();
                InputStream in = new BufferedInputStream(socket.getInputStream());
                byte[] buffer = new byte[config.slowReadChunkBytes];
                long replyBytes = 0;
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs);
                while (replyBytes < expectedReplyBytes) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new TimeoutException("TCP slow-read timed out after "
                                + config.timeoutMs + "ms");
                    }
                    int readTimeout = (int) Math.max(1, Math.min(config.timeoutMs,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    socket.setSoTimeout(readTimeout);
                    int want = (int) Math.min(buffer.length, expectedReplyBytes - replyBytes);
                    int read = in.read(buffer, 0, want);
                    if (read == -1) {
                        throw new IOException("TCP slow-read EOF after " + replyBytes
                                + " of " + expectedReplyBytes + " bytes");
                    }
                    replyBytes += read;
                    digest.update(buffer, 0, read);
                    if (config.slowReadDelayMs > 0) {
                        Thread.sleep(config.slowReadDelayMs);
                    }
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("TCP slow-read writer timed out after "
                            + config.timeoutMs + "ms");
                }
                writer.get(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)),
                        TimeUnit.MILLISECONDS);
                String replySha = hex(digest.digest());
                verifyTransfer(config, replyBytes, replySha, "TCP slow-read echo");
                reporter.log("TCP_PRESSURE PASS family="
                        + family.name().toLowerCase(Locale.US)
                        + " remote=" + address.getHostAddress() + ":" + config.tcpPort
                        + " sentBytes=" + payloadBytes + " replyBytes=" + replyBytes
                        + " sha256=" + replySha + " slowReadChunk="
                        + config.slowReadChunkBytes + " slowReadDelayMs="
                        + config.slowReadDelayMs + " shutdownOutput="
                        + config.tcpShutdownOutput + " durationMs=" + elapsedMs(start));
                return;
            } catch (Exception error) {
                last = error;
                reporter.log("TCP_PRESSURE attempt failed remote=" + address.getHostAddress()
                        + ":" + config.tcpPort + " error=" + describeThrowable(error));
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                if (writer != null && !writer.isDone()) {
                    writer.cancel(true);
                }
                writerExecutor.shutdownNow();
            }
        }
        throw new ProbeException("TCP slow-read failed for all addresses", last);
    }

    private void probeUdp(Config config, Reporter reporter, Family family) throws Exception {
        String host = require(config.udpHost, "udp-host");
        int port = requirePort(config.udpPort, "udp-port");
        requireAllowedHost(host, config.allowPublic, "UDP");
        List<InetAddress> addresses = resolveAddresses(host, family, config.timeoutMs);
        if (addresses.isEmpty()) {
            throw new ProbeException("UDP has no address for family=" + family);
        }

        byte[] payload = config.udpPayload;
        if (config.fixtureSize >= 0) {
            if (config.fixtureSize > MAX_UDP_BYTES) {
                throw new ProbeException("UDP fixture-size must be <= " + MAX_UDP_BYTES);
            }
            payload = fixtureBytes((int) config.fixtureSize);
        }
        if (payload == null || payload.length == 0) {
            payload = "luna-device-probe-v1".getBytes(StandardCharsets.UTF_8);
        }

        Exception last = null;
        for (InetAddress address : addresses) {
            long start = System.nanoTime();
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(config.timeoutMs);
                socket.connect(address, port);
                DatagramPacket request = new DatagramPacket(payload, payload.length,
                        address, port);
                socket.send(request);

                byte[] receiveBuffer = new byte[Math.max(payload.length, 64 * 1024)];
                DatagramPacket response = new DatagramPacket(receiveBuffer,
                        receiveBuffer.length);
                socket.receive(response);
                byte[] reply = Arrays.copyOfRange(response.getData(), response.getOffset(),
                        response.getOffset() + response.getLength());
                String replySha = hex(sha256(reply));
                if (config.fixtureSize >= 0) {
                    verifyTransfer(config, reply.length, replySha, "UDP echo");
                }
                reporter.log("UDP PASS family=" + family.name().toLowerCase(Locale.US)
                        + " remote=" + address.getHostAddress() + ":" + port
                        + " sentBytes=" + payload.length + " replyBytes=" + reply.length
                        + " sha256=" + replySha + " durationMs=" + elapsedMs(start));
                return;
            } catch (Exception error) {
                last = error;
                reporter.log("UDP attempt failed remote=" + address.getHostAddress()
                        + ":" + port + " error=" + describeThrowable(error));
            }
        }
        throw new ProbeException("UDP failed for all addresses host=" + safe(host), last);
    }

    private static List<InetAddress> resolveAddresses(String host, Family family,
                                                       int timeoutMs) throws Exception {
        List<InetAddress> all = timed(new Callable<List<InetAddress>>() {
            @Override
            public List<InetAddress> call() throws Exception {
                return Arrays.asList(InetAddress.getAllByName(host));
            }
        }, timeoutMs, "address lookup");
        List<InetAddress> selected = new ArrayList<>();
        for (InetAddress address : all) {
            if (family == Family.IPV4 && !(address instanceof Inet4Address)) {
                continue;
            }
            if (family == Family.IPV6 && !(address instanceof Inet6Address)) {
                continue;
            }
            selected.add(address);
        }
        return selected;
    }

    private static void verifyTransfer(Config config, long actualBytes, String actualSha,
                                       String label) throws ProbeException {
        if (config.fixtureSize >= 0 && actualBytes != config.fixtureSize) {
            throw new ProbeException(label + " bytes=" + actualBytes
                    + " expected=" + config.fixtureSize);
        }
        if (config.expectedBytes >= 0 && actualBytes != config.expectedBytes) {
            throw new ProbeException(label + " bytes=" + actualBytes
                    + " expected=" + config.expectedBytes);
        }
        if (config.expectedSha256 != null
                && !config.expectedSha256.equalsIgnoreCase(actualSha)) {
            throw new ProbeException(label + " sha256=" + actualSha
                    + " expected=" + config.expectedSha256);
        }
    }

    private void logActiveNetworks(Reporter reporter, String runId) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            Network active = manager == null ? null : manager.getActiveNetwork();
            reporter.log("ACTIVE_NETWORK runId=" + safe(runId) + " value="
                    + (active == null ? "none" : active));
            if (manager != null && active != null) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(active);
                LinkProperties links = manager.getLinkProperties(active);
                reporter.log("ACTIVE_CAPABILITIES runId=" + safe(runId) + " value="
                        + (caps == null ? "none" : caps));
                reporter.log("ACTIVE_LINKS runId=" + safe(runId) + " value="
                        + (links == null ? "none" : links));
            }
        } catch (RuntimeException error) {
            reporter.log("ACTIVE_NETWORK runId=" + safe(runId) + " unavailable error="
                    + describeThrowable(error));
        }
    }

    private void append(final String message) {
        final String line = safe(message);
        Log.i(TAG, line);
        if (output == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                output.append(line);
                output.append("\n");
            }
        });
    }

    private static String usage() {
        return "Usage: --es probe dns|dns-wire|http|tcp4|tcp6|udp4|udp6|all "
                + "with --es dns-host HOST, --es dns-wire-name NAME, --es http-url URL, "
                + "--el fixture-size N, --ei warmup-runs N, --ei measured-runs N, "
                + "--ei concurrency N. "
                + "Defaults: fixture host 10.0.2.2, ports 18080/18081/18082. "
                + "Use --ez allow-public true only for intentional public traffic. "
                + "Benchmark records are emitted as BENCH_SAMPLE_JSON and BENCH_SAMPLE_CSV.";
    }

    private static String require(String value, String name) throws ProbeException {
        if (value == null || value.trim().isEmpty()) {
            throw new ProbeException("missing --es " + name);
        }
        return value.trim();
    }

    private static int requirePort(int port, String name) throws ProbeException {
        if (port < 1 || port > 65_535) {
            throw new ProbeException("invalid " + name + "=" + port);
        }
        return port;
    }

    private static void requireAllowedHost(String host, boolean allowPublic, String kind)
            throws ProbeException {
        if (!allowPublic && !isLocalHost(host)) {
            throw new ProbeException(kind + " host is not local: " + safe(host)
                    + "; pass --ez allow-public true explicitly");
        }
    }

    private static boolean isLocalHost(String original) {
        if (original == null) {
            return false;
        }
        String host = original.trim().toLowerCase(Locale.US);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if ("localhost".equals(host) || host.endsWith(".local")
                || host.endsWith(".test") || host.endsWith(".invalid")) {
            return true;
        }
        if (host.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(host);
                return address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || host.startsWith("fc") || host.startsWith("fd");
            } catch (Exception ignored) {
                return false;
            }
        }
        int[] octets = parseIpv4(host);
        if (octets == null) {
            return false;
        }
        return octets[0] == 10 || octets[0] == 127 || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    private static int[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] result = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                result[i] = Integer.parseInt(parts[i]);
                if (result[i] < 0 || result[i] > 255) {
                    return null;
                }
            }
            return result;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static void validateFixtureSize(long size) throws ProbeException {
        if (size < -1 || size > MAX_STREAM_BYTES) {
            throw new ProbeException("fixture-size must be -1.." + MAX_STREAM_BYTES);
        }
    }

    private static byte[] fixtureBytes(int size) throws ProbeException {
        if (size < 0 || size > MAX_UDP_BYTES) {
            throw new ProbeException("UDP fixture-size must be 0.." + MAX_UDP_BYTES);
        }
        byte[] result = new byte[size];
        for (int i = 0; i < size; i++) {
            result[i] = (byte) (i % 251);
        }
        return result;
    }

    private static void writeFixture(OutputStream output, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long offset = 0;
        while (offset < size) {
            int count = (int) Math.min(buffer.length, size - offset);
            for (int i = 0; i < count; i++) {
                buffer[i] = (byte) ((offset + i) % 251);
            }
            output.write(buffer, 0, count);
            offset += count;
        }
    }

    private static <T> T timed(Callable<T> work, int timeoutMs, String label) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
                new NamedThreadFactory("luna-probe-timeout"));
        Future<T> future = executor.submit(work);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new TimeoutException(label + " timed out after " + timeoutMs + "ms");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw error;
        } finally {
            executor.shutdownNow();
        }
    }

    private static MessageDigest sha256() throws ProbeException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new ProbeException("SHA-256 unavailable", error);
        }
    }

    private static byte[] sha256(byte[] data) throws ProbeException {
        MessageDigest digest = sha256();
        digest.update(data);
        return digest.digest();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String redactUrl(URL url) {
        String host = url.getHost();
        if (host == null || host.isEmpty()) {
            host = "?";
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        String port = url.getPort() >= 0 ? ":" + url.getPort() : "";
        String path = url.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return url.getProtocol() + "://" + host + port + path;
    }

    private static String describeThrowable(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getClass() == ExecutionException.class) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ":" + safe(message));
    }

    private static String safe(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '"' || character == '\\') {
                result.append('\\').append(character);
            } else if (character == '\n') {
                result.append("\\n");
            } else if (character == '\r') {
                result.append("\\r");
            } else if (character == '\t') {
                result.append("\\t");
            } else if (character < 0x20) {
                result.append(String.format(Locale.US, "\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }

    private static String csvField(String value) {
        String normalized = value == null ? "" : value;
        return '"' + normalized.replace("\"", "\"\"") + '"';
    }

    private enum Family {
        ANY, IPV4, IPV6
    }

    private static final class Config {
        final String runId;
        final String probes;
        final String dnsHost;
        final String dnsWireHost;
        final int dnsWirePort;
        final String dnsWireName;
        final String dnsWireType;
        final String httpUrl;
        final String tlsHost;
        final String tlsAddress;
        final String tlsPath;
        final String tcpHost;
        final int tcpPort;
        final String udpHost;
        final int udpPort;
        final String tcpFamily;
        final String udpFamily;
        final int timeoutMs;
        final int durationCapMs;
        final int warmupRuns;
        final int measuredRuns;
        final int concurrency;
        final int repeatDelayMs;
        final boolean allowPublic;
        final long fixtureSize;
        final long expectedBytes;
        final String expectedSha256;
        final int expectedRcode;
        final int maxBodyBytes;
        final long tcpReadBytes;
        final byte[] tcpPayload;
        final byte[] udpPayload;
        final boolean tcpShutdownOutput;
        final boolean tcpSlowRead;
        final int slowReadDelayMs;
        final int slowReadChunkBytes;

        private Config(String runId, String probes, String dnsHost, String dnsWireHost,
                       int dnsWirePort, String dnsWireName, String dnsWireType, String httpUrl,
                       String tlsHost, String tlsAddress, String tlsPath,
                       String tcpHost, int tcpPort, String udpHost, int udpPort,
                       String tcpFamily, String udpFamily, int timeoutMs, int durationCapMs,
                       int warmupRuns, int measuredRuns, int concurrency, int repeatDelayMs,
                       boolean allowPublic, long fixtureSize, long expectedBytes,
                       String expectedSha256, int expectedRcode, int maxBodyBytes,
                       long tcpReadBytes,
                       byte[] tcpPayload, byte[] udpPayload,
                       boolean tcpShutdownOutput, boolean tcpSlowRead, int slowReadDelayMs,
                       int slowReadChunkBytes) {
            this.runId = runId;
            this.probes = probes;
            this.dnsHost = dnsHost;
            this.dnsWireHost = dnsWireHost;
            this.dnsWirePort = dnsWirePort;
            this.dnsWireName = dnsWireName;
            this.dnsWireType = dnsWireType;
            this.httpUrl = httpUrl;
            this.tlsHost = tlsHost;
            this.tlsAddress = tlsAddress;
            this.tlsPath = tlsPath;
            this.tcpHost = tcpHost;
            this.tcpPort = tcpPort;
            this.udpHost = udpHost;
            this.udpPort = udpPort;
            this.tcpFamily = tcpFamily;
            this.udpFamily = udpFamily;
            this.timeoutMs = timeoutMs;
            this.durationCapMs = durationCapMs;
            this.warmupRuns = warmupRuns;
            this.measuredRuns = measuredRuns;
            this.concurrency = concurrency;
            this.repeatDelayMs = repeatDelayMs;
            this.allowPublic = allowPublic;
            this.fixtureSize = fixtureSize;
            this.expectedBytes = expectedBytes;
            this.expectedSha256 = expectedSha256;
            this.expectedRcode = expectedRcode;
            this.maxBodyBytes = maxBodyBytes;
            this.tcpReadBytes = tcpReadBytes;
            this.tcpPayload = tcpPayload;
            this.udpPayload = udpPayload;
            this.tcpShutdownOutput = tcpShutdownOutput;
            this.tcpSlowRead = tcpSlowRead;
            this.slowReadDelayMs = slowReadDelayMs;
            this.slowReadChunkBytes = slowReadChunkBytes;
        }

        static Config from(Intent intent) {
            int durationCapMs = clamp(numberExtra(intent, "duration-cap-ms",
                    DEFAULT_DURATION_CAP_MS), 250, MAX_DURATION_CAP_MS);
            int timeout = clamp(Math.min(numberExtra(intent, "timeout-ms", DEFAULT_TIMEOUT_MS),
                    durationCapMs), 250, MAX_TIMEOUT_MS);
            long fixtureSize = numberExtra(intent, "fixture-size", -1);
            int maxBodyBytes = clamp(numberExtra(intent, "max-body-bytes",
                    DEFAULT_MAX_BODY_BYTES), 1, (int) MAX_STREAM_BYTES);
            int warmupRuns = clamp(numberExtra(intent, "warmup-runs", 0), 0, MAX_WARMUP_RUNS);
            int measuredRuns = clamp(numberExtra(intent, "measured-runs", 1),
                    1, MAX_MEASURED_RUNS);
            int concurrency = clamp(numberExtra(intent, "concurrency", 1),
                    1, MAX_CONCURRENCY);
            int repeatDelayMs = clamp(numberExtra(intent, "repeat-delay-ms", 0), 0, 5_000);
            int slowReadDelayMs = clamp(numberExtra(intent, "slow-read-delay-ms", 2),
                    0, 250);
            int slowReadChunkBytes = clamp(numberExtra(intent, "slow-read-chunk-bytes", 1024),
                    1, 16 * 1024);
            return new Config(
                    stringExtra(intent, "run-id", ""),
                    stringExtra(intent, "probe", "help"),
                    stringExtra(intent, "dns-host", DEFAULT_DNS_HOST),
                    stringExtra(intent, "dns-wire-host", "10.111.0.1"),
                    intent.getIntExtra("dns-wire-port", 53),
                    stringExtra(intent, "dns-wire-name", "probe.invalid"),
                    stringExtra(intent, "dns-wire-type", "A"),
                    stringExtra(intent, "http-url", DEFAULT_HTTP_URL),
                    stringExtra(intent, "tls-host", DEFAULT_TLS_HOST),
                    stringExtra(intent, "tls-address", DEFAULT_TLS_ADDRESS),
                    stringExtra(intent, "tls-path", DEFAULT_TLS_PATH),
                    stringExtra(intent, "tcp-host", DEFAULT_FIXTURE_HOST),
                    intent.getIntExtra("tcp-port", 18081),
                    stringExtra(intent, "udp-host", DEFAULT_FIXTURE_HOST),
                    intent.getIntExtra("udp-port", 18082),
                    stringExtra(intent, "tcp-family", "any"),
                    stringExtra(intent, "udp-family", "any"),
                    timeout,
                    durationCapMs,
                    warmupRuns,
                    measuredRuns,
                    concurrency,
                    repeatDelayMs,
                    intent.getBooleanExtra("allow-public", false),
                    fixtureSize,
                    numberExtra(intent, "expected-bytes", -1),
                    normalizeSha(stringExtra(intent, "expected-sha256", "")),
                    (int) numberExtra(intent, "expected-rcode", -1),
                    maxBodyBytes,
                    Math.max(0, numberExtra(intent, "tcp-read-bytes", 0)),
                    bytesExtra(intent, "tcp-payload"),
                    bytesExtra(intent, "udp-payload"),
                    intent.getBooleanExtra("tcp-shutdown-output", false),
                    intent.getBooleanExtra("tcp-slow-read", false),
                    slowReadDelayMs,
                    slowReadChunkBytes);
        }

        String describeArgs() {
            return "runId=" + runId + " probe=" + probes + " dnsWire=" + dnsWireHost + ":"
                    + dnsWirePort + "/" + dnsWireType + " http=" + redactForDisplay(httpUrl)
                    + " tls=" + tlsHost + "@" + tlsAddress + tlsPath
                    + " tcp=" + tcpHost + ":" + tcpPort + "(" + tcpFamily + ")"
                    + " udp=" + udpHost + ":" + udpPort + "(" + udpFamily + ")"
                    + " fixtureSize=" + fixtureSize + " maxBodyBytes=" + maxBodyBytes
                    + " warmupRuns=" + warmupRuns + " measuredRuns=" + measuredRuns
                    + " concurrency=" + concurrency + " durationCapMs=" + durationCapMs
                    + " shutdownOutput=" + tcpShutdownOutput + " slowRead=" + tcpSlowRead;
        }

        private static String stringExtra(Intent intent, String key, String fallback) {
            String value = intent.getStringExtra(key);
            return value == null ? fallback : value;
        }

        private static byte[] bytesExtra(Intent intent, String key) {
            String value = intent.getStringExtra(key);
            return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        }

        private static long numberExtra(Intent intent, String key, long fallback) {
            Object value = intent.getExtras() == null ? null : intent.getExtras().get(key);
            return value instanceof Number ? ((Number) value).longValue() : fallback;
        }

        private static String normalizeSha(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
            return normalized.isEmpty() ? null : normalized;
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        private static int clamp(long value, int minimum, int maximum) {
            return (int) Math.max(minimum, Math.min(maximum, value));
        }

        private static String redactForDisplay(String value) {
            if (value == null) {
                return "null";
            }
            try {
                return redactUrl(new URL(value.replace("{size}", "N")));
            } catch (Exception ignored) {
                return "<invalid-url>";
            }
        }
    }

    private static final class RepeatedCounts {
        final int attempted;
        final int passed;

        RepeatedCounts(int attempted, int passed) {
            this.attempted = attempted;
            this.passed = passed;
        }
    }

    private static final class BenchmarkSample {
        final String runId;
        final String probe;
        final String phase;
        final int iteration;
        final int slot;
        final int concurrency;
        final String status;
        final long durationMs;
        final long startedAtEpochMs;
        final long requestedBytes;
        final int timeoutMs;
        final int durationCapMs;
        final String error;

        private BenchmarkSample(String runId, String probe, String phase, int iteration,
                                int slot, int concurrency, String status, long durationMs,
                                long startedAtEpochMs, long requestedBytes, int timeoutMs,
                                int durationCapMs, String error) {
            this.runId = runId;
            this.probe = probe;
            this.phase = phase;
            this.iteration = iteration;
            this.slot = slot;
            this.concurrency = concurrency;
            this.status = status;
            this.durationMs = durationMs;
            this.startedAtEpochMs = startedAtEpochMs;
            this.requestedBytes = requestedBytes;
            this.timeoutMs = timeoutMs;
            this.durationCapMs = durationCapMs;
            this.error = error == null ? "" : error;
        }

        static BenchmarkSample pass(Config config, String probe, String phase, int iteration,
                                    int slot, long startedAtEpochMs, long durationMs) {
            return new BenchmarkSample(config.runId, probe, phase, iteration, slot,
                    config.concurrency, "pass", durationMs, startedAtEpochMs,
                    requestedBytes(config), config.timeoutMs, config.durationCapMs, "");
        }

        static BenchmarkSample fail(Config config, String probe, String phase, int iteration,
                                    int slot, long startedAtEpochMs, long durationMs,
                                    String error) {
            return new BenchmarkSample(config.runId, probe, phase, iteration, slot,
                    config.concurrency, "fail", durationMs, startedAtEpochMs,
                    requestedBytes(config), config.timeoutMs, config.durationCapMs, error);
        }

        private static long requestedBytes(Config config) {
            if (config.fixtureSize >= 0) {
                return config.fixtureSize;
            }
            if (config.expectedBytes >= 0) {
                return config.expectedBytes;
            }
            if (config.tcpPayload != null) {
                return config.tcpPayload.length;
            }
            if (config.udpPayload != null) {
                return config.udpPayload.length;
            }
            return -1;
        }

        boolean isPass() {
            return "pass".equals(status);
        }

        static String csvHeader(String runId) {
            return "BENCH_CSV_HEADER runId=" + safe(runId) + " schema,record,runId,probe,phase,iteration,slot,"
                    + "concurrency,status,durationMs,startedAtEpochMs,requestedBytes,"
                    + "timeoutMs,durationCapMs,error";
        }

        String jsonLine() {
            return "BENCH_SAMPLE_JSON {\"schema\":1,\"record\":\"sample\","
                    + "\"runId\":" + jsonString(runId)
                    + ",\"probe\":" + jsonString(probe)
                    + ",\"phase\":" + jsonString(phase)
                    + ",\"iteration\":" + iteration
                    + ",\"slot\":" + slot
                    + ",\"concurrency\":" + concurrency
                    + ",\"status\":" + jsonString(status)
                    + ",\"durationMs\":" + durationMs
                    + ",\"startedAtEpochMs\":" + startedAtEpochMs
                    + ",\"requestedBytes\":" + requestedBytes
                    + ",\"timeoutMs\":" + timeoutMs
                    + ",\"durationCapMs\":" + durationCapMs
                    + ",\"error\":" + jsonString(error) + "}";
        }

        String csvLine() {
            return "BENCH_SAMPLE_CSV 1,sample," + csvField(runId) + ","
                    + csvField(probe) + "," + csvField(phase) + "," + iteration + ","
                    + slot + "," + concurrency + "," + csvField(status) + ","
                    + durationMs + "," + startedAtEpochMs + "," + requestedBytes + ","
                    + timeoutMs + "," + durationCapMs + "," + csvField(error);
        }
    }

    private final class Reporter {
        void log(String message) {
            append(message);
        }

        void benchmarkSample(BenchmarkSample sample) {
            log(sample.jsonLine());
            log(sample.csvLine());
        }

        void error(String message) {
            Log.e(TAG, safe(message));
            append(message);
        }
    }

    private static final class ProbeException extends Exception {
        ProbeException(String message) {
            super(message);
        }

        ProbeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;

        NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        }
    }
}
