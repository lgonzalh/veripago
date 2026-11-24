package com.lantonium.veripago.util

import com.lantonium.veripago.BuildConfig

object VersionHelper {
    private val greekLetters = listOf(
        "alfa", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta",
        "iota", "kappa", "lambda", "mu", "nu", "xi", "omicron", "pi", "rho",
        "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega",
    )
    private val latinLetters = ('a'..'z').map(Char::toString)
    private val greekGods = listOf(
        "Zeus", "Hera", "Poseidon", "Demeter", "Athena", "Apollo",
        "Artemis", "Ares", "Aphrodite", "Hephaestus", "Hermes", "Dionysus",
    )
    private val romanGods = listOf(
        "Jupiter", "Juno", "Neptune", "Ceres", "Minerva", "Mars",
        "Venus", "Vulcan", "Mercury", "Vesta", "Bacchus", "Janus",
    )

    fun getGreekVersionString(): String {
        return "Ver 1.0 build ${getBuildLabel(BuildConfig.VERSION_CODE)}"
    }

    internal fun getBuildLabel(versionCode: Int): String {
        val buildIndex = (versionCode.coerceAtLeast(1) - 1)
        return if (buildIndex < greekLetters.size) {
            greekLetters[buildIndex]
        } else if (buildIndex < greekLetters.size + latinLetters.size) {
            val latinIndex = buildIndex - greekLetters.size
            latinLetters[latinIndex]
        } else {
            val gods = greekGods + romanGods
            val godIndex = buildIndex - greekLetters.size - latinLetters.size
            val god = gods[godIndex % gods.size]
            val cycle = godIndex / gods.size + 1
            if (cycle == 1) god else "$god-$cycle"
        }
    }
}
