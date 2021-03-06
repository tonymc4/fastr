package r.nodes.truffle;


import java.util.*;

import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

import r.*;
import r.data.*;
import r.data.RAny.Attributes;
import r.data.RArray.Names;
import r.data.RComplex.RComplexUtils;
import r.data.internal.*;
import r.data.internal.IntImpl.RIntSequence;
import r.errors.*;
import r.gnur.*;
import r.nodes.*;

// FIXME: the design may not be good for complex numbers (too much common computation for real, imaginary parts)

// FIXME: the complex arithmetic differs for scalars/non-scalars (NA semantics - which part is NA), though
// this should not be visible to the end-user

// TODO: the complex arithmetics (here and in some math functions, which are builtins), is not IEEE 754 compliant
// GNU-R depends on the C99 compiler to implement the low-level operations correctly.
// Note that getting the Infs and NaNs right in complex arithmetics is far from trivial. See e.g. libgcc, divdc3, muldc3

// TODO: perhaps could be smarter about the arithIsNA checks with doubles; one can rely on that if (with most math operations), if there is
// na NA on input, there will be a double NaN on output (either NA or R'NaN). So instead of checking both inputs of a binary operation for NA,
// one could just check the result for NaN, and only in the positive case check the operands for NA...

public class Arithmetic extends BaseR {

    private static final boolean IN_PLACE = true;

    @Child RNode left;
    @Child RNode right;
    final ValueArithmetic arit;

    private static final boolean EAGER = false;
    private static final boolean LIMIT_VIEW_DEPTH = true;
    private static final int MAX_VIEW_DEPTH = 5;

    private static final boolean DEBUG_AR = false;
    private static final boolean EAGER_COMPLEX = true;

    public Arithmetic(ASTNode ast, RNode left, RNode right, ValueArithmetic arit) {
        super(ast);
        this.left = adoptChild(left);
        this.right = adoptChild(right);
        this.arit = arit;
    }

    public static boolean returnsDouble(ValueArithmetic arit) {
//        return (arit == POW || arit == DIV);
        return arit.returnsDouble();
    }

    @Override
    public Object execute(Frame frame) {

//        // an experiment
//        if (!arit.returnsDouble()) {
//            return replace(new ScalarIntSpecialized(ast, left, right, arit)).execute(frame);
//        }

        Object lexpr = left.execute(frame);
        Object rexpr = right.execute(frame);
        return execute(lexpr, rexpr);
    }

    public Object execute(Object lexpr, Object rexpr) {
        try {
            throw new UnexpectedResultException(null);
        } catch (UnexpectedResultException e) {

            if (left instanceof Constant || right instanceof Constant) {
                SpecializedConst sc = SpecializedConst.createSpecialized((RAny) lexpr, (RAny) rexpr, ast, left, right, arit);
                replace(sc, "install Specialized from Uninitialized");
                if (DEBUG_AR) Utils.debug("Installed " + sc.dbg + " for expressions " + lexpr + "(" + ((RAny) lexpr).pretty() + ") and " + rexpr + "(" + ((RAny) rexpr).pretty() + ")");
                return sc.execute(lexpr, rexpr);
            } else {
                Specialized sn = Specialized.createSpecialized((RAny) lexpr, (RAny) rexpr, ast, left, right, arit);
                replace(sn, "install Specialized from Uninitialized");
                if (DEBUG_AR) Utils.debug("Installed " + sn.dbg);
                return sn.execute(lexpr, rexpr);
            }
        }
    }

    public enum FailedSpecialization {
        FIXED_TYPE,
        MULTI_TYPE
    }

    static class Specialized extends Arithmetic {
        final String dbg;
        final Calculator calc;

        public Specialized(ASTNode ast, RNode left, RNode right, ValueArithmetic arit, Calculator calc, String dbg) {
            super(ast, left, right, arit);
            this.dbg = dbg;
            this.calc = calc;
        }

        public abstract static class Calculator {
            public abstract Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException;
        }

        public static Specialized createSpecialized(RAny leftTemplate, RAny rightTemplate, final ASTNode ast, RNode left, RNode right, final ValueArithmetic arit) {
            if (leftTemplate instanceof ScalarComplexImpl && rightTemplate instanceof ScalarComplexImpl) {
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarComplexImpl && rexpr instanceof ScalarComplexImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        ScalarComplexImpl lcomp = (ScalarComplexImpl) lexpr;
                        double lreal = lcomp.getReal();
                        double limag = lcomp.getImag();
                        ScalarComplexImpl rcomp = (ScalarComplexImpl) rexpr;
                        double rreal = rcomp.getReal();
                        double rimag = rcomp.getImag();
                        if (!RComplex.RComplexUtils.arithEitherIsNA(lreal, limag) && !RComplex.RComplexUtils.arithEitherIsNA(rreal, rimag)) {
                            return RComplex.RComplexFactory.getScalar(arit.opReal(ast, lreal, limag, rreal, rimag), arit.opImag(ast, lreal, limag, rreal, rimag));
                        } else {
                            return RComplex.BOXED_NA;
                        }
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<ScalarComplex, ScalarComplex>");
            }
            if (leftTemplate instanceof ScalarComplexImpl && rightTemplate instanceof ScalarDoubleImpl) {
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarComplexImpl && rexpr instanceof ScalarDoubleImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        ScalarComplexImpl lcomp = (ScalarComplexImpl) lexpr;
                        double lreal = lcomp.getReal();
                        double limag = lcomp.getImag();
                        double rreal = ((ScalarDoubleImpl) rexpr).getDouble();
                        if (!RComplex.RComplexUtils.arithEitherIsNA(lreal, limag) && !RDouble.RDoubleUtils.arithIsNA(rreal)) {
                            return RComplex.RComplexFactory.getScalar(arit.opReal(ast, lreal, limag, rreal, 0), arit.opImag(ast, lreal, limag, rreal, 0));
                        } else {
                            return RComplex.BOXED_NA;
                        }
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<ScalarComplex, ScalarDouble>");
            }
            if (leftTemplate instanceof ScalarDoubleImpl && rightTemplate instanceof ScalarComplexImpl) {
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarDoubleImpl && rexpr instanceof ScalarComplexImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        double lreal = ((ScalarDoubleImpl) lexpr).getDouble();
                        ScalarComplexImpl rcomp = (ScalarComplexImpl) rexpr;
                        double rreal = rcomp.getReal();
                        double rimag = rcomp.getImag();
                        if (!RDouble.RDoubleUtils.arithIsNA(lreal) && !RComplex.RComplexUtils.arithEitherIsNA(rreal, rimag)) {
                            return RComplex.RComplexFactory.getScalar(arit.opReal(ast, lreal, 0, rreal, rimag), arit.opImag(ast, lreal, 0, rreal, rimag));
                        } else {
                            return RComplex.BOXED_NA;
                        }
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<ScalarDouble, ScalarComplex>");
            }
            if (leftTemplate instanceof ScalarDoubleImpl && rightTemplate instanceof ScalarDoubleImpl) {
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarDoubleImpl && rexpr instanceof ScalarDoubleImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        double ldbl = ((ScalarDoubleImpl) lexpr).getDouble();
                        double rdbl = ((ScalarDoubleImpl) rexpr).getDouble();
                        if (RDouble.RDoubleUtils.arithIsNA(ldbl) || RDouble.RDoubleUtils.arithIsNA(rdbl)) {
                            return RDouble.BOXED_NA;
                        }
                        return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rdbl));
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<ScalarDouble, ScalarDouble>");
            }
            if (leftTemplate instanceof ScalarDoubleImpl && rightTemplate instanceof ScalarIntImpl) {
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarDoubleImpl && rexpr instanceof ScalarIntImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        double ldbl = ((ScalarDoubleImpl) lexpr).getDouble();
                        int rint = ((ScalarIntImpl) rexpr).getInt();
                        if (RDouble.RDoubleUtils.arithIsNA(ldbl) || rint == RInt.NA) {
                            return RDouble.BOXED_NA;
                        }
                        return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rint));
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<ScalarDouble, ScalarInt>");
            }
            if (leftTemplate instanceof ScalarIntImpl && rightTemplate instanceof ScalarDoubleImpl) {
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarIntImpl && rexpr instanceof ScalarDoubleImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        int lint = ((ScalarIntImpl) lexpr).getInt();
                        double rdbl = ((ScalarDoubleImpl) rexpr).getDouble();
                        if (lint == RInt.NA || RDouble.RDoubleUtils.arithIsNA(rdbl)) {
                            return RDouble.BOXED_NA;
                        }
                        return RDouble.RDoubleFactory.getScalar(arit.op(ast, lint, rdbl));
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<ScalarInt, ScalarDouble>");
            }
            if (leftTemplate instanceof ScalarIntImpl && rightTemplate instanceof ScalarIntImpl) {
                if (returnsDouble(arit)) {
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (!(lexpr instanceof ScalarIntImpl && rexpr instanceof ScalarIntImpl)) {
                                throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                            }
                            int lint = ((ScalarIntImpl) lexpr).getInt();
                            int rint = ((ScalarIntImpl) rexpr).getInt();
                            if (lint == RInt.NA || rint == RInt.NA) {
                                return RDouble.BOXED_NA;
                            }
                            return RDouble.RDoubleFactory.getScalar(arit.op(ast, (double) lint, (double) rint));
                        }
                    };
                    return new Specialized(ast, left, right, arit, c, "<ScalarInt, ScalarInt>");
                } else {
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (!(lexpr instanceof ScalarIntImpl && rexpr instanceof ScalarIntImpl)) {
                                throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                            }
                            int lint = ((ScalarIntImpl) lexpr).getInt();
                            int rint = ((ScalarIntImpl) rexpr).getInt();
                            if (lint == RInt.NA || rint == RInt.NA) {
                                return RInt.BOXED_NA;
                            }
                            return RInt.RIntFactory.getScalar(arit.opWarnOverflow(ast, lint, rint));
                        }
                    };
                    return new Specialized(ast, left, right, arit, c, "<ScalarInt, ScalarInt>");
                }
            }
            if (leftTemplate instanceof RDouble && rightTemplate instanceof RDouble) {
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof RDouble && rexpr instanceof RDouble)) {
                            throw new UnexpectedResultException(null);
                        }
                        return doubleBinary((RDouble)lexpr,  (RDouble)rexpr, arit, ast);
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<RDouble, RDouble>");
            }
            if (leftTemplate instanceof RInt && rightTemplate instanceof RInt && !returnsDouble(arit)) {
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof RInt && rexpr instanceof RInt)) {
                            throw new UnexpectedResultException(null);
                        }
                        return intBinary((RInt)lexpr,  (RInt)rexpr, arit, ast);
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<RInt, RInt>");
            }
            return createGeneric(ast, left, right, arit);
        }

        public static Specialized createSpecializedMultiType(RAny leftTemplate, RAny rightTemplate, final ASTNode ast, RNode left, RNode right, final ValueArithmetic arit) {
            if ((leftTemplate instanceof ScalarIntImpl || leftTemplate instanceof ScalarDoubleImpl) &&
               (rightTemplate instanceof ScalarIntImpl || rightTemplate instanceof ScalarDoubleImpl)) {

                final boolean alwaysDouble = returnsDouble(arit);
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (lexpr instanceof ScalarDoubleImpl) {
                            double ldbl = ((ScalarDoubleImpl) lexpr).getDouble();
                            boolean leftIsNA = RDouble.RDoubleUtils.arithIsNA(ldbl);
                            if (rexpr instanceof ScalarDoubleImpl) {
                                double rdbl = ((ScalarDoubleImpl) rexpr).getDouble();
                                if (leftIsNA || RDouble.RDoubleUtils.arithIsNA(rdbl)) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rdbl));
                            }
                            if (rexpr instanceof ScalarIntImpl) {
                                int rint = ((ScalarIntImpl) rexpr).getInt();
                                if (leftIsNA || rint == RInt.NA) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rint));
                            }
                        } else if (lexpr instanceof ScalarIntImpl) {
                            int lint = ((ScalarIntImpl) lexpr).getInt();
                            boolean leftIsNA = lint == RInt.NA;
                            if (rexpr instanceof ScalarDoubleImpl) {
                                double rdbl = ((ScalarDoubleImpl) rexpr).getDouble();
                                if (leftIsNA || RDouble.RDoubleUtils.arithIsNA(rdbl)) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, lint, rdbl));
                            }
                            if (rexpr instanceof ScalarIntImpl) {
                                int rint = ((ScalarIntImpl) rexpr).getInt();
                                boolean arithIsNA = leftIsNA || rint == RInt.NA;
                                if (alwaysDouble) {
                                    if (arithIsNA) {
                                        return RDouble.BOXED_NA;
                                    }
                                    return RDouble.RDoubleFactory.getScalar(arit.op(ast, (double) lint, (double) rint));
                                } else {
                                    if (arithIsNA) {
                                        return RInt.BOXED_NA;
                                    }
                                    return RInt.RIntFactory.getScalar(arit.opWarnOverflow(ast, lint, rint));
                                }
                            }
                        }
                        throw new UnexpectedResultException(FailedSpecialization.MULTI_TYPE);
                    }
                };
                return new Specialized(ast, left, right, arit, c, "<ScalarDouble|Int, ScalarDouble|Int>");
            }
            return null;
        }

        public static Specialized createGeneric(final ASTNode ast, RNode left, RNode right, final ValueArithmetic arit) {
            Calculator c;
            final boolean returnsDouble = returnsDouble(arit);
            c = new Calculator() {
                @Override
                public Object calc(Object lexpr, Object rexpr) {
                    // TODO: re-visit this, the error semantics with non-numeric types is very likely wrong
                    if (lexpr instanceof RComplex || rexpr instanceof RComplex) {
                        RComplex lcmp = ((RAny)lexpr).asComplex();
                        RComplex rcmp = ((RAny)rexpr).asComplex();
                        return ComplexView.create(lcmp, rcmp, arit, ast);
                    }
                    if (returnsDouble) {
                        RDouble ldbl = ((RAny)lexpr).asDouble();
                        RDouble rdbl = ((RAny)rexpr).asDouble();
                        return doubleBinary(ldbl, rdbl, arit, ast);
                    }
                    if (lexpr instanceof RDouble) {
                        RDouble ldbl = (RDouble) lexpr;
                        if (rexpr instanceof RDouble) {
                            return doubleBinary(ldbl, (RDouble) rexpr, arit, ast);
                        } else if (rexpr instanceof RInt) {
                            return doubleBinary(ldbl, (RInt) rexpr, arit, ast);
                        } else {
                            return doubleBinary(ldbl, ((RAny) rexpr).asDouble(), arit, ast);
                        }
                    }
                    if (rexpr instanceof RDouble) {
                        RDouble rdbl = (RDouble) rexpr;
                        if (lexpr instanceof RInt) {
                            return doubleBinary((RInt) lexpr, rdbl, arit, ast);
                        } else {
                            return doubleBinary(((RAny) lexpr).asDouble(), rdbl, arit, ast);
                        }
                    }
                    if (lexpr instanceof RInt || rexpr instanceof RInt || lexpr instanceof RLogical || rexpr instanceof RLogical) { // FIXME: this check should be simpler
                        RInt lint = ((RAny) lexpr).asInt();
                        RInt rint = ((RAny) rexpr).asInt();
                        return intBinary(lint, rint, arit, ast);
                    }
                    throw RError.getNonNumericBinary(ast);
                }
            };
            return new Specialized(ast, left, right, arit, c, "<Generic, Generic>");
        }

        @Override
        public Object execute(Frame frame) {
            Object lexpr = left.execute(frame);
            Object rexpr = right.execute(frame);
            return execute(lexpr, rexpr);
        }

        @Override
        public final Object execute(Object lexpr, Object rexpr) {
            try {
                return calc.calc(lexpr, rexpr);
            } catch (UnexpectedResultException e) {
                FailedSpecialization f = (FailedSpecialization) e.getResult();
                if (f == FailedSpecialization.FIXED_TYPE) {
                    Specialized sn = createSpecializedMultiType((RAny) lexpr, (RAny) rexpr, ast, left, right, arit);
                    if (sn != null) {
                        replace(sn, "install SpecializedMultiType from Specialized");
                        return sn.execute(lexpr, rexpr);
                    }
                }
                Specialized gn = createGeneric(ast, left, right, arit);
                replace(gn, "install Specialized<Generic, Generic> from Specialized");
                return gn.execute(lexpr, rexpr);
            }
        }
    }

//    // just an experiment for now
//    static class ScalarIntSpecialized extends BaseR {
//        @Child RNode left;
//        @Child RNode right;
//        final ValueArithmetic arit;
//
//        public ScalarIntSpecialized(ASTNode ast, RNode left, RNode right, ValueArithmetic arit) {
//            super(ast);
//            this.left = adoptChild(left);
//            this.right = adoptChild(right);
//            this.arit = arit;
//            assert Utils.check(!arit.returnsDouble());
//        }
//
//        private Object recover(Object lobj, Object robj) {
//            RAny lexpr = (RAny) lobj;
//            RAny rexpr = (RAny) robj;
//
//            Arithmetic an = new Arithmetic(ast, left, right, arit);
//            replace(an);
//            return an.execute(lexpr, rexpr);
//
////            Specialized sn = Specialized.createSpecializedMultiType(lexpr, rexpr, ast, left, right, arit);
////            if (sn != null) {
////                replace(sn, "install SpecializedMultiType from ScalarIntSpecialized");
////                return sn.execute(lexpr, rexpr);
////            }
////            Specialized gn = Specialized.createGeneric(ast, left, right, arit);
////            replace(gn, "install Specialized<Generic, Generic> from ScalarIntSpecialized");
////            return gn.execute(lexpr, rexpr);
//        }
//
//        @Override
//        public int executeScalarInteger(Frame frame) throws UnexpectedResultException {
//            int lint;
//            try {
//                lint = left.executeScalarInteger(frame);
//            } catch (UnexpectedResultException e) {
//                throw new UnexpectedResultException(recover(e.getResult(), right.execute(frame)));
//            }
//            int rint;
//            try {
//                rint = right.executeScalarInteger(frame);
//            } catch (UnexpectedResultException e) {
//                throw new UnexpectedResultException(recover(left.execute(frame), e.getResult()));
//            }
//            if (lint == RInt.NA || rint == RInt.NA) {
//                return RInt.NA;
//            }
//            return arit.opWarnOverflow(ast, lint, rint);
//        }
//
//        @Override
//        public Object execute(Frame frame) {
//            try {
//                return RInt.RIntFactory.getScalar(executeScalarInteger(frame)); // does the rewriting
//            } catch (UnexpectedResultException e) {
//                return e.getResult();
//            }
//        }
//    }


    static class SpecializedConst extends Arithmetic {
        final String dbg;
        final Calculator calc;

        public SpecializedConst(ASTNode ast, RNode left, RNode right, ValueArithmetic arit, Calculator calc, String dbg) {
            super(ast, left, right, arit);
            this.dbg = dbg;
            this.calc = calc;
        }

        public abstract static class Calculator {
            public abstract Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException;
        }

        public static SpecializedConst createSpecialized(RAny leftTemplate, RAny rightTemplate, final ASTNode ast, RNode left, RNode right, final ValueArithmetic arit) {
            boolean leftConst = left instanceof Constant;
            boolean rightConst = right instanceof Constant;
            // non-const is complex
            if (leftConst && (rightTemplate instanceof ScalarComplexImpl) &&
               (leftTemplate instanceof ScalarComplexImpl || leftTemplate instanceof ScalarDoubleImpl || leftTemplate instanceof ScalarIntImpl || leftTemplate instanceof ScalarLogicalImpl)) {
                RComplex lcmp = leftTemplate.asComplex();
                final double lreal = lcmp.getReal(0);
                final double limag =  lcmp.getImag(0);
                final boolean isLeftNA = RComplex.RComplexUtils.arithEitherIsNA(lreal, limag);
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(rexpr instanceof ScalarComplexImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        ScalarComplexImpl rcmp = (ScalarComplexImpl) rexpr;
                        double rreal = rcmp.getReal();
                        double rimag = rcmp.getImag();
                        if (isLeftNA || RComplex.RComplexUtils.arithEitherIsNA(rreal, rimag)) {
                            return RComplex.BOXED_NA;
                        }
                        return RComplex.RComplexFactory.getScalar(arit.opReal(ast, lreal, limag, rreal, rimag), arit.opImag(ast, lreal, limag, rreal, rimag));
                    }
                };
                return createLeftConst(ast, left, right, arit, c, "<ConstScalarNumber, ScalarComplex>");
            }
            if (rightConst && (leftTemplate instanceof ScalarComplexImpl) &&
                (rightTemplate instanceof ScalarComplexImpl || rightTemplate instanceof ScalarDoubleImpl || rightTemplate instanceof ScalarIntImpl || rightTemplate instanceof ScalarLogicalImpl)) {
                 RComplex rcmp = rightTemplate.asComplex();
                 final double rreal = rcmp.getReal(0);
                 final double rimag =  rcmp.getImag(0);
                 final boolean isRightNA = RComplex.RComplexUtils.arithEitherIsNA(rreal, rimag);
                 Calculator c = new Calculator() {
                     @Override
                     public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                         if (!(lexpr instanceof ScalarComplexImpl)) {
                             throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                         }
                         ScalarComplexImpl lcmp = (ScalarComplexImpl) lexpr;
                         double lreal = lcmp.getReal();
                         double limag = lcmp.getImag();
                         if (isRightNA || RComplex.RComplexUtils.arithEitherIsNA(lreal, limag)) {
                             return RComplex.BOXED_NA;
                         }
                         return RComplex.RComplexFactory.getScalar(arit.opReal(ast, lreal, limag, rreal, rimag), arit.opImag(ast, lreal, limag, rreal, rimag));
                     }
                 };
                 return createRightConst(ast, left, right, arit, c, "<ScalarComplex, ConstScalarNumber>");
            }
            // non-const is double and const is complex
            if (leftConst && (rightTemplate instanceof ScalarDoubleImpl) && (leftTemplate instanceof ScalarComplexImpl)) {
                 ScalarComplexImpl lcmp = (ScalarComplexImpl) leftTemplate;
                 final double lreal = lcmp.getReal(0);
                 final double limag =  lcmp.getImag(0);
                 final boolean isLeftNA = RComplex.RComplexUtils.arithEitherIsNA(lreal, limag);
                 Calculator c = new Calculator() {
                     @Override
                     public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                         if (!(rexpr instanceof ScalarDoubleImpl)) {
                             throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                         }
                         double rreal = ((ScalarDoubleImpl) rexpr).getDouble();
                         if (isLeftNA || RDouble.RDoubleUtils.isNAorNaN(rreal)) { // NOTE: not arithIsNA !
                             return RComplex.BOXED_NA;
                         }
                         return RComplex.RComplexFactory.getScalar(arit.opReal(ast, lreal, limag, rreal, 0), arit.opImag(ast, lreal, limag, rreal, 0));
                     }
                 };
                 return createLeftConst(ast, left, right, arit, c, "<ConstScalarComplex, ScalarDouble>");
            }
            if (rightConst && (leftTemplate instanceof ScalarDoubleImpl) && (rightTemplate instanceof ScalarComplexImpl)) {
                ScalarComplexImpl rcmp = (ScalarComplexImpl) rightTemplate;
                final double rreal = rcmp.getReal(0);
                final double rimag =  rcmp.getImag(0);
                final boolean isRightNA = RComplex.RComplexUtils.arithEitherIsNA(rreal, rimag);
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarDoubleImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        double lreal = ((ScalarDoubleImpl) lexpr).getDouble();
                        if (isRightNA || RDouble.RDoubleUtils.isNAorNaN(lreal)) { // NOTE: not arithIsNA !
                            return RComplex.BOXED_NA;
                        }
                        return RComplex.RComplexFactory.getScalar(arit.opReal(ast, lreal, 0, rreal, rimag), arit.opImag(ast, lreal, 0, rreal, rimag));
                    }
                };
                return createRightConst(ast, left, right, arit, c, "<ScalarDouble, ConstScalarComplex>");
           }
            // non-const is double
            if (leftConst && (rightTemplate instanceof ScalarDoubleImpl) && (leftTemplate instanceof ScalarDoubleImpl || leftTemplate instanceof ScalarIntImpl || leftTemplate instanceof ScalarLogicalImpl)) {
                final double ldbl = (leftTemplate.asDouble()).getDouble(0);
                final boolean isLeftNA = RDouble.RDoubleUtils.arithIsNA(ldbl);
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(rexpr instanceof ScalarDoubleImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        double rdbl = ((ScalarDoubleImpl) rexpr).getDouble();
                        if (isLeftNA || RDouble.RDoubleUtils.arithIsNA(rdbl)) {
                            return RDouble.BOXED_NA;
                        }
                        return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rdbl));
                    }
                };
                return createLeftConst(ast, left, right, arit, c, "<ConstScalarNon-Complex, ScalarDouble>");
            }
            if (rightConst && (leftTemplate instanceof ScalarDoubleImpl) && (rightTemplate instanceof ScalarDoubleImpl || rightTemplate instanceof ScalarIntImpl || rightTemplate instanceof ScalarLogicalImpl)) {
                final double rdbl = (rightTemplate.asDouble()).getDouble(0);
                final boolean isRightNA = RDouble.RDoubleUtils.arithIsNA(rdbl);
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarDoubleImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        double ldbl = ((ScalarDoubleImpl) lexpr).getDouble();
                        if (isRightNA || RDouble.RDoubleUtils.arithIsNA(ldbl)) {
                            return RDouble.BOXED_NA;
                        }
                        return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rdbl));
                    }
                };
                return createRightConst(ast, left, right, arit, c, "<ScalarDouble, ConstScalarNon-Complex>");
            }
            // non-const is int and const is double
            // FIXME: handle also logical?
            if (leftConst && (leftTemplate instanceof ScalarDoubleImpl) && (rightTemplate instanceof ScalarIntImpl)) {
                final double ldbl = (leftTemplate.asDouble()).getDouble(0);
                final boolean isLeftNA = RDouble.RDoubleUtils.arithIsNA(ldbl);
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(rexpr instanceof ScalarIntImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        int rint = ((ScalarIntImpl) rexpr).getInt();
                        if (isLeftNA || rint == RInt.NA) {
                            return RDouble.BOXED_NA;
                        }
                        return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rint));
                    }
                };
                return createLeftConst(ast, left, right, arit, c, "<ConstScalarDouble, ScalarInt>");
            }
            if (rightConst && (rightTemplate instanceof ScalarDoubleImpl) && (leftTemplate instanceof ScalarIntImpl)) {
                final double rdbl = (rightTemplate.asDouble()).getDouble(0);
                final boolean isRightNA = RDouble.RDoubleUtils.arithIsNA(rdbl);
                Calculator c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                        if (!(lexpr instanceof ScalarIntImpl)) {
                            throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                        }
                        int lint = ((ScalarIntImpl) lexpr).getInt();
                        if (isRightNA || lint == RInt.NA) {
                            return RDouble.BOXED_NA;
                        }
                        return RDouble.RDoubleFactory.getScalar(arit.op(ast, lint, rdbl));
                    }
                };
                return createRightConst(ast, left, right, arit, c, "<ScalarInt, ConstScalarDouble>");
            }
            // non-const is int and const is int or logical
            if (leftConst && (leftTemplate instanceof ScalarIntImpl || leftTemplate instanceof ScalarLogicalImpl) && (rightTemplate instanceof ScalarIntImpl)) {
                final int lint = (leftTemplate.asInt()).getInt(0);
                final boolean isLeftNA = (lint == RInt.NA);
                if (returnsDouble(arit)) {
                    final double ldbl = lint;
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (!(rexpr instanceof ScalarIntImpl)) {
                                throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                            }
                            int rint = ((ScalarIntImpl) rexpr).getInt();
                            if (isLeftNA || rint == RInt.NA) {
                                return RDouble.BOXED_NA;
                            }
                            return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, (double) rint));
                        }
                    };
                    return createLeftConst(ast, left, right, arit, c, "<ConstScalarInt, ScalarInt>");
                } else {
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (!(rexpr instanceof ScalarIntImpl)) {
                                throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                            }
                            int rint = ((ScalarIntImpl) rexpr).getInt();
                            if (isLeftNA || rint == RInt.NA) {
                                return RInt.BOXED_NA;
                            }
                            return RInt.RIntFactory.getScalar(arit.opWarnOverflow(ast, lint, rint));
                        }
                    };
                    return createLeftConst(ast, left, right, arit, c, "<ConstScalarInt, ScalarInt>");
                }
            }
            if (rightConst && (rightTemplate instanceof ScalarIntImpl || rightTemplate instanceof ScalarLogicalImpl) && (leftTemplate instanceof ScalarIntImpl)) {
                final int rint = (rightTemplate.asInt()).getInt(0);
                final boolean isRightNA = (rint == RInt.NA);
                if (returnsDouble(arit)) {
                    final double rdbl = rint;
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (!(lexpr instanceof ScalarIntImpl)) {
                                throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                            }
                            int lint = ((ScalarIntImpl) lexpr).getInt();
                            if (isRightNA || lint == RInt.NA) {
                                return RDouble.BOXED_NA;
                            }
                            return RDouble.RDoubleFactory.getScalar(arit.op(ast, (double) lint, rdbl));
                        }
                    };
                    return createRightConst(ast, left, right, arit, c, "<ScalarInt, ConstScalarInt>");
                } else {
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (!(lexpr instanceof ScalarIntImpl)) {
                                throw new UnexpectedResultException(FailedSpecialization.FIXED_TYPE);
                            }
                            int lint = ((ScalarIntImpl) lexpr).getInt();
                            if (isRightNA || lint == RInt.NA) {
                                return RInt.BOXED_NA;
                            }
                            return RInt.RIntFactory.getScalar(arit.opWarnOverflow(ast, lint, rint));
                        }
                    };
                    return createRightConst(ast, left, right, arit, c, "<ScalarInt, ConstScalarInt>");
                }
            }
            return createGeneric(leftTemplate, rightTemplate, ast, left, right, arit);
        }

        public static SpecializedConst createSpecializedMultiType(RAny leftTemplate, RAny rightTemplate, final ASTNode ast, RNode left, RNode right, final ValueArithmetic arit) {

            boolean leftConst = left instanceof Constant;
            boolean rightConst = right instanceof Constant;
            assert Utils.check(leftConst || rightConst);

            final boolean alwaysDouble = returnsDouble(arit);

            if ((!(leftTemplate instanceof ScalarIntImpl) && !(leftTemplate instanceof ScalarDoubleImpl)) ||
               (!(rightTemplate instanceof ScalarIntImpl) && !(rightTemplate instanceof ScalarDoubleImpl))) {
                return null;
            }

            if (leftConst) {
                double tldbl;
                int tlint;
                boolean tisLeftNA;
                boolean tisLeftDouble;
                if (leftTemplate instanceof ScalarDoubleImpl) {
                    tlint = -1; // not used
                    tldbl = ((ScalarDoubleImpl) leftTemplate).getDouble();
                    tisLeftNA = RDouble.RDoubleUtils.arithIsNA(tldbl);
                    tisLeftDouble = true;
                } else {
                    tlint = ((ScalarIntImpl) leftTemplate).getInt();
                    tldbl = tlint;
                    tisLeftNA = tlint == RInt.NA;
                    tisLeftDouble = false;
                }
                final double ldbl = tldbl;
                final int lint = tlint;
                final boolean isLeftNA = tisLeftNA;
                final boolean isLeftDouble = tisLeftDouble;

                if (isLeftDouble || alwaysDouble) {
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (rexpr instanceof ScalarDoubleImpl) {
                                double rdbl = ((ScalarDoubleImpl) rexpr).getDouble();
                                if (isLeftNA || RDouble.RDoubleUtils.arithIsNA(rdbl)) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rdbl));
                            } else if (rexpr instanceof ScalarIntImpl) {
                                int rint = ((ScalarIntImpl) rexpr).getInt();
                                if (isLeftNA || rint == RInt.NA) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rint));
                            } else {
                                throw new UnexpectedResultException(FailedSpecialization.MULTI_TYPE);
                            }
                        }
                    };
                    return createLeftConst(ast, left, right, arit, c, "<ConstScalarDouble, ScalarInt|Double>");
                } else {
                    // left is constant int
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (rexpr instanceof ScalarDoubleImpl) {
                                double rdbl = ((ScalarDoubleImpl) rexpr).getDouble();
                                if (isLeftNA || RDouble.RDoubleUtils.arithIsNA(rdbl)) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, lint, rdbl));
                            } else if (rexpr instanceof ScalarIntImpl) {
                                int rint = ((ScalarIntImpl) rexpr).getInt();
                                if (isLeftNA || rint == RInt.NA) {
                                    return RInt.BOXED_NA;
                                }
                                return RInt.RIntFactory.getScalar(arit.opWarnOverflow(ast, lint, rint));
                            } else {
                                throw new UnexpectedResultException(FailedSpecialization.MULTI_TYPE);
                            }
                        }
                    };
                    return createLeftConst(ast, left, right, arit, c, "<ConstScalarInt, ScalarInt|Double>");
                }
            } else {
                // rightConst
                double trdbl;
                int trint;
                boolean tisRightNA;
                boolean tisRightDouble;
                if (rightTemplate instanceof ScalarDoubleImpl) {
                    trint = -1; // not used
                    trdbl = ((ScalarDoubleImpl) rightTemplate).getDouble();
                    tisRightNA = RDouble.RDoubleUtils.arithIsNA(trdbl);
                    tisRightDouble = true;
                } else {
                    trint = ((ScalarIntImpl) rightTemplate).getInt();
                    trdbl = trint;
                    tisRightNA = trint == RInt.NA;
                    tisRightDouble = false;
                }
                final double rdbl = trdbl;
                final int rint = trint;
                final boolean isRightNA = tisRightNA;
                final boolean isRightDouble = tisRightDouble;

                if (isRightDouble || alwaysDouble) {
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (lexpr instanceof ScalarDoubleImpl) {
                                double ldbl = ((ScalarDoubleImpl) lexpr).getDouble();
                                if (isRightNA || RDouble.RDoubleUtils.arithIsNA(ldbl)) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rdbl));
                            } else if (lexpr instanceof ScalarIntImpl) {
                                int lint = ((ScalarIntImpl) lexpr).getInt();
                                if (isRightNA || lint == RInt.NA) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, lint, rdbl));
                            } else {
                                throw new UnexpectedResultException(FailedSpecialization.MULTI_TYPE);
                            }
                        }
                    };
                    return createRightConst(ast, left, right, arit, c, "<ScalarInt|Double, ConstScalarDouble>");
                } else {
                    // left is constant int
                    Calculator c = new Calculator() {
                        @Override
                        public Object calc(Object lexpr, Object rexpr) throws UnexpectedResultException {
                            if (lexpr instanceof ScalarDoubleImpl) {
                                double ldbl = ((ScalarDoubleImpl) lexpr).getDouble();
                                if (isRightNA || RDouble.RDoubleUtils.arithIsNA(ldbl)) {
                                    return RDouble.BOXED_NA;
                                }
                                return RDouble.RDoubleFactory.getScalar(arit.op(ast, ldbl, rint));
                            } else if (lexpr instanceof ScalarIntImpl) {
                                int lint = ((ScalarIntImpl) lexpr).getInt();
                                if (isRightNA || lint == RInt.NA) {
                                    return RInt.BOXED_NA;
                                }
                                return RInt.RIntFactory.getScalar(arit.opWarnOverflow(ast, lint, rint));
                            } else {
                                throw new UnexpectedResultException(FailedSpecialization.MULTI_TYPE);
                            }
                        }
                    };
                    return createRightConst(ast, left, right, arit, c, "<ScalarInt|Double, ConstScalarInt>");
                }
            }
        }

        public static SpecializedConst createGeneric(RAny leftTemplate, RAny rightTemplate, final ASTNode ast, RNode left, RNode right, final ValueArithmetic arit) {
            Calculator c = null;
            boolean leftConst = left instanceof Constant;
            boolean rightConst = right instanceof Constant;
            final boolean returnsDouble = returnsDouble(arit);

            if (leftConst) {
                final boolean leftComplex = leftTemplate instanceof RComplex;
                final boolean leftDouble = leftTemplate instanceof RDouble;
                final boolean leftInt = leftTemplate instanceof RInt;
                final boolean leftLogicalOrInt = leftTemplate instanceof RLogical || leftTemplate instanceof RInt; // FIXME: does this pre-allocation pay off?
                final RComplex lcmp = (leftComplex) ? (RComplex) leftTemplate : leftTemplate.asComplex();
                final RDouble ldbl = (leftDouble) ? (RDouble) leftTemplate : leftTemplate.asDouble();
                final RInt lint = (leftLogicalOrInt) ? leftTemplate.asInt() : null;
                c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) {
                     // TODO: re-visit this, the error semantics with non-numeric types is very likely wrong
                        if (leftComplex || rexpr instanceof RComplex) {
                            RComplex rcmp = ((RAny) rexpr).asComplex();
                            return ComplexView.create(lcmp, rcmp, arit, ast);
                        }
                        if (returnsDouble) {
                            return doubleBinary(ldbl, ((RAny) rexpr).asDouble(), arit, ast);
                        }
                        if (leftDouble) {
                            if (rexpr instanceof RDouble) {
                                return doubleBinary(ldbl, (RDouble) rexpr, arit, ast);
                            } else if (rexpr instanceof RInt) {
                                return doubleBinary(ldbl, (RInt) rexpr, arit, ast);
                            } else {
                                return doubleBinary(ldbl, ((RAny) rexpr).asDouble(), arit, ast);
                            }
                        }
                        if (rexpr instanceof RDouble) {
                            RDouble rdbl = (RDouble) rexpr;
                            if (leftInt) {
                                return doubleBinary(lint, rdbl, arit, ast);
                            } else {
                                return doubleBinary(ldbl, rdbl, arit, ast);
                            }
                        }
                        if (leftLogicalOrInt || rexpr instanceof RInt || rexpr instanceof RLogical) { // FIXME: this check should be simpler
                            RInt rint = ((RAny) rexpr).asInt();
                            return intBinary(lint, rint, arit, ast);
                        }
                        Utils.nyi("unsupported case for binary arithmetic operation");
                        return null;
                    }
                };
            }
            if (rightConst) {
                final boolean rightComplex = rightTemplate instanceof RComplex;
                final boolean rightDouble = rightTemplate instanceof RDouble;
                final boolean rightInt = rightTemplate instanceof RInt;
                final boolean rightLogicalOrInt = rightTemplate instanceof RLogical || rightTemplate instanceof RInt;
                final RComplex rcmp = (rightComplex) ? (RComplex) rightTemplate : rightTemplate.asComplex();
                final RDouble rdbl = (rightDouble) ? (RDouble) rightTemplate : rightTemplate.asDouble();
                final RInt rint = (rightLogicalOrInt) ? rightTemplate.asInt() : null;
                c = new Calculator() {
                    @Override
                    public Object calc(Object lexpr, Object rexpr) {
                     // TODO: re-visit this, the error semantics with non-numeric types is very likely wrong
                        if (rightComplex || lexpr instanceof RComplex) {
                            RComplex lcmp = ((RAny) lexpr).asComplex();
                            return ComplexView.create(lcmp, rcmp, arit, ast);
                        }
                        if (returnsDouble) {
                            return doubleBinary(((RAny) lexpr).asDouble(), rdbl, arit, ast);
                        }
                        if (rightDouble) {
                            if (lexpr instanceof RDouble) {
                                return doubleBinary((RDouble) lexpr, rdbl, arit, ast);
                            } else if (lexpr instanceof RInt) {
                                return doubleBinary((RInt) lexpr, rdbl, arit, ast);
                            } else {
                                return doubleBinary(((RAny) lexpr).asDouble(), rdbl, arit, ast);
                            }
                        }
                        if (lexpr instanceof RDouble) {
                            RDouble ldbl = (RDouble) lexpr;
                            if (rightInt) {
                                return doubleBinary(ldbl, rint, arit, ast);
                            } else {
                                return doubleBinary(((RAny) lexpr).asDouble(), rdbl, arit, ast);
                            }
                        }
                        if (rightLogicalOrInt || lexpr instanceof RInt || lexpr instanceof RLogical) { // FIXME: this check should be simpler
                            RInt lint = ((RAny) lexpr).asInt();
                            return intBinary(lint, rint, arit, ast);
                        }
                        Utils.nyi("unsupported case for binary arithmetic operation");
                        return null;
                    }
                };
            }
            assert Utils.check(c != null);
            if (rightConst) {
                return createRightConst(ast, left, right, arit, c, "<Generic, ConstGeneric>");
            } else {
                return createLeftConst(ast, left, right, arit, c, "<ConstGeneric, Generic>");
            }
        }

        public static SpecializedConst createLeftConst(ASTNode ast, RNode left, RNode right, ValueArithmetic arit, Calculator calc, String dbg) {
            assert Utils.check(left instanceof Constant);
            return new SpecializedConst(ast, left, right, arit, calc, dbg) {

                @Override
                public Object execute(Frame frame) {
                    RAny rexpr = (RAny) right.execute(frame);
                    return execute(null, rexpr);
                }
            };
        }

        public static SpecializedConst createRightConst(ASTNode ast, RNode left, RNode right, ValueArithmetic arit, Calculator calc, String dbg) {
            assert Utils.check(right instanceof Constant);
            return new SpecializedConst(ast, left, right, arit, calc, dbg) {

                @Override
                public Object execute(Frame frame) {
                    RAny lexpr = (RAny) left.execute(frame);
                    return execute(lexpr, null);
                }
            };
        }

        private static RAny getExpr(RNode node, RAny value) {
            if (value == null) {
                return (RAny) node.execute(null);
            } else {
                return value;
            }
        }

        @Override
        public Object execute(Object lexpr, Object rexpr) {
            try {
                return calc.calc(lexpr, rexpr);
            } catch (UnexpectedResultException e) {
                FailedSpecialization f = (FailedSpecialization) e.getResult();
                RAny leftTemplate = getExpr(left, (RAny) lexpr);
                RAny rightTemplate = getExpr(right, (RAny) rexpr);
                if (f == FailedSpecialization.FIXED_TYPE) {
                    SpecializedConst sn = createSpecializedMultiType(leftTemplate, rightTemplate, ast, left, right, arit);
                    if (sn != null) {
                        replace(sn, "install SpecializedConstMultiType from SpecializedConst");
                        return sn.execute(lexpr, rexpr);
                    }
                }
                SpecializedConst gn = createGeneric(leftTemplate, rightTemplate, ast, left, right, arit);
                replace(gn, "install SpecializedConst<Generic, Generic> from SpecializedConst");
                if (DEBUG_AR) Utils.debug("Rewrote Const" + dbg + " to " + gn.dbg);
                return gn.execute(leftTemplate, rightTemplate);
            }
        }
    }

    public abstract static class ValueArithmetic {
        public abstract double opReal(ASTNode ast, double a, double b, double c, double d); // (a + bi)  op  (c + di)
        public abstract double opImag(ASTNode ast, double a, double b, double c, double d);

        public abstract double op(ASTNode ast, double a, double b);
        public abstract int op(ASTNode ast, int a, int b);
        public abstract void emitOverflowWarning(ASTNode ast);

        public final int opWarnOverflow(ASTNode ast, int a, int b) {
            int res = op(ast, a, b);
            if (res == RInt.NA) {
                emitOverflowWarning(ast);
            }
            return res;
        }
        public final double op(ASTNode ast, double a, int b) {
            return op(ast, a, (double) b);
        }
        public final double op(ASTNode ast, int a, double b) {
            return op(ast, (double) a, b);
        }

        public abstract RComplex op(ASTNode ast, ComplexImpl xcomp, ComplexImpl ycomp, int size, int[] dimensions, Names names, Attributes attributes);
        public abstract RComplex op(ASTNode ast, ComplexImpl xcomp, double c, double d, int size, int[] dimensions, Names names, Attributes attributes);

        public abstract void op(ASTNode ast, double[] x, double[] y, double[] res, int size);
        public abstract void op(ASTNode ast, double[] x, double y, double[] res, int size);

        public RDouble op(ASTNode ast, DoubleImpl xdbl, DoubleImpl ydbl, int size, int[] dimensions, Names names, Attributes attributes) {
            double[] x = xdbl.getContent();
            double[] y = ydbl.getContent();
            if (IN_PLACE && xdbl.isTemporary()) {
                op(ast, x, y, x, size);
                xdbl.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return xdbl;
            } else if (IN_PLACE && ydbl.isTemporary()) {
                op(ast, x, y, y, size);
                ydbl.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return ydbl;
            } else {
                double[] res = new double[size];
                op(ast, x, y, res, size);
                return RDouble.RDoubleFactory.getFor(res, dimensions, names, attributes);
            }
        }

        public RDouble op(ASTNode ast, DoubleImpl xdbl, double y, int size, int[] dimensions, Names names, Attributes attributes) {
            double[] x = xdbl.getContent();
            if (IN_PLACE && xdbl.isTemporary()) {
                op(ast, x, y, x, size);
                xdbl.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return xdbl;
            } else {
                double[] res = new double[size];
                op(ast, x, y, res, size);
                return RDouble.RDoubleFactory.getFor(res, dimensions, names, attributes);
            }
        }

        public abstract void op(ASTNode ast, int[] x, int yfrom, int yto, int ystep, int[] res, int size);

        public RInt op(ASTNode ast, IntImpl xint, IntImpl.RIntSequence y, int size, int[] dimensions, Names names, Attributes attributes) {
            // FIXME: needed?

            int[] x = xint.getContent();

            if (IN_PLACE && xint.isTemporary()) {
                op(ast, x, y.from(), y.to(), y.step(), x, size);
                xint.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return xint;
            } else {
                int[] res = new int[size];
                op(ast, x, y.from(), y.to(), y.step(), res, size);
                return RInt.RIntFactory.getFor(res, dimensions, names, attributes);
            }
        }

        public abstract boolean returnsDouble();
    }

    public static final class Add extends ValueArithmetic {
        @Override
        public double opReal(ASTNode ast, double a, double b, double c, double d) {
            return a + c;
        }
        @Override
        public double opImag(ASTNode ast, double a, double b, double c, double d) {
            return b + d;
        }
        @Override
        public double op(ASTNode ast, double a, double b) {
            return a + b;
        }
        public static int add(int a, int b) {
            // LICENSE: transcribed code from GNU R, which is licensed under GPL
            int r = a + b;
            boolean bLTr = b < r;
            if (a > 0) {
                if (bLTr) {
                    return r;
                }
            } else {
                if (!bLTr) {
                    return r;
                }
            }
            return RInt.NA;
        }

        @Override
        public int op(ASTNode ast, int a, int b) {
            return add(a, b);
        }
        @Override
        public void emitOverflowWarning(ASTNode ast) {
            RContext.warning(ast, RError.INTEGER_OVERFLOW);
        }
        private static void add(double[] res, double[] x, double[] y, int rsize) {
            int j = 1;
            for (int i = 0; i < rsize; i++, i++, j++, j++) {
                double a = x[i];
                double b = x[j];
                double c = y[i];
                double d = y[j];
                if (!RComplexUtils.arithEitherIsNA(a, b) && !RComplexUtils.arithEitherIsNA(c, d)) {
                    res[i] = a + c;
                    res[j] = b + d;
                } else {
                    res[i] = RDouble.NA;
                    res[j] = RDouble.NA;
                }
            }
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, ComplexImpl ycomp, int size, int[] dimensions, Names names, Attributes attributes) {
            int rsize = size * 2;
            double[] x = xcomp.getContent();
            double[] y = ycomp.getContent();
            if (IN_PLACE && xcomp.isTemporary()) {
                add(x, x, y, rsize);
                xcomp.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return xcomp;
            } else if (IN_PLACE && ycomp.isTemporary()) {
                add(y, x, y, rsize);
                ycomp.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return ycomp;
            } else {
                double[] res = new double[rsize];
                add(res, x, y, rsize);
                return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
            }
        }
        private static void add(double[] res, double[] x, double c, double d, int rsize) {
            int j = 1;
            for (int i = 0; i < rsize; i++, i++, j++, j++) {
                double a = x[i];
                double b = x[j];
                if (!RComplexUtils.arithEitherIsNA(a, b)) {
                    res[i] = a + c;
                    res[j] = b + d;
                } else {
                    res[i] = RDouble.NA;
                    res[j] = RDouble.NA;
                }
            }
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, double c, double d, int size, int[] dimensions, Names names, Attributes attributes) {
            int rsize = size * 2;
            double[] x = xcomp.getContent();
            if (IN_PLACE && xcomp.isTemporary()) {
                add(x, x, c, d, rsize);
                xcomp.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return xcomp;
            } else {
                double[] res = new double[rsize];
                add(res, x, c, d, rsize);
                return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
            }
        }
        @Override
        public void op(ASTNode ast, double[] x, double[] y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double b = y[i];
                double c = a + b;
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(b)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, double[] x, double y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double c = a + y;
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(y)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }

        @Override
        public void op(ASTNode ast, int[] x, int yfrom, int yto, int ystep, int[] res, int size) {
            // TODO: why is this not faster than the view ???
            // FIXME: needed ?
            int y = yfrom;
            boolean overflown = false;
            for (int i = 0; i < size; i++) {
                int a = x[i];
                if (a == RInt.NA) {
                    res[i] = RInt.NA;
                } else {
                    int r = add(a, y);
                    if (r == RInt.NA) {
                        overflown = true;
                    }
                    res[i] = r;
                }
                y += ystep;
                if (y > yto) {
                    y = yfrom;
                }
            }
            if (y != yfrom) {
                RContext.warning(ast, RError.LENGTH_NOT_MULTI);
            }
            if (overflown) {
                emitOverflowWarning(ast);
            }
        }

        @Override
        public boolean returnsDouble() {
            return false;
        }
    }

    public static final class Sub extends ValueArithmetic {
        @Override
        public double opReal(ASTNode ast, double a, double b, double c, double d) {
            return a - c;
        }
        @Override
        public double opImag(ASTNode ast, double a, double b, double c, double d) {
            return b - d;
        }
        @Override
        public double op(ASTNode ast, double a, double b) {
            return a - b;
        }
        @Override
        public int op(ASTNode ast, int a, int b) {
            // LICENSE: transcribed code from GNU R, which is licensed under GPL
            int r = a - b;
            if ((a < 0 == b < 0) || (a < 0 == r < 0)) {
                return r;
            } else {
                return RInt.NA;
            }
        }
        @Override
        public void emitOverflowWarning(ASTNode ast) {
            RContext.warning(ast, RError.INTEGER_OVERFLOW);
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, ComplexImpl ycomp, int size, int[] dimensions, Names names, Attributes attributes) {
            int rsize = size * 2;
            double[] res = new double[rsize];
            double[] x = xcomp.getContent();
            double[] y = ycomp.getContent();
            int j = 1;
            for (int i = 0; i < rsize; i++, i++, j++, j++) {
                double a = x[i];
                double b = x[j];
                double c = y[i];
                double d = y[j];
                if (!RComplexUtils.arithEitherIsNA(a, b) && !RComplexUtils.arithEitherIsNA(c, d)) {
                    res[i] = a - c;
                    res[j] = b - d;
                } else {
                    res[i] = RDouble.NA;
                    res[j] = RDouble.NA;
                }
            }
            return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, double c, double d, int size, int[] dimensions, Names names, Attributes attributes) {
            int rsize = size * 2;
            double[] res = new double[rsize];
            double[] x = xcomp.getContent();
            int j = 1;
            for (int i = 0; i < rsize; i++, i++, j++, j++) {
                double a = x[i];
                double b = x[j];
                if (!RComplexUtils.arithEitherIsNA(a, b)) {
                    res[i] = a - c;
                    res[j] = b - d;
                } else {
                    res[i] = RDouble.NA;
                    res[j] = RDouble.NA;
                }
            }
            return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
        }
        @Override
        public void op(ASTNode ast, double[] x, double[] y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double b = y[i];
                double c = a - b;
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(b)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, double[] x, double y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double c = a - y;
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(y)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, int[] x, int yfrom, int yto, int ystep, int[] res, int size) {
            Utils.nyi();
        }
        @Override
        public boolean returnsDouble() {
            return false;
        }
    }

    public static double convertNaN(double d) {
        if (Double.isNaN(d)) {
            return Math.copySign(0, d);
        } else {
            return d;
        }
    }

    public static double convertInf(double d) {
        return Math.copySign(Double.isInfinite(d) ? 1 : 0, d);
    }

    public static void cmult(double a, double b, double c, double d, double[] res, int offset) {
        // LICENSE: transcribed code from GCC, which is licensed under GPL
        // libgcc2

        double ac = a * c;
        double bd = b * d;
        double bc = b * c;
        double ad = a * d;

        double real = ac - bd;
        double imag = bc + ad;

        if (Double.isNaN(real) && Double.isNaN(imag)) {
            boolean recalc = false;
            double ra = a;
            double rb = b;
            double rc = c;
            double rd = d;
            if (Double.isInfinite(ra) || Double.isInfinite(rb)) {
                ra = convertInf(ra);
                rb = convertInf(rb);
                rc = convertNaN(rc);
                rd = convertNaN(rd);
                recalc = true;
            }
            if (Double.isInfinite(rc) || Double.isInfinite(rd)) {
                rc = convertInf(rc);
                rd = convertInf(rd);
                ra = convertNaN(ra);
                rb = convertNaN(rb);
                recalc = true;
            }
            if (!recalc && (Double.isInfinite(ac) || Double.isInfinite(bd) || Double.isInfinite(ad) || Double.isInfinite(bc))) {
                ra = convertNaN(ra);
                rb = convertNaN(rb);
                rc = convertNaN(rc);
                rd = convertNaN(rd);
                recalc = true;
            }
            if (recalc) {
                real = Double.POSITIVE_INFINITY * (ra * rc - rb * rd);
                imag = Double.POSITIVE_INFINITY * (ra * rd + rb * rc);
            }
        }
        res[ offset ] = real;
        res[ offset + 1 ] = imag;
    }

    public static final class Mult extends ValueArithmetic { // FIXME: will be slow for complex numbers (same calculations for real and imaginary parts)

        private static final double[] opTMP = new double[2];

        @Override
        public double opReal(ASTNode ast, double a, double b, double c, double d) {
            Arithmetic.cmult(a, b, c, d, opTMP, 0);
            return opTMP[0];
        }
        @Override
        public double opImag(ASTNode ast, double a, double b, double c, double d) {
            Arithmetic.cmult(a, b, c, d, opTMP, 0);
            return opTMP[1];
        }
        @Override
        public double op(ASTNode ast, double a, double b) {
            return a * b;
        }
        @Override
        public int op(ASTNode ast, int a, int b) {
            long l = (long) a * (long) b;
            if (!(l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)) {
                return (int) l;
            } else {
                return RInt.NA;
            }
        }
        @Override
        public void emitOverflowWarning(ASTNode ast) {
            RContext.warning(ast, RError.INTEGER_OVERFLOW);
        }
        private static void cmult(double[] res, double[] x, double[] y, int rsize) {

            if (x == y) {
                int j = 1;
                for (int i = 0; i < rsize; i++, i++, j++, j++) {
                    double a = x[i];
                    double b = x[j];
                    if (!RComplexUtils.arithEitherIsNA(a, b)) {
                        Arithmetic.Pow.cpow2(a, b, res, i);
                    } else {
                        res[i] = RDouble.NA;
                        res[j] = RDouble.NA;
                    }
                }
                return;
            }

            int j = 1;
            for (int i = 0; i < rsize; i++, i++, j++, j++) {
                double a = x[i];
                double b = x[j];
                double c = y[i];
                double d = y[j];
                if (!RComplexUtils.arithEitherIsNA(a, b) && !RComplexUtils.arithEitherIsNA(c, d)) {
                    Arithmetic.cmult(a, b, c, d, res, i);
                } else {
                    res[i] = RDouble.NA;
                    res[j] = RDouble.NA;
                }
            }
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, ComplexImpl ycomp, int size, int[] dimensions, Names names, Attributes attributes) {
            int rsize = size * 2;
            double[] x = xcomp.getContent();
            double[] y = ycomp.getContent();
            if (IN_PLACE && xcomp.isTemporary()) {
                cmult(x, x, y, rsize);
                xcomp.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return xcomp;
            } else if (IN_PLACE && ycomp.isTemporary()) {
                cmult(y, x, y, rsize);
                ycomp.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return ycomp;
            } else {
                double[] res = new double[rsize];
                cmult(res, x, y, rsize);
                return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
            }
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, double c, double d, int size, int[] dimensions, Names names, Attributes attributes) {
            int rsize = size * 2;
            double[] res = new double[rsize];
            double[] x = xcomp.getContent();
            int j = 1;
            for (int i = 0; i < rsize; i++, i++, j++, j++) {
                double a = x[i];
                double b = x[j];
                if (!RComplexUtils.arithEitherIsNA(a, b)) {
                    Arithmetic.cmult(x[i], x[j], c, d, res, i);
                } else {
                    res[i] = RDouble.NA;
                    res[j] = RDouble.NA;
                }
            }
            return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
        }
        @Override
        public void op(ASTNode ast, double[] x, double[] y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double b = y[i];
                double c = a * b;
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(b)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, double[] x, double y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double c = a * y;
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(y)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, int[] x, int yfrom, int yto, int ystep, int[] res, int size) {
            Utils.nyi();
        }
        @Override
        public boolean returnsDouble() {
            return false;
        }
    }

    public static double chypot(double real, double imag) {
        // LICENSE: transcribed code from GCC, which is licensed under GPL

        // after libgcc2's x86 hypot - note the sign of NaN below (what GNU-R uses)
        // note that Math.hypot in Java is _very_ slow as it tries to be more precise
        double res = Math.sqrt(real * real + imag * imag);

        if (!isFinite(real) || !isFinite(imag)) {
            if (Double.isInfinite(real) || Double.isInfinite(imag)) {
                res = Double.POSITIVE_INFINITY;
            } else if (Double.isNaN(imag)) {
                res = imag;
            } else {
                res = real;
            }
        }

        return res;
    }

    public static final class Pow extends ValueArithmetic {

        private static void creciprocal(double[] z, int offset) {
            // LICENSE: this code is derived from the division code, which is transcribed code from GCC, which is licensed under GPL

            double c = z[offset];
            double d = z[offset + 1];
            double ratio;
            double denom;
            double x;
            double y;

            if (Math.abs(c) < Math.abs(d)) {
                ratio = c / d;
                denom = (c * ratio) + d;
                x = ratio / denom;
                y = -1 / denom;
            } else {
                ratio = d / c;
                denom = (d * ratio) + c;
                x = 1 / denom;
                y = -ratio / denom;
            }

            if (Double.isNaN(x) && Double.isNaN(y)) {
                if (c == 0.0 && d == 0.0) {
                    x = Math.copySign(Double.POSITIVE_INFINITY, c);
                    y = Math.copySign(Double.NaN, c);
                } else if (Double.isInfinite(c) || Double.isInfinite(d)) {
                    double rc = convertInf(c);
                    double rd = convertInf(d);
                    x = 0.0 * rc;
                    y = 0.0 * (-rd);
                }
            }
            z[offset] = x;
            z[offset + 1] = y;
        }

        private static final double[] cpowTMP = new double[2];

        // R_cpow_n in complex.c
        private static void cpow(double xr, double xi, int k, double[] z, int offset) {
            // LICENSE: transcribed code from GNU R, which is licensed under GPL

            if (k == 0) {
                z[offset] = 1;
                z[offset + 1] = 0; // FIXME: perhaps should rely on cleared z
                return;
            }
            if (k == 1) {
                z[offset] = xr;
                z[offset + 1] = xi;
                return;
            }
            if (k < 0) {
                cpow(xr, xi, -k, z, offset); // x^(-k)
                creciprocal(z, offset);
                return;
            }
            double[] x = cpowTMP; // "x"
            x[0] = xr;
            x[1] = xi;
            z[offset] = 1; // "z"
            z[offset + 1] = 0;
            int kk = k;
            while (kk > 0) {
                if ((kk & 1) != 0) {
                    // "z = z * X"
                    cmult(z[offset], z[offset + 1], x[0], x[1], z, offset);
                    if (kk == 1) {
                        break;
                    }
                }
                kk = kk / 2;
                // "X = X * X"
                cpow2(x[0], x[1], x, 0);
            }
        }

        private static void cpow(double xr, double xi, double yr, double yi, double[] z, int offset) {
            // LICENSE: transcribed code from GNU R, which is licensed under GPL
            if (xr == 0) {
                if (yi == 0) {
                    z[offset] = pow(0, yr);
                    z[offset + 1] = xi;
                } else {
                    z[offset] = Double.NaN;
                    z[offset + 1] = Double.NaN;
                }
                return;
            }

            if (yi == 0) {
                int k = (int) yr;
                if (yr == k && Math.abs(k) <= 65536) {
                    cpow(xr, xi, k, z, offset);
                    return;
                }
            }

            double zr = chypot(xr, xi);
            double zi = Math.atan2(xi, xr);
            double theta = zi * yr;
            double rho;
            if (yi == 0) {
                rho = pow(zr, yr);
            } else {
                zr = Math.log(zr);
                theta += zr * yi;
                rho = Math.exp(zr * yr - zi * yi);
            }
            z[offset] = rho * Math.cos(theta);
            z[offset + 1] = rho * Math.sin(theta);
        }

        private static final double[] opTMP = new double[2];

        @Override
        public double opReal(ASTNode ast, double a, double b, double c, double d) {
            cpow(a, b, c, d, opTMP, 0);
            return opTMP[0];
        }
        @Override
        public double opImag(ASTNode ast, double a, double b, double c, double d) {
            cpow(a, b, c, d, opTMP, 0); // FIXME: remember last values? would a boxed version be faster?
            return opTMP[1];
        }

        @Override
        public double op(ASTNode ast, double a, double b) {
            // LICENSE: transcribed code from GNU R, which is licensed under GPL

            // NOTE: Math.pow (which uses FDLIBM) is very slow, the version written in assembly in GLIBC (SSE2 optimized) is about 2x faster

            // arithmetic.c (GNU R)
            if (b == 2) {
                return a * a;
            }
            if (a == 1 || b == 0) {
                return 1;
            }
            if (a == 0) {
                if (b > 0) {
                    return 0;
                }
                if (b < 0) {
                    return Double.POSITIVE_INFINITY;
                }
                return b;  // NA or NaN
            }
            if (isFinite(a) && isFinite(b)) {
                return pow(a, b);
            }
            if (RDouble.RDoubleUtils.isNAorNaN(a) || RDouble.RDoubleUtils.isNAorNaN(b)) {
                // NA check was before, so this can only mean NaN
                return a + b;
            }
            if (!isFinite(a)) {
                if (a > 0) { // Inf ^ y
                    if (b < 0) {
                        return 0;
                    }
                    return Double.POSITIVE_INFINITY;
                } else if (isFinite(b) && b == Math.floor(b)) { // (-Inf) ^ n
                    if (b < 0) {
                        return 0;
                    }
                    return fmod(ast, b, 2) != 0 ? a : -a;
                }
            }
            if (!isFinite(b)) {
                if (a >= 0) {
                    if (b > 0) {
                        return (a >= 1) ? Double.POSITIVE_INFINITY : 0;
                    }
                    return (a < 1) ? Double.POSITIVE_INFINITY : 0;
                }
            }
            return Double.NaN;
        }
        @Override
        public int op(ASTNode ast, int a, int b) {
            Utils.nyi("unreachable");
            return -1;
        }
        @Override
        public void emitOverflowWarning(ASTNode ast) {
            Utils.nyi("unreachable");
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, ComplexImpl ycomp, int size, int[] dimensions, Names names, Attributes attributes) {
            double[] x = xcomp.getContent();
            double[] y = ycomp.getContent();
            double[] z = new double[x.length];
            for (int i = 0; i < x.length; i += 2) {
                double xr = x[i];
                double xi = x[i + 1];
                double yr = y[i];
                double yi = y[i + 1];
                if (!RComplex.RComplexUtils.arithEitherIsNA(xr,  xi) && !RComplex.RComplexUtils.arithEitherIsNA(yr, yi)) {
                    cpow(x[i], x[i + 1], y[i], y[i + 1], z, i);
                } else {
                    z[i] = RDouble.NA;
                    z[i + 1] = RDouble.NA;
                }
            }
            RComplex res = RComplex.RComplexFactory.getFor(z, dimensions, names);
            return res;
        }

        public static void cpow2(double a, double b, double[] res, int offset) {
            // LICENSE: this code is derived from the multiplication code, which is transcribed code from GCC, which is licensed under GPL

            double a2 = a * a;
            double b2 = b * b;
            double ab = a * b;

            double real = a2 - b2;
            double imag = 2 * ab;

            if (Double.isNaN(real) && Double.isNaN(imag)) {
                boolean recalc = false;
                double ra = a;
                double rb = b;
                if (Double.isInfinite(ra) || Double.isInfinite(rb)) {
                    ra = convertInf(ra);
                    rb = convertInf(rb);
                    recalc = true;
                }
                if (!recalc && (Double.isInfinite(a2) || Double.isInfinite(b2) || Double.isInfinite(ab))) {
                    ra = convertNaN(ra);
                    rb = convertNaN(rb);
                    recalc = true;
                }
                if (recalc) {
                    real = Double.POSITIVE_INFINITY * (ra * ra - rb * rb);
                    imag = Double.POSITIVE_INFINITY * (ra * rb);
                }
            }
            res[ offset ] = real;
            res[ offset + 1 ] = imag;
        }

        private static void cpow(double[] res, double[] x, double yr, double yi) {
            if (yr == 2 && yi == 0) {
                for (int i = 0; i < x.length; i += 2) {
                    double xr = x[i];
                    double xi = x[i + 1];
                    if (!RComplex.RComplexUtils.arithEitherIsNA(xr, xi)) {
                        cpow2(xr, xi, res, i);
                    } else {
                        res[i] = RDouble.NA;
                        res[i + 1] = RDouble.NA;
                    }
                }
            } else {
                for (int i = 0; i < x.length; i += 2) {
                    double xr = x[i];
                    double xi = x[i + 1];
                    if (!RComplex.RComplexUtils.arithEitherIsNA(xr, xi)) {
                        cpow(x[i], x[i + 1], yr, yi, res, i); // FIXME: extract some checks on the exponent here
                    } else {
                        res[i] = RDouble.NA;
                        res[i + 1] = RDouble.NA;
                    }

                }
            }
        }

        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, double yr, double yi, int size, int[] dimensions, Names names, Attributes attributes) {
            double[] x = xcomp.getContent();
            if (IN_PLACE && xcomp.isTemporary()) {
                cpow(x, x, yr, yi);
                xcomp.setNames(names).setDimensions(dimensions).setAttributes(attributes);
                return xcomp;
            } else {
                double[] res = new double[x.length];
                cpow(res, x, yr, yi);
                return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
            }
        }

        @Override
        public void op(ASTNode ast, double[] x, double[] y, double[] res, int size) {
            if (!RContext.hasGNUR()) {
                for (int i = 0; i < size; i++) {
                    double a = x[i];
                    double b = y[i];
                    double c = pow(a, b);
                    if (RDouble.RDoubleUtils.arithIsNA(c)) {
                        if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(b)) {
                            res[i] = RDouble.NA;
                        }
                    } else {
                        res[i] = c;
                    }
                }
            } else {
                GNUR.pow(x, y, res, size);
            }
        }
        @Override
        public void op(ASTNode ast, double[] x, double y, double[] res, int size) {
            if (!RContext.hasGNUR()) {
                for (int i = 0; i < size; i++) {
                    double a = x[i];
                    double c = pow(a, y);
                    if (RDouble.RDoubleUtils.arithIsNA(c)) {
                        if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(y)) {
                            res[i] = RDouble.NA;
                        }
                    } else {
                        res[i] = c;
                    }
                }
            } else {
                GNUR.pow(x, y, res, size);
            }
        }

        @Override
        public void op(ASTNode ast, int[] x, int yfrom, int yto, int ystep, int[] res, int size) {
            Utils.nyi();
        }

        @Override
        public boolean returnsDouble() {
            return true;
        }
    }

    public static boolean isFinite(double d) {
        // NOTE: this is currently equivalent to RDoubleUtils.isFinite, but that can change in the future
        return !Double.isInfinite(d) && !Double.isNaN(d);
    }

    public static double pow(double a, double b) {
        if (!RContext.hasGNUR()) {
            return Math.pow(a, b);
        } else {
            return GNUR.pow(a, b);
        }
    }

    public static void cdiv(double a, double b, double c, double d, double[] res, int offset) {
        // LICENSE: transcribed code from GCC, which is licensed under GPL
        // libgcc2

        double ratio;
        double denom;
        double x;
        double y;

        if (Math.abs(c) < Math.abs(d)) {
            ratio = c / d;
            denom = (c * ratio) + d;
            x = ((a * ratio) + b) / denom;
            y = ((b * ratio) - a) / denom;
        } else {
            ratio = d / c;
            denom = (d * ratio) + c;
            x = ((b * ratio) + a) / denom;
            y = (b - (a * ratio)) / denom;
        }

        if (Double.isNaN(x) && Double.isNaN(y)) {
            if (c == 0.0 && d == 0.0 && (!Double.isNaN(a) || !Double.isNaN(b))) {
                x = Math.copySign(Double.POSITIVE_INFINITY, c) * a;
                y = Math.copySign(Double.POSITIVE_INFINITY, c) * b;
            } else if ((Double.isInfinite(a) || Double.isInfinite(b)) && isFinite(c) && isFinite(d)) {
                double ra = convertInf(a);
                double rb = convertInf(b);
                x = Double.POSITIVE_INFINITY * (ra * c + rb * d);
                y = Double.POSITIVE_INFINITY * (rb * c - ra * d);
            } else if ((Double.isInfinite(c) || Double.isInfinite(d)) && isFinite(a) && isFinite(b)) {
                double rc = convertInf(c);
                double rd = convertInf(d);
                x = 0.0 * (a * rc + b * rd);
                y = 0.0 * (b * rc - a * rd);
            }
        }
        res[offset] = x;
        res[offset + 1] = y;
    }

    public static final class Div extends ValueArithmetic {

        private static final double[] opTMP = new double[2];

        @Override
        public double opReal(ASTNode ast, double a, double b, double c, double d) {
            cdiv(a, b, c, d, opTMP, 0);
            return opTMP[0];
        }
        @Override
        public double opImag(ASTNode ast, double a, double b, double c, double d) {
            cdiv(a, b, c, d, opTMP, 0);
            return opTMP[1];
        }
        @Override
        public double op(ASTNode ast, double a, double b) {
            return a / b; // FIXME: check that the R rules correspond to Java
        }
        @Override
        public int op(ASTNode ast, int a, int b) {
            Utils.nyi("unreachable");
            return -1;
        }
        @Override
        public void emitOverflowWarning(ASTNode ast) {
            Utils.nyi("unreachable");
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, ComplexImpl ycomp, int size, int[] dimensions, Names names, Attributes attributes) {
            int rsize = size * 2;
            double[] res = new double[rsize];
            double[] x = xcomp.getContent();
            double[] y = ycomp.getContent();
            int j = 1;
            for (int i = 0; i < rsize; i++, i++, j++, j++) {
                double a = x[i];
                double b = x[j];
                double c = y[i];
                double d = y[j];
                if (!RComplexUtils.arithEitherIsNA(a, b) && !RComplexUtils.arithEitherIsNA(c, d)) {
                    cdiv(a, b, c, d, res, i);
                } else {
                    res[i] = RDouble.NA;
                    res[j] = RDouble.NA;
                }
            }
            return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, double c, double d, int size, int[] dimensions, Names names, Attributes attributes) {
            int rsize = size * 2;
            double[] res = new double[rsize];
            double[] x = xcomp.getContent();
            int j = 1;
            for (int i = 0; i < rsize; i++, i++, j++, j++) {
                double a = x[i];
                double b = x[j];
                if (!RComplexUtils.arithEitherIsNA(a, b)) {
                    cdiv(a, b, c, d, res, i);
                } else {
                    res[i] = RDouble.NA;
                    res[j] = RDouble.NA;
                }
            }
            return RComplex.RComplexFactory.getFor(res, dimensions, names, attributes);
        }
        @Override
        public void op(ASTNode ast, double[] x, double[] y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double b = y[i];
                double c = a / b;
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(b)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, double[] x, double y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double c = a / y;
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(y)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, int[] x, int yfrom, int yto, int ystep, int[] res, int size) {
            Utils.nyi();
        }
        @Override
        public boolean returnsDouble() {
            return true;
        }
    }

    public static final class IntegerDiv extends ValueArithmetic {
        @Override
        public double opReal(ASTNode ast, double a, double b, double c, double d) {
            throw RError.getUnimplementedComplex(ast);
        }
        @Override
        public double opImag(ASTNode ast, double a, double b, double c, double d) {
            throw RError.getUnimplementedComplex(ast);
        }
        @Override
        public double op(ASTNode ast, double a, double b) {
            // LICENSE: transcribed code from GNU R, which is licensed under GPL
            double q = a / b;
            if (b != 0) {
                double qfloor = Math.floor(q);
                double tmp = a - qfloor * b; // FIXME: this is R implementation, check if we can avoid this in Java
                return qfloor + Math.floor(tmp / b);

            } else {
                return q;
            }
        }
        @Override
        public int op(ASTNode ast, int a, int b) {
            if (b != 0) {
                return (int) Math.floor((double) a / (double) b); // FIXME: this is R implementation, can we do faster without floating point?
            } else {
                return RInt.NA;
            }
        }
        @Override
        public void emitOverflowWarning(ASTNode ast) {
            // no warning
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, ComplexImpl ycomp, int size, int[] dimensions, Names names, Attributes attributes) {
            throw RError.getUnimplementedComplex(ast);
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, double c, double d, int size, int[] dimensions, Names names, Attributes attributes) {
            throw RError.getUnimplementedComplex(ast);
        }
        @Override
        public void op(ASTNode ast, double[] x, double[] y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double b = y[i];
                double c = op(ast, a, b);
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(b)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, double[] x, double y, double[] res, int size) {
            for (int i = 0; i < size; i++) {
                double a = x[i];
                double c = op(ast, a, y);
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(y)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }
        }
        @Override
        public void op(ASTNode ast, int[] x, int yfrom, int yto, int ystep, int[] res, int size) {
            Utils.nyi();
        }
        @Override
        public boolean returnsDouble() {
            return false;
        }
    }

    public static double fmod(ASTNode ast, double a, double b) { // FIXME: this is R implementation, can we do faster in Java?
        // LICENSE: transcribed code from GNU R, which is licensed under GPL
        double q = a / b;
        if (b != 0) {
            double tmp = a - Math.floor(q) * b;
            if (RDouble.RDoubleUtils.isFinite(q) && Math.abs(q) > 1 / RDouble.EPSILON) {
                RContext.warning(ast, RError.ACCURACY_MODULUS);
            }
            return tmp - Math.floor(tmp / b) * b;
        } else {
            return RDouble.NaN;
        }
    }

    public static final class Mod extends ValueArithmetic {
        @Override
        public double opReal(ASTNode ast, double a, double b, double c, double d) {
            throw RError.getUnimplementedComplex(ast);
        }
        @Override
        public double opImag(ASTNode ast, double a, double b, double c, double d) {
            throw RError.getUnimplementedComplex(ast);
        }
        @Override
        public double op(ASTNode ast, double a, double b) {
            return fmod(ast, a, b);
        }
        @Override
        public int op(ASTNode ast, int a, int b) {
            // LICENSE: transcribed code from GNU R, which is licensed under GPL
            if (b != 0) {
                if (a >= 0 && b > 0) {
                    return a % b;
                } else {
                    return (int) fmod(ast, a, b);
                }
            } else {
                return RInt.NA;
            }
        }
        @Override
        public void emitOverflowWarning(ASTNode ast) {
            // no warning
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, ComplexImpl ycomp, int size, int[] dimensions, Names names, Attributes attributes) {
            throw RError.getUnimplementedComplex(ast);
        }
        @Override
        public RComplex op(ASTNode ast, ComplexImpl xcomp, double c, double d, int size, int[] dimensions, Names names, Attributes attributes) {
            throw RError.getUnimplementedComplex(ast);
        }
        @Override
        public void op(ASTNode ast, double[] x, double[] y, double[] res, int size) {
            if (!RContext.hasGNUR()) {
                for (int i = 0; i < size; i++) {
                    double a = x[i];
                    double b = y[i];
                    double c = fmod(ast, a, b);
                    if (RDouble.RDoubleUtils.arithIsNA(c)) {
                        if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(b)) {
                            res[i] = RDouble.NA;
                        }
                    } else {
                        res[i] = c;
                    }
                }
            } else { // FIXME: check if it won't be better to use the Java version for short vectors (branch above)
                boolean warn = GNUR.fmod(x, y, res, size);
                if (warn) {
                    RContext.warning(ast, RError.ACCURACY_MODULUS); // FIXME: will only appear once per vector
                }
            }
        }
        @Override
        public void op(ASTNode ast, double[] x, double y, double[] res, int size) {

            for (int i = 0; i < size; i++) {
                double a = x[i];
                double c = fmod(ast, a, y);
                if (RDouble.RDoubleUtils.arithIsNA(c)) {
                    if (RDouble.RDoubleUtils.arithIsNA(a) || RDouble.RDoubleUtils.arithIsNA(y)) {
                        res[i] = RDouble.NA;
                    }
                } else {
                    res[i] = c;
                }
            }

        }
        @Override
        public void op(ASTNode ast, int[] x, int yfrom, int yto, int ystep, int[] res, int size) {
            Utils.nyi();
        }
        @Override
        public boolean returnsDouble() {
            return false;
        }
    }

    public static final Add ADD = new Add();
    public static final Sub SUB = new Sub();
    public static final Mult MULT = new Mult();
    public static final Pow POW = new Pow();
    public static final Div DIV = new Div();
    public static final IntegerDiv INTEGER_DIV = new IntegerDiv();
    public static final Mod MOD = new Mod();


    public static class ComplexView extends View.RComplexView implements RComplex {
        final RComplex a;
        final RComplex b;
        final int na;
        final int nb;
        final int n;
        final int[] dimensions;
        final Names names;
        final Attributes attributes;
        boolean overflown = false;

        final ValueArithmetic arit;
        final ASTNode ast;

        // limiting view depth
        private int depth;  // total views involved

        public static RComplex create(RComplex a, RComplex b, ValueArithmetic arit, ASTNode ast) {
            if (EAGER_COMPLEX) {
                int asize = a.size();
                if (asize > 1) {
                    int bsize = b.size();
                    if (asize == bsize) {
                        return arit.op(ast, (ComplexImpl) a.materialize(), (ComplexImpl) b.materialize(), asize, resultDimensions(ast, a, b), resultNames(ast, a, b), resultAttributes(ast, a, b));
                    }
                    if (bsize == 1) {
                        double c = b.getReal(0);
                        double d = b.getImag(0);
                        if (!RComplexUtils.arithEitherIsNA(c, d)) {
                            return arit.op(ast, (ComplexImpl) a.materialize(), c, d, asize, resultDimensions(ast, a, b), resultNames(ast, a, b), resultAttributes(ast, a, b));
                        }
                        // NOTE: NA case falls back, could be added here
                    }
                }
            }
            int depth = 0;
            if (LIMIT_VIEW_DEPTH) {
                int adepth = (a instanceof ComplexView) ? ((ComplexView) a).depth : 0; // FIXME what about chains of double/complex views, etc?
                int bdepth = (b instanceof ComplexView) ? ((ComplexView) b).depth : 0;
                depth = adepth + bdepth + 1;
            }
            int[] dim = resultDimensions(ast, a, b);
            Names names = resultNames(ast, a, b);
            Attributes attributes = resultAttributes(ast, a, b);
            ComplexView res = new ComplexView(a, b, dim, names, attributes, depth, arit, ast);
            if (EAGER || (LIMIT_VIEW_DEPTH && (depth > MAX_VIEW_DEPTH)) || (a instanceof ScalarComplexImpl && b instanceof ScalarComplexImpl)) {
                return RComplexFactory.copy(res);
            }
            return res;
        }

        public ComplexView(RComplex a, RComplex b, int[] dimensions, Names names, Attributes attributes, int depth, ValueArithmetic arit, ASTNode ast) {
            this.a = a;
            this.b = b;
            na = a.size();
            nb = b.size();
            this.ast = ast;
            this.arit = arit;
            this.dimensions = dimensions;
            this.names = names;
            this.attributes = attributes;
            this.depth = depth;

            if (na > nb) {
                n = na;
                if ((n / nb) * nb != n) {
                    RContext.warning(ast, RError.LENGTH_NOT_MULTI);
                }
            } else {
                n = nb;
                if ((n / na) * na != n) {
                    RContext.warning(ast, RError.LENGTH_NOT_MULTI);
                }
            }
        }

        @Override
        public int size() {
            return n;
        }

        @Override
        public double getReal(int i) { // FIXME: this is very slow (real and imag getters repeat the same computation)
            int ai;
            int bi;
            if (i >= na) {
                ai = i % na;
                bi = i;
            } else if (i >= nb) {
                bi = i % nb;
                ai = i;
            } else {
                ai = i;
                bi = i;
            }
            double areal = a.getReal(ai);
            double aimag = a.getImag(ai);
            double breal = b.getReal(bi);
            double bimag = b.getImag(bi);
            if (!RComplexUtils.arithEitherIsNA(areal, aimag) && !RComplexUtils.arithEitherIsNA(breal, bimag)) {
                return arit.opReal(ast, areal, aimag, breal, bimag);
            } else {
                return RDouble.NA;
            }
        }

        @Override
        public double getImag(int i) { // FIXME: this is very slow (real and imag getters repeat the same computation)
            int ai;
            int bi;
            if (i >= na) {
                ai = i % na;
                bi = i;
            } else if (i >= nb) {
                bi = i % nb;
                ai = i;
            } else {
                ai = i;
                bi = i;
            }
            double areal = a.getReal(ai);
            double aimag = a.getImag(ai);
            double breal = b.getReal(bi);
            double bimag = b.getImag(bi);
            if (!RComplexUtils.arithEitherIsNA(areal, aimag) && !RComplexUtils.arithEitherIsNA(breal, bimag)) {
                return arit.opImag(ast, areal, aimag, breal, bimag);
            } else {
                return RDouble.NA;
            }
        }

        @Override
        public boolean isSharedReal() {
            return a.isShared() || b.isShared();
        }

        @Override
        public void ref() {
            a.ref();
            b.ref();
        }

        @Override
        public int[] dimensions() {
            return dimensions;
        }

        @Override
        public Names names() {
            return names;
        }

        @Override
        public Attributes attributes() {
            return attributes;
        }

        @Override
        public boolean dependsOn(RAny value) {
            return a.dependsOn(value) || b.dependsOn(value);
        }
    }

    public static RDouble doubleBinary(RDouble a, RDouble b, ValueArithmetic arit, ASTNode ast) {
        int depth = 0;
        if (LIMIT_VIEW_DEPTH) {
            int adepth = (a instanceof DoubleView) ? ((DoubleView) a).depth() : 0;
            int bdepth = (b instanceof DoubleView) ? ((DoubleView) b).depth() : 0;
            depth = adepth + bdepth + 1;
        }
        int[] dim = resultDimensions(ast, a, b);
        Names names = resultNames(ast, a, b);
        Attributes attributes = resultAttributes(ast, a, b);
        int na = a.size();
        int nb = b.size();
        DoubleView res;

        if (na == nb) {
            if (arit == POW && na > 1) {
                // FIXME: this is a hack.. POW is so expensive though that this is likely to pay off
                return arit.op(ast, (DoubleImpl) a.materialize(), (DoubleImpl) b.materialize(), na, dim, names, attributes);
            }
            if (a instanceof DoubleImpl && b instanceof DoubleImpl && (a.isTemporary() || b.isTemporary())) {
                // FIXME: do this only for Pow? sometimes? the check may be costly for short vectors
                return arit.op(ast, (DoubleImpl) a, (DoubleImpl) b, na, dim, names, attributes);
            }
            res = new DoubleView.EqualSize(a, b, dim, names, attributes, na, depth, arit, ast);
        } else if (nb == 1 && na > 0) {
            if (arit == POW && na > 1) {
                return arit.op(ast, (DoubleImpl) a.materialize(), b.getDouble(0), na, dim, names, attributes);
            }
            if (na > 1 && a instanceof DoubleImpl && a.isTemporary()) {
                // FIXME: re-visit the condition, like above
                return arit.op(ast, (DoubleImpl) a, b.getDouble(0), na, dim, names, attributes);
            }
            res = new DoubleView.VectorScalar(a, b, dim, names, attributes, na, depth, arit, ast);
        } else if (na == 1 && nb > 0) {
            res = new DoubleView.ScalarVector(a, b, dim, names, attributes, nb, depth, arit, ast);
        } else {
            int n = resultSize(ast, na, nb);
            res = new DoubleView.Generic(a, b, dim, names, attributes, n, depth, arit, ast);
        }
        if (EAGER || (LIMIT_VIEW_DEPTH && (depth > MAX_VIEW_DEPTH)) ||  (na == 1 && nb == 1)) {
            return RDouble.RDoubleFactory.copy(res);
        }
        return res;
    }

    // FIXME: try to reduce copy-paste, but may not be easy without harming performance
    public static RDouble doubleBinary(RDouble a, RInt b, ValueArithmetic arit, ASTNode ast) {
        int depth = 0;
        if (LIMIT_VIEW_DEPTH) {
            int adepth = (a instanceof DoubleView) ? ((DoubleView) a).depth() : 0;
            int bdepth = (b instanceof DoubleView) ? ((DoubleView) b).depth() : 0;
            depth = adepth + bdepth + 1;
        }
        int[] dim = resultDimensions(ast, a, b);
        Names names = resultNames(ast, a, b);
        Attributes attributes = resultAttributes(ast, a, b);
        int na = a.size();
        int nb = b.size();
        RDouble res;

        if (na == nb) {
            if (b instanceof RIntSequence) {
                res = new DoubleViewForDoubleInt.EqualSizeVectorSequence(a, (RIntSequence) b, dim, names, attributes, na, depth, arit, ast);
            } else {
                res = new DoubleViewForDoubleInt.EqualSizeVectorVector(a, b, dim, names, attributes, na, depth, arit, ast);
            }
        } else if (nb == 1 && na > 0) {
            res = new DoubleView.VectorScalar(a, b.asDouble(), dim, names, attributes, na, depth, arit, ast);
        } else if (na == 1 && nb > 0) {
            if (b instanceof RIntSequence) {
                res = new DoubleViewForDoubleInt.ScalarSequence(a, (RIntSequence) b, dim, names, attributes, nb, depth, arit, ast);
            } else {
                res = new DoubleView.ScalarVector(a, b.asDouble(), dim, names, attributes, nb, depth, arit, ast);
            }
        } else {
            int n = resultSize(ast, na, nb);
            if (b instanceof RIntSequence) {
                res = new DoubleViewForDoubleInt.VectorSequence(a, (RIntSequence) b, dim, names, attributes, n, depth, arit, ast);
            } else {
                res = new DoubleView.Generic(a, b.asDouble(), dim, names, attributes, n, depth, arit, ast);
            }
        }
        if (EAGER || (LIMIT_VIEW_DEPTH && (depth > MAX_VIEW_DEPTH)) ||  (na == 1 && nb == 1)) {
            return RDouble.RDoubleFactory.copy(res);
        }
        return res;
    }

    // FIXME: try to reduce copy-paste, but may not be easy without harming performance
    public static RDouble doubleBinary(RInt a, RDouble b, ValueArithmetic arit, ASTNode ast) {
        int depth = 0;
        if (LIMIT_VIEW_DEPTH) {
            int adepth = (a instanceof DoubleView) ? ((DoubleView) a).depth() : 0;
            int bdepth = (b instanceof DoubleView) ? ((DoubleView) b).depth() : 0;
            depth = adepth + bdepth + 1;
        }
        int[] dim = resultDimensions(ast, a, b);
        Names names = resultNames(ast, a, b);
        Attributes attributes = resultAttributes(ast, a, b);
        int na = a.size();
        int nb = b.size();
        RDouble res;

        if (na == nb) {
            if (a instanceof RIntSequence) {
                res = new DoubleViewForIntDouble.EqualSizeSequenceVector((RIntSequence) a, b, dim, names, attributes, na, depth, arit, ast);
            } else {
                res = new DoubleViewForIntDouble.EqualSizeVectorVector(a, b, dim, names, attributes, na, depth, arit, ast);
            }
        } else if (nb == 1 && na > 0) {
            if (a instanceof RIntSequence) {
                res = new DoubleViewForIntDouble.SequenceScalar((RIntSequence) a, b, dim, names, attributes, na, depth, arit, ast);
            } else {
                res = new DoubleViewForIntDouble.VectorScalar(a, b, dim, names, attributes, na, depth, arit, ast);
            }
        } else if (na == 1 && nb > 0) {
            res = new DoubleView.ScalarVector(a.asDouble(), b, dim, names, attributes, nb, depth, arit, ast);
        } else {
            int n = resultSize(ast, na, nb);
            if (a instanceof RIntSequence) {
                res = new DoubleViewForIntDouble.SequenceVector((RIntSequence) a, b, dim, names, attributes, n, depth, arit, ast);
            } else {
                res = new DoubleView.Generic(a.asDouble(), b, dim, names, attributes, n, depth, arit, ast);
            }
        }
        if (EAGER || (LIMIT_VIEW_DEPTH && (depth > MAX_VIEW_DEPTH)) ||  (na == 1 && nb == 1)) {
            return RDouble.RDoubleFactory.copy(res);
        }
        return res;
    }


    // NOTE: it is tempting to template this class by the type of a and type of b, re-using for
    // int and double combinations; unfortunately, that leads to slower execution
    abstract static class DoubleView extends View.RDoubleView implements RDouble {
        final RDouble a;
        final RDouble b;
        final int n;
        final int[] dimensions;
        final Names names;
        final Attributes attributes;

        final ValueArithmetic arit;
        final ASTNode ast;

        // limiting view depth
        private int depth;  // total views involved

        public DoubleView(RDouble a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
            this.a = a;
            this.b = b;
            this.dimensions = dimensions;
            this.names = names;
            this.attributes = attributes;
            this.n = n;
            this.depth = depth;
            this.arit = arit;
            this.ast = ast;
        }

        @Override
        public final int size() {
            return n;
        }

        @Override
        public final boolean isSharedReal() {
            return a.isShared() || b.isShared();
        }

        @Override
        public final void ref() {
            a.ref();
            b.ref();
        }

        @Override
        public final int[] dimensions() {
            return dimensions;
        }

        @Override
        public final Names names() {
            return names;
        }

        @Override
        public final Attributes attributes() {
            return attributes;
        }

        @Override
        public final boolean dependsOn(RAny value) {
            return a.dependsOn(value) || b.dependsOn(value);
        }

        public final int depth() {
            return depth;
        }

        static final class Generic extends DoubleView implements RDouble {
            final int na;
            final int nb;

            public Generic(RDouble a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                na = a.size();
                nb = b.size();
            }

            @Override
            public double getDouble(int i) {

                int ai;
                int bi;
                if (i >= na) {
                    ai = i % na;
                    bi = i;
                } else if (i >= nb) {
                    bi = i % nb;
                    ai = i;
                } else {
                    ai = i;
                    bi = i;
                }
                double adbl = a.getDouble(ai);
                double bdbl = b.getDouble(bi);
                if (RDouble.RDoubleUtils.arithIsNA(adbl) || RDouble.RDoubleUtils.arithIsNA(bdbl)) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, adbl, bdbl);
                }
             }
        }

        static final class EqualSize extends DoubleView implements RDouble {

            public EqualSize(RDouble a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
            }

            @Override
            public double getDouble(int i) {

                double adbl = a.getDouble(i);
                double bdbl = b.getDouble(i);
                if (RDouble.RDoubleUtils.arithIsNA(adbl) || RDouble.RDoubleUtils.arithIsNA(bdbl)) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, adbl, bdbl);
                }
             }

            @Override
            public RDouble materialize() {
                double[] res = new double[n];
                arit.op(ast, a.getContent(), b.getContent(), res, n);
                return RDouble.RDoubleFactory.getFor(res, dimensions, names, attributes);
            }
        }

        static final class VectorScalar extends DoubleView implements RDouble {

            final boolean arithIsNA;
            final double bdbl;

            public VectorScalar(RDouble a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                bdbl = b.getDouble(0);
                arithIsNA = RDouble.RDoubleUtils.arithIsNA(bdbl);
            }

            @Override
            public double getDouble(int i) {
                double adbl = a.getDouble(i);
                if (arithIsNA || RDouble.RDoubleUtils.arithIsNA(adbl)) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, adbl, bdbl);
                }
             }
        }

        // FIXME: this should be specialized much more in the call stack (building names, dimensions, attributes, calling ref, depends on, ...)
        static final class ScalarVector extends DoubleView implements RDouble {

            final boolean arithIsNA;
            final double adbl;

            public ScalarVector(RDouble a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                adbl = a.getDouble(0);
                arithIsNA = RDouble.RDoubleUtils.arithIsNA(adbl);
            }

            @Override
            public double getDouble(int i) {
                double bdbl = b.getDouble(i);
                if (arithIsNA || RDouble.RDoubleUtils.arithIsNA(bdbl)) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, adbl, bdbl);
                }
             }
        }

    }


    // note: the base class is a copy-paste of ArithmeticDoubleView, but templates make it slower
    abstract static class DoubleViewForDoubleInt extends View.RDoubleView implements RDouble {
        final RDouble a;
        final RInt b;
        final int n;
        final int[] dimensions;
        final Names names;
        final Attributes attributes;

        final ValueArithmetic arit;
        final ASTNode ast;

        // limiting view depth
        private int depth;  // total views involved

        public DoubleViewForDoubleInt(RDouble a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
            this.a = a;
            this.b = b;
            this.dimensions = dimensions;
            this.names = names;
            this.attributes = attributes;
            this.n = n;
            this.depth = depth;
            this.arit = arit;
            this.ast = ast;
        }

        @Override
        public final int size() {
            return n;
        }

        @Override
        public final boolean isSharedReal() {
            return a.isShared() || b.isShared();
        }

        @Override
        public final void ref() {
            a.ref();
            b.ref();
        }

        @Override
        public final int[] dimensions() {
            return dimensions;
        }

        @Override
        public final Names names() {
            return names;
        }

        @Override
        public final Attributes attributes() {
            return attributes;
        }

        @Override
        public final boolean dependsOn(RAny value) {
            return a.dependsOn(value) || b.dependsOn(value);
        }

        public final int depth() {
            return depth;
        }

        static final class VectorSequence extends DoubleViewForDoubleInt implements RDouble {
            final int na;
            final int nb;
            final int bfrom;
            final int bstep;

            public VectorSequence(RDouble a, RIntSequence b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                na = a.size();
                nb = b.size();
                bfrom = b.from();
                bstep = b.step();
            }

            @Override
            public double getDouble(int i) {

                int ai;
                int bi;
                if (i >= na) {
                    ai = i % na;
                    bi = i;
                } else if (i >= nb) {
                    bi = i % nb;
                    ai = i;
                } else {
                    ai = i;
                    bi = i;
                }
                double adbl = a.getDouble(ai);
                int bint = bfrom + bi * bstep;
                if (RDouble.RDoubleUtils.arithIsNA(adbl)) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, adbl, bint);
                }
             }
        }

        static final class EqualSizeVectorVector extends DoubleViewForDoubleInt implements RDouble {

            public EqualSizeVectorVector(RDouble a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
            }

            @Override
            public double getDouble(int i) {

                double adbl = a.getDouble(i);
                int bint = b.getInt(i);
                if (bint == RInt.NA || RDouble.RDoubleUtils.arithIsNA(adbl)) {
                    return RDouble.NA;
                }
                return arit.op(ast, adbl, bint);
             }
        }

        static final class EqualSizeVectorSequence extends DoubleViewForDoubleInt implements RDouble {

            final int bfrom;
            final int bstep;

            public EqualSizeVectorSequence(RDouble a, RIntSequence b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                bfrom = b.from();
                bstep = b.step();
            }

            @Override
            public double getDouble(int i) {

                double adbl = a.getDouble(i);
                int bint = bfrom + i * bstep;
                if (RDouble.RDoubleUtils.arithIsNA(adbl)) {
                    return RDouble.NA;
                }
                return arit.op(ast, adbl, bint);
             }
        }

        static final class ScalarSequence extends DoubleViewForDoubleInt implements RDouble {

            final boolean arithIsNA;
            final double adbl;
            final int bfrom;
            final int bstep;

            public ScalarSequence(RDouble a, RIntSequence b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                adbl = a.getDouble(0);
                arithIsNA = RDouble.RDoubleUtils.arithIsNA(adbl);
                bfrom = b.from();
                bstep = b.step();
            }

            @Override
            public double getDouble(int i) {
                int bint = bfrom + i * bstep;
                if (arithIsNA) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, adbl, bint);
                }
             }
        }
    }

 // note: the base class is a copy-paste of Arithmetic.DoubleView, but templates make it slower
    abstract static class DoubleViewForIntDouble extends View.RDoubleView implements RDouble {
        final RInt a;
        final RDouble b;
        final int n;
        final int[] dimensions;
        final Names names;
        final Attributes attributes;

        final ValueArithmetic arit;
        final ASTNode ast;

        // limiting view depth
        private int depth;  // total views involved

        public DoubleViewForIntDouble(RInt a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
            this.a = a;
            this.b = b;
            this.dimensions = dimensions;
            this.names = names;
            this.attributes = attributes;
            this.n = n;
            this.depth = depth;
            this.arit = arit;
            this.ast = ast;
        }

        @Override
        public final int size() {
            return n;
        }

        @Override
        public final boolean isSharedReal() {
            return a.isShared() || b.isShared();
        }

        @Override
        public final void ref() {
            a.ref();
            b.ref();
        }

        @Override
        public final int[] dimensions() {
            return dimensions;
        }

        @Override
        public final Names names() {
            return names;
        }

        @Override
        public final Attributes attributes() {
            return attributes;
        }

        @Override
        public final boolean dependsOn(RAny value) {
            return a.dependsOn(value) || b.dependsOn(value);
        }

        public final int depth() {
            return depth;
        }

        static final class SequenceVector extends DoubleViewForIntDouble implements RDouble {
            final int na;
            final int nb;
            final int afrom;
            final int astep;

            public SequenceVector(RIntSequence a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                na = a.size();
                nb = b.size();
                afrom = a.from();
                astep = a.step();
            }

            @Override
            public double getDouble(int i) {

                int ai;
                int bi;
                if (i >= na) {
                    ai = i % na;
                    bi = i;
                } else if (i >= nb) {
                    bi = i % nb;
                    ai = i;
                } else {
                    ai = i;
                    bi = i;
                }
                int aint = afrom + ai * astep;
                double bdbl = b.getDouble(bi);

                if (RDouble.RDoubleUtils.arithIsNA(bdbl)) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, aint, bdbl);
                }
             }
        }

        static final class EqualSizeVectorVector extends DoubleViewForIntDouble implements RDouble {

            public EqualSizeVectorVector(RInt a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
            }

            @Override
            public double getDouble(int i) {

                int aint = a.getInt(i);
                double bdbl = b.getDouble(i);

                if (aint == RInt.NA || RDouble.RDoubleUtils.arithIsNA(bdbl)) {
                    return RDouble.NA;
                }
                return arit.op(ast, aint, bdbl);
             }
        }

        static final class EqualSizeSequenceVector extends DoubleViewForIntDouble implements RDouble {
            final int afrom;
            final int astep;

            public EqualSizeSequenceVector(RIntSequence a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                afrom = a.from();
                astep = a.step();
            }

            @Override
            public double getDouble(int i) {

                int aint = afrom + i * astep;
                double bdbl = b.getDouble(i);

                if (RDouble.RDoubleUtils.arithIsNA(bdbl)) {
                    return RDouble.NA;
                }
                return arit.op(ast, aint, bdbl);
             }
        }

        static final class VectorScalar extends DoubleViewForIntDouble implements RDouble {

            final boolean arithIsNA;
            final double bdbl;

            public VectorScalar(RInt a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                bdbl = b.getDouble(0);
                arithIsNA = RDouble.RDoubleUtils.arithIsNA(bdbl);
            }

            @Override
            public double getDouble(int i) {
                double aint = a.getInt(i);
                if (arithIsNA || aint == RInt.NA) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, aint, bdbl);
                }
            }
        }

        static final class SequenceScalar extends DoubleViewForIntDouble implements RDouble {

            final boolean arithIsNA;
            final double bdbl;
            final int afrom;
            final int astep;

            public SequenceScalar(RIntSequence a, RDouble b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                bdbl = b.getDouble(0);
                arithIsNA = RDouble.RDoubleUtils.arithIsNA(bdbl);
                afrom = a.from();
                astep = a.step();
            }

            @Override
            public double getDouble(int i) {
                double aint = afrom + i*astep;
                if (arithIsNA) {
                    return RDouble.NA;
                } else {
                    return arit.op(ast, aint, bdbl);
                }
            }
        }
    }

//    // experimental optimization - may have to be re-visited later
//    private static RInt hackAddTemporaryIntandSequence(RInt aArg, RIntSequence b, int na, int nb, ValueArithmetic arit, ASTNode ast) {
//        int[] a = aArg.getContent();
//        int bfrom = b.from();
//        int bstep = b.step();
//
//        boolean overflown = false;
//
//        int nfull = na / nb;
//        int aoffset = 0;
//        for (int fi = 0; fi < nfull; fi++) {
//            int bval = bfrom;
//            for (int bi = 0; bi < nb; bi++) {
//                int ai = aoffset + bi;
//                int aval = a[ai];
//                int res;
//                if (aval > 0) {
//                    res = aval + bval;
//                    // NOTE: aval cannot be RInt.NA
//                    if (bval >= res) {
//                        res = RInt.NA;
//                        overflown = true;
//                    }
//                } else {
//                    if (aval == RInt.NA) {
//                        res = RInt.NA;
//                    } else {
//                        res = aval + bval;
//                        if (bval < res) {
//                            res = RInt.NA;
//                            overflown = true;
//                        } else {
//                            if (res == RInt.NA) {
//                             // r may be NA (may "naturally" reach NA which is however still reported as overflow by R)
//                                overflown = true;
//                            }
//                        }
//                    }
//                }
//                a[ai] = res;
//                bval += bstep;
//            }
//            aoffset += nb;
//        }
//        int bval = bfrom;
//        for (int ai = aoffset; ai < na; ai++) {
//            int aval = a[ai];
//            int res;
//            if (aval > 0) {
//                res = aval + bval;
//                // NOTE: aval cannot be RInt.NA
//                if (bval >= res) {
//                    res = RInt.NA;
//                    overflown = true;
//                }
//            } else {
//                if (aval == RInt.NA) {
//                    res = RInt.NA;
//                } else {
//                    res = aval + bval;
//                    if (bval < res) {
//                        res = RInt.NA;
//                        overflown = true;
//                    } else {
//                        if (res == RInt.NA) {
//                         // r may be NA (may "naturally" reach NA which is however still reported as overflow by R)
//                            overflown = true;
//                        }
//                    }
//                }
//            }
//            bval += bstep;
//        }
//        if (overflown) {
//            arit.emitOverflowWarning(ast);
//        }
//        return aArg;
//    }

    // FIXME: it might pay off to use some of the optimizations only with sufficiently large vectors, so e.g. conditionally on the size
    // that is being checked already anyway
    public static RInt intBinary(RInt a, RInt b, ValueArithmetic arit, ASTNode ast) {
        assert Utils.check(!arit.returnsDouble());

        int depth = 0;
        if (LIMIT_VIEW_DEPTH) {
            int adepth = (a instanceof IntView) ? ((IntView) a).depth() : 0;
            int bdepth = (b instanceof IntView) ? ((IntView) b).depth() : 0;
            depth = adepth + bdepth + 1;
        }
        int[] dim = resultDimensions(ast, a, b);
        Names names = resultNames(ast, a, b);
        Attributes attributes = resultAttributes(ast, a, b);
        int na = a.size();
        int nb = b.size();
        IntView res;

        if (na == nb) {
            if (b instanceof RIntSequence) {
                res = new IntView.EqualSizeIntSequence(a, (RIntSequence) b, dim, names, attributes, na, depth, arit, ast);
            } else if (a instanceof RIntSequence) {
                res = new IntView.EqualSizeSequenceInt((RIntSequence) a, b, dim, names, attributes, na, depth, arit, ast);
            } else {
                res = new IntView.EqualSize(a, b, dim, names, attributes, na, depth, arit, ast);
            }
        } else if (nb == 1 && na > 0) {
            if (a instanceof RIntSequence) {
                res = new IntView.SequenceScalar((RIntSequence) a, b, dim, names, attributes, na, depth, arit, ast);
            } else {
                res = new IntView.VectorScalar(a, b, dim, names, attributes, na, depth, arit, ast);
            }
        } else if (na == 1 && nb > 0) {
            if (b instanceof RIntSequence) {
                res = new IntView.ScalarSequence(a, (RIntSequence) b, dim, names, attributes, nb, depth, arit, ast);
            } else {
                res = new IntView.ScalarVector(a, b, dim, names, attributes, nb, depth, arit, ast);
            }
        } else {
            int n = resultSize(ast, na, nb);
            if (b instanceof RIntSequence) {
                // HACK HACK just to test if this would help in one benchmark
//                if (na > 1 && arit == ADD && a.isTemporary() && n == na) {
//                    return hackAddTemporaryIntandSequence(a, (RIntSequence) b, na, nb, arit, ast);
//                }

                // TODO: why is this actually slowing us down?
//                if (a instanceof IntImpl && a.isTemporary() && n == na) {
//                    return arit.op(ast, (IntImpl) a, (RIntSequence) b, n, dim, names, attributes);
//                }

                res = new IntView.VectorSequence(a, (RIntSequence) b, dim, names, attributes, n, depth, arit, ast);
            } else if (a instanceof RIntSequence) {
                res = new IntView.SequenceVector((RIntSequence) a, b, dim, names, attributes, n, depth, arit, ast);
            } else {
                res = new IntView.Generic(a, b, dim, names, attributes, n, depth, arit, ast);
            }
        }
        if (EAGER || (LIMIT_VIEW_DEPTH && (depth > MAX_VIEW_DEPTH)) ||  (na == 1 && nb == 1)) {
            return RInt.RIntFactory.copy(res);
        }
        return res;
    }

    abstract static class IntView extends View.RIntView implements RInt {
        final RInt a;
        final RInt b;
        final int n;
        final int[] dimensions;
        final Names names;
        final Attributes attributes;
        boolean overflown = false;

        final ValueArithmetic arit;
        final ASTNode ast;

        // limiting view depth
        private int depth;  // total views involved

        public IntView(RInt a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
            this.a = a;
            this.b = b;
            this.dimensions = dimensions;
            this.names = names;
            this.attributes = attributes;
            this.n = n;
            this.depth = depth;
            this.arit = arit;
            this.ast = ast;
        }

        @Override
        public final int size() {
            return n;
        }

        @Override
        public boolean isSharedReal() {
            return a.isShared() || b.isShared();
        }

        @Override
        public void ref() {
            a.ref();
            b.ref();
        }

        @Override
        public int[] dimensions() {
            return dimensions;
        }

        @Override
        public Names names() {
            return names;
        }

        @Override
        public Attributes attributes() {
            return attributes;
        }

        @Override
        public boolean dependsOn(RAny value) {
            return a.dependsOn(value) || b.dependsOn(value);
        }

        public final int depth() {
            return depth;
        }

        static final class Generic extends IntView implements RInt {
            final int na;
            final int nb;

            public Generic(RInt a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                na = a.size();
                nb = b.size();
            }

            @Override
            public int getInt(int i) {
                int ai;
                int bi;
                if (i >= na) {
                    ai = i % na;
                    bi = i;
                } else if (i >= nb) {
                    bi = i % nb;
                    ai = i;
                } else {
                    ai = i;
                    bi = i;
                }
                int aint = a.getInt(ai);
                int bint = b.getInt(bi);
                if (aint == RInt.NA || bint == RInt.NA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
            }
        }

        static final class VectorSequence extends IntView implements RInt {
            final int na;
            final int nb;
            final int bfrom;
            final int bstep;

            public VectorSequence(RInt a, RIntSequence b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                na = a.size();
                nb = b.size();
                bfrom = b.from();
                bstep = b.step();
            }

            @Override
            public int getInt(int i) {
                int ai;
                int bi;
                if (i >= na) {
                    ai = i % na;
                    bi = i;
                } else if (i >= nb) {
                    bi = i % nb;
                    ai = i;
                } else {
                    ai = i;
                    bi = i;
                }
                int aint = a.getInt(ai);
                int bint = bfrom + bi * bstep;
                if (aint == RInt.NA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
            }
        }

        static final class SequenceVector extends IntView implements RInt {
            final int na;
            final int nb;
            final int afrom;
            final int astep;

            public SequenceVector(RIntSequence a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                na = a.size();
                nb = b.size();
                afrom = a.from();
                astep = a.step();
            }

            @Override
            public int getInt(int i) {
                int ai;
                int bi;
                if (i >= na) {
                    ai = i % na;
                    bi = i;
                } else if (i >= nb) {
                    bi = i % nb;
                    ai = i;
                } else {
                    ai = i;
                    bi = i;
                }
                int aint = afrom + ai * astep;
                int bint = b.getInt(bi);
                if (bint == RInt.NA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
            }
        }

        static final class EqualSize extends IntView implements RInt {

            public EqualSize(RInt a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
            }

            @Override
            public int getInt(int i) {

                int aint = a.getInt(i);
                int bint = b.getInt(i);
                if (aint == RInt.NA || bint == RInt.NA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
             }
        }

        static final class EqualSizeIntSequence extends IntView implements RInt {

            final int bfrom;
            final int bstep;

            public EqualSizeIntSequence(RInt a, RIntSequence b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                bfrom = b.from();
                bstep = b.step();
            }

            @Override
            public int getInt(int i) {

                int aint = a.getInt(i);
                int bint = bfrom + i * bstep;
                if (aint == RInt.NA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
             }
        }

        static final class EqualSizeSequenceInt extends IntView implements RInt {

            final int afrom;
            final int astep;

            public EqualSizeSequenceInt(RIntSequence a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                afrom = a.from();
                astep = a.step();
            }

            @Override
            public int getInt(int i) {

                int aint = afrom + i * astep;
                int bint = b.getInt(i);
                if (bint == RInt.NA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
             }
        }


        static final class VectorScalar extends IntView implements RInt {

            final boolean arithIsNA;
            final int bint;

            public VectorScalar(RInt a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                bint = b.getInt(0);
                arithIsNA = bint == RInt.NA;
            }

            @Override
            public int getInt(int i) {

                int aint = a.getInt(i);
                if (arithIsNA || aint == RInt.NA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
             }
        }

        static final class SequenceScalar extends IntView implements RInt {

            final boolean arithIsNA;
            final int bint;
            final int afrom;
            final int astep;

            public SequenceScalar(RIntSequence a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                bint = b.getInt(0);
                arithIsNA = bint == RInt.NA;
                afrom = a.from();
                astep = a.step();
            }

            @Override
            public int getInt(int i) {

                int aint = afrom + i * astep;
                if (arithIsNA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
             }
        }

        static final class ScalarVector extends IntView implements RInt {

            final boolean arithIsNA;
            final int aint;

            public ScalarVector(RInt a, RInt b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                aint = a.getInt(0);
                arithIsNA = aint == RInt.NA;
            }

            @Override
            public int getInt(int i) {

                int bint = b.getInt(i);
                if (arithIsNA || bint == RInt.NA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
             }
        }

        static final class ScalarSequence extends IntView implements RInt {

            final boolean arithIsNA;
            final int aint;
            final int bfrom;
            final int bstep;

            public ScalarSequence(RInt a, RIntSequence b, int[] dimensions, Names names, Attributes attributes, int n, int depth, ValueArithmetic arit, ASTNode ast) {
                super(a, b, dimensions, names, attributes, n, depth, arit, ast);
                aint = a.getInt(0);
                arithIsNA = aint == RInt.NA;
                bfrom = b.from();
                bstep = b.step();
            }

            @Override
            public int getInt(int i) {

                int bint = bfrom + i * bstep;
                if (arithIsNA) {
                    return RInt.NA;
                } else {
                    int res = arit.op(ast, aint, bint);
                    if (res == RInt.NA && !overflown) {
                        overflown = true;
                        arit.emitOverflowWarning(ast);
                    }
                    return res;
                }
             }
        }

    }

    public static int[] resultDimensions(ASTNode ast, RArray a, RArray b) {
        int[] dima = a.dimensions();
        int[] dimb = b.dimensions();
        if (dima == null) {
            if (dimb != null) {
                int asize = a.size();
                int bsize = b.size();
                if (asize > bsize) {
                    throw RError.getDimsDontMatchLength(ast, bsize, asize);
                }
            }
            return dimb;
        }
        if (dimb == null) {
            if (dima != null) {
                int asize = a.size();
                int bsize = b.size();
                if (bsize > asize) {
                    throw RError.getDimsDontMatchLength(ast, asize, bsize);
                }
            }
            return dima;
        }

        int alen = dima.length;
        int blen = dimb.length;
        if (alen == 2 && blen == 2 && dima[0] == dimb[0] && dima[1] == dimb[1]) {
            return dima;
        }

        if (alen == blen) {
            for (int i = 0; i < alen; i++) {
                if (dima[i] != dimb[i]) {
                    throw RError.getNonConformableArrays(ast);
                }
            }
            return dima;
        }
        throw RError.getNonConformableArrays(ast);
    }

    public static Names resultNames(ASTNode ast, RArray a, RArray b) {
        Names na = a.names();
        Names nb = b.names();
        if (nb == null) {
            return na;
        }
        if (na == null) {
            return nb;
        }
        int asize = a.size();
        int bsize = b.size();

        if (bsize > asize) {
            return nb;
        } else {
            return na;
        }
    }

    // note: increments reference count on attributes
    public static Attributes resultAttributes(ASTNode ast, RArray a, RArray b) {
        Attributes aa = a.attributes();
        Attributes ba = b.attributes();

        if (ba == null && aa == null) {
            return null;
        }
        int asize = a.size();
        int bsize = b.size();

        if (asize > bsize) {
            return Attributes.markShared(aa);
        }
        if (bsize > asize) {
            return Attributes.markShared(ba);
        }
        // asize == bsize
        if (ba == null) {
            return Attributes.markShared(aa);
        }
        if (aa == null) {
            return Attributes.markShared(ba);
        }
        // both aa != null and ba != null

        Attributes res = ba.copy();
        Map<RSymbol, RAny> amap = aa.map();
        for (Map.Entry<RSymbol, RAny> ae : amap.entrySet()) {
            RAny value = ae.getValue();
            value.ref();
            res.put(ae.getKey(), value);
        }
        return res;
    }

    public static int resultSize(ASTNode ast, int na, int nb) {
        int n;
        if (na == 0 || nb == 0) {
            return 0;
        }
        if (na > nb) {
            n = na;
            if ((n / nb) * nb != n) {
                RContext.warning(ast, RError.LENGTH_NOT_MULTI);
            }
        } else {
            n = nb;
            if ((n / na) * na != n) {
                RContext.warning(ast, RError.LENGTH_NOT_MULTI);
            }
        }
        return n;
    }
}

