package com.madebycli.aiusage.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "42" or "42,5": whole numbers stay whole. */
/** Whole display percentages everywhere; the underlying measurements retain their precision. */
fun formatPercent(value: Double): String = if (value.isFinite()) Math.round(value).toString() else "–"

