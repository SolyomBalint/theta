package hu.bme.mit.theta.xta.local_analysis;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.Collections;

import hu.bme.mit.theta.analysis.InitFunc;
import hu.bme.mit.theta.analysis.localzone.LocalZonePrec;
import hu.bme.mit.theta.analysis.localzone.LocalZoneState;

final class XtaLocalInitFunc implements InitFunc<LocalZoneState, LocalZonePrec> {

    private static final XtaLocalInitFunc INSTANCE = new XtaLocalInitFunc();

    private XtaLocalInitFunc() {
    }

    static XtaLocalInitFunc getInstance() {
        return INSTANCE;
    }

    @Override
    public Collection<? extends LocalZoneState> getInitStates(LocalZonePrec prec) {
        checkNotNull(prec);
        return Collections.singleton(LocalZoneState.zero(prec.getMapping()).transform().up().build());
    }
}
