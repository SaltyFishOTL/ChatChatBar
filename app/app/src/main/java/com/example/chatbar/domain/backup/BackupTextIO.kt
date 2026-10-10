package com.example.chatbar.domain.backup

import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

internal fun InputStream.strictUtf8Reader(): java.io.BufferedReader =
    InputStreamReader(this, Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)).buffered()

internal fun File.strictUtf8Reader(): java.io.BufferedReader = inputStream().strictUtf8Reader()
