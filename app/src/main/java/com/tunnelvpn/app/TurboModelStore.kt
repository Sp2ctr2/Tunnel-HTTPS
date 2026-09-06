package com.tunnelvpn.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TurboModelCodec {
    const val SCHEMA_VERSION = 2
    private const val HEADER = "TURBO_AI_MODEL"

    fun encode(state: TurboPersistedState): String {
        val lines = ArrayList<String>(state.entries.size + 2)
        lines += "$HEADER|$SCHEMA_VERSION"
        lines += "S|${state.totalSamples.coerceAtLeast(0L)}"
        state.entries.forEach { entry ->
            if (!isValid(entry)) return@forEach
            lines += listOf(
                "E",
                encodeHex(entry.contextKey.toByteArray(Charsets.UTF_8)),
                entry.strategy.stableId,
                format(entry.weightedCount),
                format(entry.meanReward.coerceIn(-1.0, 1.0)),
                format(entry.weightedSuccesses),
                format(entry.latencyEmaMs),
                entry.lastUpdatedMs.coerceAtLeast(0L).toString(),
                entry.lastAccessMs.coerceAtLeast(0L).toString()
            ).joinToString("|")
        }
        return lines.joinToString("\n")
    }

    fun decode(value: String?): TurboDecodedState {
        if (value.isNullOrBlank()) return TurboDecodedState(TurboPersistedState(), corrupted = false)
        if (value.length > MAX_SERIALIZED_CHARS) return TurboDecodedState(TurboPersistedState(), corrupted = true)
        val lines = value.lineSequence().toList()
        if (lines.size > MAX_ENTRY_COUNT + 2 || lines.any { it.length > MAX_LINE_CHARS }) {
            return TurboDecodedState(TurboPersistedState(), corrupted = true)
        }
        if (lines.firstOrNull() != "$HEADER|$SCHEMA_VERSION") {
            return TurboDecodedState(TurboPersistedState(), corrupted = true)
        }
        var corrupted = false
        var samples = 0L
        val entries = ArrayList<TurboModelEntry>()
        lines.drop(1).forEach { line ->
            val fields = line.split('|')
            when (fields.firstOrNull()) {
                "S" -> {
                    val parsed = fields.getOrNull(1)?.toLongOrNull()
                    if (fields.size != 2 || parsed == null || parsed < 0L) corrupted = true else samples = parsed
                }
                "E" -> {
                    if (entries.size >= MAX_ENTRY_COUNT) {
                        corrupted = true
                        return@forEach
                    }
                    val entry = decodeEntry(fields)
                    if (entry == null) corrupted = true else entries += entry
                }
                else -> corrupted = true
            }
        }
        return TurboDecodedState(
            TurboPersistedState(
                schemaVersion = SCHEMA_VERSION,
                totalSamples = samples,
                entries = entries
            ),
            corrupted
        )
    }

    private fun decodeEntry(fields: List<String>): TurboModelEntry? {
        if (fields.size != 9) return null
        if (fields[1].length > MAX_CONTEXT_HEX_CHARS) return null
        val contextBytes = decodeHex(fields[1]) ?: return null
        val contextKey = runCatching { String(contextBytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (contextKey.isBlank() || contextKey.length > 192) return null
        val strategy = TurboStrategyId.fromStableId(fields[2]) ?: return null
        val count = fields[3].toDoubleOrNull() ?: return null
        val reward = fields[4].toDoubleOrNull() ?: return null
        val successes = fields[5].toDoubleOrNull() ?: return null
        val latency = fields[6].toDoubleOrNull() ?: return null
        val updated = fields[7].toLongOrNull() ?: return null
        val accessed = fields[8].toLongOrNull() ?: return null
        val entry = TurboModelEntry(
            contextKey = contextKey,
            strategy = strategy,
            weightedCount = count,
            meanReward = reward,
            weightedSuccesses = successes,
            latencyEmaMs = latency,
            lastUpdatedMs = updated,
            lastAccessMs = accessed
        )
        return entry.takeIf(::isValid)
    }

    private fun isValid(entry: TurboModelEntry): Boolean {
        return entry.contextKey.isNotBlank() &&
            entry.contextKey.length <= 192 &&
            entry.weightedCount.isFinite() && entry.weightedCount >= 0.0 &&
            entry.meanReward.isFinite() && entry.meanReward in -1.0..1.0 &&
            entry.weightedSuccesses.isFinite() && entry.weightedSuccesses >= 0.0 &&
            entry.weightedSuccesses <= entry.weightedCount + 0.000001 &&
            entry.latencyEmaMs.isFinite() && entry.latencyEmaMs in 0.0..MAX_LATENCY_MS &&
            entry.lastUpdatedMs >= 0L && entry.lastAccessMs >= 0L
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    internal fun encodeHex(value: ByteArray, byteLimit: Int = value.size): String {
        require(byteLimit in 0..value.size)
        val encoded = CharArray(byteLimit * 2)
        var source = 0
        var target = 0
        while (source < byteLimit) {
            val byte = value[source].toInt() and 0xff
            encoded[target] = HEX_DIGITS[byte ushr 4]
            encoded[target + 1] = HEX_DIGITS[byte and 0x0f]
            source += 1
            target += 2
        }
        return encoded.concatToString()
    }

    internal fun decodeHex(value: String): ByteArray? {
        if (value.length % 2 != 0) return null
        val decoded = ByteArray(value.length / 2)
        for (index in decoded.indices) {
            val high = hexNibble(value[index * 2])
            val low = hexNibble(value[index * 2 + 1])
            if (high < 0 || low < 0) return null
            decoded[index] = ((high shl 4) or low).toByte()
        }
        return decoded
    }

    private fun hexNibble(value: Char): Int = when (value) {
        in '0'..'9' -> value.code - '0'.code
        in 'a'..'f' -> value.code - 'a'.code + 10
        in 'A'..'F' -> value.code - 'A'.code + 10
        else -> -1
    }

    private const val MAX_SERIALIZED_CHARS = 1_048_576
    private const val MAX_ENTRY_COUNT = 2_048
    private const val MAX_LINE_CHARS = 512
    private const val MAX_CONTEXT_HEX_CHARS = 384
    private const val MAX_LATENCY_MS = 60_000.0
    private const val HEX_DIGITS = "0123456789abcdef"
}

data class TurboLoadedModel(
    val secret: ByteArray,
    val state: TurboPersistedState,
    val recoveredFromCorruption: Boolean,
    val generation: Long = 0L
)

interface TurboModelStore {
    fun load(): TurboLoadedModel
    fun save(state: TurboPersistedState, confidence: TurboConfidence, generation: Long): Boolean
    fun reset()
}

@SuppressLint("ApplySharedPref")
class SharedPreferencesTurboModelStore(
    context: Context
) : TurboModelStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): TurboLoadedModel {
        synchronized(STORE_LOCK) {
            var recovered = false
            val storedSecret = preferences.getString(KEY_SECRET, "").orEmpty()
            var secret = TurboSecretProtector.decrypt(storedSecret)
            val legacySecret = if (secret == null) TurboModelCodec.decodeHex(storedSecret) else null
            if (legacySecret?.size == SECRET_BYTES) secret = legacySecret
            if (secret == null || secret.size != SECRET_BYTES) {
                recovered = storedSecret.isNotBlank()
                TurboSecretProtector.deleteKey()
                secret = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
                preferences.edit()
                    .putString(KEY_SECRET, TurboSecretProtector.encrypt(secret))
                    .putInt(KEY_SECRET_VERSION, SECRET_VERSION)
                    .putString(KEY_SECRET_ID, secretId(secret))
                    .remove(KEY_MODEL)
                    .putLong(KEY_SAMPLES, 0L)
                    .putString(KEY_CONFIDENCE, TurboConfidence.LOW.name)
                    .commit()
            } else {
                val currentId = secretId(secret)
                val storedId = preferences.getString(KEY_SECRET_ID, "").orEmpty()
                val identityMismatch = storedId.isNotBlank() && storedId != currentId
                if (identityMismatch) {
                    recovered = true
                    preferences.edit()
                        .remove(KEY_MODEL)
                        .putLong(KEY_SAMPLES, 0L)
                        .putString(KEY_CONFIDENCE, TurboConfidence.LOW.name)
                        .commit()
                }
                if (legacySecret != null || storedId != currentId ||
                    preferences.getInt(KEY_SECRET_VERSION, 0) != SECRET_VERSION
                ) {
                    preferences.edit()
                        .putString(KEY_SECRET, TurboSecretProtector.encrypt(secret))
                        .putInt(KEY_SECRET_VERSION, SECRET_VERSION)
                        .putString(KEY_SECRET_ID, currentId)
                        .commit()
                }
            }
            val decoded = TurboModelCodec.decode(preferences.getString(KEY_MODEL, null))
            if (decoded.corrupted) {
                recovered = true
                preferences.edit().remove(KEY_MODEL).putLong(KEY_SAMPLES, 0L).commit()
            }
            return TurboLoadedModel(
                secret,
                if (decoded.corrupted) TurboPersistedState() else decoded.state,
                recovered,
                preferences.getLong(KEY_GENERATION, 0L).coerceAtLeast(0L)
            )
        }
    }

    override fun save(state: TurboPersistedState, confidence: TurboConfidence, generation: Long): Boolean {
        synchronized(STORE_LOCK) {
            if (preferences.getLong(KEY_GENERATION, 0L) != generation) return false
            preferences.edit()
                .putString(KEY_MODEL, TurboModelCodec.encode(state))
                .putLong(KEY_SAMPLES, state.totalSamples)
                .putString(KEY_CONFIDENCE, confidence.name)
                .putLong(KEY_LAST_SAVED_MS, System.currentTimeMillis())
                .apply()
            return true
        }
    }

    override fun reset() {
        synchronized(STORE_LOCK) {
            val nextGeneration = (preferences.getLong(KEY_GENERATION, 0L) + 1L).coerceAtLeast(1L)
            preferences.edit()
                .remove(KEY_MODEL)
                .remove(KEY_SECRET)
                .putLong(KEY_SAMPLES, 0L)
                .putString(KEY_CONFIDENCE, TurboConfidence.LOW.name)
                .putLong(KEY_LAST_SAVED_MS, 0L)
                .putLong(KEY_GENERATION, nextGeneration)
                .commit()
            TurboSecretProtector.deleteKey()
        }
    }

    private fun secretId(secret: ByteArray): String {
        return TurboModelCodec.encodeHex(MessageDigest.getInstance("SHA-256").digest(secret))
    }

    companion object {
        const val PREFERENCES_NAME = "turbo_ai_private_v1"
        const val KEY_SAMPLES = "samples"
        const val KEY_CONFIDENCE = "confidence"
        private const val KEY_MODEL = "model"
        private const val KEY_SECRET = "destination_hmac_secret"
        private const val KEY_SECRET_ID = "destination_hmac_secret_id"
        private const val KEY_SECRET_VERSION = "destination_hmac_secret_version"
        private const val KEY_LAST_SAVED_MS = "last_saved_ms"
        private const val KEY_GENERATION = "reset_generation"
        private const val SECRET_BYTES = 32
        private const val SECRET_VERSION = 1
        private val STORE_LOCK = Any()
    }
}

private object TurboSecretProtector {
    private const val KEY_ALIAS = "tunnel_https_turbo_ai_secret_v1"
    private const val PREFIX = "v1"

    fun encrypt(secret: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP)
        return "$PREFIX:$iv:$encrypted"
    }

    fun decrypt(value: String): ByteArray? {
        if (!value.startsWith("$PREFIX:") || value.length > 1_024) return null
        val parts = value.split(':', limit = 3)
        if (parts.size != 3) return null
        return runCatching {
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).takeIf { it.size == 32 }
        }.getOrNull()
    }

    fun deleteKey() {
        runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }
}

object TurboAiPreferences {
    const val PREF_ENABLED = "turbo_ai_enabled"
    const val PREF_EXPLORATION_ENABLED = "turbo_ai_exploration_enabled"
    const val PREF_MODEL_UPDATES_ENABLED = "turbo_ai_model_updates_enabled"
    const val PREF_DEVELOPER_DIAGNOSTICS = "turbo_ai_developer_diagnostics"
    const val PREF_FORCE_BASELINE = "turbo_ai_force_baseline"
    const val PREF_AEGIS_SHADOW = "aegis_v2_shadow_enabled"

    fun flags(preferences: SharedPreferences, developerBuild: Boolean): TurboAiFlags {
        return TurboAiFlags(
            enabled = preferences.getBoolean(PREF_ENABLED, true),
            explorationEnabled = preferences.getBoolean(PREF_EXPLORATION_ENABLED, true),
            modelUpdatesEnabled = preferences.getBoolean(PREF_MODEL_UPDATES_ENABLED, true),
            developerDiagnosticsEnabled = developerBuild &&
                preferences.getBoolean(PREF_DEVELOPER_DIAGNOSTICS, false),
            forceBaseline = preferences.getBoolean(PREF_FORCE_BASELINE, false),
            aegisShadowEnabled = preferences.getBoolean(PREF_AEGIS_SHADOW, true)
        )
    }

    fun status(context: Context, enabled: Boolean): TurboAiStatus {
        TurboAiStatusRegistry.current()?.let { live ->
            if (live.enabled == enabled) return live
        }
        val preferences = context.getSharedPreferences(
            SharedPreferencesTurboModelStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        val samples = preferences.getLong(SharedPreferencesTurboModelStore.KEY_SAMPLES, 0L).coerceAtLeast(0L)
        val confidence = runCatching {
            TurboConfidence.valueOf(
                preferences.getString(
                    SharedPreferencesTurboModelStore.KEY_CONFIDENCE,
                    TurboConfidence.LOW.name
                ).orEmpty()
            )
        }.getOrDefault(TurboConfidence.LOW)
        return TurboAiStatus(
            available = true,
            enabled = enabled,
            samples = samples,
            confidence = confidence,
            state = when {
                !enabled -> "disabled"
                samples == 0L -> "learning"
                else -> "active"
            }
        )
    }

    fun resetModel(context: Context) {
        SharedPreferencesTurboModelStore(context).reset()
        TurboAiStatusRegistry.set(
            TurboAiStatus(
                available = true,
                enabled = true,
                samples = 0L,
                confidence = TurboConfidence.LOW,
                state = "learning"
            )
        )
    }
}
