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
package com.oracle.svm.hosted.phases;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Executable;
import java.lang.reflect.MalformedParametersException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import jdk.vm.ci.meta.ResolvedJavaMethod;

import com.oracle.svm.core.graal.nodes.ThrowBytecodeExceptionNode;
import com.oracle.svm.hosted.meta.HostedMethod;
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
import org.graalvm.compiler.nodes.calc.FloatingNode;
import org.graalvm.compiler.nodes.calc.TernaryNode;
import org.graalvm.compiler.nodes.calc.UnaryNode;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.nodes.extended.BytecodeExceptionNode;
import org.graalvm.compiler.nodes.java.*;
import org.graalvm.compiler.nodes.memory.MemoryAccess;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.graph.ReentrantBlockIterator;
import org.graalvm.compiler.phases.schedule.SchedulePhase;
import org.graalvm.compiler.replacements.arraycopy.ArrayCopyCallNode;
import org.graalvm.compiler.replacements.nodes.*;

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

    public ParseImportantFeaturesPhase(String methodRegex) {
        this.methodRegex = methodRegex;
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
//        for (Block b : schedule.getCFG().getBlocks())
//            System.err.println(b + ": " + schedule.nodesFor(b)); //+ "[" + b.getLoopDepth() + "]");
//        for (Node node : graph.getNodes()) {
//            System.err.println(node + ": " + (schedule.getNodeToBlockMap().get(node) != null ? schedule.getNodeToBlockMap().get(node).toString() : "null"));
//        }
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
            int depth = getCSDepth(cs, splits);
            while (__sons.advance()) {
                AbstractBeginNode sonHead = __sons.getKey();
                List<Block> sonPath = __sons.getValue();
                if (sonHead instanceof LoopExitNode) {
                    writerAttr.printf(",\"[x(%s)][null]\"", sonHead.toString());  // x is an abbreviation for LoopExitNode
                    writerAttr.printf("; NBlocks: [0][0]");
                    writerAttr.printf("; IRFixedNodeCount: [0][0]");
                    writerAttr.printf("; IRFloatingNodeCount: [0][0]");
                    writerAttr.printf("; EstimatedCPUCycles: [0][0]");
                    writerAttr.printf("; EstimatedAssemblySize: [0][0]");
                    writerAttr.printf("; EstimatedCPUCheap: [0][0]");
                    writerAttr.printf("; EstimatedCPUCExpns: [0][0]");
                    writerAttr.printf("; LoopDepth: [%d][0]", sonPath.get(0).getLoopDepth());
                    writerAttr.printf("; MaxLoopDepth: [%d][0]", sonPath.get(0).getLoopDepth());
                    writerAttr.printf("; NLoops: [0][0]");
                    writerAttr.printf("; NLoopExits: [1][0]");
                    writerAttr.printf("; NControlSplits: [0][0]");
                    writerAttr.printf("; CSDepth: [%d][%d]", depth, depth);
                    writerAttr.printf("; NInvoke: [0][0]");
                    writerAttr.printf("; NAllocations: [0][0]");
                    writerAttr.printf("; NMonitorEnter: [0][0]");
                    writerAttr.printf("; NMonitorExit: [0][0]");
                    writerAttr.printf("; NArrayLoad: [0][0]");
                    writerAttr.printf("; NArrayStore: [0][0]");
                    writerAttr.printf("; NArrayCompare: [0][0]");
                    writerAttr.printf("; NArrayCopy: [0][0]");
                    writerAttr.printf("; NExceptions: [0][0]");
                    writerAttr.printf("; NAssertions: [0][0]");
                    writerAttr.printf("; NControlSinks: [0][0]");
                    writerAttr.printf("; NConstNodes: [0][0]");
                    writerAttr.printf("; NLogicOperations: [0][0]");
                    writerAttr.printf("; NUnaryOperations: [0][0]");
                    writerAttr.printf("; NBinaryOperations: [0][0]");
                    writerAttr.printf("; NTernaryOperations: [0][0]");
                    writerAttr.printf("; NStaticLoadFields: [0][0]");
                    writerAttr.printf("; NInstanceLoadFields: [0][0]");
                    writerAttr.printf("; NStaticStoreFields: [0][0]");
                    writerAttr.printf("; NInstanceStoreFields: [0][0]");
                    writerAttr.printf("; NRawMemoryAccess: [0][0]");
                } else {
                    List<Block> pinnedPath = pinnedPaths.get(sonHead);
                    EconomicMap<String, Integer> sonData = getData(sonPath, schedule);
                    EconomicMap<String, Integer> pinnedData = getData(pinnedPath, schedule);
                    writerAttr.printf(",\"%s%s\"", sonPath, pinnedPath == null ? "[null]" : pinnedPath);
                    writerAttr.printf("; NBlocks: [%d][%d]", getNBlocks(sonPath), getNBlocks(pinnedPath));
                    writerAttr.printf("; IRFixedNodeCount: [%d][%d]", getIRFixedNodeCount(sonPath), getIRFixedNodeCount(pinnedPath));
                    writerAttr.printf("; IRFloatingNodeCount: [%d][%d]", getIRFloatingNodeCount(sonPath, schedule), getIRFloatingNodeCount(pinnedPath, schedule));
                    writerAttr.printf("; EstimatedCPUCycles: [%d][%d]", getEstimatedCPUCycles(sonPath, schedule), getEstimatedCPUCycles(pinnedPath, schedule));
                    writerAttr.printf("; EstimatedAssemblySize: [%d][%d]", getEstimatedAssemblySize(sonPath, schedule), getEstimatedAssemblySize(pinnedPath, schedule));
                    writerAttr.printf("; EstimatedCPUCheap: [%d][%d]", getNEstimatedCPUCheap(sonPath, schedule), getNEstimatedCPUCheap(pinnedPath, schedule));
                    writerAttr.printf("; EstimatedCPUCExpns: [%d][%d]", getNEstimatedCPUExpns(sonPath, schedule), getNEstimatedCPUExpns(pinnedPath, schedule));
                    writerAttr.printf("; LoopDepth: [%d][%d]", getLoopDepth(sonPath), getLoopDepth(pinnedPath));
                    writerAttr.printf("; MaxLoopDepth: [%d][%d]", getMaxLoopDepth(sonPath), getMaxLoopDepth(pinnedPath));
                    writerAttr.printf("; NLoops: [%d][%d]", getNLoops(sonPath), getNLoops(pinnedPath));
                    writerAttr.printf("; NLoopExits: [%d][%d]", getNLoopExits(sonPath), getNLoopExits(pinnedPath));
                    writerAttr.printf("; NControlSplits: [%d][%d]", getNControlSplits(sonPath), getNControlSplits(pinnedPath));
                    writerAttr.printf("; CSDepth: [%d][%d]", depth, depth);
                    writerAttr.printf("; NInvoke: [%d][%d]", getNInvoke(sonPath), getNInvoke(pinnedPath));
                    writerAttr.printf("; NAllocations: [%d][%d]", getNAllocations(sonPath), getNAllocations(pinnedPath));
                    writerAttr.printf("; NMonitorEnter: [%d][%d]", getNMonitorEnter(sonPath), getNMonitorEnter(pinnedPath));
                    writerAttr.printf("; NMonitorExit: [%d][%d]", getNMonitorExit(sonPath), getNMonitorExit(pinnedPath));
                    writerAttr.printf("; NArrayLoad: [%d][%d]", getNArrayLoad(sonPath), getNArrayLoad(pinnedPath));
                    writerAttr.printf("; NArrayStore: [%d][%d]", getNArrayStore(sonPath), getNArrayStore(pinnedPath));
                    writerAttr.printf("; NArrayCompare: [%d][%d]", getNArrayCompare(sonPath), getNArrayCompare(pinnedPath));
                    writerAttr.printf("; NArrayCopy: [%d][%d]", getNArrayCopy(sonPath), getNArrayCopy(pinnedPath));
                    writerAttr.printf("; NExceptions: [%d][%d]", getNExceptions(sonPath), getNExceptions(pinnedPath));
                    writerAttr.printf("; NAssertions: [%d][%d]", getNAssertions(sonPath), getNAssertions(pinnedPath));
                    writerAttr.printf("; NControlSinks: [%d][%d]", getNControlSinks(sonPath), getNControlSinks(pinnedPath));
                    writerAttr.printf("; NConstNodes: [%d][%d]", getNConstNodes(sonPath, schedule), getNConstNodes(pinnedPath, schedule));
                    writerAttr.printf("; NLogicOperations: [%d][%d]", getNLogicOperations(sonPath, schedule), getNLogicOperations(pinnedPath, schedule));
                    writerAttr.printf("; NUnaryOperations: [%d][%d]", getNUnaryOperations(sonPath, schedule), getNUnaryOperations(pinnedPath, schedule));
                    writerAttr.printf("; NBinaryOperations: [%d][%d]", getNBinaryOperations(sonPath, schedule), getNBinaryOperations(pinnedPath, schedule));
                    writerAttr.printf("; NTernaryOperations: [%d][%d]", getNTernaryOperations(sonPath, schedule), getNTernaryOperations(pinnedPath, schedule));
                    writerAttr.printf("; NStaticLoadFields: [%d][%d]", getNStaticLoadFields(sonPath), getNStaticLoadFields(pinnedPath));
                    writerAttr.printf("; NInstanceLoadFields: [%d][%d]", getNInstanceLoadFields(sonPath), getNInstanceLoadFields(pinnedPath));
                    writerAttr.printf("; NStaticStoreFields: [%d][%d]", getNStaticStoreFields(sonPath), getNStaticStoreFields(pinnedPath));
                    writerAttr.printf("; NInstanceStoreFields: [%d][%d]", getNInstanceStoreFields(sonPath), getNInstanceStoreFields(pinnedPath));
                    writerAttr.printf("; NRawMemoryAccess: [%d][%d]", getNRawMemoryAccess(sonPath, schedule), getNRawMemoryAccess(pinnedPath, schedule));

                    // CHECK
                    if(getNBlocks(sonPath)!=sonData.get("N. Blocks") || (pinnedData!=null && getNBlocks(pinnedPath)!=pinnedData.get("N. Blocks")))
                        System.out.println("1");
                    if(getIRFixedNodeCount(sonPath)!=sonData.get("IR Fixed Node Count") || (pinnedData!=null && getIRFixedNodeCount(pinnedPath)!=pinnedData.get("IR Fixed Node Count")))
                        System.out.println("2");
                    if(getIRFloatingNodeCount(sonPath, schedule)!=sonData.get("IR Floating Node Count") || (pinnedData!=null && getIRFloatingNodeCount(pinnedPath, schedule)!=pinnedData.get("IR Floating Node Count")))
                        System.out.println("3");
                    if(getEstimatedCPUCycles(sonPath, schedule)!=sonData.get("Estimated CPU Cycles") || (pinnedData!=null && getEstimatedCPUCycles(pinnedPath, schedule)!=pinnedData.get("Estimated CPU Cycles")))
                        System.out.println("4");
                    if(getEstimatedAssemblySize(sonPath, schedule)!=sonData.get("Estimated Assembly Size") || (pinnedData!=null && getEstimatedAssemblySize(pinnedPath, schedule)!=pinnedData.get("Estimated Assembly Size")))
                        System.out.println("5");
                     if(getNEstimatedCPUCheap(sonPath, schedule)!=sonData.get("N. Estimated CPU Cheap") || (pinnedData!=null && getNEstimatedCPUCheap(pinnedPath, schedule)!=pinnedData.get("N. Estimated CPU Cheap")))
                         System.out.println("6");
                    if(getNEstimatedCPUExpns(sonPath, schedule)!=sonData.get("N. Estimated CPU Expns") || (pinnedData!=null && getNEstimatedCPUExpns(pinnedPath, schedule)!=pinnedData.get("N. Estimated CPU Expns")))
                        System.out.println("7");
                    if(getLoopDepth(sonPath)!=sonData.get("Loop Depth") || (pinnedData!=null && getLoopDepth(pinnedPath)!=pinnedData.get("Loop Depth")))
                        System.out.println("8");
                    if(getMaxLoopDepth(sonPath)!=sonData.get("Max Loop Depth") || (pinnedData!=null && getMaxLoopDepth(pinnedPath)!=pinnedData.get("Max Loop Depth")))
                        System.out.println("9");
                    if(getNLoops(sonPath)!=sonData.get("N. Loops") || (pinnedData!=null && getNLoops(pinnedPath)!=pinnedData.get("N. Loops")))
                        System.out.println("10");
                    if(getNLoopExits(sonPath)!=sonData.get("N. Loop Exits") || (pinnedData!=null && getNLoopExits(pinnedPath)!=pinnedData.get("N. Loop Exits")))
                        System.out.println("11");
                    if(getNControlSplits(sonPath)!=sonData.get("N. Control Splits") || (pinnedData!=null && getNControlSplits(pinnedPath)!=pinnedData.get("N. Control Splits")))
                        System.out.println("12");
                    if(getNInvoke(sonPath)!=sonData.get("N. Invoke") || (pinnedData!=null && getNInvoke(pinnedPath)!=pinnedData.get("N. Invoke")))
                        System.out.println("13");
                    if(getNAllocations(sonPath)!=sonData.get("N. Allocations") || (pinnedData!=null && getNAllocations(pinnedPath)!=pinnedData.get("N. Allocations")))
                        System.out.println("14");
                    if(getNMonitorEnter(sonPath)!=sonData.get("N. Monitor Enter") || (pinnedData!=null && getNMonitorEnter(pinnedPath)!=pinnedData.get("N. Monitor Enter")))
                        System.out.println("15");
                    if(getNMonitorExit(sonPath)!=sonData.get("N. Monitor Exit") || (pinnedData!=null && getNMonitorExit(pinnedPath)!=pinnedData.get("N. Monitor Exit")))
                        System.out.println("16");
                    if(getNArrayLoad(sonPath)!=sonData.get("N. Array Load") || (pinnedData!=null && getNArrayLoad(pinnedPath)!=pinnedData.get("N. Array Load")))
                        System.out.println("17");
                    if(getNArrayStore(sonPath)!=sonData.get("N. Array Store") || (pinnedData!=null && getNArrayStore(pinnedPath)!=pinnedData.get("N. Array Store")))
                        System.out.println("18");
                    if(getNArrayCompare(sonPath)!=sonData.get("N. Array Compare") || (pinnedData!=null && getNArrayCompare(pinnedPath)!=pinnedData.get("N. Array Compare")))
                        System.out.println("19");
                    if(getNArrayCopy(sonPath)!=sonData.get("N. Array Copy") || (pinnedData!=null && getNArrayCopy(pinnedPath)!=pinnedData.get("N. Array Copy")))
                        System.out.println("20");
                    if(getNExceptions(sonPath)!=sonData.get("N. Exceptions") || (pinnedData!=null && getNExceptions(pinnedPath)!=pinnedData.get("N. Exceptions")))
                        System.out.println("12");
                    if(getNAssertions(sonPath)!=sonData.get("N. Assertions") || (pinnedData!=null && getNAssertions(pinnedPath)!=pinnedData.get("N. Assertions")))
                        System.out.println("12");
                    if(getNControlSinks(sonPath)!=sonData.get("N. Control Sinks") || (pinnedData!=null && getNControlSinks(pinnedPath)!=pinnedData.get("N. Control Sinks")))
                        System.out.println("12");
                    if(getNConstNodes(sonPath, schedule)!=sonData.get("N. Const. Nodes") || (pinnedData!=null && getNConstNodes(pinnedPath, schedule)!=pinnedData.get("N. Const. Nodes")))
                        System.out.println("13");
                    if(getNLogicOperations(sonPath, schedule)!=sonData.get("N. Logic Op.") || (pinnedData!=null && getNLogicOperations(pinnedPath, schedule)!=pinnedData.get("N. Logic Op.")))
                        System.out.println("13");
                    if(getNUnaryOperations(sonPath, schedule)!=sonData.get("N. Unary Op.") || (pinnedData!=null && getNUnaryOperations(pinnedPath, schedule)!=pinnedData.get("N. Unary Op.")))
                        System.out.println("13");
                    if(getNBinaryOperations(sonPath, schedule)!=sonData.get("N. Binary Op.") || (pinnedData!=null && getNBinaryOperations(pinnedPath, schedule)!=pinnedData.get("N. Binary Op.")))
                        System.out.println("13");
                    if(getNTernaryOperations(sonPath, schedule)!=sonData.get("N. Ternary Op.") || (pinnedData!=null && getNTernaryOperations(pinnedPath, schedule)!=pinnedData.get("N. Ternary Op.")))
                        System.out.println("13");
                    if(getNStaticLoadFields(sonPath)!=sonData.get("N. Static Load Fields") || (pinnedData!=null && getNStaticLoadFields(pinnedPath)!=pinnedData.get("N. Static Load Fields")))
                        System.out.println("13");
                    if(getNInstanceLoadFields(sonPath)!=sonData.get("N. Instance Load Fields") || (pinnedData!=null && getNInstanceLoadFields(pinnedPath)!=pinnedData.get("N. Instance Load Fields")))
                        System.out.println("13");
                    if(getNStaticStoreFields(sonPath)!=sonData.get("N. Static Store Fields") || (pinnedData!=null && getNStaticStoreFields(pinnedPath)!=pinnedData.get("N. Static Store Fields")))
                        System.out.println("13");
                    if(getNInstanceStoreFields(sonPath)!=sonData.get("N. Instance Store Fields") || (pinnedData!=null && getNInstanceStoreFields(pinnedPath)!=pinnedData.get("N. Instance Store Fields")))
                        System.out.println("13");
                    if(getNRawMemoryAccess(sonPath, schedule)!=sonData.get("N. Raw Memory Access") || (pinnedData!=null && getNRawMemoryAccess(pinnedPath, schedule)!=pinnedData.get("N. Raw Memory Access")))
                        System.out.println("21");
                    // CHECK END
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

    private static EconomicMap<String, Integer> getData(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null)
            return null;
        EconomicMap<String, Integer> data = EconomicMap.create(Equivalence.IDENTITY);
        int nblocks = path.size();  // N. Blocks
        int nfixed = 0;             // IR Fixed Node Count
        int nfloat = 0;             // IR Floating Node Count
        int ecycles = 0;            // Estimated CPU Cycles
        int eassembly = 0;          // Estimated Assembly Size
        int necpucheap = 0;         // N. Estimated CPU Cheap
        int necpuexpns = 0;         // N. Estimated CPU Expensive
        int loopdepth = path.get(0).getLoopDepth();  // Loop Depth
        int maxloopdepth = 0;                        // Max Loop Depth
        int nloops = 0;                              // N. Loops
        int nloopexits = 0;                          // N. Loop Exits
        int ncontrolsplits = 0;     // N. Control Splits
        int ninvoke = 0;            // N. Invoke
        int nalloc = 0;             // N. Allocations
        int nexceptions = 0;        // N. Exceptions
        int nassertions = 0;        // N. Assertions
        int ncontrolsinks = 0;      // N. Control Sinks
        int nmonenter = 0;          // N. Monitor Enter
        int nmonexit = 0;           // N. Monitor Exit
        int narrload = 0;           // N. Array Load
        int narrstore = 0;          // N. Array Store
        int narrcompare = 0;        // N. Array Compare
        int narrcopy = 0;           // N. Array Copy
        int nconst = 0;             // N. Const. Nodes
        int nlogic = 0;             // N. Logic Op.
        int nunary = 0;             // N. Unary Op.
        int nbinary = 0;            // N. Binary Op.
        int nternary = 0;           // N. Ternary Op.
        int nstaticload = 0;        // N. Static Load Fields
        int ninstload = 0;          // N. Instance Load Fields
        int nstaticstore = 0;       // N. Static Store Fields
        int ninststore = 0;         // N. Instance Store Fields
        int nrawmemaccess = 0;      // N. Raw Memory Access

        for (Block block : path) {
            for (Node node : schedule.nodesFor(block)) {
                // IR Fixed Node Count
                if (node instanceof FixedNode) {
                    nfixed++;
                }

                // IR Floating Node Count
                if (node instanceof FloatingNode) {
                    nfloat++;
                }

                // Estimated CPU Cycles
                ecycles += __castNodeCycles(node.estimatedNodeCycles());

                // Estimated Assembly Size
                eassembly += __castNodeSize(node.estimatedNodeSize());

                // N. Estimated CPU Cheap
                if (node.estimatedNodeCycles() == NodeCycles.CYCLES_0 || node.estimatedNodeCycles() == NodeCycles.CYCLES_1) {
                    necpucheap++;
                }

                // N. Estimated CPU Expensive
                if (node.estimatedNodeCycles() == NodeCycles.CYCLES_1024 || node.estimatedNodeCycles() == NodeCycles.CYCLES_512 || node.estimatedNodeCycles() == NodeCycles.CYCLES_256 || node.estimatedNodeCycles() == NodeCycles.CYCLES_128 || node.estimatedNodeCycles() == NodeCycles.CYCLES_64) {
                    necpuexpns++;
                }

                // N. Loop Exits
                if (node instanceof LoopExitNode) {
                    nloopexits++;
                }

                // N. Invoke
                if (node instanceof InvokeNode)
                    ninvoke++;

                // N. Allocations
                if (node instanceof AbstractNewObjectNode) {  // The AbstractNewObjectNode is the base class for the new instance and new array nodes.
                    nalloc++;
                }

                // N. Exceptions
                if (node instanceof BytecodeExceptionNode || node instanceof ThrowBytecodeExceptionNode) {
                    nexceptions++;
                } else if (node instanceof InvokeNode) {
                    ResolvedJavaMethod tmethod = ((InvokeNode) node).callTarget().targetMethod();
                    if (tmethod instanceof HostedMethod) {
                        try {
                            Executable jmethod = ((HostedMethod) tmethod).getJavaMethod();
                            if (jmethod.getDeclaringClass() == java.lang.Throwable.class && jmethod.equals(Throwable.class.getMethod("fillInStackTrace"))) {
                                nexceptions++;
                            }
                        } catch (NoSuchMethodException e) {
                            e.printStackTrace();
                        } catch (MalformedParametersException e) {
                            // its okay if there is no such a method, just don't increase the counter
                        }
                    }
                }

                // N. Assertions
                if (node instanceof AssertionNode) {
                    nassertions++;
                } else if (node instanceof InvokeNode) {
                    ResolvedJavaMethod tmethod = ((InvokeNode) node).callTarget().targetMethod();
                    if (tmethod instanceof HostedMethod) {
                        try {
                            Executable jmethod = ((HostedMethod) tmethod).getJavaMethod();
                            if (jmethod.getDeclaringClass() == java.lang.AssertionError.class) {
                                nassertions++;
                            }
                        } catch (MalformedParametersException e) {
                            // its okay if there is "Wrong number of parameters in MethodParameters attribute" just don't increase the counter
                        }
                    }
                }

                // N. Control Sinks
                if (node instanceof ControlSinkNode) {
                    ncontrolsinks++;
                }

                // N. Monitor Enter
                if (node instanceof MonitorEnterNode || node instanceof RawMonitorEnterNode) {
                    nmonenter++;
                }

                // N. Monitor Exit
                if (node instanceof MonitorExitNode) {
                    nmonexit++;
                }

                // N. Array Load
                if (node instanceof LoadIndexedNode) {
                    narrload++;
                }

                // N. Array Store
                if (node instanceof StoreIndexedNode) {
                    narrstore++;
                }

                // N. Array Compare
                if (node instanceof ArrayCompareToNode || node instanceof ArrayEqualsNode || node instanceof ArrayRegionEqualsNode) {
                    narrcompare++;
                } else if (node instanceof InvokeNode) {
                    ResolvedJavaMethod tmethod = ((InvokeNode) node).callTarget().targetMethod();
                    if (tmethod instanceof HostedMethod) {
                        try {
                            Executable jmethod = ((HostedMethod) tmethod).getJavaMethod();
                            if (jmethod.getDeclaringClass() == Arrays.class && jmethod.equals(Arrays.class.getMethod("compare", jmethod.getParameterTypes()))) {
                                narrcompare++;
                            }
                        } catch (NoSuchMethodException e) {
                            // it's okay if there no such a method, just don't increase the counter
                        } catch (MalformedParametersException e) {
                            // it' okay if there no valid parameters
                        }
                    }
                }

                // N. Array Copy
                if (node instanceof BasicArrayCopyNode || node instanceof ArrayCopyCallNode) {
                    narrcopy++;
                }

                // N. Const Nodes
                if (node instanceof ConstantNode) {
                    nconst++;
                }

                // N. Logic Op.
                if (node instanceof LogicNode) {
                    nlogic++;
                }

                // N. Unary Op.
                if (node instanceof UnaryNode) {
                    nunary++;
                }

                // N. Binary Op.
                if (node instanceof BinaryNode) {
                    nbinary++;
                }

                // N. Ternary Op.
                if (node instanceof TernaryNode) {
                    nternary++;
                }

                // N. Static Load Fields
                // N. Instance Load Fields
                if (node instanceof LoadFieldNode) {
                    if (((LoadFieldNode) node).isStatic()) {
                        nstaticload++;
                    } else {
                        ninstload++;
                    }
                }

                // N. Static Store Fields
                // N. Instance Store Fields
                if (node instanceof StoreFieldNode) {
                    if (((StoreFieldNode) node).isStatic()) {
                        nstaticstore++;
                    } else {
                        ninststore++;
                    }
                }

                // N. Raw Memory Access
                if (node instanceof MemoryAccess) {
                    nrawmemaccess++;
                }
            }

            // Max Loop Depth
            if (block.getLoopDepth() > maxloopdepth) {
                maxloopdepth = block.getLoopDepth();
            }

            // N. Loops
            if (block.isLoopHeader()) {
                nloops++;
            }

            // N. Control Splits
            if (block.getEndNode() instanceof ControlSplitNode) {
                ncontrolsplits++;
            }
        }

        data.put("N. Blocks", nblocks);
        data.put("IR Fixed Node Count", nfixed);
        data.put("IR Floating Node Count", nfloat);
        data.put("Estimated CPU Cycles", ecycles);
        data.put("Estimated Assembly Size", eassembly);
        data.put("N. Estimated CPU Cheap", necpucheap);
        data.put("N. Estimated CPU Expns", necpuexpns);
        data.put("Loop Depth", loopdepth);
        data.put("Max Loop Depth", maxloopdepth);
        data.put("N. Loops", nloops);
        data.put("N. Loop Exits", nloopexits);
        data.put("N. Control Splits", ncontrolsplits);
        data.put("N. Invoke", ninvoke);
        data.put("N. Allocations", nalloc);
        data.put("N. Exceptions", nexceptions);
        data.put("N. Assertions", nassertions);
        data.put("N. Control Sinks", ncontrolsinks);
        data.put("N. Monitor Enter", nmonenter);
        data.put("N. Monitor Exit", nmonexit);
        data.put("N. Array Load", narrload);
        data.put("N. Array Store", narrstore);
        data.put("N. Array Compare", narrcompare);
        data.put("N. Array Copy", narrcopy);
        data.put("N. Const. Nodes", nconst);
        data.put("N. Logic Op.", nlogic);
        data.put("N. Unary Op.", nunary);
        data.put("N. Binary Op.", nbinary);
        data.put("N. Ternary Op.", nternary);
        data.put("N. Static Load Fields", nstaticload);
        data.put("N. Instance Load Fields", ninstload);
        data.put("N. Static Store Fields", nstaticstore);
        data.put("N. Instance Store Fields", ninststore);
        data.put("N. Raw Memory Access", nrawmemaccess);

        return data;
    }

    /* Util functions that parse important attributes of blocks */
    private static int getNBlocks(List<Block> path) {
        if (path == null)
            return 0;
        return path.size();
    }

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

    private static int getIRFloatingNodeCount(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nfloat = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {  // annotation: phi nodes aren't counted - they are naturally connected with AbstractMerge and LoopBegin nodes
                if (n instanceof FloatingNode) {
                    nfloat++;
                }
            }
        }
        return nfloat;
    }

    private static int getEstimatedCPUCycles(List<Block> path, StructuredGraph.ScheduleResult schedule) {
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

    private static int getEstimatedAssemblySize(List<Block> path, StructuredGraph.ScheduleResult schedule) {
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

    private static int getNEstimatedCPUCheap(List<Block> path, StructuredGraph.ScheduleResult schedule) {  // CYCLES_0 || CYCLES_1
        if (path == null) {
            return 0;
        }
        int nchp = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n.estimatedNodeCycles() == NodeCycles.CYCLES_0 || n.estimatedNodeCycles() == NodeCycles.CYCLES_1) {
                    nchp++;
                }
            }
        }
        return nchp;
    }

    private static int getNEstimatedCPUExpns(List<Block> path, StructuredGraph.ScheduleResult schedule) {  // CYCLES_1024 || CYCLES_512 || CYCLES_256 || CYCLES_128 || CYCLES_64
        if (path == null) {
            return 0;
        }
        int nexp = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n.estimatedNodeCycles() == NodeCycles.CYCLES_1024 || n.estimatedNodeCycles() == NodeCycles.CYCLES_512 || n.estimatedNodeCycles() == NodeCycles.CYCLES_256 || n.estimatedNodeCycles() == NodeCycles.CYCLES_128 || n.estimatedNodeCycles() == NodeCycles.CYCLES_64) {
                    nexp++;
                }
            }
        }
        return nexp;
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

    private static int getNLoops(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nloops = 0;
        for (Block b : path) {
            if (b.isLoopHeader()) {
                nloops++;
            }
        }
        return nloops;
    }

    private static int getNLoopExits(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nlexit = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof LoopExitNode) {
                    nlexit++;
                }
            }
        }
        return nlexit;
    }

    private static int getNControlSplits(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int ncs = 0;
        for (Block b : path) {
            if (b.getEndNode() instanceof ControlSplitNode) {
                ncs += 1;
            }
        }
        return ncs;
    }

    private static int getCSDepth(ControlSplit cs, Stack<ControlSplit> splits) {
        int depth = 0;
        Block head = cs.getBlock();
        List<Block> pathToBlock = cs.getPathToBlock();

        for (ControlSplit tmpCs : splits) {
            if (!tmpCs.finished()) {
                if (pathToBlock == null || pathToBlock.size() == 0 || !tmpCs.areInTails(pathToBlock.get(0).getBeginNode())) {  // todo: fix this by gentleman solution
                    depth++;
                }
            }
            pathToBlock = tmpCs.getPathToBlock();
        }
        return depth;
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

    private static int getNAllocations(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nnew = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof AbstractNewObjectNode) {  // The AbstractNewObjectNode is the base class for the new instance and new array nodes.
                    nnew += 1;
                }
            }
        }
        return nnew;
    }

    private static int getNMonitorEnter(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nmonacq = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof MonitorEnterNode || n instanceof RawMonitorEnterNode) {
                    nmonacq += 1;
                }
            }
        }
        return nmonacq;
    }

    private static int getNMonitorExit(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nexit = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof MonitorExitNode) {
                    nexit += 1;
                }
            }
        }
        return nexit;
    }

    private static int getNArrayLoad(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nload = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {  // its okay to go through fixed nodes
                if (n instanceof LoadIndexedNode) {
                    nload += 1;
                }
            }
        }
        return nload;
    }

    private static int getNArrayStore(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nstore = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {  // its okay to go through fixed nodes
                if (n instanceof StoreIndexedNode) {
                    nstore += 1;
                }
            }
        }
        return nstore;
    }

    private static int getNArrayCompare(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int ncmp = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof ArrayCompareToNode || n instanceof ArrayEqualsNode || n instanceof ArrayRegionEqualsNode) {
                    ncmp++;
                } else if (n instanceof InvokeNode) {
                    ResolvedJavaMethod tmethod = ((InvokeNode) n).callTarget().targetMethod();
                    if (!(tmethod instanceof HostedMethod)) {
                        continue;
                    }
                    try {
                        Executable jmethod = ((HostedMethod) tmethod).getJavaMethod();
                        if (jmethod.getDeclaringClass() == Arrays.class && jmethod.equals(Arrays.class.getMethod("compare", jmethod.getParameterTypes()))) {
                            ncmp++;
                        }
                    } catch (NoSuchMethodException e) {
                        // it's okay if there no such a method, just don't increase the counter
                    } catch (MalformedParametersException e) {
                        // it' okay if there no valid parameters
                    }
                }
            }
        }
        return ncmp;
    }

    private static int getNArrayCopy(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int ncpy = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof BasicArrayCopyNode || n instanceof ArrayCopyCallNode) {
                    ncpy += 1;
                }
            }
        }
        return ncpy;
    }

    private static int getNExceptions(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nexc = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof BytecodeExceptionNode || n instanceof ThrowBytecodeExceptionNode) {
                    nexc++;
                } else if (n instanceof InvokeNode) {
                    ResolvedJavaMethod tmethod = ((InvokeNode) n).callTarget().targetMethod();
                    if (!(tmethod instanceof HostedMethod)) {
                        continue;
                    }
                    try {
                        Executable jmethod = ((HostedMethod) tmethod).getJavaMethod();
                        if (jmethod.getDeclaringClass() == java.lang.Throwable.class && jmethod.equals(Throwable.class.getMethod("fillInStackTrace"))) {
                            nexc++;
                        }
                    } catch (NoSuchMethodException e) {
                        e.printStackTrace();
                    } catch (MalformedParametersException e) {
                        // its okay if there is no such a method, just don't increase the counter
                    }
                }
            }
        }
        return nexc;
    }

    private static int getNAssertions(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nass = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof AssertionNode) {
                    nass++;
                } else if (n instanceof InvokeNode) {
                    ResolvedJavaMethod tmethod = ((InvokeNode) n).callTarget().targetMethod();
                    if (!(tmethod instanceof HostedMethod)) {
                        continue;
                    }
                    try {
                        Executable jmethod = ((HostedMethod) tmethod).getJavaMethod();
                        if (jmethod.getDeclaringClass() == java.lang.AssertionError.class) {
                            nass++;
                        }
                    } catch (MalformedParametersException e) {
                        // its okay if there is "Wrong number of parameters in MethodParameters attribute" just don't increase the counter
                    }
                }
            }
        }
        return nass;
    }

    private static int getNControlSinks(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int ncs = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof ControlSinkNode) {
                    ncs++;
                }
            }
        }
        return ncs;
    }

    // LASTEST scheduling is important (to avoid catching by phi)
    private static int getNConstNodes(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nconst = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n instanceof ConstantNode) {
                    nconst++;
                }
            }
        }
        return nconst;
    }

    private static int getNLogicOperations(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nconst = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n instanceof LogicNode) {
                    nconst++;
                }
            }
        }
        return nconst;
    }

    private static int getNUnaryOperations(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nuna = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n instanceof UnaryNode) {
                    nuna++;
                }
            }
        }
        return nuna;
    }

    private static int getNBinaryOperations(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nbin = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n instanceof BinaryNode) {
                    nbin++;
                }
            }
        }
        return nbin;
    }

    private static int getNTernaryOperations(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nter = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n instanceof TernaryNode) {
                    nter++;
                }
            }
        }
        return nter;
    }

    private static int getNStaticLoadFields(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nstaticload = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof LoadFieldNode && ((LoadFieldNode) n).isStatic()) {
                    nstaticload++;
                }
            }
        }
        return nstaticload;
    }

    private static int getNInstanceLoadFields(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int ninstload = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof LoadFieldNode && !((LoadFieldNode) n).isStatic()) {
                    ninstload++;
                }
            }
        }
        return ninstload;
    }

    private static int getNStaticStoreFields(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int nstaticstore = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof StoreFieldNode && ((StoreFieldNode) n).isStatic()) {
                    nstaticstore++;
                }
            }
        }
        return nstaticstore;
    }

    private static int getNInstanceStoreFields(List<Block> path) {
        if (path == null) {
            return 0;
        }
        int ninststore = 0;
        for (Block b : path) {
            for (Node n : b.getNodes()) {
                if (n instanceof StoreFieldNode && !((StoreFieldNode) n).isStatic()) {
                    ninststore++;
                }
            }
        }
        return ninststore;
    }

    private static int getNRawMemoryAccess(List<Block> path, StructuredGraph.ScheduleResult schedule) {
        if (path == null) {
            return 0;
        }
        int nmemacc = 0;
        for (Block b : path) {
            for (Node n : schedule.nodesFor(b)) {
                if (n instanceof MemoryAccess) {
                    nmemacc++;
                }
            }
        }
        return nmemacc;
    }

}