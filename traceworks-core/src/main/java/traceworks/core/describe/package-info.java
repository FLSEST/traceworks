/**
 * The description of a workflow package before it runs: which workflow it is, what it takes, what each block does and
 * may touch, and what its blocks hold. {@code WorkflowDescriber} reads a loaded package into a
 * {@code WorkflowDescription}, with the rules the run itself reads a block by; {@code DescriptionText} prints it as
 * lines, and {@code core.view.DescribeHtml} as a page. Pure: it starts nothing and reads no file.
 */
package traceworks.core.describe;
