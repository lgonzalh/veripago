package com.lantonium.veripago.util

import com.lantonium.veripago.BuildConfig

object VersionHelper {
    private val greekLetters = listOf(
        "alfa", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
        "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi", "rho",
        "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega",
    )
    private val latinLetters = ('a'..'z').map(Char::toString)

    fun getGreekVersionString(): String {
        val buildIndex = (BuildConfig.VERSION_CODE - 1).coerceAtLeast(0)
        val label = if (buildIndex < greekLetters.size) {
            greekLetters[buildIndex]
        } else {
            val latinIndex = buildIndex - greekLetters.size
            val cycle = latinIndex / latinLetters.size + 1
            val letter = latinLetters[latinIndex % latinLetters.size]
            if (cycle == 1) letter else "$letter-$cycle"
        }
        return "Ver 1.0 build $label"
    }
}
