package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.NOVEL_AI_MAX_BATCH_SIZE
import com.example.chatbar.domain.image.parseNovelAiBatchSize
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun NovelAiBatchSizeInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.width(72.dp)) {
        CbText("批量", style = ChatBarTheme.typography.label)
        CbInput(
            value = value,
            onValueChange = { next ->
                if (next.length <= 1 && next.all(Char::isDigit)) onValueChange(next)
            },
            modifier = Modifier.semantics { contentDescription = "批量生图数量" },
            enabled = enabled,
            isError = parseNovelAiBatchSize(value) == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = "1-$NOVEL_AI_MAX_BATCH_SIZE"
        )
    }
}
