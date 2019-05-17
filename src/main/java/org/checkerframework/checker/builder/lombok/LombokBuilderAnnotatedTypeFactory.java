package org.checkerframework.checker.builder.lombok;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import org.checkerframework.checker.builder.CalledMethodsAnnotatedTypeFactory;
import org.checkerframework.checker.builder.CalledMethodsUtil;
import org.checkerframework.checker.builder.TypesafeBuilderQualifierHierarchy;
import org.checkerframework.checker.builder.qual.CalledMethods;
import org.checkerframework.checker.builder.qual.CalledMethodsBottom;
import org.checkerframework.checker.builder.qual.CalledMethodsPredicate;
import org.checkerframework.checker.builder.qual.CalledMethodsTop;
import org.checkerframework.checker.builder.qual.ReturnsReceiver;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy.MultiGraphFactory;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;

/** Responsible for placing appropriate annotations on Lombok builders. */
public class LombokBuilderAnnotatedTypeFactory extends BaseAnnotatedTypeFactory
    implements CalledMethodsAnnotatedTypeFactory {

  private final AnnotationMirror TOP, BOTTOM;

  public LombokBuilderAnnotatedTypeFactory(BaseTypeChecker checker) {
    super(checker);
    TOP = AnnotationBuilder.fromClass(elements, CalledMethodsTop.class);
    BOTTOM = AnnotationBuilder.fromClass(elements, CalledMethodsBottom.class);
    this.postInit();
  }

  @Override
  protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
    return new LinkedHashSet<>(
        Arrays.asList(
            CalledMethodsTop.class,
            CalledMethods.class,
            CalledMethodsBottom.class,
            CalledMethodsPredicate.class));
  }

  /** Creates a @CalledMethods annotation whose values are the given strings. */
  public AnnotationMirror createCalledMethods(final String... val) {
    return CalledMethodsUtil.createCalledMethodsImpl(TOP, processingEnv, val);
  }

  /**
   * Wrapper to accept a List of Strings instead of an array if that's convenient at the call site.
   */
  public AnnotationMirror createCalledMethods(final List<String> valList) {
    String[] vals = valList.toArray(new String[0]);
    return createCalledMethods(vals);
  }

  @Override
  public TreeAnnotator createTreeAnnotator() {
    return new ListTreeAnnotator(super.createTreeAnnotator(), new LombokBuilderTreeAnnotator(this));
  }

  private class LombokBuilderTreeAnnotator extends TreeAnnotator {

    public LombokBuilderTreeAnnotator(AnnotatedTypeFactory atypeFactory) {
      super(atypeFactory);
    }

    @Override
    public Void visitClass(final ClassTree tree, final AnnotatedTypeMirror type) {
      System.out.println(tree);
      return super.visitClass(tree, type);
    }

    @Override
    public Void visitMethod(final MethodTree node, final AnnotatedTypeMirror type) {
      // TODO: Lombok @Generated builder classes should have the following type annotations placed
      // automatically:
      // on setters: @ReturnsReceiver as a decl annotation
      // on the finalizer (build()) method: @CalledMethods(A), where A is the set of lombok.@NonNull
      // fields

      // Was the class that contains this method generated by Lombok?
      boolean lombokGenerated = false;

      ClassTree enclosingClass = TreeUtils.enclosingClass(getPath(node));

      for (AnnotationTree atree : enclosingClass.getModifiers().getAnnotations()) {
        AnnotationMirror anm = TreeUtils.annotationFromAnnotationTree(atree);
        if (AnnotationUtils.areSameByClass(anm, lombok.Generated.class)) {
          lombokGenerated = true;
        }
      }

      // System.out.println("was " + enclosingClass.getSimpleName().toString() + " generated by
      // lombok? " + lombokGenerated);

      // if this class was generated by Lombok, then we know:
      // - is this a setter method? If so, we need to add an @ReturnsReceiver annotation
      // - is this the finalizer method? If so, we need to add an @CalledMethods annotation
      //   to its receiver
      if (lombokGenerated) {
        // get the name of the method
        String methodName = node.getName().toString();

        boolean isFinalizer = "build".equals(methodName);

        // get the names of the fields
        List<String> fieldNames = new ArrayList<>();
        for (Tree member : enclosingClass.getMembers()) {
          if (member.getKind() == Tree.Kind.VARIABLE) {
            VariableTree fieldTree = (VariableTree) member;
            if (isFinalizer) {
              // for finalizers, only add the names of fields annotated with lombok.NonNull
              for (AnnotationTree atree : fieldTree.getModifiers().getAnnotations()) {
                AnnotationMirror anm = TreeUtils.annotationFromAnnotationTree(atree);
                if (AnnotationUtils.areSameByClass(anm, lombok.NonNull.class)) {
                  fieldNames.add(fieldTree.getName().toString());
                }
              }
            } else {
              // for other methods, we only care if this is a setter (which we will deduce by
              // checking if the method name is also a field name
              fieldNames.add(fieldTree.getName().toString());
            }
          }
        }

        if (isFinalizer) {
          // if its a finalizer, add the @CalledMethods annotation with the field names
          // to the receiver
          VariableTree receiverTree = node.getReceiverParameter();
          AnnotationMirror newCalledMethodsAnno = createCalledMethods(fieldNames);
          System.out.println(
              "adding this annotation "
                  + newCalledMethodsAnno
                  + " to the receiver of this method "
                  + methodName);
          getAnnotatedType(receiverTree).addAnnotation(newCalledMethodsAnno);
        } else if (fieldNames.contains(methodName)) {
          AnnotationMirror newReturnsReceiverAnno =
              AnnotationBuilder.fromClass(elements, ReturnsReceiver.class);
          System.out.println("adding @ReturnsReceiver to this method " + methodName);
          // gotta use this weird formulation b/c we're adding a declaration annotation, not
          // a type annotation
          type.getAnnotations().add(newReturnsReceiverAnno);
        }
      }
      return super.visitMethod(node, type);
    }
  }

  @Override
  public MultiGraphQualifierHierarchy createQualifierHierarchy(MultiGraphFactory factory) {
    return new TypesafeBuilderQualifierHierarchy(factory, TOP, BOTTOM, this);
  }
}
