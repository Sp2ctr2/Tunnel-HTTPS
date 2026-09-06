package com.tunnelvpn.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurboModelCodecTest {
    @Test
    fun routineModelSaveDoesNotBlockOnSharedPreferencesDiskCommit() {
        val source = File("src/main/java/com/tunnelvpn/app/TurboModelStore.kt").readText()
        val saveBody = source.substringAfter("override fun save(").substringBefore("override fun reset()")

        assertTrue(saveBody.contains(".apply()"))
        assertFalse(saveBody.contains(".commit()"))
    }

    @Test
    fun modelStateRoundTripsThroughVersionedCodec() {
        val state = TurboPersistedState(
            totalSamples = 42,
            entries = listOf(
                TurboModelEntry(
                    contextKey = "abc|443|WIFI|UNVP|HS|DEFAULT|100000|NONE",
                    strategy = TurboStrategyId.TLS_HOSTNAME_V1,
                    weightedCount = 8.5,
                    meanReward = 0.625,
                    weightedSuccesses = 7.0,
                    latencyEmaMs = 145.25,
                    lastUpdatedMs = 5_000L,
                    lastAccessMs = 5_100L
                )
            )
        )

        val decoded = TurboModelCodec.decode(TurboModelCodec.encode(state))

        assertFalse(decoded.corrupted)
        assertEquals(42L, decoded.state.totalSamples)
        assertEquals(state.entries.single().contextKey, decoded.state.entries.single().contextKey)
        assertEquals(state.entries.single().strategy, decoded.state.entries.single().strategy)
        assertEquals(0.625, decoded.state.entries.single().meanReward, 0.000001)
    }

    @Test
    fun hexadecimalCodecRoundTripsWithoutPerByteParsingObjects() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = TurboModelCodec.encodeHex(bytes)

        assertEquals(512, encoded.length)
        assertTrue(bytes.contentEquals(TurboModelCodec.decodeHex(encoded)!!))
        assertEquals(encoded.take(32), TurboModelCodec.encodeHex(bytes, byteLimit = 16))
        assertTrue(TurboModelCodec.decodeHex("0g") == null)
    }

    @Test
    fun corruptedStateRecoversToSafeEmptyModel() {
        val decoded = TurboModelCodec.decode("not-a-model\nraw-host=private.example")

        assertTrue(decoded.corrupted)
        assertEquals(0L, decoded.state.totalSamples)
        assertTrue(decoded.state.entries.isEmpty())
    }

    @Test
    fun previousSchemaAndNonFiniteStatisticsAreRejected() {
        assertTrue(TurboModelCodec.decode("TURBO_AI_MODEL|1\nS|0").corrupted)
        val invalid = "TURBO_AI_MODEL|2\nS|1\nE|73616665|tls-plain-v1|NaN|0.5|1.0|100.0|1|1"

        val decoded = TurboModelCodec.decode(invalid)

        assertTrue(decoded.corrupted)
        assertTrue(decoded.state.entries.isEmpty())
    }

    @Test
    fun destinationSecretUsesKeystoreEncryptionAndModelIdentity() {
        val source = File("src/main/java/com/tunnelvpn/app/TurboModelStore.kt").readText()

        assertTrue(source.contains("AndroidKeyStore"))
        assertTrue(source.contains("AES/GCM/NoPadding"))
        assertTrue(source.contains("KEY_SECRET_ID"))
        assertTrue(source.contains("identityMismatch"))
        assertFalse(source.contains("putString(KEY_SECRET, TurboModelCodec.encodeHex(secret))"))
    }

    @Test
    fun oversizedCorruptedStateIsRejectedBeforeParsing() {
        val decoded = TurboModelCodec.decode("TURBO_AI_MODEL|2\n" + "x".repeat(1_048_576))

        assertTrue(decoded.corrupted)
        assertTrue(decoded.state.entries.isEmpty())
    }

    @Test
    fun partiallyCorruptedEntriesAreDropped() {
        val valid = TurboModelCodec.encode(
            TurboPersistedState(
                totalSamples = 1,
                entries = listOf(
                    TurboModelEntry(
                        contextKey = "safe",
                        strategy = TurboStrategyId.TLS_PLAIN_V1,
                        weightedCount = 1.0,
                        meanReward = 0.2,
                        weightedSuccesses = 1.0,
                        latencyEmaMs = 100.0,
                        lastUpdatedMs = 10L,
                        lastAccessMs = 10L
                    )
                )
            )
        )

        val decoded = TurboModelCodec.decode("$valid\nE|broken")

        assertTrue(decoded.corrupted)
        assertEquals(1, decoded.state.entries.size)
    }

    @Test
    fun rawHostnameIsNeverPersisted() {
        val rawHostname = "sensitive.private.example"
        val engine = TurboAiEngine(
            secret = ByteArray(32) { 11 },
            flags = TurboAiFlags(enabled = true, explorationEnabled = false),
            clock = TurboClock { 1_000L }
        )
        val hashed = engine.destinationKey(rawHostname)
        val context = TurboContext(
            destinationKey = hashed,
            destinationPort = 443,
            transport = TurboNetworkTransport.CELLULAR,
            metered = true,
            roaming = false,
            validated = true,
            batterySaver = false,
            clientHelloParsed = true,
            sniPresent = true,
            latencyProfile = "DEFAULT",
            approximateRttBucket = 2,
            recentRetryBucket = 0,
            recentSuccessBucket = 0,
            recentHandshakeBucket = 0,
            resourcePressureBucket = 0
        )
        val decision = engine.select(context, TurboStrategyId.values().toList())
        engine.update(
            TurboOutcome(
                contextKey = decision.contextKey,
                strategy = decision.strategy,
                policy = decision.policy,
                success = true,
                handshakeLatencyMs = 100,
                connectionDurationMs = 1_000,
                bytesUp = 1_000,
                bytesDown = 2_000,
                retries = 0,
                timedOut = false,
                abruptDisconnect = false,
                fallbackUsed = false,
                decisionOverheadNanos = decision.decisionOverheadNanos,
                batterySaver = false,
                failureCategory = TurboFailureCategory.NONE,
                completedAtMs = 1_000
            )
        )

        val serialized = TurboModelCodec.encode(engine.exportState())

        assertFalse(serialized.contains(rawHostname))
        assertFalse(serialized.contains("private.example"))
        assertTrue(serialized.contains(TurboStrategyId.TLS_SNI_MULTI_V1.stableId))
    }
}
