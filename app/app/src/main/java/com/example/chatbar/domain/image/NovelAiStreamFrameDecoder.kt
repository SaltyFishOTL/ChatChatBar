package com.example.chatbar.domain.image

class NovelAiStreamFrameDecoder(private val maxFrameSize: Int = 32 * 1024 * 1024) {
    private var pending = ByteArray(0)

    fun feed(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        pending += chunk
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (pending.size - offset >= 4) {
            val size = ((pending[offset].toInt() and 0xff) shl 24) or
                ((pending[offset + 1].toInt() and 0xff) shl 16) or
                ((pending[offset + 2].toInt() and 0xff) shl 8) or
                (pending[offset + 3].toInt() and 0xff)
            require(size in 1..maxFrameSize) { "NovelAI 流帧大小无效: $size" }
            if (pending.size - offset - 4 < size) break
            frames += pending.copyOfRange(offset + 4, offset + 4 + size)
            offset += 4 + size
        }
        if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
        return frames
    }
}
