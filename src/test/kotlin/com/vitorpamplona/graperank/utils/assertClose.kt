package com.vitorpamplona.graperank.utils

import kotlin.test.assertEquals

fun assertClose(
    expected: Double,
    actual: Double?,
) {
    // depends on the accuracy from graperank
    assertEquals(expected, actual ?: 0.0, 0.0001)
}