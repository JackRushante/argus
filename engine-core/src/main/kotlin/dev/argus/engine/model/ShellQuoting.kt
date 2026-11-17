package dev.argus.engine.model

/**
 * Quoting POSIX sh per i dati ESTERNI (TAINTED) interpolati in un comando shell (D1).
 *
 * Il template di `run_shell.cmd` è fingerprintato e resta letterale; solo il VALORE di una
 * variabile TAINTED viene reso "inerte" al momento dell'interpolazione, così un SMS/notifica con
 * `; rm -rf /`, `$(…)` o apici non può cambiare la struttura del comando approvato. L'impronta non
 * cambia perché il template non cambia: il quoting avviene solo a runtime.
 *
 * Il quoting dipende da DOVE cade il segnaposto nel template, perché lo stesso testo è pericoloso in
 * modo diverso a seconda del contesto:
 *  - [Context.WORD] (fuori da apici): il valore è avvolto in `'…'` con `'` → `'\''` ([quote]);
 *  - [Context.DOUBLE_QUOTED] (`"…${x}…"`): dentro i doppi apici `$(…)`, `` ` `` e `$VAR` restano
 *    ATTIVI, quindi avvolgere in apici singoli non basta: si fa escape di `\`, `"`, `$`, `` ` ``;
 *  - [Context.SINGLE_QUOTED] (`'…${x}…'`): si chiude, si inserisce `\'` e si riapre (`'` → `'\''`);
 *  - [Context.NUMERIC]: la shell valuta il valore come espressione aritmetica (operandi di `-eq`…
 *    in `[`/`test`/`[[`, `(( … ))`, `let`, `typeset -i`, indici di array, variabili intere). La
 *    valutazione espande le sostituzioni di comando contenute nel testo anche se è tra apici: il
 *    quoting non basta, conta solo il valore, che deve essere un intero `^-?[0-9]{1,18}$`;
 *  - [Context.UNSAFE]: contesto che non sappiamo rendere sicuro (sostituzione di comando aperta,
 *    backtick, here-doc, commento, `$'…'`, backslash o `$` subito prima del segnaposto, posizione
 *    aritmetica con testo attaccato, nome di variabile). Il valore TAINTED NON viene inserito:
 *    l'azione è bloccata (fail-closed);
 *  - [Context.COMMAND_POSITION]: il segnaposto è (parte del) nome del comando: il dato sceglierebbe
 *    quale programma parte. Sempre bloccato;
 *  - [Context.REEVALUATED]: il comando rilegge i suoi argomenti come codice (`sh -c`, `eval`, `su`,
 *    `xargs`, `awk`, `find -exec`…) o legge come codice ciò che arriva da una pipe: gli apici
 *    proteggono solo il primo parsing. Sempre bloccato.
 *
 * Il contesto si calcola in due passi: uno lessicale (apici, escape, costrutti non modellati) e uno
 * strutturale (comandi semplici, parole, posizione del comando, contesti aritmetici, variabili della
 * shell che conservano il dato). Vince il verdetto più restrittivo.
 */
object ShellQuoting {
    /** Contesto di un segnaposto `${nome}` nel template del comando. */
    enum class Context { WORD, SINGLE_QUOTED, DOUBLE_QUOTED, NUMERIC, UNSAFE, COMMAND_POSITION, REEVALUATED }

    /**
     * Esito per un segnaposto. [optionLike] è vero quando il segnaposto apre una parola (argomento di
     * un comando) e nessun `--` lo precede nel comando: un valore che inizia con `-` diventerebbe
     * un'opzione del comando.
     */
    data class Placement(val context: Context, val optionLike: Boolean)

    private val SHELL_INTEGER = Regex("^-?[0-9]{1,18}$")

    /** Il solo valore non fidato ammesso in un [Context.NUMERIC]: cifre e un segno, inerti ovunque. */
    fun isShellInteger(text: String): Boolean = SHELL_INTEGER.matches(text)

    /** `'` + testo con `'` sostituito da `'\''` + `'`: sempre un solo token, mai espanso dalla shell. */
    fun quote(text: String): String = "'" + escapeForSingleQuotes(text) + "'"

    /** Per un segnaposto già dentro apici singoli: chiude, aggiunge un `'` letterale, riapre. */
    fun escapeForSingleQuotes(text: String): String = text.replace("'", "'\\''")

    /** Per un segnaposto dentro doppi apici: neutralizza i soli caratteri che restano attivi. */
    fun escapeForDoubleQuotes(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            if (c == '\\' || c == '"' || c == '$' || c == '`') append('\\')
            append(c)
        }
    }

    /** Resa del valore per il contesto; null se il contesto non ammette il valore (il chiamante blocca). */
    fun render(text: String, context: Context): String? = when (context) {
        Context.WORD -> quote(text)
        Context.SINGLE_QUOTED -> escapeForSingleQuotes(text)
        Context.DOUBLE_QUOTED -> escapeForDoubleQuotes(text)
        // Cifre e segno non hanno significato per la shell in nessun contesto lessicale.
        Context.NUMERIC -> text.takeIf(::isShellInteger)
        Context.UNSAFE, Context.COMMAND_POSITION, Context.REEVALUATED -> null
    }

    /**
     * Contesto di ciascun segnaposto, nell'ordine in cui compaiono. [placeholders] sono gli
     * intervalli (ordinati, non sovrapposti) di ogni `${nome}` valido nel [template].
     */
    fun contexts(template: String, placeholders: List<IntRange>): List<Context> =
        placements(template, placeholders).map { it.context }

    /** Come [contexts], con in più il rischio "valore letto come opzione" di ogni segnaposto. */
    fun placements(template: String, placeholders: List<IntRange>): List<Placement> {
        val lexical = lexicalScan(template, placeholders)
        val structure = StructureScan(template, placeholders, lexical.opaqueStart).run()
        return placeholders.indices.map { index ->
            val lexicalContext = lexical.contexts[index]
            val structural = structure.verdicts[index]
            val context = if (structural != null && structural.severity() > lexicalContext.severity()) {
                structural
            } else {
                lexicalContext
            }
            Placement(context, context.severity() == 0 && structure.optionLike[index])
        }
    }

    private fun Context.severity(): Int = when (this) {
        Context.WORD, Context.SINGLE_QUOTED, Context.DOUBLE_QUOTED -> 0
        Context.NUMERIC -> 1
        Context.UNSAFE -> 2
        Context.COMMAND_POSITION, Context.REEVALUATED -> 3
    }

    private class Lexical(val contexts: List<Context>, val opaqueStart: Int)

    /**
     * Passo lessicale: lo scanner legge il template da sinistra a destra e il contesto di un segnaposto
     * dipende solo dal testo che lo precede. Fail-closed: una volta incontrata una costruzione che lo
     * scanner non modella (`$(`, backtick, `$'`, `<<`, commento), ogni segnaposto successivo è
     * [Context.UNSAFE]; [Lexical.opaqueStart] è dove quella costruzione inizia.
     */
    private fun lexicalScan(template: String, placeholders: List<IntRange>): Lexical {
        val result = ArrayList<Context>(placeholders.size)
        var next = 0
        var state = Context.WORD
        var opaqueStart = -1
        var i = 0
        val n = template.length

        fun placeholderAt(index: Int): Boolean =
            next < placeholders.size && placeholders[next].first == index

        fun consumePlaceholder(context: Context) {
            result += context
            i = placeholders[next].last + 1
            next++
        }

        fun opaque() {
            if (opaqueStart < 0) opaqueStart = i
        }

        while (i < n) {
            if (placeholderAt(i)) {
                val glued = i > 0 && template[i - 1] == '$' && state != Context.SINGLE_QUOTED
                // Un `$` subito prima del valore lo trasformerebbe in `$'…'`, `$(…)` o `$NOME`.
                consumePlaceholder(if (opaqueStart >= 0 || glued) Context.UNSAFE else state)
                continue
            }
            val c = template[i]
            val following = template.getOrNull(i + 1)
            when (state) {
                Context.SINGLE_QUOTED -> if (c == '\'') state = Context.WORD
                Context.WORD, Context.DOUBLE_QUOTED -> {
                    val inDouble = state == Context.DOUBLE_QUOTED
                    when {
                        c == '\\' -> {
                            // Il backslash escapa il carattere successivo: se è il segnaposto, il
                            // valore verrebbe attaccato a un escape e cambierebbe la struttura.
                            if (placeholderAt(i + 1)) {
                                i++
                                consumePlaceholder(Context.UNSAFE)
                            } else {
                                i += 2
                            }
                            continue
                        }
                        c == '`' -> opaque()
                        c == '$' && (following == '(' || following == '{') -> opaque()
                        inDouble && c == '"' -> state = Context.WORD
                        !inDouble && c == '"' -> state = Context.DOUBLE_QUOTED
                        !inDouble && c == '\'' -> state = Context.SINGLE_QUOTED
                        !inDouble && c == '$' && following == '\'' -> opaque()
                        !inDouble && c == '#' && startsWord(template, i) -> opaque()
                        !inDouble && c == '<' && following == '<' -> opaque()
                    }
                }
                else -> error("lo stato lessicale è sempre WORD, SINGLE_QUOTED o DOUBLE_QUOTED")
            }
            i++
        }
        // Segnaposto non raggiunti (intervalli incoerenti col template): mai un contesto ottimista.
        while (result.size < placeholders.size) result += Context.UNSAFE
        return Lexical(result, if (opaqueStart < 0) n else opaqueStart)
    }

    private fun startsWord(template: String, index: Int): Boolean =
        index == 0 || template[index - 1].isWhitespace() || template[index - 1] in ";&|()<>"

    // ---------------------------------------------------------------------------------------------
    // Passo strutturale
    // ---------------------------------------------------------------------------------------------

    /** Carattere che sostituisce un segnaposto nel testo letterale di una parola. */
    private const val MARKER = '\uE000'

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val RAW_REFERENCE = Regex("""\$\{?([A-Za-z_][A-Za-z0-9_]*)""")
    private val ASSIGNMENT = Regex("""^([A-Za-z_][A-Za-z0-9_]*)(\[[^\]]*\])?\+?=""")
    private const val ARITHMETIC_NEIGHBOURS = " \t\n+-*/%<>=!&|^~?:,()"
    private val NUMERIC_TEST_OPERATORS = setOf("-eq", "-ne", "-gt", "-ge", "-lt", "-le")

    /** Comandi che rileggono i propri argomenti come codice (o lanciano un altro comando). */
    private val REEVALUATING = setOf(
        "sh", "bash", "mksh", "ksh", "lksh", "ash", "dash", "zsh", "su", "eval", "source", ".", "trap",
        "xargs", "awk", "gawk", "mawk", "nawk", "sed", "perl", "lua", "sqlite3", "ssh", "adb",
        "sudo", "doas", "run-as", "chroot", "nsenter", "unshare", "watch", "script", "flock",
    )

    /** Comandi che eseguono come codice ciò che ricevono sullo standard input. */
    private val STDIN_CODE = setOf(
        "sh", "bash", "mksh", "ksh", "lksh", "ash", "dash", "zsh", "su", "xargs", "sqlite3", "ssh", "adb",
        "perl", "lua", "sudo", "doas",
    )

    private val FIND_EXEC = setOf("-exec", "-execdir", "-ok", "-okdir")

    private class Wrapper(
        val optionsWithArgument: Set<String> = emptySet(),
        val positional: Int = 0,
        val assignments: Boolean = false,
        val splitString: Boolean = false,
    )

    /** Comandi che eseguono le parole seguenti come comando: si guarda attraverso. */
    private val WRAPPERS = mapOf(
        "env" to Wrapper(setOf("-u", "--unset", "-C", "--chdir"), assignments = true, splitString = true),
        "nohup" to Wrapper(),
        "timeout" to Wrapper(setOf("-s", "--signal", "-k", "--kill-after"), positional = 1),
        "nice" to Wrapper(setOf("-n", "--adjustment")),
        "setsid" to Wrapper(),
        "time" to Wrapper(),
        "command" to Wrapper(),
        "builtin" to Wrapper(),
        "exec" to Wrapper(setOf("-a")),
        "busybox" to Wrapper(),
        "toybox" to Wrapper(),
    )

    private val DECLARATIONS = setOf("typeset", "declare", "local", "export", "readonly", "integer", "nameref")

    /** Variabili speciali di mksh con attributo intero: un'assegnazione le valuta come espressione. */
    private val INTEGER_SPECIALS = setOf(
        "RANDOM", "SECONDS", "LINENO", "OPTIND", "COLUMNS", "LINES", "TMOUT", "HISTSIZE",
    )

    private class Word(val start: Int) {
        var end = start
        val lit = StringBuilder()
        val placeholders = ArrayList<Int>()

        /** Posizione in [lit] di ciascun segnaposto di [placeholders]. */
        val positions = ArrayList<Int>()

        /** Indici in [lit] dei caratteri che venivano da apici o escape. */
        val quotedAt = HashSet<Int>()

        /** Nomi letti con `$NOME` fuori dagli apici singoli. */
        val refs = LinkedHashSet<String>()

        /** Sottoinsieme di [refs] letto fuori da OGNI apice: la shell lo divide in parole e ne espande i glob. */
        val unquotedRefs = LinkedHashSet<String>()

        /** Contiene un'espansione della shell che non è un segnaposto (`$NOME`, `$1`, `$?`…). */
        var expansion = false
        var quoted = false

        val text: String get() = lit.toString()
        val fixed: Boolean get() = placeholders.isEmpty() && !expansion
        val plain: Boolean get() = fixed && !quoted
        val pure: Boolean get() = placeholders.size == 1 && lit.length == 1

        fun append(c: Char, quotedChar: Boolean) {
            if (quotedChar) quotedAt += lit.length
            lit.append(c)
        }
    }

    private class Command {
        val words = ArrayList<Word>()
        val redirects = ArrayList<Word>()
        var arithmetic: Word? = null

        /** Parole a monte nella pipe (`a | b`): il loro contenuto arriva su stdin. */
        var stdin: List<Word>? = null
        val empty: Boolean get() = words.isEmpty() && redirects.isEmpty() && arithmetic == null
    }

    private class Frame(
        val closer: String,
        val savedPipelineStart: Int,
        val stdin: List<Word>?,
        var expectPattern: Boolean = false,
    )

    private class Assignment(val name: String, val subscript: IntRange?, val valueStart: Int)

    private class StructureScan(
        private val template: String,
        private val placeholders: List<IntRange>,
        private val limit: Int,
    ) {
        val verdicts = arrayOfNulls<Context>(placeholders.size)
        val optionLike = BooleanArray(placeholders.size)

        private var i = 0
        private var next = 0
        private var word: Word? = null
        private var redirectPending = false
        private var command = Command()
        private var doubleBracket = false
        private val allWords = ArrayList<Word>()
        private var pipelineStart = 0
        private val frames = ArrayList<Frame>()
        private val stored = HashMap<String, LinkedHashSet<Int>>()
        private val integerVariables = HashSet(INTEGER_SPECIALS)

        fun run(): StructureScan {
            var quote = 0 // 0 fuori, 1 apici singoli, 2 doppi apici
            while (i < limit) {
                if (placeholderAt(i)) {
                    val w = currentWord()
                    w.placeholders += next
                    w.positions += w.lit.length
                    w.append(MARKER, quote != 0)
                    i = placeholders[next].last + 1
                    next++
                    continue
                }
                val c = template[i]
                val following = if (i + 1 < limit) template[i + 1] else null
                if (quote == 1) {
                    if (c == '\'') quote = 0 else currentWord().append(c, true)
                    i++
                    continue
                }
                if (quote == 2) {
                    when {
                        c == '"' -> quote = 0
                        c == '\\' && following != null && !placeholderAt(i + 1) -> {
                            if (following in "\\\"$`") {
                                currentWord().append(following, true)
                            } else if (following != '\n') {
                                currentWord().append('\\', true)
                                currentWord().append(following, true)
                            }
                            i += 2
                            continue
                        }
                        c == '$' -> {
                            dollar(true)
                            continue
                        }
                        else -> currentWord().append(c, true)
                    }
                    i++
                    continue
                }
                if (doubleBracket && c in "&|()<>") {
                    endWord()
                    val pair = following == c && (c == '&' || c == '|')
                    val w = Word(i)
                    w.append(c, false)
                    if (pair) w.append(c, false)
                    i += if (pair) 2 else 1
                    w.end = i
                    word = w
                    endWord()
                    continue
                }
                when {
                    c == '\\' -> {
                        if (following != null && !placeholderAt(i + 1)) {
                            if (following != '\n') currentWord().append(following, true)
                            i += 2
                        } else {
                            i++
                        }
                        continue
                    }
                    c == '\'' -> {
                        currentWord().quoted = true
                        quote = 1
                    }
                    c == '"' -> {
                        currentWord().quoted = true
                        quote = 2
                    }
                    c == '$' -> {
                        dollar(false)
                        continue
                    }
                    c == ' ' || c == '\t' -> endWord()
                    c == '\n' -> {
                        endWord()
                        separator(";")
                    }
                    c == ';' -> {
                        endWord()
                        if (following == ';' || following == '&' || following == '|') {
                            i++
                            separator(";;")
                        } else {
                            separator(";")
                        }
                    }
                    c == '&' -> {
                        endWord()
                        when (following) {
                            '&' -> {
                                i++
                                separator("&&")
                            }
                            '>' -> {
                                redirect()
                                continue
                            }
                            else -> separator("&")
                        }
                    }
                    c == '|' -> {
                        endWord()
                        when (following) {
                            '|' -> {
                                i++
                                separator("||")
                            }
                            '&' -> {
                                i++
                                pipe()
                            }
                            else -> pipe()
                        }
                    }
                    c == '(' -> {
                        endWord()
                        if (following == '(' && command.empty && !inCasePattern()) {
                            arithmeticCommand()
                            continue
                        }
                        open()
                    }
                    c == ')' -> {
                        endWord()
                        close()
                    }
                    c == '<' || c == '>' -> {
                        redirect()
                        continue
                    }
                    else -> currentWord().append(c, false)
                }
                i++
            }
            endWord()
            finishCommand()
            // Un dato conservato in una variabile e riletto dopo un costrutto non modellato: non si
            // sa come viene usato.
            if (limit < template.length) {
                val rest = template.substring(limit)
                stored.forEach { (name, indices) ->
                    if (Regex("(?<![A-Za-z0-9_])$name(?![A-Za-z0-9_])").containsMatchIn(rest)) {
                        indices.forEach { mark(it, Context.UNSAFE) }
                    }
                }
            }
            return this
        }

        private fun Char.isNameChar(): Boolean =
            this == '_' || this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

        private fun placeholderAt(index: Int): Boolean =
            next < placeholders.size && placeholders[next].first == index && placeholders[next].last < limit

        private fun currentWord(): Word = word ?: Word(i).also { word = it }

        private fun dollar(inDouble: Boolean) {
            val w = currentWord()
            if (i + 1 >= limit || placeholderAt(i + 1)) {
                w.append('$', inDouble)
                i++
                return
            }
            val following: Char = template[i + 1]
            when {
                following == '_' || following in 'A'..'Z' || following in 'a'..'z' -> {
                    var end = i + 1
                    while (end < limit && !placeholderAt(end) && template[end].isNameChar()) end++
                    val name = template.substring(i + 1, end)
                    w.refs += name
                    if (!inDouble) w.unquotedRefs += name
                    w.expansion = true
                    ("\$" + name).forEach { w.append(it, inDouble) }
                    i = end
                }
                following.isDigit() || following in "?#@*!$-" -> {
                    w.expansion = true
                    w.append('$', inDouble)
                    w.append(following, inDouble)
                    i += 2
                }
                else -> {
                    w.append('$', inDouble)
                    i++
                }
            }
        }

        private fun endWord() {
            val w = word ?: return
            word = null
            w.end = i
            allWords += w
            if (redirectPending) {
                redirectPending = false
                command.redirects += w
                return
            }
            addWord(w)
        }

        private fun redirect() {
            val w = word
            // `2>…`: le cifre attaccate all'operatore sono il descrittore, non una parola.
            if (w != null && w.plain && w.text.isNotEmpty() && w.text.all(Char::isDigit)) {
                word = null
            } else {
                endWord()
            }
            var count = 0
            while (i < limit && count < 3 && template[i] in "<>&|" && !placeholderAt(i)) {
                i++
                count++
            }
            redirectPending = true
        }

        private fun inCasePattern(): Boolean = frames.lastOrNull()?.expectPattern == true

        private fun addWord(w: Word) {
            // I pattern di `case` sono confronti, non comandi.
            if (inCasePattern()) {
                if (w.plain && w.text == "esac") {
                    frames.last().expectPattern = false
                    pop("esac")
                }
                return
            }
            if (command.words.isEmpty() && command.arithmetic == null && w.plain) {
                when (w.text) {
                    "if", "while", "until", "{" -> {
                        push(if (w.text == "if") "fi" else if (w.text == "{") "}" else "done")
                        return
                    }
                    "then", "else", "elif", "do", "!" -> {
                        pipelineStart = allWords.size
                        return
                    }
                    "fi", "done", "esac", "}" -> {
                        pop(w.text)
                        return
                    }
                    "for", "select" -> push("done")
                    "case" -> push("esac")
                    "[[" -> doubleBracket = true
                }
            }
            command.words += w
            if (doubleBracket && w.plain && w.text == "]]") doubleBracket = false
            val first = command.words.first()
            if (command.words.size == 3 && first.plain && first.text == "case" && w.plain && w.text == "in") {
                finishCommand()
                frames.lastOrNull()?.expectPattern = true
            }
        }

        private fun push(closer: String) {
            frames += Frame(closer, pipelineStart, effectiveStdin())
            command.stdin = null
            pipelineStart = allWords.size
        }

        private fun pop(closer: String) {
            val top = frames.lastOrNull() ?: return
            if (top.closer != closer) return
            frames.removeAt(frames.lastIndex)
            pipelineStart = top.savedPipelineStart
        }

        private fun separator(kind: String) {
            finishCommand()
            doubleBracket = false
            pipelineStart = allWords.size
            if (kind == ";;") frames.lastOrNull()?.takeIf { it.closer == "esac" }?.expectPattern = true
        }

        private fun pipe() {
            finishCommand()
            doubleBracket = false
            command.stdin = allWords.subList(pipelineStart, allWords.size).toList()
        }

        private fun open() {
            if (inCasePattern()) return
            // `nome()` (definizione di funzione) non apre una subshell.
            if (command.empty) push(")") else finishCommand()
        }

        private fun close() {
            val top = frames.lastOrNull()
            if (top != null && top.expectPattern) {
                top.expectPattern = false
                command = Command()
                pipelineStart = allWords.size
                return
            }
            finishCommand()
            pop(")")
        }

        /** `(( … ))`: tutto ciò che sta dentro è un'espressione aritmetica. */
        private fun arithmeticCommand() {
            val region = Word(i)
            i += 2
            var depth = 0
            var quote = 0
            while (i < limit) {
                if (placeholderAt(i)) {
                    region.placeholders += next
                    region.positions += region.lit.length
                    region.append(MARKER, quote != 0)
                    i = placeholders[next].last + 1
                    next++
                    continue
                }
                val c = template[i]
                when {
                    quote == 1 -> if (c == '\'') quote = 0 else region.append(c, true)
                    quote == 2 && c == '"' -> quote = 0
                    quote == 0 && c == '\'' -> quote = 1
                    quote == 0 && c == '"' -> quote = 2
                    quote == 0 && c == '(' -> {
                        depth++
                        region.append(c, false)
                    }
                    quote == 0 && c == ')' -> {
                        if (depth == 0 && i + 1 < limit && template[i + 1] == ')') {
                            i += 2
                            break
                        }
                        depth--
                        region.append(c, false)
                    }
                    else -> region.append(c, quote != 0)
                }
                i++
            }
            region.end = i
            allWords += region
            command.arithmetic = region
        }

        private fun effectiveStdin(): List<Word>? =
            command.stdin ?: frames.asReversed().firstNotNullOfOrNull { it.stdin }

        private fun finishCommand() {
            val current = command
            if (current.empty) return
            command = Command()
            analyze(current, current.stdin ?: frames.asReversed().firstNotNullOfOrNull { it.stdin })
        }

        // --- classificazione --------------------------------------------------------------------

        private fun mark(index: Int, context: Context) {
            val previous = verdicts[index]
            if (previous == null || context.severity() > previous.severity()) verdicts[index] = context
        }

        private fun markWord(w: Word, context: Context) = w.placeholders.forEach { mark(it, context) }

        private fun storedIn(names: Collection<String>): Set<Int> =
            names.flatMapTo(LinkedHashSet()) { stored[it].orEmpty() }

        /** Dati conservati nelle variabili nominate in [w], anche dentro apici singoli. */
        private fun rawStored(w: Word): Set<Int> {
            val raw = template.substring(w.start, w.end.coerceAtMost(template.length))
            return storedIn(RAW_REFERENCE.findAll(raw).map { it.groupValues[1] }.toList())
        }

        private fun identifiers(text: String): List<String> = IDENTIFIER.findAll(text).map { it.value }.toList()

        private fun markEverything(words: List<Word>, context: Context) {
            words.forEach { w ->
                markWord(w, context)
                rawStored(w).forEach { mark(it, context) }
            }
        }

        /** Espressione aritmetica in `w.lit[from, to)`: il segnaposto deve stare da solo tra operatori. */
        private fun arithmetic(w: Word, from: Int, to: Int) {
            w.placeholders.forEachIndexed { k, index ->
                val at = w.positions[k]
                if (at < from || at >= to) return@forEachIndexed
                val before = if (at > from) w.lit[at - 1] else null
                val after = if (at + 1 < to) w.lit[at + 1] else null
                val alone = (before == null || before in ARITHMETIC_NEIGHBOURS) &&
                    (after == null || after in ARITHMETIC_NEIGHBOURS)
                mark(index, if (alone) Context.NUMERIC else Context.UNSAFE)
            }
            // Un nome in un'espressione aritmetica si valuta a sua volta come espressione.
            storedIn(identifiers(w.lit.substring(from, to))).forEach { mark(it, Context.NUMERIC) }
        }

        /** Operando aritmetico che è una parola intera (`[ x -eq y ]`, `shift`, `ulimit`). */
        private fun numericOperand(w: Word) {
            markWord(w, if (w.pure) Context.NUMERIC else Context.UNSAFE)
            storedIn(identifiers(w.text)).forEach { mark(it, Context.NUMERIC) }
        }

        private fun assignment(w: Word): Assignment? {
            val match = ASSIGNMENT.find(w.lit) ?: return null
            val nameEnd = match.groups[1]!!.range.last
            if ((0..nameEnd).any { it in w.quotedAt }) return null
            val equals = match.range.last
            if (equals in w.quotedAt) return null
            return Assignment(
                name = match.groupValues[1],
                subscript = match.groups[2]?.range?.let { (it.first + 1) until it.last },
                valueStart = equals + 1,
            )
        }

        private fun handleAssignment(w: Word, a: Assignment, integerDeclaration: Boolean) {
            a.subscript?.let { arithmetic(w, it.first, it.last + 1) }
            if (integerDeclaration || a.name in integerVariables) {
                arithmetic(w, a.valueStart, w.lit.length)
            } else {
                val flowing = LinkedHashSet<Int>()
                w.placeholders.forEachIndexed { k, index -> if (w.positions[k] >= a.valueStart) flowing += index }
                flowing += storedIn(w.refs)
                if (flowing.isNotEmpty()) stored.getOrPut(a.name) { LinkedHashSet() } += flowing
            }
            if (integerDeclaration) integerVariables += a.name
        }

        private fun commandName(w: Word): String? {
            if (!w.fixed) return null
            val text = w.text
            if (text == "[" || text == "[[") return text
            if (text.isEmpty() || text.any { it in "*?[" }) return null
            return text.substringAfterLast('/').ifEmpty { null }
        }

        private fun isOption(w: Word): Boolean = w.text.length > 1 && w.text.startsWith("-") && w.text != "--"

        private fun analyze(command: Command, stdin: List<Word>?) {
            command.arithmetic?.let { arithmetic(it, 0, it.lit.length) }
            val words = command.words
            if (words.isEmpty()) return
            val whole = words + command.redirects
            var k = 0
            while (k < words.size) {
                val a = assignment(words[k]) ?: break
                handleAssignment(words[k], a, integerDeclaration = false)
                k++
            }
            if (k == words.size) return

            // Attraverso i comandi che lanciano il comando seguente (env, nohup, timeout, busybox…).
            while (k < words.size) {
                val name = commandName(words[k]) ?: break
                val wrapper = WRAPPERS[name] ?: break
                k++
                var positional = 0
                while (k < words.size) {
                    val w = words[k]
                    if (!w.fixed) break
                    val text = w.text
                    if (text == "--") {
                        k++
                        break
                    }
                    if (isOption(w)) {
                        if (wrapper.splitString && (text.startsWith("-S") || text.startsWith("--split-string"))) {
                            markEverything(whole, Context.REEVALUATED)
                            return
                        }
                        k += if (text in wrapper.optionsWithArgument) 2 else 1
                        continue
                    }
                    if (wrapper.assignments && assignment(w) != null) {
                        k++
                        continue
                    }
                    if (positional < wrapper.positional) {
                        positional++
                        k++
                        continue
                    }
                    break
                }
            }
            if (k >= words.size) return
            val commandWord = words[k]
            val arguments = words.subList(k + 1, words.size)
            val name = commandName(commandWord)
            if (name == null) {
                // Il dato (o un'espansione) sceglie il programma: tutto il comando è imprevedibile.
                markWord(commandWord, Context.COMMAND_POSITION)
                storedIn(commandWord.refs).forEach { mark(it, Context.COMMAND_POSITION) }
                markEverything(whole.filter { it !== commandWord }, Context.REEVALUATED)
                stdin?.let { markEverything(it, Context.REEVALUATED) }
                return
            }
            // Parole saltate (assegnazioni iniziali, argomenti dei wrapper) con un segnaposto dove
            // si cerca il comando: potrebbero diventare il comando.
            words.subList(0, k).forEach { w ->
                if (assignment(w) == null) markWord(w, Context.COMMAND_POSITION)
            }
            val reevaluating = name in REEVALUATING || name.startsWith("python") ||
                (name == "find" && arguments.any { it.fixed && it.text in FIND_EXEC })
            if (reevaluating) {
                markEverything(whole, Context.REEVALUATED)
                if (stdin != null && (name in STDIN_CODE || name.startsWith("python"))) {
                    markEverything(stdin, Context.REEVALUATED)
                }
                return
            }
            // Un dato conservato in una variabile della shell e riletto SENZA apici (`f=…; rm $f`) viene
            // diviso in parole e i suoi glob espansi: non è più "un solo argomento". Resta inerte solo
            // se è un intero. Fuori: `[[ … ]]` e la parola di `case` (nessuna divisione), le
            // dichiarazioni (`export G=$f` non divide) e la lista di `for` (il dato prosegue nella
            // variabile del ciclo, che è tracciata a sua volta).
            if (name != "[[" && name != "case" && name != "for" && name != "select" && name !in DECLARATIONS) {
                (arguments + command.redirects).forEach { w ->
                    storedIn(w.unquotedRefs).forEach { mark(it, Context.NUMERIC) }
                }
            }
            when (name) {
                "[", "test" -> testOperands(arguments.filterNot { name == "[" && it === arguments.lastOrNull() && it.plain && it.text == "]" })
                "[[" -> testOperands(arguments.filterNot { it === arguments.lastOrNull() && it.plain && it.text == "]]" })
                "let" -> arguments.forEach { arithmetic(it, 0, it.lit.length) }
                "shift", "ulimit" -> arguments.forEach { if (!isOption(it)) numericOperand(it) }
                "unset" -> arguments.forEach { markWord(it, Context.UNSAFE) }
                "read" -> {
                    arguments.forEach { markWord(it, Context.UNSAFE) }
                    if (stdin != null) {
                        val flowing = LinkedHashSet<Int>()
                        stdin.forEach { w ->
                            flowing += w.placeholders
                            flowing += rawStored(w)
                        }
                        arguments.filter { it.plain && !isOption(it) && IDENTIFIER.matches(it.text) }.forEach {
                            if (flowing.isNotEmpty()) stored.getOrPut(it.text) { LinkedHashSet() } += flowing
                        }
                    }
                }
                "for", "select" -> {
                    val variable = arguments.firstOrNull()
                    if (variable != null && variable.plain && arguments.getOrNull(1)?.text == "in") {
                        val flowing = LinkedHashSet<Int>()
                        arguments.drop(2).forEach { w ->
                            flowing += w.placeholders
                            flowing += storedIn(w.refs)
                        }
                        if (flowing.isNotEmpty()) stored.getOrPut(variable.text) { LinkedHashSet() } += flowing
                    }
                }
                in DECLARATIONS -> declaration(name, arguments)
                else -> optionWarnings(arguments)
            }
        }

        private fun testOperands(operands: List<Word>) {
            fun operator(w: Word?) = w != null && w.fixed && w.text in NUMERIC_TEST_OPERATORS
            fun variable(w: Word?) = w != null && !w.fixed
            operands.forEachIndexed { index, w ->
                val before = operands.getOrNull(index - 1)
                val after = operands.getOrNull(index + 1)
                when {
                    operator(before) || operator(after) -> numericOperand(w)
                    before != null && before.fixed && before.text == "-v" -> markWord(w, Context.UNSAFE)
                    // Un vicino variabile potrebbe diventare `-eq` e rendere aritmetico questo operando.
                    variable(before) || variable(after) -> {
                        markWord(w, Context.UNSAFE)
                        storedIn(identifiers(w.text)).forEach { mark(it, Context.UNSAFE) }
                    }
                }
            }
        }

        private fun declaration(name: String, arguments: List<Word>) {
            var integer = name == "integer"
            var reference = name == "nameref"
            arguments.forEach { w ->
                val text = w.text
                if (text.length > 1 && (text.startsWith("-") || text.startsWith("+"))) {
                    if (!w.fixed) {
                        markWord(w, Context.UNSAFE)
                    } else {
                        if ('i' in text) integer = true
                        if ('n' in text) reference = true
                    }
                    return@forEach
                }
                val a = assignment(w)
                when {
                    a == null -> {
                        // Il dato sarebbe il NOME di una variabile (con un eventuale indice aritmetico).
                        markWord(w, Context.UNSAFE)
                        storedIn(w.refs).forEach { mark(it, Context.UNSAFE) }
                        if (integer && w.plain && IDENTIFIER.matches(text)) integerVariables += text
                    }
                    reference -> markWord(w, Context.UNSAFE)
                    else -> handleAssignment(w, a, integer)
                }
            }
        }

        private fun optionWarnings(arguments: List<Word>) {
            var endOfOptions = false
            arguments.forEach { w ->
                if (w.fixed && w.text == "--") {
                    endOfOptions = true
                    return@forEach
                }
                if (!endOfOptions && w.positions.firstOrNull() == 0) optionLike[w.placeholders.first()] = true
            }
        }
    }
}
