package com.abrignoni.knowndatalogger

/**
 * Everything the screen watch decides, with no Android in it, so a recorded session (LookLog) can be replayed off
 * the phone. CaptureService reads the screen into lines and feeds each look here, along with every text it
 * remembers (typed, cleared or notified) and every reset; it then writes the rows this returns, in order.
 *
 * Time is the elapsed-realtime clock in ms, given with each call. A look reads it once: before this class, the
 * time was read again for each line, so a 2-minute window could close in the middle of a look.
 */
class WatchJudge(private val mask: (String?) -> String? = { NotificationMap.maskCodes(it).first }) {
    /** A row to write. [token] names a text_appeared row so a later label can refer to it. */
    sealed class Out {
        data class Raw(val token: Long, val pkg: String, val content: String?, val detail: String) : Out()
        data class Label(val refersTo: Long, val pkg: String, val action: String, val direction: String?,
                         val content: String?, val detail: String, val screenshot: Boolean) : Out()
        data class System(val pkg: String?, val action: String, val detail: String) : Out()
    }

    var rules: List<Rule> = emptyList()
    var maskCodes = true
    var shotAuto = true
    /** Told of every text remembered, with the list it went into ("own", "notified", "rule_labelled"). */
    var onRemember: ((list: String, text: String, now: Long) -> Unit)? = null

    private val chatWatch = ChatWatch()
    private val recentOwn = LinkedHashMap<String, Long>()
    private val recentNotified = LinkedHashMap<String, Long>()
    private val ruleLabelled = LinkedHashMap<String, Long>()
    private val receivedLabelled = LinkedHashMap<String, Long>()
    /** This phone's author name per app, learned from its own lines in an app with a message_author rule. */
    private val ownAuthor = HashMap<String, String>()
    /** Messages logged before this phone's author was known, labelled once it is. */
    private val awaitingAuthor = ArrayList<AwaitingAuthor>()
    private data class AwaitingAuthor(val pkg: String, val author: String, val raw: Long, val shown: String?, val ruleId: String, val action: String)
    private var tokens = 0L
    private var looks = 0
    private var baselines = 0
    private var found = 0
    private var lastKey: Any? = null

    /** A session started, stopped or the service reconnected. Stopping a session that looked reports the summary row. */
    fun reset(active: Boolean): List<Out> {
        val out = ArrayList<Out>()
        if (!active && looks > 0) out += Out.System(null, "screen_watch_summary", "looks $looks, baselines $baselines, new lines $found")
        chatWatch.reset(); recentOwn.clear(); recentNotified.clear(); ruleLabelled.clear(); receivedLabelled.clear(); ownAuthor.clear(); awaitingAuthor.clear()
        looks = 0; baselines = 0; found = 0; lastKey = null
        return out
    }

    fun rememberOwn(text: String?, now: Long) = remember(recentOwn, "own", text, now)
    fun rememberNotified(text: String?, now: Long) = remember(recentNotified, "notified", text, now)

    private fun remember(map: LinkedHashMap<String, Long>, list: String, t: String?, now: Long) {
        val s = t?.trim()?.takeIf { it.isNotEmpty() } ?: return
        onRemember?.invoke(list, s, now)
        map.remove(s); map[s] = now
        while (map.size > 50) map.remove(map.keys.first())
    }

    /** True when the line holds a text in [map] remembered less than [within] ms before [now]. */
    private fun recentlyIn(map: Map<String, Long>, line: String, now: Long, within: Long = 120_000, exact: Boolean = false): Boolean =
        map.any { (t, at) -> now - at < within && (if (exact) line == t else line.contains(t)) }

    private fun masked(t: String?): String? = if (maskCodes) mask(t) else t

    /**
     * One look at an open screen of an app under test. Each new line is a raw text_appeared row with its side of the
     * screen; a line on the left, not one this phone just sent or was notified of, also gets a received_message
     * label for review. A text_appeared rule or a message_author rule for the package decides instead.
     */
    fun look(now: Long, pkg: String, key: Any, width: Int, atNewest: Boolean?, lines: List<ShownText>,
             authors: List<Pair<String, Int>>): List<Out> {
        val out = ArrayList<Out>()
        if (looks == 0) out += Out.System(pkg, "screen_watch_active", "${lines.size} lines on the first look")
        looks++
        if (key != lastKey) { baselines++; lastKey = key }
        val appeared = chatWatch.look(key, lines, width, atNewest, now)
        val returned = chatWatch.lastLookReturned
        found += appeared.size
        val authorRule = rules.firstOrNull { it.pkg == pkg && it.event == RuleEvent.MESSAGE_AUTHOR }
        val authorLines = if (authorRule != null && authorRule.viewId == null) authorsByText(pkg, authorRule, lines, now, out) else authors
        for (a in appeared) {
            if (authorRule != null) { authoredLine(now, pkg, authorRule, a, authorLines, out); continue }
            // An app can say in the line itself which way it went: Telegram's message cells span the whole width
            // (side "across") and end with "Received at <time>" or "Sent at <time>". A text_appeared rule names that.
            val rule = Rules.match(rules, pkg, RuleEvent.TEXT_APPEARED, null, a.text)
                ?: Rules.match(rules, pkg, RuleEvent.DESCRIPTION, null, a.text)
            // A line holding what this phone just typed is its own message: dropped, unless a rule says it was
            // sent. Telegram reports no field clear, so its "Sent at" line is the only sign of a send (Galaxy
            // A16 5G and moto g play, 15 November 2026: 0 of 15 sends labelled per phone before this).
            if (recentlyIn(recentOwn, a.text, now) && (rule == null || directionFor(rule.action) != "out")) {
                // A typed_text_appeared rule says such a line in the list is the message this phone sent.
                val typedRule = if (a.inList) Rules.match(rules, pkg, RuleEvent.TYPED_TEXT, null, a.text) else null
                if (typedRule != null) {
                    val k = "${typedRule.id}|${a.text}"
                    if (!recentlyIn(ruleLabelled, k, now, 600_000, exact = true)) {
                        remember(ruleLabelled, "rule_labelled", k, now)
                        val shown = masked(a.text.take(2000))
                        out += Out.Label(raw(pkg, shown, "side ${a.side}", out), pkg, typedRule.action, directionFor(typedRule.action),
                            if (typedRule.content == "line") shown else null, "auto rule ${typedRule.id}", shotAuto)
                    }
                }
                continue
            }
            val shown = masked(a.text.take(2000))
            val raw = raw(pkg, shown, "side ${a.side}", out)
            // Outside the message list (title bar, message box) nothing is a message: logged, never labelled.
            if (!a.inList) continue
            if (rule != null) {
                if (directionFor(rule.action) == "in" && recentlyIn(recentNotified, a.text, now)) continue
                // One label per message, though its line changes in place (Rules.lineKey).
                val k = Rules.lineKey(rule, a.text)
                if (k != null && recentlyIn(ruleLabelled, k, now, 600_000, exact = true)) continue
                remember(ruleLabelled, "rule_labelled", k, now)
                out += Out.Label(raw, pkg, rule.action, directionFor(rule.action), if (rule.content == "line") shown else null,
                    "auto rule ${rule.id}", shotAuto)
                continue
            }
            if (a.side != "left" || recentlyIn(recentNotified, a.text, now) || ChatWatch.isTimeOrDate(a.text) ||
                ChatWatch.isStatusOnly(a.text)) continue
            // Back on the screen held just before (ChatWatch): a text labelled received in the last 2 minutes is the
            // same message seen again (a chat list's preview of what came in while the chat was open), not a new one.
            if (returned && recentlyIn(receivedLabelled, a.text, now, exact = true)) continue
            remember(receivedLabelled, "received_labelled", a.text, now)
            out += Out.Label(raw, pkg, "received_message", "in", shown, "auto: text appeared on the left of an open screen", shotAuto)
        }
        return out
    }

    /**
     * Author lines of an app that names them by their words, not by a view (Snapchat 14.32: "ME" above this phone's
     * messages, the contact's name in capitals above theirs). A message_author rule with no view id: a line in the
     * list, all in capitals, that matches the rule's pattern is this phone's author; one that equals the chat's
     * title (a line outside the list) in capitals is the other side's. Seeing this phone's author line sets this
     * phone's author, and messages waiting for it are labelled.
     */
    private fun authorsByText(pkg: String, rule: Rule, lines: List<ShownText>, now: Long, out: MutableList<Out>): List<Pair<String, Int>> {
        val titles = lines.filter { !it.inList }.map { it.text.uppercase() }.toSet()
        val found = lines.filter { l ->
            l.inList && l.text == l.text.uppercase() && (rule.regex?.matches(l.text) == true || l.text in titles)
        }.map { it.text to it.top }
        val mine = found.firstOrNull { rule.regex?.matches(it.first) == true }?.first
        if (mine != null && ownAuthor[pkg] != mine) {
            ownAuthor[pkg] = mine
            val waiting = awaitingAuthor.filter { it.pkg == pkg }
            awaitingAuthor.removeAll(waiting)
            for (w in waiting) byAuthor(now, w.pkg, w.author, mine, w.raw, w.shown, w.ruleId, w.action, null, out)
        }
        return found
    }

    private fun raw(pkg: String, shown: String?, detail: String, out: MutableList<Out>): Long {
        val t = ++tokens
        out += Out.Raw(t, pkg, shown, detail)
        return t
    }

    /**
     * One new line in an app whose messages sit under their author's name (a message_author rule). Every line
     * is logged; a line is a message only when it is in the message list, has no child views (the row around
     * a message also carries the author and time as text), is not an author line and is not only a time or a
     * date. A message holding text this phone just typed is its own: its author becomes this phone's author and
     * it is labelled sent. A message under another author, once this phone's author is known, gets the rule's
     * action. Until then it waits. (Discord 353.11, Galaxy A16 5G and moto g play, 15 November 2026: every message
     * sits on the left under its author, so the side of the screen could not tell, and author names were
     * labelled received.)
     */
    private fun authoredLine(now: Long, pkg: String, rule: Rule, a: Appeared, authors: List<Pair<String, Int>>, out: MutableList<Out>) {
        val author = ChatWatch.authorAbove(a.top, authors)
        val shown = masked(a.text.take(2000))
        val raw = raw(pkg, shown, "side ${a.side}", out)
        val isAuthorLine = authors.any { it.first == a.text && it.second == a.top }
        if (!a.inList || !a.leaf || isAuthorLine || ChatWatch.isTimeOrDate(a.text) || ChatWatch.isStatusOnly(a.text)) return
        // Text this phone just typed is a send even when its author line has scrolled off above it (Snapchat, Galaxy
        // A16 5G, 15 November 2026: the fifth of five sends in a row sat under a "ME" no longer on the screen).
        if (author == null) {
            if (recentlyIn(recentOwn, a.text, now)) out += Out.Label(raw, pkg, "sent_message", directionFor("sent_message"), shown,
                "auto rule ${rule.id}: holds text this phone just typed", shotAuto)
            return
        }
        if (recentlyIn(recentOwn, a.text, now)) {
            ownAuthor[pkg] = author
            out += Out.Label(raw, pkg, "sent_message", directionFor("sent_message"), shown,
                "auto rule ${rule.id}: holds text this phone just typed", shotAuto)
            // Messages that came before this phone's author was known are labelled now, at their own time.
            val waiting = awaitingAuthor.filter { it.pkg == pkg }
            awaitingAuthor.removeAll(waiting)
            for (w in waiting) byAuthor(now, w.pkg, w.author, author, w.raw, w.shown, w.ruleId, w.action, null, out)
            return
        }
        val mine = ownAuthor[pkg]
        if (mine == null) {
            awaitingAuthor += AwaitingAuthor(pkg, author, raw, shown, rule.id, rule.action)
            if (awaitingAuthor.size > 200) awaitingAuthor.removeAt(0)
            return
        }
        byAuthor(now, pkg, author, mine, raw, shown, rule.id, rule.action, a.text, out)
    }

    /**
     * A message under this phone's own author was sent from this account, though its typed text was not seen
     * yet (Discord, moto g play: the line was on screen before the field cleared). Any other author: the rule's action.
     */
    private fun byAuthor(now: Long, pkg: String, author: String, mine: String, raw: Long, shown: String?, ruleId: String,
                         action: String, text: String?, out: MutableList<Out>) {
        if (author == mine) out += Out.Label(raw, pkg, "sent_message", directionFor("sent_message"), shown,
            "auto rule $ruleId: under this phone's own author", shotAuto)
        else if (text == null || !recentlyIn(recentNotified, text, now))
            out += Out.Label(raw, pkg, action, directionFor(action), shown, "auto rule $ruleId: under another author than this phone's", shotAuto)
    }
}
