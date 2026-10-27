package org.example.otppager

/** Local-only orchestration; no networking, enrollment, or journal removal transition. */
internal object GlobalRetirementMachine {
    data class Previous(val roots: Set<String>, val usable: Boolean = true)
    data class Scope(val roots: Set<String>, val complete: Boolean, val failures: List<String> = emptyList())
    data class Result(val roots: Set<String>, val terminalDurable: Boolean, val scopeComplete: Boolean,
                      val completed: Boolean, val failures: List<String>)
    interface Operations {
        fun freeze(): Set<String>
        fun previous(): Previous
        fun write(roots: Set<String>, complete: Boolean)
        fun discover(): Scope
        fun retire(root: String): Boolean
        fun metadata(): List<Pair<String, () -> Unit>>
    }
    fun perform(op: Operations): Result {
        val roots = op.freeze().toMutableSet()
        val failures = mutableListOf<String>()
        fun attempt(label: String, action: () -> Unit): Boolean = try { action(); true }
            catch (_: Exception) { failures.add(label); false }
        var usable = false
        attempt("global-journal-read") { val old = op.previous(); roots.addAll(old.roots); usable = old.usable }
        if (!usable) failures.add("global-journal-unavailable")
        fun persist(complete: Boolean) = attempt(if (complete) "global-completion" else "global-intent") {
            check(usable) { "Existing global scope is unavailable" }; op.write(roots.toSet(), complete)
        }
        val initial = persist(false) // Durable global intent before discovery or destruction when possible.
        var scopeComplete = false
        fun discover(): Scope? {
            var scope: Scope? = null
            attempt("global-discovery") { scope = op.discover() }
            scope?.let { roots.addAll(it.roots); failures.addAll(it.failures); if (!it.complete) failures.add("global-scope-incomplete") }
            return scope
        }
        val before = discover()
        val recorded = persist(false) // Remember all known roots before their last keys/files can disappear.
        val targets = roots.toSet()
        for (root in targets.sorted()) attempt("contact:$root") { check(op.retire(root)) { "Contact cleanup incomplete" } }
        for ((label, action) in op.metadata()) attempt(label, action)
        val after = discover()
        scopeComplete = before?.complete == true && after?.complete == true
        if (roots != targets) { failures.add("global-scope-changed"); persist(false) }
        val completed = scopeComplete && recorded && failures.isEmpty() && persist(true)
        return Result(roots.toSet(), initial || recorded, scopeComplete, completed, failures.toList())
    }
}
