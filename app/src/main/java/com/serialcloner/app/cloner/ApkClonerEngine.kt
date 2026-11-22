package com.serialcloner.app.cloner

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Ultra-fast v1-style single-APK cloner with BEST_SPEED compression,
 * 64 KB buffered I/O, proper 4-byte / 4KB ZIP alignment, and v1+v2+v3 signing.
 */
object ApkClonerEngine {

    private const val IO_BUFFER_SIZE = 65536

    data class CloneResult(
        val signedApk: File,
        val clonedPackageName: String,
        val clonedLabel: String
    )

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    fun cloneApk(
        context: Context,
        sourceApkPath: String,
        originalPackage: String,
        originalLabel: String,
        serialNumber: Int,
        customLabel: String? = null,
        onProgress: (String) -> Unit = {}
    ): CloneResult {
        val clonedPackage = "$originalPackage.clone$serialNumber"
        val clonedLabel = customLabel?.takeIf { it.isNotBlank() } ?: "$originalLabel $serialNumber"

        val outDir = File(context.cacheDir, "cloned_apks").apply { mkdirs() }
        val unsignedApk = File(outDir, "${clonedPackage}_unsigned.apk")
        val signedApk = File(outDir, "${clonedPackage}.apk")
        if (unsignedApk.exists()) unsignedApk.delete()
        if (signedApk.exists()) signedApk.delete()

        onProgress("Cloning #$serialNumber...")
        ZipFile(File(sourceApkPath)).use { zipIn ->
            val countingOut = CountingOutputStream(
                BufferedOutputStream(FileOutputStream(unsignedApk), IO_BUFFER_SIZE)
            )
            ZipOutputStream(countingOut).use { zipOut ->
                zipOut.setLevel(Deflater.BEST_SPEED)

                val entries = zipIn.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    if (isSignatureFile(name)) continue

                    if (name == "AndroidManifest.xml") {
                        val originalManifest = zipIn.getInputStream(entry).use { it.readBytes() }
                        val patchedManifest = AxmlPatcher.patchManifest(
                            axmlBytes = originalManifest,
                            oldPackage = originalPackage,
                            newPackage = clonedPackage,
                            newLabel = clonedLabel,
                            serialNumber = serialNumber
                        )
                        val newEntry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
                        zipOut.putNextEntry(newEntry)
                        zipOut.write(patchedManifest)
                        zipOut.closeEntry()
                    } else if (entry.method == ZipEntry.STORED || name == "resources.arsc" || name.endsWith(".so")) {
                        val data = zipIn.getInputStream(entry).use { it.readBytes() }
                        val crc = if (entry.crc >= 0) entry.crc else CRC32().apply { update(data) }.value

                        zipOut.flush()
                        val alignment = if (name.endsWith(".so")) 4096 else 4
                        val nameBytesLen = name.toByteArray(Charsets.UTF_8).size
                        val dataOffset = countingOut.count + 30L + nameBytesLen
                        val rem = (dataOffset % alignment).toInt()
                        val pad = if (rem == 0) 0 else (alignment - rem)

                        val newEntry = ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = data.size.toLong()
                            compressedSize = data.size.toLong()
                            this.crc = crc
                            if (pad > 0) extra = ByteArray(pad)
                        }
                        zipOut.putNextEntry(newEntry)
                        zipOut.write(data)
                        zipOut.closeEntry()
                    } else {
                        val newEntry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
                        zipOut.putNextEntry(newEntry)
                        zipIn.getInputStream(entry).use { it.copyTo(zipOut, IO_BUFFER_SIZE) }
                        zipOut.closeEntry()
                    }
                }
            }
        }

        onProgress("Signing #$serialNumber...")
        val material = KeystoreManager.getOrCreateSigningMaterial(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "SERIAL_CLONER",
            material.privateKey,
            listOf(material.certificate)
        ).build()

        val apkSigner = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        apkSigner.sign()

        unsignedApk.delete()
        onProgress("Ready!")

        return CloneResult(
            signedApk = signedApk,
            clonedPackageName = clonedPackage,
            clonedLabel = clonedLabel
        )
    }

    private fun isSignatureFile(entryName: String): Boolean {
        if (!entryName.startsWith("META-INF/")) return false
        val upper = entryName.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC")
    }
}
