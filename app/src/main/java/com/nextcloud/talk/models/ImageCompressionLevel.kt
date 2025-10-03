package com.nextcloud.talk.models

enum class ImageCompressionLevel(val key: String) {
    NONE("none"),
    LIGHT("light"),
    MEDIUM("medium"),
    STRONG("strong");

    companion object {
        fun fromKey(key: String): ImageCompressionLevel {
            return values().find { it.key == key } ?: NONE
        }
    }
}
