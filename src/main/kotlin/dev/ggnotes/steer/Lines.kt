package dev.ggnotes.steer

/** gg's line rules for the session memory (`agentdocs.Lines`) and the "landed" reply prose. */
object Lines {

    /** CRLF and a lone CR are line breaks, trailing breaks are dropped, no lines = nothing to note. */
    fun split(text: CharSequence): List<String> {
        val s = text.toString().replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        if (s.isEmpty()) return emptyList()
        return s.split('\n')
    }

    /** `<lead> at line <n>`; a line past the end lands on the last one and says so; no line → the lead alone. */
    fun landed(lead: String, line: Int, lineCount: Int): String {
        if (line <= 0 || lineCount <= 0) return lead
        return if (line > lineCount) "$lead at line $lineCount (line $line is past the end, $lineCount lines)"
        else "$lead at line $line"
    }
}
