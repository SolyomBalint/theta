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
package hu.bme.mit.theta.xta.analysis.expr;

import hu.bme.mit.theta.analysis.TransFunc;
import hu.bme.mit.theta.analysis.expr.BasicExprState;
import hu.bme.mit.theta.analysis.expr.ExprAction;
import hu.bme.mit.theta.analysis.unit.UnitPrec;
import hu.bme.mit.theta.core.decl.ConstDecl;
import hu.bme.mit.theta.core.decl.Decl;
import hu.bme.mit.theta.core.decl.Decls;
import hu.bme.mit.theta.core.decl.IndexedConstDecl;
import hu.bme.mit.theta.core.decl.ParamDecl;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.Type;
import hu.bme.mit.theta.core.type.anytype.RefExpr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.utils.ExprUtils;
import hu.bme.mit.theta.core.utils.PathUtils;
import hu.bme.mit.theta.core.utils.indexings.VarIndexing;
import hu.bme.mit.theta.xta.analysis.XtaAction;
import hu.bme.mit.theta.xta.analysis.XtaDataAction;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static hu.bme.mit.theta.core.type.booltype.BoolExprs.And;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.Exists;
import static hu.bme.mit.theta.core.utils.TypeUtils.cast;

public final class XtaExprTransFunc implements TransFunc<BasicExprState, XtaAction, UnitPrec> {

    private final static XtaExprTransFunc INSTANCE = new XtaExprTransFunc();

    private XtaExprTransFunc() {
    }

    public static XtaExprTransFunc getInstance() {
        return INSTANCE;
    }

    @Override
    public Collection<? extends BasicExprState> getSuccStates(final BasicExprState state, final XtaAction action, final UnitPrec prec) {
        final XtaDataAction dataAction = XtaDataAction.of(action);
        final QuantifiedPostImage post = new QuantifiedPostImage(state, dataAction);
        final Expr<BoolType> postExpr = post.getResultExpr();
        final Collection<ParamDecl<?>> quantifiedParams = post.getQuantifiedParams();
        final Expr<BoolType> succExpr = (quantifiedParams.isEmpty()) ? postExpr : Exists(quantifiedParams, postExpr);
        return Collections.singleton(BasicExprState.of(succExpr));
    }

    private static class QuantifiedPostImage {

        private static int globalNextIndex = 100;

        private final BasicExprState state;
        private final ExprAction action;
        private Map<Decl<?>, Decl<?>> varMapping;
        private Collection<ParamDecl<?>> quantifiedParams;
        private Expr<BoolType> resultExpr;

        private QuantifiedPostImage(final BasicExprState state, final ExprAction action) {
            this.state = state;
            this.action = action;
        }

        private void setResult() {
            varMapping = new HashMap<>();
            quantifiedParams = new HashSet<>();
            final Expr<BoolType> stateExpr = state.toExpr();
            final Expr<BoolType> actionExpr = action.toExpr();
            final Expr<BoolType> indexedActionExpr = PathUtils.unfold(actionExpr, 0);
            final VarIndexing actionIndexing = action.nextIndexing();
            for (final ConstDecl<?> decl : ExprUtils.getConstants(indexedActionExpr)) {
                assert decl instanceof IndexedConstDecl;
                final IndexedConstDecl<?> indexedConstDecl = (IndexedConstDecl<?>) decl;
                final VarDecl<?> varDecl = indexedConstDecl.getVarDecl();
                final int index = indexedConstDecl.getIndex();
                if (index == actionIndexing.get(varDecl)) {
                    varMapping.put(indexedConstDecl, varDecl);
                    if (index > 0 && !varMapping.containsKey(varDecl)) {
                        final IndexedConstDecl<?> newVarDecl = nextIndexedDecl(varDecl);
                        varMapping.put(varDecl, newVarDecl);
                        addQuantifiedParam(newVarDecl);
                    }
                } else {
                    Decl<?> newVarDecl = varMapping.get(varDecl);
                    if (newVarDecl == null) {
                        newVarDecl = nextIndexedDecl(varDecl);
                        varMapping.put(varDecl, newVarDecl);
                        addQuantifiedParam(newVarDecl);
                    }
                    varMapping.put(indexedConstDecl, newVarDecl);
                }
            }
            for (final ConstDecl<?> decl : ExprUtils.getConstants(stateExpr)) {
                if (decl instanceof IndexedConstDecl && !varMapping.containsKey(decl)) {
                    final IndexedConstDecl<?> indexedConstDecl = (IndexedConstDecl<?>) decl;
                    final IndexedConstDecl<?> newVarDecl = nextIndexedDecl(indexedConstDecl.getVarDecl());
                    varMapping.put(indexedConstDecl, newVarDecl);
                    addQuantifiedParam(newVarDecl);
                }
            }
            resultExpr = replaceVars(And(stateExpr, indexedActionExpr));
        }

        private <T extends Type> IndexedConstDecl<T> nextIndexedDecl(final VarDecl<T> decl) {
            final IndexedConstDecl<T> newDecl = decl.getConstDecl(globalNextIndex);
            globalNextIndex++;
            return newDecl;
        }

        private <T extends Type> void addQuantifiedParam(final Decl<T> decl) {
            final ParamDecl<T> quantifiedParam = Decls.Param(decl.getName(), decl.getType());
            quantifiedParams.add(quantifiedParam);
        }

        private <T extends Type> Expr<T> replaceVars(final Expr<T> expr) {
            if (expr instanceof RefExpr) {
                final Decl<T> originalDecl = ((RefExpr<T>) expr).getDecl();
                final Decl<?> replacementDecl = varMapping.get(originalDecl);
                if (replacementDecl == null) {
                    return expr;
                }
                return cast(replacementDecl.getRef(), expr.getType());
            }
            return expr.map(this::replaceVars);
        }

        private Expr<BoolType> getResultExpr() {
            Expr<BoolType> result = resultExpr;
            if (result == null) {
                setResult();
                result = resultExpr;
            }
            return result;
        }

        private Collection<ParamDecl<?>> getQuantifiedParams() {
            Collection<ParamDecl<?>> result = quantifiedParams;
            if (result == null) {
                setResult();
                result = quantifiedParams;
            }
            return result;
        }
    }
}
