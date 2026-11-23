package dev.wimmboo.omni.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Prism Tomorrow syntax highlighting ported natively to Compose AnnotatedString,
 * matching prism-tomorrow.min.css + diff highlighting in index.html.
 */
object CodeHighlighter {
    private val plainColor = Color(0xFFCCCCCC)
    private val commentColor = Color(0xFF999999)
    private val keywordColor = Color(0xFFCC99CD)
    private val stringColor = Color(0xFF7EC699)
    private val numberColor = Color(0xFFF08D49)
    private val functionColor = Color(0xFFF08D49)
    private val typeColor = Color(0xFFF8C555)
    private val operatorColor = Color(0xFF67CDCC)
    private val tagColor = Color(0xFFE2777A)
    private val diffAddFg = Color(0xFF7AAD8A)
    private val diffAddBg = Color(0x267AAD8A)
    private val diffDelFg = Color(0xFFD47878)
    private val diffDelBg = Color(0x26D47878)

    private val COMMON_KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data", "override",
        "private", "public", "protected", "internal", "companion", "suspend", "inline", "return",
        "if", "else", "when", "for", "while", "do", "try", "catch", "finally", "throw", "import",
        "package", "is", "in", "as", "by", "this", "super", "null", "true", "false",
        "def", "lambda", "async", "await", "yield", "from", "with", "pass", "break", "continue",
        "None", "True", "False", "and", "or", "not", "elif", "except", "raise", "global", "nonlocal",
        "function", "const", "let", "new", "export", "default", "extends", "implements", "typeof",
        "instanceof", "void", "delete", "switch", "case", "undefined", "of", "type", "readonly",
        "fn", "mut", "pub", "struct", "trait", "impl", "match", "mod", "use", "crate", "self", "Self",
        "func", "go", "defer", "chan", "select", "range", "map", "int", "float", "double", "char",
        "bool", "boolean", "string", "static", "final", "abstract", "synchronized", "volatile",
        "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY",
        "ORDER", "LIMIT", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE",
        "DROP", "ALTER", "AND", "OR", "NOT", "AS", "DISTINCT", "HAVING", "UNION", "NULL",
        "select", "from", "where", "join", "group", "order", "limit", "insert", "update", "delete",
        "echo", "export", "local", "then", "fi", "done", "esac", "elif",
    )

    private val TOKEN_REGEX = Regex(
        pattern = "(//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?\\*/|--[^\\n]*|" +
            "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|" +
            "\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|`(?:\\\\.|[^`\\\\])*`|" +
            "@[A-Za-z_][\\w.]*|" +
            "</?[A-Za-z][\\w:-]*|/?\\s*>|" +
            "\\b0[xX][0-9a-fA-F_]+\\b|\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b|" +
            "\\b[A-Za-z_][\\w]*\\b)",
    )

    fun highlight(code: String, language: String): AnnotatedString {
        val lang = language.lowercase().trim()
        if (lang == "diff" || lang == "patch") {
            return highlightDiff(code)
        }
        val isHashComment = lang in setOf("python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "ruby", "rb", "toml", "r")
        val isMarkup = lang in setOf("html", "htm", "xml", "svg", "web")
        val isSql = lang == "sql"
        val isJson = lang == "json"

        return buildAnnotatedString {
            append(code)
            addStyle(SpanStyle(color = plainColor), 0, code.length)

            for (m in TOKEN_REGEX.findAll(code)) {
                val tok = m.value
                val start = m.range.first
                val end = m.range.last + 1

                val color: Color? = when {
                    tok.startsWith("/*") || tok.startsWith("//") -> commentColor
                    tok.startsWith("#") && isHashComment -> commentColor
                    tok.startsWith("--") && isSql -> commentColor
                    tok.startsWith("\"") || tok.startsWith("'") || tok.startsWith("`") -> {
                        if (isJson && end < code.length && code.substring(end).trimStart().startsWith(":")) {
                            tagColor
                        } else {
                            stringColor
                        }
                    }
                    tok.startsWith("@") -> tagColor
                    isMarkup && (tok.startsWith("<") || tok.endsWith(">")) -> tagColor
                    tok[0].isDigit() -> numberColor
                    tok in COMMON_KEYWORDS -> keywordColor
                    tok[0].isUpperCase() && tok.length > 1 && tok.any { it.isLowerCase() } -> typeColor
                    end < code.length && code[end] == '(' -> functionColor
                    else -> null
                }

                if (color != null) {
                    addStyle(SpanStyle(color = color), start, end)
                }
            }
        }
    }

    private fun highlightDiff(code: String): AnnotatedString = buildAnnotatedString {
        val lines = code.split('\n')
        lines.forEachIndexed { idx, line ->
            val start = length
            append(line)
            if (idx < lines.lastIndex) append('\n')
            val end = length
            when {
                line.startsWith("+") && !line.startsWith("+++") ->
                    addStyle(SpanStyle(color = diffAddFg, background = diffAddBg), start, end)
                line.startsWith("-") && !line.startsWith("---") ->
                    addStyle(SpanStyle(color = diffDelFg, background = diffDelBg), start, end)
                line.startsWith("@@") ->
                    addStyle(SpanStyle(color = operatorColor), start, end)
                else ->
                    addStyle(SpanStyle(color = plainColor), start, end)
            }
        }
    }

    fun fileExtensionFor(lang: String): String = when (lang.lowercase().trim()) {
        "javascript", "js" -> "js"
        "jsx" -> "jsx"
        "typescript", "ts" -> "ts"
        "tsx" -> "tsx"
        "python", "py" -> "py"
        "html", "htm", "web" -> "html"
        "css" -> "css"
        "json" -> "json"
        "rust", "rs" -> "rs"
        "cpp", "c++" -> "cpp"
        "c" -> "c"
        "cs", "csharp" -> "cs"
        "java" -> "java"
        "kotlin", "kt" -> "kt"
        "go" -> "go"
        "php" -> "php"
        "ruby", "rb" -> "rb"
        "sql" -> "sql"
        "bash", "sh", "shell", "zsh" -> "sh"
        "yaml", "yml" -> "yml"
        "xml" -> "xml"
        "svg" -> "svg"
        "markdown", "md" -> "md"
        else -> "txt"
    }

    fun isPreviewable(lang: String): Boolean {
        val l = lang.lowercase().trim()
        return l == "html" || l == "svg" || l == "xml" || l == "web"
    }
}
