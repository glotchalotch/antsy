package net.glotch.antsy

import android.graphics.Color
import android.util.Log
import androidx.annotation.ColorInt
import kotlin.math.nextDown
import kotlin.math.roundToInt

class ANSIGraphics {
    class GraphicsMode {
        companion object {
            // bitfields :D
            val BOLD = 1
            val DIM = 2
            val ITALIC = 4
            val UNDERLINE = 8
            val BLINK = 16
            val INVERSE = 32
            val HIDDEN = 64
            val STRIKETHROUGH = 128
        }
    }
    companion object {
        @ColorInt
        fun getColor(mode: Int, colorIndices: IntArray): Int {
            when(mode) {
                2 -> {
                    // 24 bit color mode

                }
                5 -> {
                    // 256 color mode
                    if(colorIndices.isNotEmpty()) {
                        when(colorIndices[0]) {
                            in 0..15 -> {
                                return getColor16(colorIndices[0])
                            }
                            in 16..231 -> {
                                val colorBase = colorIndices[0] - 16
                                val col = colorBase % 36
                                val rIndex = colorBase / 36 // row of color chart
                                val gIndex = col / 6 // "block" of color chart
                                val bIndex = col % 6

                                val colorByteArray = intArrayOf(0x00, 0x5f, 0x87, 0xaf, 0xd7, 0xff)

                                val alphaByte = 255 shl 24
                                val redByte = colorByteArray[rIndex] shl 16
                                val greenByte = colorByteArray[gIndex] shl 8
                                val blueByte = colorByteArray[bIndex]

                                return alphaByte or redByte or greenByte or blueByte

                            }
                            in 232..255 -> {
                                // grayscale is literally just 24 steps from 0x08 to 0xee
                                // it's as shrimple as that
                                val colorByte = (10 * (colorIndices[0] - 232)) + 8
                                return Color.rgb(colorByte, colorByte, colorByte)
                            }
                        }
                    }
                }
                else -> {
                    if(colorIndices.isNotEmpty()) {
                        return getColor16(colorIndices[0])
                    }
                }
            }
            return Color.BLACK
        }

        @ColorInt
        private fun getColor16(index: Int): Int {
            val newIndex = when(index) {
                in 100..107 -> index - 92
                in 90..97 -> index - 82
                in 40..47 -> index - 40
                in 30..37 -> index - 30
                39 -> 7 // default fg color
                49 -> 0 // default bg color
                else -> index
            }
            return when(newIndex) {
                0 -> Color.rgb(0, 0, 0) // black
                1 -> Color.rgb(170, 0 , 0) // red
                2 -> Color.rgb(0, 170, 0) // green
                3 -> Color.rgb(170, 85, 0) // "yellow" actually orange
                4 -> Color.rgb(0, 0, 170) // blue
                5 -> Color.rgb(170, 0, 170) // magenta
                6 -> Color.rgb(0, 170, 170) // cyan
                7 -> Color.rgb(170, 170, 170) // "white"
                8 -> Color.rgb(85, 85, 85) // "bright black" (gray)
                9 -> Color.rgb(255, 85, 85) // bright red
                10 -> Color.rgb(85, 255, 85) // bright green
                11 -> Color.rgb(255, 255, 85) // bright yellow
                12 -> Color.rgb(85, 85, 255) // bright blue
                13 -> Color.rgb(255, 85, 255) // bright magenta
                14 -> Color.rgb(85, 255, 255) // bright cyan
                15 -> Color.rgb(255, 255, 255) // bright white
                else -> {
                    Log.w("ANSIColors", "Invalid 16 color index $index")
                    Color.TRANSPARENT
                }
            }
        }

        fun newCellArray(length: Int): Array<Cell> {
            return Array(length) {
                defaultCell()
            }
        }

        fun defaultCell(): Cell {
            return Cell('\u0000', getColor16(39), getColor16(49), 0)
        }
    }

    data class Cell(var char: Char, @ColorInt var fgColor: Int, @ColorInt var bgColor: Int, var graphicsMode: Int)
}