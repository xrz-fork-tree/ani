package me.him188.ani.app.torrent.io

import kotlinx.coroutines.runBlocking
import me.him188.ani.app.torrent.api.PieceState
import me.him188.ani.app.torrent.api.pieces.Piece
import me.him188.ani.app.torrent.api.pieces.awaitFinished
import me.him188.ani.app.torrent.api.pieces.lastIndex
import me.him188.ani.app.torrent.api.pieces.startIndex
import me.him188.ani.utils.io.BufferedInput
import me.him188.ani.utils.io.SeekableInput
import org.jetbrains.annotations.Range
import java.io.RandomAccessFile


/**
 * A [SeekableInput] that reads from a torrent save file.
 *
 * It takes the advantage of the fact that the torrent save file is a concatenation of all pieces,
 * and awaits [Piece]s to be finished when they are sought and read.
 *
 * 即使 [pieces] 的起始不为 0, [SeekableInput.position] 也是从 0 开始.
 */
internal class TorrentInput(
    /**
     * The torrent save file.
     */
    private val file: RandomAccessFile,
    /**
     * The corresponding pieces of the [file], must contain all bytes in the [file].
     */
    private val pieces: List<Piece>,
    private val onWait: suspend (Piece) -> Unit = { },
    private val bufferSize: Int = DEFAULT_BUFFER_PER_DIRECTION,
) : BufferedInput(bufferSize) {
    private val logicalStartOffset: Long = pieces.minOf { it.offset }
    override val fileLength: Long = file.length()

    init {
        val pieceSum = pieces.maxOf { it.offset + it.size } - logicalStartOffset
        check(pieceSum >= fileLength) {
            "file length ${file.length()} is larger than pieces' range $pieceSum"
        }
    }

    override fun fillBuffer() {
        val fileLength = this.fileLength
        val pos = this.position


        // 保证当前位置的 piece 已完成
        val index = findPieceIndexOrFail(pos)
        val piece = pieces[index]
        if (piece.state.value != PieceState.FINISHED) {
            runBlocking {
                onWait(piece)
                piece.awaitFinished()
            }
        }

        val maxBackward = computeMaxBufferSizeBackward(pos, bufferSize.toLong(), piece = piece)
        val maxForward = computeMaxBufferSizeForward(pos, bufferSize.toLong(), piece = piece)

        val readStart = (pos - maxBackward).coerceAtLeast(0)
        val readEnd = (pos + maxForward).coerceAtMost(fileLength)

        fillBufferRange(readStart, readEnd)
    }

    override fun readFileToBuffer(fileOffset: Long, bufferOffset: Int, length: Int): Int {
        val file = this.file
        file.seek(fileOffset)
        file.readFully(buf, bufferOffset, length)
        return length
    }

    /**
     * 计算从 [viewOffset] 开始,
     */
    @Suppress("SameParameterValue")
    internal fun computeMaxBufferSizeForward(
        viewOffset: Long,
        cap: Long,
        piece: Piece = pieces[findPieceIndex(viewOffset)] // you can pass if you already have it. not checked though.
    ): Long {
        require(cap > 0) { "cap must be positive, but was $cap" }
        require(viewOffset >= 0) { "viewOffset must be non-negative, but was $viewOffset" }

        var curr = piece
        var currOffset = logicalStartOffset + viewOffset
        var accSize = 0L
        while (true) {
            if (curr.state.value != PieceState.FINISHED) return accSize
            val length = curr.lastIndex - currOffset + 1
            accSize += length

            if (accSize >= cap) return cap

            val next = pieces.getOrNull(curr.pieceIndex + 1) ?: return accSize
            currOffset = curr.lastIndex + 1
            curr = next
        }
    }

    /**
     * 从 [viewOffset] 开始 (不包含)
     */
    @Suppress("SameParameterValue")
    internal fun computeMaxBufferSizeBackward(
        viewOffset: Long,
        cap: Long,
        piece: Piece = pieces[findPieceIndex(viewOffset)] // you can pass if you already have it. not checked though.
    ): Long {
        require(cap > 0) { "cap must be positive, but was $cap" }
        require(viewOffset >= 0) { "viewOffset must be non-negative, but was $viewOffset" }

        var curr = piece
        var currOffset = logicalStartOffset + viewOffset
        var accSize = 0L
        while (true) {
            if (curr.state.value != PieceState.FINISHED) return accSize
            val length = currOffset - curr.startIndex
            accSize += length

            if (accSize >= cap) return cap

            val next = pieces.getOrNull(curr.pieceIndex - 1) ?: return accSize
            currOffset = curr.startIndex
            curr = next
        }
    }

    /**
     * @throws IllegalArgumentException
     */
    private fun findPieceIndexOrFail(viewOffset: Long): @Range(from = 0L, to = Long.MAX_VALUE) Int {
        val index = findPieceIndex(viewOffset)
        if (index == -1) {
            throw IllegalArgumentException("offset $viewOffset is not in any piece")
        }
        return index.also {
            check(it >= 0) { "findPieceIndex returned a negative index: $it" }
        }
    }

    internal fun findPieceIndex(viewOffset: Long): @Range(from = -1L, to = Long.MAX_VALUE) Int {
        require(viewOffset >= 0) { "viewOffset must be non-negative, but was $viewOffset" }
        val logicalOffset = logicalStartOffset + viewOffset
        return pieces.indexOfFirst { it.startIndex <= logicalOffset && logicalOffset <= it.lastIndex }
    }

    override fun close() {
        super.close()
        if (!closed) {
            file.close()
        }
    }
}