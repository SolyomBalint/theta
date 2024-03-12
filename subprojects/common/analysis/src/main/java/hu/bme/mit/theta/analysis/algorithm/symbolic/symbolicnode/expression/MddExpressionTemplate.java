/*
 *  Copyright 2024 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.analysis.algorithm.symbolic.symbolicnode.expression;

import hu.bme.mit.delta.collections.RecursiveIntObjMapView;
import hu.bme.mit.delta.java.mdd.MddCanonizationStrategy;
import hu.bme.mit.delta.java.mdd.MddGraph;
import hu.bme.mit.delta.java.mdd.MddNode;
import hu.bme.mit.delta.java.mdd.MddVariable;
import hu.bme.mit.theta.analysis.algorithm.symbolic.symbolicnode.SolverPool;
import hu.bme.mit.theta.core.decl.Decl;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.type.booltype.FalseExpr;
import hu.bme.mit.theta.core.utils.ExprUtils;
import hu.bme.mit.theta.solver.Solver;
import hu.bme.mit.theta.solver.utils.WithPushPop;

import java.util.function.Function;
import java.util.function.Supplier;

public class MddExpressionTemplate implements MddNode.Template {

    private final Expr<BoolType> expr;
    private final Function<Object, Decl> extractDecl;
    private final SolverPool solverPool;

    private static Solver lazySolver;

    private static boolean isSat(Expr<BoolType> expr, SolverPool solverPool) {
        if (lazySolver == null) lazySolver = solverPool.requestSolver();
        boolean res;
        try (var wpp = new WithPushPop(lazySolver)) {
            lazySolver.add(expr);
            res = lazySolver.check().isSat();
        }
        return res;
    }

    private MddExpressionTemplate(Expr<BoolType> expr, Function<Object, Decl> extractDecl, SolverPool solverPool) {
        this.expr = expr;
        this.extractDecl = extractDecl;
        this.solverPool = solverPool;
    }

    public static MddExpressionTemplate of(Expr<BoolType> expr, Function<Object, Decl> extractDecl, SolverPool solverPool) {
        return new MddExpressionTemplate(expr, extractDecl, solverPool);
    }

    @Override
    public RecursiveIntObjMapView<? extends MddNode> toCanonicalRepresentation(MddVariable mddVariable, MddCanonizationStrategy mddCanonizationStrategy) {
        final Decl decl = extractDecl.apply(mddVariable.getTraceInfo());

        final Expr<BoolType> canonizedExpr = ExprUtils.canonize(ExprUtils.simplify(expr));

//        // TODO: we might not need this
//        // Check if terminal 1
//        if (ExprUtils.getConstants(canonizedExpr).isEmpty()) {
//            if (canonizedExpr instanceof FalseExpr) {
//                return mddVariable.getMddGraph().getTerminalZeroNode();
//            } /*else {
//                final MddGraph<Expr> mddGraph = (MddGraph<Expr>) mddVariable.getMddGraph();
//                return mddGraph.getNodeFor(canonizedExpr);
//            }*/
//        }

        // Check if terminal 0
        if (canonizedExpr instanceof FalseExpr || !isSat(canonizedExpr, solverPool)) {
            return null;
        }

        // Check if default
        if (mddVariable.getDomainSize() == 0 && !ExprUtils.getConstants(canonizedExpr).contains(decl)) {
            final MddNode childNode;
            if (mddVariable.getLower().isPresent()) {
                childNode = mddVariable.getLower().get().checkInNode(new MddExpressionTemplate(canonizedExpr, o -> (Decl) o, solverPool));
            } else {
                final MddGraph<Expr> mddGraph = (MddGraph<Expr>) mddVariable.getMddGraph();
                childNode = mddGraph.getNodeFor(canonizedExpr);
            }
            return MddExpressionRepresentation.ofDefault(canonizedExpr, decl, mddVariable, solverPool, childNode);
        }

        return MddExpressionRepresentation.of(canonizedExpr, decl, mddVariable, solverPool);

    }
}
