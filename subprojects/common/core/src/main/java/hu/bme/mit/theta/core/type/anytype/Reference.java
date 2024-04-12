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

package hu.bme.mit.theta.core.type.anytype;

import hu.bme.mit.theta.common.Utils;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.model.ImmutableValuation;
import hu.bme.mit.theta.core.model.Valuation;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.LitExpr;
import hu.bme.mit.theta.core.type.Type;
import hu.bme.mit.theta.core.type.bvtype.BvType;
import hu.bme.mit.theta.core.type.inttype.IntLitExpr;
import hu.bme.mit.theta.core.type.inttype.IntType;
import hu.bme.mit.theta.core.utils.BvUtils;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;

public final class Reference<A extends Type, T extends Type> implements Expr<A> {

    private static final String OPERATOR_LABEL = "ref";
    private final Expr<T> expr;
    private final A type;

    private Reference(Expr<T> expr, A type) {
        this.expr = expr;
        this.type = type;
    }

    public Expr<T> getExpr() {
        return expr;
    }

    public Expr<T> getLiteral() {
        return (Expr<T>) eval(ImmutableValuation.empty());
    }

    public static <A extends Type, T extends Type> Reference<A, T> of(Expr<T> expr, A type) {
        return new Reference<>(expr, type);
    }

    @Override
    public int getArity() {
        return 1;
    }

    @Override
    public A getType() {
        return type;
    }

    @Override
    public LitExpr<A> eval(Valuation val) {
        Function<Integer, LitExpr<A>> fromInt = null;
        if (type instanceof IntType) {
            //noinspection unchecked
            fromInt = integer -> (LitExpr<A>) IntLitExpr.of(BigInteger.valueOf(integer));
        } else if (type instanceof BvType) {
            fromInt = integer -> (LitExpr<A>) BvUtils.fitBigIntegerIntoNeutralDomain(BigInteger.valueOf(integer), ((BvType) type).getSize());
        } else {
            throw new RuntimeException("Pointers should either be Int or BvType");
        }

        if (expr instanceof RefExpr<T> refExpr) {
            return fromInt.apply(refExpr.getDecl().hashCode());
        } else {
            checkState(expr instanceof Reference<?, ?>);
            return (LitExpr<A>) expr.eval(val);
        }
    }

    @Override
    public List<? extends Expr<?>> getOps() {
        return List.of(expr);
    }

    @Override
    public Expr<A> withOps(List<? extends Expr<?>> ops) {
        checkState(ops.size() == 1);
        checkState(ops.get(0) instanceof RefExpr<?> && ((RefExpr) ops.get(0)).getDecl() instanceof VarDecl<?>, "Don't transform references to constants.");
        return Reference.of(ops.get(0), type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expr, type);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Reference<?, ?> that) {
            return Objects.equals(this.expr, that.expr) &&
                    Objects.equals(this.type, that.type);
        }
        return false;
    }

    @Override
    public String toString() {
        return Utils.lispStringBuilder(OPERATOR_LABEL).add(getExpr()).add(type)
                .toString();
    }
}
