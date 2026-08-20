package com.hfstudio.guidenh

/** Converts the legacy Minecraft formatting stream into safe IDEA documentation HTML. */
object GuideNhMinecraftText {
    private val colorCodes = mapOf(
        '0' to "#000000", '1' to "#0000aa", '2' to "#00aa00", '3' to "#00aaaa", '4' to "#aa0000", '5' to "#aa00aa", '6' to "#ffaa00", '7' to "#aaaaaa", '8' to "#555555", '9' to "#5555ff", 'a' to "#55ff55", 'b' to "#55ffff", 'c' to "#ff5555", 'd' to "#ff55ff", 'e' to "#ffff55", 'f' to "#ffffff"
    )

    fun toHtml(value: String): String {
        val output = StringBuilder()
        var color: String? = null
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        fun open() {
            output.append("<span style=\"")
            color?.let { output.append("color:").append(it) }
            if (bold) output.append(";font-weight:bold")
            if (italic) output.append(";font-style:italic")
            if (underline) output.append(";text-decoration:underline")
            if (strike) output.append(if (underline) " line-through" else ";text-decoration:line-through")
            output.append("\">")
        }
        open()
        var index = 0
        while (index < value.length) {
            if (value[index] == '§' && index + 1 < value.length) {
                when (val code = value[index + 1].lowercaseChar()) {
                    in colorCodes -> { color = colorCodes.getValue(code); bold = false; italic = false; underline = false; strike = false }
                    'l' -> bold = true
                    'o' -> italic = true
                    'n' -> underline = true
                    'm' -> strike = true
                    'r' -> { color = null; bold = false; italic = false; underline = false; strike = false }
                    else -> { index += 2; continue }
                }
                output.append("</span>")
                open()
                index += 2
                continue
            }
            output.append(when (value[index]) {
                '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '\"' -> "&quot;"; '\n' -> "<br/>"; else -> value[index].toString()
            })
            index++
        }
        return output.append("</span>").toString()
    }

    fun strip(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value[index] == '§' && index + 1 < value.length) index += 2
            else append(value[index++])
        }
    }
}
