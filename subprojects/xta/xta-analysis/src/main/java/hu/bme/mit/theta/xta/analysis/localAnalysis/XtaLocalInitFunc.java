package hu.bme.mit.theta.xta.analysis.localAnalysis;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.List;

import hu.bme.mit.theta.analysis.InitFunc;
import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.analysis.State;
import hu.bme.mit.theta.xta.XtaSystem;
import hu.bme.mit.theta.xta.XtaProcess.Loc;
import hu.bme.mit.theta.xta.analysis.XtaState;

final class XtaLocalInitFunc<S extends State, P extends Prec> implements InitFunc<XtaState<S>, P> {

    private final XtaSystem system;
    private final InitFunc<S, ? super P> initFunc;

    private XtaLocalInitFunc(final XtaSystem system, final InitFunc<S, ? super P> initFunc) {
        this.system = checkNotNull(system);
        this.initFunc = checkNotNull(initFunc);
    }

    public static <S extends State, P extends Prec> XtaLocalInitFunc<S, P> create(final XtaSystem system,
                                                                             final InitFunc<S, ? super P> initFunc) {
        return new XtaLocalInitFunc<>(system, initFunc);
    }

    @Override
    public Collection<XtaState<S>> getInitStates(final P prec) {
        checkNotNull(prec);
        final List<Loc> initLocs = system.getInitLocs();
        final Collection<? extends S> initStates = initFunc.getInitStates(prec);
        return XtaState.collectionOf(initLocs, initStates);
    }

}
