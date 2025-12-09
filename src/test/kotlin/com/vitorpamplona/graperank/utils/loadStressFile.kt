package com.vitorpamplona.graperank.utils

import java.io.BufferedReader

open class BaseStressTest {
    fun loadFile(fileName: String): BufferedReader {
        return this
            .javaClass
            .classLoader
            .getResourceAsStream(fileName)!!
            .bufferedReader()
    }
}
