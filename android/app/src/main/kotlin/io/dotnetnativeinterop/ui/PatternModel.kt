package io.dotnetnativeinterop.ui

import kotlinx.serialization.Serializable

/** Top-level wrapper for docs/patterns.json. */
@Serializable
public data class PatternsJson(
    val version: Int,
    val recommendation: String,
    val patterns: List<PatternInfo>,
)

/** One transport pattern entry from patterns.json. */
@Serializable
public data class PatternInfo(
    val id: String,
    val name: String,
    val transport: String,
    val summary: String,
    val bestFor: String,
    val features: List<String>,
    val limitations: List<String>,
)
