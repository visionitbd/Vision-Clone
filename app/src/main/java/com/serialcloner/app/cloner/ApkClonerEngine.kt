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
 * Fast APK cloner supporting both single-APK and Split-APK apps
 * using BEST_SPEED compression and 64 KB buffered I/O.
 */
object ApkClonerEngine {

    private const val IO_BUFFER_SIZE = 65536

    data class CloneResult(
        val apkFiles: List<File>,
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
        splitApkPaths: List<String>,
        originalPackage: String,
        originalLabel: String,
        serialNumber: Int,
        customLabel: String? = null,
        onProgress: (String) -> Unit = {}
    ): CloneResult {
        val clonedPackage = "$originalPackage.clone$serialNumber"
        val clonedLabel = customLabel?.takeIf { it.isNotBlank() } ?: "$originalLabel $serialNumber"

        val workDir = File(File(context.cacheDir, "cloned_apks"), clonedPackage).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        val material = KeystoreManager.getOrCreateSigningMaterial(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "SERIAL_CLONER",
            material.privateKey,
            listOf(material.certificate)
        ).build()

        val resultApks = mutableListOf<File>()

        onProgress("Patching & signing base APK...")
        val signedBase = processSingleApk(
            inputApk = File(sourceApkPath),
            outputDir = workDir,
            outputName = "base.apk",
            originalPackage = originalPackage,
            clonedPackage = clonedPackage,
            clonedLabel = clonedLabel,
            serialNumber = serialNumber,
            isSplitApk = false,
            signerConfig = signerConfig
        )
        resultApks.add(signedBase)

        splitApkPaths.forEachIndexed { idx, splitPath ->
            onProgress("Cloning split ${idx + 1}/${splitApkPaths.size}...")
            val signedSplit = processSingleApk(
                inputApk = File(splitPath),
                outputDir = workDir,
                outputName = "split_$idx.apk",
                originalPackage = originalPackage,
                clonedPackage = clonedPackage,
                clonedLabel = clonedLabel,
                serialNumber = serialNumber,
                isSplitApk = true,
                signerConfig = signerConfig
            )
            resultApks.add(signedSplit)
        }

        return CloneResult(
            apkFiles = resultApks,
            clonedPackageName = clonedPackage,
            clonedLabel = clonedLabel
        )
    }

    private fun processSingleApk(
        inputApk: File,
        outputDir: File,
        outputName: String,
        originalPackage: String,
        clonedPackage: String,
        clonedLabel: String,
        serialNumber: Int,
        isSplitApk: Boolean,
        signerConfig: ApkSigner.SignerConfig
    ): File {
        val unsignedApk = File(outputDir, "${outputName}.unsigned")
        val signedApk = File(outputDir, outputName)

        ZipFile(inputApk).use { zipIn ->
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
                            serialNumber = serialNumber,
                            isSplitApk = isSplitApk
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

        val apkSigner = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        apkSigner.sign()

        unsignedApk.delete()
        return signedApk
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
