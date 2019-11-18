package org.checkerframework.checker.objectconstruction.framework;

import com.sun.source.tree.NewClassTree;
import org.checkerframework.framework.type.AnnotatedTypeMirror;

public interface FrameworkSupport {

  /**
   * For a toBuilder routine, we know that the returned Builder effectively has had all the required
   * setters invoked. Add a CalledMethods annotation capturing this fact.
   *
   * @param t
   */
  public void handleToBulder(AnnotatedTypeMirror.AnnotatedExecutableType t);

  /**
   * determine the required properties and add a corresponding @CalledMethods annotation to the
   * receiver
   *
   * @param t
   */
  public void handleBuilderBuildMethod(AnnotatedTypeMirror.AnnotatedExecutableType t);

  /**
   * handle a constructor call inside a generated toBuilder method
   *
   * @param tree
   * @param type
   */
  public void handleConstructor(NewClassTree tree, AnnotatedTypeMirror type);
}
