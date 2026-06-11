package io.dotnetnativeinterop.model

/**
 * Boundary validation for user text crossing into the native engine, mirrored from iOS
 * (`ios/Shared/QueryInput.swift`). The embedding model only reads ~256 word-pieces, so anything
 * past the cap is dead weight in the JNI marshal.
 */
public object QueryInput {
    public const val MAX_LENGTH: Int = 2000

    /** Trimmed, length-capped query — or null if there's nothing to send. The cap bounds the JNI
     *  marshal without cutting below the model's ~256-word-piece window; truncation never splits
     *  a surrogate pair (an unpaired surrogate would corrupt the UTF-8 marshal). */
    public fun sanitize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        var capped = trimmed.take(MAX_LENGTH)
        if (capped.isNotEmpty() && capped.last().isHighSurrogate()) capped = capped.dropLast(1)
        return capped
    }
}
