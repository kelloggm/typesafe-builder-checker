package org.checkerframework.checker.objectconstruction;

import com.sun.source.tree.Tree;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import org.checkerframework.checker.calledmethods.CalledMethodsAnnotatedTypeFactory;
import org.checkerframework.checker.calledmethods.qual.CalledMethods;
import org.checkerframework.checker.calledmethods.qual.CalledMethodsBottom;
import org.checkerframework.checker.calledmethods.qual.CalledMethodsPredicate;
import org.checkerframework.checker.mustcall.MustCallAnnotatedTypeFactory;
import org.checkerframework.checker.mustcall.MustCallChecker;
import org.checkerframework.checker.mustcall.qual.MustCall;
import org.checkerframework.checker.mustcall.qual.MustCallChoice;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.objectconstruction.MustCallInvokedChecker.LocalVarWithTree;
import org.checkerframework.com.google.common.collect.ImmutableSet;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.value.ValueCheckerUtils;
import org.checkerframework.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.dataflow.expression.LocalVariable;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

/**
 * The annotated type factory for the object construction checker. Primarily responsible for the
 * subtyping rules between @CalledMethod annotations.
 */
public class ObjectConstructionAnnotatedTypeFactory extends CalledMethodsAnnotatedTypeFactory {

  /**
   * Default constructor matching super. Should be called automatically.
   *
   * @param checker the checker associated with this type factory
   */
  public ObjectConstructionAnnotatedTypeFactory(final BaseTypeChecker checker) {
    super(checker);
    this.postInit();
  }

  @Override
  protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
    return getBundledTypeQualifiers(
        CalledMethods.class, CalledMethodsBottom.class, CalledMethodsPredicate.class);
  }

  /**
   * Creates a @CalledMethods annotation whose values are the given strings.
   *
   * @param val the methods that have been called
   * @return an annotation indicating that the given methods have been called
   */
  public AnnotationMirror createCalledMethods(final String... val) {
    return createAccumulatorAnnotation(Arrays.asList(val));
  }

  @Override
  public void postAnalyze(ControlFlowGraph cfg) {
    if (checker.hasOption(ObjectConstructionChecker.CHECK_MUST_CALL)) {
      MustCallInvokedChecker mustCallInvokedChecker =
          new MustCallInvokedChecker(this, (ObjectConstructionChecker) this.checker, this.analysis);
      mustCallInvokedChecker.checkMustCallInvoked(cfg);
    }
    super.postAnalyze(cfg);
  }

  /**
   * Tries to look up the value of the local variable to which the tree was assigned
   * in the Must Call store passed. If it is not found, it returns the default type for
   * the class (i.e. it fails safe with respect to the must-call type.
   */
  public List<String> getMustCallValue(ImmutableSet<LocalVarWithTree> varSet, @Nullable CFStore mcStore) {
    MustCallAnnotatedTypeFactory mustCallAnnotatedTypeFactory =
            getTypeFactoryOfSubchecker(MustCallChecker.class);
    AnnotationMirror mcLub = mustCallAnnotatedTypeFactory.BOTTOM;
    for (LocalVarWithTree lvt : varSet) {
      AnnotationMirror mcAnno = null;
      LocalVariable local = lvt.localVar;
      CFValue value = /*mcStore == null ? null :*/ mcStore.getValue(local);
      if (value != null) {
        mcAnno = value.getAnnotations().stream()
                .filter(anno -> AnnotationUtils.areSameByClass(anno, MustCall.class))
                .findAny().get();
      }
      // If it wasn't in the store, fall back to the default must-call type for the class.
      if (mcAnno == null) {
        mcAnno = mustCallAnnotatedTypeFactory.getAnnotatedType(
                TypesUtils.getTypeElement(local.getType()))
                .getAnnotationInHierarchy(mustCallAnnotatedTypeFactory.TOP);
        }
      mcLub = mustCallAnnotatedTypeFactory.getQualifierHierarchy().leastUpperBound(mcLub, mcAnno);
    }

    return getMustCallValues(mcLub);
  }

  /** Returns the String value of @MustCall annotation of the type of {@code tree}. */
  List<String> getMustCallValue(Tree tree) {
    MustCallAnnotatedTypeFactory mustCallAnnotatedTypeFactory =
        getTypeFactoryOfSubchecker(MustCallChecker.class);
    AnnotationMirror mustCallAnnotation =
        mustCallAnnotatedTypeFactory.getAnnotatedType(tree).getAnnotation(MustCall.class);

    return getMustCallValues(mustCallAnnotation);
  }

  /**
   * Returns the String value of @MustCall annotation declared on the class type of {@code element}.
   *
   * <p>If possible, prefer {@link #getMustCallValue(Tree)}, which will account for flow-sensitive
   * refinement.
   */
  List<String> getMustCallValue(Element element) {
    MustCallAnnotatedTypeFactory mustCallAnnotatedTypeFactory =
        getTypeFactoryOfSubchecker(MustCallChecker.class);
    AnnotatedTypeMirror mustCallAnnotatedType =
        mustCallAnnotatedTypeFactory.getAnnotatedType(element);
    AnnotationMirror mustCallAnnotation = mustCallAnnotatedType.getAnnotation(MustCall.class);

    return getMustCallValues(mustCallAnnotation);
  }

  private List<String> getMustCallValues(AnnotationMirror mustCallAnnotation) {
    List<String> mustCallValues =
        (mustCallAnnotation != null)
            ? ValueCheckerUtils.getValueOfAnnotationWithStringArgument(mustCallAnnotation)
            : Collections.emptyList();
    return mustCallValues;
  }

  /**
   * Returns true if the type of the tree includes a must-call annotation. Note that this
   * method may not consider dataflow, and is only safe to use on declarations, such as
   * method invocation trees or parameter trees. Use {@link #getMustCallValue(ImmutableSet, CFStore)}
   * (and check for emptiness) if you are trying to determine whether a local variable has must-call
   * obligations.
   */
  boolean hasMustCall(Tree t) {
    return !getMustCallValue(t).isEmpty();
  }

  /**
   * Returns true iff the unconnected sockets checker determined that this tree represents a socket
   * that is definitely unconnected.
   *
   * @param tree a tree
   * @return true iff the unconnected sockets checker proved that tree represents a
   *     definitely-unconnected socket
   */
  public boolean isUnconnectedSocket(Tree tree) {
    return false; // TODO get rid of this?
  }

  boolean hasMustCallChoice(Tree tree) {
    Element elt = TreeUtils.elementFromTree(tree);
    return hasMustCallChoice(elt);
  }

  boolean hasMustCallChoice(Element elt) {
    MustCallAnnotatedTypeFactory mustCallAnnotatedTypeFactory =
        getTypeFactoryOfSubchecker(MustCallChecker.class);
    return mustCallAnnotatedTypeFactory.getDeclAnnotationNoAliases(elt, MustCallChoice.class)
        != null;
  }
}
