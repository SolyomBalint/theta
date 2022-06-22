package hu.bme.mit.theta.analysis.algorithm.symbolic.expression;

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import hu.bme.mit.theta.core.decl.Decl;
import hu.bme.mit.theta.core.model.Valuation;
import hu.bme.mit.theta.core.type.LitExpr;
import hu.bme.mit.theta.solver.Solver;
import hu.bme.mit.theta.solver.SolverStatus;
import hu.bme.mit.theta.solver.utils.WithPushPop;

import java.util.Optional;
import java.util.Stack;

import static hu.bme.mit.theta.core.type.abstracttype.AbstractExprs.Eq;
import static hu.bme.mit.theta.core.type.abstracttype.AbstractExprs.Neq;

public class ExpressionNodeTraverser {

    private ExpressionNode currentNode;

    private final Solver solver;

    private final Stack<ExpressionNode> stack;
    private int pushedNegatedAssignments = 0;


    public ExpressionNodeTraverser(ExpressionNode rootNode, Supplier<Solver> solverSupplier) {
        this.solver = solverSupplier.get();
        this.stack = new Stack<>();

        this.currentNode = Preconditions.checkNotNull(rootNode);
        stack.push(rootNode);
        solver.add(rootNode.getDecision().first);
        pushNegatedAssignments();
    }

    public boolean isOn(final ExpressionNode node){
        Preconditions.checkNotNull(node);
        return currentNode.equals(node);
    }

    public void moveUp(){
        Preconditions.checkState(stack.size()>1);
        popNegatedAssignments();
        solver.pop(); // pop assignment that brought us here
        currentNode = stack.pop();
        pushNegatedAssignments();
    }

    public boolean queryAssignment(int assignment){
        if(currentNode.getCacheView().keySet().contains(assignment)) return true;
        else if(!currentNode.isComplete()){
            SolverStatus status;
            final LitExpr<?> litExpr = LitExprConverter.toLitExpr(assignment, currentNode.getVariable().getTraceInfo(Decl.class).getType());
            try(WithPushPop wpp = new WithPushPop(solver)){
                solver.add(Eq(currentNode.getVariable().getTraceInfo(Decl.class).getRef(), litExpr));
                solver.check();
                status = solver.getStatus();
            }
            Preconditions.checkNotNull(status);
            if(status.isSat()) {
                solver.add(Neq(currentNode.getVariable().getTraceInfo(Decl.class).getRef(), litExpr));
                pushedNegatedAssignments++;
                cacheModel(solver.getModel());
                return true;
            }
        }
        return false;
    }

    public void queryAssignment(){
        if(!currentNode.isComplete()){
            if(pushedNegatedAssignments != currentNode.getCacheView().keySet().size()){
                popNegatedAssignments();
                pushNegatedAssignments();
            }
            solver.check();
            if(solver.getStatus().isSat()){
                final Valuation model = solver.getModel();
                Optional<LitExpr<?>> optionalLitExpr = model.eval(currentNode.getVariable().getTraceInfo(Decl.class));
                if(optionalLitExpr.isPresent()){
                    LitExpr<?> literal = optionalLitExpr.get();
                    solver.add(Neq(currentNode.getVariable().getTraceInfo(Decl.class).getRef(), literal));
                    pushedNegatedAssignments++;
                    cacheModel(model);
                } else {
                    // TODO na itt mi van?
                    throw new UnsupportedOperationException("Not implemented yet");
                }
            } else {
                currentNode.setComplete();
            }
        }

    }

    public void moveDown(int assignment){
        if(queryAssignment(assignment)){
            popNegatedAssignments();
            solver.push();
            solver.add(Eq(currentNode.getVariable().getTraceInfo(Decl.class).getRef(), LitExprConverter.toLitExpr(assignment, currentNode.getVariable().getTraceInfo(Decl.class).getType())));
            currentNode = currentNode.getCacheView().get(assignment);
            stack.push(currentNode);
            pushNegatedAssignments();
        } else throw new IllegalArgumentException("The provided assignment does not satisfy the formula.");
    }

    private void cacheModel(Valuation model){
        ExpressionNodeUtils.cacheModel(currentNode,model);
    }

    private void pushNegatedAssignments(){
        solver.push();
        for(var cur = currentNode.getCacheView().cursor();cur.moveNext();){
            solver.add(Neq(currentNode.getVariable().getTraceInfo(Decl.class).getRef(), LitExprConverter.toLitExpr(cur.key(), currentNode.getVariable().getTraceInfo(Decl.class).getType())));
            pushedNegatedAssignments++;
        }
    }

    private void popNegatedAssignments(){
        solver.pop();
        pushedNegatedAssignments = 0;
    }


}
