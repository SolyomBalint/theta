package hu.bme.mit.theta.xcfa.analysis.oc

import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.type.Expr
import hu.bme.mit.theta.core.type.booltype.BoolType
import hu.bme.mit.theta.core.type.booltype.TrueExpr
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.solver.SolverStatus

enum class OcDecisionProcedureType {
    BASIC, PROPAGATOR
}


internal sealed class Reason {

    open val reasons: List<Reason> get() = listOf(this)
    val exprs: List<Expr<BoolType>> get() = toExprs()
    val expr: Expr<BoolType> get() = exprs.toAnd()
    infix fun and(other: Reason): Reason = CombinedReason(reasons + other.reasons)
    open fun toExprs(): List<Expr<BoolType>> = reasons.map { it.toExprs() }.flatten().filter { it !is TrueExpr }
}

internal class CombinedReason(override val reasons: List<Reason>) : Reason()
internal object PoReason : Reason() {

    override val reasons get() = emptyList<Reason>()
    override fun toExprs(): List<Expr<BoolType>> = listOf()
}

internal class RelationReason(val relation: Relation) : Reason() {

    override fun toExprs(): List<Expr<BoolType>> = listOf(relation.declRef)
}

internal open class DerivedReason(val rf: Relation, val w: Event, val wRfRelation: Reason) : Reason() {

    override fun toExprs(): List<Expr<BoolType>> = listOf(rf.declRef, w.guardExpr) + wRfRelation.toExprs()
}

internal class WriteSerializationReason(rf: Relation, w: Event, wBeforeRf: Reason) : DerivedReason(rf, w, wBeforeRf)
internal class FromReadReason(rf: Relation, w: Event, wAfterRf: Reason) : DerivedReason(rf, w, wAfterRf)

internal class OcAssignment(
    val relation: Relation? = null,
    val event: Event? = null,
    val rels: Array<Array<Reason?>>,
    val solverLevel: Int = 0,
) {

    companion object {

        private fun initRels(rels: Array<Array<Reason?>>) =
            Array(rels.size) { i -> Array(rels.size) { j -> rels[i][j] } }
    }

    constructor(rels: Array<Array<Reason?>>, e: Event, solverLevel: Int = 0)
        : this(event = e, rels = initRels(rels), solverLevel = solverLevel)

    constructor(rels: Array<Array<Reason?>>, r: Relation, solverLevel: Int = 0)
        : this(relation = r, rels = initRels(rels), solverLevel = solverLevel)
}

internal interface OcDecisionProcedure {

    val solver: Solver

    fun check(events: Map<VarDecl<*>, Map<Int, List<Event>>>, pos: List<Relation>,
        rfs: Map<VarDecl<*>, List<Relation>>): SolverStatus?

    fun derive(rels: Array<Array<Reason?>>, rf: Relation, w: Event): Reason? = when {
        rf.from.clkId == rf.to.clkId -> null // rf within an atomic block
        w.clkId == rf.from.clkId || w.clkId == rf.to.clkId -> null // w within an atomic block with one of the rf ends

        rels[w.clkId][rf.to.clkId] != null -> { // WS derivation
            val reason = WriteSerializationReason(rf, w, rels[w.clkId][rf.to.clkId]!!)
            setAndClose(rels, w.clkId, rf.from.clkId, reason)
        }

        rels[rf.from.clkId][w.clkId] != null -> { // FR derivation
            val reason = FromReadReason(rf, w, rels[rf.from.clkId][w.clkId]!!)
            setAndClose(rels, rf.to.clkId, w.clkId, reason)
        }

        else -> null
    }

    fun setAndClose(rels: Array<Array<Reason?>>, rel: Relation): Reason? {
        if (rel.from.clkId == rel.to.clkId) return null // within an atomic block
        return setAndClose(rels, rel.from.clkId, rel.to.clkId,
            if (rel.type == RelationType.PO) PoReason else RelationReason(rel))
    }

    private fun setAndClose(rels: Array<Array<Reason?>>, from: Int, to: Int, reason: Reason): Reason? {
        if (from == to) return reason // cycle (self-loop) found
        val toClose = mutableListOf(from to to to reason)
        while (toClose.isNotEmpty()) {
            val (fromTo, r) = toClose.removeFirst()
            val (i1, i2) = fromTo
            check(i1 != i2)
            if (rels[i1][i2] != null) continue

            rels[i1][i2] = r
            rels[i2].forEachIndexed { i2next, b ->
                if (b != null && rels[i1][i2next] == null) { // i2 -> i2next, not i1 -> i2next
                    val combinedReason = r and b
                    if (i1 == i2next) return combinedReason // cycle (self-loop) found
                    toClose.add(i1 to i2next to combinedReason)
                }
            }
            rels.forEachIndexed { i1previous, b ->
                if (b[i1] != null && rels[i1previous][i2] == null) { // i1previous -> i1, not i1previous -> i2
                    val combinedReason = r and b[i1]!!
                    if (i1previous == i2) return combinedReason // cycle (self-loop) found
                    toClose.add(i1previous to i2 to combinedReason)
                }
            }
        }
        return null
    }
}
