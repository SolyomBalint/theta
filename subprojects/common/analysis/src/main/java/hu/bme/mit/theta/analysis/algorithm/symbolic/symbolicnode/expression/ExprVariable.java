package hu.bme.mit.theta.analysis.algorithm.symbolic.symbolicnode.expression;

import com.google.common.base.Preconditions;
import hu.bme.mit.delta.Pair;
import hu.bme.mit.delta.collections.IntObjMapView;
import hu.bme.mit.delta.collections.IntSetView;
import hu.bme.mit.delta.java.mdd.MddVariable;
import hu.bme.mit.theta.analysis.algorithm.symbolic.symbolicnode.IncompleteMddSymbolicNodeInterpretation;
import hu.bme.mit.theta.analysis.algorithm.symbolic.symbolicnode.MddSymbolicNode;
import hu.bme.mit.theta.analysis.algorithm.symbolic.symbolicnode.MddSymbolicNodeTraverser;
import hu.bme.mit.theta.core.decl.ConstDecl;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.utils.ExprUtils;
import hu.bme.mit.theta.solver.Solver;

import java.util.function.Supplier;

public class ExprVariable {

    public static IntObjMapView<MddSymbolicNode> getNodeInterpreter(MddSymbolicNode node, MddVariable variable, Supplier<Solver> solverSupplier) {
        if (!node.isOn(variable)) {
            if (!node.isBelow(variable)) {
                throw new AssertionError();
            }

            IntObjMapView<MddSymbolicNode> nodeInterpreter = IntObjMapView.empty(node);
            if (variable.isBounded()) {
                nodeInterpreter = ((IntObjMapView)nodeInterpreter).trim(IntSetView.range(0, variable.getDomainSize()));
            }
            return nodeInterpreter;
        }

        if(node.isComplete()){
            return node.getCacheView();
        } else {
            final Expr<?> expr = node.getSymbolicRepresentation(Expr.class).first;
            // TODO this check should only be done once per node
            // TODO this is not the right place for this check
            if(!ExprUtils.getConstants(expr).contains(node.getSymbolicRepresentation().second.getTraceInfo(ConstDecl.class))){
                final MddSymbolicNode childNode = ExprNodeUtils.uniqueTable.checkIn(new MddSymbolicNode(new Pair<>(expr,node.getSymbolicRepresentation().second.getLower().orElse(null))));
                if(node.getCacheView().defaultValue() != null) Preconditions.checkState(node.getCacheView().defaultValue().equals(childNode));
                else {
                    node.getExplicitRepresentation().cacheDefault(childNode);
                    node.getExplicitRepresentation().setComplete();
                }
                return node.getCacheView();
            }
            return new IncompleteMddSymbolicNodeInterpretation(node, getNodeTraverser(node, solverSupplier));
        }
    }

    public static MddSymbolicNodeTraverser getNodeTraverser(MddSymbolicNode node, Supplier<Solver> solverSupplier){
        return new ExprNodeTraverser(node, solverSupplier);
    }


}
