package r.nodes.truffle;

import com.oracle.truffle.nodes.*;
import com.oracle.truffle.runtime.Frame;
import com.oracle.truffle.runtime.ContentStable;
import com.oracle.truffle.runtime.Stable;

import r.*;
import r.data.*;
import r.data.internal.*;
import r.errors.*;
import r.nodes.*;

// FIXME: can we avoid copying in some cases? E.g. when representation of a vector is explicit.
// FIXME: could reduce code size by some refactoring, e.g. subclassing on copiers that use double, int, logical
// FIXME: some of the guards from common "exec" methods could be elided - type checking for RArray and then again in specialized functions for RArray subclasses

public abstract class UpdateVector extends BaseR {

    final RSymbol var;
    @Stable RNode lhs;
    @Stable @ContentStable RNode[] indexes;
    @Stable RNode rhs;
    final boolean subset;

    @Stable RNode assign;  // node which will assign the whole new vector to var
    RAny newVector;

    private static final boolean DEBUG_UP = false;

    UpdateVector(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset) {
        super(ast);
        this.var = var;
        this.lhs = updateParent(lhs);
        this.indexes = updateParent(indexes);
        this.rhs = updateParent(rhs);
        this.subset = subset;

        RNode node = updateParent(new BaseR(ast) {
            @Override
            public final Object execute(RContext context, Frame frame) {
                return newVector;
            }
        });

        this.assign = updateParent(WriteVariable.getUninitialized(ast, var, node));
    }

    enum Failure {
        NOT_ARRAY_BASE,
        NOT_ONE_ELEMENT_INDEX,
        NOT_NUMERIC_INDEX,
        NOT_LOGICAL_INDEX,
        NOT_ARRAY_INDEX,
        NOT_INT_SEQUENCE_INDEX,
        INDEX_OUT_OF_BOUNDS,
        NOT_ONE_ELEMENT_VALUE,
        NOT_ARRAY_VALUE,
        UNEXPECTED_TYPE,
        NOT_SAME_LENGTH,
        MAYBE_VECTOR_UPDATE,
        NOT_INT_DOUBLE_OR_LOGICAL_INDEX,
    }

    @Override
    public final Object execute(RContext context, Frame frame) {
        RAny value = (RAny) rhs.execute(context, frame); // note: order is important
        RAny index = (RAny) indexes[0].execute(context, frame);
        RAny base = (RAny) lhs.execute(context, frame);

        newVector = execute(context, base, index, value);
        assign.execute(context, frame);
        return value;
    }

    abstract RAny execute(RContext context, RAny base, RAny index, RAny value);

    // for a numeric (int, double) scalar index
    //   first installs an uninitialized node
    //   this node rewrites itself to type-specialized nodes for simple assignment, or to a generic node
    //   the specialized nodes can rewrite themselves to the generic node
    //   rewrites to GenericScalarSelection when types change or otherwise needed
    public static class ScalarNumericSelection extends UpdateVector {

        public ScalarNumericSelection(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset) {
            super(ast, var, lhs, indexes, rhs, subset);
        }

        @Override
        public RAny execute(RContext context, RAny base, RAny index, RAny value) {
            if (DEBUG_UP) Utils.debug("update - executing ScalarNumericSelection (uninitialized)");

            try {
                throw new UnexpectedResultException(null);
            } catch (UnexpectedResultException e) {
                Specialized sn = createSimple(base, value);
                if (sn != null) {
                    replace(sn, "specialize ScalarNumericSelection");
                    if (DEBUG_UP) Utils.debug("update - replaced and re-executing with ScalarNumericSelection.Simple");
                    return sn.execute(context, base, index, value);
                } else {
                    sn = createGeneric();
                    replace(sn, "specialize ScalarNumericSelection");
                    if (DEBUG_UP) Utils.debug("update - replaced and re-executing with ScalarNumericSelection.Generic");
                    return sn.execute(context, base, index, value);
                }
            }
        }

        abstract class ValueCopy {
            abstract RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException;
        }

        public Specialized createSimple(RAny baseTemplate, RAny valueTemplate) {
            if (baseTemplate instanceof RInt) {
                if (valueTemplate instanceof ScalarIntImpl) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RInt) || !(value instanceof ScalarIntImpl)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RInt ibase = (RInt) base;
                            int bsize = ibase.size();
                            if (pos < 1 || pos > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int zpos = pos - 1;
                            int[] content = new int[bsize];
                            int i = 0;
                            for (; i < zpos; i++) {
                                content[i] = ibase.getInt(i);
                            }
                            content[i++] = ((ScalarIntImpl) value).getInt();
                            for (; i < bsize; i++) {
                                content[i] = ibase.getInt(i);
                            }
                            return RInt.RIntFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RInt,ScalarInt>");
                }
                if (valueTemplate instanceof ScalarLogicalImpl) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException  {
                            if (!(base instanceof RInt) || !(value instanceof ScalarLogicalImpl)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RInt ibase = (RInt) base;
                            int bsize = ibase.size();
                            if (pos < 1 || pos > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int zpos = pos - 1;
                            int[] content = new int[bsize];
                            int i = 0;
                            for (; i < zpos; i++) {
                                content[i] = ibase.getInt(i);
                            }
                            content[i++] = ((ScalarLogicalImpl) value).getLogical();
                            for (; i < bsize; i++) {
                                content[i] = ibase.getInt(i);
                            }
                            return RInt.RIntFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RInt,ScalarLogical>");
                }
                return null;
            }
            if (baseTemplate instanceof RDouble) {
                if (valueTemplate instanceof ScalarDoubleImpl) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RDouble) || !(value instanceof RDouble)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RDouble dbase = (RDouble) base;
                            int bsize = dbase.size();
                            if (pos < 1 || pos > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int zpos = pos - 1;
                            if (!dbase.isShared()) {
                                dbase.set(zpos, ((RDouble) value).getDouble(0));
                                return dbase;
                            } else {
                                double[] content = new double[bsize];
                                int i = 0;
                                for (; i < zpos; i++) {
                                    content[i] = dbase.getDouble(i);
                                }
                                content[i++] = ((ScalarDoubleImpl) value).getDouble();
                                for (; i < bsize; i++) {
                                    content[i] = dbase.getDouble(i);
                                }
                                return RDouble.RDoubleFactory.getForArray(content);
                            }
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RDouble,ScalarDouble>");
                }
                if (valueTemplate instanceof ScalarIntImpl) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RDouble) || !(value instanceof ScalarIntImpl)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RDouble dbase = (RDouble) base;
                            int bsize = dbase.size();
                            if (pos < 1 || pos > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int zpos = pos - 1;
                            double[] content = new double[bsize];
                            int i = 0;
                            for (; i < zpos; i++) {
                                content[i] = dbase.getDouble(i);
                            }
                            content[i++] = Convert.int2double(((ScalarIntImpl) value).getInt());
                            for (; i < bsize; i++) {
                                content[i] = dbase.getDouble(i);
                            }
                            return RDouble.RDoubleFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RDouble,ScalarInt>");
                }
                if (valueTemplate instanceof ScalarLogicalImpl) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RDouble) || !(value instanceof ScalarLogicalImpl)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RDouble dbase = (RDouble) base;
                            int bsize = dbase.size();
                            if (pos < 1 || pos > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int zpos = pos - 1;
                            double[] content = new double[bsize];
                            int i = 0;
                            for (; i < zpos; i++) {
                                content[i] = dbase.getDouble(i);
                            }
                            content[i++] = Convert.logical2double(((ScalarLogicalImpl) value).getLogical());
                            for (; i < bsize; i++) {
                                content[i] = dbase.getDouble(i);
                            }
                            return RDouble.RDoubleFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RDouble,ScalarLogical>");
                }
                return null;
            }
            if (baseTemplate instanceof RLogical) {
                if (valueTemplate instanceof ScalarLogicalImpl) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RLogical) || !(value instanceof ScalarLogicalImpl)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RLogical lbase = (RLogical) base;
                            int bsize = lbase.size();
                            if (pos < 1 || pos > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int zpos = pos - 1;
                            int[] content = new int[bsize];
                            int i = 0;
                            for (; i < zpos; i++) {
                                content[i] = lbase.getLogical(i);
                            }
                            content[i++] = ((ScalarLogicalImpl) value).getLogical();
                            for (; i < bsize; i++) {
                                content[i] = lbase.getLogical(i);
                            }
                            return RLogical.RLogicalFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RLogical,ScalarLogical>");
                }
            }
            if (baseTemplate instanceof RList && !(valueTemplate instanceof RNull)) {
                ValueCopy cpy = new ValueCopy() {
                    @Override
                    RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException {
                        if (!(base instanceof RList) || (value instanceof RNull)) {
                            throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                        }
                        RList lbase = (RList) base;
                        int bsize = lbase.size();
                        if (pos < 1 || pos > bsize) {
                            throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                        }
                        int zpos = pos - 1;
                        RAny[] content = new RAny[bsize];
                        int i = 0;
                        for (; i < zpos; i++) { // shallow copy
                            content[i] = lbase.getRAnyRef(i);
                        }
                        content[i++] = value;
                        for (; i < bsize; i++) { // shallow copy
                            content[i] = lbase.getRAnyRef(i);
                        }
                        return RList.RListFactory.getForArray(content);
                    }
                };
                return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RList,?>");
            }
            return null;
        }

        // FIXME: the asXXX functions will allocate views, but the value has only a single element, this is an over-kill
        //        probably should create asXXXScalar() casts
        public static RAny genericUpdate(RArray base, int pos, RAny value, boolean subset, ASTNode ast) {
            RArray typedBase;
            RArray typedValue;
            if (base instanceof RList || value instanceof RList) {
                typedBase = base.asList();
                typedValue = value.asList();
            } else if (base instanceof RDouble || value instanceof RDouble) {
                typedBase = base.asDouble();
                typedValue = value.asDouble();
            } else if (base instanceof RInt || value instanceof RInt) {
                typedBase = base.asInt();
                typedValue = value.asInt();
            } else if (base instanceof RLogical || value instanceof RLogical) {
                typedBase = base.asLogical();
                typedValue = value.asLogical();
            } else {
                Utils.nyi("unsupported vector types");
                return null;
            }
            int bsize = base.size();
            if (pos > 0) {
                if (pos <= bsize) {
                    int zpos = pos - 1;
                    RArray res = Utils.createArray(typedBase, bsize);
                    int i = 0;
                    for (; i < zpos; i++) {
                        res.set(i, typedBase.get(i));
                    }
                    res.set(i++, typedValue.get(0));
                    for (; i < bsize; i++) {
                        res.set(i, typedBase.get(i));
                    }
                    return res;
                } else {
                    int zpos = pos - 1;
                    int nsize = zpos + 1;
                    RArray res = Utils.createArray(typedBase, nsize);
                    int i = 0;
                    for (; i < bsize; i++) {
                        res.set(i, typedBase.get(i));
                    }
                    for (; i < zpos; i++) {
                        Utils.setNA(res, i);
                    }
                    res.set(i, typedValue.get(0));
                    return res;
                }
            } else { // pos < 0
                if (pos == RInt.NA) {
                    return base;
                }
                if (!subset) {
                    if (bsize <= 1) {
                        throw RError.getSelectLessThanOne(ast);
                    }
                    if (bsize > 2) {
                        throw RError.getSelectMoreThanOne(ast);
                    }
                    // bsize == 2
                    if (pos != -1 && pos != -2) {
                        throw RError.getSelectMoreThanOne(ast);
                    }
                }
                int keep = -pos - 1;
                RArray res = Utils.createArray(typedBase, bsize);
                int i = 0;
                Object v = typedValue.get(0);
                for (; i < keep; i++) {
                    res.set(i, v);
                }
                res.set(i, typedBase.get(i));
                i++;
                for (; i < bsize; i++) {
                    res.set(i, v);
                }
                return res;
            }
        }

        public Specialized createGeneric() {
            ValueCopy cpy = new ValueCopy() {
                @Override
                RAny copy(RArray base, int pos, RAny value) throws UnexpectedResultException {
                    if (!(value instanceof RArray)) {
                        throw new UnexpectedResultException(Failure.NOT_ARRAY_VALUE);
                    }
                    RArray avalue = (RArray) value;
                    int vsize = avalue.size();
                    if (vsize != 1) {
                        throw new UnexpectedResultException(Failure.NOT_ONE_ELEMENT_VALUE);
                    }
                    return genericUpdate(base, pos, value, subset, ast);
                }
            };
            return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<Generic>");
        }


        class Specialized extends ScalarNumericSelection {
            final ValueCopy copy;
            final String dbg;

            Specialized(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset, ValueCopy copy, String dbg) {
                super(ast, var, lhs, indexes, rhs, subset);
                this.copy = copy;
                this.dbg = dbg;
            }

            @Override
            public RAny execute(RContext context, RAny base, RAny index, RAny value) {
                if (DEBUG_UP) Utils.debug("update - executing ScalarNumericSelection" + dbg);
                try {
                    if (!(base instanceof RArray)) {
                        throw new UnexpectedResultException(Failure.NOT_ARRAY_BASE);
                    }
                    RArray abase = (RArray) base;
                    int pos;
                    if (index instanceof ScalarIntImpl) {
                        pos = ((ScalarIntImpl) index).getInt();
                    } else if (index instanceof ScalarDoubleImpl) {
                        pos = Convert.double2int(((ScalarDoubleImpl) index).getDouble());
                    } else {
                        throw new UnexpectedResultException(null);
                    }
                    return copy.copy(abase, pos, value);

                } catch (UnexpectedResultException e) {
                    Failure f = (Failure) e.getResult();
                    if (f == null) {
                        if ((index instanceof RArray) && (((RArray) index).size() != 1)) {
                            f = Failure.NOT_ONE_ELEMENT_INDEX;
                        } else {
                            f = Failure.NOT_NUMERIC_INDEX;
                        }
                    }
                    if (DEBUG_UP) Utils.debug("update - ScalarNumericSelection" + dbg + " failed: " + f);
                    switch(f) {
                        case INDEX_OUT_OF_BOUNDS:
                        case UNEXPECTED_TYPE:
                            Specialized sn = createGeneric();
                            replace(sn, "specialize ScalarNumericSelection");
                            if (DEBUG_UP) Utils.debug("update - replaced and re-executing with ScalarNumericSelection.Generic");
                            return sn.execute(context, base, index, value);

                        case NOT_ONE_ELEMENT_INDEX:
                            if (!subset) {
                                Subscript s = new Subscript(ast, var, lhs, indexes, rhs, subset);
                                replace(s, "install Subscript from ScalarNumericSelection");
                                if (DEBUG_UP) Utils.debug("update - replaced and re-executing with Subscript");
                                return s.execute(context, base, index, value);
                            }
                            // propagate below

                        default:
                            GenericScalarSelection gs = new GenericScalarSelection(ast, var, lhs, indexes, rhs, subset);
                            replace(gs, "install GenericScalarSelection from ScalarNumericSelection");
                            if (DEBUG_UP) Utils.debug("update - replaced and re-executing with GenericScalarSelection");
                            return gs.execute(context, base, index, value);
                    }
                }
            }
        }
    }

    // any update when the selector is a scalar
    //   includes deletion of list elements (FIXME: perhaps could move that out into a special node?)
    //   rewrites itself if the update is in fact vector-like (subset with logical index, multi-value subset with negative number index)
    //   rewrites for other cases (vector selection)
    //   so the contract is that this can handle any subscript with a single-value index
    public static class GenericScalarSelection extends UpdateVector {

        public GenericScalarSelection(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset) {
            super(ast, var, lhs, indexes, rhs, subset);
        }

        public static RAny deleteElement(RList base, int i, ASTNode ast, boolean subset) {
            int size = base.size();
            if (i > 0) {
                if (i <= size) {
                    // remove element i
                    int zi = i - 1; // zero-based
                    int nsize = size - 1;
                    RAny[] content = new RAny[nsize];
                    int j = 0;
                    for (; j < zi; j++) {  // shallow copy
                        content[j] = base.getRAnyRef(j);
                    }
                    zi++;
                    for (; j < nsize; j++) { // shallow copy
                        content[j] = base.getRAnyRef(zi++);
                    }
                    return RList.RListFactory.getForArray(content);
                } else {
                    return base;
                }
            }
            if (i == 0 || i == RInt.NA) {
                if (subset) {
                    return base;
                } else {
                    throw RError.getSelectLessThanOne(ast);
                }
            }
            // i < 0
            if (!subset) {
                if (size <= 1) {
                    throw RError.getSelectLessThanOne(ast);
                }
                if (size > 2) {
                    throw RError.getSelectMoreThanOne(ast);
                }
                if (i != -1 && i != -2) {
                    throw RError.getSelectMoreThanOne(ast);
                }
            }
            int keep = -i - 1;
            return RList.RListFactory.getScalar(base.getRAnyRef(keep)); // shallow copy
        }

        public static RAny deleteElement(RArray base, RArray index, ASTNode ast, boolean subset) {
            if (!(base instanceof RList)) {
                throw RError.getReplacementZero(ast);
            }
            RList l = (RList) base;
            int i = -1;
            if (index instanceof RInt) {
                i = ((RInt) index).getInt(0);
                return deleteElement(l, i, ast, subset);
            } else if (index instanceof RDouble) {
                i = Convert.double2int(((RDouble) index).getDouble(0));
                return deleteElement(l, i, ast, subset);
            } else if (index instanceof RLogical) {
                i = Convert.logical2int(((RLogical) index).getLogical(0));
                if (subset) {
                    if (i == RLogical.TRUE) {
                        return RList.EMPTY;
                    } else {
                        return base;
                    }
                }
                return deleteElement(l, i, ast, subset);
            } else if (index instanceof RNull) {
                return l;
            } else {
                Utils.nyi("unsupported type");
                return null;
            }
        }

        public static RAny update(RContext context, RArray base, RArray index, RArray value, ASTNode ast, boolean subset) throws UnexpectedResultException {
            int vsize = value.size();
            if (vsize == 0) {
                throw RError.getReplacementZero(ast);
            }
            if (index instanceof RLogical) {
                if (subset) {
                    throw new UnexpectedResultException(Failure.MAYBE_VECTOR_UPDATE);
                }
                if (vsize > 1) {
                    throw RError.getMoreElementsSupplied(ast);
                }
                int l = ((RLogical) index).getLogical(0);
                if (l == RLogical.FALSE) {
                    throw RError.getSelectLessThanOne(ast);
                }
                if (l == RLogical.NA) {
                    throw RError.getSelectMoreThanOne(ast);
                }
                return ScalarNumericSelection.genericUpdate(base, RLogical.TRUE, value, subset, ast);
            }
            int i = -1;
            if (index instanceof RInt) {
                i = ((RInt) index).getInt(0);
            } else if (index instanceof RDouble) {
                i = Convert.double2int(((RDouble) index).getDouble(0));
            } else {
                Utils.nyi("unsupported index type in vector update");
                return null;
            }
            if (i >= 0 || i == RInt.NA || !subset) {
                if (vsize > 1) {
                    if (subset) {
                        context.warning(ast, RError.NOT_MULTIPLE_REPLACEMENT);
                    } else {
                        throw RError.getMoreElementsSupplied(ast);
                    }
                }
                return ScalarNumericSelection.genericUpdate(base, i, value, subset, ast);
            } else {
                // subset with negative index
                if (vsize == 1) {
                    return ScalarNumericSelection.genericUpdate(base, i, value, subset, ast);
                } else {
                    throw new UnexpectedResultException(Failure.MAYBE_VECTOR_UPDATE);
                }
            }
        }

        @Override
        public RAny execute(RContext context, RAny base, RAny index, RAny value) {
            if (DEBUG_UP) Utils.debug("update - executing GenericScalarSelection");
            try {
                if (!(base instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_BASE);
                }
                RArray abase = (RArray) base;
                if (!(index instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_INDEX);
                }
                RArray aindex = (RArray) index;
                int isize = aindex.size();
                if (isize != 1) {
                    throw new UnexpectedResultException(Failure.NOT_ONE_ELEMENT_INDEX);
                }
                if (value instanceof RNull) {
                    return deleteElement(abase, aindex, ast, subset);
                }
                if (!(value instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_VALUE);
                }
                RArray avalue = (RArray) value;
                return update(context, abase, aindex, avalue, ast, subset);

            } catch (UnexpectedResultException e) {
                Failure f = (Failure) e.getResult();
                if (DEBUG_UP) Utils.debug("update - GenericScalarSelection failed: " + f);
                switch (f) {
                    case MAYBE_VECTOR_UPDATE:
                    case NOT_ONE_ELEMENT_INDEX:
                        if (subset) {
                            if (index instanceof IntImpl.RIntSequence) {
                                IntSequenceSelection is = new IntSequenceSelection(ast, var, lhs, indexes, rhs, subset);
                                replace(is, "install IntSequenceSelection from GenericScalarSelection");
                                if (DEBUG_UP) Utils.debug("update - replaced and re-executing with IntSequenceSelection");
                                return is.execute(context, base, index, value);
                            }
                            if (index instanceof RInt || index instanceof RDouble) {
                                NumericSelection ns = new NumericSelection(ast, var, lhs, indexes, rhs, subset);
                                replace(ns, "install NumericSelection from GenericScalarSelection");
                                if (DEBUG_UP) Utils.debug("update - replaced and re-executing with NumericSelection");
                                return ns.execute(context, base, index, value);
                            }
                            if (index instanceof RLogical) {
                                LogicalSelection ls = new LogicalSelection(ast, var, lhs, indexes, rhs, subset);
                                replace(ls, "install LogicalSelection from GenericScalarSelection");
                                if (DEBUG_UP) Utils.debug("update - replaced and re-executing with LogicalSelection");
                                return ls.execute(context, base, index, value);
                            }
                        } else {
                            Subscript s = new Subscript(ast, var, lhs, indexes, rhs, subset);
                            replace(s, "install Subscript from GenericScalarSelection");
                            if (DEBUG_UP) Utils.debug("update - replaced and re-executing with Subscript");
                            return s.execute(context, base, index, value);
                        }
                        // propagate below

                    default:
                        GenericSelection gs = new GenericSelection(ast, var, lhs, indexes, rhs, subset);
                        replace(gs, "install GenericSelection from GenericScalarSelection");
                        if (DEBUG_UP) Utils.debug("update - replaced and re-executing with GenericScalarSelection");
                        return gs.execute(context, base, index, value);
                }
            }
        }
    }

    // for updates where the index is an int sequence
    //   specializes for types (base, value) in simple cases
    //   handles also some simple cases when types change or when type-conversion of base is needed
    //   rewrites itself for more complicated cases
    public static class IntSequenceSelection extends UpdateVector {

        public IntSequenceSelection(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset) {
            super(ast, var, lhs, indexes, rhs, subset);
            Utils.check(subset);
        }

        @Override
        public RAny execute(RContext context, RAny base, RAny index, RAny value) {
            if (DEBUG_UP) Utils.debug("update - executing IntSequenceSelection (uninitialized)");

            try {
                throw new UnexpectedResultException(null);
            } catch (UnexpectedResultException e) {
                Specialized sn = createSimple(base, value);
                if (sn != null) {
                    replace(sn, "specialize IntSequenceSelection");
                    if (DEBUG_UP) Utils.debug("update - replaced and re-executing with IntSequenceSelection.Simple");
                    return sn.execute(context, base, index, value);
                } else {
                    sn = createExtended();
                    replace(sn, "specialize IntSequenceSelection");
                    if (DEBUG_UP) Utils.debug("update - replaced and re-executing with IntSequenceSelection.Extended");
                    return sn.execute(context, base, index, value);
                }
            }
        }

        abstract class ValueCopy {
            abstract RAny copy(RArray base, IntImpl.RIntSequence index, RAny value) throws UnexpectedResultException;
        }

        // specialized for type combinations (base vector, value written)
        public Specialized createSimple(RAny baseTemplate, RAny valueTemplate) {
            if (baseTemplate instanceof RList) {
                if (valueTemplate instanceof RList || valueTemplate instanceof RDouble || valueTemplate instanceof RInt || valueTemplate instanceof RLogical) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, IntImpl.RIntSequence index, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RList)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RList typedBase = (RList) base;
                            RList typedValue;
                            if (value instanceof RList) {
                                typedValue = (RList) value;
                            } else if (value instanceof RDouble || value instanceof RInt || value instanceof RLogical) {
                                typedValue = value.asList();
                            } else {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            int bsize = base.size();
                            int imin = index.min();
                            int imax = index.max();
                            if (imin < 1 || imax > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            imin--;
                            imax--;  // convert to 0-based
                            int isize = index.size();
                            int vsize = typedValue.size();
                            if (isize != vsize) {
                                throw new UnexpectedResultException(Failure.NOT_SAME_LENGTH);
                            }
                            RAny[] content = new RAny[bsize];
                            int i = 0;
                            for (; i < imin; i++) { // shallow copy
                                content[i] = typedBase.getRAnyRef(i);
                            }
                            i = index.from() - 1;  // -1 for 0-based
                            int step = index.step();
                            int astep;
                            int delta;
                            if (step > 0) {
                                astep = step;
                                delta = 1;
                            } else {
                                astep = -step;
                                delta = -1;
                            }
                            for (int steps = 0; steps < isize; steps++) { // shallow copy
                                content[i] = typedValue.getRAnyRef(steps);
                                i += delta;
                                for (int j = 1; j < astep; j++) { // shallow copy
                                    content[i] = typedBase.getRAnyRef(i);
                                    i += delta;
                                }
                            }
                            for (i = imax + 1; i < bsize; i++) { // shallow copy
                                content[i] = typedBase.getRAnyRef(i);
                            }
                            return RList.RListFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RList,RList|RDouble|RInt|RLogical>");
                }
                return null;
            }
            if (baseTemplate instanceof RDouble) {
                if (valueTemplate instanceof RDouble || valueTemplate instanceof RLogical || valueTemplate instanceof RInt) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, IntImpl.RIntSequence index, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RDouble)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RDouble typedBase = (RDouble) base;
                            RDouble typedValue;
                            if (value instanceof RDouble) {
                                typedValue = (RDouble) value;
                            } else if (value instanceof RInt || value instanceof RLogical) {
                                typedValue = value.asDouble();
                            } else {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            int bsize = base.size();
                            int imin = index.min();
                            int imax = index.max();
                            if (imin < 1 || imax > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            imin--;
                            imax--;  // convert to 0-based
                            int isize = index.size();
                            int vsize = typedValue.size();
                            if (isize != vsize) {
                                throw new UnexpectedResultException(Failure.NOT_SAME_LENGTH);
                            }
                            double[] content = new double[bsize];
                            int i = 0;
                            for (; i < imin; i++) {
                                content[i] = typedBase.getDouble(i);
                            }
                            i = index.from() - 1;  // -1 for 0-based
                            int step = index.step();
                            int astep;
                            int delta;
                            if (step > 0) {
                                astep = step;
                                delta = 1;
                            } else {
                                astep = -step;
                                delta = -1;
                            }
                            for (int steps = 0; steps < isize; steps++) {
                                content[i] = typedValue.getDouble(steps);
                                i += delta;
                                for (int j = 1; j < astep; j++) {
                                    content[i] = typedBase.getDouble(i);
                                    i += delta;
                                }
                            }
                            for (i = imax + 1; i < bsize; i++) {
                                content[i] = typedBase.getDouble(i);
                            }
                            return RDouble.RDoubleFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RDouble,RDouble|RInt|RLogical>");
                }
                return null;
            }
            if (baseTemplate instanceof RInt) {
                if (valueTemplate instanceof RInt || valueTemplate instanceof RLogical) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, IntImpl.RIntSequence index, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RInt)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RInt typedBase = (RInt) base;
                            RInt typedValue;
                            if (value instanceof RInt) {
                                typedValue = (RInt) value;
                            } else if (value instanceof RLogical) {
                                typedValue = value.asInt();
                            } else {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            int bsize = base.size();
                            int imin = index.min();
                            int imax = index.max();
                            if (imin < 1 || imax > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            imin--;
                            imax--;  // convert to 0-based
                            int isize = index.size();
                            int vsize = typedValue.size();
                            if (isize != vsize) {
                                throw new UnexpectedResultException(Failure.NOT_SAME_LENGTH);
                            }
                            int[] content = new int[bsize];
                            int i = 0;
                            for (; i < imin; i++) {
                                content[i] = typedBase.getInt(i);
                            }
                            i = index.from() - 1;  // -1 for 0-based
                            int step = index.step();
                            int astep;
                            int delta;
                            if (step > 0) {
                                astep = step;
                                delta = 1;
                            } else {
                                astep = -step;
                                delta = -1;
                            }
                            for (int steps = 0; steps < isize; steps++) {
                                content[i] = typedValue.getInt(steps);
                                i += delta;
                                for (int j = 1; j < astep; j++) {
                                    content[i] = typedBase.getInt(i);
                                    i += delta;
                                }
                            }
                            for (i = imax + 1; i < bsize; i++) {
                                content[i] = typedBase.getInt(i);
                            }
                            return RInt.RIntFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RInt,RInt|RLogical>");
                }
                return null;
            }
            if (baseTemplate instanceof RLogical) {
                if (valueTemplate instanceof RLogical) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, IntImpl.RIntSequence index, RAny value) throws UnexpectedResultException {
                            if (!(base instanceof RLogical && value instanceof RLogical)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RLogical typedBase = (RLogical) base;
                            RLogical typedValue = (RLogical) value;
                            int bsize = base.size();
                            int imin = index.min();
                            int imax = index.max();
                            if (imin < 1 || imax > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            imin--;
                            imax--;  // convert to 0-based
                            int isize = index.size();
                            int vsize = typedValue.size();
                            if (isize != vsize) {
                                throw new UnexpectedResultException(Failure.NOT_SAME_LENGTH);
                            }
                            int[] content = new int[bsize];
                            int i = 0;
                            for (; i < imin; i++) {
                                content[i] = typedBase.getLogical(i);
                            }
                            i = index.from() - 1;  // -1 for 0-based
                            int step = index.step();
                            int astep;
                            int delta;
                            if (step > 0) {
                                astep = step;
                                delta = 1;
                            } else {
                                astep = -step;
                                delta = -1;
                            }
                            for (int steps = 0; steps < isize; steps++) {
                                content[i] = typedValue.getLogical(steps);
                                i += delta;
                                for (int j = 1; j < astep; j++) {
                                    content[i] = typedBase.getLogical(i);
                                    i += delta;
                                }
                            }
                            for (i = imax + 1; i < bsize; i++) {
                                content[i] = typedBase.getLogical(i);
                            }
                            return RLogical.RLogicalFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RInt,RInt|RLogical>");
                }
                return null;
            }
            return null;
        }

        // handles type conversion of base
        public Specialized createExtended() {
            ValueCopy cpy = new ValueCopy() {
                @Override
                RAny copy(RArray base, IntImpl.RIntSequence index, RAny value) throws UnexpectedResultException {
                    RArray typedBase;
                    RArray typedValue;
                    if (base instanceof RList || value instanceof RList) {
                        typedBase = base.asList();
                        typedValue = value.asList();
                    } else if (base instanceof RDouble || value instanceof RDouble) {
                        typedBase = base.asDouble();
                        typedValue = value.asDouble();
                    } else if (base instanceof RInt || value instanceof RInt) {
                        typedBase = base.asInt();
                        typedValue = value.asInt();
                    } else if (base instanceof RLogical || value instanceof RLogical) {
                        typedBase = base.asLogical();
                        typedValue = value.asLogical();
                    } else {
                        Utils.nyi("unsupported vector types");
                        return null;
                    }
                    int bsize = base.size();
                    int imin = index.min();
                    int imax = index.max();
                    if (imin < 1 || imax > bsize) {
                        throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                    }
                    imin--;
                    imax--;  // convert to 0-based
                    int isize = index.size();
                    int vsize = typedValue.size();
                    if (isize != vsize) {
                        throw new UnexpectedResultException(Failure.NOT_SAME_LENGTH);
                    }
                    RArray res = Utils.createArray(typedBase, bsize);
                    int i = 0;
                    for (; i < imin; i++) {
                        res.set(i, typedBase.get(i));
                    }
                    i = index.from() - 1;  // -1 for 0-based
                    int step = index.step();
                    int astep;
                    int delta;
                    if (step > 0) {
                        astep = step;
                        delta = 1;
                    } else {
                        astep = -step;
                        delta = -1;
                    }
                    for (int steps = 0; steps < isize; steps++) {
                        res.set(i, typedValue.get(steps));
                        i += delta;
                        for (int j = 1; j < astep; j++) {
                            res.set(i,  typedBase.get(i));
                            i += delta;
                        }
                    }
                    for (i = imax + 1; i < bsize; i++) {
                        res.set(i, typedBase.get(i));
                    }
                    return res;
                }
            };
            return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<Extended>");
        }


        class Specialized extends IntSequenceSelection {
            final ValueCopy copy;
            final String dbg;

            Specialized(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset, ValueCopy copy, String dbg) {
                super(ast, var, lhs, indexes, rhs, subset);
                this.copy = copy;
                this.dbg = dbg;
            }

            @Override
            public RAny execute(RContext context, RAny base, RAny index, RAny value) {
                if (DEBUG_UP) Utils.debug("update - executing IntSequenceSelection" + dbg);
                try {
                    if (!(base instanceof RArray)) {
                        throw new UnexpectedResultException(Failure.NOT_ARRAY_BASE);
                    }
                    RArray abase = (RArray) base;
                    if (!(value instanceof RArray)) {
                        throw new UnexpectedResultException(Failure.NOT_ARRAY_VALUE);
                    }
                    RArray avalue = (RArray) value;
                    if (!(index instanceof IntImpl.RIntSequence)) {
                        throw new UnexpectedResultException(Failure.NOT_INT_SEQUENCE_INDEX);
                    }
                    return copy.copy(abase, (IntImpl.RIntSequence) index, avalue);

                } catch (UnexpectedResultException e) {
                    Failure f = (Failure) e.getResult();
                    if (DEBUG_UP) Utils.debug("update - IntSequenceSelection" + dbg + " failed: " + f);
                    switch(f) {
                        case UNEXPECTED_TYPE:
                            Specialized sn = createExtended();
                            replace(sn, "specialize IntSequenceSelection");
                            if (DEBUG_UP) Utils.debug("update - replaced and re-executing with IntSequenceSelection.Extended");
                            return sn.execute(context, base, index, value);

                        default:
                            NumericSelection ns = new NumericSelection(ast, var, lhs, indexes, rhs, subset);
                            replace(ns, "install NumericSelection from IntSequenceSelection");
                            if (DEBUG_UP) Utils.debug("update - replaced and re-executing with NumericSelection");
                            return ns.execute(context, base, index, value);
                    }
                }
            }
        }
    }

    // for updates where the index is a numeric (int, double) vector
    public static class NumericSelection extends UpdateVector {

        public NumericSelection(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset) {
            super(ast, var, lhs, indexes, rhs, subset);
            Utils.check(subset);
        }

        public static RArray deleteElements(RList base, RInt index, ASTNode ast, boolean subset) {
            Utils.check(subset);
            boolean hasNegative = false;
            boolean hasPositive = false;
            boolean hasNA = false;
            int bsize = base.size();
            int isize = index.size();
            boolean[] selected = new boolean[bsize];
            int maxIndex = 0;
            int ntrue = 0;

            for (int i = 0; i < isize; i++) {
                int v = index.getInt(i);
                if (v > maxIndex) {
                    maxIndex = v;
                }
                if (v == RInt.NA) {
                    hasNA = true;
                    continue;
                }
                if (v == 0) {
                    continue;
                }
                int vi;
                if (v > 0) {
                    hasPositive = true;
                    vi = v - 1;
                } else {
                    hasNegative = true;
                    vi = -v - 1;
                }
                if (vi < selected.length) {
                    if (!selected[vi]) {
                        ntrue++;
                        selected[vi] = true;
                    }
                }
            }
            if (!hasNegative) {
                int nullsToAdd = 0;
                if (maxIndex > (bsize + 1)) {
                    // there were indexes "above" the base vector, but perhaps not all were mentioned
                    // for all non-mentioned we have to add NULL at the end of the new list
                    final int aboveSize = maxIndex - bsize;
                    boolean[] aboveSelected = new boolean[aboveSize];
                    int natrue = 0;

                    for (int i = 0; i < isize; i++) {
                        int v = index.getInt(i);
                        if (v > bsize) { // note RInt.NA < 0, bsize >= 0
                            int vi = v - bsize - 1;
                            if (!aboveSelected[vi]) {
                                aboveSelected[vi] = true;
                                natrue++;
                            }
                        }
                    }
                    nullsToAdd = aboveSize - natrue;
                }
                int nsize = (bsize - ntrue) + nullsToAdd;
                RAny[] content = new RAny[nsize];
                int j = 0;
                for (int i = 0; i < bsize; i++) {
                    if (!selected[i]) { // shallow copy
                        content[j++] = base.getRAnyRef(i);
                    }
                }
                for (int i = 0; i < nullsToAdd; i++) {
                    content[j++] = RList.NULL;
                }
                return RList.RListFactory.getForArray(content);
            } else {
                // hasNegative == true
                if (hasPositive || hasNA) {
                    throw RError.getOnlyZeroMixed(ast);
                }
                int nsize = ntrue;
                RAny[] content = new RAny[nsize];
                int j = 0;
                for (int i = 0; i < bsize; i++) {
                    if (selected[i]) { // shallow copy
                        content[j++] = base.getRAnyRef(i);
                    }
                }
                return RList.RListFactory.getForArray(content);
            }
        }

        public static RArray genericUpdate(RArray base, RInt index, RArray value, RContext context, ASTNode ast, boolean subset) {
            Utils.check(subset);
            RArray typedBase;
            RArray typedValue;
            if (base instanceof RList && value instanceof RNull) {
                return deleteElements((RList) base, index, ast, subset);
            } else if (base instanceof RList || value instanceof RList) {
                typedBase = base.asList();
                typedValue = value.asList();
            } else if (base instanceof RDouble || value instanceof RDouble) {
                typedBase = base.asDouble();
                typedValue = value.asDouble();
            } else if (base instanceof RInt || value instanceof RInt) {
                typedBase = base.asInt();
                typedValue = value.asInt();
            } else if (base instanceof RLogical || value instanceof RLogical) {
                typedBase = base.asLogical();
                typedValue = value.asLogical();
            } else {
                Utils.nyi("unsupported vector types");
                return null;
            }

            boolean hasNegative = false;
            boolean hasPositive = false;
            boolean hasNA = false;
            int bsize = typedBase.size();
            int isize = index.size();
            boolean[] omit = null;
            int maxIndex = 0;

            for (int i = 0; i < isize; i++) {
                int v = index.getInt(i);
                if (v > maxIndex) {
                    maxIndex = v;
                }
                if (v == RInt.NA) {
                    hasNA = true;
                    continue;
                }
                if (v > 0) {
                    hasPositive = true;
                    continue;
                }
                if (v < 0) {
                    if (!hasNegative) {
                        hasNegative = true;
                        omit = new boolean[bsize];
                    }
                    int vi = -v - 1;
                    if (vi < omit.length) {
                        if (!omit[vi]) {
                            omit[vi] = true;
                        }
                    }
                }
            }
            int vsize = typedValue.size();
            if (!hasNegative) {
                int nsize = maxIndex;
                if (nsize < bsize) {
                    nsize = bsize;
                }
                RArray res = Utils.createArray(typedBase, nsize);
                // FIXME: this may lead to unnecessary computation and copying if the base is a complex view
                int i = 0;
                for (; i < bsize; i++) {
                    res.set(i, typedBase.get(i));
                }
                for (; i < nsize; i++) {
                    Utils.setNA(res, i);
                }
                int j = 0;
                for (i = 0; i < isize; i++) {
                    int v = index.getInt(i);
                    if (v != 0 && v != RInt.NA) {
                        res.set(v - 1, typedValue.get(j++));
                        if (j == vsize) {
                            j = 0;
                        }
                    }
                }
                if (j != 0) {
                    context.warning(ast, RError.NOT_MULTIPLE_REPLACEMENT);
                }
                return res;
            } else {
                // hasNegative == true
                if (hasPositive || hasNA) {
                    throw RError.getOnlyZeroMixed(ast);
                }
                RArray res = Utils.createArray(typedBase, bsize);
                int j = 0;
                for (int i = 0; i < bsize; i++) {
                    if (omit[i]) {
                        res.set(i, typedBase.get(i));
                    } else {
                        res.set(i,  typedValue.get(j++));
                        if (j == vsize) {
                            j = 0;
                        }
                    }
                }
                return res;
            }
        }

        @Override
        public RAny execute(RContext context, RAny base, RAny index, RAny value) {
            if (DEBUG_UP) Utils.debug("update - executing NumericSelection");
            try {
                if (!(base instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_BASE);
                }
                RArray abase = (RArray) base;
                if (!(value instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_VALUE);
                }
                RArray avalue = (RArray) value;
                RInt iindex;
                if (index instanceof RInt) {
                    iindex = (RInt) index;
                } else if (index instanceof RDouble) {
                    iindex = index.asInt();
                } else {
                    throw new UnexpectedResultException(Failure.NOT_NUMERIC_INDEX);
                }
                return genericUpdate(abase, iindex, avalue, context, ast, subset);
            } catch (UnexpectedResultException e) {
                Failure f = (Failure) e.getResult();
                if (DEBUG_UP) Utils.debug("update - NumericSelection failed: " + f);
                GenericSelection gs = new GenericSelection(ast, var, lhs, indexes, rhs, subset);
                replace(gs, "install GenericSelection from NumericSelection");
                if (DEBUG_UP) Utils.debug("update - replaced and re-executing with GenericSelection");
                return gs.execute(context, base, index, value);
            }
        }
    }

    // for updates where the index is a logical sequence
    //   specializes for types (base, value) in simple cases
    //   handles also corner cases and when the type of the base changes
    public static class LogicalSelection extends UpdateVector {

        public LogicalSelection(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset) {
            super(ast, var, lhs, indexes, rhs, subset);
            Utils.check(subset);
        }

        @Override
        public RAny execute(RContext context, RAny base, RAny index, RAny value) {
            if (DEBUG_UP) Utils.debug("update - executing LogicalSelection (uninitialized)");
            try {
                throw new UnexpectedResultException(null);
            } catch (UnexpectedResultException e) {
                Specialized sn = createSimple(base, value);
                if (sn != null) {
                    replace(sn, "specialize LogicalSelection");
                    if (DEBUG_UP) Utils.debug("update - replaced and re-executing with LogicalSelection.Simple");
                    return sn.execute(context, base, index, value);
                } else {
                    sn = createGeneric();
                    replace(sn, "specialize LogicalSelection");
                    if (DEBUG_UP) Utils.debug("update - replaced and re-executing with LogicalSelection.Generic");
                    return sn.execute(context, base, index, value);
                }
            }
        }

        abstract class ValueCopy {
            abstract RAny copy(RArray base, RLogical index, RAny value, RContext context) throws UnexpectedResultException;
        }

        public Specialized createSimple(RAny baseTemplate, RAny valueTemplate) {
            if (baseTemplate instanceof RList) {
                if (valueTemplate instanceof RList || valueTemplate instanceof RDouble || valueTemplate instanceof RLogical || valueTemplate instanceof RInt) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, RLogical index, RAny value, RContext context) throws UnexpectedResultException {
                            if (!(base instanceof RList)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RList typedBase = (RList) base;
                            RList typedValue;
                            if (value instanceof RList) {
                                typedValue = (RList) value;
                            } else if (value instanceof RDouble || value instanceof RInt || value instanceof RLogical) {
                                typedValue = value.asList();
                            } else {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            int bsize = base.size();
                            int isize = index.size();
                            if (isize > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int vsize = typedValue.size();
                            RAny[] content = new RAny[bsize];
                            int ii = 0;
                            int vi = 0;
                            boolean hasNA = false;
                            for (int bi = 0; bi < bsize; bi++) {
                                int v = index.getLogical(ii);
                                ii++;
                                if (ii == isize) {
                                    ii = 0;
                                }
                                if (v == RLogical.TRUE) { // shallow copy
                                    content[bi] = typedValue.getRAnyRef(vi);
                                    vi++;
                                    if (vi == vsize) {
                                        vi = 0;
                                    }
                                    continue;
                                }
                                if (v == RLogical.NA) {
                                    hasNA = true;
                                }
                                content[bi] = typedBase.getRAnyRef(bi); // shallow copy
                            }
                            if (hasNA && vsize >= 2) {
                                throw RError.getNASubscripted(ast);
                            }
                            if (vi != 0) {
                                context.warning(ast, RError.NOT_MULTIPLE_REPLACEMENT);
                            }
                            return RList.RListFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RDouble,RList|RDouble|RInt|RLogical>");
                }
                return null;
            }
            if (baseTemplate instanceof RDouble) {
                if (valueTemplate instanceof RDouble || valueTemplate instanceof RLogical || valueTemplate instanceof RInt) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, RLogical index, RAny value, RContext context) throws UnexpectedResultException {
                            if (!(base instanceof RDouble)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RDouble typedBase = (RDouble) base;
                            RDouble typedValue;
                            if (value instanceof RDouble) {
                                typedValue = (RDouble) value;
                            } else if (value instanceof RInt || value instanceof RLogical) {
                                typedValue = value.asDouble();
                            } else {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            int bsize = base.size();
                            int isize = index.size();
                            if (isize > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int vsize = typedValue.size();
                            double[] content = new double[bsize];
                            int ii = 0;
                            int vi = 0;
                            boolean hasNA = false;
                            for (int bi = 0; bi < bsize; bi++) {
                                int v = index.getLogical(ii);
                                ii++;
                                if (ii == isize) {
                                    ii = 0;
                                }
                                if (v == RLogical.TRUE) {
                                    content[bi] = typedValue.getDouble(vi);
                                    vi++;
                                    if (vi == vsize) {
                                        vi = 0;
                                    }
                                    continue;
                                }
                                if (v == RLogical.NA) {
                                    hasNA = true;
                                }
                                content[bi] = typedBase.getDouble(bi);
                            }
                            if (hasNA && vsize >= 2) {
                                throw RError.getNASubscripted(ast);
                            }
                            if (vi != 0) {
                                context.warning(ast, RError.NOT_MULTIPLE_REPLACEMENT);
                            }
                            return RDouble.RDoubleFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RDouble,RDouble|RInt|RLogical>");
                }
                return null;
            }
            if (baseTemplate instanceof RInt) {
                if (valueTemplate instanceof RLogical || valueTemplate instanceof RInt) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, RLogical index, RAny value, RContext context) throws UnexpectedResultException {
                            if (!(base instanceof RInt)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RInt typedBase = (RInt) base;
                            RInt typedValue;
                            if (value instanceof RInt) {
                                typedValue = (RInt) value;
                            } else if (value instanceof RLogical) {
                                typedValue = value.asInt();
                            } else {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            int bsize = base.size();
                            int isize = index.size();
                            if (isize > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int vsize = typedValue.size();
                            int[] content = new int[bsize];
                            int ii = 0;
                            int vi = 0;
                            boolean hasNA = false;
                            for (int bi = 0; bi < bsize; bi++) {
                                int v = index.getLogical(ii);
                                ii++;
                                if (ii == isize) {
                                    ii = 0;
                                }
                                if (v == RLogical.TRUE) {
                                    content[bi] = typedValue.getInt(vi);
                                    vi++;
                                    if (vi == vsize) {
                                        vi = 0;
                                    }
                                    continue;
                                }
                                if (v == RLogical.NA) {
                                    hasNA = true;
                                }
                                content[bi] = typedBase.getInt(bi);
                            }
                            if (hasNA && vsize >= 2) {
                                throw RError.getNASubscripted(ast);
                            }
                            if (vi != 0) {
                                context.warning(ast, RError.NOT_MULTIPLE_REPLACEMENT);
                            }
                            return RInt.RIntFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RInt,RInt|RLogical>");
                }
                return null;
            }
            if (baseTemplate instanceof RLogical) {
                if (valueTemplate instanceof RLogical) {
                    ValueCopy cpy = new ValueCopy() {
                        @Override
                        RAny copy(RArray base, RLogical index, RAny value, RContext context) throws UnexpectedResultException {
                            if (!(base instanceof RLogical)) {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            RLogical typedBase = (RLogical) base;
                            RLogical typedValue;
                            if (value instanceof RLogical) {
                                typedValue = (RLogical) value;
                            } else {
                                throw new UnexpectedResultException(Failure.UNEXPECTED_TYPE);
                            }
                            int bsize = base.size();
                            int isize = index.size();
                            if (isize > bsize) {
                                throw new UnexpectedResultException(Failure.INDEX_OUT_OF_BOUNDS);
                            }
                            int vsize = typedValue.size();
                            int[] content = new int[bsize];
                            int ii = 0;
                            int vi = 0;
                            boolean hasNA = false;
                            for (int bi = 0; bi < bsize; bi++) {
                                int v = index.getLogical(ii);
                                ii++;
                                if (ii == isize) {
                                    ii = 0;
                                }
                                if (v == RLogical.TRUE) {
                                    content[bi] = typedValue.getLogical(vi);
                                    vi++;
                                    if (vi == vsize) {
                                        vi = 0;
                                    }
                                    continue;
                                }
                                if (v == RLogical.NA) {
                                    hasNA = true;
                                }
                                content[bi] = typedBase.getLogical(bi);
                            }
                            if (hasNA && vsize >= 2) {
                                throw RError.getNASubscripted(ast);
                            }
                            if (vi != 0) {
                                context.warning(ast, RError.NOT_MULTIPLE_REPLACEMENT);
                            }
                            return RLogical.RLogicalFactory.getForArray(content);
                        }
                    };
                    return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<RLogical,RLogical>");
                }
                return null;
            }
            return null;
        }

        public static RAny deleteElements(RList base, RLogical index, ASTNode ast) {
            int bsize = base.size();
            int isize = index.size();

            if (isize == bsize) {
                int ntrue = RLogical.RLogicalUtils.truesInRange(index, 0, isize);
                int nsize = bsize - ntrue;
                RAny[] content = new RAny[nsize];
                int j = 0;
                for (int i = 0; i < bsize; i++) {
                    int l = index.getLogical(i);
                    if (l != RLogical.TRUE) { // shallow copy
                        content[j++] = base.getRAnyRef(i);
                    }
                }
                return RList.RListFactory.getForArray(content);
            }
            if (isize > bsize) {
                // for each "non-TRUE" element above base vector size, have to add NULL to the vector
                int ntrue = RLogical.RLogicalUtils.truesInRange(index, 0, bsize);
                int natrue = RLogical.RLogicalUtils.truesInRange(index, bsize, isize);
                int nullsToAdd = isize - bsize - natrue;
                int nsize = (bsize - ntrue) + nullsToAdd;
                RAny[] content = new RAny[nsize];
                int j = 0;
                for (int i = 0; i < bsize; i++) {
                    int l = index.getLogical(i);
                    if (l != RLogical.TRUE) { // shallow copy
                        content[j++] = base.getRAnyRef(i);
                    }
                }
                for (int i = 0; i < nullsToAdd; i++) {
                    content[j++] = RList.NULL;
                }
                return RList.RListFactory.getForArray(content);
            }
            // isize < bsize
            if (isize == 0) {
                return base;
            }
            int rep = bsize / isize;
            int lsize = bsize - rep * isize;
            int ntrue = RLogical.RLogicalUtils.truesInRange(index, 0, isize);
            int nltrue = RLogical.RLogicalUtils.truesInRange(index, 0, lsize); // TRUEs in the last cycle of index over base

            int nsize = bsize - (ntrue * rep + nltrue);
            RAny[] content = new RAny[nsize];
            int ii = 0;
            int ci = 0;
            for (int bi = 0; bi < bsize; bi++) {
                int l = index.getLogical(ii++);
                if (ii == isize) {
                    ii = 0;
                }
                if (l != RLogical.TRUE) { // shallow copy
                    content[ci++] = base.getRAnyRef(bi);
                }
            }
            return RList.RListFactory.getForArray(content);
        }

        public static RAny genericUpdate(RArray base, RLogical index, RAny value, RContext context, ASTNode ast) {
            RArray typedBase;
            RArray typedValue;
            if (base instanceof RList && value instanceof RNull) {
                return deleteElements((RList) base, index, ast);
            } else if (base instanceof RList || value instanceof RList) {
                typedBase = base.asList();
                typedValue = value.asList();
            } else if (base instanceof RDouble || value instanceof RDouble) {
                typedBase = base.asDouble();
                typedValue = value.asDouble();
            } else if (base instanceof RInt || value instanceof RInt) {
                typedBase = base.asInt();
                typedValue = value.asInt();
            } else if (base instanceof RLogical || value instanceof RLogical) {
                typedBase = base.asLogical();
                typedValue = value.asLogical();
            } else {
                Utils.nyi("unsupported vector types");
                return null;
            }
            int bsize = base.size();
            int isize = index.size();
            int vsize = typedValue.size();
            int nsize = (bsize > isize) ? bsize : isize;

            RArray res = Utils.createArray(typedBase, nsize);
            int ii = 0;
            int vi = 0;
            int ni = 0;
            boolean hasNA = false;
            for (; ni < bsize; ni++) {
                int v = index.getLogical(ii);
                ii++;
                if (ii == isize) {
                    ii = 0;
                }
                if (v == RLogical.TRUE) {
                    res.set(ni, typedValue.get(vi));
                    vi++;
                    if (vi == vsize) {
                        vi = 0;
                    }
                    continue;
                }
                if (v == RLogical.NA) {
                    hasNA = true;
                }
                res.set(ni, typedBase.get(ni));
            }
            for (; ni < nsize; ni++) {
                Utils.setNA(res, ni);
            }
            if (hasNA && vsize >= 2) {
                throw RError.getNASubscripted(ast);
            }
            if (vi != 0) {
                context.warning(ast, RError.NOT_MULTIPLE_REPLACEMENT);
            }
            return res;
        }

        public Specialized createGeneric() {
            ValueCopy cpy = new ValueCopy() {
                @Override
                RAny copy(RArray base, RLogical index, RAny value, RContext context) {
                    return genericUpdate(base, index, value, context, ast);
                }
            };
            return new Specialized(ast, var, lhs, indexes, rhs, subset, cpy, "<Generic>");
        }

        class Specialized extends LogicalSelection {
            final ValueCopy copy;
            final String dbg;

            Specialized(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset, ValueCopy copy, String dbg) {
                super(ast, var, lhs, indexes, rhs, subset);
                this.copy = copy;
                this.dbg = dbg;
            }

            @Override
            public RAny execute(RContext context, RAny base, RAny index, RAny value) {
                if (DEBUG_UP) Utils.debug("update - executing LogicalSelection" + dbg);
                try {
                    if (!(base instanceof RArray)) {
                        throw new UnexpectedResultException(Failure.NOT_ARRAY_BASE);
                    }
                    RArray abase = (RArray) base;
                    if (!(value instanceof RArray)) {
                        throw new UnexpectedResultException(Failure.NOT_ARRAY_VALUE);
                    }
                    RArray avalue = (RArray) value;
                    if (!(index instanceof RLogical)) {
                        throw new UnexpectedResultException(Failure.NOT_LOGICAL_INDEX);
                    }
                    RLogical lindex = (RLogical) index;
                    return copy.copy(abase, lindex, avalue, context);
                } catch (UnexpectedResultException e) {
                    Failure f = (Failure) e.getResult();
                    if (DEBUG_UP) Utils.debug("update - LogicalSelection" + dbg + " failed: " + f);
                    switch(f) {
                        case INDEX_OUT_OF_BOUNDS:
                        case UNEXPECTED_TYPE:
                            Specialized sn = createGeneric();
                            replace(sn, "specialize LogicalSelection");
                            if (DEBUG_UP) Utils.debug("update - replaced and re-executing with LogicalSelection.Generic");
                            return sn.execute(context, base, index, value);

                        default:
                            GenericSelection gs = new GenericSelection(ast, var, lhs, indexes, rhs, subset);
                            replace(gs, "install GenericSelection from LogicalSelection");
                            if (DEBUG_UP) Utils.debug("update - replaced and re-executing with GenericSelection");
                            return gs.execute(context, base, index, value);
                    }
                }
            }
        }
    }

    // when the index is a vector of integers (selection by index)
    //   and the base can be recursive
    //   and the mode is subscript ([[.]])
    public static class Subscript extends UpdateVector {
        public Subscript(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset) {
            super(ast, var, lhs, indexes, rhs, subset);
            Utils.check(!subset);
        }

        public static RAny executeSubscript(RInt index, RArray base, RArray value, ASTNode ast) {
            final int isize = index.size();
            if (isize == 0) {
                throw RError.getSelectLessThanOne(ast);
            }
            int i = 0;
            RAny b = base;
            RAny res = null;
            RList parent = null;
            int parentIndex = -1;
            if (isize > 1) {
                for (; i < isize - 1; i++) {  // shallow copy
                    if (!(b instanceof RList)) {
                        throw RError.getMoreElementsSupplied(ast);
                    }
                    RList l = (RList) b;
                    int indexv = index.getInt(i);
                    int bsize = l.size();
                    int isel = ReadVector.Subscript.convertDereferencingIndex(indexv, i, bsize, ast);

                    RAny[] content = new RAny[bsize];
                    int k = 0;
                    int j = 0;
                    for (; j < isel; j++) { // shallow copy
                        content[k++] = l.getRAnyRef(j);
                    }
                    j++; // skip
                    k++;
                    for (; j < bsize; j++) { // shallow copy
                        content[k++] = l.getRAnyRef(j);
                    }
                    RList newList = RList.RListFactory.getForArray(content);
                    if (parent != null) {
                        parent.set(parentIndex, newList);
                    } else {
                        res = newList;
                    }
                    parent = newList;
                    parentIndex = isel;
                    b = l.getRAnyRef(isel); // shallow copy
                }
            }
            // selection at the last level
            int indexv = index.getInt(i);
            if (!(b instanceof RArray)) {
                Utils.nyi("unuspported base type");
            }
            RArray a = (RArray) b;
            if (value instanceof RNull) {
                if (a instanceof RList) {
                    b = GenericScalarSelection.deleteElement((RList) a, indexv, ast, false);
                } else {
                    throw RError.getMoreElementsSupplied(ast);
                }
            } else {
                if (value.size() > 1) {
                    throw RError.getMoreElementsSupplied(ast);
                } else {
                    b = ScalarNumericSelection.genericUpdate(a, indexv, value, false, ast);
                }
            }
            if (parent == null) {
                return b;
            } else {
                parent.set(parentIndex, b);
                return res;
            }
        }

        @Override
        public RAny execute(RContext context, RAny base, RAny index, RAny value) {
            if (DEBUG_UP) Utils.debug("update - executing Subscript");
            try {
                if (!(base instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_BASE);
                }
                RArray abase = (RArray) base;
                RInt iindex;
                if (index instanceof RInt) {
                    iindex = (RInt) index;
                } else if (index instanceof RDouble || index instanceof RLogical) {
                    iindex = index.asInt();
                } else {
                    throw new UnexpectedResultException(Failure.NOT_INT_DOUBLE_OR_LOGICAL_INDEX);
                }
                if (!(value instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_VALUE);
                }
                RArray avalue = (RArray) value;
                return executeSubscript(iindex, abase, avalue, ast);

            } catch (UnexpectedResultException e) {
                Failure f = (Failure) e.getResult();
                if (DEBUG_UP) Utils.debug("update - Subscript failed: " + f);
                GenericSelection gs = new GenericSelection(ast, var, lhs, indexes, rhs, subset);
                  // rewriting itself only to handle the error, there is no way to recover
                replace(gs, "install GenericSelection from Subscript");
                if (DEBUG_UP) Utils.debug("update - replaced and re-executing with GenericSelection");
                return gs.execute(context, base, index, value);
            }
        }
    }

    // handles any update, won't rewrite itself
    public static class GenericSelection extends UpdateVector {

        public GenericSelection(ASTNode ast, RSymbol var, RNode lhs, RNode[] indexes, RNode rhs, boolean subset) {
            super(ast, var, lhs, indexes, rhs, subset);
        }

        @Override
        public RAny execute(RContext context, RAny base, RAny index, RAny value) {
            if (DEBUG_UP) Utils.debug("update - executing GenericSelection");
            try {
                if (!(base instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_BASE);
                }
                RArray abase = (RArray) base;
                if (!(index instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_INDEX);
                }
                RArray aindex = (RArray) index;
                int isize = aindex.size();
                if (value instanceof RNull) {
                    return GenericScalarSelection.deleteElement(abase, aindex, ast, subset);
                }
                if (!(value instanceof RArray)) {
                    throw new UnexpectedResultException(Failure.NOT_ARRAY_VALUE);
                }
                RArray avalue = (RArray) value;
                if (!subset && isize == 1) {
                    return GenericScalarSelection.update(context, abase, aindex, avalue, ast, subset);
                }

                if (subset) {
                    if (aindex instanceof RDouble || aindex instanceof RInt) {
                        return NumericSelection.genericUpdate(abase, aindex.asInt(), avalue, context, ast, subset);
                    } else if (aindex instanceof RLogical) {
                        return LogicalSelection.genericUpdate(abase, index.asLogical(), avalue, context, ast);
                    } else {
                        Utils.nyi("unsupported update");
                        return null;
                    }
                } else {
                    return Subscript.executeSubscript(aindex.asInt(), abase, avalue, ast);
                }
            } catch (UnexpectedResultException e) {
                Failure f = (Failure) e.getResult();
                if (DEBUG_UP) Utils.debug("update - GenericSelection failed: " + f);
                Utils.nyi("unsupported update");
                return null;
            }
        }
    }
}
