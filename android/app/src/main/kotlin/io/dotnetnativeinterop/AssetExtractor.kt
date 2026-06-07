package io.dotnetnativeinterop

import android.content.Context
import java.io.File

/** Copies bundled AI assets (dni-assets/) from the APK to filesDir so ORT can open them by path. */
internal object AssetExtractor {
    private const val DIR = "dni-assets"
    private val ROOT_FILES = listOf("model.onnx", "vocab.txt", "corpus.txt", "edge-index.db")

    /** Idempotent extraction (skips files that already exist non-empty). Returns the destination dir. */
    internal fun ensure(context: Context): File {
        val out = File(context.filesDir, DIR).apply { mkdirs() }
        ROOT_FILES.forEach { name -> copyIfMissing(context, "$DIR/$name", File(out, name)) }
        val manualsOut = File(out, "manuals").apply { mkdirs() }
        context.assets.list("$DIR/manuals")?.forEach { m ->
            copyIfMissing(context, "$DIR/manuals/$m", File(manualsOut, m))
        }
        return out
    }

    private fun copyIfMissing(context: Context, assetPath: String, dest: File) {
        if (dest.exists() && dest.length() > 0L) return
        context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }
}
