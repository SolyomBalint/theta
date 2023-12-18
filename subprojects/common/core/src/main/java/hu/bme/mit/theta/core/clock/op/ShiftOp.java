/*
 *  Copyright 2017 Budapest University of Technology and Economics
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
package hu.bme.mit.theta.core.clock.op;

import static com.google.common.base.Preconditions.checkNotNull;
import static hu.bme.mit.theta.core.stmt.Stmts.Assign;
import static hu.bme.mit.theta.core.type.rattype.RatExprs.Add;
import static hu.bme.mit.theta.core.type.rattype.RatExprs.Rat;

import java.util.Collection;

import com.google.common.collect.ImmutableSet;

import hu.bme.mit.theta.common.Utils;
import hu.bme.mit.theta.core.decl.VarDecl;
import hu.bme.mit.theta.core.stmt.AssignStmt;
import hu.bme.mit.theta.core.type.clocktype.ClockType;

public final class ShiftOp implements ClockOp {

	private static final int HASH_SEED = 5521;

	private final VarDecl<ClockType> varDecl;
	private final int offset;

	private volatile int hashCode = 0;
	private volatile AssignStmt<ClockType> stmt = null;

	ShiftOp(final VarDecl<ClockType> varDecl, final int offset) {
		this.varDecl = checkNotNull(varDecl);
		this.offset = offset;
	}

	public VarDecl<ClockType> getVar() {
		return varDecl;
	}

	public int getOffset() {
		return offset;
	}

	@Override
	public Collection<VarDecl<ClockType>> getVars() {
		return ImmutableSet.of(varDecl);
	}

	@Override
	public AssignStmt<ClockType> toStmt() {
		AssignStmt<ClockType> result = stmt;
		if (result == null) {
			// TODO
			//result = Assign(varDecl, Add(varDecl.getRef(), Rat(offset, 1)));
			stmt = result;
		}
		return result;
	}

	@Override
	public <P, R> R accept(final ClockOpVisitor<? super P, ? extends R> visitor, final P param) {
		return visitor.visit(this, param);
	}

	@Override
	public int hashCode() {
		int result = hashCode;
		if (result == 0) {
			result = HASH_SEED;
			result = 31 * result + varDecl.hashCode();
			result = 31 * result + offset;
			hashCode = result;
		}
		return result;
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		} else if (obj instanceof ShiftOp) {
			final ShiftOp that = (ShiftOp) obj;
			return this.getVar().equals(that.getVar()) && this.getOffset() == that.getOffset();
		} else {
			return false;
		}
	}

	@Override
	public String toString() {
		return Utils.lispStringBuilder("shift").add(varDecl.getName()).add(offset).toString();
	}

}
