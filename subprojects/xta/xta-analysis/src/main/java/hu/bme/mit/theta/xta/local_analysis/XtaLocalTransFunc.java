package hu.bme.mit.theta.xta.local_analysis;

import java.util.Collection;

import com.google.common.collect.ImmutableList;

import hu.bme.mit.theta.analysis.TransFunc;
import hu.bme.mit.theta.analysis.localzone.LocalZonePrec;
import hu.bme.mit.theta.analysis.localzone.LocalZoneState;
import hu.bme.mit.theta.xta.analysis.XtaAction;

final class XtaZoneTransFunc implements TransFunc<LocalZoneState, XtaAction, LocalZonePrec> {

    private final static XtaZoneTransFunc INSTANCE = new XtaZoneTransFunc();

    private XtaZoneTransFunc() {
    }

    static XtaZoneTransFunc getInstance() {
        return INSTANCE;
    }

    @Override
    public Collection<LocalZoneState> getSuccStates(final LocalZoneState state, final XtaAction action,
                                               final LocalZonePrec prec) {
        final LocalZoneState succState = XtaLocalZoneUtils.post(state, action, prec);
        return ImmutableList.of(succState);
    }

}
