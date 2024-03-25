package hu.bme.mit.theta.xcfa.analysis.oc

import hu.bme.mit.theta.analysis.Trace
import hu.bme.mit.theta.analysis.algorithm.SafetyChecker
import hu.bme.mit.theta.analysis.algorithm.SafetyResult
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.unit.UnitPrec
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.core.decl.*
import hu.bme.mit.theta.core.model.ImmutableValuation
import hu.bme.mit.theta.core.model.Valuation
import hu.bme.mit.theta.core.stmt.AssignStmt
import hu.bme.mit.theta.core.stmt.AssumeStmt
import hu.bme.mit.theta.core.stmt.HavocStmt
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.Type
import hu.bme.mit.theta.core.type.abstracttype.AbstractExprs.Eq
import hu.bme.mit.theta.core.type.anytype.RefExpr
import hu.bme.mit.theta.core.type.booltype.BoolExprs.*
import hu.bme.mit.theta.core.type.booltype.BoolLitExpr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.inttype.IntExprs.Int
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.core.utils.TypeUtils.cast
import hu.bme.mit.theta.core.utils.indexings.VarIndexingFactory
import hu.bme.mit.theta.xcfa.analysis.XcfaAction
import hu.bme.mit.theta.xcfa.analysis.XcfaPrec
import hu.bme.mit.theta.xcfa.analysis.XcfaProcessState
import hu.bme.mit.theta.xcfa.analysis.XcfaState
import hu.bme.mit.theta.xcfa.collectVars
import hu.bme.mit.theta.xcfa.getFlatLabels
import hu.bme.mit.theta.xcfa.isAtomicBegin
import hu.bme.mit.theta.xcfa.isAtomicEnd
import hu.bme.mit.theta.xcfa.model.*
import hu.bme.mit.theta.xcfa.passes.AssumeFalseRemovalPass
import hu.bme.mit.theta.xcfa.passes.AtomicReadsOneWritePass
import hu.bme.mit.theta.xcfa.passes.MutexToVarPass
import java.util.*

private val Expr<*>.vars get() = ExprUtils.getVars(this)

class XcfaOcChecker(xcfa: XCFA, decisionProcedure: OcDecisionProcedureType, private val logger: Logger) :
    SafetyChecker<XcfaState<*>, XcfaAction, XcfaPrec<UnitPrec>> {

    private val ocDecisionProcedure = when (decisionProcedure) {
        OcDecisionProcedureType.BASIC -> BasicOcDecisionProcedure()
        OcDecisionProcedureType.PROPAGATOR -> UserPropagatorOcDecisionProcedure()
    }
    private val solver = ocDecisionProcedure.solver

    private val xcfa: XCFA = xcfa.optimizeFurther(
        listOf(AssumeFalseRemovalPass(), MutexToVarPass(), AtomicReadsOneWritePass())
    )
    private var indexing = VarIndexingFactory.indexing(0)
    private val localVars = mutableMapOf<VarDecl<*>, MutableMap<Int, VarDecl<*>>>()

    private val threads = mutableSetOf<Thread>()
    private val events = mutableMapOf<VarDecl<*>, MutableMap<Int, MutableList<Event>>>()
    private val violations = mutableListOf<Violation>() // OR!
    private val branchingConditions = mutableListOf<Expr<BoolType>>()
    private val pos = mutableListOf<Relation>()
    private val rfs = mutableMapOf<VarDecl<*>, MutableList<Relation>>()

    override fun check(prec: XcfaPrec<UnitPrec>?): SafetyResult<XcfaState<*>, XcfaAction> {
        if (xcfa.initProcedures.size > 1) error("Multiple entry points are not supported by this checker.")

        logger.write(Logger.Level.MAINSTEP, "Adding constraints...\n")
        if (!addConstraints()) return SafetyResult.safe() // no violations

        logger.write(Logger.Level.MAINSTEP, "Start checking...\n")
        val status = ocDecisionProcedure.check(events, pos, rfs)
        return when {
            status?.isUnsat == true -> SafetyResult.safe()
            status?.isSat == true -> SafetyResult.unsafe(getTrace(solver.model))
            else -> SafetyResult.unknown()
        }
    }

    private fun addConstraints(): Boolean {
        val threads = xcfa.initProcedures.map { Thread(it.first) }.toMutableSet()
        this.threads.addAll(threads)
        while (threads.isNotEmpty()) {
            val thread = threads.first()
            threads.remove(thread)
            val newThreads = processThread(thread)
            threads.addAll(newThreads)
            this.threads.addAll(newThreads)
        }

        addCrossThreadRelations()
        return addToSolver()
    }

    private fun processThread(thread: Thread): List<Thread> {
        val pid = thread.pid
        var last = listOf<Event>()
        var guard = listOf<Expr<BoolType>>()
        lateinit var lastWrites: MutableMap<VarDecl<*>, Set<Event>>
        lateinit var edge: XcfaEdge
        var inEdge = false
        var atomicEntered: Boolean? = null

        val newEvent: (VarDecl<*>, EventType) -> List<Event> = { d, type ->
            check(!inEdge || last.size == 1)
            val decl = d.threadVar(pid)
            val useLastClk = inEdge || atomicEntered == true
            val e =
                if (useLastClk) Event(decl.getNewIndexed(), type, guard, pid, edge, last.first().clkId)
                else Event(decl.getNewIndexed(), type, guard, pid, edge)
            last.forEach { po(it, e, useLastClk) }
            inEdge = true
            if (atomicEntered == false) atomicEntered = true
            when (type) {
                EventType.READ -> lastWrites[decl]?.forEach { rfs.add(RelationType.RFI, it, e) }
                EventType.WRITE -> lastWrites[decl] = setOf(e)
            }
            events[decl] = (events[decl] ?: mutableMapOf()).apply {
                this[pid] = (this[pid] ?: mutableListOf()).apply { add(e) }
            }
            listOf(e)
        }

        val waitList = mutableSetOf<SearchItem>()
        val toVisit = mutableSetOf(SearchItem(thread.procedure.initLoc).apply {
            guards.add(thread.guard)
            thread.startEvent?.let { lastEvents.add(it) }
        })
        val threads = mutableListOf<Thread>()

        while (toVisit.isNotEmpty()) {
            val current = toVisit.first()
            toVisit.remove(current)
            check(current.incoming == current.loc.incomingEdges.size)
            check(current.incoming == current.guards.size || current.loc.initial)
            check(current.incoming == current.lastWrites.size) // lastEvents intentionally skipped
            check(current.incoming == current.pidLookups.size)
            check(current.incoming == current.atomics.size)
            check(current.atomics.all { it == current.atomics.first() }) // bad pattern otherwise

            if (current.loc.error) {
                violations.add(Violation(current.loc, Or(current.guards.map { it.toAnd() }), current.lastEvents))
                continue
            }

            if (current.loc.final) {
                current.lastEvents.forEach { final ->
                    thread.joinEvents.forEach { join -> po(final, join) }
                }
            }

            val assumeConsts = mutableMapOf<VarDecl<*>, MutableList<ConstDecl<*>>>()
            for (e in current.loc.outgoingEdges) {
                edge = e
                inEdge = false
                last = current.lastEvents
                // intersection of guards of incoming edges:
                guard = current.guards.reduce(listOf()) { g1, g2 -> g1.filter { it in g2 } }
                lastWrites = current.lastWrites.merge().toMutableMap()
                val pidLookup = current.pidLookups.merge { s1, s2 ->
                    s1 + s2.filter { (guard2, _) -> s1.none { (guard1, _) -> guard1 == guard2 } }
                }.toMutableMap()
                var firstLabel = true
                atomicEntered = current.atomics.firstOrNull()

                edge.getFlatLabels().forEach { label ->
                    when (label) {
                        is StmtLabel -> {
                            when (val stmt = label.stmt) {
                                is AssignStmt<*> -> {
                                    val consts = mutableMapOf<VarDecl<*>, ConstDecl<*>>()
                                    stmt.expr.vars.forEach {
                                        last = newEvent(it, EventType.READ)
                                        consts[it] = last.first().const
                                    }
                                    last = newEvent(stmt.varDecl, EventType.WRITE)
                                    last.first().assignment = Eq(last.first().const.ref, stmt.expr.withConsts(consts))
                                }

                                is AssumeStmt -> {
                                    val consts = stmt.cond.vars.associateWith { it.threadVar(pid).getNewIndexed(false) }
                                    val condWithConsts = stmt.cond.withConsts(consts)
                                    if (edge.source.outgoingEdges.size > 1) {
                                        guard = guard + condWithConsts
                                        if (firstLabel) {
                                            consts.forEach { (v, c) ->
                                                assumeConsts.getOrPut(v) { mutableListOf() }.add(c)
                                            }
                                        }
                                    }
                                    stmt.cond.vars.forEach {
                                        last = newEvent(it, EventType.READ)
                                    }
                                    if (edge.source.outgoingEdges.size == 1) {
                                        last.first().assignment = condWithConsts
                                    }
                                }

                                is HavocStmt<*> -> {
                                    last = newEvent(stmt.varDecl, EventType.WRITE)
                                }

                                else -> error("Unsupported statement type: $stmt")
                            }
                        }

                        is StartLabel -> {
                            // TODO StartLabel params
                            val procedure = xcfa.procedures.find { it.name == label.name }
                                ?: error("Procedure not found: ${label.name}")
                            last = newEvent(label.pidVar, EventType.WRITE)
                            val pidVar = label.pidVar.threadVar(pid)
                            if (this.threads.any { it.pidVar == pidVar }) {
                                error("Multi-thread use of pthread_t variables are not supported by this checker.")
                            }
                            val newThread = Thread(procedure, guard, pidVar, last.first())
                            last.first().assignment = Eq(last.first().const.ref, Int(newThread.pid))
                            pidLookup[pidVar] = setOf(Pair(guard, newThread.pid))
                            threads.add(newThread)
                        }

                        is JoinLabel -> {
                            val guardTemp = guard
                            val lastEvents = mutableListOf<Event>()
                            pidLookup[label.pidVar.threadVar(pid)]?.forEach { (g, pid) ->
                                guard = g
                                lastEvents.addAll(newEvent(label.pidVar, EventType.READ))
                                threads.find { it.pid == pid }?.joinEvents?.add(lastEvents.last())
                            } ?: error("Thread started in a different thread: not supported by this checker.")
                            guard = guardTemp
                            last = lastEvents
                        }

                        is FenceLabel -> {
                            if (label.labels.size > 1 || label.labels.firstOrNull()?.contains("ATOMIC") != true) {
                                error("Untransformed fence label: $label")
                            }
                            if (label.isAtomicBegin) atomicEntered = false
                            if (label.isAtomicEnd) atomicEntered = null
                        }

                        is NopLabel -> {}
                        else -> error("Unsupported label type by this checker: $label")
                    }
                    firstLabel = false
                }

                val searchItem = waitList.find { it.loc == edge.target }
                    ?: SearchItem(edge.target).apply { waitList.add(this) }
                searchItem.guards.add(guard)
                searchItem.lastEvents.addAll(last)
                searchItem.lastWrites.add(lastWrites)
                searchItem.pidLookups.add(pidLookup)
                searchItem.atomics.add(atomicEntered)
                searchItem.incoming++
                if (searchItem.incoming == searchItem.loc.incomingEdges.size) {
                    waitList.remove(searchItem)
                    toVisit.add(searchItem)
                }
            }

            if (current.loc.outgoingEdges.size > 1) {
                for (e in current.loc.outgoingEdges) {
                    val first = e.getFlatLabels().first()
                    if (first !is StmtLabel || first.stmt !is AssumeStmt) {
                        error("Branching with non-assume labels not supported by this checker.")
                    }
                }
                assumeConsts.forEach { (_, set) ->
                    for ((i1, v1) in set.withIndex())
                        for ((i2, v2) in set.withIndex()) {
                            if (i1 == i2) break
                            branchingConditions.add(Eq(v1.ref, v2.ref))
                        }
                }
            }
        }

        if (waitList.isNotEmpty()) error("Loops and dangling edges not supported by this checker.")
        return threads
    }

    private fun addCrossThreadRelations() {
        for ((_, map) in events)
            for ((pid1, list1) in map)
                for ((pid2, list2) in map)
                    if (pid1 != pid2)
                        for (e1 in list1.filter { it.type == EventType.WRITE })
                            for (e2 in list2.filter { it.type == EventType.READ })
                                rfs.add(RelationType.RFE, e1, e2)
    }

    private fun addToSolver(): Boolean {
        if (violations.isEmpty()) return false

        // Value assignment
        events.values.flatMap { it.values.flatten() }.filter { it.assignment != null }.forEach { event ->
            if (event.guard.isEmpty()) solver.add(event.assignment)
            else solver.add(Imply(event.guardExpr, event.assignment))
        }

        // Branching conditions
        branchingConditions.forEach { solver.add(it) }

        // Property violation
        solver.add(Or(violations.map { it.guard }))

        // RF
        rfs.forEach { (_, list) ->
            list.groupBy { it.to }.forEach { (event, rels) ->
                rels.forEach { rel ->
                    solver.add(Imply(rel.declRef,
                        And(rel.from.guardExpr, rel.to.guardExpr, Eq(rel.from.const.ref, rel.to.const.ref))
                    )) // RF-Val
                }
                solver.add(Imply(event.guardExpr, Or(rels.map { it.declRef }))) // RF-Some
            }
        }

        return true
    }

    private fun getTrace(model: Valuation): Trace<XcfaState<*>, XcfaAction> {
        val stateList = mutableListOf<XcfaState<*>>()
        val actionList = mutableListOf<XcfaAction>()
        val eventTrace = getEventTrace(model)
        val valuation = model.toMap()

        val processes = events.values.fold(mapOf<Int, Event>()) { acc, map ->
            (acc.keys + map.keys).associateWith { k -> acc[k] ?: map[k]!!.first() }
        }.map { (pid, event) ->
            pid to XcfaProcessState(
                locs = LinkedList(listOf(
                    xcfa.procedures.find { p -> p.edges.any { event.const.varDecl in it.label.collectVars() } }!!.initLoc
                )),
                varLookup = LinkedList(emptyList())
            )
        }.toMap()
        var explState = ExplState.of(ImmutableValuation.from(mapOf()))
        var state = XcfaState<ExplState>(xcfa, processes, explState)
        stateList.add(state)
        var lastEdge: XcfaEdge? = eventTrace[0].edge

        for ((index, event) in eventTrace.withIndex()) {
            valuation[event.const]?.let {
                val newVal = explState.`val`.toMap().toMutableMap().apply { put(event.const.varDecl, it) }
                explState = ExplState.of(ImmutableValuation.from(newVal))
            }

            val nextEdge = eventTrace.getOrNull(index + 1)?.edge
            if (nextEdge != lastEdge) {
                actionList.add(XcfaAction(event.pid, lastEdge!!))
                state = state.copy(
                    processes = state.processes.toMutableMap().apply {
                        put(event.pid, XcfaProcessState(
                            locs = LinkedList(listOf(lastEdge!!.target)),
                            varLookup = LinkedList(emptyList())
                        ))
                    },
                    sGlobal = explState
                )
                stateList.add(state)
                lastEdge = nextEdge
            }
        }
        return Trace.of(stateList, actionList)
    }

    private fun getEventTrace(model: Valuation): List<Event> {
        val valuation = model.toMap()
        val previousEvents: Collection<Relation>.(Event) -> Collection<Event> = { event ->
            filter { it.to == event && it.enabled(valuation) == true && it.from.enabled(model) == true }.map { it.from }
        }
        val violation = violations.first { (it.guard.eval(model) as BoolLitExpr).value }

        val startEvents = violation.lastEvents.toMutableList()
        val finished = mutableListOf<Event>() // topological order
        while (startEvents.isNotEmpty()) { // DFS from startEvents as root nodes
            val stack = Stack<StackItem>()
            stack.push(StackItem(startEvents.removeFirst()))
            while (stack.isNotEmpty()) {
                val top = stack.peek()
                if (top.eventsToVisit == null) {
                    val previous = mutableSetOf<Event>()
                    previous.addAll(pos.previousEvents(top.event))
                    if (top.event.type == EventType.READ) {
                        rfs[top.event.const.varDecl]?.previousEvents(top.event)?.let { previous.addAll(it) }
                    }
                    top.eventsToVisit = previous.toMutableList()
                }

                if (top.eventsToVisit!!.isEmpty()) {
                    stack.pop()
                    finished.add(top.event)
                    continue
                }

                val visiting = top.eventsToVisit!!.removeFirst()
                if (visiting !in finished) {
                    stack.push(StackItem(visiting))
                }
            }
        }
        return finished
    }

    // Utility functions

    private fun po(from: Event?, to: Event, inEdge: Boolean = false) {
        from ?: return
        pos.add(Relation(if (inEdge) RelationType.EPO else RelationType.PO, from, to))
    }

    private fun <K, V> List<Map<K, Set<V>>>.merge(merge: (Set<V>, Set<V>) -> Set<V> = { a, b -> a + b }) =
        reduce(mapOf()) { acc, map ->
            (acc.keys + map.keys).associateWith { k ->
                val set1 = acc[k] ?: setOf()
                val set2 = map[k] ?: setOf()
                merge(set1, set2)
            }
        }

    private inline fun <T> Collection<T>.reduce(default: T, operation: (T, T) -> T): T =
        if (isEmpty()) default else reduce(operation)

    private fun MutableMap<VarDecl<*>, MutableList<Relation>>.add(type: RelationType, from: Event, to: Event) =
        getOrPut(from.const.varDecl) { mutableListOf() }.add(Relation(type, from, to))

    private fun <T : Type> Expr<T>.withConsts(varToConst: Map<out Decl<*>, ConstDecl<*>>): Expr<T> {
        if (this is RefExpr<T>) {
            return varToConst[decl]?.ref?.let { cast(it, type) } ?: this
        }
        return map { it.withConsts(varToConst) }
    }

    private fun <T : Type> VarDecl<T>.threadVar(pid: Int): VarDecl<T> =
        if (xcfa.vars.none { it.wrappedVar == this && !it.threadLocal }) { // if not global var
            cast(localVars.getOrPut(this) { mutableMapOf() }.getOrPut(pid) {
                Decls.Var("t$pid::$name", type)
            }, type)
        } else this

    private fun <T : Type> VarDecl<T>.getNewIndexed(increment: Boolean = true): IndexedConstDecl<T> {
        val constDecl = getConstDecl(indexing.get(this))
        if (increment) indexing = indexing.inc(this)
        return constDecl
    }

    fun printXcfa() = xcfa.toDot { edge ->
        "(${
            events.values.flatMap { it.flatMap { it.value } }.filter { it.edge == edge }
                .joinToString(",") { it.const.name }
        })"
    }
}
