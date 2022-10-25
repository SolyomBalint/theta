/*
 *  Copyright 2022 Budapest University of Technology and Economics
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

package hu.bme.mit.theta.xcfa.passes

import hu.bme.mit.theta.core.decl.VarDecl
import hu.bme.mit.theta.core.stmt.AssignStmt
import hu.bme.mit.theta.core.stmt.HavocStmt
import hu.bme.mit.theta.core.stmt.Stmts.Havoc
import hu.bme.mit.theta.xcfa.collectVars
import hu.bme.mit.theta.xcfa.model.*

/**
 * This pass simplifies assignments from havoc'd intermediate variables.
 * It determines intermediate variables based on their usage patterns:
 *      `havoc x; y := x` matches when y is not used in other contexts.
 * Requires the ProcedureBuilder to be `deterministic` (@see DeterministicPass)
 */

class HavocPromotionAndRange : ProcedurePass {
    override fun run(builder: XcfaProcedureBuilder): XcfaProcedureBuilder {
        checkNotNull(builder.metaData["deterministic"])

        val varEdgeLut = LinkedHashMap<VarDecl<*>, MutableList<XcfaEdge>>()
        builder.getEdges().forEach { it.label.collectVars().forEach { v ->
            varEdgeLut.putIfAbsent(v, ArrayList())
            varEdgeLut[v]!!.add(it)
        } }


        val edges = LinkedHashSet(builder.getEdges())
        for (edge in edges) {
            var candidates = (edge.label as SequenceLabel).labels
                    .mapIndexed { index, it -> Pair(index, it) }
                    .filter {
                        it.second is StmtLabel &&
                        (it.second as StmtLabel).stmt is HavocStmt<*> &&
                        varEdgeLut[((it.second as StmtLabel).stmt as HavocStmt<*>).varDecl]!!.size == 1
                    }
            if(candidates.isNotEmpty()) {
                val labelEdgeLut = LinkedHashMap<VarDecl<*>, MutableList<XcfaLabel>>()
                edge.label.labels.forEach { it.collectVars().forEach { v ->
                    labelEdgeLut.putIfAbsent(v, ArrayList())
                    labelEdgeLut[v]!!.add(it)
                } }
                candidates = candidates.filter {
                        val v = ((it.second as StmtLabel).stmt as HavocStmt<*>).varDecl
                        val labels = labelEdgeLut[v]!!
                        labels.size == 2 &&
                        labels[0] == edge.label.labels[it.first] &&
                        labels[1] == edge.label.labels[it.first + 1] &&
                        labels[1] is StmtLabel && (labels[1] as StmtLabel).stmt is AssignStmt<*> &&
                                ((labels[1] as StmtLabel).stmt as AssignStmt<*>).varDecl == v &&
                                ((labels[1] as StmtLabel).stmt as AssignStmt<*>).expr == v.ref }
                val indices = candidates.map(Pair<Int, XcfaLabel>::first)
                if(indices.isNotEmpty()) {
                    builder.removeEdge(edge)
                    val newLabels = ArrayList<XcfaLabel>()
                    var offset = 0;
                    for ((index, label) in edge.label.labels.withIndex()) {
                        if(index < indices[offset] + offset) newLabels.add(label)
                        else if (index == indices[offset] + offset) {
                            newLabels.add(StmtLabel(Havoc(((edge.label.labels[index+1] as StmtLabel).stmt as AssignStmt<*>).varDecl)))
                        } else if (index == indices[offset] + offset + 1) {
                            offset++
                        } else {
                            error("Should not be here")
                        }
                    }
                    builder.addEdge(edge.withLabel(SequenceLabel(newLabels)))
                }
            }
        }
        return builder
    }
}