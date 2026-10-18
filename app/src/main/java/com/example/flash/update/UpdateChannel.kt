package com.example.flash.update

/** Stable = releases built from `main` (confirmed to work); Beta = every `develop` build. */
enum class UpdateChannel(val id: String) {
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromId(id: String): UpdateChannel =
            entries.firstOrNull { it.id == id } ?: BETA
    }
}
