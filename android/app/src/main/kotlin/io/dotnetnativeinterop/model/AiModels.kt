package io.dotnetnativeinterop.model

import kotlinx.serialization.Serializable

/** One semantic-search hit (dni_search returns JSON [{text,score}]). */
@Serializable
public data class SearchResult(val text: String, val score: Double)
