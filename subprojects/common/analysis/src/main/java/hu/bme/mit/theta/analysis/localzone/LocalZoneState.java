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

package hu.bme.mit.theta.analysis.localzone;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;


import hu.bme.mit.theta.analysis.expr.ExprState;
import hu.bme.mit.theta.analysis.zone.BoundFunc;
import hu.bme.mit.theta.analysis.zone.DBM;
import hu.bme.mit.theta.analysis.zone.DbmRelation;
import hu.bme.mit.theta.common.Utils;
import hu.bme.mit.theta.common.container.Containers;
import hu.bme.mit.theta.core.clock.constr.ClockConstr;
import hu.bme.mit.theta.core.clock.op.ClockOp;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.type.Expr;
import hu.bme.mit.theta.core.type.booltype.BoolType;
import hu.bme.mit.theta.core.type.rattype.RatType;
import hu.bme.mit.theta.xta.XtaProcess;
import hu.bme.mit.theta.xta.XtaSystem;
import static hu.bme.mit.theta.core.type.booltype.SmartBoolExprs.And;
import static java.util.stream.Collectors.toList;

public class LocalZoneState implements ExprState {

    private interface DbmCalcInterface {
         DBM dbmCalc(final DBM lhs, final DBM rhs);
    }

    private static final int HASH_SEED = 4349;

    private volatile int hashCode = 0;
    private volatile Expr<BoolType> expr = null;

    private  Map<XtaProcess, DBM> localDBMs = Containers.createMap();

    // Protected so that children classes can call the parent ctr
    protected LocalZoneState(final XtaSystem system) {
        for (var mapping : system.getProcessClockMap().entrySet()){
            localDBMs.put(mapping.getKey(), DBM.zero(mapping.getValue()));
        }
    }

    protected LocalZoneState(final Builder builder) {
        this.localDBMs = builder.localDBMs;
    }

    protected LocalZoneState(final Map<XtaProcess, DBM> inputMap) {
        localDBMs = inputMap;
    }

    public Optional<DBM> getDbmForProcess(XtaProcess proc) {
        return Optional.ofNullable(localDBMs.get(proc));
    }

    private static Map<XtaProcess, DBM> twoOperandLocalZoneCalc(
        final LocalZoneState zone1,
        final LocalZoneState zone2,
        DbmCalcInterface lambda
    ) {
        checkNotNull(zone1);
        checkNotNull(zone2);

        Map<XtaProcess, DBM> toReturn = Containers.createMap();
        for(var zone1Map : zone1.localDBMs.entrySet()){
            // The key stores the process
            DBM zone2Dbm = zone2.localDBMs.get(zone1Map.getKey());

            checkNotNull(zone1Map.getValue());
            checkNotNull(zone2Dbm);

            toReturn.put(zone1Map.getKey(), lambda.dbmCalc(zone1Map.getValue(), zone2Dbm));
        }

        return toReturn;
    }

    public static LocalZoneState intersection(final LocalZoneState zone1, final LocalZoneState zone2) {
        // This may add an unnecessary function call to the mix, but it saves lots of coding lines, later it would be
        // good to check the technical options
        return new LocalZoneState(twoOperandLocalZoneCalc(zone1, zone2, (z1, z2) -> {return DBM.intersection(z1, z2);}));
    }

    public static LocalZoneState enclosure(final LocalZoneState zone1, final LocalZoneState zone2) {
        return new LocalZoneState(twoOperandLocalZoneCalc(zone1, zone2, (z1, z2) -> {return DBM.enclosure(z1, z2);}));
    }

    public static LocalZoneState interpolant(final LocalZoneState zone1, final LocalZoneState zone2) {
        return new LocalZoneState(twoOperandLocalZoneCalc(zone1, zone2, (z1, z2) -> {return DBM.interpolant(z1, z2);}));
    }

    public static LocalZoneState weakInterpolant(final LocalZoneState zone1, final LocalZoneState zone2) {
        return new LocalZoneState(twoOperandLocalZoneCalc(zone1, zone2, (z1, z2) -> {return DBM.weakInterpolant(z1, z2);}));
    }


    @Override
    public Expr<BoolType> toExpr() {
        Expr<BoolType> result = expr;
        if (result == null) {
            Collection<Expr<BoolType>> exprs = Containers.createSet();
            for(var mapping : this.localDBMs.entrySet()){
                exprs.addAll(mapping.getValue().getConstrs().stream().map(ClockConstr::toExpr).collect(toList()));
            }
            result = And(exprs);
            expr = result;
        }
        return result;
    }

    @Override
    public int hashCode() {
        int result = hashCode;
        if (result == 0) {
            result = HASH_SEED;
            result = 31 * result + localDBMs.hashCode();
            hashCode = result;
        }
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        } else if (obj != null && this.getClass() == obj.getClass()) {
            final LocalZoneState that = (LocalZoneState) obj;
            for(var mapping : this.localDBMs.entrySet()){
                DBM thatProcessDbm = that.getDbmForProcess(mapping.getKey()).get();
                DBM thisProcessDmb = mapping.getValue();
                checkNotNull(thisProcessDmb);

                if(!thisProcessDmb.equals(thatProcessDbm))
                    return false;
            }
        } else {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        Collection<ClockConstr> constrs = Containers.createSet();
        for(var dbm : this.localDBMs.values()){
            constrs.addAll(dbm.getConstrs());
        }
        return Utils.lispStringBuilder(getClass().getSimpleName()).aligned().addAll(constrs)
                .toString();
    }
    //TODO ask about this shit
    //public Collection<LocalZoneState> complement() { for(var dbm : this.localDBMs.values()){ final Collection<DBM> dbms = dbm.complement(); dbms.stream().map(LocalZoneState::new).collect(toList());
    //     }
    //         final Collection<DBM> dbms = dbm.complement();
    //         return dbms.stream().map(LocalZoneState::new).collect(toList());
    //     }

    public Builder transform() {
        return Builder.transform(this);
    }

    // TODO ask how this shit works
    // public Builder project(final Collection<? extends VarDecl<RatType>> clocks) {
    //     checkNotNull(clocks);
    //     return Builder.project(this, clocks);
    // }
    //

    public boolean isTop() {
        for(var dbm : this.localDBMs.values()){
            if (!(DBM.top(Collections.emptySet()).getRelation(dbm) == DbmRelation.EQUAL)){
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean isBottom() {
        for(var dbm : this.localDBMs.values()){
            if (!dbm.isConsistent()){
                return true;
            }
        }

        return false;
    }

    public boolean isLeq(final LocalZoneState that) {
        for(var mapping : this.localDBMs.entrySet()){
            DBM thatProcessDbm = that.getDbmForProcess(mapping.getKey()).get();
            DBM thisProcessDmb = mapping.getValue();
            checkNotNull(thisProcessDmb);

            if(!thisProcessDmb.isLeq(thatProcessDbm))
                return false;
        }

        return true;
    }

    // TODO ask how this shit works
    // public boolean isLeq(final LocalZoneState that,
    //                      final Collection<? extends VarDecl<RatType>> activeVars) {
    //
    //     return this.dbm.isLeq(that.dbm, activeVars);
    // }

    public boolean isLeq(final LocalZoneState that, final BoundFunc boundFunction) {
        for(var mapping : this.localDBMs.entrySet()){
            DBM thatProcessDbm = that.getDbmForProcess(mapping.getKey()).get();
            DBM thisProcessDmb = mapping.getValue();
            checkNotNull(thisProcessDmb);

            if(!thisProcessDmb.isLeq(thatProcessDbm, boundFunction))
                return false;
        }

        return true;
    }

    public boolean isConsistentWith(final LocalZoneState that) {
        for(var mapping : this.localDBMs.entrySet()){
            DBM thatProcessDbm = that.getDbmForProcess(mapping.getKey()).get();
            DBM thisProcessDmb = mapping.getValue();
            checkNotNull(thisProcessDmb);

            if(!thisProcessDmb.isConsistentWith(thatProcessDbm))
                return false;
        }

        return true;
    }
    ////////
    // Since Java lambdas suck and there is no nice overload this is going to be a whole lot of code repetition

    public static class Builder {

        private  Map<XtaProcess, DBM> localDBMs = Containers.createMap();

        private Builder(final Map<XtaProcess, DBM> localDBMs) {
            this.localDBMs = localDBMs;
        }

        ////

        private static Builder transform(final LocalZoneState state) {
            Map<XtaProcess, DBM> tmp = Containers.createMap();

            for(var mapping : state.localDBMs.entrySet()){
                tmp.put(XtaProcess.copyOf(mapping.getKey()), DBM.copyOf(mapping.getValue()));
            }
            // TODO ask if this tmp gets later deleted or how this works in java
            return new Builder(tmp);
        }

        // TODO ask about this one
        // private static Builder project(final ZoneState state,
        //                                final Collection<? extends VarDecl<RatType>> clocks) {
        //     return new Builder(DBM.project(state.dbm, clocks));
        // }

        ////

        public LocalZoneState build() {
            return new LocalZoneState(this);
        }

        ////

        public Builder up() {
            for(var dbm : this.localDBMs.values())
                dbm.up();
            return this;
        }

        public Builder down() {
            for(var dbm : this.localDBMs.values())
                dbm.down();
            return this;
        }

        public Builder nonnegative() {
            for(var dbm : this.localDBMs.values())
                dbm.nonnegative();
            return this;
        }

        public Builder execute(final ClockOp op) {
            for(var dbm : this.localDBMs.values())
                dbm.execute(op);
            return this;
        }

        public Builder and(final ClockConstr constr) {
            for(var dbm : this.localDBMs.values())
                dbm.and(constr);
            return this;
        }

        //TODO the input of this may be wrong
        public Builder free(final VarDecl<RatType> varDecl) {
            for(var dbm : this.localDBMs.values())
                dbm.free(varDecl);
            return this;
        }

        //TODO the input of this may be wrong
        public Builder reset(final VarDecl<RatType> varDecl, final int m) {
            for(var dbm : this.localDBMs.values())
                dbm.reset(varDecl, m);
            return this;
        }

        public Builder copy(final VarDecl<RatType> lhs, final VarDecl<RatType> rhs) {
            for(var dbm : this.localDBMs.values())
                dbm.copy(lhs, rhs);
            return this;
        }

        //TODO the input of this may be wrong
        public Builder shift(final VarDecl<RatType> varDecl, final int m) {
            for(var dbm : this.localDBMs.values())
                dbm.shift(varDecl, m);
            return this;
        }

        //TODO the input of this may be wrong
        public Builder norm(final Map<? extends VarDecl<RatType>, ? extends Integer> ceilings) {
            for(var dbm : this.localDBMs.values())
                dbm.norm(ceilings);
            return this;
        }
    }


}
