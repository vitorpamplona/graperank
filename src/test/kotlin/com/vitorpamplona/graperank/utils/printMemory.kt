package com.vitorpamplona.graperank.utils

fun printMemory() {
    System.gc()
    Thread.sleep(1000)

    val totalMemoryMb = Runtime.getRuntime().totalMemory() / (1024 * 1024)
    val freeMemoryMb = Runtime.getRuntime().freeMemory() / (1024 * 1024)
    val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
    val jvmHeapAllocatedMb = totalMemoryMb - freeMemoryMb
    println("Total Heap Allocated: $jvmHeapAllocatedMb/$maxMemoryMb MB")
}