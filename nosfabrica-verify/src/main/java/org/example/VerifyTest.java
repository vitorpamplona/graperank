package org.example;

import org.example.grape.*;
import java.util.*;

/**
 * Runs NosFabrica's GrapeRankAlgorithm against the same graph scenarios
 * used in vitorpamplona/graperank's Kotlin tests, to verify results match.
 */
public class VerifyTest {

    static String OBS = "observer";

    // Helper: build inputs and scorecards, run algorithm, return influence map
    static Map<String, Double> run(String observer, List<String> users,
                                    List<GrapeRankInput> inputList) {
        // Build input map: ratee -> list of inputs
        Map<String, List<GrapeRankInput>> inputMap = new HashMap<>();
        for (GrapeRankInput inp : inputList) {
            inputMap.computeIfAbsent(inp.getRatee(), k -> new ArrayList<>()).add(inp);
        }

        // Build scorecards
        Map<String, ScoreCard> scorecards = new HashMap<>();
        for (String user : users) {
            if (user.equals(observer)) {
                scorecards.put(user, new ScoreCard(observer, user, 1.0, Double.POSITIVE_INFINITY, 1.0, 1.0));
            } else {
                scorecards.put(user, new ScoreCard(observer, user, 999));
            }
        }

        GrapeRankAlgorithmResult result = GrapeRankAlgorithm.graperankAlgorithm(inputMap, scorecards);

        Map<String, Double> influences = new HashMap<>();
        for (Map.Entry<String, ScoreCard> e : result.getScorecards().entrySet()) {
            influences.put(e.getKey(), e.getValue().getInfluence());
        }
        return influences;
    }

    // Helper to create a follow input
    static GrapeRankInput follow(String rater, String ratee, String observer) {
        double conf = rater.equals(observer)
            ? Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW_FROM_OBSERVER
            : Constants.DEFAULT_CONFIDENCE_FOR_FOLLOW;
        return new GrapeRankInput(rater, ratee, Constants.DEFAULT_RATING_FOR_FOLLOW, conf);
    }

    static GrapeRankInput mute(String rater, String ratee) {
        return new GrapeRankInput(rater, ratee, Constants.DEFAULT_RATING_FOR_MUTE, Constants.DEFAULT_CONFIDENCE_FOR_MUTE);
    }

    static GrapeRankInput report(String rater, String ratee) {
        return new GrapeRankInput(rater, ratee, Constants.DEFAULT_RATING_FOR_REPORT, Constants.DEFAULT_CONFIDENCE_FOR_REPORT);
    }

    static void assertClose(String label, double expected, double actual) {
        double diff = Math.abs(expected - actual);
        String status = diff < 0.0001 ? "PASS" : "FAIL";
        if (!status.equals("PASS")) {
            System.out.printf("  %s %s: expected=%.15f actual=%.15f delta=%.10f%n", status, label, expected, actual, diff);
        }
    }

    static int passed = 0;
    static int failed = 0;

    static void check(String label, double expected, double actual) {
        double diff = Math.abs(expected - actual);
        if (diff < 0.0001) {
            passed++;
        } else {
            failed++;
            System.out.printf("  FAIL %s: expected=%.15f actual=%.15f delta=%.10f%n", label, expected, actual, diff);
        }
    }

    public static void main(String[] args) {

        // ============================================================
        // TEST 1: SimpleGraph (v1 equivalent)
        // alice follows bob, bob follows david, bob follows charlie,
        // david mutes charlie, alice reports charlie
        // ============================================================
        System.out.println("=== TEST 1: SimpleGraph ===");
        {
            String alice = "alice", bob = "bob", charlie = "charlie", david = "david";
            List<String> users = List.of(alice, bob, charlie, david);
            List<GrapeRankInput> inputs = List.of(
                follow(alice, bob, alice),
                follow(bob, david, alice),
                follow(bob, charlie, alice),
                mute(david, charlie),
                report(alice, charlie)
            );
            Map<String, Double> inf = run(alice, users, inputs);
            check("alice", 1.0, inf.get(alice));
            check("bob", 0.25516126843864884, inf.get(bob));
            check("charlie", 0.0, inf.get(charlie));
            check("david", 0.004499885043810381, inf.get(david));
        }

        // ============================================================
        // TEST 2: LinearGraph (center observer)
        // ============================================================
        System.out.println("=== TEST 2: LinearGraph (center) ===");
        {
            String m4="m4",m3="m3",m2="m2",m1="m1",c="center",p1="p1",p2="p2",p3="p3",p4="p4";
            List<String> users = List.of(m4,m3,m2,m1,c,p1,p2,p3,p4);
            List<GrapeRankInput> inputs = List.of(
                follow(m4,m3,c), follow(m3,m2,c), follow(m2,m1,c), follow(m1,c,c),
                follow(c,p1,c), follow(p1,p2,c), follow(p2,p3,c), follow(p3,p4,c)
            );
            Map<String, Double> inf = run(c, users, inputs);
            check("m4", 0.0, inf.get(m4));
            check("m3", 0.0, inf.get(m3));
            check("m2", 0.0, inf.get(m2));
            check("m1", 0.0, inf.get(m1));
            check("center", 1.0, inf.get(c));
            check("p1", 0.25516126843864884, inf.get(p1));
            check("p2", 0.004499885043810381, inf.get(p2));
            check("p3", 7.953344413746954E-5, inf.get(p3));
            check("p4", 0.0, inf.get(p4));
        }

        // ============================================================
        // TEST 3: LinearGraph (minus4 observer)
        // ============================================================
        System.out.println("=== TEST 3: LinearGraph (minus4) ===");
        {
            String m4="m4",m3="m3",m2="m2",m1="m1",c="center",p1="p1",p2="p2",p3="p3",p4="p4";
            List<String> users = List.of(m4,m3,m2,m1,c,p1,p2,p3,p4);
            List<GrapeRankInput> inputs = List.of(
                follow(m4,m3,m4), follow(m3,m2,m4), follow(m2,m1,m4), follow(m1,c,m4),
                follow(c,p1,m4), follow(p1,p2,m4), follow(p2,p3,m4), follow(p3,p4,m4)
            );
            Map<String, Double> inf = run(m4, users, inputs);
            check("m4", 1.0, inf.get(m4));
            check("m3", 0.25516126843864884, inf.get(m3));
            check("m2", 0.004499885043810381, inf.get(m2));
            check("m1", 7.953344413746954E-5, inf.get(m1));
            check("center", 0.0, inf.get(c));
        }

        // ============================================================
        // TEST 4: CircularGraph
        // ============================================================
        System.out.println("=== TEST 4: CircularGraph ===");
        {
            String p1="p1",p2="p2",p3="p3";
            List<String> users = List.of(p1,p2,p3);
            List<GrapeRankInput> inputs = List.of(
                follow(p1,p2,p1), follow(p2,p3,p1), follow(p3,p1,p1)
            );
            Map<String, Double> inf = run(p1, users, inputs);
            check("p1", 1.0, inf.get(p1));
            check("p2", 0.25516126843864884, inf.get(p2));
            check("p3", 0.004499885043810381, inf.get(p3));
        }

        // ============================================================
        // TEST 5: SimpleFollow 2 plebs (pleb1 observer)
        // ============================================================
        System.out.println("=== TEST 5: SimpleFollow 2 plebs ===");
        {
            String p1="p1",p2="p2";
            List<String> users = List.of(p1,p2);
            List<GrapeRankInput> inputs = List.of(follow(p1,p2,p1));
            Map<String, Double> inf = run(p1, users, inputs);
            check("p1", 1.0, inf.get(p1));
            check("p2", 0.25516126843864884, inf.get(p2));
        }

        // ============================================================
        // TEST 6: SimpleFollow 3 plebs
        // ============================================================
        System.out.println("=== TEST 6: SimpleFollow 3 plebs ===");
        {
            String p1="p1",p2="p2",p3="p3";
            List<String> users = List.of(p1,p2,p3);
            List<GrapeRankInput> inputs = List.of(
                follow(p1,p2,p1), follow(p2,p3,p1)
            );
            Map<String, Double> inf = run(p1, users, inputs);
            check("p1", 1.0, inf.get(p1));
            check("p2", 0.25516126843864884, inf.get(p2));
            check("p3", 0.004499885043810381, inf.get(p3));
        }

        // ============================================================
        // TEST 7: ReactiveSweep step-by-step
        // (Each step rebuilds the full graph from scratch, like v1)
        // ============================================================
        System.out.println("=== TEST 7: ReactiveSweep steps ===");
        {
            String alice="alice",bob="bob",charlie="charlie",david="david";
            List<String> users = List.of(alice,bob,charlie,david);

            // Step 1: alice follows bob
            List<GrapeRankInput> inputs1 = new ArrayList<>(List.of(
                follow(alice,bob,alice)
            ));
            Map<String, Double> inf1 = run(alice, users, inputs1);
            check("step1 bob", 0.25516126843864884, inf1.get(bob));

            // Step 2: + bob follows david
            List<GrapeRankInput> inputs2 = new ArrayList<>(List.of(
                follow(alice,bob,alice), follow(bob,david,alice)
            ));
            Map<String, Double> inf2 = run(alice, users, inputs2);
            check("step2 bob", 0.25516126843864884, inf2.get(bob));
            check("step2 david", 0.004499885043810381, inf2.get(david));

            // Step 3: + bob follows charlie
            List<GrapeRankInput> inputs3 = new ArrayList<>(List.of(
                follow(alice,bob,alice), follow(bob,david,alice), follow(bob,charlie,alice)
            ));
            Map<String, Double> inf3 = run(alice, users, inputs3);
            check("step3 bob", 0.25516126843864884, inf3.get(bob));
            check("step3 charlie", 0.004499885043810381, inf3.get(charlie));
            check("step3 david", 0.004499885043810381, inf3.get(david));

            // Step 4: + david mutes charlie
            List<GrapeRankInput> inputs4 = new ArrayList<>(List.of(
                follow(alice,bob,alice), follow(bob,david,alice), follow(bob,charlie,alice),
                mute(david,charlie)
            ));
            Map<String, Double> inf4 = run(alice, users, inputs4);
            check("step4 bob", 0.25516126843864884, inf4.get(bob));
            check("step4 charlie", 0.0043647310818470215, inf4.get(charlie));
            check("step4 david", 0.004499885043810381, inf4.get(david));

            // Step 5: + alice reports charlie
            List<GrapeRankInput> inputs5 = new ArrayList<>(List.of(
                follow(alice,bob,alice), follow(bob,david,alice), follow(bob,charlie,alice),
                mute(david,charlie), report(alice,charlie)
            ));
            Map<String, Double> inf5 = run(alice, users, inputs5);
            check("step5 bob", 0.25516126843864884, inf5.get(bob));
            check("step5 charlie", 0.0, inf5.get(charlie));
            check("step5 david", 0.004499885043810381, inf5.get(david));
        }

        // ============================================================
        // TEST 8: Celebrity Weak Pleb Network
        // ============================================================
        System.out.println("=== TEST 8: Celebrity Weak Pleb Network ===");
        {
            String cel="celebrity";
            String[] plebs = {"pleb1","pleb2","pleb3","pleb4","pleb5","pleb6","pleb7"};
            String np = "newPleb";
            List<String> allUsers = new ArrayList<>(List.of(cel));
            allUsers.addAll(List.of(plebs));
            allUsers.add(np);

            // Base: all plebs follow celebrity
            List<GrapeRankInput> base = new ArrayList<>();
            for (String p : plebs) base.add(follow(p, cel, np));

            // Step by step: newPleb follows pleb1
            List<GrapeRankInput> inputs = new ArrayList<>(base);
            inputs.add(follow(np, "pleb1", np));
            Map<String, Double> inf = run(np, allUsers, inputs);
            check("weak s1 pleb1", 0.25516126843864884, inf.get("pleb1"));
            check("weak s1 celeb", 0.004499885043810381, inf.get(cel));

            // + newPleb follows pleb2
            inputs.add(follow(np, "pleb2", np));
            inf = run(np, allUsers, inputs);
            check("weak s2 pleb1", 0.25516126843864884, inf.get("pleb1"));
            check("weak s2 celeb", 0.00897952112221323, inf.get(cel));

            // + newPleb follows pleb3
            inputs.add(follow(np, "pleb3", np));
            inf = run(np, allUsers, inputs);
            check("weak s3 celeb", 0.013438999353225234, inf.get(cel));

            // + pleb3 follows pleb1
            inputs.add(follow("pleb3", "pleb1", np));
            inf = run(np, allUsers, inputs);
            check("weak s4 pleb1", 0.2585129571068525, inf.get("pleb1"));
            check("weak s4 celeb", 0.013497443415107724, inf.get(cel));

            // + newPleb follows pleb4..7
            inputs.add(follow(np, "pleb4", np));
            inf = run(np, allUsers, inputs);
            check("weak s5 celeb", 0.0179365915151648, inf.get(cel));

            inputs.add(follow(np, "pleb5", np));
            inf = run(np, allUsers, inputs);
            check("weak s6 celeb", 0.022355763959079122, inf.get(cel));

            inputs.add(follow(np, "pleb6", np));
            inf = run(np, allUsers, inputs);
            check("weak s7 celeb", 0.02675505063500705, inf.get(cel));

            inputs.add(follow(np, "pleb7", np));
            inf = run(np, allUsers, inputs);
            check("weak s8 pleb1", 0.2585129571068525, inf.get("pleb1"));
            check("weak s8 celeb", 0.031134541026618612, inf.get(cel));
        }

        // ============================================================
        // TEST 9: Celebrity Strong Pleb Network
        // ============================================================
        System.out.println("=== TEST 9: Celebrity Strong Pleb Network ===");
        {
            String cel="celebrity";
            String[] plebs = {"pleb1","pleb2","pleb3","pleb4","pleb5","pleb6","pleb7"};
            String np = "newPleb";
            List<String> allUsers = new ArrayList<>(List.of(cel));
            allUsers.addAll(List.of(plebs));
            allUsers.add(np);

            // Base: all plebs follow celebrity + all plebs follow each other
            List<GrapeRankInput> base = new ArrayList<>();
            for (String p : plebs) base.add(follow(p, cel, np));
            for (String a : plebs) for (String b : plebs) base.add(follow(a, b, np));

            // Step by step
            List<GrapeRankInput> inputs = new ArrayList<>(base);
            inputs.add(follow(np, "pleb1", np));
            Map<String, Double> inf = run(np, allUsers, inputs);
            check("strong s1 pleb1", 0.2589640145647133, inf.get("pleb1"));
            check("strong s1 celeb", 0.005105462383908299, inf.get(cel));
            check("strong s1 pleb2", 0.0051055112419791104, inf.get("pleb2"));

            inputs.add(follow(np, "pleb2", np));
            inf = run(np, allUsers, inputs);
            check("strong s2 pleb1", 0.2627065436907533, inf.get("pleb1"));
            check("strong s2 celeb", 0.010130079079383791, inf.get(cel));

            inputs.add(follow(np, "pleb3", np));
            inf = run(np, allUsers, inputs);
            check("strong s3 pleb1", 0.2663905382863555, inf.get("pleb1"));
            check("strong s3 celeb", 0.01507610892383038, inf.get(cel));

            inputs.add(follow("pleb3", "pleb1", np));
            inf = run(np, allUsers, inputs);
            check("strong s4 pleb1", 0.2698870435756965, inf.get("pleb1"));
            check("strong s4 celeb", 0.015143316989020383, inf.get(cel));

            inputs.add(follow(np, "pleb4", np));
            inf = run(np, allUsers, inputs);
            check("strong s5 pleb1", 0.27354348323178446, inf.get("pleb1"));
            check("strong s5 celeb", 0.020012749935916174, inf.get(cel));

            inputs.add(follow(np, "pleb5", np));
            inf = run(np, allUsers, inputs);
            check("strong s6 pleb1", 0.27714359619528595, inf.get("pleb1"));
            check("strong s6 celeb", 0.024807800464691998, inf.get(cel));

            inputs.add(follow(np, "pleb6", np));
            inf = run(np, allUsers, inputs);
            check("strong s7 pleb1", 0.2806888836765492, inf.get("pleb1"));
            check("strong s7 celeb", 0.029530446702571433, inf.get(cel));

            inputs.add(follow(np, "pleb7", np));
            inf = run(np, allUsers, inputs);
            check("strong s8 pleb1", 0.28418078392601753, inf.get("pleb1"));
            check("strong s8 celeb", 0.034182583468493344, inf.get(cel));
        }

        // ============================================================
        System.out.println();
        System.out.println("============ RESULTS ============");
        System.out.printf("PASSED: %d  FAILED: %d%n", passed, failed);
        if (failed > 0) {
            System.out.println("*** SOME TESTS FAILED ***");
            System.exit(1);
        } else {
            System.out.println("ALL TESTS PASSED - NosFabrica results match our Kotlin implementation!");
        }
    }
}
