package com.madebycli.aiusage.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted on-device storage in noBackupFilesDir, one namespace per provider. */
class SecureStore(context: Context, private val namespace: String = "account") : TokenStore, ClaudeCredentialStore, DeviceLoginStore {
    private val directory = context.noBackupFilesDir
    private val ioLock = locks.getOrPut(directory.absolutePath + "/" + namespace) { Any() }
    private val alias = "aiusage.$namespace.v1"
    private fun filename(name: String) = if (namespace == "account") name else "$namespace.$name"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun write(name: String, text: String): Unit = synchronized(ioLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(text.toByteArray())
        val file = AtomicFile(File(directory, filename(name)))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private fun read(name: String): String? = synchronized(ioLock) {
        val file = AtomicFile(File(directory, filename(name)))
        if (!file.baseFile.exists()) return@synchronized null
        val bytes = file.readFully()
        val ivLength = bytes.first().toInt()
        require(ivLength in 12..16 && bytes.size > ivLength + 1)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivLength)))
            doFinal(bytes.copyOfRange(1 + ivLength, bytes.size)).toString(Charsets.UTF_8)
        }
    }
    override fun readTokens(): Tokens? = read("tokens.enc")?.let { appJson.decodeFromString(it) }
    override fun writeTokens(tokens: Tokens) = write("tokens.enc", appJson.encodeToString(tokens))
    fun readRecord(): PersonalRecord = read("usage.enc")?.let { appJson.decodeFromString(it) } ?: PersonalRecord()
    fun writeRecord(record: PersonalRecord) = write("usage.enc", appJson.encodeToString(record))
    fun readStats(): StatsBundle = read("stats.enc")?.let { appJson.decodeFromString(it) } ?: StatsBundle()
    fun writeStats(value: StatsBundle) = write("stats.enc", appJson.encodeToString(value))
    fun saveMeasurement(usage: UsageSnapshot, provider: String): PersonalRecord = synchronized(ioLock) {
        recordMeasurement(readRecord(), usage, provider).also(::writeRecord)
    }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
    override fun readClaude(): ClaudeCredential? = read("tokens.enc")?.let { appJson.decodeFromString(it) }
    override fun writeClaude(value: ClaudeCredential) = write("tokens.enc", appJson.encodeToString(value))
    override fun readClaudeLogin(): ClaudePendingLogin? = read("login.enc")?.let { appJson.decodeFromString(it) }
    override fun writeClaudeLogin(value: ClaudePendingLogin) = write("login.enc", appJson.encodeToString(value))
    override fun clearClaudeLogin(): Unit = synchronized(ioLock) { AtomicFile(File(directory, filename("login.enc"))).delete() }
    override fun readDeviceLogin(): PendingDeviceLogin? = read("device.enc")?.let { appJson.decodeFromString(it) }
    override fun writeDeviceLogin(value: PendingDeviceLogin) = write("device.enc", appJson.encodeToString(value))
    override fun clearDeviceLogin(): Unit = synchronized(ioLock) { AtomicFile(File(directory, filename("device.enc"))).delete() }
    /** Drops what the removed organisation-API and file-import features may have stored. */
    fun purgeLegacyStatistics(): Unit = synchronized(ioLock) {
        AtomicFile(File(directory, filename("analytics-key.enc"))).delete()
        if (namespace != "account") AtomicFile(File(directory, filename("stats.enc"))).delete()
    }
    override fun clear(): Unit = synchronized(ioLock) {
        listOf("tokens.enc", "usage.enc", "stats.enc", "analytics-key.enc").forEach { AtomicFile(File(directory, filename(it))).delete() }
        clearClaudeLogin()
        clearDeviceLogin()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
