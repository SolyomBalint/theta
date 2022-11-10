/*
 *  Copyright 2022 Budapest University of Technology and Economics
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package hu.bme.mit.theta.xcfa.analysis

import hu.bme.mit.theta.analysis.*
import hu.bme.mit.theta.analysis.algorithm.ArgBuilder
import hu.bme.mit.theta.analysis.algorithm.ArgNodeComparators
import hu.bme.mit.theta.analysis.algorithm.cegar.Abstractor
import hu.bme.mit.theta.analysis.algorithm.cegar.BasicAbstractor
import hu.bme.mit.theta.analysis.algorithm.cegar.abstractor.StopCriterion
import hu.bme.mit.theta.analysis.expl.ExplInitFunc
import hu.bme.mit.theta.analysis.expl.ExplPrec
import hu.bme.mit.theta.analysis.expl.ExplState
import hu.bme.mit.theta.analysis.expl.ExplStmtTransFunc
import hu.bme.mit.theta.analysis.expr.ExprState
import hu.bme.mit.theta.analysis.pred.*
import hu.bme.mit.theta.analysis.pred.PredAbstractors.PredAbstractor
import hu.bme.mit.theta.analysis.waitlist.PriorityWaitlist
import hu.bme.mit.theta.common.logging.Logger
import hu.bme.mit.theta.core.type.booltype.BoolExprs.True
import hu.bme.mit.theta.solver.Solver
import hu.bme.mit.theta.xcfa.model.XCFA
import hu.bme.mit.theta.xcfa.model.XcfaLocation
import java.util.*
import java.util.function.Predicate

open class XcfaAnalysis<S: ExprState, P: Prec> (
        private val corePartialOrd: PartialOrd<XcfaState<S>>,
        private val coreInitFunc: InitFunc<XcfaState<S>, XcfaPrec<P>>,
        private val coreTransFunc: TransFunc<XcfaState<S>, XcfaAction, XcfaPrec<P>>,
) : Analysis<XcfaState<S>, XcfaAction, XcfaPrec<P>> {
    override fun getPartialOrd(): PartialOrd<XcfaState<S>> = corePartialOrd
    override fun getInitFunc(): InitFunc<XcfaState<S>, XcfaPrec<P>> = coreInitFunc
    override fun getTransFunc(): TransFunc<XcfaState<S>, XcfaAction, XcfaPrec<P>> = coreTransFunc
}

/// Common

fun getXcfaLts() = LTS<XcfaState<out ExprState>, XcfaAction> {
            s -> s.processes.map {
                proc -> proc.value.locs.peek().outgoingEdges.map { XcfaAction(proc.key, it) }.filter { !s.apply(it).first.bottom }
            }.flatten()
        }

fun getXcfaErrorPredicate() =
        Predicate<XcfaState<out ExprState>>{ s -> s.processes.any { it.value.locs.peek().error }}

fun <S: ExprState> getPartialOrder(partialOrd: PartialOrd<S>) =
        PartialOrd<XcfaState<S>>{s1, s2 -> s1.processes == s2.processes && partialOrd.isLeq(s1.sGlobal, s2.sGlobal)}

private fun <S: XcfaState<out ExprState>, P: XcfaPrec<out Prec>> getXcfaArgBuilder(
        analysis: Analysis<S, XcfaAction, P>,
        ltsSupplier: () -> LTS<XcfaState<out ExprState>, XcfaAction>)
: ArgBuilder<S, XcfaAction, P> =
        ArgBuilder.create(
                ltsSupplier(),
                analysis,
                getXcfaErrorPredicate()
        )

fun <S: XcfaState<out ExprState>, P: XcfaPrec<out Prec>> getXcfaAbstractor(
        analysis: Analysis<S, XcfaAction, P>,
        argNodeComparator: ArgNodeComparators.ArgNodeComparator,
        stopCriterion: StopCriterion<*, *>,
        logger: Logger,
        ltsSupplier: () -> LTS<XcfaState<out ExprState>, XcfaAction>
): Abstractor<out XcfaState<out ExprState>, XcfaAction, out XcfaPrec<out Prec>> =
        BasicAbstractor.builder(getXcfaArgBuilder(analysis, ltsSupplier))
                .waitlist(PriorityWaitlist.create(argNodeComparator))
                .stopCriterion(stopCriterion as StopCriterion<S, XcfaAction>).logger(logger).build() // TODO: can we do this nicely?



/// EXPL

private fun getExplXcfaInitFunc(xcfa: XCFA, solver: Solver): (XcfaPrec<ExplPrec>) -> List<XcfaState<ExplState>> {
    val processInitState = xcfa.initProcedures.mapIndexed { i, it ->
        val initLocStack: LinkedList<XcfaLocation> = LinkedList()
        initLocStack.add(it.first.initLoc)
        Pair(i, XcfaProcessState(initLocStack))
    }.toMap()
    return { p -> ExplInitFunc.create(solver, True()).getInitStates(p.p).map { XcfaState(xcfa, processInitState, it) } }
}
private fun getExplXcfaTransFunc(solver: Solver, maxEnum: Int): (XcfaState<ExplState>, XcfaAction, XcfaPrec<ExplPrec>) -> List<XcfaState<ExplState>> {
    val explTransFunc = ExplStmtTransFunc.create(solver, maxEnum)
    return { s, a, p ->
        val (newSt, newAct) = s.apply(a)
        explTransFunc.getSuccStates(newSt.sGlobal, newAct, p.p).map { newSt.withState(it) }
    }
}

class ExplXcfaAnalysis(xcfa: XCFA, solver: Solver, maxEnum: Int) : XcfaAnalysis<ExplState, ExplPrec>(
        corePartialOrd = getPartialOrder { s1, s2 -> s1.isLeq(s2) },
        coreInitFunc = getExplXcfaInitFunc(xcfa, solver),
        coreTransFunc = getExplXcfaTransFunc(solver, maxEnum)
)

/// PRED

private fun getPredXcfaInitFunc(xcfa: XCFA, predAbstractor: PredAbstractor): (XcfaPrec<PredPrec>) -> List<XcfaState<PredState>> {
    val processInitState = xcfa.initProcedures.mapIndexed { i, it ->
        val initLocStack: LinkedList<XcfaLocation> = LinkedList()
        initLocStack.add(it.first.initLoc)
        Pair(i, XcfaProcessState(initLocStack))
    }.toMap()
    return { p -> PredInitFunc.create(predAbstractor, True()).getInitStates(p.p).map { XcfaState(xcfa, processInitState, it) } }
}
private fun getPredXcfaTransFunc(predAbstractor: PredAbstractors.PredAbstractor): (XcfaState<PredState>, XcfaAction, XcfaPrec<PredPrec>) -> List<XcfaState<PredState>> {
    val predTransFunc = PredTransFunc.create<XcfaAction>(predAbstractor)
    return { s, a, p ->
        val (newSt, newAct) = s.apply(a)
        predTransFunc.getSuccStates(newSt.sGlobal, newAct, p.p).map { newSt.withState(it) }
    }
}

class PredXcfaAnalaysis(xcfa: XCFA, solver: Solver, predAbstractor: PredAbstractor) : XcfaAnalysis<PredState, PredPrec>(
        corePartialOrd = getPartialOrder(PredOrd.create(solver)),
        coreInitFunc = getPredXcfaInitFunc(xcfa, predAbstractor),
        coreTransFunc = getPredXcfaTransFunc(predAbstractor)
)
