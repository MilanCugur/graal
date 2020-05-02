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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA.
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.phases;

import java.io.*;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;

import com.oracle.graal.pointsto.infrastructure.WrappedJavaMethod;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.core.graal.nodes.ThrowBytecodeExceptionNode;
import com.oracle.svm.hosted.meta.HostedMethod;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;
import org.graalvm.collections.UnmodifiableMapCursor;
import org.graalvm.compiler.core.common.cfg.Loop;
import org.graalvm.compiler.debug.MethodFilter;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodeinfo.NodeCycles;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.calc.BinaryNode;
import org.graalvm.compiler.nodes.calc.FloatingNode;
import org.graalvm.compiler.nodes.calc.TernaryNode;
import org.graalvm.compiler.nodes.calc.UnaryNode;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.nodes.extended.BytecodeExceptionNode;
import org.graalvm.compiler.nodes.extended.SwitchNode;
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
import org.graalvm.compiler.replacements.nodes.arithmetic.IntegerExactArithmeticSplitNode;

/***
 * In order to estimate the probabilities for each of the control splits branches in the Graal's IR graph, first of all, we need to parse important features of each of them.
 * This class is used for extracting important attributes for each branch each of the Graal IR graph control splits.
 ***/

/* Representation of a control split */
class ControlSplit {
    private Block block;                                             // Block ending with the control split node
    private List<Block> pathToBlock;                                 // The path leading to this block
    private EconomicSet<AbstractBeginNode> sonsHeads;                // Head nodes of sons I am waiting for
    private EconomicMap<AbstractBeginNode, List<Block>> sonsBlocks;  // Completed sons
    private EconomicSet<AbstractBeginNode> tailHeads;                // If I go through my personal merge and I am not complete at that time. Simply code propagation to predecessor control splits. If I am finished at my personal merge but that merge is continue-in-switch caused.
    private EconomicMap<AbstractBeginNode, List<Block>> tailBlocks;  // Tail blocks appended to this control split, for propagation to father blocks
    private EconomicMap<AbstractBeginNode, List<Block>> pinnedPaths; // Asymmetric switch case: paths that go out from each son to the new end
    private EconomicMap<AbstractBeginNode, Integer> sonsKeys;        // Sons ordering - due to profiles parsing

    public ControlSplit(Block block, List<Block> path) {
        assert block.getEndNode() instanceof ControlSplitNode : "ParseImportantFeaturesError: Control Split can be instantiated only with Control Split Node (as end).";
        this.block = block;
        this.pathToBlock = new ArrayList<>(path);
        this.sonsBlocks = EconomicMap.create(Equivalence.DEFAULT);
        this.sonsHeads = EconomicSet.create(Equivalence.DEFAULT);
        this.sonsKeys = EconomicMap.create(Equivalence.DEFAULT);
        for (int i = 0; i < block.getSuccessorCount(); i++) {
            AbstractBeginNode sonHead = block.getSuccessors()[i].getBeginNode();
            this.sonsHeads.add(sonHead);
        }
        if (block.getEndNode() instanceof IfNode) {
            IfNode cs = (IfNode) block.getEndNode();
            this.sonsKeys.put(cs.trueSuccessor(), 0);
            this.sonsKeys.put(cs.falseSuccessor(), 1);
        } else if (block.getEndNode() instanceof InvokeWithExceptionNode) {
            InvokeWithExceptionNode cs = (InvokeWithExceptionNode) block.getEndNode();
            this.sonsKeys.put(cs.getPrimarySuccessor(), 0);
            this.sonsKeys.put(cs.exceptionEdge(), 1);
        } else if (block.getEndNode() instanceof IntegerExactArithmeticSplitNode) {
            IntegerExactArithmeticSplitNode cs = (IntegerExactArithmeticSplitNode) block.getEndNode();
            this.sonsKeys.put(cs.getPrimarySuccessor(), 0);
            this.sonsKeys.put(cs.getOverflowSuccessor(), 1);
        } else {
            assert block.getEndNode() instanceof SwitchNode : "ParseImportantFeaturesError: unknown control split.";
            for (int i = 0; i < block.getSuccessorCount(); i++) {
                AbstractBeginNode sonHead = block.getSuccessors()[i].getBeginNode();
                this.sonsKeys.put(sonHead, i); // Assume ordered 1, 2, ..., n
            }
        }
        this.tailHeads = EconomicSet.create(Equivalence.DEFAULT);
        this.tailBlocks = EconomicMap.create(Equivalence.DEFAULT);
        this.pinnedPaths = null;
    }

    public Integer getSonKey(AbstractBeginNode sonHead) {
        assert this.sonsKeys.containsKey(sonHead) : "ParseImportantFeaturesPhaseError: Searching for the key of the invalid son.";
        return this.sonsKeys.get(sonHead);
    }

    public Block getBlock() {
        return this.block;
    }

    public List<Block> getPathToBlock() {
        return pathToBlock;
    }

    // Sons operations
    public int liveSons() {
        return this.sonsHeads.size();
    }

    public Boolean finished() {  // Are control split on top of the stack finished?
        if (this.sonsHeads.isEmpty()) {
            if (this.block.getSuccessorCount() == 2) {  // If/Invoke control split
                return true;
            } else {  // Switch control split [sons tails purpose]
                EconomicSet<AbstractMergeNode> reachable = EconomicSet.create(Equivalence.DEFAULT);
                for (List<Block> son : this.getSonsPaths()) {
                    reachable.addAll(__pathReachable(son));  // Add son's reachable merge nodes
                }
                for (List<Block> tail : this.getTailsPaths()) {
                    reachable.remove((AbstractMergeNode) tail.get(0).getBeginNode());  // Remove already reached merge nodes
                }
                // All son's merges are reached
                return reachable.size() == 0;
            }
        } else {
            return false;
        }
    }

    public UnmodifiableMapCursor<AbstractBeginNode, List<Block>> getSons() {  // main getter
        return this.sonsBlocks.getEntries();
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

    public boolean areInTails(AbstractBeginNode node) {  // check
        return this.tailHeads.contains(node);
    }

    // Pinned paths operations
    public EconomicMap<AbstractBeginNode, List<Block>> getPinnedPaths() {
        return this.pinnedPaths;
    }

    public void sonsConcat() {  // Concatenate sons of switch control split; additionally calculate pinned paths
        assert this.liveSons() == 0 : "ParseImportantFeaturesPhaseError: Cannot concat sons of unfinished control split.";
        EconomicMap<AbstractBeginNode, List<Block>> pinnedPaths = EconomicMap.create(Equivalence.DEFAULT);  // pinned sons paths

        if (this.getBlock().getSuccessorCount() > 2) {
            EconomicMap<AbstractBeginNode, List<Block>> __fulltails = this.tailBlocks;  // cs tails map
            EconomicMap<AbstractBeginNode, List<Block>> __fullsons = this.sonsBlocks;  // cs sons map

            UnmodifiableMapCursor<AbstractBeginNode, List<Block>> __sons = this.getSons();
            while (__sons.advance()) {
                AbstractBeginNode sonHead = __sons.getKey();
                List<Block> sonPath = __sons.getValue();

                pinnedPaths.put(sonHead, null);  // initially put null value

                List<Block> newMeat = new ArrayList<>(sonPath);
                while (true) {  // traverse following sons path
                    EconomicSet<AbstractMergeNode> sonEnds = __pathReachable(newMeat);
                    newMeat.clear();
                    if (sonEnds.isEmpty()) {
                        break;
                    }
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
                    } else {
                        sonPath.addAll(new ArrayList<>(newMeat));
                    }
                }

                __fullsons.put(sonHead, sonPath);  // [eventually] replace sons body
            }
            EconomicSet<List<Block>> tmp = EconomicSet.create(Equivalence.DEFAULT);  // If all sons have the same pinned path, don't use it at all
            boolean nullExists = false;
            for (List<Block> elem : pinnedPaths.getValues()) {
                if (elem != null) {
                    tmp.add(elem);
                } else {
                    nullExists = true;
                }
            }
            if (tmp.size() == 1 && !nullExists) {
                pinnedPaths.clear();
            }
        }
        this.pinnedPaths = pinnedPaths;
    }

    private static boolean __hasInnerExit(List<Block> path, Iterable<AbstractBeginNode> hostNodes) {
        // Return true if the path has reachable merge nodes in the set of the hostNodes nodes
        EconomicSet<AbstractMergeNode> reach = __pathReachable(path);
        for (AbstractBeginNode hostNode : hostNodes) {
            if (reach.contains((AbstractMergeNode) hostNode)) {
                return true;
            }
        }
        return false;
    }

    private static EconomicSet<AbstractMergeNode> __pathReachable(List<Block> path) {
        // Return set of Merge nodes which are reachable by the current path
        EconomicSet<AbstractMergeNode> reach = EconomicSet.create(Equivalence.DEFAULT);
        for (Block b : path) {
            if (b.getEndNode() instanceof AbstractEndNode) {
                Block succ = b.getFirstSuccessor();
                if (succ.getBeginNode() instanceof AbstractMergeNode) {
                    reach.add((AbstractMergeNode) succ.getBeginNode());
                }
            }
        }
        for (Block b : path) {
            if (b.getBeginNode() instanceof AbstractMergeNode) {
                reach.remove((AbstractMergeNode) b.getBeginNode());  // return only real-reachable
            }
        }
        return reach;
    }
}

/* Graph traversal intermediate state representation */
class TraversalState {
    private List<Block> path;  // List of blocks visited so far

    public TraversalState() {
        this.path = new ArrayList<>();
    }

    public TraversalState(List<Block> path) {
        if (path == null) {
            this.path = new ArrayList<>();
        } else {
            this.path = new ArrayList<>(path);
        }
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

    private String methodRegex;  // Functions targeted for attribute parsing
    private static String PATH;  // Results directory

    static {
        PATH = "importantAttributes" + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Timestamp(System.currentTimeMillis()));
        boolean dirExist = new File(PATH).mkdir();
        assert dirExist : "ParseImportantFeaturesPhaseError: Cannot create a directory.";
    }

    public ParseImportantFeaturesPhase(String methodRegex) {
        this.methodRegex = methodRegex;
    }

    public static class Options {
        // @formatter:off
        @Option(help = "Parse important features from Graal IR Graph.", type = OptionType.Expert)
        public static final OptionKey<Boolean> ParseImportantFeatures = new OptionKey<>(false);
        // @formatter:on
    }

    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {
        if (methodRegex != null) {  // If Method Filter is not specified, parse all functions, otherwise parse only desired function[s]
            MethodFilter mf = MethodFilter.parse(methodRegex);
            if (!mf.matches(graph.method()))  // If Method Filter is specified, parse only target function[s]
                return;
        }

        // Block and nodes integration
        ControlFlowGraph cfg = ControlFlowGraph.compute(graph, true, true, true, true);
        try {
            SchedulePhase.run(graph, SchedulePhase.SchedulingStrategy.LATEST, cfg);  // Do [Latest] scheduling because of floating point nodes
        } catch (Throwable t) {
            throw graph.getDebug().handle(t);
        }
        StructuredGraph.ScheduleResult schedule = graph.getLastSchedule();

        Stack<ControlSplit> splits = new Stack<>();      // Active Control Splits
        List<ControlSplit> fsplits = new ArrayList<>();  // Finished Control Splits Data

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
                        if (targetCS != null) {
                            targetCS.addASon(currentState.getPath());
                        } else {  // If no one waits for me as a son, look at a their tails
                            targetCS = findTailFather(splits, currentState.getPath());
                            if (targetCS != null) {
                                targetCS.setTailBlocks(currentState.getPath());
                            }
                        }
                        // else - no one to catch
                        // currentState path will be reset on ReentrantBlockIterator.java, @ line 170.
                    } else if (block.getSuccessors().length == 1) {  // I have only one successor
                        // End before loops aren't end of any Control Split branches: simply skip that end (in the term of adding a son/tail)
                        // If next is LoopBeginNode: if curr is loop end: add path as a someone son/tail, else: skip adding path as a anyone son/tail
                        if (block.getEndNode() instanceof AbstractEndNode && (block.isLoopEnd() || !(block.getFirstSuccessor().getBeginNode() instanceof LoopBeginNode))) {
                            ControlSplit targetCS = findControlSplitFather(splits, currentState.getPath());
                            if (targetCS != null) {
                                targetCS.addASon(currentState.getPath());
                            } else {  // If no one waits for me as a son, look at a theirs tails
                                targetCS = findTailFather(splits, currentState.getPath());
                                if (targetCS != null) {
                                    targetCS.setTailBlocks(currentState.getPath());
                                }
                            }
                            // else - no one to catch
                            // currentState path will be reset on ReentrantBlockIterator.java, @ line 170, eventually @ line 147.
                        }
                    } else {
                        assert false : "Node with more than one successors hasn't caught as a Control Split Node.";
                    }
                }
                return currentState;  // This will be used only on FixedWithNextNode process
            }

            @Override
            protected TraversalState merge(Block merge, List<TraversalState> __states) {
                // ___states are used internally by ReentrantBlockIterator in order to ensure that the graph is properly visited

                while (splits.size() > 0) {
                    if (splits.peek().finished()) {
                        // Finished Control Split (on top of the stack)
                        ControlSplit stacksTop = splits.peek();

                        // My new path
                        List<Block> newPath = writeOutFromStack(splits, fsplits);

                        // Try to eventually add a son
                        if (splits.size() > 0) {
                            ControlSplit fatherCS, tailCS;
                            fatherCS = findControlSplitFather(splits, newPath);
                            tailCS = findTailFather(splits, newPath);
                            if (fatherCS != null) {
                                // If tis my personal merge continue, else push as a son
                                if (personalMerge(stacksTop, (AbstractMergeNode) merge.getBeginNode())) {
                                    return new TraversalState(newPath);
                                } else {
                                    fatherCS.addASon(newPath);
                                }
                            } else if (tailCS != null) {
                                // If its my personal merge continue, else push as a tail
                                if (personalMerge(stacksTop, (AbstractMergeNode) merge.getBeginNode())) {
                                    return new TraversalState(newPath);
                                } else {
                                    tailCS.setTailBlocks(newPath);
                                }
                            } // else continue: son not added; No one is waiting for me
                        }
                    } else {
                        // Going through uncompleted (personal) merge (merge which all ends were visited, but appropriate control split isn't finished)
                        // A Control Split on the top of the splits firstly was finished, then popped up and added as a son or tail, then loop were continued, then control split on top of the stack aren't finished: further go on merge node deeper with empty path, later on, when finish that Control Split, just do regularly
                        // If on top of the stack are switch control split which is not fully finished: should propagate through that merge, and add merge as a cs tail. Later on, eventually add it to the appropriate merge forward ends as their part or simply propagate it upwards [switches tails caused]
                        splits.peek().setTailNode(merge.getBeginNode()); // Add as a tail
                        return new TraversalState();  // Clear path
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
            List<Block> newPath = writeOutFromStack(splits, fsplits);

            // Try to eventually add a son
            if (splits.size() > 0) {
                ControlSplit fatherCS, tailCS;
                fatherCS = findControlSplitFather(splits, newPath);
                tailCS = findTailFather(splits, newPath);
                if (fatherCS != null) {
                    fatherCS.addASon(newPath);
                } else if (tailCS != null) {
                    tailCS.setTailBlocks(newPath);
                }  // else continue: son/tail not added; No one is waiting for me, continue to flushing splits
            }
        }

        flushToDb(fsplits, graph, schedule);  // Flush important attributes to database
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

    private static List<Block> writeOutFromStack(Stack<ControlSplit> splits, List<ControlSplit> fsplits) {
        // Pop element from the top of a stack and append it to the list of finished Control Splits; return integrated path
        assert splits.size() > 0 && splits.peek().liveSons() == 0 : "ParseImportantFeaturesError: invalid call of 'writeOutFromStack'";

        // pop finished cs
        ControlSplit cs = splits.pop();

        // In the case of the switch control split: eventually do a sons concatenation and fill up pinned path for every son
        cs.sonsConcat();

        // Write out important attributes - add finished control split to the list of finished control split
        fsplits.add(cs);

        // Parse tail
        List<Block> tail = new ArrayList<>();
        UnmodifiableMapCursor<AbstractBeginNode, List<Block>> __tails = cs.getTails();
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
        List<Block> newPath = new ArrayList<>(cs.getPathToBlock());
        newPath.add(cs.getBlock());
        UnmodifiableMapCursor<AbstractBeginNode, List<Block>> __sons = cs.getSons();
        while (__sons.advance()) {
            List<Block> sonPath = __sons.getValue();
            newPath.addAll(sonPath);
        }
        if (tail.size() > 0)
            newPath.addAll(tail);

        // Remove duplicates (we can have blocks duplication by branches: "continue" in switch, path tails in asymmetric switch)
        return newPath.stream().distinct().collect(Collectors.toList());
    }

    private static void flushToDb(List<ControlSplit> fsplits, StructuredGraph graph, StructuredGraph.ScheduleResult schedule) {
        bciFiltering(fsplits);

        List<EconomicMap<String, Integer>> asplits = appendAncestorsAttributesUtil(fsplits, schedule);

        // Print gt data
        PrintWriter writerMethods = null;
        try {
            writerMethods = new PrintWriter(new FileOutputStream(new File(PATH, "methodSignature_" + graph.method().getName() + "_" + graph.graphId() + ".gt")));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        assert writerMethods != null : "ParseImportantFeaturesPhaseError: Cannot instantiate a results writer.";
        ResolvedJavaMethod m = graph.method();
        Signature s = m.getSignature();
        writerMethods.printf("%d,\"%s\",%d,\"%s\",\"%s\"", graph.graphId(), m.getName(), s.getParameterCount(false), m.getDeclaringClass().toJavaName(true), s.getReturnType(null).toJavaName(true));
        JavaType[] params = s.toParameterTypes(null);
        for (JavaType param : params) {
            writerMethods.printf(",\"%s\"", param.toJavaName(true));
        }
        writerMethods.write("\n");
        writerMethods.close();

        // Print attributes
        PrintWriter writerAttr = null;
        try {
            writerAttr = new PrintWriter(new FileOutputStream(new File(PATH, "importantAttributes_" + graph.method().getName() + "_" + graph.graphId() + ".csv")));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        assert writerAttr != null : "ParseImportantFeaturesPhaseError: Cannot instantiate a result writer.";
        writerAttr.printf("Graph Id,Source Function,Node Description,Node BCI,head,CD Depth,N. CS Father Blocks,N. CS Father Fixed Nodes,N. CS Father Floating Nodes%n");
        for (int i = 0; i < fsplits.size(); i++) {
            flushToDbUtil(i, fsplits, asplits, writerAttr, graph, schedule);
        }
        writerAttr.close();
    }

    /**
     * If two {@link IfNode}s have the same source position we need to decide which node to
     * instrument. We always choose the one with probability 0.5. Rationale is that the node that
     * was injected probably should not be instrumented.
     */
    private static void bciFiltering(List<ControlSplit> fsplits) {
        EconomicMap<Integer, List<Integer>> bciPool = EconomicMap.create(Equivalence.DEFAULT);
        for (int i = 0; i < fsplits.size(); i++) {
            ControlSplitNode csnode = (ControlSplitNode) fsplits.get(i).getBlock().getEndNode();
            Integer pos = csnode.getNodeSourcePosition().getBCI();
            if (!(bciPool.containsKey(pos))) {
                List<Integer> ids = new ArrayList<>();
                ids.add(i);
                bciPool.put(pos, ids);
            } else {
                bciPool.get(pos).add(i);
            }
        }
        List<Integer> trash = new ArrayList<>();
        for (Integer pos : bciPool.getKeys()) {
            List<Integer> ids = bciPool.get(pos);
            while (ids.size() > 1) {
                Integer index0 = ids.get(0);
                Integer index1 = ids.get(1);
                ControlSplitNode n0 = (ControlSplitNode) fsplits.get(index0).getBlock().getEndNode();
                ControlSplitNode n1 = (ControlSplitNode) fsplits.get(index1).getBlock().getEndNode();
                ControlSplitNode cs = __chooseRelevantConditionalNode(n0, n1);
                if (cs.equals(n0)) {
                    ids.remove(1);
                    trash.add(index1);
                } else {
                    ids.remove(0);
                    trash.add(index0);
                }
            }
        }
        trash.sort(Collections.reverseOrder());  // sort list in reverse order cause of the index deletion
        for (Integer id : trash) {
            fsplits.remove((int) id);
        }
    }

    private static ControlSplitNode __chooseRelevantConditionalNode(ControlSplitNode n1, ControlSplitNode n2) {
        // assert n1.getNodeSourcePosition().equals(n2.getNodeSourcePosition()) : "This method distinguishes between nodes with the same source position.";
        // assert n1.getNodeSourcePosition().getBCI() < 0 || !(__hasDefaultProbability(n1) && __hasDefaultProbability(n2)) : "Two nodes with the same source position that is positive should not have default probability.";
        return __hasDefaultProbability(n1) ? n1 : n2;
    }

    public static boolean __hasDefaultProbability(ControlSplitNode n1) {
        double defaultProbability = 1.0 / n1.successors().count();
        for (Node s : n1.successors().snapshot()) {
            if (Double.compare(n1.probability((AbstractBeginNode) s), defaultProbability) != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<EconomicMap<String, Integer>> appendAncestorsAttributesUtil(List<ControlSplit> fsplits, StructuredGraph.ScheduleResult schedule) {
        // Return 'asplits': additional CS fashion attributes to already finished control splits 'fsplits'
        int n = fsplits.size();
        List<EconomicMap<String, Integer>> asplits = new ArrayList<>(n);
        List<EconomicSet<Block>> csdata = new ArrayList<>(n);
        for (ControlSplit cs : fsplits) {
            Block head = cs.getBlock();
            UnmodifiableMapCursor<AbstractBeginNode, List<Block>> sons = cs.getSons();
            EconomicSet<Block> pth = EconomicSet.create(Equivalence.DEFAULT);  // path which consist only of Control Split blocks
            pth.add(head);
            while (sons.advance()) {
                pth.addAll(sons.getValue());
            }
            csdata.add(pth);
            asplits.add(EconomicMap.create(Equivalence.IDENTITY));
        }

        // CS Depth
        for (int i = 0; i < n; i++) {
            EconomicSet<Block> cs = csdata.get(i);  // current Control Split
            int csdepth = 0;

            for (int j = i + 1; j < n; j++) {
                if (__compareSets(cs, csdata.get(j))) {
                    csdepth++;
                }
            }
            asplits.get(i).put("CS Depth", csdepth);
        }

        // N. CS Father Blocks
        // N. CS Father Nodes
        for (int i = 0; i < n; i++) {
            EconomicSet<Block> cs = csdata.get(i);  // current Control Split
            int fblocks = 0;
            int ffixnodes = 0;
            int ffloatnodes = 0;

            for (int j = i + 1; j < n; j++) {
                if (__compareSets(cs, csdata.get(j))) {  // father: the first who contains me
                    for (Block block : csdata.get(j)) {
                        fblocks++;
                        for (Node node : schedule.nodesFor(block)) {
                            if (node instanceof FixedNode) {
                                ffixnodes++;
                            }
                            if (node instanceof FloatingNode) {
                                ffloatnodes++;
                            }
                        }
                    }
                    break;
                }
            }
            asplits.get(i).put("N. CS Father Blocks", fblocks);
            asplits.get(i).put("N. CS Father Fixed Nodes", ffixnodes);
            asplits.get(i).put("N. CS Father Floating Nodes", ffloatnodes);
        }
        return asplits;
    }

    private static boolean __compareSets(EconomicSet<Block> X, EconomicSet<Block> Y) {
        // whether X is a subset of Y?
        for (Block b : X) {
            if (!Y.contains(b)) {
                return false;
            }
        }
        return true;
    }

    private static void flushToDbUtil(int i, List<ControlSplit> fsplits, List<EconomicMap<String, Integer>> asplits, PrintWriter writerAttr, StructuredGraph graph, StructuredGraph.ScheduleResult schedule) {
        ControlSplit cs = fsplits.get(i);
        EconomicMap<String, Integer> csaattributes = asplits.get(i);
        Block head = cs.getBlock();
        UnmodifiableMapCursor<AbstractBeginNode, List<Block>> sons = cs.getSons();
        EconomicMap<AbstractBeginNode, List<Block>> pinnedPaths = cs.getPinnedPaths();
        int csdepth = csaattributes.get("CS Depth");
        int csfblocks = csaattributes.get("N. CS Father Blocks");
        int csfnodesfix = csaattributes.get("N. CS Father Fixed Nodes");
        int csfnodesfloat = csaattributes.get("N. CS Father Floating Nodes");

        long graphId = graph.graphId();
        String name = graph.method().getName();
        String csDescription = head.getEndNode().toString();
        int csBCI = head.getEndNode().getNodeSourcePosition() == null ? -9999 : head.getEndNode().getNodeSourcePosition().getBCI();

        writerAttr.printf("%d,\"%s\",%s,%d,%s,%d,%d,%d,%d", graphId, name, csDescription, csBCI, head, csdepth, csfblocks, csfnodesfix, csfnodesfloat);
        while (sons.advance()) {
            AbstractBeginNode sonHead = sons.getKey();
            int sonBCI = sonHead.getNodeSourcePosition() == null ? -9999 : sonHead.getNodeSourcePosition().getBCI();
            Integer sonKey = cs.getSonKey(sonHead);
            List<Block> sonPath = sons.getValue();
            List<Block> pinnedPath = pinnedPaths.get(sonHead);
            EconomicMap<String, Integer> sonData = __getData(sonPath, fsplits, asplits, schedule);

            if (sonHead instanceof LoopExitNode) {
                writerAttr.printf(",\"[x(%s):%d:%d][null]\"", sonHead.toString(), sonBCI, sonKey);  // x is an abbreviation for LoopExitNode
                for (String attribute : sonData.getKeys()) {
                    switch (attribute) {
                        case "Loop Depth":
                        case "Max Loop Depth":
                            writerAttr.printf("; %s: [%d][0]", attribute, sonPath.get(0).getLoopDepth());
                            break;
                        case "N. Loop Exits":
                            writerAttr.printf("; %s: [1][0]", attribute);
                            break;
                        default:
                            writerAttr.printf("; %s: [0][0]", attribute);
                            break;
                    }
                }
            } else {
                writerAttr.printf(",\"%s:%d:%d]%s\"", sonPath.toString().substring(0, sonPath.toString().length() - 1), sonBCI, sonKey, pinnedPath == null ? "[null]" : pinnedPath);
                EconomicMap<String, Integer> pinnedData = __getData(pinnedPath, fsplits, asplits, schedule);
                for (String attribute : sonData.getKeys())  // always preserves insertion order when iterating over keys
                    writerAttr.printf("; %s: [%d][%d]", attribute, sonData.get(attribute), pinnedData != null ? pinnedData.get(attribute) : 0);
            }
        }
        writerAttr.printf("%n");
    }

    private static EconomicMap<String, Integer> __getData(List<Block> path, List<ControlSplit> fsplits, List<EconomicMap<String, Integer>> asplits, StructuredGraph.ScheduleResult schedule) {
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
        int maxcsdepth = 0;         // Max CS Depth
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
                ecycles += node.estimatedNodeCycles().value;

                // Estimated Assembly Size
                eassembly += node.estimatedNodeSize().value;

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
                if (node instanceof InvokeNode) {
                    ninvoke++;
                }

                // N. Allocations
                if (node instanceof AbstractNewObjectNode) {  // The AbstractNewObjectNode is the base class for the new instance and new array nodes.
                    nalloc++;
                }

                // N. Exceptions
                if (node instanceof BytecodeExceptionNode || node instanceof ThrowBytecodeExceptionNode || __getInvoke(node, java.lang.Throwable.class, "fillInStackTrace")) {
                    nexceptions++;
                }

                // N. Assertions
                if (node instanceof AssertionNode || __getInvoke(node, java.lang.AssertionError.class, "<init>")) {
                    nassertions++;
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
                if (node instanceof ArrayCompareToNode || node instanceof ArrayEqualsNode || node instanceof ArrayRegionEqualsNode || __getInvoke(node, java.util.Arrays.class, "compare")) {
                    narrcompare++;
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

            // Max CS Depth
            if (block.getEndNode() instanceof ControlSplitNode) {
                int tmpdepth = __getDepth(block, fsplits, asplits);
                if (tmpdepth > maxcsdepth) {
                    maxcsdepth = tmpdepth;
                }
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
        data.put("Max CS Depth", maxcsdepth);
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

    private static boolean __getInvoke(Node node, Class<?> targetClass, String targetName) {
        if (node instanceof InvokeNode) {
            ResolvedJavaMethod tmethod = ((InvokeNode) node).callTarget().targetMethod();
            if (tmethod instanceof WrappedJavaMethod) {
                ResolvedJavaMethod w = ((WrappedJavaMethod) tmethod).getWrapped();
                Class<?> methodClass;
                if (w instanceof AnalysisMethod) {
                    HostedMethod hmethod = (HostedMethod) tmethod;
                    HostedType htype = hmethod.getDeclaringClass();
                    methodClass = htype.getJavaClass();
                } else {
                    AnalysisMethod amethod = (AnalysisMethod) tmethod;
                    AnalysisType atype = amethod.getDeclaringClass();
                    methodClass = atype.getJavaClass();
                }
                return methodClass == targetClass && tmethod.getName().equals(targetName);
            }
        }
        return false;
    }

    private static int __getDepth(Block head, List<ControlSplit> fsplits, List<EconomicMap<String, Integer>> asplits) {
        // Get the depth of the control split led by the head 'head'
        for (int i = 0; i < fsplits.size(); i++) {
            ControlSplit tmpcs = fsplits.get(i);
            if (tmpcs.getBlock().equals(head)) {
                return asplits.get(i).get("CS Depth");
            }
        }
        return -1;  // JNIFunctions can break this rule (can't find a cs block)
    }
}