/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.phases.common;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;
import org.graalvm.collections.UnmodifiableMapCursor;
import org.graalvm.compiler.core.common.cfg.Loop;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.MethodFilter;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodeinfo.NodeCycles;
import org.graalvm.compiler.nodeinfo.NodeSize;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.calc.BinaryNode;
import org.graalvm.compiler.nodes.calc.TernaryNode;
import org.graalvm.compiler.nodes.calc.UnaryNode;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.nodes.extended.BytecodeExceptionNode;
import org.graalvm.compiler.nodes.java.AbstractNewObjectNode;
import org.graalvm.compiler.nodes.java.AccessArrayNode;
import org.graalvm.compiler.nodes.java.AccessMonitorNode;
import org.graalvm.compiler.nodes.java.ExceptionObjectNode;
import org.graalvm.compiler.nodes.memory.FixedAccessNode;
import org.graalvm.compiler.nodes.memory.MemoryAccess;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.graph.ReentrantBlockIterator;
import org.graalvm.compiler.phases.schedule.SchedulePhase;

/***
 * In order to estimate the probabilities for each of the control splits branches in the Graal's IR graph, first of all, we need to parse important features of each of them.
 * This class is used for extracting important attributes for each branch each of the Graal IR graph control splits.
 ***/

/* Representation of a control split */
class ControlSplit {
    private Block block;  // Block ending with control split node
    private List<Block> pathToBlock;  // The path leading to this block
    private EconomicSet<AbstractBeginNode> sonsHeads;  // Head nodes of sons I am waiting for
    private EconomicMap<AbstractBeginNode, List<Block>> sonsBlocks;  // Completed sons
    private EconomicSet<AbstractBeginNode> tailHeads;  // If I go through my personal merge and I am not complete at that time. If I am finished at my personal merge but that merge is continue-in-switch caused. Simply code propagation to predecessor control splits.
    private EconomicMap<AbstractBeginNode, List<Block>> tailBlocks;  // Tail blocks appended to this control split, for propagation to father blocks

    public ControlSplit(Block block, List<Block> path) {
        assert block.getEndNode() instanceof ControlSplitNode : "ParseImportantFeaturesError: Control Split can be instantiated only with Control Split Node (as end).";
        this.block = block;
        this.pathToBlock = new ArrayList<>(path);
        this.sonsBlocks = EconomicMap.create(Equivalence.DEFAULT);
        this.sonsHeads = EconomicSet.create(Equivalence.DEFAULT);
        for (Block son : block.getSuccessors())
            this.sonsHeads.add(son.getBeginNode());
        this.tailHeads = EconomicSet.create(Equivalence.DEFAULT);
        this.tailBlocks = EconomicMap.create(Equivalence.DEFAULT);
    }

    public Block getBlock() {
        return this.block;
    }

    public List<Block> getPathToBlock() {
        return pathToBlock;
    }

    // Sons operations
    public Boolean finished() {
        return this.sonsHeads.isEmpty();
    }

    public UnmodifiableMapCursor<AbstractBeginNode, List<Block>> getSons() {  // main getter
        return this.sonsBlocks.getEntries();
    }

    public EconomicMap<AbstractBeginNode, List<Block>> getSonsMap() {  // additional getter
        return this.sonsBlocks;
    }

    public Iterable<List<Block>> getSonsPaths() {  // auxiliary getter
        return this.sonsBlocks.getValues();
    }

    public void addASon(List<Block> sonsPath) {  // add
        AbstractBeginNode sonsHead = sonsPath.get(0).getBeginNode();
        assert this.sonsHeads.contains(sonsHead) : "ParseImportantFeaturesError: Adding invalid son.";
        assert !this.sonsBlocks.containsKey(sonsHead) : "ParseImportantFeaturesError: Adding same son twice.";
        this.sonsBlocks.put(sonsHead, new ArrayList<>(sonsPath));
        this.sonsHeads.remove(sonsHead);
    }

    public boolean areInSons(AbstractBeginNode node) {  // check
        return this.sonsHeads.contains(node);
    }

    // Tails operations
    public UnmodifiableMapCursor<AbstractBeginNode, List<Block>> getTails() { // main getter
        return this.tailBlocks.getEntries();
    }

    public EconomicMap<AbstractBeginNode, List<Block>> getTailsMap() {  // additional getter
        return this.tailBlocks;
    }

    public Iterable<List<Block>> getTailsPaths() {  // auxiliary getter
        return this.tailBlocks.getValues();
    }

    public void setTailNode(AbstractBeginNode tailNode) {  // add
        this.tailHeads.add(tailNode);
    }

    public void setTailBlocks(List<Block> tailBlocks) {  // add
        AbstractBeginNode node = tailBlocks.get(0).getBeginNode();
        assert this.tailHeads.contains(node) : "ParseImportantFeaturesError: set tail blocks on wrong tail.";
        this.tailBlocks.put(node, new ArrayList<>(tailBlocks));
    }

    public boolean areInTails(AbstractBeginNode node) {
        return this.tailHeads.contains(node);
    }  // check
}

/* Graph traversal intermediate state representation */
class TraversalState {
    private List<Block> path;  // List of blocks visited so far

    public TraversalState() {
        this.path = new ArrayList<>();
    }

    public TraversalState(List<Block> path) {
        if (path == null)
            this.path = new ArrayList<>();
        else
            this.path = new ArrayList<>(path);
    }

    public List<Block> getPath() {
        return this.path;
    }

    public void addBlockToPath(Block block) {
        this.path.add(block);
    }

    public void clearPath() {
        this.path.clear();
    }
}

public class ParseImportantFeaturesPhase extends BasePhase<CoreProviders> {

    private Stage stage;
    private String methodRegex;

    private static PrintWriter writer;
    private static PrintWriter writerAttr;

    static { // Static writer used for dumping important features to database (currently .csv file)
        try {
            writer = new PrintWriter(new FileOutputStream(new File("./importantFeatures.csv")), true, StandardCharsets.UTF_8);
            writer.printf("Graph Id,Source Function,Node Description,Cardinality,Node Id,Node BCI,head%n");
            writerAttr = new PrintWriter(new FileOutputStream(new File("./importantAttributes.csv")), true, StandardCharsets.US_ASCII);
            writerAttr.printf("Graph Id, Source Function, Node Description, head%n");
        } catch (FileNotFoundException e) {
            System.exit(1);  // Can't open a database file.
        }
    }

    public ParseImportantFeaturesPhase(Stage stage, String methodRegex) {
        this.stage = stage;
        this.methodRegex = methodRegex;
    }

    public enum Stage {
        INIT,
        EARLY,
        LATE
    }

    public static class Options {
        // @formatter:off
        @Option(help = "Parse important features from graph nodes.", type = OptionType.Expert)
        public static final OptionKey<Boolean> ParseImportantFeatures = new OptionKey<>(false);
        // @formatter:on
    }

    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {
        if (methodRegex != null) {  // If Method Filter is not specified, parse all functions, otherwise parse only desired function[s]
            MethodFilter mf = MethodFilter.parse(methodRegex);
            if (!mf.matches(graph.method()))  // If Method Filter is specified, parse only target functions
                return;
        }

        // Block and nodes integration
        ControlFlowGraph cfg = ControlFlowGraph.compute(graph, true, true, true, true);
        try (DebugContext.Scope scheduleScope = graph.getDebug().scope(SchedulePhase.class)) {
            SchedulePhase.run(graph, SchedulePhase.SchedulingStrategy.LATEST, cfg);  // Do scheduling because of floating point nodes
        } catch (Throwable t) {
            throw graph.getDebug().handle(t);
        }
        StructuredGraph.ScheduleResult schedule = graph.getLastSchedule();

        // temporary writeout
        for (Block b : schedule.getCFG().getBlocks())
            System.err.println(b + ": " + schedule.nodesFor(b) + "[" + b.getLoopDepth() + "]");
        System.err.println("\n\n");
        for (Node node : graph.getNodes()) {
            System.err.println(node + ": " + (schedule.getNodeToBlockMap().get(node) != null ? schedule.getNodeToBlockMap().get(node).toString() : "null") + " cyc: " + __castNodeCycles(node.estimatedNodeCycles()) + " ass: " + __castNodeSize(node.estimatedNodeSize()));
            //if (node instanceof InvokeNode) {
            //    System.err.println("CALL_TARGET: [" + ((InvokeNode) node).callTarget().targetName() + "]");
            //}
            System.err.println("INPUTS: " + node.inputs().toString());
            for (Node ninput : node.inputs())
                System.err.println("NODE: " + ninput + ":" + (ninput instanceof BinaryNode || ninput instanceof LogicNode || ninput instanceof TernaryNode || ninput instanceof UnaryNode));
            if (node instanceof AbstractMergeNode) {
                System.err.println("ABMN: PHIS" + Arrays.toString(((AbstractMergeNode) node).phis().snapshot().toArray()));
                System.err.println("ABMN: VALUE PHIS" + Arrays.toString(((AbstractMergeNode) node).valuePhis().snapshot().toArray()));
                System.err.println("ABMN: MEMORY PHIS" + Arrays.toString(((AbstractMergeNode) node).memoryPhis().snapshot().toArray()));
            }
        }
        // end of temporary writeout

        // Graph traversal algorithm

        Stack<ControlSplit> splits = new Stack<>();  // Active control splits

        ReentrantBlockIterator.BlockIteratorClosure<TraversalState> CSClosure = new ReentrantBlockIterator.BlockIteratorClosure<TraversalState>() {
            @Override
            protected TraversalState getInitialState() {
                return new TraversalState();
            }

            @Override
            protected TraversalState processBlock(Block block, TraversalState currentState) {
                if (block.getEndNode() instanceof ControlSplitNode) {
                    splits.push(new ControlSplit(block, currentState.getPath()));  // Add control split currently being processed (with appropriate path to block)
                    currentState.clearPath();                                      // Clear path, fresh restart (for the first successor path to block is already set)
                } else {
                    currentState.addBlockToPath(block);

                    if (block.getSuccessors().length == 0) {  // I don't have successors: for blocks like this ReentrantBlockIterator simply go on
                        ControlSplit targetCS = findControlSplitFather(splits, currentState.getPath());
                        if (targetCS != null)
                            targetCS.addASon(currentState.getPath());
                        else {  // If no one waits for me as a son, look at a their tails
                            targetCS = findTailFather(splits, currentState.getPath());
                            if (targetCS != null)
                                targetCS.setTailBlocks(currentState.getPath());
                        }
                        // else - no one to catch
                        // currentState path will be reset on ReentrantBlockIterator.java, @ line 170.
                    } else if (block.getSuccessors().length == 1) {  // I have only one successor
                        // End before loops aren't end of any Control Split branches: simply skip that end (in the term of adding a son/tail)
                        // If next is LoopBeginNode: if curr is loop end: add path as a someone son/tail, else: skip adding path as a anyone son/tail
                        if (block.getEndNode() instanceof AbstractEndNode && (block.isLoopEnd() || !(block.getFirstSuccessor().getBeginNode() instanceof LoopBeginNode))) {
                            ControlSplit targetCS = findControlSplitFather(splits, currentState.getPath());
                            if (targetCS != null)
                                targetCS.addASon(currentState.getPath());
                            else {  // If no one waits for me as a son, look at a theirs tails
                                targetCS = findTailFather(splits, currentState.getPath());
                                if (targetCS != null)
                                    targetCS.setTailBlocks(currentState.getPath());
                            }
                            // else - no one to catch
                            // currentState path will be reset on ReentrantBlockIterator.java, @ line 170, eventually @ line 147.
                        }
                    } else {
                        assert false : "Node with more than one successors doesn't catch as a Control Split Node.";
                    }
                }
                return currentState;  // This will be used only on FixedWithNextNode process
            }

            @Override
            protected TraversalState merge(Block merge, List<TraversalState> __states) {
                // ___states are used internally by ReentrantBlockIterator in order to ensure that the graph is properly visited
                if (splits.size() > 0 && !splits.peek().finished()) {
                    // Going through uncomplete (personal) merge (merge which all ends were visited, but appropriate control split isn't finished)
                    splits.peek().setTailNode(merge.getBeginNode());  // Add as a tail
                    return new TraversalState();  // Clear path
                }

                while (splits.size() > 0) {
                    if (splits.peek().finished()) {
                        // Finished Control Split (on top of the stack)
                        ControlSplit stacksTop = splits.peek();

                        // If on top of the stack are switch control split which is not fully finished
                        // Should propagate through that merge, and add merge as a cs tail. Later on, eventually add it to the appropriate merge forward ends as their part or simply propagate it upwards
                        if (splits.peek().getBlock().getSuccessorCount() > 2) {  // Switch node case
                            EconomicSet<AbstractMergeNode> reachable = EconomicSet.create(Equivalence.DEFAULT);
                            for (List<Block> son : splits.peek().getSonsPaths()) {
                                reachable.addAll(__pathReachable(son));  // Add son's reachable merge nodes
                            }
                            for (List<Block> tail : splits.peek().getTailsPaths())
                                reachable.remove((AbstractMergeNode) tail.get(0).getBeginNode());  // Remove already reached merge nodes
                            if (reachable.size() > 0) {  // Control split is currently incomplete
                                splits.peek().setTailNode(merge.getBeginNode());  // Add next path as a tail, if its connected it will be kept, otherwise will be propagated upwards
                                return new TraversalState();
                            }
                        }

                        // My new path
                        List<Block> newPath = writeOutFromStack(splits, graph, schedule);

                        // Try to eventually add a son
                        if (splits.size() > 0) {
                            ControlSplit fatherCS, tailCS;
                            fatherCS = findControlSplitFather(splits, newPath);
                            tailCS = findTailFather(splits, newPath);
                            if (fatherCS != null) {
                                // If tis my personal merge continue, else push as a son
                                if (personalMerge(stacksTop, (AbstractMergeNode) merge.getBeginNode()))
                                    return new TraversalState(newPath);
                                else
                                    fatherCS.addASon(newPath);
                            } else if (tailCS != null) {
                                // If its my personal merge continue, else push as a tail
                                if (personalMerge(stacksTop, (AbstractMergeNode) merge.getBeginNode()))
                                    return new TraversalState(newPath);
                                else
                                    tailCS.setTailBlocks(newPath);
                            } else
                                continue; // Son not added; No one waiting for me
                        }
                    } else {
                        splits.peek().setTailNode(merge.getBeginNode()); // Add as a tail
                        return new TraversalState();  // A Control Split on the top of the splits firstly was finished, then popped up and added as a son or tail, then loop were continued, then control split on top of the stack aren't finished: further go on merge node deeper with empty path, later on, when finish that Control Split, just do regularly
                    }
                }
                return new TraversalState();  // No more Control Splits on stack, fresh restart
            }

            @Override
            protected TraversalState cloneState(TraversalState oldState) {
                return new TraversalState();  // @ReentrantBlockIterator processMultipleSuccessors: used only for control split purpose, when pushing sons, father has been already on top of the stack, waiting for them
            }

            @Override
            protected List<TraversalState> processLoop(Loop<Block> loop, TraversalState initialState) {
                EconomicMap<FixedNode, TraversalState> blockEndStates = ReentrantBlockIterator.apply(this, loop.getHeader(), initialState, block -> !(block.getLoop() == loop || block.isLoopHeader()));  // Recursive call, stopping on the LoopExitNodes

                Block[] predecessors = loop.getHeader().getPredecessors();
                ReentrantBlockIterator.LoopInfo<TraversalState> info = new ReentrantBlockIterator.LoopInfo<>(predecessors.length - 1, loop.getLoopExits().size());
                for (int i = 1; i < predecessors.length; i++) {
                    TraversalState endState = blockEndStates.get(predecessors[i].getEndNode());
                    // make sure all end states are unique objects
                    info.endStates.add(this.cloneState(endState));
                }
                for (Block loopExit : loop.getLoopExits()) {
                    assert loopExit.getPredecessorCount() == 1;
                    assert blockEndStates.containsKey(loopExit.getBeginNode()) : loopExit.getBeginNode() + " " + blockEndStates;
                    TraversalState exitState = blockEndStates.get(loopExit.getBeginNode());
                    info.exitStates.add(exitState);  // Need to propagate full path to the loop exit - ex.: when son is BX1+BX2, where BX2 is LoopExit+Unwind (need to propagate B1 as a state for block BX2)
                }
                return info.exitStates;
            }
        };

        ReentrantBlockIterator.apply(CSClosure, schedule.getCFG().getStartBlock());

        // Flush [finished] Control Splits from the stack as the end of the iteration process
        while (splits.size() > 0) {
            // My new path
            List<Block> newPath = writeOutFromStack(splits, graph, schedule);

            // Try to eventually add a son
            if (splits.size() > 0) {
                ControlSplit fatherCS, tailCS;
                fatherCS = findControlSplitFather(splits, newPath);
                tailCS = findTailFather(splits, newPath);
                if (fatherCS != null)
                    fatherCS.addASon(newPath);
                else if (tailCS != null)
                    tailCS.setTailBlocks(newPath);
                else
                    continue; // A son not added; no one waiting for this path as a son; continue to flushing splits
            }
        }
    }

    private static boolean personalMerge(ControlSplit cs, AbstractMergeNode merge) {  // Are merge block (block starting with AbstractMergeNode merge) fully owned by Control split cs
        Iterable<List<Block>> sons = cs.getSonsPaths();
        EconomicSet<AbstractEndNode> myEnds = EconomicSet.create(Equivalence.IDENTITY);
        for (List<Block> son : sons) {
            for (Block sblock : son) {
                if (sblock.getEndNode() instanceof AbstractEndNode) {  // For merge of 2nd and higher order
                    myEnds.add((AbstractEndNode) sblock.getEndNode());
                }
            }
        }
        for (AbstractEndNode forwardEnd : merge.forwardEnds()) {
            if (!myEnds.contains(forwardEnd)) {
                return false;
            }
        }
        return true;
    }

    private static ControlSplit findTailFather(Stack<ControlSplit> splits, List<Block> path) {
        if (path == null) return null;
        int i;
        for (i = splits.size() - 1; i >= 0; i--) {
            if (splits.get(i).areInTails(path.get(0).getBeginNode()))
                break;
        }
        if (i == -1)
            return null;
        else
            return splits.get(i);
    }

    private static ControlSplit findControlSplitFather(Stack<ControlSplit> splits, List<Block> path) {
        if (path == null) return null;
        int i;
        for (i = splits.size() - 1; i >= 0; i--) {
            if (splits.get(i).areInSons(path.get(0).getBeginNode()))
                break;
        }
        if (i == -1)
            return null;
        else
            return splits.get(i);
    }

    private static List<Block> writeOutFromStack(Stack<ControlSplit> splits, StructuredGraph graph, StructuredGraph.ScheduleResult schedule) {
        // Pop element from the top of a stack and write it out to the database; return integrated path
        assert splits.size() > 0 && splits.peek().finished() : "ParseImportantFeaturesError: invalid call of 'writeOutFromStack'";
        List<Block> newPath;

        // pop finished cs
        ControlSplit cs = splits.pop();
        Block head = cs.getBlock();
        int card = head.getSuccessorCount();

        // In the case of the switch control split: eventually do a sons concatenation and fill up pinned path for every son
        EconomicMap<AbstractBeginNode, List<Block>> pinnedPaths = __sonsConcat(cs);

        // writeout
        UnmodifiableMapCursor<AbstractBeginNode, List<Block>> __sons = cs.getSons();
        synchronized (writer) {
            long graphId = graph.graphId();
            int nodeBCI = head.getEndNode().getNodeSourcePosition() == null ? -9999 : head.getEndNode().getNodeSourcePosition().getBCI();  // -9999 represent error BCI code
            String name = graph.method().getName();

            writer.printf("%d,\"%s\",%s,%d,%d,%d,%s", graphId, name, head.getEndNode().toString(), card, head.getEndNode().getId(), nodeBCI, head);
            while (__sons.advance()) {
                AbstractBeginNode sonHead = __sons.getKey();
                List<Block> sonPath = __sons.getValue();
                if (sonHead instanceof LoopExitNode)
                    writer.printf(",\"[x(%s)][null]\"", sonHead.toString());  // x is an abbreviation for LoopExitNode
                else {
                    List<Block> pinnedPath = pinnedPaths.get(sonHead);  // pinnedPath represents eventually path from the sons end to the end of that path (a path that comes after final sons merge node - asymmetric ending switch case)
                    writer.printf(",\"%s%s\"", sonPath, pinnedPath == null ? "[null]" : pinnedPath); // write sons path to database
                }
            }
            writer.printf("%n");
        }

        // Write out important attributes; due to test compatibility maintain two versions
        __sons = cs.getSons();
        synchronized (writerAttr) {
            long graphId = graph.graphId();
            String name = graph.method().getName();

            writerAttr.printf("%d,\"%s\",%s,%s", graphId, name, head.getEndNode().toString(), head);
            while (__sons.advance()) {
                AbstractBeginNode sonHead = __sons.getKey();
                List<Block> sonPath = __sons.getValue();
                if (sonHead instanceof LoopExitNode) {
                    writerAttr.printf(",\"[x(%s)][null]\"", sonHead.toString());  // x is an abbreviation for LoopExitNode
                    writerAttr.printf("; IRFixedNodeCount: [0][0]");
                    writerAttr.printf("; IRNodeCount: [0][0]");
                    writerAttr.printf("; EstimatedCPUCycles: [0][0]");
                    writerAttr.printf("; EstimatedAssemblySize: [0][0]");
                    writerAttr.printf("; LoopDepth: [%d][0]", sonPath.get(0).getLoopDepth());
                    writerAttr.printf("; MaxPathLoopDepth: [%d][0]", sonPath.get(0).getLoopDepth());
                    writerAttr.printf("; NCond: [0][0]");
                    writerAttr.printf("; NInvoke: [0][0]");
                    writerAttr.printf("; NExceptions: [0][0]");
                    writerAttr.printf("; NAllocations: [0][0]");
                    writerAttr.printf("; NSimpleInstr: [0][0]");
                    writerAttr.printf("; NArrayAccess: [0][0]");
                    writerAttr.printf("; NMonitor: [0][0]");
                    writerAttr.printf("; NMemoryAccess: [0][0]");
                } else {
                    List<Block> pinnedPath = pinnedPaths.get(sonHead);
                    writerAttr.printf(",\"%s%s\"", sonPath, pinnedPath == null ? "[null]" : pinnedPath);
                    writerAttr.printf("; IRFixedNodeCount: [%d][%d]", getIRFixedNodeCount(sonPath), getIRFixedNodeCount(pinnedPath));
                    writerAttr.printf("; IRNodeCount: [%d][%d]", getIRNodeCount(sonPath, schedule), getIRNodeCount(pinnedPath, schedule));
                    writerAttr.printf("; EstimatedCPUCycles: [%d][%d]", getEstimatedNodeCycles(sonPath, schedule), getEstimatedNodeCycles(pinnedPath, schedule));
                    writerAttr.printf("; EstimatedAssemblySize: [%d][%d]", getEstimatedAssemblyNodeSize(sonPath, schedule), getEstimatedAssemblyNodeSize(pinnedPath, schedule));
                    writerAttr.printf("; LoopDepth: [%d][%d]", getLoopDepth(sonPath), getLoopDepth(pinnedPath));
                    writerAttr.printf("; MaxPathLoopDepth: [%d][%d]", getMaxLoopDepth(sonPath), getMaxLoopDepth(pinnedPath));
                    writerAttr.printf("; NCond: [%d][%d]", getNCond(sonPath), getNCond(pinnedPath));
                    writerAttr.printf("; NInvoke: [%d][%d]", getNInvoke(sonPath), getNInvoke(pinnedPath));
                    writerAttr.printf("; NExceptions: [%d][%d]", genNExceptions(sonPath), genNExceptions(pinnedPath));
                    writerAttr.printf("; NAllocations: [%d][%d]", getNAllocations(sonPath), getNAllocations(pinnedPath));
                    writerAttr.printf("; NSimpleInstr: [%d][%d]", getNSimpleInstr(sonPath, schedule), getNSimpleInstr(pinnedPath, schedule));
                    writerAttr.printf("; NArrayAccess: [%d][%d]", getNArrayAccess(sonPath), getNArrayAccess(pinnedPath));
                    writerAttr.printf("; NMonitor: [%d][%d]", getNMonitor(sonPath), getNMonitor(pinnedPath));
                    writerAttr.printf("; NMemoryAccess: [%d][%d]", getNMemoryAccess(sonPath, schedule), getNMemoryAccess(pinnedPath, schedule));
                }
            }
            writerAttr.printf("%n");
        }

        // Parse tail
        UnmodifiableMapCursor<AbstractBeginNode, List<Block>> __tails = cs.getTails();
        List<Block> tail = new ArrayList<>();
        while (__tails.advance()) {
            AbstractBeginNode csNode = __tails.getKey();
            List<Block> csBlocks = __tails.getValue();
            if (personalMerge(cs, (AbstractMergeNode) csNode))  // A path which follows the current control split; for the propagation to the older splits.
                tail.addAll(csBlocks);
            else if (splits.size() > 0) {
                splits.peek().setTailNode(csNode);  // Propagate unused tails upward
                splits.peek().setTailBlocks(csBlocks);
            }
        }

        // Create a full cs path
        newPath = new ArrayList<>(cs.getPathToBlock());
        newPath.add(head);
        __sons = cs.getSons();
        while (__sons.advance()) {
            List<Block> sonPath = __sons.getValue();
            newPath.addAll(sonPath);
        }
        if (tail.size() > 0)
            newPath.addAll(tail);

        return newPath.stream().distinct().collect(Collectors.toList());  // remove duplicates (we can have blocks duplication by branches: "continue" in switch, path tails in asymmetric switch)
    }

    private static EconomicMap<AbstractBeginNode, List<Block>> __sonsConcat(ControlSplit cs) {
        // Concatenate sons of switch control split
        Block head = cs.getBlock();
        int card = head.getSuccessorCount();
        UnmodifiableMapCursor<AbstractBeginNode, List<Block>> __sons = cs.getSons();

        EconomicMap<AbstractBeginNode, List<Block>> __fulltails = cs.getTailsMap();  // cs tails map
        EconomicMap<AbstractBeginNode, List<Block>> __fullsons = cs.getSonsMap();  // cs sons map
        EconomicMap<AbstractBeginNode, List<Block>> pinnedPaths = EconomicMap.create(Equivalence.DEFAULT);  // pinned sons paths in case of the switch control splits

        if (card > 2) {  // switch cs case
            while (__sons.advance()) {
                AbstractBeginNode sonHead = __sons.getKey();
                List<Block> sonPath = __sons.getValue();

                pinnedPaths.put(sonHead, null);  // initially put null value

                List<Block> newMeat = new ArrayList<>(sonPath);
                while (true) {  // traverse following sons path
                    EconomicSet<AbstractMergeNode> sonEnds = __pathReachable(newMeat);
                    newMeat.clear();
                    if (sonEnds.isEmpty())
                        break;
                    for (AbstractMergeNode nextNode : sonEnds) {
                        if (__fulltails.containsKey(nextNode)) {
                            // If tail is personal ended add it as a intermediate path, else add it as a pinned path and break
                            List<Block> tailBody = __fulltails.get(nextNode);
                            if (__hasInnerExit(tailBody, __fulltails.getKeys())) {  // Inner sub-path
                                newMeat.addAll(new ArrayList<>(__fulltails.get(nextNode)));  // If this merge node is caused by continue inside switch statement, add appropriate tail blocks to the son's path
                            } else {
                                pinnedPaths.put(sonHead, new ArrayList<>(__fulltails.get(nextNode)));
                                break;
                            }
                        }
                    }
                    if (newMeat.size() == 0 || pinnedPaths.get(sonHead) != null) {
                        break;
                    } else
                        sonPath.addAll(new ArrayList<>(newMeat));
                }

                __fullsons.put(sonHead, sonPath);
            }
            EconomicSet<List<Block>> tmp = EconomicSet.create(Equivalence.DEFAULT);  // If all sons have the same pinned path, don't use it at all
            boolean nullexists = false;
            for (List<Block> elem : pinnedPaths.getValues())
                if (elem != null)
                    tmp.add(elem);
                else
                    nullexists = true;
            if (tmp.size() == 1 && !nullexists)
                pinnedPaths.clear();
        }
        return pinnedPaths;
    }

    private static EconomicSet<AbstractMergeNode> __pathReachable(List<Block> path) {
        // Return set of Merge nodes which are reachable by the current path
        EconomicSet<AbstractMergeNode> reach = EconomicSet.create(Equivalence.DEFAULT);
        for (Block b : path) {
            if (b.getEndNode() instanceof AbstractEndNode) {
                Block succ = b.getFirstSuccessor();
                if (succ.getBeginNode() instanceof AbstractMergeNode)
                    reach.add((AbstractMergeNode) succ.getBeginNode());
            }
        }
        for (Block b : path)
            if (b.getBeginNode() instanceof AbstractMergeNode)
                reach.remove((AbstractMergeNode) b.getBeginNode());  // return only real-reachable
        return reach;
    }

    private static boolean __hasInnerExit(List<Block> path, Iterable<AbstractBeginNode> tailHeads) {
        // Return true if the path has reachable merge nodes in the set of the tailHeads nodes
        EconomicSet<AbstractMergeNode> reach = __pathReachable(path);
        for (AbstractBeginNode thead : tailHeads)
            if (reach.contains((AbstractMergeNode) thead))
                return true;
        return false;
    }

    /* Util functions that parse important attributes of blocks */
    private static int getIRFixedNodeCount(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nnodes = 0;
        for (Block b : path) {
            for (Node node : b.getNodes()) {
                nnodes += 1;
            }
        }
        return nnodes;
    }

    private static int getIRNodeCount(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nnodes = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                nnodes += 1;  // todo: // Add eventually phi nodes which are not block assigned
            }
        }
        return nnodes;
    }

    private static int getEstimatedNodeCycles(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int ncycles = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                ncycles += __castNodeCycles(n.estimatedNodeCycles());
            }
        }
        return ncycles;
    }

    private static int __castNodeCycles(NodeCycles ncyc) {
        if (ncyc == NodeCycles.CYCLES_1) {
            return 1;
        } else if (ncyc == NodeCycles.CYCLES_2) {
            return 2;
        } else if (ncyc == NodeCycles.CYCLES_4) {
            return 4;
        } else if (ncyc == NodeCycles.CYCLES_8) {
            return 8;
        } else if (ncyc == NodeCycles.CYCLES_16) {
            return 16;
        } else if (ncyc == NodeCycles.CYCLES_32) {
            return 32;
        } else if (ncyc == NodeCycles.CYCLES_64) {
            return 64;
        } else if (ncyc == NodeCycles.CYCLES_128) {
            return 128;
        } else if (ncyc == NodeCycles.CYCLES_256) {
            return 256;
        } else if (ncyc == NodeCycles.CYCLES_512) {
            return 512;
        } else if (ncyc == NodeCycles.CYCLES_1024) {
            return 1024;
        } else {
            return 0;  // CYCLES_UNSET, CYCLES_UNKNOWN, CYCLES_IGNORED, CYCLES_0
        }
    }

    private static int getEstimatedAssemblyNodeSize(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nsize = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                nsize += __castNodeSize(n.estimatedNodeSize());
            }
        }
        return nsize;
    }

    private static int __castNodeSize(NodeSize nsiz) {
        if (nsiz == NodeSize.SIZE_1) {
            return 1;
        } else if (nsiz == NodeSize.SIZE_2) {
            return 2;
        } else if (nsiz == NodeSize.SIZE_4) {
            return 4;
        } else if (nsiz == NodeSize.SIZE_8) {
            return 8;
        } else if (nsiz == NodeSize.SIZE_16) {
            return 16;
        } else if (nsiz == NodeSize.SIZE_32) {
            return 32;
        } else if (nsiz == NodeSize.SIZE_64) {
            return 64;
        } else if (nsiz == NodeSize.SIZE_128) {
            return 128;
        } else if (nsiz == NodeSize.SIZE_256) {
            return 256;
        } else if (nsiz == NodeSize.SIZE_512) {
            return 512;
        } else if (nsiz == NodeSize.SIZE_1024) {
            return 1024;
        } else {
            return 0;  // SIZE_UNSET, SIZE_UNKNOWN, SIZE_IGNORED, SIZE_0
        }
    }

    private static int getLoopDepth(List<Block> path) {
        if (path == null) {
            return 0;
        }
        return path.get(0).getLoopDepth();
    }

    private static int getMaxLoopDepth(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int maxdepth = 0;
        for (Block b : path) {
            if (b.getLoopDepth() > maxdepth) {
                maxdepth = b.getLoopDepth();
            }
        }
        return maxdepth;
    }

    private static int getNCond(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int ncond = 0;
        for (Block b : path) {
            if (b.getEndNode() instanceof ControlSplitNode) {
                ncond += 1;
            }
        }
        return ncond;
    }

    private static int getNInvoke(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int ninv = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof InvokeNode)
                    ninv += 1;
            }
        }
        return ninv;
    }

    private static int genNExceptions(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nexc = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof BytecodeExceptionNode || ((n instanceof InvokeNode) && (((InvokeNode) n).callTarget().targetName().equals("Throwable.fillInStackTrace")))) {  // todo: fix this with catching "Throwable" (unittest there is "<init>"); TODO: ThrowBytecodeExceptionNode in com.oracle.svm.core.graal.nodes; Why?
                    nexc += 1;
                }
            }
        }
        return nexc;
    }

    private static int getNAllocations(List<Block> path) {
        // The AbstractNewObjectNode is the base class for the new instance and new array nodes.
        if (path == null) {
            return 0;
        }
        int nnew = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof AbstractNewObjectNode) {
                    nnew += 1;
                }
            }
        }
        return nnew;
    }

    private static int getNSimpleInstr(List<Block> path, StructuredGraph.ScheduleResult schedule) {  // LASTEST scheduling is important (avoid catching by phi)
        if (path == null) {
            return 0;
        }
        int nsimple = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n instanceof BinaryNode || n instanceof LogicNode || n instanceof TernaryNode || n instanceof UnaryNode) {
                    nsimple++;
                }
            }
        }
        return nsimple;
    }

    private static int getNArrayAccess(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int narracc = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {  // its okay to go through fixed nodes
                if (n instanceof AccessArrayNode) {
                    narracc += 1;
                }
            }
        }
        return narracc;
    }

    private static int getNMonitor(List<Block> path) {
        // The {AccessMonitorNode} is the base class of both monitor acquisition and release.
        if (path == null) {
            return 0;
        }
        int nmonitor = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {  // its okay to go through fixed nodes
                if (n instanceof AccessMonitorNode) {
                    nmonitor += 1;
                }
            }
        }
        return nmonitor;
    }

    private static int getNMemoryAccess(List<Block> path, StructuredGraph.ScheduleResult schedule){
        if (path == null) {
            return 0;
        }
        int nmemacc = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n instanceof FixedAccessNode || n instanceof MemoryAccess) {
                    nmemacc++;
                }
            }
        }
        return nmemacc;
    }

}