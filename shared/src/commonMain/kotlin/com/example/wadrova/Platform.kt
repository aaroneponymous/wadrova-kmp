package com.example.wadrova

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform