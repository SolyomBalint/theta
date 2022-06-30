package hu.bme.mit.theta.analysis.algorithm.symbolic.symbolicnode;

import com.google.common.base.Preconditions;
import hu.bme.mit.delta.Pair;
import hu.bme.mit.delta.collections.IntObjMapView;
import hu.bme.mit.delta.java.DdLevel;
import hu.bme.mit.delta.java.mdd.MddNode;
import hu.bme.mit.delta.java.mdd.MddVariable;

import java.util.Objects;

public class MddSymbolicNode implements IMddSymbolicNode {

    private final SymbolicRepresentation symbolicRepresentation;
    private final MddSymbolicNodeTraverser.ExplicitRepresentation explicitRepresentation;

    // MddNodeból lopva
    private final DdLevel<MddNode> level;
    private final int hashCode;
    private int references = 0;

    public MddSymbolicNode(SymbolicRepresentation symbolicRepresentation, DdLevel<MddNode> level) {
        this.symbolicRepresentation = symbolicRepresentation;
        this.level = level;
        this.hashCode = symbolicRepresentation.hashCode();

        this.explicitRepresentation = new MddSymbolicNodeTraverser.ExplicitRepresentation();
    }

    public MddSymbolicNode(Pair<Object, MddVariable> symbolicRepresentation, DdLevel<MddNode> level) {
        this(new SymbolicRepresentation(symbolicRepresentation), level);
    }

    public MddSymbolicNode(SymbolicRepresentation symbolicRepresentation){
        this(symbolicRepresentation, symbolicRepresentation.value.second.getLevel());
    }

    public MddSymbolicNode(Pair<Object, MddVariable> symbolicRepresentation) {
        this(new SymbolicRepresentation(symbolicRepresentation), symbolicRepresentation.second.getLevel());
    }

    public static class SymbolicRepresentation {
        private final Pair<Object, MddVariable> value;

        private SymbolicRepresentation(final Pair<Object, MddVariable> value) {
            this.value = value;
        }

        public static SymbolicRepresentation of(final Pair<Object, MddVariable> value){
            return new SymbolicRepresentation(value);
        }

        @Override
        public boolean equals(Object that) {
            if (this == that) return true;
            if (that instanceof SymbolicRepresentation) {
                return Objects.equals(value, ((SymbolicRepresentation) that).value);
            }
            if (that instanceof MddSymbolicNode) {
                return Objects.equals(value, ((MddSymbolicNode) that).symbolicRepresentation.value);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    @Override
    public Pair<Object, MddVariable> getSymbolicRepresentation() {
        return symbolicRepresentation.value;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean isOn(MddVariable variable) {
        Preconditions.checkNotNull(variable, "variable");
        return this.level == variable.getLevel();
    }

    @Override
    public boolean isAbove(MddVariable variable) {
        Preconditions.checkNotNull(variable, "variable");
        return this.level.isAbove(variable.getLevel());
    }

    @Override
    public boolean isBelow(MddVariable variable) {
        Preconditions.checkNotNull(variable, "variable");
        return this.level.isBelow(variable.getLevel());
    }

    @Override
    public void acquire() {
        if (references == 0) {
            acquireChildren();
        }
        references++;
    }

    private void acquireChildren() {
        // Ez így biztos nem lesz jó
//        for (IntObjCursor<? extends MddNode> c = this.cursor(); c.moveNext(); ) {
//            c.value().acquire();
//        }
//        if ((this.defaultValue() != null) && (this.defaultValue() != this)) {
//            this.defaultValue().acquire();
//        }
    }

    @Override
    public void release() {
        Preconditions.checkArgument(references > 0, "Invalid release on MDD node.");
        references--;
        if (references == 0) {
            releaseChildren();
        }
    }

    private void releaseChildren() {
//        for (IntObjCursor<? extends MddNode> c = this.cursor(); c.moveNext(); ) {
//            c.value().release();
//        }
//        if ((this.defaultValue() != null) && (this.defaultValue() != this)) {
//            this.defaultValue().release();
//        }
    }

    @Override
    public int getReferenceCount() {
        return references;
    }

    public MddSymbolicNodeTraverser.ExplicitRepresentation getExplicitRepresentation() {
        return explicitRepresentation;
    }

    public IntObjMapView<MddSymbolicNode> getCacheView() {
        return explicitRepresentation.getCacheView();
    }

    public boolean isComplete() {
        return explicitRepresentation.isComplete();
    }
}
