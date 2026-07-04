package com.example.chatbar.domain.card

object CharacterCardImagePolicy {
    fun sessionBackgroundOverrideAfterCardBackgroundChange(
        sessionBackground: String?,
        previousCardBackground: String?,
        newCardBackground: String
    ): String? {
        return if (sessionBackground != null && sessionBackground == previousCardBackground) {
            newCardBackground
        } else {
            sessionBackground
        }
    }
}
