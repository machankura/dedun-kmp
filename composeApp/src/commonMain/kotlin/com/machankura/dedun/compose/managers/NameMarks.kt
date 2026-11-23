package com.machankura.dedun.compose.managers

/**
 * The braces drawn around a name, and the marks older names were stored with, taken off.
 *
 * Giza shares it between [MarmotGroupName] and its `ConfederationName`, which Dedun does not
 * take (docs/giza-second-batch.md, D12). docs/group-name-braces.md, D1.
 */
internal object NameMarks {
    /**
     * [name] trimmed, without its leading run of whitespace, `{` and [marks], and without as
     * many trailing `}` as there were `{` in that run. Empty when nothing else is left.
     *
     * A closing brace comes off only when an opening one did, so a name whose real last
     * character is `}` -- `Set {a}` -- keeps it.
     */
    fun strip(name: String, marks: CharArray): String {
        val text = name.trim()
        var start = 0
        var opened = 0
        while (start < text.length && (text[start].isWhitespace() || text[start] == '{' || text[start] in marks)) {
            if (text[start] == '{') opened++
            start++
        }

        var end = text.length
        var closed = 0
        while (true) {
            while (end > start && text[end - 1].isWhitespace()) end--
            if (closed < opened && end > start && text[end - 1] == '}') {
                end--
                closed++
            } else {
                break
            }
        }

        return text.substring(start, end)
    }
}
