package com.invictus.kodex.core.git

/** A clone the Clone screen should start by itself on open (set by "New repository"). */
data class ClonePrefill(val url: String, val folder: String)

class PendingClone {
    @Volatile private var value: ClonePrefill? = null
    fun set(p: ClonePrefill) { value = p }

    /** Returns the request once; later calls return null. */
    fun take(): ClonePrefill? = value.also { value = null }
}
