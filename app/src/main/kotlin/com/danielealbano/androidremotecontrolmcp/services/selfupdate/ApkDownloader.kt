package com.danielealbano.androidremotecontrolmcp.services.selfupdate

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam [SelfUpdater] fetches an APK through. An interface so the updater's ORDER — refuse a
 * same-version push, fetch, verify, install — is unit-testable with no network.
 */
interface ApkDownloader {
    /**
     * Streams the APK at [url] into [target], replacing whatever was there, and returns the
     * lowercase hex SHA-256 of the bytes actually written.
     *
     * The hash is computed DURING the copy rather than by re-reading the file, so what is verified
     * is what crossed the socket: a file mutated between the write and the check cannot pass.
     *
     * @throws IOException for every fetch failure — a refused connection, a non-2xx answer, a body
     *   past [OkHttpApkDownloader.MAX_APK_BYTES], an empty or truncated stream. The caller turns
     *   all of them into one [UpdateRefusal.DOWNLOAD_FAILED], because a device can act on none of
     *   them differently.
     */
    suspend fun download(
        url: String,
        target: File,
    ): String
}

/**
 * The real downloader, on the same OkHttp the connector's socket already uses — no second HTTP
 * stack for one request a month.
 *
 * Its own client rather than the connector's, though: that one is tuned for a single long-lived
 * WebSocket (no read timeout at all, and `Proxy.NO_PROXY` because the platform socket must never
 * traverse a device-configured proxy), and neither setting suits a bulk fetch from the public
 * edge. A download with no timeout is a download that can pin the updater forever.
 *
 * **Nothing here logs the URL.** A publishing URL may carry a capability token in its query
 * string, and a log line is the one place a credential outlives the process that used it.
 */
@Singleton
class OkHttpApkDownloader
    @Inject
    constructor(
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ApkDownloader {
        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        override suspend fun download(
            url: String,
            target: File,
        ): String =
            withContext(ioDispatcher) {
                target.parentFile?.mkdirs()
                // A URL OkHttp cannot parse is an IllegalArgumentException, which is the one fetch
                // failure that is not already an IOException. Normalised here so the caller has a
                // single catch and cannot be surprised by an unchecked throw.
                val request =
                    try {
                        Request.Builder().url(url).build()
                    } catch (e: IllegalArgumentException) {
                        throw IOException("the published URL is not a usable HTTP URL", e)
                    }
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("the publisher answered HTTP ${response.code}")
                    }
                    val digest = response.body.byteStream().use { source -> copyHashing(source, target) }
                    Log.i(TAG, "Fetched an update APK of ${target.length()} bytes")
                    digest
                }
            }

        /** Copies [source] into [target] in bounded chunks, hashing as it goes. */
        private fun copyHashing(
            source: InputStream,
            target: File,
        ): String {
            val sha256 = MessageDigest.getInstance(SHA_256)
            var written = 0L
            target.outputStream().use { sink ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    written += read
                    if (written > MAX_APK_BYTES) {
                        throw IOException("the published APK exceeds the $MAX_APK_BYTES byte ceiling")
                    }
                    sha256.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                }
            }
            if (written == 0L) throw IOException("the publisher answered with an empty body")
            return toHex(sha256.digest())
        }

        companion object {
            private const val TAG = "MCP:SelfUpdateDownload"

            private const val SHA_256 = "SHA-256"

            private const val CONNECT_TIMEOUT_SECONDS = 30L
            private const val READ_TIMEOUT_SECONDS = 60L

            /** A whole-call ceiling, so a publisher trickling bytes cannot pin the updater forever. */
            private const val CALL_TIMEOUT_SECONDS = 600L

            private const val COPY_BUFFER_BYTES = 64 * 1024

            /**
             * The largest APK this app will write to its cache. Not a statement about build size —
             * it is what stops a wrong or hostile URL from filling a phone's storage before the
             * checksum ever gets a chance to reject the bytes.
             */
            const val MAX_APK_BYTES = 512L * 1024 * 1024

            /**
             * Lowercase hex, which is the encoding the wire contract's `sha256` field uses.
             *
             * `%02x` on a [Byte] formats its unsigned value, so a byte above 0x7f renders as `ff`
             * rather than as a sign-extended `ffffffff` — which is the whole trap in hand-rolling
             * this, and the reason it is a named function with a test rather than an inline
             * expression.
             */
            internal fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        }
    }
