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
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.core.common.cfg.Loop;
import org.graalvm.compiler.debug.DebugContext;
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

class ControlSplit {  // Representation of a control split
    private Block block;             // Block which is ended with control split node
    private List<Block> pathToBlock; // The path leading to this block
    private Integer nsons;           // Number of sons
    private List<List<Block>> sons;  // Completed sons
    private EconomicSet<AbstractBeginNode> sonsHeads;  // Head nodes of sons I am waiting for
    private AbstractBeginNode tailNode;  // If I go through my personal merge and I am not complete at that time; AbstractMergeNode -> AbstractBeginNode
    private List<Block> tailBlocks;  // Tail blocks appended to this control split, for propagation to father blocks

    public ControlSplit(Block block, List<Block> path) {
        assert block.getEndNode() instanceof ControlSplitNode : "Control Split can be instantiated only with Control Split Node (as end).";  // Can instantiate only with control split nodes
        this.block = block;
        this.pathToBlock = new ArrayList<>(path);
        ControlSplitNode endnode = (ControlSplitNode) block.getEndNode();
        this.nsons = endnode.getSuccessorCount();
        this.sons = new ArrayList<>(nsons);
        this.sonsHeads =  EconomicSet.create(Equivalence.IDENTITY);
        for(Block son: block.getSuccessors())
            this.sonsHeads.add(son.getBeginNode());
        this.tailNode = null;
        this.tailBlocks = null;
    }

    public void addASon(List<Block> sonsPath){
        if(this.sonsHeads.contains(sonsPath.get(0).getBeginNode())) {
            this.sons.add(new ArrayList<>(sonsPath));
            this.nsons -= 1;
            this.sonsHeads.remove(sonsPath.get(0).getBeginNode());
        }else
            System.out.println("ParseImportantFeaturesError: adding invalid son.");
    }

    public Boolean finished(){
        return this.nsons == 0;
    }

    public Block getBlock(){ return this.block; }

    public List<List<Block>> getSons() { return sons; }

    public List<Block> getPathToBlock() { return pathToBlock; }

    public EconomicSet<AbstractBeginNode> getSonsHeads() { return sonsHeads; }

    public AbstractBeginNode getTailNode() { return tailNode; }
    public void setTailNode(AbstractBeginNode tailNode) { this.tailNode = tailNode; } // TODO: can I wait more than one
    public List<Block> getTailBlocks() { return tailBlocks; }
    public void setTailBlocks(List<Block> tailBlocks) {
        if(tailBlocks.get(0).getBeginNode()!=this.tailNode)
            System.out.println("ParseImportantFeaturesError: set tail blocks on wrong tail.");

        this.tailBlocks = new ArrayList<>(tailBlocks);
    }
}

class TraversalState {  // Intermediate state while traversing graph
    private List<Block> path;        // List of blocks visited so far

    public TraversalState() {
        this.path = new ArrayList<>();
    }
    public TraversalState(Block block) {
        this.path = new ArrayList<>();
        this.path.add(block);
    }
    public TraversalState(List<Block> path){
        if(path!=null)
            this.path = new ArrayList<>(path);
        else
            this.path = new ArrayList<>();
    }
    public TraversalState(TraversalState state){
        this.path = new ArrayList<>(state.getPath());
    }

    public void addBlockToPath(Block block) { this.path.add(block); }
    public void addBlocksToPath(List<Block> blocks){ this.path.addAll(blocks); }
    public void clearPath(){ this.path.clear(); }
    public List<Block> getPath() { return this.path; }
}

public class ParseImportantFeaturesPhase extends BasePhase<CoreProviders> {

    private Stage stage;

    private static PrintWriter writer;

    static {
        try {
            writer = new PrintWriter(new FileOutputStream(new File("./importantFeatures.csv")), true, StandardCharsets.UTF_8);
            writer.printf("Graph Id, Node BCI, Node Id, Node Description, Number of blocks%n");
        } catch (FileNotFoundException e) {
            System.out.println("Error with file opening. "); // TODO: fix this
            e.printStackTrace();
        }
    }

    public ParseImportantFeaturesPhase(Stage stage) {
        this.stage = stage;
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
                    splits.push(new ControlSplit(block, currentState.getPath()));  // add control split currently being processed
                    currentState.clearPath();                                      // clear path, fresh restart (for the first successor path to block already set)
                } else {
                    currentState.addBlockToPath(block);

                    if (block.getSuccessors().length == 0) {  // I don't have successors: for blocks like this iterator simply go on
                        ControlSplit fatherCS = findControlSplitFather(splits, currentState.getPath());
                        if (fatherCS != null)
                            fatherCS.addASon(currentState.getPath());
                        else {  // If no one waits for me as a son, look at a theirs tails; specific need for real end of a graph (for backpropagation blocks upwards)
                            fatherCS = findTailFather(splits, currentState.getPath());
                            if (fatherCS != null)
                                fatherCS.setTailBlocks(currentState.getPath());
                        }
                        // else - no one to catch
                        // currentState path will be reset on ReentrantBlockIterator.java, @ line 170.
                    } else if (block.getSuccessors().length == 1) {  // I have only one successor
                        // End before loops aren't end of any Control Split branches: simply skip that end (in the term of adding a son)
                        // If next is loop begin node: if curr is loop end: add path as a someone son, else: skip adding path as a anyone son
                        if (block.getEndNode() instanceof AbstractEndNode && (block.isLoopEnd() || !(block.getFirstSuccessor().getBeginNode() instanceof LoopBeginNode))) {
                            ControlSplit fatherCS = findControlSplitFather(splits, currentState.getPath());
                            if (fatherCS != null)
                                fatherCS.addASon(currentState.getPath());
                            else {  // If no one waits for me as a son, look at a theirs tails
                                fatherCS = findTailFather(splits, currentState.getPath());
                                if (fatherCS != null)
                                    fatherCS.setTailBlocks(currentState.getPath());
                            }
                            // else - no one to catch
                            // currentState path will be reset on ReentrantBlockIterator.java, @ line 170.
                        }
                    } else {
                        assert false : "Node with more than one successors doesn't catch as a Control Split Node.";
                    }
                }
                return currentState;  // This will be used only on Fixed With Next Node process
            }

            @Override
            protected TraversalState merge(Block merge, List<TraversalState> __states) { // Vrati poslednje sto je spojio a nije nalepio, inace <>
                // ___states are used internally by ReentrantBlockIterator in order to ensure that the graph is properly visited
                List<Block> newPath = null;

                if (splits.size() > 0 && !splits.peek().finished()) {
                    // Going through uncomplete (personal) merge (merge which all ends were visited, but appropriate control split isn't finished)
                    if (personalMerge(splits.peek(), (AbstractMergeNode) merge.getBeginNode())) {
                        if(splits.peek().getTailNode() != null)
                            System.out.println("ParseImportantFeaturesError: Going through the same merge node twice.");
                        splits.peek().setTailNode(merge.getBeginNode());
                        return new TraversalState(); // will start from that merge block/node which was just added as a tail
                    }
                }

                while (splits.size() > 0) {
                    if (splits.peek().finished()) {
                        // I (on top of the stack)
                        ControlSplit stacksTop = splits.peek();
                        // My new path
                        newPath = writeOutFromStack(splits, graph);

                        // Try to eventually add a son
                        if (splits.size() > 0) {
                            ControlSplit fatherCS = null, tailCS = null;
                            fatherCS = findControlSplitFather(splits, newPath);
                            tailCS = findTailFather(splits, newPath);
                            if (fatherCS != null) {
                                // IF IT IS MY PERSONAL MERGE CONTINUE ELSE PUSH AS A SON
                                if (personalMerge(stacksTop, (AbstractMergeNode) merge.getBeginNode()))
                                    return new TraversalState(newPath);
                                else
                                    fatherCS.addASon(newPath);
                            } else if (tailCS != null) {
                                // JUST ADD TO APPROPRIATE TAIL
                                if (personalMerge(stacksTop, (AbstractMergeNode) merge.getBeginNode()))  // newly added
                                    return new TraversalState(newPath);  // newly added
                                else  // newly added
                                    tailCS.setTailBlocks(newPath);
                            } else
                                continue; // Son not added; all control splits are full
                        }
                    } else if (personalMerge(splits.peek(), (AbstractMergeNode) merge.getBeginNode())) { // !splits.peek().finished()
                        if(splits.peek().getTailNode() != null && splits.peek().getTailNode()!=merge.getBeginNode())
                            System.out.println("ParseImportantFeaturesError: Going through the same merge node twice.");
                        if(splits.peek().getTailNode()!=merge.getBeginNode())
                            splits.peek().setTailNode(merge.getBeginNode());
                        return new TraversalState(); // will start from that merge block/node which was just added as a tail
                    } else
                        return new TraversalState(newPath);  // Control spit on splits top aren't finished, continue with merge node and so on.
                }
                return new TraversalState();  // No more Control Splits on stack, fresh restart
            }

            @Override
            protected TraversalState cloneState(TraversalState oldState) {
                return new TraversalState();  // Till now only for control split purpose, when push sons, father is on the stack TODO: Vidi kako ces kod kloniranja za petlje
            }

            @Override
            protected List<TraversalState> processLoop(Loop<Block> loop, TraversalState initialState) {
                EconomicMap<FixedNode, TraversalState> blockEndStates = ReentrantBlockIterator.apply(this, loop.getHeader(), initialState, block -> !(block.getLoop() == loop || block.isLoopHeader()));

                Block[] predecessors = loop.getHeader().getPredecessors();
                ReentrantBlockIterator.LoopInfo<TraversalState> info = new ReentrantBlockIterator.LoopInfo<>(predecessors.length - 1, loop.getLoopExits().size());
                for (int i = 1; i < predecessors.length; i++) {
                    TraversalState endState = blockEndStates.get(predecessors[i].getEndNode());
                    // make sure all end states are unique objects
                    info.endStates.add(this.cloneState(endState));
                }
                for (Block loopExit : loop.getLoopExits()) { // getLoopExits()
                    assert loopExit.getPredecessorCount() == 1;
                    assert blockEndStates.containsKey(loopExit.getBeginNode()) : loopExit.getBeginNode() + " " + blockEndStates;
                    TraversalState exitState = blockEndStates.get(loopExit.getBeginNode());
                    // make sure all exit states are unique objects
                    info.exitStates.add(exitState);  // this.cloneState(exitState) for unfinished sons error - ex.: when son is BX1+BX2, where BX2 is LoopExit+Unwind (need to propagate B1 as a state for block BX2)
                }
                return info.exitStates;
            }
        };

        ReentrantBlockIterator.apply(CSClosure, r.getCFG().getStartBlock());

        // Flush [finished] Control Splits from the stack as the end of the iteration process
        while (splits.size() > 0) {
            // My new path
            List<Block> newPath = null;
            newPath = writeOutFromStack(splits, graph);

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
                    continue; // Son not added; no one waiting for this path as a son; continue flushing splits
            }
        }
    }

    private static boolean personalMerge(ControlSplit cs, AbstractMergeNode merge){
        // Are merge block (block starting with AbstractMergeNode merge) fully owned by Control split cs
        List<List<Block>> sons = cs.getSons();
        EconomicSet<AbstractEndNode> myEnds = EconomicSet.create(Equivalence.IDENTITY);
        for(List<Block> son: sons){
            for(Block sblock : son){
                if(sblock.getEndNode() instanceof AbstractEndNode){  // For merge of 2nd order (B30 @CompleteExample) - imagine more stacked [node 47|If also covered with this rule]
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
        if(path==null || path.size()==0) return null;
        int i;
        for (i = splits.size() - 1; i >= 0; i--) {
            if (splits.get(i).getTailNode()==path.get(0).getBeginNode())
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
            if (splits.get(i).getSonsHeads().contains(path.get(0).getBeginNode()))
                break;
        }
        if (i == -1)
            return null;
        else
            return splits.get(i);
    }

    private static List<Block> writeOutFromStack(Stack<ControlSplit> splits, StructuredGraph graph){
        // Pop element from the top of a stack and write it out to database; return integrated path
        if(splits.size()==0 || !splits.peek().finished()){
            System.out.println("ParseImportantFeaturesError: invalid call of 'writeOutFromStack'");
            return new ArrayList<Block>();
        }
        List<Block> newPath = null;

        // pop finished cs
        ControlSplit cs = splits.pop();
        Block head = cs.getBlock();
        List<List<Block>> sons = cs.getSons();
        int nsons = sons.size();
        List<Block> tail = cs.getTailBlocks();

        // writeout
        synchronized (writer) {
            long graphId = graph.graphId();
            int nodeId = ((Node) head.getEndNode()).getNodeSourcePosition() == null ? -9999 : ((Node) head.getEndNode()).getNodeSourcePosition().getBCI();

            writer.printf("%d, %d (%s), %d, \"%s\"", graphId, nodeId, head, ((Node) head.getEndNode()).getId(), ((Node) head.getEndNode()).toString());
            for (int i = 0; i < nsons; i++)
                writer.printf(", \"%s\"", sons.get(i));
            writer.printf("%n");
        }

        // create a full cs path
        newPath = new ArrayList<>(cs.getPathToBlock());
        newPath.add(head);
        for (int i = 0; i < nsons; i++)
            newPath.addAll(sons.get(i));
        if(tail!=null)
            newPath.addAll(tail);

        return newPath;
    }
}