package hu.bme.mit.inf.ttmc.constraint.decl.defaults;

import hu.bme.mit.inf.ttmc.constraint.ConstraintManager;
import hu.bme.mit.inf.ttmc.constraint.decl.ParamDecl;
import hu.bme.mit.inf.ttmc.constraint.expr.ParamRefExpr;
import hu.bme.mit.inf.ttmc.constraint.type.Type;

public abstract class AbstractParamDecl<DeclType extends Type> extends AbstractDecl<DeclType>
		implements ParamDecl<DeclType> {

	private static final String DECL_LABEL = "Param";

	private final ConstraintManager manager;

	private volatile ParamRefExpr<DeclType> ref;

	public AbstractParamDecl(final ConstraintManager manager, final String name, final DeclType type) {
		super(name, type);
		this.manager = manager;
	}

	@Override
	public ParamRefExpr<DeclType> getRef() {
		if (ref == null) {
			ref = manager.getExprFactory().Ref(this);
		}

		return ref;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("Param(");
		sb.append(getName());
		sb.append(" : ");
		sb.append(getType());
		sb.append(")");
		return sb.toString();
	}

	@Override
	protected final String getDeclLabel() {
		return DECL_LABEL;
	}

}
