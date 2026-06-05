import java.util.ArrayList;
import java.util.List;

// =============================================================================
//  BlockchainTest.java
//  Self-contained unit test suite — no external libraries required.
//
//  Run with:  java BlockchainTest
//
//  Tests cover:
//    1.  Transaction creation & field access
//    2.  Transaction validation (amount rules)
//    3.  Proof-of-work block mining (hash starts with DIFFICULTY zeros)
//    4.  Genesis block properties
//    5.  Blockchain integrity after adding blocks
//    6.  Correct block linking (previousHash chain)
//    7.  Balance calculations derived from transaction history
//    8.  Double-spending prevention (pending pool balance check)
//    9.  Multi-node consensus (block acceptance & chain agreement)
//   10.  Chain tamper detection
//
//  Each test prints  [PASS] or [FAIL] with a description.
//  A summary is printed at the end.
// =============================================================================

public class BlockchainTest {

    // ---- counters -----------------------------------------------------------
    private static int total  = 0;
    private static int passed = 0;
    private static int failed = 0;

    // =========================================================================
    //  main
    // =========================================================================
    public static void main(String[] args) {
        printHeader("BLOCKCHAIN TEST SUITE");

        test1_TransactionCreation();
        test2_TransactionValidation();
        test3_BlockMining();
        test4_GenesisBlock();
        test5_BlockchainIntegrity();
        test6_BlockLinking();
        test7_BalanceCalculations();
        test8_DoubleSpendingPrevention();
        test9_MultiNodeConsensus();
        test10_ChainTamperDetection();

        printSummary();
    }

    // =========================================================================
    //  TEST 1 – Transaction creation
    // =========================================================================
    private static void test1_TransactionCreation() {
        section("Test 1: Transaction Creation");

        Blockchain.Transaction tx =
                new Blockchain.Transaction("Alice", "Bob", 42.5);

        check("TX1.1 sender is Alice",          tx.getSender().equals("Alice"));
        check("TX1.2 receiver is Bob",          tx.getReceiver().equals("Bob"));
        check("TX1.3 amount is 42.5",           tx.getAmount() == 42.5);
        check("TX1.4 timestamp > 0",            tx.getTimestamp() > 0);
        check("TX1.5 toString contains Alice",  tx.toString().contains("Alice"));
        check("TX1.6 toString contains Bob",    tx.toString().contains("Bob"));
        check("TX1.7 toData is deterministic",
                tx.toData().equals(tx.toData()));

        // Custom timestamp constructor
        Blockchain.Transaction tx2 =
                new Blockchain.Transaction("X", "Y", 10.0, 123456789L);
        check("TX1.8 custom timestamp preserved",
                tx2.getTimestamp() == 123456789L);
    }

    // =========================================================================
    //  TEST 2 – Transaction validation (amount rules)
    // =========================================================================
    private static void test2_TransactionValidation() {
        section("Test 2: Transaction Validation (amount rules)");

        Blockchain bc = new Blockchain();

        check("TX2.1 valid transaction accepted",
                bc.addTransaction(new Blockchain.Transaction("MacBook", "Pi1", 100.0)));

        check("TX2.2 zero-amount rejected",
                !bc.addTransaction(new Blockchain.Transaction("MacBook", "Pi1", 0.0)));

        check("TX2.3 negative amount rejected",
                !bc.addTransaction(new Blockchain.Transaction("MacBook", "Pi1", -1.0)));

        check("TX2.4 SYSTEM can always send regardless of 'balance'",
                bc.addTransaction(new Blockchain.Transaction("SYSTEM", "Pi1", 9999.0)));
    }

    // =========================================================================
    //  TEST 3 – Proof-of-work block mining
    // =========================================================================
    private static void test3_BlockMining() {
        section("Test 3: Proof-of-Work Block Mining  (please wait…)");

        List<Blockchain.Transaction> txList = new ArrayList<>();
        txList.add(new Blockchain.Transaction("Alice", "Bob", 25.0));
        txList.add(new Blockchain.Transaction("Carol", "Dave", 10.0));

        Blockchain.Block block =
                new Blockchain.Block(1, txList, "prevHash000", "TestMiner");

        long start = System.currentTimeMillis();
        block.mineBlock();
        long elapsed = System.currentTimeMillis() - start;

        String target = "0".repeat(Blockchain.Block.DIFFICULTY);

        check("BM3.1 hash starts with " + Blockchain.Block.DIFFICULTY + " zeros",
                block.getHash().startsWith(target));

        check("BM3.2 stored hash equals recalculated hash",
                block.getHash().equals(block.calculateHash()));

        check("BM3.3 block.isValid() returns true",
                block.isValid());

        check("BM3.4 nonce is positive after mining",
                block.getNonce() > 0);

        check("BM3.5 mining completed in under 120 seconds",
                elapsed < 120_000);

        System.out.println("       (mining took " + elapsed / 1000.0 + "s, nonce=" + block.getNonce() + ")");
    }

    // =========================================================================
    //  TEST 4 – Genesis block
    // =========================================================================
    private static void test4_GenesisBlock() {
        section("Test 4: Genesis Block");

        Blockchain bc1 = new Blockchain();
        Blockchain bc2 = new Blockchain();

        Blockchain.Block g1 = bc1.getChain().get(0);
        Blockchain.Block g2 = bc2.getChain().get(0);

        check("GB4.1 genesis exists",                       g1 != null);
        check("GB4.2 genesis index is 0",                   g1.getIndex() == 0);
        check("GB4.3 genesis previousHash is '0'",          g1.getPreviousHash().equals("0"));
        check("GB4.4 genesis has transactions",             !g1.getTransactions().isEmpty());
        check("GB4.5 MacBook seeded in genesis",
                g1.getTransactions().stream().anyMatch(
                        t -> t.getReceiver().equals("MacBook")));
        check("GB4.6 genesis hash is deterministic across instances",
                g1.getHash().equals(g2.getHash()));
        check("GB4.7 initial MacBook balance is 1000",
                bc1.getBalance("MacBook") == 1000.0);
        check("GB4.8 initial Pi1 balance is 500",
                bc1.getBalance("Pi1") == 500.0);
    }

    // =========================================================================
    //  TEST 5 – Blockchain integrity (adding blocks)
    // =========================================================================
    private static void test5_BlockchainIntegrity() {
        section("Test 5: Blockchain Integrity  (please wait…)");

        Blockchain bc = new Blockchain();

        check("BI5.1 fresh chain has length 1",  bc.getLength() == 1);
        check("BI5.2 fresh chain is valid",      bc.isChainValid());

        bc.addTransaction(new Blockchain.Transaction("MacBook", "Pi1", 50.0));
        bc.minePendingTransactions("Miner");

        check("BI5.3 chain length is 2 after mining",
                bc.getLength() == 2);

        check("BI5.4 chain is still valid",
                bc.isChainValid());

        check("BI5.5 pending pool cleared after mining",
                bc.getPendingTransactions().isEmpty());

        // Mine a second block
        bc.addTransaction(new Blockchain.Transaction("Pi1", "Pi2", 20.0));
        bc.minePendingTransactions("Miner2");

        check("BI5.6 chain length is 3 after second mine",
                bc.getLength() == 3);

        check("BI5.7 chain is still valid after two mines",
                bc.isChainValid());
    }

    // =========================================================================
    //  TEST 6 – Block linking (previousHash chain)
    // =========================================================================
    private static void test6_BlockLinking() {
        section("Test 6: Block Linking  (please wait…)");

        Blockchain bc = new Blockchain();
        bc.addTransaction(new Blockchain.Transaction("MacBook", "Pi2", 75.0));
        bc.minePendingTransactions("Pi3");

        Blockchain.Block genesis = bc.getChain().get(0);
        Blockchain.Block block1  = bc.getChain().get(1);

        check("BL6.1 block1 previousHash equals genesis hash",
                block1.getPreviousHash().equals(genesis.getHash()));

        check("BL6.2 block1 index is 1",
                block1.getIndex() == 1);

        check("BL6.3 block1 miner is correct",
                block1.getMinerNodeId().equals("Pi3"));

        check("BL6.4 block1 contains the expected transaction",
                block1.getTransactions().stream()
                        .anyMatch(t -> t.getSender().equals("MacBook")
                                && t.getReceiver().equals("Pi2")
                                && t.getAmount() == 75.0));

        // Chain a second block
        bc.addTransaction(new Blockchain.Transaction("Pi2", "Pi3", 30.0));
        bc.minePendingTransactions("MacBook");
        Blockchain.Block block2 = bc.getChain().get(2);

        check("BL6.5 block2 previousHash equals block1 hash",
                block2.getPreviousHash().equals(block1.getHash()));
    }

    // =========================================================================
    //  TEST 7 – Balance calculations
    // =========================================================================
    private static void test7_BalanceCalculations() {
        section("Test 7: Balance Calculations  (please wait…)");

        Blockchain bc = new Blockchain();
        // Genesis: MacBook=1000, Pi1=500, Pi2=500, Pi3=500

        check("BAL7.1 MacBook initial balance is 1000",
                bc.getBalance("MacBook") == 1000.0);

        check("BAL7.2 Pi1 initial balance is 500",
                bc.getBalance("Pi1") == 500.0);

        check("BAL7.3 unknown address balance is 0",
                bc.getBalance("Nobody") == 0.0);

        // MacBook sends 200 to Pi1, Pi2 mines (earns 50 reward).
        bc.addTransaction(new Blockchain.Transaction("MacBook", "Pi1", 200.0));
        bc.minePendingTransactions("Pi2");

        check("BAL7.4 MacBook balance is 800 after sending 200",
                bc.getBalance("MacBook") == 800.0);

        check("BAL7.5 Pi1 balance is 700 after receiving 200",
                bc.getBalance("Pi1") == 700.0);

        check("BAL7.6 Pi2 balance is 550 after mining reward",
                bc.getBalance("Pi2") == 550.0);

        // Pi1 sends 100 to Pi3, Pi3 mines.
        bc.addTransaction(new Blockchain.Transaction("Pi1", "Pi3", 100.0));
        bc.minePendingTransactions("Pi3");

        check("BAL7.7 Pi1 balance is 600 after sending 100",
                bc.getBalance("Pi1") == 600.0);

        check("BAL7.8 Pi3 balance is 650 after receiving 100 + 50 reward",
                bc.getBalance("Pi3") == 650.0);

        // getAllBalances should include all four accounts.
        check("BAL7.9 getAllBalances contains MacBook",
                bc.getAllBalances().containsKey("MacBook"));
    }

    // =========================================================================
    //  TEST 8 – Double-spending prevention
    // =========================================================================
    private static void test8_DoubleSpendingPrevention() {
        section("Test 8: Double-Spending Prevention");

        Blockchain bc = new Blockchain();
        // MacBook has 1000, Pi1 has 500.

        // Spend 800 — should succeed.
        boolean first = bc.addTransaction(
                new Blockchain.Transaction("MacBook", "Pi2", 800.0));
        check("DS8.1 first tx of 800 accepted (balance 1000)",
                first);

        // Spend another 300 while 800 is still pending — available = 1000-800 = 200.
        boolean second = bc.addTransaction(
                new Blockchain.Transaction("MacBook", "Pi3", 300.0));
        check("DS8.2 second tx of 300 rejected (only 200 available)",
                !second);

        // Spend exactly what remains (200) — should succeed.
        boolean third = bc.addTransaction(
                new Blockchain.Transaction("MacBook", "Pi3", 200.0));
        check("DS8.3 tx for remaining 200 accepted",
                third);

        // Try to spend more than balance in a single transaction.
        boolean overSpend = bc.addTransaction(
                new Blockchain.Transaction("Pi1", "MacBook", 600.0));
        check("DS8.4 tx exceeding Pi1 balance (500) rejected",
                !overSpend);

        // A valid tx within Pi1's balance.
        boolean validSmall = bc.addTransaction(
                new Blockchain.Transaction("Pi1", "MacBook", 499.0));
        check("DS8.5 tx within Pi1 balance accepted",
                validSmall);

        // New blockchain — attempt to spend twice in two separate transactions
        // whose combined total exceeds balance.
        Blockchain bc2 = new Blockchain();
        bc2.addTransaction(new Blockchain.Transaction("Pi2", "Pi1", 400.0));
        boolean dup = bc2.addTransaction(new Blockchain.Transaction("Pi2", "Pi1", 200.0));
        check("DS8.6 combined pending spend (600) exceeds Pi2 balance (500) — rejected",
                !dup);
    }

    // =========================================================================
    //  TEST 9 – Multi-node consensus
    // =========================================================================
    private static void test9_MultiNodeConsensus() {
        section("Test 9: Multi-Node Consensus  (please wait…)");

        // Four separate blockchain instances simulating four nodes.
        Blockchain n1 = new Blockchain();
        Blockchain n2 = new Blockchain();
        Blockchain n3 = new Blockchain();
        Blockchain n4 = new Blockchain();

        // All nodes start with the same genesis.
        check("MC9.1 all nodes start at length 1",
                n1.getLength() == 1 && n2.getLength() == 1
                && n3.getLength() == 1 && n4.getLength() == 1);

        check("MC9.2 all nodes share the same genesis hash",
                n1.getChain().get(0).getHash().equals(n2.getChain().get(0).getHash())
                && n2.getChain().get(0).getHash().equals(n3.getChain().get(0).getHash()));

        // Node 1 mines a block.
        n1.addTransaction(new Blockchain.Transaction("MacBook", "Pi1", 100.0));
        Blockchain.Block mined = n1.minePendingTransactions("Node1");

        check("MC9.3 mined block is not null", mined != null);

        // Broadcast: other nodes accept the block.
        boolean ok2 = n2.addMinedBlock(mined);
        boolean ok3 = n3.addMinedBlock(mined);
        boolean ok4 = n4.addMinedBlock(mined);

        check("MC9.4 node 2 accepts block from node 1", ok2);
        check("MC9.5 node 3 accepts block from node 1", ok3);
        check("MC9.6 node 4 accepts block from node 1", ok4);

        check("MC9.7 all nodes have same chain length (2)",
                n1.getLength() == 2 && n2.getLength() == 2
                && n3.getLength() == 2 && n4.getLength() == 2);

        check("MC9.8 all chains are valid",
                n1.isChainValid() && n2.isChainValid()
                && n3.isChainValid() && n4.isChainValid());

        // Balance consensus — all nodes should agree.
        double bal1 = n1.getBalance("MacBook");
        double bal2 = n2.getBalance("MacBook");
        double bal3 = n3.getBalance("MacBook");
        double bal4 = n4.getBalance("MacBook");

        check("MC9.9 all nodes agree on MacBook balance",
                bal1 == bal2 && bal2 == bal3 && bal3 == bal4);

        check("MC9.10 MacBook balance is 900 (sent 100)",
                bal1 == 900.0);

        // Reject a stale / already-added block.
        boolean duplicate = n2.addMinedBlock(mined);
        check("MC9.11 duplicate block rejected by node 2",
                !duplicate);

        // Longest-chain rule: give n3 a longer chain — n2 should adopt it.
        n3.addTransaction(new Blockchain.Transaction("Pi2", "Pi3", 50.0));
        n3.minePendingTransactions("Pi2");
        boolean replaced = n2.replaceChain(n3.getChain());
        check("MC9.12 shorter chain replaced by longer valid chain",
                replaced && n2.getLength() == n3.getLength());
    }

    // =========================================================================
    //  TEST 10 – Chain tamper detection
    // =========================================================================
    private static void test10_ChainTamperDetection() {
        section("Test 10: Chain Tamper Detection  (please wait…)");

        Blockchain bc = new Blockchain();
        bc.addTransaction(new Blockchain.Transaction("MacBook", "Pi1", 50.0));
        bc.minePendingTransactions("Pi2");

        check("CT10.1 chain is valid before tampering",
                bc.isChainValid());

        // Verify that SHA-256 is deterministic.
        Blockchain.Block block1 = bc.getChain().get(1);
        String h1 = block1.calculateHash();
        String h2 = block1.calculateHash();
        check("CT10.2 calculateHash() is deterministic",
                h1.equals(h2));

        // Verify stored hash matches recalculated hash.
        check("CT10.3 stored hash equals recalculated hash",
                block1.getHash().equals(block1.calculateHash()));

        // Tamper: corrupt the stored hash manually.
        String originalHash = block1.getHash();
        block1.setHash("0000" + "0000000000000000000000000000000000000000000000000000000000");
        check("CT10.4 isChainValid() detects tampered hash",
                !bc.isChainValid());

        // Restore and confirm valid again.
        block1.setHash(originalHash);
        check("CT10.5 chain is valid again after restoring hash",
                bc.isChainValid());

        // Verify that adding a block with a bad hash is rejected.
        List<Blockchain.Transaction> fakeTxs = new ArrayList<>();
        fakeTxs.add(new Blockchain.Transaction("Hacker", "Hacker", 9999.0));
        Blockchain.Block fakeBlock =
                new Blockchain.Block(bc.getLength(), fakeTxs,
                        bc.getLatestBlock().getHash(), "Hacker");
        // Do NOT mine — hash won't start with DIFFICULTY zeros.
        check("CT10.6 unmined block (bad difficulty) rejected by addMinedBlock",
                !bc.addMinedBlock(fakeBlock));
    }

    // =========================================================================
    //  Utility methods
    // =========================================================================

    /** Assert condition and record the result. */
    private static void check(String name, boolean condition) {
        total++;
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static void section(String title) {
        System.out.println("\n--- " + title + " ---");
    }

    private static void printHeader(String title) {
        int w = 52;
        String border = "╔" + "═".repeat(w) + "╗";
        String footer = "╚" + "═".repeat(w) + "╝";
        System.out.println(border);
        int pad = (w - title.length()) / 2;
        System.out.println("║" + " ".repeat(pad) + title
                + " ".repeat(w - pad - title.length()) + "║");
        System.out.println(footer);
    }

    private static void printSummary() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║                    TEST SUMMARY                      ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf ("║  Total Tests  : %-35d ║%n", total);
        System.out.printf ("║  Passed       : %-35d ║%n", passed);
        System.out.printf ("║  Failed       : %-35d ║%n", failed);
        double pct = total > 0 ? (passed * 100.0 / total) : 0.0;
        System.out.printf ("║  Pass Rate    : %-34.1f%% ║%n", pct);
        System.out.println("╚══════════════════════════════════════════════════════╝");

        if (failed == 0) {
            System.out.println("\nAll " + total + " tests passed.");
        } else {
            System.out.println("\n" + failed + " test(s) FAILED.");
        }
    }
}
