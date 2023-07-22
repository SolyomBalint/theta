/*
 *  Copyright 2023 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.analysis.algorithm.lazy;

import com.google.common.collect.Lists;
import hu.bme.mit.theta.analysis.*;
import hu.bme.mit.theta.analysis.algorithm.ARG;
import hu.bme.mit.theta.analysis.algorithm.ArgNode;
import hu.bme.mit.theta.analysis.algorithm.SearchStrategy;
import hu.bme.mit.theta.analysis.algorithm.cegar.Abstractor;
import hu.bme.mit.theta.analysis.algorithm.cegar.AbstractorResult;
import hu.bme.mit.theta.analysis.expr.ExprState;
import hu.bme.mit.theta.analysis.reachedset.Partition;
import hu.bme.mit.theta.analysis.unit.UnitPrec;
import hu.bme.mit.theta.analysis.waitlist.Waitlist;
import hu.bme.mit.theta.core.utils.Lens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkNotNull;

public final class LazyAbstractor<SConcr extends State, SAbstr extends State, FSConcr extends State, FSAbstr extends ExprState, A extends Action, P extends Prec>
        implements Abstractor<LazyState<FSConcr, FSAbstr>, A, UnitPrec> {
    private final LTS<FSConcr, A> lts;
    private final SearchStrategy searchStrategy;
    private final LazyStrategy<SConcr, SAbstr, LazyState<FSConcr, FSAbstr>, A> lazyStrategy;
    //private final Function<LazyState<FSConcr, FSAbstr>, ?> projection;
    private final Analysis<LazyState<FSConcr, FSAbstr>, A, P> analysis;
    private final P prec;
    private final Predicate<FSConcr> isTarget;
    private final Lens<FSConcr, SConcr> concrStateLens;

    public LazyAbstractor(final LTS<FSConcr, A> lts,
                          final SearchStrategy searchStrategy,
                          final LazyStrategy<SConcr, SAbstr, LazyState<FSConcr, FSAbstr>, A> lazyStrategy,
                          final LazyAnalysis<FSConcr, FSAbstr, A, P> analysis,
                          final P prec,
                          final Predicate<FSConcr> isTarget,
                          final Lens<FSConcr, SConcr> concrStateLens) {
        this.lts = checkNotNull(lts);
        this.searchStrategy = checkNotNull(searchStrategy);
        this.lazyStrategy = checkNotNull(lazyStrategy);
        this.analysis = checkNotNull(analysis);
        this.prec = checkNotNull(prec);
        this.isTarget = isTarget;
        this.concrStateLens = concrStateLens;
    }

    @Override
    public ARG<LazyState<FSConcr, FSAbstr>, A> createArg() {
        final ARG<LazyState<FSConcr, FSAbstr>, A> arg = ARG.create(analysis.getPartialOrd());
        final Collection<? extends LazyState<FSConcr, FSAbstr>>
                initStates = analysis.getInitFunc().getInitStates(prec);
        for (final LazyState<FSConcr, FSAbstr> initState : initStates) {
            final boolean target = isTarget.test(initState.getConcrState());
            arg.createInitNode(initState, target);
        }
        return arg;
    }

    @Override
    public AbstractorResult check(ARG<LazyState<FSConcr, FSAbstr>, A> arg, UnitPrec prec) {
        return new CheckMethod(arg).run();
    }

    private final class CheckMethod {
        final ARG<LazyState<FSConcr, FSAbstr>, A> arg;
        final LazyStatistics.Builder stats;
        final Partition<ArgNode<LazyState<FSConcr, FSAbstr>, A>, ?> passed;
        final Waitlist<ArgNode<LazyState<FSConcr, FSAbstr>, A>> waiting;


        public CheckMethod(final ARG<LazyState<FSConcr, FSAbstr>, A> arg) {
            this.arg = arg;
            stats = LazyStatistics.builder(arg);
            passed = Partition.of(n -> lazyStrategy.getProjection().apply(n.getState()));
            waiting = searchStrategy.createWaitlist();
        }

        public AbstractorResult run() {
            stats.startAlgorithm();

            if (arg.getInitNodes().anyMatch(ArgNode::isTarget)) {
                return stop(AbstractorResult.unsafe());
            }

            waiting.addAll(arg.getInitNodes());
            while (!waiting.isEmpty()) {
                final ArgNode<LazyState<FSConcr, FSAbstr>, A> v = waiting.remove();
                assert v.isFeasible();

                close(v, stats);
                if (!v.isCovered()) {
                    AbstractorResult result = expand(v, arg, stats);
                    if (result.isUnsafe()) {
                        return stop(AbstractorResult.unsafe());
                    }
                }
            }
            return stop(AbstractorResult.safe());
        }

        private AbstractorResult stop(AbstractorResult result) {
            stats.stopAlgorithm();
            final LazyStatistics statistics = stats.build();
            return result;
        }

        private void close(final ArgNode<LazyState<FSConcr, FSAbstr>, A> coveree,
                           final LazyStatistics.Builder stats) {
            stats.startClosing();

            final Iterable<ArgNode<LazyState<FSConcr, FSAbstr>, A>>
                    candidates = Lists.reverse(passed.get(coveree));
            for (final ArgNode<LazyState<FSConcr, FSAbstr>, A> coverer : candidates) {

                stats.checkCoverage();
                if (lazyStrategy.mightCover(coveree, coverer)) {

                    stats.attemptCoverage();

                    coveree.setCoveringNode(coverer);
                    final Collection<ArgNode<LazyState<FSConcr, FSAbstr>, A>>
                            uncoveredNodes = new ArrayList<>();
                    lazyStrategy.cover(coveree, coverer, uncoveredNodes);
                    waiting.addAll(uncoveredNodes.stream().filter(n -> !n.equals(coveree)));

                    if (coveree.isCovered()) {
                        stats.successfulCoverage();
                        stats.stopClosing();
                        return;
                    }
                }
            }

            stats.stopClosing();
        }

        private AbstractorResult expand(final ArgNode<LazyState<FSConcr, FSAbstr>, A> node,
                                        final ARG<LazyState<FSConcr, FSAbstr>, A> arg,
                                        final LazyStatistics.Builder stats) {
            stats.startExpanding();
            final LazyState<FSConcr, FSAbstr> state = node.getState();

            for (final A action : lts.getEnabledActionsFor(state.getConcrState())) {
                final Collection<? extends LazyState<FSConcr, FSAbstr>>
                        succStates = analysis.getTransFunc().getSuccStates(state, action, prec);

                for (final LazyState<FSConcr, FSAbstr> succState : succStates) {
                    if (lazyStrategy.inconsistentState(concrStateLens.get(succState.getConcrState()))) {
                        final Collection<ArgNode<LazyState<FSConcr, FSAbstr>, A>>
                                uncoveredNodes = new ArrayList<>();
                        lazyStrategy.disable(node, action, succState, uncoveredNodes);
                        waiting.addAll(uncoveredNodes);
                    } else {
                        final boolean target = isTarget.test(succState.getConcrState());
                        final ArgNode<LazyState<FSConcr, FSAbstr>, A>
                                succNode = arg.createSuccNode(node, action, succState, target);
                        if (target) {
                            stats.stopExpanding();
                            return AbstractorResult.unsafe();
                        }
                        waiting.add(succNode);
                    }
                }
            }
            passed.add(node);
            stats.stopExpanding();
            return AbstractorResult.safe();
        }
    }
}
