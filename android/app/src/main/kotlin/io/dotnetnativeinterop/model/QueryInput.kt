package io.dotnetnativeinterop.model

/**
 * Boundary validation for user text crossing into the native engine, mirrored from iOS
 * (`ios/Shared/QueryInput.swift`). The embedding model only reads ~256 word-pieces, so anything
 * past the cap is dead weight in the JNI marshal.
 */
public object QueryInput {
    public const val MAX_LENGTH: Int = 500

    /** Trimmed, length-capped query — or null if there's nothing to send. */
    public fun sanitize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_LENGTH)
    }
}
