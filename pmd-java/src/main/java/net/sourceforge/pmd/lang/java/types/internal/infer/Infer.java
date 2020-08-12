/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.types.internal.infer;

import static net.sourceforge.pmd.lang.java.types.TypeConversion.capture;
import static net.sourceforge.pmd.lang.java.types.TypeConversion.isNotWilcardParameterized;
import static net.sourceforge.pmd.lang.java.types.TypeOps.asList;
import static net.sourceforge.pmd.lang.java.types.TypeOps.subst;
import static net.sourceforge.pmd.lang.java.types.internal.infer.ExprOps.isPertinentToApplicability;
import static net.sourceforge.pmd.lang.java.types.internal.infer.MethodResolutionPhase.INVOC_LOOSE;
import static net.sourceforge.pmd.lang.java.types.internal.infer.MethodResolutionPhase.LOOSE;
import static net.sourceforge.pmd.lang.java.types.internal.infer.MethodResolutionPhase.STRICT;
import static net.sourceforge.pmd.lang.java.types.internal.infer.MethodResolutionPhase.VARARGS;
import static net.sourceforge.pmd.util.CollectionUtil.listOf;
import static net.sourceforge.pmd.util.CollectionUtil.setOf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.types.JArrayType;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JMethodSig;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.JTypeVar;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeOps;
import net.sourceforge.pmd.lang.java.types.TypeSystem;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprCheckHelper.ExprChecker;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.CtorInvocationMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.InvocationMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.InvocationMirror.MethodCtDecl;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.LambdaExprMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.MethodRefMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.ExprMirror.PolyExprMirror;
import net.sourceforge.pmd.lang.java.types.internal.infer.JInferenceVar.BoundKind;
import net.sourceforge.pmd.util.CollectionUtil;

/**
 * Main entry point for type inference.
 */
public final class Infer {

    final ExprOps exprOps;
    private final OverloadComparator overloadComparator;

    @SuppressWarnings("PMD.FieldNamingConventions")
    final TypeInferenceLogger LOG; // SUPPRESS CHECKSTYLE just easier to read I think

    private final boolean isJava8; // NOPMD this is unused but may be used later
    private final TypeSystem ts;

    @SuppressWarnings("PMD.FieldNamingConventions")
    private final MethodCtDecl UNRESOLVED_CTDECL; // SUPPRESS CHECKSTYLE same

    /**
     * Creates a new instance.
     *
     * @param ts         Type system
     * @param jdkVersion JDK version to use. Type inference was changed
     *                   in Java 8 to propagate the context type.
     * @param logger     Strategy to log failures
     */
    public Infer(TypeSystem ts, int jdkVersion, TypeInferenceLogger logger) {
        this.ts = ts;
        this.isJava8 = jdkVersion >= 8;
        this.LOG = logger;

        this.UNRESOLVED_CTDECL = new MethodCtDecl(ts.UNRESOLVED_METHOD, STRICT);
        this.exprOps = new ExprOps(this);
        this.overloadComparator = new OverloadComparator(this);
    }

    // test only
    OverloadComparator getOverloadComparator() {
        return overloadComparator;
    }

    public TypeSystem getTypeSystem() {
        return ts;
    }

    public TypeInferenceLogger getLogger() {
        return LOG;
    }

    public PolySite newPolySite(PolyExprMirror mirror, @Nullable JTypeMirror expectedType) {
        return mirror instanceof InvocationMirror ? newCallSite((InvocationMirror) mirror, expectedType)
                                                  : new PolySite(mirror, expectedType);
    }

    public MethodCallSite newCallSite(InvocationMirror expr,
                                      @Nullable JTypeMirror expectedType) {
        return newCallSite(expr, expectedType, emptyContext());
    }

    /** Site for a nested poly expr. */
    // package
    MethodCallSite newCallSite(InvocationMirror expr,
                               @Nullable JTypeMirror expectedType,
                               @NonNull InferenceContext infCtx) {
        return new MethodCallSite(expr, expectedType, infCtx);
    }

    InferenceContext emptyContext() {
        return new InferenceContext(ts, Collections.emptyList(), LOG);
    }

    @NonNull
    InferenceContext newContextFor(JMethodSig m) {
        return new InferenceContext(ts, m.getTypeParameters(), LOG);
    }

    public void inferLambdaOrMrefInUnambiguousContext(PolySite site) {
        Objects.requireNonNull(site);
        Objects.requireNonNull(site.getExpectedType(), "Cannot proceed without a target type");
        PolyExprMirror expr = site.getExpr();
        if (expr instanceof MethodRefMirror || expr instanceof LambdaExprMirror) {
            try {
                // this infers method invocations and stuff, I think it's recursive though
                addBoundOrDefer(emptyContext(), INVOC_LOOSE, expr, site.getExpectedType());
            } catch (ResolutionFailedException rfe) {
                rfe.getFailure().addContext(null, site, null);
                LOG.logResolutionFail(rfe.getFailure());
            }
        } else {
            throw new IllegalArgumentException(expr + " should be lambda or method ref");
        }
    }

    public @NonNull MethodCtDecl getCompileTimeDecl(MethodCallSite site) {
        if (site.getExpr().getMethodType() == null) {
            MethodCtDecl ctdecl = computeCompileTimeDecl(site);
            site.getExpr().setMethodType(ctdecl); // cache it for later
        }
        return site.getExpr().getMethodType();
    }


    /**
     * Determines the most specific applicable method for the given call site.
     *
     * <p>The returned method type may be null, in which case no method is
     * applicable (compile-time error).
     */
    private @NonNull MethodCtDecl computeCompileTimeDecl(MethodCallSite site) {

        /*
         *  The process starts with a set of candidates and refines it
         *  iteratively. Applicability/best applicability are the only
         *  ones which needs inference.
         *
         *  visible ⊇ accessible ⊇ potentially applicable ⊇ applicable ⊇ best applicable
         */
        List<JMethodSig> potentiallyApplicable = new ArrayList<>();
        for (JMethodSig it : site.getExpr().getAccessibleCandidates()) {
            if (isPotentiallyApplicable(it, site.getExpr())) {
                potentiallyApplicable.add(it);
            }
        }

        if (potentiallyApplicable.isEmpty()) {
            LOG.noApplicableCandidates(site);
            return UNRESOLVED_CTDECL;
        }

        JMethodSig bestApplicable = null;

        for (MethodResolutionPhase phase : MethodResolutionPhase.APPLICABILITY_TESTS) {
            for (JMethodSig m : potentiallyApplicable) {
                JMethodSig candidate = logInference(site, phase, m);

                bestApplicable = overloadComparator.selectMostSpecific(bestApplicable, candidate, site, phase);
            }

            if (bestApplicable != null) {
                bestApplicable = ExprOps.adaptGetClass(bestApplicable, site.getExpr().getErasedReceiverType());
                return new MethodCtDecl(bestApplicable, phase);
            }
        }

        LOG.noCompileTimeDeclaration(site);

        // no applicable method, maybe a check on a parameter failed
        if (potentiallyApplicable.size() == 1) {
            // single applicable method? assume it's the ctdecl (there can be no other one)

            // this is custom behavior for PMD to reflect more types than we know for sure,
            // a compiler would probably refuse to go further (an IDE would though)

            JMethodSig fallback = potentiallyApplicable.get(0);

            LOG.fallBackCompileTimeDecl(fallback, site); // log it

            return new MethodCtDecl(fallback, fallback.isVarargs() ? VARARGS : LOOSE);
        }

        return UNRESOLVED_CTDECL;
    }

    private @Nullable JMethodSig logInference(MethodCallSite site, MethodResolutionPhase phase, JMethodSig m) {
        LOG.startInference(m, site, phase);
        @Nullable JMethodSig candidate = instantiateMethodOrCtor(site, phase, m);
        LOG.endInference(candidate);
        return candidate;
    }

    /**
     * Determines the most specific applicable method for the given call site.
     *
     * <p>The returned method type may be {@link TypeSystem#UNRESOLVED_METHOD},
     * in which case no method is applicable (compile-time error).
     *
     * <p>The returned method type may contain un-instantiated inference
     * variables, which depend on the target type. In that case those
     * variables and their bounds will have been duplicated into the
     * inference context of the [site].
     *
     * <p>The given call site should mention information like the expected
     * return type, to help inference. This should be non-null if we're
     * in an invocation or assignment context, otherwise can be left blank.
     */
    public @NonNull MethodCtDecl determineInvocationType(MethodCallSite site) {
        MethodCtDecl ctdecl = getCompileTimeDecl(site);
        if (ctdecl == UNRESOLVED_CTDECL) { // NOPMD CompareObjectsWithEquals
            return ctdecl;
        }

        return finishInstantiation(site, ctdecl);
    }

    private MethodCtDecl finishInstantiation(MethodCallSite site, MethodCtDecl ctdecl) {
        JMethodSig m = ctdecl.getMethodType();
        InvocationMirror expr = site.getExpr();

        if (isReturnTypeFinished(m) && site.getExpectedType() == null) {
            assert assertReturnIsGround(m);

            expr.setInferredType(m.getReturnType());

            if (site.areAllArgsRelevantToApplicability()) {
                LOG.skipInstantiation(m, site);
                // then all have been inferred
                return ctdecl;
            }
        }

        // start the inference over with the original method, including
        // arguments that are not pertinent to applicability (lambdas)
        // to instantiate all tvars

        JMethodSig inst = logInference(site, ctdecl.getResolvePhase().asInvoc(), ctdecl.getMethodType().internalApi().adaptedMethod());
        return inst == null ? UNRESOLVED_CTDECL : new MethodCtDecl(inst, ctdecl.getResolvePhase());
    }

    private boolean isReturnTypeFinished(JMethodSig m) {
        return !isAdaptedConsType(m)
            // this means that the invocation type cannot be affected by context type
            && !TypeOps.mentionsAnyTvar(m.internalApi().originalMethod().getReturnType(), m.getTypeParameters());
    }

    private boolean isAdaptedConsType(JMethodSig m) {
        return m.isConstructor()
            && m.getDeclaringType().isClassOrInterface()
            && m.getDeclaringType().isGeneric()
            && !m.getDeclaringType().isRaw();
    }

    // this is skipped when running without assertions
    private boolean assertReturnIsGround(JMethodSig t) {
        subst(t.getReturnType(), var -> {
            assert !(var instanceof JInferenceVar) : "Expected a ground type " + t;
            return var;
        });

        assert !TypeOps.mentionsAnyTvar(t.getReturnType(), t.getTypeParameters())
            : " Method return type mentions type params: " + t;

        return true;
    }

    private JMethodSig instantiateMethodOrCtor(MethodCallSite site, MethodResolutionPhase phase, JMethodSig m) {
        return site.getExpr() instanceof CtorInvocationMirror ? instantiateConstructor(m, site, phase)
                                                              : instantiateMethod(m, site, phase);
    }


    /**
     * Infer type arguments for the given method at the method call.
     * Returns null if no instantiations exist, ie the method is not
     * applicable.
     *
     * @param m     Candidate method
     * @param site  Descriptor of the context of the call.
     * @param phase Phase in which the method is reviewed
     */
    private JMethodSig instantiateMethod(JMethodSig m,
                                         MethodCallSite site,
                                         MethodResolutionPhase phase) {
        try {
            if (phase.requiresVarargs() && !m.isVarargs()) {
                assert !phase.isInvocation()
                    // this would mean isPotentiallyApplicable is broken
                    : "Should not have proceeded to invocation";
                return null;
            }
            return instantiateMaybeNoInfer(m, site, phase);
        } catch (ResolutionFailedException e) {
            ResolutionFailure failure = e.getFailure();
            failure.addContext(m, site, phase);
            LOG.logResolutionFail(failure);
            // preserve method if we were in invocation
            // TODO what if it's not ground?
            return phase.isInvocation() ? failure.getFailedMethod()
                                        : null;
        }
    }

    private JMethodSig instantiateConstructor(JMethodSig cons,
                                              MethodCallSite site,
                                              MethodResolutionPhase phase) {

        CtorInvocationMirror expr = (CtorInvocationMirror) site.getExpr();

        JMethodSig adapted = expr.isDiamond() || expr.getNewType().isParameterizedType()
                             ? adaptGenericConstructor(cons, expr.getNewType())
                             : cons;

        return instantiateMethod(adapted, site, phase);
    }

    /**
     * Transform the constructor of a generic class so that its type parameters
     * mention the type params of the declaring class. This enables diamond
     * inference, we just treat the class type params to infer as
     * additional inference variables.
     *
     * <p>E.g. for
     *
     * {@code class ArrayList<T> { ArrayList() {} } }
     *
     * the constructor is represented as a method type:
     *
     * {@code <T> ArrayList<T> new() }
     *
     * the return type being that of the created instance.
     */
    private static JMethodSig adaptGenericConstructor(JMethodSig cons, JClassType newType) {
        assert cons.isConstructor() : cons + " should be a constructor";

        if (cons.getDeclaringType().isArray()) {
            // array methods do not need to be adapted and don't support it
            return cons;
        }

        // replace the return type so that anonymous class ctors return the supertype
        JMethodSig adaptedSig = cons.internalApi().withReturnType(newType).internalApi().markAsAdapted();

        List<JTypeVar> newTypeFormals = newType.getFormalTypeParams();
        if (newTypeFormals.isEmpty()) {
            // non-generic type
            return adaptedSig;
        } else {
            // else transform the constructor to add the type parameters
            // of the constructed type
            List<JTypeVar> tparams = CollectionUtil.concatView(cons.getTypeParameters(), newTypeFormals);

            // type parameters are not part of the adapted signature, so that when we reset
            // the signature for invocation inference, we don't duplicate new type parameters
            return adaptedSig.internalApi().withTypeParams(tparams);
        }
    }

    /**
     * Catch the easy cases before starting inference.
     */
    private JMethodSig instantiateMaybeNoInfer(JMethodSig m, MethodCallSite site, MethodResolutionPhase phase) {

        if (!m.isGeneric()) {
            // non-generic methods may mention explicit type arguments
            // for compatibility, they must be ignored.

            // check that the arguments are conformant
            // the inference context is empty because all param types are ground.
            addArgsConstraints(emptyContext(), m, site, phase);
            return m;
        }

        InvocationMirror expr = site.getExpr();
        List<JTypeMirror> explicitTargs = expr.getExplicitTypeArguments();

        if (!explicitTargs.isEmpty()) {
            // we have explicit type arguments
            List<JTypeVar> tparams = m.getTypeParameters();

            if (tparams.size() != explicitTargs.size()) {
                // normally checked by isPotentiallyApplicable
                throw ResolutionFailedException.incompatibleTypeParamCount(LOG, site.getExpr(), m, explicitTargs.size(), tparams.size());
            }

            for (int i = 0; i < tparams.size(); i++) {
                List<JTypeMirror> bounds = asList(tparams.get(i).getUpperBound());
                JTypeMirror explicit = explicitTargs.get(i);

                for (JTypeMirror bound : bounds) {
                    JTypeMirror sub = bound.subst(Substitution.mapping(tparams, explicitTargs));
                    if (!explicit.isSubtypeOf(sub, true)) {
                        throw ResolutionFailedException.incompatibleBound(LOG, explicit, sub, expr.getExplicitTargLoc(i));
                    }
                }
            }


            JMethodSig subst = m.subst(Substitution.mapping(tparams, explicitTargs));

            // check that the arguments are conformant
            // the inference context is empty because all param types are ground.
            addArgsConstraints(emptyContext(), subst, site, phase);

            return subst;
        }


        return instantiateImpl(m, site, phase);
    }

    /**
     * Perform actual inference. If the method is return-type-polymorphic,
     * then we delegate the solving to the call site's inference context,
     * which knows more, however we add inference vars and their constraints
     * to it.
     */
    private JMethodSig instantiateImpl(JMethodSig m, MethodCallSite site, MethodResolutionPhase phase) {

        InferenceContext infCtx = newContextFor(m); // b0
        LOG.ctxInitialization(infCtx, m);

        try {

            if (phase.isInvocation()) {
                LOG.startReturnChecks();
                // Add return constraints before argument constraints -> this pushes the target type down
                JTypeMirror actualResType = addReturnConstraints(infCtx, m, site); // b3
                LOG.endReturnChecks();

                m = m.internalApi().withReturnType(actualResType);
            }

            addArgsConstraints(infCtx, m, site, phase); // c
            infCtx.incorporate(); // b2

            if (phase.isInvocation()) {

                boolean shouldPropagate = shouldPropagateOutwards(m.getReturnType(), site, infCtx);

                //propagate outwards if needed
                if (shouldPropagate) {
                    // propagate inference context outwards and exit
                    // the outer context will solve the variables and call listeners
                    // of this context
                    LOG.propagateAndAbort(infCtx, site.getInferenceContext());
                    infCtx.duplicateInto(site.getInferenceContext());
                    // TODO there may be stuck terms
                    return infCtx.mapToIVars(m);
                }
            }

            // TODO there may be stuck terms
            infCtx.solve();

            // instantiate vars and return
            return infCtx.finalGround(infCtx.mapToIVars(m));
        } finally {
            infCtx.callListeners();
        }
    }


    private boolean shouldPropagateOutwards(JTypeMirror resultType, MethodCallSite target, InferenceContext inferenceContext) {

        return !target.getInferenceContext().isEmpty()  //enclosing context is a generic method
            && !inferenceContext.isGround(resultType)   //return type contains inference vars
            && !(resultType instanceof JInferenceVar    //no eager instantiation is required (as per 18.5.2)
            && needsEagerInstantiation((JInferenceVar) inferenceContext.mapToIVars(resultType), target.getExpectedType(), inferenceContext));
    }

    /**
     * Add more constraints on the inference vars based on the expected
     * return type at the call site. This is described in
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-18.html#jls-18.5.2.1
     *
     * under "Let B3 be the bound set derived from B2 as follows."
     *
     * <p>This binds the ivars of this context to those of the outer context.
     */
    private JTypeMirror addReturnConstraints(InferenceContext infCtx, JMethodSig m, MethodCallSite site) {

        /*
            Remember: calling stuff like isConvertible or isSubtype
            adds constraints on the type variables that are found there.
         */

        JTypeMirror resultType = infCtx.mapToIVars(m.getReturnType());
        InferenceContext outerInfCtx = site.getInferenceContext();

        if (!infCtx.isGround(resultType) && !outerInfCtx.isEmpty() && resultType instanceof JClassType) {
            // TODO we need to link the capture variables to the outer context
            JClassType resClass = capture((JClassType) resultType);
            resultType = resClass;

            for (JTypeMirror targ : resClass.getTypeArgs()) {
                if (targ instanceof JTypeVar && ((JTypeVar) targ).isCaptured()) {
                    infCtx.addVar((JTypeVar) targ);
                }
            }
        }

        JTypeMirror mapped = infCtx.mapToIVars(resultType);
        JTypeMirror actualRes = site.getExpectedType();
        if (actualRes == null) {
            actualRes = ts.OBJECT;
        }

        if (mapped instanceof JInferenceVar) {
            JInferenceVar retVar = (JInferenceVar) mapped;
            if (needsEagerInstantiation(retVar, actualRes, infCtx)) {
                infCtx.solve(retVar);
                infCtx.callListeners();
                if (!isConvertible(retVar.getInst(), actualRes, true)) {
                    actualRes = ts.OBJECT;
                }
            } else if (actualRes.isPrimitive()) {
                actualRes = actualRes.box();
            }
        }

        if (!isConvertible(mapped, outerInfCtx.mapToIVars(actualRes), true)) {
            throw ResolutionFailedException.incompatibleReturn(LOG, site.getExpr(), mapped, actualRes);
        }

        return resultType; // return to preserve the capture
    }


    /**
     * Returns true if the inference var needs to be instantiated eagerly,
     * as described in JLS§18.5.2.1. (Poly Method Invocation Compatibility)
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-18.html#jls-18.5.2.1
     *
     * @param alpha  Inference var
     * @param t      Target type of the invocation
     * @param infCtx Inference context
     */
    private boolean needsEagerInstantiation(JInferenceVar alpha, JTypeMirror t, InferenceContext infCtx) {
        if (t == null) {
            return false;
        }

        if (t.isPrimitive()) {
            // T is a primitive type, and one of the primitive wrapper classes is an instantiation,
            // upper bound, or lower bound for alpha in B2.

            for (JTypeMirror b : alpha.getBounds(BoundKind.ALL)) {
                if (b.isBoxedPrimitive()) {
                    return true;
                }
            }
            return false;
        }

        // T is a reference type, but is not a wildcard-parameterized type, and either

        if (!t.isPrimitive() && isNotWilcardParameterized(t)) {
            // i) B2 contains a bound of one of the forms alpha = S or S <: alpha,
            //    where S is a wildcard-parameterized type, or
            for (JTypeMirror s : alpha.getBounds(BoundKind.EQ_LOWER)) {
                if (!isNotWilcardParameterized(s)) {
                    return true;
                }
            }

            // ii) B2 contains two bounds of the forms S1 <: alpha and S2 <: alpha,
            // where S1 and S2 have supertypes that are two different
            // parameterizations of the same generic class or interface.

            for (JTypeMirror aLowerBound : alpha.getBounds(BoundKind.LOWER)) {
                for (JTypeMirror anotherLowerBound : alpha.getBounds(BoundKind.LOWER)) {
                    if (aLowerBound != anotherLowerBound // NOPMD CompareObjectsWithEquals
                        && infCtx.isGround(aLowerBound)
                        && infCtx.isGround(anotherLowerBound)
                        && commonSuperWithDiffParameterization(aLowerBound, anotherLowerBound)) {
                        return true;
                    }
                }
            }
        }

        // T is a parameterization of a generic class or interface, G,
        // and B2 contains a bound of one of the forms alpha = S or S <: alpha,
        // where there exists no type of the form G<...> that is a
        // supertype of S, but the raw type G is a supertype of S

        if (t.isParameterizedType()) {
            for (JTypeMirror b : alpha.getBounds(BoundKind.EQ_LOWER)) {
                JTypeMirror sup = b.getAsSuper(((JClassType) t).getSymbol());
                if (sup != null && sup.isRaw()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean commonSuperWithDiffParameterization(JTypeMirror t, JTypeMirror s) {
        JTypeMirror lubResult = ts.lub(listOf(t, s));
        if (lubResult == ts.NULL_TYPE || lubResult == ts.OBJECT) {
            return false;
        }
        for (JTypeMirror sup : asList(lubResult)) {
            if (sup.isParameterizedType()) {
                JClassSymbol sym = ((JClassType) sup).getSymbol();
                JTypeMirror asSuperOfT = t.getAsSuper(sym);
                JTypeMirror asSuperOfS = s.getAsSuper(sym);
                if (!asSuperOfS.equals(asSuperOfT)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Generate bounds on the ivars based on the expected/actual types
     * of the arguments to the call. This is described in
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-18.html#jls-18.5.1
     *
     * as being the set C.
     *
     * <p>For invocation applicability inference (phases {@link MethodResolutionPhase#STRICT STRICT}
     * through {@link MethodResolutionPhase#VARARGS VARARGS}), only arguments
     * that are {@linkplain ExprOps#isPertinentToApplicability(ExprMirror, JMethodSig, JTypeMirror, InvocationMirror)
     * pertinent to applicability}
     * are considered. Arguments like lambdas do not influence the applicability
     * check beyond checking their basic 'shape' (number of params)
     * to check that the method is {@linkplain #isPotentiallyApplicable(JMethodSig, InvocationMirror) potentially
     * applicable}, which is done very much earlier.
     * So they don't add constraints during those first phases.
     *
     * <p>When we have found an applicable method and are instantiating it
     * (phases {@link MethodResolutionPhase#INVOC_STRICT INVOC_STRICT} through {@link
     * MethodResolutionPhase#INVOC_VARARGS INVOC_VARARGS}),
     * all arguments are considered so as to yield sharper bounds.
     *
     * @param infCtx Inference context
     * @param m      Tested method
     * @param site   Invocation expression
     * @param phase  Phase (determines what constraints are allowed)
     */
    private void addArgsConstraints(InferenceContext infCtx, JMethodSig m, MethodCallSite site, MethodResolutionPhase phase) {
        LOG.startArgsChecks();

        InvocationMirror expr = site.getExpr();

        boolean varargsRequired = phase.requiresVarargs();

        if (!varargsRequired && m.getArity() != expr.getArgumentCount()) {
            throw ResolutionFailedException.incompatibleArity(LOG, expr.getArgumentCount(), m.getArity(), expr.getLocation());
        }

        List<JTypeMirror> fs = m.getFormalParameters();

        @Nullable
        JArrayType varargsParam = varargsRequired && m.isVarargs() ? (JArrayType) fs.get(fs.size() - 1) : null;
        int lastP = varargsParam == null ? fs.size() : fs.size() - 1;

        List<ExprMirror> args = expr.getArgumentExpressions();

        for (int i = 0; i < lastP; i++) {
            ExprMirror ei = args.get(i);


            if (phase.isInvocation() || isPertinentToApplicability(ei, m, fs.get(i), expr)) {
                JTypeMirror stdType = ei.getStandaloneType();
                JTypeMirror fi = infCtx.mapToIVars(fs.get(i));

                LOG.startArg(i, ei, fi);

                if (!phase.canBox()) {
                    // these are cases where applicability is impossible (in strict ctx)
                    if (stdType != null && stdType.isPrimitive() != fi.isPrimitive()) {
                        throw ResolutionFailedException.incompatibleFormal(LOG, ei, stdType, fi);
                    }
                }

                addBoundOrDefer(infCtx, phase, ei, fi);

                LOG.endArg();
            } else {
                // then the final reinvocation is necessary
                site.setSomeArgsAreNotPertinent();
                LOG.skipArgAsNonPertinent(i, ei);
            }
        }

        if (varargsRequired && varargsParam != null) {
            JTypeMirror varargsComponent = infCtx.mapToIVars(varargsParam.getComponentType());

            // possibly some varargs arguments left
            for (int i = lastP; i < args.size(); i++) {
                ExprMirror ei = args.get(i);


                if (phase.isInvocation() || isPertinentToApplicability(ei, m, varargsComponent, expr)) {
                    LOG.startArg(i, ei, varargsComponent);
                    addBoundOrDefer(infCtx, phase, ei, varargsComponent);
                    LOG.endArg();
                } else {
                    LOG.skipArgAsNonPertinent(i, ei);
                }
            }
        }
        LOG.endArgsChecks();
    }

    /**
     * This corresponds to the attribution of expression compatibility
     * constraints in https://docs.oracle.com/javase/specs/jls/se9/html/jls-18.html#jls-18.2.1
     * although it's not implemented as described.
     *
     * See {@link ExprCheckHelper#isCompatible(JTypeMirror, ExprMirror)}.
     */
    private void addBoundOrDefer(InferenceContext infCtx, MethodResolutionPhase phase, ExprMirror arg, JTypeMirror formalType) {
        ExprChecker exprChecker =
            (ctx, exprType, formalType1) -> checkConvertibleOrDefer(ctx, exprType, formalType1, arg, phase);

        ExprCheckHelper helper = new ExprCheckHelper(infCtx, phase, exprChecker, this);
        helper.isCompatible(formalType, arg);
    }

    /**
     * Add a compatibility constraint between an exprType and a formalType.
     * This asserts {@code exprType <: formalType}, the arg parameter is only
     * used for reporting.
     *
     * <p>This method is called back to by {@link ExprCheckHelper#isCompatible(JTypeMirror, ExprMirror)}.
     */
    void checkConvertibleOrDefer(InferenceContext infCtx, JTypeMirror exprType, JTypeMirror formalType, ExprMirror arg, MethodResolutionPhase phase) {
        if (!infCtx.isGround(formalType) || !infCtx.isGround(exprType)) {
            // defer the check
            infCtx.addInstantiationListener(setOf(formalType, exprType), solvedCtx -> checkConvertibleOrDefer(solvedCtx, exprType, formalType, arg, phase));
        }

        JTypeMirror groundE = infCtx.ground(exprType);
        JTypeMirror groundF = infCtx.ground(formalType);

        // This method call does all the work of adding constraints
        // If groundE or groundF are in fact not ground, then constraints
        // on the ivars that appear within them are implicitly added during
        // the subtyping check. The call then returns true and we return
        // normally

        // If they are ground, then they must conform to each other else
        // the exception stops the resolution process.
        if (!isConvertible(groundE, groundF, phase.canBox())) {
            throw ResolutionFailedException.incompatibleFormal(LOG, arg, groundE, groundF);
        }

    }

    /**
     * Convertibility in *invocation* context.
     *
     * https://docs.oracle.com/javase/specs/jls/se8/html/jls-5.html#jls-5.3
     */
    static boolean isConvertible(JTypeMirror exprType, JTypeMirror formalType, boolean canBox) {
        if (exprType == formalType) {
            // fast path
            return true;
        }

        if (canBox && exprType.isPrimitive() ^ formalType.isPrimitive()) {
            // then boxing conversions may be useful
            return exprType.box().isSubtypeOf(formalType.box(), true)
                || exprType.unbox().isSubtypeOf(formalType.unbox(), true);
        }

        // unchecked conversion is allowed even in STRICT
        return exprType.isSubtypeOf(formalType, true);
    }

    /**
     * Returns true if the method is potentially applicable to the invocation
     * expression expr, as specified in JLS§15.12.2.1.
     *
     * https://docs.oracle.com/javase/specs/jls/se9/html/jls-15.html#jls-15.12.1
     *
     * <p>This assumes the name of the method matches the expression, and
     * the method is accessible.
     *
     * @param m    Method to test
     * @param expr Invocation expression
     */
    private boolean isPotentiallyApplicable(JMethodSig m, InvocationMirror expr) {

        if (m.isGeneric()
            && !expr.getExplicitTypeArguments().isEmpty()
            && expr.getExplicitTypeArguments().size() != m.getTypeParameters().size()) {
            return false;
        }

        List<ExprMirror> args = expr.getArgumentExpressions();

        if (!m.isVarargs()) {
            // we can avoid computing formal parameters by using getArity here
            if (args.size() != m.getArity()) {
                return false;
            }

            List<JTypeMirror> fs = m.getFormalParameters();
            for (int i = 0; i < args.size(); i++) {
                if (!exprOps.isPotentiallyCompatible(m, args.get(i), fs.get(i))) {
                    return false;
                }
            }

        } else {
            List<JTypeMirror> fs = m.getFormalParameters();

            // test first n-1 params
            int varargIdx = fs.size() - 1;
            for (int i = 0; i < varargIdx; i++) {
                if (i >= args.size()) {
                    // not enough arguments
                    return false;
                }

                if (!exprOps.isPotentiallyCompatible(m, args.get(i), fs.get(i))) {
                    return false;
                }
            }

            if (args.size() == varargIdx - 1) {
                return true;
            }

            if (args.size() == fs.size()) {
                ExprMirror last = args.get(varargIdx);
                JArrayType t = (JArrayType) fs.get(varargIdx);

                return exprOps.isPotentiallyCompatible(m, last, t)
                    || exprOps.isPotentiallyCompatible(m, last, t.getComponentType());
            }

            if (args.size() > fs.size()) {
                JTypeMirror t = ((JArrayType) fs.get(varargIdx)).getComponentType();
                for (int i = varargIdx; i < args.size(); i++) {
                    if (!exprOps.isPotentiallyCompatible(m, args.get(i), t)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }


}
