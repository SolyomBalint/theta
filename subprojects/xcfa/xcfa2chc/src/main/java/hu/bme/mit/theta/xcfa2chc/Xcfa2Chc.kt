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

package hu.bme.mit.theta.xcfa2chc

import hu.bme.mit.theta.core.decl.Decls.Param
import hu.bme.mit.theta.core.type.arraytype.ArrayType
import hu.bme.mit.theta.core.type.booltype.BoolExprs.And
import hu.bme.mit.theta.core.type.booltype.BoolExprs.True
import hu.bme.mit.theta.core.type.inttype.IntExprs.Int
import hu.bme.mit.theta.core.utils.ExprUtils
import hu.bme.mit.theta.core.utils.PathUtils
import hu.bme.mit.theta.core.utils.StmtUtils
import hu.bme.mit.theta.core.utils.indexings.VarIndexingFactory
import hu.bme.mit.theta.xcfa.collectVars
import hu.bme.mit.theta.xcfa.model.XcfaProcedure

fun XcfaProcedure.toCHC(): List<Relation> {
    val i2i = ArrayType.of(Int(), Int())

    val vars = edges.flatMap { it.label.collectVars() }.toSet().toList()

    val types = vars.map { it.type }.toTypedArray()
    val oldParams = vars.associateWith { Param("|" + it.name + "|", it.type) }
    val oldParamList = vars.map { oldParams[it]!!.ref }.toTypedArray()
    val newParams = vars.associateWith { Param("|" + it.name + "_new|", it.type) }

    val ufs = locs.associateWith { Relation(it.name, *types) } // br, co, rf, com

    edges.forEach {
        val unfoldResult = StmtUtils.toExpr(it.label.toStmt(), VarIndexingFactory.basicVarIndexing(0))
        val expr = PathUtils.unfold(And(unfoldResult.exprs), VarIndexingFactory.indexing(0))
        // var[0] is oldParam, var[-1]is newParam, everything else is a fresh param
        var cnt = 0
        val consts = ExprUtils.getIndexedConstants(expr).associateWith {
            if(it.index == 0) oldParams[it.varDecl]!!
            else if (it.index == unfoldResult.indexing[it.varDecl]) newParams[it.varDecl]!!
            else Param("__tmp_${cnt++}", it.type)
        }
        val newParamList = vars.map { if(unfoldResult.indexing[it] == 0) oldParams[it]!!.ref else newParams[it]!!.ref }.toTypedArray()
        val paramdExpr = ExprUtils.changeDecls(expr, consts)
        (ufs[it.target]!!)(*newParamList) += (ufs[it.source]!!)(*oldParamList).expr + paramdExpr
    }

    if(errorLoc.isPresent) {
        !(ufs[errorLoc.get()]!!(*oldParamList))
    }

    ufs[initLoc]!!(*oldParamList) += True()

    return ufs.values.toList()
}

fun XcfaProcedure.toSMT2CHC(): String {
    val chc = toCHC()
    val smt2 = chc.toSMT2()
    return smt2
}
