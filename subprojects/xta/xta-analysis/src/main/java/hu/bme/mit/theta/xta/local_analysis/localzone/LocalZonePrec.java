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

import com.google.common.collect.ImmutableMap;
import hu.bme.mit.theta.analysis.Prec;
import hu.bme.mit.theta.common.LispStringBuilder;
import hu.bme.mit.theta.common.Utils;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.type.rattype.RatType;
import hu.bme.mit.theta.xta.XtaProcess;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

public final class LocalZonePrec implements Prec {

    private final Map<XtaProcess, Collection<VarDecl<RatType>>> clocksProcessMap;

    // TODO java fuckery had to remove ? extends
    private LocalZonePrec(final Map<XtaProcess, Collection<VarDecl<RatType>>> clocksProcessMap) {
        checkNotNull(clocksProcessMap);
        this.clocksProcessMap = ImmutableMap.copyOf(clocksProcessMap);
    }

    public static LocalZonePrec of(final Map<XtaProcess, Collection<VarDecl<RatType>>> clocksProcessMap) {
        return new LocalZonePrec(clocksProcessMap);
    }

    public Collection<VarDecl<RatType>> getVars(XtaProcess proc) {
        return clocksProcessMap.get(proc);
    }

    public Map<XtaProcess, Collection<VarDecl<RatType>>> getMapping() {
        return this.clocksProcessMap;
    }

    @Override
    public String toString() {
        LispStringBuilder tmp = Utils.lispStringBuilder(getClass().getSimpleName());
        for (var mapping : this.clocksProcessMap.entrySet()) {
            tmp.addAll(mapping.getValue());
        }
        return tmp.toString();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        } else if (obj != null && this.getClass() == obj.getClass()) {
            final LocalZonePrec that = (LocalZonePrec) obj;
            return this.getMapping().equals(that.getMapping());
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return 31 * clocksProcessMap.hashCode();
    }

    @Override
    public Collection<VarDecl<?>> getUsedVars() { // This could be way more elegant
        Collection<VarDecl<?>> tmp = Collections.emptySet();

        for (var clocks : this.clocksProcessMap.values()){
            tmp.addAll(clocks.stream().map(ratTypeVarDecl -> (VarDecl<?>) ratTypeVarDecl).collect(Collectors.toSet()));
        }
        return tmp;
    }
}
