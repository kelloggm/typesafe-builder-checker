package org.checkerframework.checker.objectconstruction;

import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.calledmethods.qual.CalledMethods;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.objectconstruction.qual.Owning;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.block.Block;
import org.checkerframework.dataflow.cfg.block.BlockImpl;
import org.checkerframework.dataflow.cfg.block.ConditionalBlock;
import org.checkerframework.dataflow.cfg.block.ExceptionBlock;
import org.checkerframework.dataflow.cfg.block.ExceptionBlockImpl;
import org.checkerframework.dataflow.cfg.block.RegularBlockImpl;
import org.checkerframework.dataflow.cfg.block.SingleSuccessorBlock;
import org.checkerframework.dataflow.cfg.block.SpecialBlock;
import org.checkerframework.dataflow.cfg.block.SpecialBlockImpl;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.dataflow.cfg.node.ReturnNode;
import org.checkerframework.dataflow.cfg.node.TypeCastNode;
import org.checkerframework.dataflow.expression.LocalVariable;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.TreeUtils;

/**
 * Checks that all methods in {@link org.checkerframework.checker.mustcall.qual.MustCall} object
 * types are invoked before the corresponding objects become unreachable
 */
class MustCallInvokedChecker {

  /** {@code @MustCall} errors reported thus far, to avoid duplicates */
  private final Set<LocalVarWithAssignTree> reportedMustCallErrors = new HashSet<>();

  private final ObjectConstructionAnnotatedTypeFactory typeFactory;

  private final BaseTypeChecker checker;

  private final CFAnalysis analysis;

  MustCallInvokedChecker(
      ObjectConstructionAnnotatedTypeFactory typeFactory,
      BaseTypeChecker checker,
      CFAnalysis analysis) {
    this.typeFactory = typeFactory;
    this.checker = checker;
    this.analysis = analysis;
  }
  /**
   * This function traverses the given method CFG and reports an error if "f" isn't called on any
   * local variable node whose class type has @MustCall(f) annotation before the variable goes out
   * of scope. The traverse is a standard worklist algorithm. Worklist and visited entries are
   * BlockWithLocals objects that contain a set of (LocalVariableNode, Tree) pairs for each block. A
   * pair (n, T) represents a local variable node "n" and the latest AssignmentTree "T" that assigns
   * a value to "n".
   *
   * @param cfg the control flow graph of a method
   */
  void mustCallTraverse(ControlFlowGraph cfg) {
    // add any owning parameters to initial set
    Set<LocalVarWithAssignTree> init = new HashSet<>();
    UnderlyingAST underlyingAST = cfg.getUnderlyingAST();
    if (underlyingAST instanceof UnderlyingAST.CFGMethod) {
      // TODO what about lambdas?
      MethodTree method = ((UnderlyingAST.CFGMethod) underlyingAST).getMethod();
      for (VariableTree param : method.getParameters()) {
        Element paramElement = TreeUtils.elementFromDeclaration(param);
        if (typeFactory.hasMustCall(param) && paramElement.getAnnotation(Owning.class) != null) {
          init.add(new LocalVarWithAssignTree(new LocalVariable(paramElement), param));
        }
      }
    }
    BlockWithLocals firstBlockLocals = new BlockWithLocals(cfg.getEntryBlock(), init);

    Set<BlockWithLocals> visited = new HashSet<>();
    Deque<BlockWithLocals> worklist = new ArrayDeque<>();

    worklist.add(firstBlockLocals);
    visited.add(firstBlockLocals);

    while (!worklist.isEmpty()) {

      BlockWithLocals curBlockLocals = worklist.removeLast();
      List<Node> nodes = getBlockNodes(curBlockLocals.block);
      Set<LocalVarWithAssignTree> newDefs = new HashSet<>(curBlockLocals.localSetInfo);

      for (Node node : nodes) {

        if (node instanceof MethodInvocationNode) {
          Node receiver = ((MethodInvocationNode) node).getTarget().getReceiver();
          Element method = ((MethodInvocationNode) node).getTarget().getMethod();

          if (receiver instanceof LocalVariableNode
              && isVarInDefs(newDefs, (LocalVariableNode) receiver)) {
            List<String> mustCallVal =
                typeFactory.getMustCallValue(((LocalVariableNode) receiver).getTree());
            // TODO: I think this will cause false positives if there is more than one value in the
            // @MustCall annotation

            if (mustCallVal.size() == 1
                && mustCallVal.get(0).equals(method.getSimpleName().toString())) {
              // If the method called on the receiver is the same as receiver's @MustCall value,
              // then we can remove the receiver from the newDefs
              newDefs.remove(getAssignmentTreeOfVar(newDefs, (LocalVariableNode) receiver));
            }
          }
        }

        if (node instanceof AssignmentNode) {
          Node lhs = ((AssignmentNode) node).getTarget();
          Node rhs = ((AssignmentNode) node).getExpression();

          if (rhs instanceof TypeCastNode) {
            rhs = ((TypeCastNode) rhs).getOperand();
          }

          if (lhs instanceof LocalVariableNode
              && !isTryWithResourcesVariable((LocalVariableNode) lhs)) {

            // Reassignment to the lhs
            if (isVarInDefs(newDefs, (LocalVariableNode) lhs)) {
              LocalVarWithAssignTree latestAssignmentPair =
                  getAssignmentTreeOfVar(newDefs, (LocalVariableNode) lhs);
              checkMustCall(
                  latestAssignmentPair,
                  typeFactory.getStoreBefore(node),
                  "variable overwritten by assignment " + node.getTree());
              newDefs.remove(latestAssignmentPair);
            }

            // If the rhs is an ObjectCreationNode, or a MethodInvocationNode, then it adds
            // the AssignmentNode to the newDefs.
            if ((rhs instanceof ObjectCreationNode)
                || (rhs instanceof MethodInvocationNode && !typeFactory.hasNotOwningAnno(rhs))) {
              newDefs.add(
                  new LocalVarWithAssignTree(
                      new LocalVariable((LocalVariableNode) lhs), node.getTree()));
            }

            // Ownership Transfer
            if (rhs instanceof LocalVariableNode && isVarInDefs(newDefs, (LocalVariableNode) rhs)) {
              // If the rhs is a LocalVariableNode that exists in the newDefs (Note that if a
              // localVariableNode exists in the newDefs it means it isn't assigned to a null
              // literals), then it adds the localVariableNode to the newDefs
              newDefs.add(
                  new LocalVarWithAssignTree(
                      new LocalVariable((LocalVariableNode) lhs), node.getTree()));
              newDefs.remove(getAssignmentTreeOfVar(newDefs, (LocalVariableNode) rhs));
            }
          }
        }

        // Remove the returned localVariableNode from newDefs.
        if (node instanceof ReturnNode
            && (typeFactory.isTransferOwnershipAtReturn(node, cfg)
                || (typeFactory.transferOwnershipAtReturn
                    && !typeFactory.hasNotOwningAnno(node, cfg)))) {
          Node result = ((ReturnNode) node).getResult();
          if (result instanceof LocalVariableNode
              && isVarInDefs(newDefs, (LocalVariableNode) result)) {
            newDefs.remove(getAssignmentTreeOfVar(newDefs, (LocalVariableNode) result));
          }
        }

        if (node instanceof MethodInvocationNode || node instanceof ObjectCreationNode) {
          List<Node> arguments;
          ExecutableElement executableElement;
          if (node instanceof MethodInvocationNode) {
            MethodInvocationNode invocationNode = (MethodInvocationNode) node;
            arguments = invocationNode.getArguments();
            executableElement = TreeUtils.elementFromUse(invocationNode.getTree());
          } else {
            arguments = ((ObjectCreationNode) node).getArguments();
            executableElement = TreeUtils.elementFromUse(((ObjectCreationNode) node).getTree());
          }

          List<? extends VariableElement> formals = executableElement.getParameters();
          if (arguments.size() != formals.size()) {
            // this could happen, e.g., with varargs, or with strange cases like generated Enum
            // constructors
            // for now, just skip this case
            // TODO allow for ownership transfer here if needed in future
            continue;
          }
          for (int i = 0; i < arguments.size(); i++) {
            Node n = arguments.get(i);
            if (n instanceof MethodInvocationNode || n instanceof ObjectCreationNode) {
              VariableElement formal = formals.get(i);
              Set<AnnotationMirror> annotationMirrors = typeFactory.getDeclAnnotations(formal);
              TypeMirror t = TreeUtils.typeOf(n.getTree());
              List<String> mustCallVal = typeFactory.getMustCallValue(n.getTree());
              if (!mustCallVal.isEmpty()
                  && annotationMirrors.stream()
                      .noneMatch(anno -> AnnotationUtils.areSameByClass(anno, Owning.class))) {
                // TODO why is this logic here and not in the visitor?
                checker.reportError(
                    n.getTree(),
                    "required.method.not.called",
                    formatMissingMustCallMethods(mustCallVal),
                    t.toString(),
                    "never assigned to a variable");
              }
            }

            if (n instanceof LocalVariableNode) {
              LocalVariableNode local = (LocalVariableNode) n;
              if (isVarInDefs(newDefs, local)) {

                // check if formal has an @Owning annotation
                VariableElement formal = formals.get(i);
                Set<AnnotationMirror> annotationMirrors = typeFactory.getDeclAnnotations(formal);

                if (annotationMirrors.stream()
                    .anyMatch(anno -> AnnotationUtils.areSameByClass(anno, Owning.class))) {
                  // transfer ownership!
                  newDefs.remove(getAssignmentTreeOfVar(newDefs, local));
                }
              }
            }
          }
        }
      }

      if (curBlockLocals.block.getType() == Block.BlockType.EXCEPTION_BLOCK) {
        checkMustCallInExceptionSuccessors(
            (ExceptionBlockImpl) curBlockLocals.block, newDefs, visited, worklist);
      }

      for (BlockImpl succ : getSuccessors(curBlockLocals.block)) {
        Set<LocalVarWithAssignTree> toRemove = new HashSet<>();

        CFStore succRegularStore = analysis.getInput(succ).getRegularStore();
        for (LocalVarWithAssignTree assign : newDefs) {

          // If the successor block is the exit block or if the variable is going out of scope
          if (succ instanceof SpecialBlockImpl
              || succRegularStore.getValue(assign.localVar) == null) {
            // technically the variable may be going out of scope before the method exit, but that
            // doesn't seem to provide additional helpful information
            String outOfScopeReason = "regular method exit";
            if (nodes.size() == 0) { // If the cur block is special or conditional block
              checkMustCall(assign, succRegularStore, outOfScopeReason);

            } else { // If the cur block is Exception/Regular block then it checks MustCall
              // annotation in the store right after the last node
              Node last = nodes.get(nodes.size() - 1);
              CFStore storeAfter = typeFactory.getStoreAfter(last);
              checkMustCall(assign, storeAfter, outOfScopeReason);
            }

            toRemove.add(assign);
          }
        }

        newDefs.removeAll(toRemove);
        propagate(new BlockWithLocals(succ, newDefs), visited, worklist);
      }
    }
  }

  /**
   * If the input block is Regular, the returned list of nodes is exactly the contents of the block.
   * If it's an Exception_Block, then it returns the corresponding node that causes exception.
   * Otherwise, it returns an emptyList.
   *
   * @param block
   * @return List of Nodes
   */
  private static List<Node> getBlockNodes(Block block) {
    List<Node> blockNodes = new ArrayList<>();

    switch (block.getType()) {
      case REGULAR_BLOCK:
        blockNodes = ((RegularBlockImpl) block).getContents();
        break;

      case EXCEPTION_BLOCK:
        blockNodes.add(((ExceptionBlockImpl) block).getNode());
    }
    return blockNodes;
  }

  /**
   * Checks whether a pair exists in {@code defs} that its first var is equal to {@code node} or
   * not. This is useful when we want to check if a LocalVariableNode is overwritten or not.
   */
  private static boolean isVarInDefs(
      Set<MustCallInvokedChecker.LocalVarWithAssignTree> defs, LocalVariableNode node) {
    return defs.stream()
        .map(assign -> ((assign.localVar).getElement()))
        .anyMatch(elem -> elem.equals(node.getElement()));
  }

  /**
   * Returns a pair in {@code defs} that its first var is equal to {@code node} if one exists, null
   * otherwise.
   */
  private static @Nullable LocalVarWithAssignTree getAssignmentTreeOfVar(
      Set<LocalVarWithAssignTree> defs, LocalVariableNode node) {
    return defs.stream()
        .filter(assign -> assign.localVar.getElement().equals(node.getElement()))
        .findAny()
        .orElse(null);
  }

  /** checks if the variable has been declared in a try-with-resources header */
  private static boolean isTryWithResourcesVariable(LocalVariableNode lhs) {
    Tree tree = lhs.getTree();
    return tree != null
        && TreeUtils.elementFromTree(tree).getKind().equals(ElementKind.RESOURCE_VARIABLE);
  }

  /**
   * Creates the appropriate @CalledMethods annotation that corresponds to the @MustCall annotation
   * declared on the class type of {@code assign.first}. Then, it gets @CalledMethod annotation of
   * {@code assign.first} to do a subtyping check and reports an error if the check fails.
   */
  private void checkMustCall(
      LocalVarWithAssignTree assign, CFStore store, String outOfScopeReason) {
    List<String> mustCallValue = typeFactory.getMustCallValue(assign.assignTree);
    // optimization: if there are no must-call methods, we do not need to perform the check
    if (mustCallValue.isEmpty()) {
      return;
    }
    AnnotationMirror dummyCMAnno =
        typeFactory.createCalledMethods(mustCallValue.toArray(new String[0]));

    boolean report = true;

    AnnotationMirror cmAnno;

    // sometimes the store is null!  this looks like a bug in checker dataflow.
    // TODO track down and report the root-cause bug
    CFValue lhsCFValue = store != null ? store.getValue(assign.localVar) : null;
    if (lhsCFValue != null) { // When store contains the lhs
      cmAnno =
          lhsCFValue.getAnnotations().stream()
              .filter(anno -> AnnotationUtils.areSameByClass(anno, CalledMethods.class))
              .findAny()
              .orElse(typeFactory.top);
    } else {
      cmAnno =
          typeFactory
              .getAnnotatedType(assign.localVar.getElement())
              .getAnnotationInHierarchy(typeFactory.top);
    }

    if (typeFactory.getQualifierHierarchy().isSubtype(cmAnno, dummyCMAnno)) {
      report = false;
    }

    if (report) {
      if (!reportedMustCallErrors.contains(assign)) {
        reportedMustCallErrors.add(assign);

        checker.reportError(
            assign.assignTree,
            "required.method.not.called",
            formatMissingMustCallMethods(mustCallValue),
            assign.localVar.getType().toString(),
            outOfScopeReason);
      }
    }
  }

  /**
   * Performs {@code @MustCall} checking for exceptional successors of a block.
   *
   * @param exceptionBlock the block with exceptional successors.
   * @param defs current locals to check
   * @param visited already-visited state
   * @param worklist current worklist
   */
  private void checkMustCallInExceptionSuccessors(
      ExceptionBlock exceptionBlock,
      Set<MustCallInvokedChecker.LocalVarWithAssignTree> defs,
      Set<MustCallInvokedChecker.BlockWithLocals> visited,
      Deque<MustCallInvokedChecker.BlockWithLocals> worklist) {
    Map<TypeMirror, Set<Block>> exSucc = exceptionBlock.getExceptionalSuccessors();
    for (Map.Entry<TypeMirror, Set<Block>> pair : exSucc.entrySet()) {
      if (isIgnoredExceptionType(((Type) pair.getKey()).tsym.getSimpleName())) {
        continue;
      }
      CFStore storeAfter = typeFactory.getStoreAfter(exceptionBlock.getNode());
      for (Block tSucc : pair.getValue()) {
        if (tSucc instanceof SpecialBlock) {
          for (MustCallInvokedChecker.LocalVarWithAssignTree assignTree : defs) {
            checkMustCall(
                assignTree,
                storeAfter,
                "possible exceptional exit due to " + exceptionBlock.getNode().getTree());
          }
        } else {
          propagate(new MustCallInvokedChecker.BlockWithLocals(tSucc, defs), visited, worklist);
        }
      }
    }
  }

  /**
   * Is {@code exceptionClassName} an exception type we are ignoring, to avoid excessive false
   * positives? For now we ignore {@code java.lang.Throwable} and {@code NullPointerException}
   */
  private boolean isIgnoredExceptionType(Name exceptionClassName) {
    boolean isThrowableOrNPE =
        exceptionClassName.contentEquals(Throwable.class.getSimpleName())
            || exceptionClassName.contentEquals(NullPointerException.class.getSimpleName());
    return isThrowableOrNPE;
  }

  /**
   * If cur is Conditional block, then it returns a list of two successor blocks contains then block
   * and else block. If cur is instance of SingleSuccessorBlock then it returns a list of one block.
   * Otherwise, it throws an assertion error at runtime.
   *
   * @param cur
   * @return list of successor blocks
   */
  private List<BlockImpl> getSuccessors(BlockImpl cur) {
    List<BlockImpl> successorBlock = new ArrayList<>();

    if (cur.getType() == Block.BlockType.CONDITIONAL_BLOCK) {

      ConditionalBlock ccur = (ConditionalBlock) cur;

      successorBlock.add((BlockImpl) ccur.getThenSuccessor());
      successorBlock.add((BlockImpl) ccur.getElseSuccessor());

    } else {
      if (!(cur instanceof SingleSuccessorBlock)) {
        throw new BugInCF("BlockImpl is neither a conditional block nor a SingleSuccessorBlock");
      }

      Block b = ((SingleSuccessorBlock) cur).getSuccessor();
      if (b != null) {
        successorBlock.add((BlockImpl) b);
      }
    }
    return successorBlock;
  }

  /**
   * Updates {@code visited} and {@code worklist} if the input {@code state} has not been visited
   * yet.
   */
  private void propagate(
      MustCallInvokedChecker.BlockWithLocals state,
      Set<MustCallInvokedChecker.BlockWithLocals> visited,
      Deque<MustCallInvokedChecker.BlockWithLocals> worklist) {

    if (visited.add(state)) {
      worklist.add(state);
    }
  }

  /**
   * Formats a list of must-call method names to be printed in an error message.
   *
   * @param mustCallVal the list of must-call strings
   * @return a formatted string
   */
  static String formatMissingMustCallMethods(List<String> mustCallVal) {
    return mustCallVal.stream().reduce("", (s, acc) -> "".equals(acc) ? s : acc + ", " + s);
  }

  private static class BlockWithLocals {
    public final BlockImpl block;
    public final Set<LocalVarWithAssignTree> localSetInfo;

    public BlockWithLocals(Block b, Set<LocalVarWithAssignTree> ls) {
      this.block = (BlockImpl) b;
      this.localSetInfo = ls;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      BlockWithLocals that = (BlockWithLocals) o;
      return block.equals(that.block) && localSetInfo.equals(that.localSetInfo);
    }

    @Override
    public int hashCode() {
      return Objects.hash(block, localSetInfo);
    }
  }

  /**
   * A pair of a local variable along with a tree in the corresponding method that "assigns" the
   * variable. Besides a normal assignment, the tree may be a {@link VariableTree} in the case of a
   * formal parameter
   */
  private static class LocalVarWithAssignTree {
    public final LocalVariable localVar;
    public final Tree assignTree;

    public LocalVarWithAssignTree(LocalVariable localVarNode, Tree assignTree) {
      this.localVar = localVarNode;
      this.assignTree = assignTree;
    }

    @Override
    public String toString() {
      return "(LocalVarWithAssignTree: localVar: "
          + localVar
          + " |||| assignTree: "
          + assignTree
          + ")";
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      LocalVarWithAssignTree that = (LocalVarWithAssignTree) o;
      return localVar.equals(that.localVar) && assignTree.equals(that.assignTree);
    }

    @Override
    public int hashCode() {
      return Objects.hash(localVar, assignTree);
    }
  }
}
