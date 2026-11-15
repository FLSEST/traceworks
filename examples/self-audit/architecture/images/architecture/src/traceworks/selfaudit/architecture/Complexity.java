package traceworks.selfaudit.architecture;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;

/**
 * A member's cognitive complexity, after G. Ann Campbell's definition (the one Sonar and PMD use), from the syntax
 * tree: each {@code if}, {@code ?:}, {@code switch}, loop and {@code catch} adds 1 plus its nesting depth; an
 * {@code else} or {@code else if} adds 1; each run of one logical operator ({@code &&} or {@code ||}) adds 1, so each
 * change of operator adds 1 again; a {@code break} or {@code continue} to a label adds 1. The depth grows inside those
 * structures (an {@code else if} stays at the depth of its {@code if}) and inside a lambda or a local or anonymous
 * class, whose code counts toward the member that holds it. Recursion is not counted.
 */
final class Complexity extends TreeScanner<Void, Void> {

    private int total;
    private int depth;
    /** The logical operator directly above the node being scanned, through parentheses; null for anything else. */
    private Tree.Kind operatorAbove;
    private IfTree elseIf;

    private Complexity() {
    }

    /** The cognitive complexity of {@code member}: a method, constructor, initializer or field. */
    static int of(Tree member) {
        Complexity c = new Complexity();
        c.scan(member, null);
        return c.total;
    }

    @Override
    public Void scan(Tree tree, Void unused) {
        if (tree == null) return null;
        Tree.Kind saved = operatorAbove;
        if (!(tree instanceof BinaryTree) && !(tree instanceof ParenthesizedTree)) operatorAbove = null;
        try {
            return super.scan(tree, unused);
        } finally {
            operatorAbove = saved;
        }
    }

    @Override
    public Void visitBinary(BinaryTree node, Void unused) {
        Tree.Kind kind = node.getKind();
        boolean logical = kind == Tree.Kind.CONDITIONAL_AND || kind == Tree.Kind.CONDITIONAL_OR;
        if (logical && kind != operatorAbove) total++;
        Tree.Kind saved = operatorAbove;
        operatorAbove = logical ? kind : null;
        try {
            scan(node.getLeftOperand(), unused);
            operatorAbove = logical ? kind : null;
            scan(node.getRightOperand(), unused);
        } finally {
            operatorAbove = saved;
        }
        return null;
    }

    @Override
    public Void visitIf(IfTree node, Void unused) {
        total += node == elseIf ? 1 : 1 + depth;
        scan(node.getCondition(), unused);
        nested(node.getThenStatement());
        Tree otherwise = node.getElseStatement();
        if (otherwise instanceof IfTree chained) {
            elseIf = chained;
            scan(chained, unused);
        } else if (otherwise != null) {
            total++;
            nested(otherwise);
        }
        return null;
    }

    @Override
    public Void visitConditionalExpression(ConditionalExpressionTree node, Void unused) {
        total += 1 + depth;
        scan(node.getCondition(), unused);
        nested(node.getTrueExpression());
        nested(node.getFalseExpression());
        return null;
    }

    @Override
    public Void visitSwitch(SwitchTree node, Void unused) {
        total += 1 + depth;
        scan(node.getExpression(), unused);
        node.getCases().forEach(this::nested);
        return null;
    }

    @Override
    public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
        total += 1 + depth;
        scan(node.getExpression(), unused);
        node.getCases().forEach(this::nested);
        return null;
    }

    @Override
    public Void visitForLoop(ForLoopTree node, Void unused) {
        total += 1 + depth;
        scan(node.getInitializer(), unused);
        scan(node.getCondition(), unused);
        scan(node.getUpdate(), unused);
        nested(node.getStatement());
        return null;
    }

    @Override
    public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
        total += 1 + depth;
        scan(node.getExpression(), unused);
        nested(node.getStatement());
        return null;
    }

    @Override
    public Void visitWhileLoop(WhileLoopTree node, Void unused) {
        total += 1 + depth;
        scan(node.getCondition(), unused);
        nested(node.getStatement());
        return null;
    }

    @Override
    public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
        total += 1 + depth;
        nested(node.getStatement());
        scan(node.getCondition(), unused);
        return null;
    }

    @Override
    public Void visitCatch(CatchTree node, Void unused) {
        total += 1 + depth;
        nested(node.getBlock());
        return null;
    }

    @Override
    public Void visitBreak(BreakTree node, Void unused) {
        if (node.getLabel() != null) total++;
        return null;
    }

    @Override
    public Void visitContinue(ContinueTree node, Void unused) {
        if (node.getLabel() != null) total++;
        return null;
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
        nested(node.getBody());
        return null;
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
        // A local or anonymous class inside the member: its code counts here, one level deeper
        node.getMembers().forEach(this::nested);
        return null;
    }

    private void nested(Tree tree) {
        depth++;
        try {
            scan(tree, null);
        } finally {
            depth--;
        }
    }
}
