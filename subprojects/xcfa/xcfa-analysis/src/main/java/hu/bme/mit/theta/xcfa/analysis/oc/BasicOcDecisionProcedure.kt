package hu.bme.mit.theta.xcfa.analysis.oc

import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.type.booltype.BoolExprs
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.SolverManager
import hu.bme.mit.theta.solver.SolverStatus
import java.util.*

internal class BasicOcDecisionProcedure : OcDecisionProcedure {

    override val solver: Solver = SolverManager.resolveSolverFactory("Z3:4.13").createSolver()

    override fun check(
        events: Map<VarDecl<*>, Map<Int, List<Event>>>,
        pos: List<Relation>,
        rfs: Map<VarDecl<*>, List<Relation>>,
    ): SolverStatus? {
        val modifiableRels = rfs.values.flatten() // modifiable relation vars
        val flatEvents = events.values.flatMap { it.values.flatten() }
        val initialRels = Array(flatEvents.size) { Array<Reason?>(flatEvents.size) { null } }
        pos.forEach { setAndClose(initialRels, it) }
        val decisionStack = Stack<OcAssignment>()
        decisionStack.push(OcAssignment(rels = initialRels)) // not really a decision point (initial)

        dpllLoop@
        while (solver.check().isSat) { // DPLL loop
            val valuation = solver.model.toMap()
            val changedRfs = modifiableRels.filter { rel ->
                val value = rel.enabled(valuation)
                decisionStack.popUntil({ it.relation == rel }, value) && value == true
            }
            val changedEnabledEvents = flatEvents.filter { ev ->
                val enabled = ev.enabled(solver.model)
                if (ev.type != EventType.WRITE || !rfs.containsKey(ev.const.varDecl)) return@filter false
                decisionStack.popUntil({ it.event == ev }, enabled) && enabled == true
            }

            // propagate
            for (rf in changedRfs) {
                val decision = OcAssignment(decisionStack.peek().rels, rf)
                decisionStack.push(decision)
                val reason0 = setAndClose(decision.rels, rf)
                if (reason0 != null) {
                    solver.add(BoolExprs.Not(reason0.expr))
                    continue@dpllLoop
                }

                val writes = events[rf.from.const.varDecl]!!.values.flatten()
                    .filter { it.type == EventType.WRITE && it.enabled == true }
                for (w in writes) {
                    val reason = derive(decision.rels, rf, w)
                    if (reason != null) {
                        solver.add(BoolExprs.Not(reason.expr))
                        continue@dpllLoop
                    }
                }
            }

            for (w in changedEnabledEvents) {
                val decision = OcAssignment(decisionStack.peek().rels, w)
                decisionStack.push(decision)
                for (rf in rfs[w.const.varDecl]!!.filter { it.enabled == true }) {
                    val reason = derive(decision.rels, rf, w)
                    if (reason != null) {
                        solver.add(BoolExprs.Not(reason.expr))
                        continue@dpllLoop
                    }
                }
            }

            return solver.status // no conflict found, counterexample is valid
        }
        return solver.status
    }

    /**
     *  Returns true if obj is not on the stack (in other words, if the value of obj is changed in the new model)
     */
    private fun <T> Stack<T>.popUntil(obj: (T) -> Boolean, value: Boolean?): Boolean {
        val index = indexOfFirst(obj)
        if (index == -1) return true
        if (value == true) return false
        while (size > index) pop()
        return true
    }
}