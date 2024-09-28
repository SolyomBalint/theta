package hu.bme.mit.theta.xta.local_analysis;

import com.google.common.collect.Lists;

import hu.bme.mit.theta.analysis.localzone.LocalZonePrec;
import hu.bme.mit.theta.analysis.localzone.LocalZoneState;
import hu.bme.mit.theta.core.clock.op.ResetOp;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.type.rattype.RatType;
import hu.bme.mit.theta.xta.Guard;
import hu.bme.mit.theta.xta.Update;
import hu.bme.mit.theta.xta.XtaProcess.Edge;
import hu.bme.mit.theta.xta.XtaProcess.Loc;
import hu.bme.mit.theta.xta.XtaProcess.LocKind;
import hu.bme.mit.theta.xta.analysis.XtaAction;
import hu.bme.mit.theta.xta.analysis.XtaAction.BasicXtaAction;
import hu.bme.mit.theta.xta.analysis.XtaAction.BinaryXtaAction;
import hu.bme.mit.theta.xta.analysis.XtaAction.BroadcastXtaAction;

import java.util.Collection;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static hu.bme.mit.theta.core.clock.constr.ClockConstrs.Eq;

public final class XtaLocalZoneUtils {

    private XtaLocalZoneUtils() {
    }

    public static LocalZoneState post(final LocalZoneState state, final XtaAction action,
                                 final LocalZonePrec prec) {
        checkNotNull(state);
        checkNotNull(action);
        checkNotNull(prec);

        if (action.isBasic()) {
            return postForBasicAction(state, action.asBasic(), prec);
        } else if (action.isBinary()) {
            return postForBinaryAction(state, action.asBinary(), prec);
        } else if (action.isBroadcast()) {
            return postForBroadcastAction(state, action.asBroadcast(), prec);
        } else {
            throw new AssertionError();
        }
    }

    private static LocalZoneState postForBasicAction(final LocalZoneState state, final BasicXtaAction action,
                                                final LocalZonePrec prec) {
        final LocalZoneState.Builder succStateBuilder = state.project(prec.getMapping());

        final List<Loc> sourceLocs = action.getSourceLocs();
        final Edge edge = action.getEdge();
        final List<Loc> targetLocs = action.getTargetLocs();

        applyInvariants(succStateBuilder, sourceLocs);
        applyGuards(succStateBuilder, edge);
        applyUpdates(succStateBuilder, edge);
        applyInvariants(succStateBuilder, targetLocs);

        List<Loc> involvedLocs = action.getTargetLocs();
        if (shouldApplyDelay(involvedLocs)) {
            applyDelay(succStateBuilder, involvedLocs);
        }

        final LocalZoneState succState = succStateBuilder.build();
        return succState;
    }

    private static LocalZoneState postForBinaryAction(final LocalZoneState state,
                                                 final BinaryXtaAction action,
                                                 final LocalZonePrec prec) {
        final LocalZoneState.Builder succStateBuilder = state.project(prec.getMapping());

        final List<Loc> sourceLocs = action.getSourceLocs();
        final Edge emittingEdge = action.getEmitEdge();
        final Edge receivingEdge = action.getRecvEdge();
        final List<Loc> targetLocs = action.getTargetLocs();

        applyInvariants(succStateBuilder, sourceLocs);
        applyGuards(succStateBuilder, emittingEdge);
        applyGuards(succStateBuilder, receivingEdge);
        applyUpdates(succStateBuilder, emittingEdge);
        applyUpdates(succStateBuilder, receivingEdge);
        applyInvariants(succStateBuilder, targetLocs);

        if (shouldApplyDelay(targetLocs)) {
            applyDelay(succStateBuilder, targetLocs);
        }

        final LocalZoneState succState = succStateBuilder.build();
        return succState;
    }

    private static LocalZoneState postForBroadcastAction(final LocalZoneState state,
                                                    final BroadcastXtaAction action,
                                                    final LocalZonePrec prec) {
        final LocalZoneState.Builder succStateBuilder = state.project(prec.getMapping());

        final List<Loc> sourceLocs = action.getSourceLocs();
        final Edge emitEdge = action.getEmitEdge();
        final List<Edge> recvEdges = action.getRecvEdges();
        final List<Collection<Edge>> nonRecvEdgeCols = action.getNonRecvEdges();
        final List<Loc> targetLocs = action.getTargetLocs();

        applyInvariants(succStateBuilder, sourceLocs);
        applyGuards(succStateBuilder, emitEdge);

        if (recvEdges.stream().anyMatch(XtaLocalZoneUtils::hasClockGuards)) {
            throw new UnsupportedOperationException(
                    "Clock guards on edges with broadcast synchronization labels are not supported.");
        }

        if (nonRecvEdgeCols.stream()
                .anyMatch(c -> c.stream().anyMatch(XtaLocalZoneUtils::hasClockGuards))) {
            throw new UnsupportedOperationException(
                    "Clock guards on edges with broadcast synchronization labels are not supported.");
        }

        applyUpdates(succStateBuilder, emitEdge);
        recvEdges.stream().forEachOrdered(recvEdge -> applyUpdates(succStateBuilder, recvEdge));
        applyInvariants(succStateBuilder, targetLocs);

        if (shouldApplyDelay(targetLocs)) {
            applyDelay(succStateBuilder, targetLocs);
        }

        final LocalZoneState succState = succStateBuilder.build();
        return succState;
    }

    private static boolean hasClockGuards(Edge edge) {
        return edge.getGuards().stream().anyMatch(Guard::isClockGuard);
    }

    ////

    public static LocalZoneState pre(final LocalZoneState state, final XtaAction action,
                                final LocalZonePrec prec) {
        checkNotNull(state);
        checkNotNull(action);
        checkNotNull(prec);

        if (action.isBasic()) {
            return preForBasicAction(state, action.asBasic(), prec);
        } else if (action.isBinary()) {
            return preForBinaryAction(state, action.asBinary(), prec);
        } else if (action.isBroadcast()) {
            return preForBroadcastAction(state, action.asBroadcast(), prec);
        } else {
            throw new AssertionError();
        }
    }

    private static LocalZoneState preForBasicAction(final LocalZoneState state, final BasicXtaAction action,
                                               final LocalZonePrec prec) {
        final LocalZoneState.Builder preStateBuilder = state.project(prec.getMapping());

        final List<Loc> sourceLocs = action.getSourceLocs();
        final Edge edge = action.getEdge();
        final List<Loc> targetLocs = action.getTargetLocs();

        List<Loc> involvedLocs = action.getTargetLocs();
        if (shouldApplyDelay(involvedLocs)) {
            applyInverseDelay(preStateBuilder, involvedLocs);
        }
        applyInvariants(preStateBuilder, targetLocs);
        applyInverseUpdates(preStateBuilder, edge);
        applyGuards(preStateBuilder, edge);
        applyInvariants(preStateBuilder, sourceLocs);

        final LocalZoneState preState = preStateBuilder.build();
        return preState;
    }

    private static LocalZoneState preForBinaryAction(final LocalZoneState state, final BinaryXtaAction action,
                                                final LocalZonePrec prec) {
        final LocalZoneState.Builder preStateBuilder = state.project(prec.getMapping());

        final List<Loc> sourceLocs = action.getSourceLocs();
        final Edge emitEdge = action.getEmitEdge();
        final Edge recvEdge = action.getRecvEdge();
        final List<Loc> targetLocs = action.getTargetLocs();

        List<Loc> involvedLocs = action.getTargetLocs();
        if (shouldApplyDelay(involvedLocs)) {
            applyInverseDelay(preStateBuilder, involvedLocs);
        }
        applyInvariants(preStateBuilder, targetLocs);
        applyInverseUpdates(preStateBuilder, recvEdge);
        applyInverseUpdates(preStateBuilder, emitEdge);
        applyGuards(preStateBuilder, recvEdge);
        applyGuards(preStateBuilder, emitEdge);
        applyInvariants(preStateBuilder, sourceLocs);

        final LocalZoneState succState = preStateBuilder.build();
        return succState;
    }

    private static LocalZoneState preForBroadcastAction(final LocalZoneState state,
                                                   final BroadcastXtaAction action,
                                                   final LocalZonePrec prec) {
        final LocalZoneState.Builder preStateBuilder = state.project(prec.getMapping());

        final List<Loc> sourceLocs = action.getSourceLocs();
        final Edge emitEdge = action.getEmitEdge();
        final List<Edge> reverseRecvEdges = Lists.reverse(action.getRecvEdges());
        final List<Collection<Edge>> nonRecvEdgeCols = action.getNonRecvEdges();
        final List<Loc> targetLocs = action.getTargetLocs();

        List<Loc> involvedLocs = action.getTargetLocs();
        if (shouldApplyDelay(involvedLocs)) {
            applyInverseDelay(preStateBuilder, involvedLocs);
        }
        applyInvariants(preStateBuilder, targetLocs);
        reverseRecvEdges.stream()
                .forEachOrdered(recvEdge -> applyInverseUpdates(preStateBuilder, recvEdge));
        applyInverseUpdates(preStateBuilder, emitEdge);

        if (nonRecvEdgeCols.stream()
                .anyMatch(c -> c.stream().anyMatch(XtaLocalZoneUtils::hasClockGuards))) {
            throw new UnsupportedOperationException(
                    "Clock guards on edges with broadcast synchronization labels are not supported.");
        }

        if (reverseRecvEdges.stream().anyMatch(XtaLocalZoneUtils::hasClockGuards)) {
            throw new UnsupportedOperationException(
                    "Clock guards on edges with broadcast synchronization labels are not supported.");
        }

        applyGuards(preStateBuilder, emitEdge);
        applyInvariants(preStateBuilder, sourceLocs);

        final LocalZoneState succState = preStateBuilder.build();
        return succState;
    }

    ////

    private static boolean shouldApplyDelay(final List<Loc> locs) {
        return locs.stream().allMatch(l -> l.getKind() == LocKind.NORMAL);
    }

    // This needs altering
    private static void applyDelay(final LocalZoneState.Builder builder, List<Loc> involvedLocs) {
        builder.nonnegative();
        for(var loc : involvedLocs) {
            builder.localUp(loc.getProc());
        }
    }

    // This needs altering
    private static void applyInverseDelay(final LocalZoneState.Builder builder, List<Loc> involvedLocs) {
        for(var loc : involvedLocs) {
            builder.localDown(loc.getProc());
        }
        builder.nonnegative();
    }

    private static void applyInvariants(final LocalZoneState.Builder builder,
                                        final Collection<Loc> locs) {
        for (final Loc target : locs) {
            for (final Guard invar : target.getInvars()) {
                if (invar.isClockGuard()) {
                    builder.and(invar.asClockGuard().getClockConstr());
                }
            }
        }
    }

    private static void applyUpdates(final LocalZoneState.Builder builder, final Edge edge) {
        for (final Update update : edge.getUpdates()) {
            if (update.isClockUpdate()) {
                final ResetOp op = (ResetOp) update.asClockUpdate().getClockOp();
                final VarDecl<RatType> varDecl = op.getVar();
                final int value = op.getValue();
                builder.reset(varDecl, value);
            }
        }
    }

    private static void applyInverseUpdates(final LocalZoneState.Builder builder, final Edge edge) {
        for (final Update update : Lists.reverse(edge.getUpdates())) {
            if (update.isClockUpdate()) {
                final ResetOp op = (ResetOp) update.asClockUpdate().getClockOp();
                final VarDecl<RatType> varDecl = op.getVar();
                final int value = op.getValue();
                builder.and(Eq(varDecl, value));
                builder.free(varDecl);
            }
        }
    }

    private static void applyGuards(final LocalZoneState.Builder builder, final Edge edge) {
        for (final Guard guard : edge.getGuards()) {
            if (guard.isClockGuard()) {
                builder.and(guard.asClockGuard().getClockConstr());
            }
        }
    }

}
