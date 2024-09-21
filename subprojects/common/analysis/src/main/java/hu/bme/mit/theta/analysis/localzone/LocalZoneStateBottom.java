package hu.bme.mit.theta.analysis.localzone;

import hu.bme.mit.theta.xta.XtaSystem;
import hu.bme.mit.theta.xta.XtaProcess;
import hu.bme.mit.theta.analysis.zone.DBM;

import java.util.Collections;
import java.util.Optional;

public class LocalZoneStateBottom extends LocalZoneState {
    private LocalZoneStateBottom(final XtaSystem system) {
        super(system);
    }

    @Override
    public Optional<DBM> getDbmForProcess(XtaProcess proc) {
        return Optional.of(DBM.bottom(Collections.emptySet()));
    }
}
