package hu.bme.mit.theta.xta.analysis.prec;

import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.analysis.expl.ExplPrec;
import hu.bme.mit.theta.analysis.pred.PredPrec;
import hu.bme.mit.theta.analysis.prod2.Prod2Prec;
import hu.bme.mit.theta.analysis.zone.ClockPredPrec;
import hu.bme.mit.theta.analysis.zone.Pair;
import hu.bme.mit.theta.analysis.zone.ZonePrec;
import hu.bme.mit.theta.common.container.Containers;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.type.rattype.RatType;
import hu.bme.mit.theta.xta.XtaProcess;
import hu.bme.mit.theta.xta.XtaSystem;

import java.util.*;
import java.util.stream.Collectors;

public final class  XtaInitPrec{
    public static Prod2Prec<PredPrec,  ZonePrec> createEmptyProd2PredZone(XtaSystem xta){
        return Prod2Prec.of(PredPrec.of(), ZonePrec.of(xta.getClockVars()));
    }

    public static Prod2Prec<PredPrec, ZonePrec> createEmptyZoneProd2PredZone(XtaSystem xta) {
        return Prod2Prec.of(PredPrec.of(), ZonePrec.of(Collections.emptySet()));
    }
    public static LocalXtaPrec<Prod2Prec<PredPrec, ZonePrec>> collectLocalProd2PredZone(XtaSystem xta) {
        Map<XtaProcess.Loc, Prod2Prec<PredPrec, ZonePrec>> mapping = Containers.createMap();
        for(XtaProcess proc: xta.getProcesses()){
            for (XtaProcess.Loc l : proc.getActiveClockMap().keySet()){
                mapping.put(l, Prod2Prec.of(PredPrec.of(), ZonePrec.of(proc.getActiveClockMap().get(l))));
            }
        }
        return LocalXtaPrec.create(mapping, Prod2Prec.of(PredPrec.of(), ZonePrec.of(xta.getClockVars())));
    }

    public static GlobalXtaPrec<Prod2Prec<PredPrec,  ZonePrec>> collectGlobalProd2PredZone(XtaSystem xta) {
        return GlobalXtaPrec.create(Prod2Prec.of(PredPrec.of(), ZonePrec.of(xta.getClockVars())));
    }

    public static Prod2Prec<ExplPrec,  ZonePrec> createEmptyProd2ExplZone(XtaSystem xta) {
        return Prod2Prec.of(ExplPrec.empty(), ZonePrec.of(xta.getClockVars()));
    }

    public static XtaPrec<Prod2Prec<ExplPrec, ZonePrec>> collectLocalProd2ExplZone(XtaSystem xta) {
        Map<XtaProcess.Loc, Prod2Prec<ExplPrec, ZonePrec>> mapping = Containers.createMap();
        for(XtaProcess proc: xta.getProcesses()){
            for (XtaProcess.Loc l : proc.getActiveClockMap().keySet()){
                mapping.put(l, Prod2Prec.of(ExplPrec.empty(), ZonePrec.of(proc.getActiveClockMap().get(l))));
            }
        }
        return LocalXtaPrec.create(mapping, Prod2Prec.of(ExplPrec.empty(), ZonePrec.of(xta.getClockVars())));
    }

    public static XtaPrec<Prod2Prec<ExplPrec,  ZonePrec>> collectGlobalProd2ExplZone(XtaSystem xta) {
        return GlobalXtaPrec.create(Prod2Prec.of(ExplPrec.empty(), ZonePrec.of(xta.getClockVars())));
    }

   /* public static XtaPrec<Prod2Prec<PredPrec, ClockPredPrec>> createTestPrec(XtaSystem xta) {
        ArrayList<VarDecl<RatType>> clocks = new  ArrayList<VarDecl<RatType>>(xta.getClockVars());
        Map<Pair<VarDecl<RatType>, VarDecl<RatType>>, Integer> map = Collections.emptyMap();
        Pair<VarDecl<RatType>, VarDecl<RatType>> pair = new Pair<VarDecl<RatType>, VarDecl<RatType>>(clocks.get(0), clocks.get(1));
        map.put(new Pair<VarDecl<RatType>, VarDecl<RatType>>(clocks.get(0), clocks.get(1)), (10 << 1) | 1);
        return GlobalXtaPrec.create(Prod2Prec.of(PredPrec.of(), ClockPredPrec.of(map, xta.getClockVars())));
    }*/
}
