package net.glotch.antsy

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.*
import androidx.core.content.getSystemService
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import org.apache.commons.collections4.bidimap.DualHashBidiMap
import java.util.*

class TerminalView(context: Context?, attrs: AttributeSet) : View(context, attrs) {
    private val cp437ConversionMap = DualHashBidiMap<Int, Int>()
    private var blinkTimer = Timer()
    private var currentColorIndex = 0
    init {
        // java's builtin cp437 conversion seems to not work right? so we're doing it ourselves baby
        // each high ascii cp437 codepoint is bidirectionally mapped to its unicode equivalent!
        var cp437point = 127
        val highUnicodePoints = intArrayOf(0x2302, // 0x7f
            0x00c7, 0x00fc, 0x00e9, 0x00e2, 0x00e4, 0x00e0, 0x00e5, 0x00e7, 0x00ea, 0x00eb, 0x00e8, 0x00ef, 0x00ee, 0x00ec, 0x00c4, 0x00c5, // 0x8x
            0x00c9, 0x00e6, 0x00c6, 0x00f4, 0x00f6, 0x00f2, 0x00fb, 0x00f9, 0x00ff, 0x00d6, 0x00dc, 0x00a2, 0x00a3, 0x00a5, 0x20a7, 0x0192, // 0x9x
            0x00e1, 0x00ed, 0x00f3, 0x00fa, 0x00f1, 0x00d1, 0x00aa, 0x00ba, 0x00bf, 0x2310, 0x00ac, 0x00bd, 0x00bc, 0x00a1, 0x00ab, 0x00bb, // 0xax
            0x2591, 0x2592, 0x2593, 0x2502, 0x2524, 0x2561, 0x2562, 0x2556, 0x2555, 0x2563, 0x2551, 0x2557, 0x255d, 0x255c, 0x255b, 0x2510, // 0xbx
            0x2514, 0x2534, 0x252c, 0x251c, 0x2500, 0x253c, 0x255e, 0x255f, 0x255a, 0x2554, 0x2569, 0x2566, 0x2560, 0x2550, 0x256c, 0x2567, // 0xcx
            0x2568, 0x2564, 0x2565, 0x2559, 0x2558, 0x2552, 0x2553, 0x256b, 0x256a, 0x2518, 0x250c, 0x2588, 0x2584, 0x258c, 0x2590, 0x2580, // 0xdx
            0x03b1, 0x00df, 0x0393, 0x03c0, 0x03a3, 0x03c3, 0x00b5, 0x03c4, 0x03a6, 0x0398, 0x03a9, 0x03b4, 0x221e, 0x03c6, 0x03b5, 0x2229, // 0xex
            0x2261, 0x00b1, 0x2265, 0x2264, 0x2320, 0x2321, 0x00f7, 0x2248, 0x00b0, 0x2219, 0x00b7, 0x221a, 0x207f, 0x00b2, 0x25a0, 0x00a0) // 0xfx
        while(cp437point <= 0xff) {
            cp437ConversionMap[cp437point] = highUnicodePoints[cp437point - 127]
            cp437point++
        }
        val lowUnicodePoints = intArrayOf(0x263a, 0x263b, 0x2665, 0x2663, 0x2666, 0x2660, 0x2022, 0x25d8, 0x25cb, 0x25d9, 0x2642, 0x2640, 0x266a, 0x266b, 0x263c,
            0x25ba, 0x25c4, 0x2195, 0x203c, 0x00b6, 0x00a7, 0x25ac, 0x21a8, 0x2191, 0x2193, 0x2192, 0x2190, 0x221f, 0x2194, 0x25b2, 0x25bc)
        cp437point = 1
        while(cp437point <= 0x1f) {
            cp437ConversionMap[cp437point] = lowUnicodePoints[cp437point - 1]
            cp437point++
        }

        blinkTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                showBlink = !showBlink
                postInvalidate()
            }
        }, 0, 1000)

        ViewCompat.setOnApplyWindowInsetsListener(this) { view: View, windowInsetsCompat: WindowInsetsCompat ->
            val imeType = WindowInsetsCompat.Type.ime()
            if(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if(windowInsetsCompat.isVisible(imeType)) {
                    val imeHeight = windowInsetsCompat.getInsets(imeType).bottom;
                    updateLayoutParams {
                        height = imeHeight
                        width = imeHeight * (4 / 3)
                    }
                } else {
                    val screenHeight = Resources.getSystem().displayMetrics.heightPixels
                    updateLayoutParams {
                        height = Resources.getSystem().displayMetrics.heightPixels - 200
                        width = screenHeight * (4 / 3)
                        top = 0
                    }
                }

            }
            windowInsetsCompat
        }
    }

    private fun rescheduleBlinkTimer() {
        showBlink = true
        blinkTimer = Timer()
        blinkTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                showBlink = !showBlink
                postInvalidate()
            }
        }, 500, 500)
    }

    private var numColumns = 80
    private var numLines = 25

    private var cursorPos = Array(2) { 0 }

    private val termLines = Array(numLines) {
        ANSIGraphics.newCellArray(numColumns)
    }

    private var fontWidth = 8
    private var fontHeight = 16
    private val fullRect = Rect(0, 0, width, height)
    private val textBGRect = Rect(0, 0, fontWidth + 1, fontHeight)
    private val textBGPaint = Paint().apply {
        style = Paint.Style.FILL
        color = ANSIGraphics.getColor(0, intArrayOf(49))
    }
    private val blackPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val fontPaint = Paint().apply {
        color = Color.WHITE
        context?.obtainStyledAttributes(attrs, R.styleable.TerminalView)?.apply {
            typeface = ResourcesCompat.getFont(context, getResourceId(R.styleable.TerminalView_termFont, 0))
            fontWidth = getInt(R.styleable.TerminalView_termFontWidth, 8)
            fontHeight = getInt(R.styleable.TerminalView_termFontHeight, 16)
        }

    }
    private val cursorRect = Rect(0, fontHeight * (2/3), fontWidth, fontHeight)
    private val whitePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private var showBlink = true
    private var currentGraphicsMode = 0
    private var currentFGColor = ANSIGraphics.getColor(0, intArrayOf(7))
    private var currentBGColor = ANSIGraphics.getColor(0, intArrayOf(0))

    private val inputMethodManager = context?.getSystemService<InputMethodManager>()

    fun lineIn(buf: ByteArray, totalChars: Int) {
        var charsWritten = 0
        //if line gets filled, shift all other lines up
        while(charsWritten < totalChars) {
            charsWritten = writeLine(charsWritten, buf, totalChars)
            if(charsWritten < totalChars) {
                if(cursorPos[1] == numLines - 1) {
                    var lineIndex = 0
                    for(line in termLines) {
                        if(lineIndex < numLines - 1) termLines[lineIndex] = termLines[++lineIndex]
                    }
                    termLines[numLines - 1] = ANSIGraphics.newCellArray(numColumns)
                } else cursorPos[1]++
                cursorPos[0] = 0
            }

        }

        postInvalidate()
    }

    private var currentAnsiCommand = CharArray(20)
    private var currentAnsiCommandIndex = 0
    private var writingAnsiCommand = false
    private var savedCursorPos = Array(2) { 0 }

    private fun processCurrentAnsiCommand() {
        val fullCommand = currentAnsiCommand.concatToString().trimEnd(Char(0))
        //Log.d("ansicommanddetect", fullCommand)



        if(currentAnsiCommand[0] == '[') {
            val command = fullCommand.last()
            val csiArgs = fullCommand.removePrefix("[").dropLast(1).split(';')
            when(command) {
                'H', 'f' -> {
                    // move cursor to (line, column) or home position if args null
                    if(csiArgs.size >= 2) {
                        cursorPos[1] = csiArgs[0].toInt() - 1
                        cursorPos[0] = csiArgs[1].toInt() - 1
                    } else {
                        cursorPos[0] = 0
                        cursorPos[1] = 0
                    }
                }
                'A' -> {
                    // move up n lines
                    if(csiArgs.isNotEmpty()) {
                        cursorPos[1] = (cursorPos[1] - csiArgs[0].toInt()).coerceIn(0, numLines - 1)
                    }
                }
                'B' -> {
                    // move down n lines
                    if(csiArgs.isNotEmpty()) {
                        cursorPos[1] = (cursorPos[1] + csiArgs[0].toInt()).coerceIn(0, numLines - 1)
                    }
                }
                'C' -> {
                    // move right n columns
                    if(csiArgs.isNotEmpty()) {
                        cursorPos[0] = (cursorPos[0] + csiArgs[0].toInt()).coerceIn(0, numColumns - 1)
                    }
                }
                'D' -> {
                    // move left n columns
                    if(csiArgs.isNotEmpty()) {
                        cursorPos[0] = (cursorPos[0] - csiArgs[0].toInt()).coerceIn(0, numColumns - 1)
                    }
                }
                'E' -> {
                    // move to start of line n lines down
                    val linesToMove = if(csiArgs.isNotEmpty()) csiArgs[0].toInt() else 1
                    cursorPos[1] = (cursorPos[1] + linesToMove).coerceIn(0, numLines - 1)
                    cursorPos[0] = 0
                }
                'F' -> {
                    // move to start of line n lines up
                    val linesToMove = if(csiArgs.isNotEmpty()) csiArgs[0].toInt() else 1
                    cursorPos[1] = (cursorPos[1] - linesToMove).coerceIn(0, numLines - 1)
                    cursorPos[0] = 0
                }
                'G' -> {
                    // move to column n
                    if(csiArgs.isNotEmpty()) {
                        cursorPos[1] = csiArgs[0].toInt() - 1
                    }
                }
                'n' -> {
                    // report cursor position
                    if(fullCommand == "[6n") {
                        val toSend = Char(0x1b) + "[" + (cursorPos[1] + 1).toString() + ';' + (cursorPos[0] + 1).toString() + 'R'
                        MainActivity.outStream.write(toSend.toByteArray(Charsets.US_ASCII))
                        Thread {
                            MainActivity.outStream.flush()
                        }.start()
                    }
                }
                's' -> {
                    savedCursorPos = cursorPos
                }
                'u' -> {
                    cursorPos = savedCursorPos
                }
                'J' -> {
                    when(if(fullCommand.length == 3) currentAnsiCommand[1] else '0') {
                        '0' -> {
                            // clears from cursor to end of screen
                            for((curLineCharIndex, _) in termLines[cursorPos[1]].withIndex()) {
                                if(cursorPos[0] <= curLineCharIndex) {
                                    termLines[cursorPos[1]][curLineCharIndex] = ANSIGraphics.defaultCell()
                                }
                            }
                            for((curLineIndex, _) in termLines.withIndex()) {
                                if(curLineIndex > cursorPos[1]) {
                                    termLines[curLineIndex] = ANSIGraphics.newCellArray(numColumns)
                                }
                            }
                        }
                        '1' -> {
                            // clears from cursor to start of screen
                            for((curLineCharIndex, _) in termLines[cursorPos[1]].withIndex()) {
                                if(cursorPos[0] >= curLineCharIndex) {
                                    termLines[cursorPos[1]][curLineCharIndex] = ANSIGraphics.defaultCell()
                                }
                            }
                            for((curLineIndex, _) in termLines.withIndex()) {
                                if(curLineIndex < cursorPos[1]) {
                                    termLines[curLineIndex] = ANSIGraphics.newCellArray(numColumns)
                                }
                            }
                        }
                        '2', '3' -> {
                            // clear entire screen
                            // 3j is supposed to clear scrollback buffer but we don't have that (yet)
                            for((curLineIndex, _) in termLines.withIndex()) {
                                termLines[curLineIndex] = ANSIGraphics.newCellArray(numColumns)
                            }
                        }
                    }
                    postInvalidate()
                }
                'K' -> {
                    when(if(fullCommand.length == 3) currentAnsiCommand[1] else '0') {
                        '0' -> {
                            // clear from cursor to end of line
                            for((curLineCharIndex, _) in termLines[cursorPos[1]].withIndex()) {
                                if(cursorPos[0] <= curLineCharIndex) {
                                    termLines[cursorPos[1]][curLineCharIndex] = ANSIGraphics.defaultCell()
                                }
                            }
                        }
                        '1' -> {
                            // clear from cursor to start of line
                            for((curLineCharIndex, _) in termLines[cursorPos[1]].withIndex()) {
                                if(cursorPos[0] >= curLineCharIndex) {
                                    termLines[cursorPos[1]][curLineCharIndex] = ANSIGraphics.defaultCell()
                                }
                            }
                        }
                        '2' -> {
                            // clear entire line
                            termLines[cursorPos[1]] = ANSIGraphics.newCellArray(numColumns)
                        }
                    }
                    postInvalidate()
                }

                'm' -> {
                    if(csiArgs.isEmpty()) {
                        val newCell = ANSIGraphics.defaultCell()
                        newCell.char = termLines[cursorPos[1]][cursorPos[0]].char
                        termLines[cursorPos[1]][cursorPos[0]] = newCell
                    } else for((i, arg) in csiArgs.withIndex()) {
                        var parsedArg = -1
                        try {
                            parsedArg = arg.toInt()
                        } catch (_: java.lang.NumberFormatException) {
                            Log.w("ansicommand", "Invalid integer in command $fullCommand")
                            break
                        }
                        if (parsedArg >= 0) {
                            var modeToSet = 0
                            val unset = parsedArg in 22..29
                            when (parsedArg) {
                                0 -> {
                                    currentFGColor = ANSIGraphics.getColor(0, intArrayOf(39))
                                    currentBGColor = ANSIGraphics.getColor(0, intArrayOf(49))
                                    currentGraphicsMode = 0
                                }
                                22 -> {
                                    modeToSet = ANSIGraphics.GraphicsMode.BOLD and ANSIGraphics.GraphicsMode.DIM
                                    if(currentFGColor == ANSIGraphics.getColor(0, intArrayOf(97))) {
                                        currentFGColor = ANSIGraphics.getColor(0, intArrayOf(39))
                                    }
                                }
                                1 -> {
                                    modeToSet = ANSIGraphics.GraphicsMode.BOLD
                                    if(currentFGColor == ANSIGraphics.getColor(0, intArrayOf(39))) {
                                        currentFGColor = ANSIGraphics.getColor(0, intArrayOf(97))
                                    }
                                }
                                2 -> modeToSet = ANSIGraphics.GraphicsMode.DIM
                                3, 23 -> modeToSet = ANSIGraphics.GraphicsMode.ITALIC
                                4, 24 -> modeToSet = ANSIGraphics.GraphicsMode.UNDERLINE
                                5, 25 -> modeToSet = ANSIGraphics.GraphicsMode.BLINK
                                7, 27 -> modeToSet = ANSIGraphics.GraphicsMode.INVERSE
                                8, 28 -> modeToSet = ANSIGraphics.GraphicsMode.HIDDEN
                                9, 29 -> modeToSet = ANSIGraphics.GraphicsMode.STRIKETHROUGH
                                in 30..37, 39, in 90..97 -> {
                                    // foreground colors
                                    if(currentGraphicsMode and ANSIGraphics.GraphicsMode.BOLD == ANSIGraphics.GraphicsMode.BOLD
                                        && parsedArg <= 39) {
                                        if (parsedArg == 39) parsedArg = 97 // default -> bright white when bold
                                        else parsedArg += 60
                                    }
                                    currentFGColor = ANSIGraphics.getColor(0, intArrayOf(parsedArg))
                                }
                                in 40..47, 49, in 100..107 -> {
                                    // background colors
                                    // no bright backgrounds when using the traditional bold color method!
                                    currentBGColor = ANSIGraphics.getColor(0, intArrayOf(parsedArg))
                                }
                            }

                            if (!unset) currentGraphicsMode =
                                currentGraphicsMode or modeToSet
                            else termLines[cursorPos[1]][cursorPos[0]].graphicsMode =
                                currentGraphicsMode xor modeToSet
                        }
                    }
                }
            }
        } else when(currentAnsiCommand[0]) {
            '7' -> {
                savedCursorPos = cursorPos
            }
            '8' -> {
                cursorPos = savedCursorPos
            }
            'M' -> {
                // scroll cursor one line up
                cursorPos[1] = (cursorPos[1]--).coerceIn(0, numLines)
            }
        }

        currentAnsiCommand = CharArray(20)
    }

    private fun writeLine(charsWritten: Int, buf: ByteArray, totalChars: Int): Int {
        // return value >= totalChars means "stream over, stop writing to output!"
        // return value < totalChars means "this line over, write to a new line starting at this index in buf!"
        var written = charsWritten
        showBlink = true
        //if(cursorPos[0] >= numColumns) cursorPos[0] = 0
        while(cursorPos[0] < numColumns) {
            // the shl/ushr strips the high bits from the int to allow values over 127... thanks java
            // this also caps it at 255, however should not be a problem if only cp437 chars are being sent
            if(written > buf.size - 1) {
                return totalChars
            }
            MainActivity.dumpStream?.write(buf[written].toInt())
            val newCharCode = buf[written].toInt() shl 24 ushr 24
            if(newCharCode == 0) return totalChars
            if(writingAnsiCommand) {
                // TODO other ansi command types exist other than control sequence indicator. maybe add those?
                if(currentAnsiCommandIndex > 19) {
                    // just toss it out, ansi commands should NOT be this long
                    writingAnsiCommand = false
                    currentAnsiCommandIndex = 0
                } else if(newCharCode == '['.code && currentAnsiCommandIndex == 0) {
                    currentAnsiCommand[0] = '['
                    currentAnsiCommandIndex++
                } else if(currentAnsiCommand[0] == '[') {
                    when(newCharCode) {
                        in 0x30..0x3f -> {
                            currentAnsiCommand[currentAnsiCommandIndex++] = Char(newCharCode)
                        }
                        in 0x40..0x7e -> {
                            currentAnsiCommand[currentAnsiCommandIndex++] = Char(newCharCode)
                            writingAnsiCommand = false
                            currentAnsiCommandIndex = 0
                            processCurrentAnsiCommand()
                        }
                    }
                } else if(newCharCode <= 0x7e) {
                    // there's a lot of single byte and even some more multibyte ansi commands
                    // this catches all of them but not even close to all of them will be supported
                    currentAnsiCommand[0] = Char(newCharCode)
                    writingAnsiCommand = false
                    processCurrentAnsiCommand()

                }
                written++
                continue
            }

            if(newCharCode == 0x1b) { // ansi escape char
                writingAnsiCommand = true
                written++
                continue
            }
            if(newCharCode == '\r'.code) {
                cursorPos[0] = 0
                written++
                continue
            }
            if(newCharCode == '\n'.code) {
                return written + 1
            }
            if(newCharCode == '\b'.code) {
                if(cursorPos[0] > 0) {
                    termLines[cursorPos[1]][--cursorPos[0]].char = '\u0000'
                }
                written++
                continue
            }
            if(newCharCode == '\t'.code) {
                Log.d("horitab", "HORIZ TAB")
                //TODO horizontal tab maybe?
            }
            if(newCharCode == 0x0c) {
                //TODO form feed
                written++
                continue
            }
            termLines[cursorPos[1]][cursorPos[0]] = ANSIGraphics.Cell(
                (if(cp437ConversionMap.containsKey(newCharCode)) cp437ConversionMap[newCharCode]?.let { Char(it) } else Char(newCharCode))!!,
                currentFGColor,
                currentBGColor,
                currentGraphicsMode)

            cursorPos[0]++
            written++

            if(cursorPos[0] >= numColumns) {
                cursorPos[0] = 0
                return written
            }

            if(written >= totalChars) {
                return totalChars
            }
        }
        rescheduleBlinkTimer()
        return totalChars
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)




        fullRect.right = width
        fullRect.bottom = height
        fontPaint.textSize = (width.toFloat() * fontHeight) / (fontWidth.toFloat() * numColumns)
        textBGRect.set(0, 0, width / numColumns, fontPaint.textSize.toInt())
        canvas?.apply {
            drawRect(fullRect, blackPaint)
            var line = 1
            while(line < numLines + 1) {
                for((charIndex, cell) in termLines[line - 1].withIndex()) {
                    textBGRect.offsetTo((width / numColumns) * charIndex,
                        ((fontPaint.textSize * (line - 1)) + (fontPaint.textSize / 4)).toInt()
                    )
                    textBGPaint.color = cell.bgColor
                    drawRect(textBGRect, textBGPaint)

                    fontPaint.color = cell.fgColor
                    if(cell.char.code == 0) cell.char = ' '
                    val blinkText = cell.graphicsMode and ANSIGraphics.GraphicsMode.BLINK == ANSIGraphics.GraphicsMode.BLINK
                    if(!blinkText || (blinkText && showBlink))
                        drawText(cell.char.toString(), ((width / numColumns) * charIndex).toFloat(), fontPaint.textSize * line, fontPaint)
                }
                line++
                //drawText(termLines[line - 1].concatToString().replace('\u0000', ' '), 0f, fontPaint.textSize * line++, fontPaint)
            }
            if(showBlink) {
                cursorRect.bottom = (fontPaint.textSize * (cursorPos[1] + 1)).toInt()
                cursorRect.top = cursorRect.bottom - 2//(cursorRect.bottom.toFloat() - (fontPaint.textSize * (1/3))).toInt()
                cursorRect.left = (width / numColumns) * cursorPos[0]
                cursorRect.right = cursorRect.left + fontWidth // FIXME THIS WON'T SCALE RIGHT! GET A SCALAR IN THERE!
                drawRect(cursorRect, whitePaint)
            }
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if(gainFocus) inputMethodManager?.showSoftInput(this, 0)
        else inputMethodManager?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection {
        if(outAttrs != null) {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) outAttrs.contentMimeTypes = null
            outAttrs.initialSelEnd = -1
            outAttrs.initialSelStart = -1
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.packageName = BuildConfig.APPLICATION_ID
            outAttrs.actionId = EditorInfo.IME_ACTION_UNSPECIFIED
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        return TerminalInputConnection(inputMethodManager, this)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        inputMethodManager?.showSoftInput(this, 0)
        return super.onTouchEvent(event)
    }



    override fun checkInputConnectionProxy(view: View?): Boolean {
        return true
    }

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    private fun streamKeyPress(keyCode: Int) {
        MainActivity.outStream.write(keyCode)
        Thread {
            MainActivity.outStream.flush()
        }.start()
    }

        inner class TerminalInputConnection(inputMethodManager: InputMethodManager?, view: View) : InputConnection {
            private var lastComposeSpanLength = 0
            private val manager = inputMethodManager
            private val connectedView = view


            override fun getTextBeforeCursor(p0: Int, p1: Int): CharSequence? {
                return ""
            }

            override fun getTextAfterCursor(p0: Int, p1: Int): CharSequence? {
                return ""
            }

            override fun getSelectedText(p0: Int): CharSequence? {
                return ""
            }

            override fun getCursorCapsMode(p0: Int): Int {
                TODO("Not yet implemented")
            }

            override fun getExtractedText(p0: ExtractedTextRequest?, p1: Int): ExtractedText {
                return ExtractedText()
            }

            override fun deleteSurroundingText(p0: Int, p1: Int): Boolean {
                return true
            }

            override fun deleteSurroundingTextInCodePoints(p0: Int, p1: Int): Boolean {
                return true
            }

            override fun setComposingText(sequence: CharSequence?, p1: Int): Boolean {
                return true
            }

            override fun setComposingRegion(p0: Int, p1: Int): Boolean {
                return true
            }

            override fun finishComposingText(): Boolean {
                return true
            }

            override fun commitText(p0: CharSequence?, p1: Int): Boolean {
                //all non-control chars come through commit text (mostly), all control chars come thru key event
                // TODO convert to cp437
                p0?.get(0)?.code?.let { streamKeyPress(it) }
                return true
            }

            override fun commitCompletion(p0: CompletionInfo?): Boolean {
                return true
            }

            override fun commitCorrection(p0: CorrectionInfo?): Boolean {
                return true
            }

            override fun setSelection(p0: Int, p1: Int): Boolean {
                return true
            }

            override fun performEditorAction(p0: Int): Boolean {
                return true
            }

            override fun performContextMenuAction(p0: Int): Boolean {
                return true
            }

            override fun beginBatchEdit(): Boolean {
                return true
            }

            override fun endBatchEdit(): Boolean {
                return true
            }

            override fun sendKeyEvent(e: KeyEvent?): Boolean {
                if(e?.action == KeyEvent.ACTION_DOWN) {
                    when(e.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> {
                            streamKeyPress('\r'.code)
                            streamKeyPress('\n'.code)
                        }
                        KeyEvent.KEYCODE_DEL -> {
                            streamKeyPress(0x08) //backspace
                        }
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                            //for some reason number keys at least on gboard are sent as key events instead of commited text
                            //but only if it's on the main kb and not in the symbols kb?
                            streamKeyPress(e.keyCharacterMap.get(e.keyCode, e.metaState))
                        }
                    }
                }

                return true
            }

            override fun clearMetaKeyStates(p0: Int): Boolean {
                TODO("Not yet implemented")
            }

            override fun reportFullscreenMode(p0: Boolean): Boolean {
                return Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1;
            }

            override fun performPrivateCommand(p0: String?, p1: Bundle?): Boolean {
                TODO("Not yet implemented")
            }

            override fun requestCursorUpdates(p0: Int): Boolean {
                return false
            }

            override fun getHandler(): Handler? {
                return null
            }

            override fun closeConnection() {
                return
            }

            override fun commitContent(p0: InputContentInfo, p1: Int, p2: Bundle?): Boolean {
                return false
            }
        }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null) {
            if(keyCode == KeyEvent.KEYCODE_DEL) {
                streamKeyPress(0x08) //backspace
            } else {
                val code = event.keyCharacterMap.get(keyCode, event.metaState)
                streamKeyPress(code)
            }
        }
        return true
    }
}