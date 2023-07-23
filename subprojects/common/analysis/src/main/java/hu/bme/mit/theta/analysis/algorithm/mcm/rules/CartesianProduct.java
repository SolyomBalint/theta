/*
 *  Copyright 2023 Budapest University of Technology and Economics
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

package hu.bme.mit.theta.analysis.algorithm.mcm.rules;

import hu.bme.mit.theta.analysis.algorithm.mcm.mcm.EncodedRelationWrapper;
import hu.bme.mit.theta.analysis.algorithm.mcm.mcm.EventConstantLookup;
import hu.bme.mit.theta.analysis.algorithm.mcm.mcm.MCMRelation;
import hu.bme.mit.theta.common.TupleN;
import hu.bme.mit.theta.core.decl.ConstDecl;
import hu.bme.mit.theta.core.type.booltype.BoolType;

import java.util.List;

import static hu.bme.mit.theta.core.type.booltype.BoolExprs.And;
import static hu.bme.mit.theta.core.type.booltype.BoolExprs.Iff;

public class CartesianProduct extends BinaryMCMRule {
    public CartesianProduct(MCMRelation e1, MCMRelation e2) {
        super(e1, e2);
    }

    @Override
    public void encodeSingleEvent(int i, int j, List<Integer> idList, ConstDecl<BoolType> constDecl, EventConstantLookup e1, EventConstantLookup e2, EncodedRelationWrapper encodedRelationWrapper) {
        encodedRelationWrapper.getSolver().add(Iff(constDecl.getRef(), And(e1.get(TupleN.of(i)).getRef(), e2.get(TupleN.of(j)).getRef())));
    }

    @Override
    public String toString() {
        return e1.toString() + " X " + e2.toString();
    }
}
