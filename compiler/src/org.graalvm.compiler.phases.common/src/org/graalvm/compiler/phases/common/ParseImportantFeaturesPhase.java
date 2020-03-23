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

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;
import org.graalvm.collections.UnmodifiableMapCursor;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.core.common.cfg.Loop;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.MethodFilter;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeMap;
import org.graalvm.compiler.nodes.*;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.graph.ReentrantBlockIterator;
import org.graalvm.compiler.phases.schedule.SchedulePhase;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import jdk.vm.ci.meta.ResolvedJavaMethod;

/* Representation of a control split */
class ControlSplit {
    private Block block;  // Block which is ended with control split node
    private List<Block> pathToBlock;  // The path leading to this block
    private EconomicSet<AbstractBeginNode> sonsHeads;  // Head nodes of sons I am waiting for
    private EconomicMap<AbstractBeginNode, List<Block>> sonsBlocks;  // Completed sons
    private EconomicSet<AbstractBeginNode> tailHeads;  // If I go through my personal merge and I am not complete at that time
    private EconomicMap<AbstractBeginNode, List<Block>> tailBlocks;  // Tail blocks appended to this control split, for propagation to father blocks

    public ControlSplit(Block block, List<Block> path) {
        assert block.getEndNode() instanceof ControlSplitNode : "ParseImportantFeaturesError: Control Split can be instantiated only with Control Split Node (as end).";
        this.block = block;
        this.pathToBlock = new ArrayList<>(path);
        this.sonsBlocks = EconomicMap.create(Equivalence.IDENTITY);
        this.sonsHeads =  EconomicSet.create(Equivalence.IDENTITY);
        for(Block son: block.getSuccessors())
            this.sonsHeads.add(son.getBeginNode());
        this.tailHeads = EconomicSet.create(Equivalence.IDENTITY);
        this.tailBlocks = EconomicMap.create(Equivalence.IDENTITY);
    }

    public Block getBlock(){ return this.block; }

    public List<Block> getPathToBlock() { return pathToBlock; }

    // Sons operations
    public Boolean finished(){ return this.sonsBlocks.size()==this.block.getSuccessorCount(); }
    public UnmodifiableMapCursor<AbstractBeginNode, List<Block>> getSons() { return this.sonsBlocks.getEntries(); }
    public Iterable<List<Block>> getSonsPaths(){ return this.sonsBlocks.getValues(); }
    public boolean areInSons(AbstractBeginNode node){ return this.sonsHeads.contains(node); }
    public void addASon(List<Block> sonsPath){
        AbstractBeginNode sonsHead = sonsPath.get(0).getBeginNode();
        assert this.sonsHeads.contains(sonsHead) : "ParseImportantFeaturesError: Adding invalid son.";
        assert !this.sonsBlocks.containsKey(sonsHead) : "ParseImportantFeaturesError: Adding same son twice.";
        this.sonsBlocks.put(sonsHead, new ArrayList<>(sonsPath));
        this.sonsHeads.remove(sonsHead);
    }

    // Tails operations
    public boolean areInTails(AbstractBeginNode node){ return this.tailHeads.contains(node); }
    public void setTailNode(AbstractBeginNode tailNode) { this.tailHeads.add(tailNode); }
    public void setTailBlocks(List<Block> tailBlocks) {
        AbstractBeginNode node = tailBlocks.get(0).getBeginNode();
        assert this.tailHeads.contains(node) : "ParseImportantFeaturesError: set tail blocks on wrong tail.";
        this.tailBlocks.put(node, new ArrayList<Block>(tailBlocks));
    }
    public UnmodifiableMapCursor<AbstractBeginNode, List<Block>> getTails(){ return this.tailBlocks.getEntries(); }
}

/* Graph traversal intermediate state representation */
class TraversalState {
    private List<Block> path;  // List of blocks visited so far

    public TraversalState() {
        this.path = new ArrayList<>();
    }
    public TraversalState(List<Block> path){
        if(path==null)
            this.path = new ArrayList<>();
        else
            this.path = new ArrayList<>(path);
    }

    public List<Block> getPath() { return this.path; }

    public void addBlockToPath(Block block) { this.path.add(block); }
    public void clearPath(){ this.path.clear(); }
}

public class ParseImportantFeaturesPhase extends BasePhase<CoreProviders> {

    private Stage stage;
    private String methodRegex;

    private static PrintWriter writer;

    static { // Static writer used for dumping important features to database (currently .csv file)
        try {
            writer = new PrintWriter(new FileOutputStream(new File("./importantFeatures.csv")), true, StandardCharsets.UTF_8);
            writer.printf("Graph Id,Source Function,Node Description,Node Id,Node BCI,head%n");
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

    public static class Options { // TODO: Use false as default value and do properly setting it up
        // @formatter:off
        @Option(help = "Parse important features from graph nodes.", type = OptionType.Expert)
        public static final OptionKey<Boolean> ParseImportantFeatures = new OptionKey<>(true);
        // @formatter:on
    }

    @Override
    protected void run(StructuredGraph graph, CoreProviders context) {
        // Method filter
        ResolvedJavaMethod orign = graph.method();
//        String name = orign.getName();
//        if(methodRegex!=null && !name.equals(this.methodRegex))
//            return;
        if(methodRegex!=null) {  // if methodRegex is null (MethodFilter not specified), parse all
            MethodFilter mf = MethodFilter.parse(methodRegex);
            if (!mf.matches(orign))
                return;
        }

        // Block and nodes integration
        ControlFlowGraph cfg = ControlFlowGraph.compute(graph, true, true, true, true);
        BlockMap<List<Node>> blockToNode = null;
        NodeMap<Block> nodeToBlock = null;
        try (DebugContext.Scope scheduleScope = graph.getDebug().scope(SchedulePhase.class)) {
            SchedulePhase.run(graph, SchedulePhase.SchedulingStrategy.EARLIEST_WITH_GUARD_ORDER, cfg);
        } catch (Throwable t) {
            throw graph.getDebug().handle(t);
        }
        StructuredGraph.ScheduleResult r = graph.getLastSchedule();
        blockToNode = r.getBlockToNodesMap();
        nodeToBlock = r.getNodeToBlockMap();

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
                            // currentState path will be reset on ReentrantBlockIterator.java, @ line 170.
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
                        // Finished ControlSplit (on top of the stack)
                        ControlSplit stacksTop = splits.peek();
                        // My new path
                        List<Block> newPath = writeOutFromStack(splits, graph);

                        // Try to eventually add a son
                        if (splits.size() > 0) {
                            ControlSplit fatherCS = null, tailCS = null;
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
                    } else
                        return new TraversalState();  // A Control Split on the top of the splits firstly was finished, then popped up and added as a son or tail, then loop were continued, then control split on top of the stack aren't finished: further go on merge node deeper with empty path (and no current wait), later on, when finish that Control Split, just do regularly
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
                    // make sure all exit states are unique objects
                    info.exitStates.add(exitState);  // Need to propagate full path to the loop exit - ex.: when son is BX1+BX2, where BX2 is LoopExit+Unwind (need to propagate B1 as a state for block BX2)
                }
                return info.exitStates;
            }
        };

        ReentrantBlockIterator.apply(CSClosure, r.getCFG().getStartBlock());

        // Flush [finished] Control Splits from the stack as the end of the iteration process
        while (splits.size() > 0) {
            // My new path
            List<Block> newPath = writeOutFromStack(splits, graph);

            // Try to eventually add a son
            if (splits.size() > 0) {
                ControlSplit fatherCS = null, tailCS = null;
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

    private static boolean personalMerge(ControlSplit cs, AbstractMergeNode merge){
        // Are merge block (block starting with AbstractMergeNode merge) fully owned by Control split cs
        Iterable<List<Block>> sons = cs.getSonsPaths();
        EconomicSet<AbstractEndNode> myEnds = EconomicSet.create(Equivalence.IDENTITY);
        for(List<Block> son: sons){
            for(Block sblock : son){
                if(sblock.getEndNode() instanceof AbstractEndNode){  // For merge of 2nd and higher order
                    myEnds.add((AbstractEndNode)sblock.getEndNode());
                }
            }
        }
        boolean personalmerge = true;
        for (AbstractEndNode forwardEnd : merge.forwardEnds()){
            if(!myEnds.contains(forwardEnd)){
                personalmerge = false;
                break;
            }
        }
        return personalmerge;
    }

    private static ControlSplit findTailFather(Stack<ControlSplit> splits, List<Block> path){
        if(path==null) return null;
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

    private static ControlSplit findControlSplitFather(Stack<ControlSplit> splits, List<Block> path){
        if(path==null) return null;
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

    private static List<Block> writeOutFromStack(Stack<ControlSplit> splits, StructuredGraph graph){
        // Pop element from the top of a stack and write it out to database; return integrated path
        assert splits.size()>0 && splits.peek().finished() :  "ParseImportantFeaturesError: invalid call of 'writeOutFromStack'";
        List<Block> newPath = null;

        // pop finished cs
        ControlSplit cs = splits.pop();
        Block head = cs.getBlock();
        UnmodifiableMapCursor<AbstractBeginNode, List<Block>> __sons = cs.getSons();
        List<Block> tail = new ArrayList<Block>();
        UnmodifiableMapCursor<AbstractBeginNode, List<Block>> __tail = cs.getTails();
        while(__tail.advance()){
            AbstractBeginNode csNode = __tail.getKey();
            List<Block> csBlocks = __tail.getValue();
            if(personalMerge(cs, (AbstractMergeNode)csNode))
                tail.addAll(csBlocks);
            else if(splits.size()>0){
                splits.peek().setTailNode(csNode);  // Propagate tail upwards
                splits.peek().setTailBlocks(csBlocks);
            }
        }

        // writeout
        synchronized (writer) {
            long graphId = graph.graphId();
            int nodeBCI = ((Node) head.getEndNode()).getNodeSourcePosition() == null ? -9999 : ((Node) head.getEndNode()).getNodeSourcePosition().getBCI();
            ResolvedJavaMethod orign = graph.method();
            String name = orign.getName();

            writer.printf("%d,\"%s\",%s,%d,%d,%s", graphId, name, ((Node) head.getEndNode()).toString(), ((Node) head.getEndNode()).getId(), nodeBCI, head);
            while(__sons.advance()) {
                AbstractBeginNode sonHead = __sons.getKey();
                List<Block> sonPath = __sons.getValue();
                if(sonHead instanceof LoopExitNode)
                    writer.printf(",\"x(%s)\"", sonHead.toString());  // x is an abbreviation for LoopExitNode
                else
                    writer.printf(",\"%s\"", sonPath);
            }
            writer.printf("%n");
        }

        // create a full cs path
        newPath = new ArrayList<>(cs.getPathToBlock());
        newPath.add(head);
        __sons = cs.getSons();
        while(__sons.advance()) {
            AbstractBeginNode sonHead = __sons.getKey();
            List<Block> sonPath = __sons.getValue();
            newPath.addAll(sonPath);
        }
        if(tail.size()>0)
            newPath.addAll(tail);

        return newPath;
    }
}