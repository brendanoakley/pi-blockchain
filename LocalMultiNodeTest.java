import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

// =============================================================================
//  LocalMultiNodeTest.java
//  Simulates a 4-node blockchain network in a single program — no separate
//  terminals needed.  Uses in-process message passing rather than real TCP
//  so the simulation is reliable and self-contained.
//
//  Run with:  java LocalMultiNodeTest
//
//  Nodes: MacBook, Pi1, Pi2, Pi3
//
//  Scenario 1 – One node creates a transaction and broadcasts to the other 3
//  Scenario 2 – Multiple transactions from different nodes
//  Scenario 3 – All 4 nodes mine simultaneously (shows which finishes first)
//  Scenario 4 – One node mines a block; the other 3 validate and accept it
//  Scenario 5 – Verify all 4 nodes have identical account balances
// =============================================================================

public class LocalMultiNodeTest {

    // =========================================================================
    //  SimulatedNode
    //  Wraps a Blockchain and provides in-process broadcast helpers.
    //  (Network I/O is replaced by direct method calls on other nodes.)
    // =========================================================================
    static class SimulatedNode {
        final String nodeId;
        Blockchain   blockchain;

        SimulatedNode(String nodeId) {
            this.nodeId     = nodeId;
            this.blockchain = new Blockchain();
        }

        // --- Transaction API --------------------------------------------------

        /**
         * Add a transaction locally and broadcast it to every other node.
         * Returns true if the local blockchain accepted the transaction.
         */
        boolean addTransaction(String sender, String receiver, double amount) {
            Blockchain.Transaction tx =
                    new Blockchain.Transaction(sender, receiver, amount);
            boolean ok = blockchain.addTransaction(tx);
            if (ok) broadcastTransaction(tx);
            return ok;
        }

        /**
         * Push a transaction directly into another node's pending pool.
         * (Simulates the TRANSACTION network message.)
         */
        private void broadcastTransaction(Blockchain.Transaction tx) {
            for (SimulatedNode other : allNodes()) {
                if (!other.nodeId.equals(this.nodeId)) {
                    boolean ok = other.blockchain.addTransaction(tx);
                    System.out.printf("  [%s -> %s] TX: %s  %s%n",
                            nodeId, other.nodeId, tx,
                            ok ? "[ACCEPTED]" : "[REJECTED]");
                }
            }
        }

        // --- Mining API -------------------------------------------------------

        /**
         * Mine all pending transactions into a new block and broadcast it.
         * The block is NOT re-broadcast to this node.
         */
        Blockchain.Block mine() {
            Blockchain.Block block = blockchain.minePendingTransactions(nodeId);
            if (block != null) broadcastBlock(block);
            return block;
        }

        /**
         * Push a mined block to every other node for validation.
         * (Simulates the BLOCK network message.)
         */
        void broadcastBlock(Blockchain.Block block) {
            for (SimulatedNode other : allNodes()) {
                if (!other.nodeId.equals(this.nodeId)) {
                    boolean ok = other.blockchain.addMinedBlock(block);
                    System.out.printf("  [%s -> %s] Block #%d  %s%n",
                            nodeId, other.nodeId, block.getIndex(),
                            ok ? "[ACCEPTED]" : "[REJECTED]");
                }
            }
        }

        // --- Helpers ----------------------------------------------------------

        /** Reset this node's blockchain to a fresh state. */
        void reset() {
            this.blockchain = new Blockchain();
        }

        /** Print a one-line status summary. */
        void printStatus() {
            System.out.printf("  %-10s  chain=%d  pending=%d  valid=%s%n",
                    nodeId,
                    blockchain.getLength(),
                    blockchain.getPendingTransactions().size(),
                    blockchain.isChainValid() ? "YES" : "NO");
        }
    }

    // =========================================================================
    //  Network registry
    //  All SimulatedNodes are stored here so broadcasts can reach them.
    // =========================================================================
    private static final List<SimulatedNode>          nodes   = new ArrayList<>();
    private static final Map<String, SimulatedNode>   byId    = new LinkedHashMap<>();

    private static Collection<SimulatedNode> allNodes() { return nodes; }

    // =========================================================================
    //  main
    // =========================================================================
    public static void main(String[] args) throws InterruptedException {
        printHeader();

        // Create 4 nodes and register them.
        for (String id : new String[]{"MacBook", "Pi1", "Pi2", "Pi3"}) {
            SimulatedNode n = new SimulatedNode(id);
            nodes.add(n);
            byId.put(id, n);
        }

        System.out.println("4 nodes initialised: MacBook, Pi1, Pi2, Pi3");
        System.out.println("Genesis balances: MacBook=1000  Pi1=500  Pi2=500  Pi3=500\n");

        scenario1();
        scenario2();
        scenario3();
        scenario4();
        scenario5();

        System.out.println();
        System.out.println("========================================");
        System.out.println("  All 5 scenarios complete.");
        System.out.println("========================================");
    }

    // =========================================================================
    //  SCENARIO 1
    //  MacBook creates one transaction and broadcasts it to the other 3 nodes.
    // =========================================================================
    private static void scenario1() {
        scenarioHeader(1, "One node broadcasts a transaction to the other 3");

        SimulatedNode macBook = byId.get("MacBook");

        System.out.println("MacBook is sending 100 coins to Pi1...\n");
        macBook.addTransaction("MacBook", "Pi1", 100.0);

        System.out.println("\nPending transaction pool sizes after broadcast:");
        for (SimulatedNode n : nodes) {
            System.out.printf("  %-10s : %d pending tx(s)%n",
                    n.nodeId, n.blockchain.getPendingTransactions().size());
        }

        System.out.println("\nExpected: all 4 nodes have 1 pending transaction.");
        scenarioFooter(1);
    }

    // =========================================================================
    //  SCENARIO 2
    //  Three more transactions originate from different nodes.
    // =========================================================================
    private static void scenario2() {
        scenarioHeader(2, "Multiple transactions from different nodes");

        System.out.println("Pi1 sending 50 coins to Pi2...");
        byId.get("Pi1").addTransaction("Pi1", "Pi2", 50.0);

        System.out.println("\nPi2 sending 30 coins to Pi3...");
        byId.get("Pi2").addTransaction("Pi2", "Pi3", 30.0);

        System.out.println("\nPi3 sending 20 coins to MacBook...");
        byId.get("Pi3").addTransaction("Pi3", "MacBook", 20.0);

        System.out.println("\nPending pool sizes (each node should see all 4 transactions):");
        for (SimulatedNode n : nodes) {
            System.out.printf("  %-10s : %d pending tx(s)%n",
                    n.nodeId, n.blockchain.getPendingTransactions().size());
        }

        scenarioFooter(2);
    }

    // =========================================================================
    //  SCENARIO 3
    //  All 4 nodes mine blocks simultaneously using threads.
    //  Shows which finishes first and the time for each.
    // =========================================================================
    private static void scenario3() throws InterruptedException {
        scenarioHeader(3, "All 4 nodes mine simultaneously");

        System.out.println("Each node mines its own copy of the pending transaction pool.");
        System.out.println("In a real P2P network the fastest miner's block wins;");
        System.out.println("the others become orphaned and are discarded.\n");

        // Each node has 4 pending transactions from Scenarios 1 & 2.
        // They mine independently — no cross-broadcasts here — to show timing.
        ExecutorService executor  = Executors.newFixedThreadPool(4);
        AtomicInteger   finishPos = new AtomicInteger(0);

        // Use a CountDownLatch so all threads start as close together as possible.
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (SimulatedNode node : nodes) {
            futures.add(executor.submit(() -> {
                try {
                    startGun.await();   // wait for all threads to be ready
                } catch (InterruptedException ignored) { }

                long t0 = System.currentTimeMillis();
                // Mine without broadcasting (each node mines its own chain here).
                node.blockchain.minePendingTransactions(node.nodeId);
                long elapsed = System.currentTimeMillis() - t0;

                int position = finishPos.incrementAndGet();
                System.out.printf("  #%d  %-10s  finished in %.2f seconds%n",
                        position, node.nodeId, elapsed / 1000.0);
            }));
        }

        startGun.countDown();   // release all threads at once
        executor.shutdown();
        executor.awaitTermination(300, TimeUnit.SECONDS);

        System.out.println("\nAll 4 nodes have now mined block #1 locally.");
        System.out.println("Their chains are NOT yet in agreement (each has a different block #1).");
        System.out.println("Scenario 4 demonstrates how consensus is restored.\n");
        scenarioFooter(3);
    }

    // =========================================================================
    //  SCENARIO 4
    //  Reset all nodes, then have MacBook mine ONE block and broadcast it.
    //  The other 3 validate and accept it — this is the consensus mechanism.
    // =========================================================================
    private static void scenario4() {
        scenarioHeader(4, "Consensus: one node mines, the other 3 accept");

        // Reset every node to a clean blockchain so we start from genesis.
        System.out.println("Resetting all 4 nodes to the same genesis state...");
        for (SimulatedNode n : nodes) n.reset();
        System.out.println("Done.\n");

        SimulatedNode macBook = byId.get("MacBook");

        // Add a transaction to MacBook's pool only.
        System.out.println("MacBook queuing transaction: MacBook -> Pi1  50 coins");
        macBook.blockchain.addTransaction(
                new Blockchain.Transaction("MacBook", "Pi1", 50.0));

        System.out.println("\nNode status BEFORE mining:");
        for (SimulatedNode n : nodes) n.printStatus();

        System.out.println("\nMacBook is mining...");
        Blockchain.Block minedBlock = macBook.mine();   // mines + broadcasts

        System.out.println("\nNode status AFTER mining and broadcast:");
        for (SimulatedNode n : nodes) n.printStatus();

        // Verify consensus: all nodes should have the same latest block hash.
        String expectedHash = macBook.blockchain.getLatestBlock().getHash();
        boolean allAgree = nodes.stream().allMatch(
                n -> n.blockchain.getLatestBlock().getHash().equals(expectedHash));

        System.out.println("\nAll nodes have identical latest block hash: "
                + (allAgree ? "YES" : "NO"));
        System.out.println("Latest hash: " + expectedHash);

        scenarioFooter(4);
    }

    // =========================================================================
    //  SCENARIO 5
    //  After consensus (Scenario 4), compare account balances across all nodes.
    //  They must be identical.
    // =========================================================================
    private static void scenario5() {
        scenarioHeader(5, "Verify all 4 nodes agree on account balances");

        // Collect every address that appears in any node's ledger.
        Set<String> addresses = new TreeSet<>();
        for (SimulatedNode n : nodes) {
            addresses.addAll(n.blockchain.getAllBalances().keySet());
        }

        // Table header
        System.out.printf("%-12s", "Account");
        for (SimulatedNode n : nodes) System.out.printf("  %-12s", n.nodeId);
        System.out.println("  Agrees?");
        System.out.println("-".repeat(12 + nodes.size() * 14 + 10));

        boolean overallMatch = true;

        for (String addr : addresses) {
            System.out.printf("%-12s", addr);
            double first     = -1.0;
            boolean rowMatch = true;

            for (SimulatedNode n : nodes) {
                double bal = n.blockchain.getBalance(addr);
                System.out.printf("  %-12.2f", bal);
                if (first < 0) {
                    first = bal;
                } else if (Math.abs(bal - first) > 0.001) {
                    rowMatch = false;
                }
            }

            System.out.println("  " + (rowMatch ? "YES" : "NO <<<"));
            if (!rowMatch) overallMatch = false;
        }

        System.out.println("-".repeat(12 + nodes.size() * 14 + 10));
        System.out.println("\nFinal verdict — all 4 nodes agree on all balances: "
                + (overallMatch ? "YES" : "NO"));

        if (overallMatch) {
            System.out.println("\nConsensus achieved.  The blockchain is consistent across the network.");
        } else {
            System.out.println("\nWARNING: Nodes are out of sync!");
        }

        scenarioFooter(5);
    }

    // =========================================================================
    //  Display helpers
    // =========================================================================

    private static void printHeader() {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║         LOCAL MULTI-NODE BLOCKCHAIN SIMULATOR        ║");
        System.out.println("║         Nodes: MacBook  Pi1  Pi2  Pi3               ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static void scenarioHeader(int num, String desc) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.printf ("║  SCENARIO %d: %-41s ║%n", num, desc);
        System.out.println("╚══════════════════════════════════════════════════════╝");
    }

    private static void scenarioFooter(int num) {
        System.out.println("\n[ Scenario " + num + " complete ]");
        System.out.println("------------------------------------------------------");
    }
}
