package com.softberries.doubleentry.config

/** Keeps the `doubleEntry { accounts { … }; transfers { … } }` builders from leaking into each other's scope. */
@DslMarker
annotation class DoubleEntryDsl
