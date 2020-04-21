/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.test;

import com.oracle.svm.hosted.phases.ParseImportantFeaturesPhase;
import org.graalvm.compiler.core.test.GraalCompilerTest;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.phases.schedule.SchedulePhase;
import org.junit.Test;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ParseImportantFeaturesPhaseTest extends GraalCompilerTest {

    private void testCodeSnippet(String snippet, String groundTruth) {
        StructuredGraph graph = parseEager(snippet, StructuredGraph.AllowAssumptions.NO);
        ParseImportantFeaturesPhase p = new ParseImportantFeaturesPhase(snippet);
        p.apply(graph, null);

        ControlFlowGraph cfg = ControlFlowGraph.compute(graph, true, true, true, true);
        try (DebugContext.Scope scheduleScope = graph.getDebug().scope(SchedulePhase.class)) {
            SchedulePhase.run(graph, SchedulePhase.SchedulingStrategy.LATEST, cfg);  // Do scheduling cause of floating point nodes
        } catch (Throwable t) {
            throw graph.getDebug().handle(t);
        }
        StructuredGraph.ScheduleResult r = graph.getLastSchedule();

        // Parse ground truth
        HashMap<String, HashSet<String>> gt = new HashMap<String, HashSet<String>>();
        String[] lines = groundTruth.split("\\r?\\n");
        for (String line : lines) {
            String[] parts = line.split("-");
            String NodeDescription = parts[0].strip();
            String head = parts[1].strip();
            HashSet<String> sons = new HashSet<>();
            for (int index = 2; index < parts.length; index++) {
                String son = parts[index];
                String[] sonData = son.split("\\]\\[");
                assert sonData.length == 2 : "ParseImportantFeaturesPhaseTest Error: Ground Truth data invalid.";
                String branch = __sortPath(sonData[0].replaceAll("\\[", "").replaceAll("^\"|\"$", ""));
                String tail = __sortPath(sonData[1].replaceAll("\\]", "").replaceAll("^\"|\"$", ""));
                son = branch + "--" + tail;
                sons.add(son);
            }
            gt.put(NodeDescription + "--" + head, sons);
        }

        // Parse .csv
        int nerror = 0;
        try {
            File attributes = __getAttributes(snippet);
            //System.out.println("Working with attributes: " + attributes);
            assert attributes != null : "ParseImportantFeaturesPhaseTest: cannot find .csv attributes";
            BufferedReader csv = new BufferedReader(new FileReader(attributes));
            String line;
            while ((line = csv.readLine()) != null) {
                if (line.equals("Graph Id, Source Function, Node Description, head, CD Depth, N. CS Father Blocks, N. CS Father Fixed Nodes, N. CS Father Floating Nodes"))  // skip header line
                    continue;
                String[] data = line.split(",(?!\\s)");
                String SourceFunction = data[1].replaceAll("\"", "");
                assert SourceFunction.equals(snippet) : "ParseImportantFeaturesPhaseTest Error: Source function does not match.";
                String NodeDescription = data[2];
                String head = data[3];
                assert gt.containsKey(NodeDescription + "--" + head) : "ParseImportantFeaturesPhaseError: wrong Control Split in .csv file.";
                HashSet<String> sons = gt.get(NodeDescription + "--" + head);

                for (int index = 8; index < data.length; index++) {
                    String[] branchData = data[index].split(";")[0].split(Pattern.quote("]["));
                    assert branchData.length == 2 : "ParseImportantFeaturesPhaseTest Error: Invalid branch data.";
                    String branch = __sortPath(branchData[0].replaceAll("\\[", "").replaceAll("^\"|\"$", ""));
                    String tail = __sortPath(branchData[1].replaceAll("\\]", "").replaceAll("^\"|\"$", ""));
                    if (!sons.contains(branch + "--" + tail)) {
                        System.out.println("ParseImportantFeaturesPhaseTest error on function: " + snippet + " invalid son parsed: " + branch + "--" + tail);
                        nerror += 1;
                    } else
                        sons.remove(branch + "--" + tail);
                }
                if (sons.size() > 0) {
                    System.out.println("ParseImportantFeaturesPhaseTest error on function: " + snippet + " son[s] not found in parsed data: ");
                    sons.stream().forEach(son -> System.out.println(son));
                    nerror += 1;
                }
            }
            csv.close();
        } catch (FileNotFoundException e) {
            System.out.println(".csv file with parsed blocks are not found. ParseImportantFeaturesPhase - output file error.");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println(".csv file with parsed blocks are not found. ParseImportantFeaturesPhase - line read error.");
            e.printStackTrace();
        }

        assertTrue("Test " + snippet + " failed.", nerror == 0);
    }

    private File __getAttributes(String snippet) {
        // Return attributes file with respect to the snippet function name
        File directory = new File(".");
        File[] files = directory.listFiles();
        List<File> filesFeatures = new ArrayList<>();
        for (File file : files) {
            if (file.isDirectory() && file.getName().contains("importantAttributes")) {
                filesFeatures.add(file);
            }
        }

        if (filesFeatures.size() == 0) return null;
        File min = Collections.min(filesFeatures, (o1, o2) -> Long.valueOf(o2.lastModified()).compareTo(o1.lastModified()));
        if (min == null)
            return null;
        List<File> targetData = new ArrayList<>();
        for (File file : min.listFiles()) {
            if (file.isFile() && file.getName().contains("importantAttributes_" + snippet + "_")) {
                targetData.add(file);
            }
        }
        if (targetData.size() != 1)
            return null;
        return targetData.get(0);
    }

    private String __sortPath(String path) {  // sorte blocks if there are blocks in path
        if (!path.startsWith("x") && !path.equals("null"))
            return Arrays.stream(path.split(",")).map((String block) -> block.strip()).sorted().collect(Collectors.joining(", "));
        else
            return path;
    }

    private void __printCodeSnippet(String snippet) {
        StructuredGraph graph = parseEager(snippet, StructuredGraph.AllowAssumptions.NO);
        ParseImportantFeaturesPhase p = new ParseImportantFeaturesPhase(snippet);
        p.apply(graph, null);

        ControlFlowGraph cfg = ControlFlowGraph.compute(graph, true, true, true, true);
        try (DebugContext.Scope scheduleScope = graph.getDebug().scope(SchedulePhase.class)) {
            SchedulePhase.run(graph, SchedulePhase.SchedulingStrategy.LATEST, cfg);  // Do scheduling cause of floating point nodes
        } catch (Throwable t) {
            throw graph.getDebug().handle(t);
        }
        StructuredGraph.ScheduleResult r = graph.getLastSchedule();
        System.out.println("\nCFG dominator tree: \n" + cfg.dominatorTreeString());

        System.out.println("\nGraph representation: ");
        System.out.println("------------------------------------------------------------------------------------------------------------");
        System.out.printf("%3s %30s %s\n", "blk", "successors", "blk_nodes:");
        for (Block b : cfg.getBlocks()) {
            System.out.printf("%3s %30s ", b.toString(), Arrays.toString(b.getSuccessors()));
            //System.out.print(b.getNodes());
            System.out.println(r.nodesFor(b));
        }
        System.out.println("------------------------------------------------------------------------------------------------------------");
    }

    private void testAttributesCodeSnippet(String snippet, String[] attributesTruth, String groundTruth) {
        StructuredGraph graph = parseEager(snippet, StructuredGraph.AllowAssumptions.NO);
        ParseImportantFeaturesPhase p = new ParseImportantFeaturesPhase(snippet);
        p.apply(graph, null);

        ControlFlowGraph cfg = ControlFlowGraph.compute(graph, true, true, true, true);
        try {
            SchedulePhase.run(graph, SchedulePhase.SchedulingStrategy.LATEST, cfg);  // Do [LASTEST] scheduling cause of floating point nodes
        } catch (Throwable t) {
            throw graph.getDebug().handle(t);
        }
        StructuredGraph.ScheduleResult r = graph.getLastSchedule();

        // Parse ground truth
        HashMap<String, HashMap<String, String[]>> gt = new HashMap<>();
        String[] lines = groundTruth.split("\\r?\\n");
        for (String line : lines) {
            String[] parts = line.split("-");
            String NodeDescription = parts[0].strip();
            String head = parts[1].strip();
            HashMap<String, String[]> sons = new HashMap<>();
            for (int index = 2; index < parts.length; index++) {
                // src
                String[] src = parts[index].split(";");
                assert src.length == 2 : "Information not valid: " + parts[index];

                // branch + tail info
                String son = src[0];
                String[] sonData = son.split("\\]\\[");
                assert sonData.length == 2 : "ParseImportantFeaturesPhaseTest Error: Ground Truth data invalid.";
                String branch = __sortPath(sonData[0].replaceAll("\\[", "").replaceAll("^\"|\"$", ""));
                String tail = __sortPath(sonData[1].replaceAll("\\]", "").replaceAll("^\"|\"$", ""));

                // attributes info
                // ex. "17|If-B4-[B5][null];[0][0]:[0][0]"
                String attr = src[1];
                String[] attrData = attr.split(":");
                assert attrData.length == attributesTruth.length : "ParseImportantFeaturesPhaseTest Error: ground truth not valid, attributes: " + attr + " inside: " + branch + "--" + tail;
                sons.put(branch + "--" + tail, attrData);
            }
            gt.put(NodeDescription + "--" + head, sons);
        }

        // Parse .csv
        int nerror = 0;
        try {
            File attributes = __getAttributes(snippet);
            //System.out.println("Working with attributes: " + attributes);
            assert attributes != null : "ParseImportantFeaturesPhaseTest: cannot find .csv attributes";
            BufferedReader csv = new BufferedReader(new FileReader(attributes));
            String line;
            while ((line = csv.readLine()) != null) {
                if (line.equals("Graph Id, Source Function, Node Description, head, CD Depth, N. CS Father Blocks, N. CS Father Fixed Nodes, N. CS Father Floating Nodes"))  // skip header line
                    continue;
                String[] data = line.split(",(?!\\s)");
                String SourceFunction = data[1].replaceAll("\"", "");
                assert SourceFunction.equals(snippet) : "ParseImportantFeaturesPhaseTest Error: Source function does not match.";
                String NodeDescription = data[2];
                String head = data[3];
                assert gt.containsKey(NodeDescription + "--" + head) : "ParseImportantFeaturesPhaseError: wrong Control Split in .csv file.";
                HashMap<String, String[]> sons = gt.get(NodeDescription + "--" + head);
                HashMap<String, String> csData = new HashMap<>();
                csData.put("CS Depth", data[4]);
                csData.put("N. CS Father Blocks", data[5]);
                csData.put("N. CS Father Fixed Nodes", data[6]);
                csData.put("N. CS Father Floating Nodes", data[7]);

                for (int index = 8; index < data.length; index++) {
                    // src
                    String[] src = data[index].split(";");

                    // son gt info
                    HashMap<String, String> sonData = new HashMap<String, String>(csData);
                    for (int i = 1; i < src.length; i++) {
                        // ex.: "N. Array Store: [0][0]"
                        String attr = src[i];
                        sonData.put(attr.split(":")[0].strip(), attr.split(":")[1].strip());
                    }

                    // head + tail info
                    String[] branchData = src[0].split(Pattern.quote("]["));
                    assert branchData.length == 2 : "ParseImportantFeaturesPhaseTest Error: Invalid branch data.";
                    String branch = __sortPath(branchData[0].replaceAll("\\[", "").replaceAll("^\"|\"$", ""));
                    String tail = __sortPath(branchData[1].replaceAll("\\]", "").replaceAll("^\"|\"$", ""));

                    if (!sons.containsKey(branch + "--" + tail)){
                        System.out.println("ParseImportantFeaturesPhaseTest error on function: " + snippet + " Error son not found: " + branch + "--" + tail);
                        System.out.println("Ground Truth: " + sons.keySet());
                        nerror += 1;
                    } else if(!__compare(attributesTruth, sons.get(branch + "--" + tail), sonData)){
                        System.out.println("ParseImportantFeaturesPhaseTest error on function: " + snippet + " Content aren't the same on son: " + branch + "--" + tail);
                        System.out.println("Ground Truth: " + sons.keySet());
                        nerror += 1;
                    }else{
                        sons.remove(branch + "--" + tail);
                    }
                }
                if (sons.size() > 0) {
                    System.out.println("ParseImportantFeaturesPhaseTest error on function: " + snippet + " son[s] not found in parsed data: ");
                    sons.keySet().stream().forEach(son -> System.out.println(son));
                    nerror += 1;
                }
            }
            csv.close();
        } catch (FileNotFoundException e) {
            System.out.println(".csv file with parsed blocks are not found. ParseImportantFeaturesPhase - output file error.");
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println(".csv file with parsed blocks are not found. ParseImportantFeaturesPhase - line read error.");
            e.printStackTrace();
        }

        assertTrue("Test " + snippet + " failed.", nerror == 0);
    }

    public boolean __compare(String[] attributesTruth, String[] gtData, HashMap<String, String> parsedData) {
        // [2][0] vs [2][0] or [1] vs 1
        HashSet<String> unique = new HashSet<>();
        unique.addAll(Arrays.asList(new String[]{"CS Depth", "N. CS Father Blocks", "N. CS Father Fixed Nodes", "N. CS Father Floating Nodes"}));

        for (int i = 0; i < attributesTruth.length; i++) {
            String attribute = attributesTruth[i];
            String gt = gtData[i];
            String parsed = parsedData.get(attribute);
            assert parsed != null : "ParseImportantFeaturesPhaseError: cannot find attribute: " + attribute;
            if (unique.contains(attribute)) {
                if (Integer.parseInt(parsed) != Integer.parseInt(gt.replace("[", "").replace("]", ""))) {
                    System.out.println("__compare Error: TRUE" + gt + " PARSED: " + parsed);
                    return false;
                }
            } else {
                int gtBranch = Integer.parseInt(gt.split("\\]\\[")[0].replace("[", "").replace("]", ""));
                int gtTail = Integer.parseInt(gt.split("\\]\\[")[1].replace("[", "").replace("]", ""));
                int parsedBranch = Integer.parseInt(parsed.split("\\]\\[")[0].replace("[", "").replace("]", ""));
                int parsedTail = Integer.parseInt(parsed.split("\\]\\[")[1].replace("[", "").replace("]", ""));
                if (gtBranch != parsedBranch || gtTail != parsedTail) {
                    System.out.println("__compare Error: TRUE" + gt + " PARSED: " + parsed);
                    return false;
                }
            }
        }
        return true;
    }

    /* TESTS */
    @Test
    public void test1() {
        String groundTruth = "8|If-B0-[B1][null]-[B2][null]\n";
        testCodeSnippet("example_ftest1", groundTruth);
    }

    @Test
    public void test4() {
        String groundTruth = "8|If-B0-[B1][null]-[B2][null]\n";
        testCodeSnippet("example_ftest4", groundTruth);
    }

    @Test
    public void test3() {
        String groundTruth = "9|If-B0-[B1][null]-[B2][null]\n";
        testCodeSnippet("example_ftest3", groundTruth);
    }

    @Test
    public void test14() {
        String groundTruth = "12|If-B1-[B2][null]-[B3][null]\n" +
                "35|If-B5-[B6][null]-[B7][null]\n" +
                "8|If-B0-[B5, B6, B7][null]-[B1, B3, B2, B4][null]\n";
        testCodeSnippet("example_ftest14", groundTruth);
    }

    @Test
    public void test10() {
        String groundTruth = "8|If-B0-[B1][null]-[B2][null]\n";
        testCodeSnippet("example_ftest10", groundTruth);
    }

    @Test
    public void test7() {
        String groundTruth = "14|If-B1-[B2][null]-[x(11|LoopExit)][null]\n";
        testCodeSnippet("example_ftest7", groundTruth);
    }

    @Test
    public void test8() {
        String groundTruth = "14|If-B1-[B2][null]-[x(11|LoopExit)][null]\n";
        testCodeSnippet("example_ftest8", groundTruth);
    }

    @Test
    public void test9() {
        String groundTruth = "21|If-B1-[B3][null]-[x(18|LoopExit)][null]\n";
        testCodeSnippet("example_ftest9", groundTruth);
    }

    @Test
    public void test23() {
        String groundTruth = "25|If-B3-[B4][null]-[x(22|LoopExit)][null]\n" +
                "7|If-B0-[B1][null]-[B2, B3, B4, B5][null]\n";
        testCodeSnippet("example_ftest23", groundTruth);
    }

    @Test
    public void test5() {
        String groundTruth = "4|IntegerSwitch-B0-[B1][null]-[B2][null]-[B3][null]\n";
        testCodeSnippet("example_ftest5", groundTruth);
    }

    @Test
    public void test6() {
        String groundTruth = "4|IntegerSwitch-B0-[B1,B3][B6]-[B5][B6]-[B4][null]-[B2,B3][B6]\n";
        testCodeSnippet("example_ftest6", groundTruth);
    }

    @Test
    public void test31() {
        String groundTruth = "41|If-B4-[x(39|LoopExit)][null]-[B7][null]\n" +
                "30|If-B2-[x(28|LoopExit)][null]-[B4, B7, B5][null]\n" +
                "21|If-B1-[x(18|LoopExit)][null]-[B2, B3, B4, B5, B6, B7][null]\n";
        testCodeSnippet("example_ftest31", groundTruth);
    }

    @Test
    public void test68() {
        String groundTruth = "36|If-B3-[B4][null]-[x(33|LoopExit)][null]\n" +
                "14|IntegerSwitch-B1-[B10][null]-[B8][B9]-[B6,B7][B9]-[B2,B3,B4,B5,B7][B9]\n" +
                "13|If-B0-[B11][null]-[B1,B2,B3,B4,B5,B7,B9,B6,B8,B10][null]\n";
        testCodeSnippet("example_ftest68", groundTruth);
    }

    @Test
    public void test69() {
        String groundTruth = "8|If-B0-[B1,B2,B3,B4,B5,B6,B7,B8,B9,B10,B11,B12][null]-[B13][null]\n" +
                "9|IntegerSwitch-B1-[B12][null]-[B10][B11]-[B8,B9][B11]-[B7,B9][B11]-[B5][B6]-[B3,B4][B6]-[B2,B4][B6]\n";
        testCodeSnippet("example_ftest69", groundTruth);
    }

    @Test
    public void test49() {
        String groundTruth = "7|If-B0-[B1,B2,B3,B4,B5][null]-[B6][null]\n" +
                "8|IntegerSwitch-B1-[B2][null]-[B3][null]-[B4][null]\n";
        testCodeSnippet("example_ftest49", groundTruth);
    }

    // NodeDescription-Head-Branch1-Branch2-Branch3
    // BranchI: [son][tail];[son attr 1][tail attr 1]:[son attr 2][tail attr 2]:[cs global attr]:etc.
    // attr correspond to attributes respectively
    @Test
    public void test_ControlSplit() {
        String groundTruth = "9|If-B0-[B1,B2,B3,B4,B5,B6][null];[2][0]:[2][0]:[0]:[0]:[0]:[0]-[B7,B8,B9,B11,B12,B13][null];[2][0]:[2][0]:[0]:[0]:[0]:[0]\n" +
                "10|IntegerSwitch-B1-[B2][null];[0][0]:[0][0]:[1]:[13]:[26]:[16]-[B3][null];[0][0]:[0][0]:[1]:[13]:[26]:[16]-[B4,B5,B6][null];[2][0]:[1][0]:[1]:[13]:[26]:[16]\n" +
                "17|If-B4-[B5][null];[0][0]:[0][0]:[2]:[6]:[12]:[5]-[B6][null];[0][0]:[0][0]:[2]:[6]:[12]:[5]\n" +
                "42|If-B8-[x(39|LoopExit)][null];[0][0]:[0][0]:[1]:[13]:[26]:[16]-[B11,B12,B13][null];[2][0]:[1][0]:[1]:[13]:[26]:[16]\n" +
                "46|If-B11-[B12][null];[0][0]:[0][0]:[2]:[5]:[10]:[5]-[B13][null];[0][0]:[0][0]:[2]:[5]:[10]:[5]\n";
        String[] attributes = {"Max CS Depth", "N. Control Splits", "CS Depth", "N. CS Father Blocks", "N. CS Father Fixed Nodes", "N. CS Father Floating Nodes"};
        testAttributesCodeSnippet("example_ControlSplits", attributes, groundTruth);
    }

    /* SOURCE CODES */
    // IF
    private static void example_ftest1(int a) {
        if (a > 0)
            System.out.println("+");
        else
            System.out.print("-");
        return;
    }

    private static void example_ftest4(int a) {
        //Write your function here
        if (a > 5)
            return;
        System.out.println("line 1");
        System.out.println("line 2");
        System.out.println("line 3");
        System.out.println("line 4");

        return;
    }

    private static void example_ftest3(double a) {
        //Write your function here
        if (a > 0.5)
            System.console();
        System.out.println("line 1");
        System.out.println("line 2");
        System.out.println("line 3");
        System.out.println("line 4");

        return;
    }

    private static void example_ftest14(int a, int b, int c) {
        //Write your function here
        // composite if
        if (a > b) {
            if (a > c) {
                System.console();
            } else {
                System.console();
            }
            System.console(); // join together
        } else {
            System.out.println();
            if (b > c) {
                System.console();
            } else {
                System.console();
            } // not join together
        }
        return;
    }

    private static void example_ftest10(int a) throws Exception {
        //Write your function here
        if (a > 100)
            throw new Exception();
        System.console();
        System.console();

        return;
    }

    // LOOPS
    private static void example_ftest7(int a) {
        //Write your function here
        int i = 0;
        while (i < a) {
            System.out.println(i);
            i += 1;
        }
        System.console();
        return;
    }

    private static void example_ftest8(int a) {
        //Write your function here
        for (int i = 0; i < a; i++) {
            System.out.println(i);
            i += 1;
        }
        System.console();
        return;
    }

    private static void example_ftest9(int a) {
        //Write your function here
        int i = 0;
        do {
            System.out.println(i);
            i += 1;
        } while (i <= a);
        System.console();
        return;
    }

    private static void example_ftest23(int a, int b) {
        //Write your function here
        if (a < b)
            return;
        System.out.println("Begin");
        int i = 0;
        while (i < a) {
            System.out.println(i);
            i += 1;
        }
        System.out.println("End");
        return;
    }


    // SWITCHS
    private static void example_ftest5(int a, int b) {
        //Write your function here
        switch (a) {
            case 1:
                System.out.println("1");
                break;
            case 2:
                System.out.print("2");
                break;
            default:
                System.out.println();
        }
        System.console();
        return;
    }

    private static void example_ftest6(int a, int b) {
        //Write your function here
        switch (a) {
            case 1:
                System.out.println();
            case 2:
                System.console();
                break;
            case 3:
                System.out.println("3");
                return;
            default:
                System.out.println("def");
        }
        System.console();
        System.console();
        return;
    }

    private static void example_ftest49(int a, int b) {
        //Write your function here
        if (a > b) {
            switch (a) {
                case 1:
                    System.out.println("1");
                    break;
                case 2:
                    System.out.println("2");
                    break;
                default:
                    System.out.println("def");
                    break;
            }
            System.out.print("Epilog");
        } else {
            System.out.print("else brabch");
            System.out.println();
        }

        System.console();
        System.console();
        return;
    }

    // INTEGRATED
    private static void example_ftest31(int a, int b, int c) throws Exception {
        //Write your function here
        System.out.println("Begin");
        for (int i = 0; i < a; i++) {
            System.console();
            if (i > b)
                throw new Exception();
            System.out.println("body");
            if (i > c)
                throw new Exception();
            System.console();
        }
        System.out.println("End");
        return;
    }

    private static void example_ftest68(int a, int b) {
        System.console();
        System.console();
        if (a > b) {
            switch (a) {
                case 1:
                    for (int i = 0; i < b; i++)
                        System.out.println(i);
                    System.out.print("loop end");
                case 2:
                    System.console();
                case 3:
                    System.out.println();
                    break;
                default:
                    System.out.print("def.");
                    return;
            }
        } else
            System.out.println();
        System.console();
        System.console();
        return;
    }

    private static void example_ftest69(int a, int b, int c) {
        //Write your function here
        if (a > b) {
            switch (a) {
                case 1:
                    System.out.print("1");
                case 2:
                    System.out.println("2");
                case 3:
                    System.console();
                    break;
                case 4:
                    System.out.print("4");
                case 5:
                    System.out.println("5");
                case 6:
                    System.out.println("6");
                    break;
                default:
                    return;
            }
        } else
            System.out.println();
        System.console();
        System.console();
        return;
    }

    /* SOURCE CODES FOR ATTRIBUTES TESTS */
    private static int example_ControlSplits(int a, int b, int c) {
        int tmp = 11;
        if (a > b) {
            switch (c) {
                case 1:
                    tmp += b;
                    break;
                case 2:
                    tmp *= b;
                    break;
                default:
                    if (c > b)
                        tmp = 9;
                    else
                        tmp = 7;
            }
        } else {
            int i = 0;
            while (i < a) {
                if (tmp > b)
                    tmp -= c;
                else
                    tmp += a;
            }
        }
        return tmp;
    }

}
