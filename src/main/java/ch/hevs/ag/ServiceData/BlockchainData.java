package ch.hevs.ag.ServiceData;

import ch.hevs.ag.Model.Block;
import ch.hevs.ag.Model.Transaction;
import ch.hevs.ag.Model.Wallet;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;

public class BlockchainData {
    private ObservableList<Transaction> newBlockTransactionsFX;
    private ObservableList<Transaction> newBlockTransactions;
    private LinkedList<Block> currentBlockChain = new LinkedList<>();
    private Block latestBlock;
    private boolean exit = false;
    private int miningPoints;
    private static final int TIMEOUT_INTERVAL = 65;
    private static final int MINING_INTERVAL = 60;
    //helper class.
    private Signature signing = Signature.getInstance("SHA256withRSA");

    //singleton class
    private static BlockchainData instance; // always the same object is getting coded and not a new one

    static {
        try {
            instance = new BlockchainData(); // if no one => create only once the object
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    public BlockchainData() throws NoSuchAlgorithmException {
        newBlockTransactions = FXCollections.observableArrayList();
        newBlockTransactionsFX = FXCollections.observableArrayList();
    }

    public static BlockchainData getInstance() {
        return instance;
    }

    //compare if the transaction is correct regarding the one already existing
    Comparator<Transaction> transactionComparator = Comparator.comparing(Transaction::getTimestamp);
    //print the transaction into the UI
    public ObservableList<Transaction> getTransactionLedgerFX() {
        newBlockTransactionsFX.clear();
        newBlockTransactions.sort(transactionComparator);
        newBlockTransactionsFX.addAll(newBlockTransactions);
        return FXCollections.observableArrayList(newBlockTransactionsFX);
    }

    //return the amount of money that the user have in his wallet
    public String getWalletBallanceFX() {
        return getBalance(currentBlockChain, newBlockTransactions,
                WalletData.getInstance().getWallet().getPublicKey()).toString();
    }

    /**
     * return the balance using the following blockchain, the ledger and the public key of the user
     * Go check all the blocks in the blockchain ig the user has any transaction
     */
    private Integer getBalance(LinkedList<Block> blockChain,
                               ObservableList<Transaction> currentLedger, PublicKey walletAddress) {
        Integer balance = 0;
        for (Block block : blockChain) {
            for (Transaction transaction : block.getTransactionLedger()) {
                if (Arrays.equals(transaction.getFrom(), walletAddress.getEncoded())) {
                    balance -= transaction.getValue();
                }
                if (Arrays.equals(transaction.getTo(), walletAddress.getEncoded())) {
                    balance += transaction.getValue();
                }
            }
        }
        for (Transaction transaction : currentLedger) {
            if (Arrays.equals(transaction.getFrom(), walletAddress.getEncoded())) {
                balance -= transaction.getValue();
            }
        }
        return balance;
    }

    /**
     * Verify if the blockchain is correct
     * @param currentBlockChain
     * @throws GeneralSecurityException
     */
    private void verifyBlockChain(LinkedList<Block> currentBlockChain) throws GeneralSecurityException {
        for (Block block : currentBlockChain) {
            if (!block.isVerified(signing)) {//the problem is here 2
                throw new GeneralSecurityException("Block validation failed");
            }
            ArrayList<Transaction> transactions = block.getTransactionLedger();
            for (Transaction transaction : transactions) {//the problem is here 3
                if (!transaction.isVerified(signing)) {
                    throw new GeneralSecurityException("Transaction validation failed");
                }
            }
        }
    }
    public void addTransactionState(Transaction transaction) {
        newBlockTransactions.add(transaction);
        newBlockTransactions.sort(transactionComparator);
    }

    /**
     * Create a new transaction
     * Will check if the user has enough balance in his wallet
     * Will connect to the DB and create the transaction
     * @param transaction
     * @param blockReward
     * @throws GeneralSecurityException
     */
    public void addTransaction(Transaction transaction, boolean blockReward) throws GeneralSecurityException, SQLException {
        try {
            PublicKey publicKey = null;
            try {
                X509EncodedKeySpec keySpec = new X509EncodedKeySpec(transaction.getFrom());
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                publicKey = (keyFactory.generatePublic(keySpec));
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            } catch (InvalidKeySpecException e) {
                throw new InvalidKeySpecException(e.getMessage(), e);
            }
            if (getBalance(currentBlockChain, newBlockTransactions, publicKey) < transaction.getValue() && !blockReward) {
                throw new GeneralSecurityException("Not enough funds");
            } else {
                Connection connection = DriverManager.getConnection
                        ("jdbc:sqlite:DB\\BlockChain.sqlite");

                PreparedStatement pstmt;
                pstmt = connection.prepareStatement("INSERT INTO TRANSACTIONS" +
                        "(\"FROM\", \"TO\", LEDGER_ID, VALUE, SIGNATURE, CREATED_ON) " +
                        " VALUES (?,?,?,?,?,?) ");
                pstmt.setBytes(1, transaction.getFrom());
                pstmt.setBytes(2, transaction.getTo());
                pstmt.setInt(3, transaction.getLedgerId());
                pstmt.setInt(4, transaction.getValue());
                pstmt.setBytes(5, transaction.getSignature());
                pstmt.setString(6, transaction.getTimestamp());
                pstmt.executeUpdate();

                pstmt.close();
                connection.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Load all the blockchain and update the variable currentBlockChain and latestBlock
     *
     */
    public void loadBlockChain() {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:DB\\BlockChain.sqlite");
            Statement stmt = connection.createStatement();
            ResultSet resultSet = stmt.executeQuery(" SELECT * FROM BLOCKCHAIN ");
            while (resultSet.next()) {
                this.currentBlockChain.add(new Block(
                        resultSet.getBytes("PREVIOUS_HASH"),
                        resultSet.getBytes("CURRENT_HASH"),
                        resultSet.getString("CREATED_ON"),
                        resultSet.getBytes("CREATED_BY"),
                        resultSet.getInt("LEDGER_ID"),
                        resultSet.getInt("MINING_POINTS"),
                        resultSet.getDouble("LUCK"),
                        loadTransactionLedger(resultSet.getInt("LEDGER_ID"))
                ));
            }

            //to replace
            latestBlock = currentBlockChain.getLast();
            Transaction transaction = new Transaction(new Wallet(),
                    WalletData.getInstance().getWallet().getPublicKey().getEncoded(), 100, latestBlock.getLedgerId() + 1, signing);
            newBlockTransactions.clear();
            newBlockTransactions.add(transaction);

            //problem here
            verifyBlockChain(currentBlockChain);
            resultSet.close();
            stmt.close();
            connection.close();
        } catch (SQLException | NoSuchAlgorithmException e) {
            System.out.println("Problem with DB: " + e.getMessage());
            e.printStackTrace();
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
        }
    }

    /**
     * Will load all the trasaction regarding the ledgerID
     * @param ledgerID to choose which ledger is to verify
     * @return
     * @throws SQLException
     */
    private ArrayList<Transaction> loadTransactionLedger(Integer ledgerID) throws SQLException {
        ArrayList<Transaction> transactions = new ArrayList<>();
        try {
            Connection connection = DriverManager.getConnection
                    ("jdbc:sqlite:DB\\BlockChain.sqlite");
            PreparedStatement stmt = connection.prepareStatement
                    (" SELECT  * FROM TRANSACTIONS WHERE LEDGER_ID = ?");
            stmt.setInt(1, ledgerID);
            ResultSet resultSet = stmt.executeQuery();
            while (resultSet.next()) {
                transactions.add(new Transaction(
                        resultSet.getBytes("FROM"),
                        resultSet.getBytes("TO"),
                        resultSet.getInt("VALUE"),
                        resultSet.getBytes("SIGNATURE"),
                        resultSet.getInt("LEDGER_ID"),
                        resultSet.getString("CREATED_ON")
                ));
            }
            resultSet.close();
            stmt.close();
            connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return transactions;
    }

    /**
     *
     */
    public void mineBlock() {
        try {
            finalizeBlock(WalletData.getInstance().getWallet());
            addBlock(latestBlock);
        } catch (SQLException | GeneralSecurityException e) {
            System.out.println("Problem with DB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // add a block to the last block that we have
    private void finalizeBlock(Wallet minersWallet) throws GeneralSecurityException, SQLException {
        latestBlock = new Block(BlockchainData.getInstance().currentBlockChain);
        latestBlock.setTransactionLedger(new ArrayList<>(newBlockTransactions));
        latestBlock.setTimeStamp(LocalDateTime.now().toString());
        latestBlock.setMinedBy(minersWallet.getPublicKey().getEncoded());
        latestBlock.setMiningPoints(miningPoints);
        signing.initSign(minersWallet.getPrivateKey());
        signing.update(latestBlock.toString().getBytes());
        latestBlock.setCurrHash(signing.sign());
        currentBlockChain.add(latestBlock);
        miningPoints = 0;
        //Reward transaction
        latestBlock.getTransactionLedger().sort(transactionComparator);
        addTransaction(latestBlock.getTransactionLedger().get(0), true);
        Transaction transaction = new Transaction(new Wallet(), minersWallet.getPublicKey().getEncoded(),
                100, latestBlock.getLedgerId() + 1, signing);
        newBlockTransactions.clear();
        newBlockTransactions.add(transaction);
    }

    /**
     * Create a block in the blockchain
     * @param block Take the parameters of the Block
     */
    private void addBlock(Block block) {
        try {
            Connection connection = DriverManager.getConnection
                    ("jdbc:sqlite:DB\\BlockChain.sqlite");
            PreparedStatement pstmt;
            pstmt = connection.prepareStatement
                    ("INSERT INTO BLOCKCHAIN(PREVIOUS_HASH, CURRENT_HASH, LEDGER_ID, CREATED_ON," +
                            " CREATED_BY, MINING_POINTS, LUCK) VALUES (?,?,?,?,?,?,?) ");
            pstmt.setBytes(1, block.getPrevHash());
            pstmt.setBytes(2, block.getCurrHash());
            pstmt.setInt(3, block.getLedgerId());
            pstmt.setString(4, block.getTimeStamp());
            pstmt.setBytes(5, block.getMinedBy());
            pstmt.setInt(6, block.getMiningPoints());
            pstmt.setDouble(7, block.getLuck());
            pstmt.executeUpdate();
            pstmt.close();
            connection.close();
        } catch (SQLException e) {
            System.out.println("Problem with DB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Will delete the current DB and update with the new DB
     * @param receivedBC This will be the new DB
     */
    private void replaceBlockchainInDatabase(LinkedList<Block> receivedBC) {
        try {
            Connection connection = DriverManager.getConnection
                    ("jdbc:sqlite:DB\\BlockChain.sqlite");
            Statement clearDBStatement = connection.createStatement();
            clearDBStatement.executeUpdate(" DELETE FROM BLOCKCHAIN ");
            clearDBStatement.executeUpdate(" DELETE FROM TRANSACTIONS ");
            clearDBStatement.close();
            connection.close();
            for (Block block : receivedBC) {
                addBlock(block);
                boolean rewardTransaction = true;
                block.getTransactionLedger().sort(transactionComparator);
                for (Transaction transaction : block.getTransactionLedger()) {
                    addTransaction(transaction, rewardTransaction);
                    rewardTransaction = false;
                }
            }
        } catch (SQLException | GeneralSecurityException e) {
            System.out.println("Problem with DB: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     *
     * @param receivedBC
     * @return
     */
    public LinkedList<Block> getBlockchainConsensus(LinkedList<Block> receivedBC) {
        try {
            //Verify the validity of the received blockchain.
            verifyBlockChain(receivedBC);
            //Check if we have received an identical blockchain.
            if (!Arrays.equals(receivedBC.getLast().getCurrHash(), getCurrentBlockChain().getLast().getCurrHash())) {
                if (checkIfOutdated(receivedBC) != null) {
                    return getCurrentBlockChain();
                } else {
                    if (checkWhichIsCreatedFirst(receivedBC) != null) {
                        return getCurrentBlockChain();
                    } else {
                        if (compareMiningPointsAndLuck(receivedBC) != null) {
                            return getCurrentBlockChain();
                        }
                    }
                }
                // if only the transaction ledgers are different then combine them.
            } else if (!receivedBC.getLast().getTransactionLedger().equals(getCurrentBlockChain()
                    .getLast().getTransactionLedger())) {
                updateTransactionLedgers(receivedBC);
                System.out.println("Transaction ledgers updated");
                return receivedBC;
            } else {
                System.out.println("blockchains are identical");
            }
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return receivedBC;
    }

    /**
     * Will update the last transaction and add it into the blockchain
     * @param receivedBC The DB updated with all the transactions
     * @throws GeneralSecurityException
     */
    private void updateTransactionLedgers(LinkedList<Block> receivedBC) throws GeneralSecurityException, SQLException {
        for (Transaction transaction : receivedBC.getLast().getTransactionLedger()) {
            if (!getCurrentBlockChain().getLast().getTransactionLedger().contains(transaction) ) {
                getCurrentBlockChain().getLast().getTransactionLedger().add(transaction);
                System.out.println("current ledger id = " + getCurrentBlockChain().getLast().getLedgerId() + " transaction id = " + transaction.getLedgerId());
                addTransaction(transaction, false);
            }
        }
        getCurrentBlockChain().getLast().getTransactionLedger().sort(transactionComparator);
        for (Transaction transaction : getCurrentBlockChain().getLast().getTransactionLedger()) {
            if (!receivedBC.getLast().getTransactionLedger().contains(transaction) ) {
                receivedBC.getLast().getTransactionLedger().add(transaction);
            }
        }
        receivedBC.getLast().getTransactionLedger().sort(transactionComparator);
    }

    private LinkedList<Block> checkIfOutdated(LinkedList<Block> receivedBC) {
        //Check how old the blockchains are.
        long lastMinedLocalBlock = LocalDateTime.parse
                (getCurrentBlockChain().getLast().getTimeStamp()).toEpochSecond(ZoneOffset.UTC);
        long lastMinedRcvdBlock = LocalDateTime.parse
                (receivedBC.getLast().getTimeStamp()).toEpochSecond(ZoneOffset.UTC);
        //if both are old just do nothing
        if ((lastMinedLocalBlock + TIMEOUT_INTERVAL) < LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) &&
                (lastMinedRcvdBlock + TIMEOUT_INTERVAL) < LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)) {
            System.out.println("both are old check other peers");
            //If your blockchain is old but the received one is new use the received one
        } else if ((lastMinedLocalBlock + TIMEOUT_INTERVAL) < LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) &&
                (lastMinedRcvdBlock + TIMEOUT_INTERVAL) >= LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)) {
            //we reset the mining points since we weren't contributing until now.
            setMiningPoints(0);
            replaceBlockchainInDatabase(receivedBC);
            setCurrentBlockChain(new LinkedList<>());
            loadBlockChain();
            System.out.println("received blockchain won!, local BC was old");
            //If received one is old but local is new send ours to them
        } else if ((lastMinedLocalBlock + TIMEOUT_INTERVAL) > LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) &&
                (lastMinedRcvdBlock + TIMEOUT_INTERVAL) < LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)) {

            return getCurrentBlockChain();
        }
        return null;
    }

    private LinkedList<Block> checkWhichIsCreatedFirst(LinkedList<Block> receivedBC) {
        //Compare timestamps to see which one is created first.
        long initRcvBlockTime = LocalDateTime.parse(receivedBC.getFirst().getTimeStamp())
                .toEpochSecond(ZoneOffset.UTC);
        long initLocalBlockTIme = LocalDateTime.parse(getCurrentBlockChain().getFirst()
                .getTimeStamp()).toEpochSecond(ZoneOffset.UTC);
        if (initRcvBlockTime < initLocalBlockTIme) {
            //we reset the mining points since we weren't contributing until now.
            setMiningPoints(0);
            replaceBlockchainInDatabase(receivedBC);
            setCurrentBlockChain(new LinkedList<>());
            loadBlockChain();
            System.out.println("PeerClient blockchain won!, PeerServer's BC was old");
        } else if (initLocalBlockTIme < initRcvBlockTime) {
            return getCurrentBlockChain();
        }
        return null;
    }

    private LinkedList<Block> compareMiningPointsAndLuck(LinkedList<Block> receivedBC)
            throws GeneralSecurityException, SQLException {
        //check if both blockchains have the same prevHashes to confirm they are both
        //contending to mine the last block
        //if they are the same compare the mining points and luck in case of equal mining points
        //of last block to see who wins
        if (receivedBC.equals(getCurrentBlockChain())) {
            //If received block has more mining points points or luck in case of tie
            // transfer all transactions to the winning block and add them in DB.
            if (receivedBC.getLast().getMiningPoints() > getCurrentBlockChain()
                    .getLast().getMiningPoints() || receivedBC.getLast().getMiningPoints()
                    .equals(getCurrentBlockChain().getLast().getMiningPoints()) &&
                    receivedBC.getLast().getLuck() > getCurrentBlockChain().getLast().getLuck()) {
                //remove the reward transaction from our losing block and
                // transfer the transactions to the winning block
                getCurrentBlockChain().getLast().getTransactionLedger().remove(0);
                for (Transaction transaction : getCurrentBlockChain().getLast().getTransactionLedger()) {
                    if (!receivedBC.getLast().getTransactionLedger().contains(transaction)) {
                        receivedBC.getLast().getTransactionLedger().add(transaction);
                    }
                }
                receivedBC.getLast().getTransactionLedger().sort(transactionComparator);
                //we are returning the mining points since our local block lost.
                setMiningPoints(BlockchainData.getInstance().getMiningPoints() +
                        getCurrentBlockChain().getLast().getMiningPoints());
                replaceBlockchainInDatabase(receivedBC);
                setCurrentBlockChain(new LinkedList<>());
                loadBlockChain();
                System.out.println("Received blockchain won!");
            } else {
                // remove the reward transaction from their losing block and transfer
                // the transactions to our winning block
                receivedBC.getLast().getTransactionLedger().remove(0);
                for (Transaction transaction : receivedBC.getLast().getTransactionLedger()) {
                    if (!getCurrentBlockChain().getLast().getTransactionLedger().contains(transaction)) {
                        getCurrentBlockChain().getLast().getTransactionLedger().add(transaction);
                        addTransaction(transaction, false);
                    }
                }
                getCurrentBlockChain().getLast().getTransactionLedger().sort(transactionComparator);
                return getCurrentBlockChain();
            }
        }
        return null;
    }

    public LinkedList<Block> getCurrentBlockChain() {
        return currentBlockChain;
    }

    public void setCurrentBlockChain(LinkedList<Block> currentBlockChain) { // have data private to not allow others to change anything
        this.currentBlockChain = currentBlockChain;
    }

    public static int getTimeoutInterval() { return TIMEOUT_INTERVAL; }

    public static int getMiningInterval() { return MINING_INTERVAL; }

    public int getMiningPoints() {
        return miningPoints;
    }

    public void setMiningPoints(int miningPoints) {
        this.miningPoints = miningPoints;
    }

    public boolean isExit() {
        return exit;
    }

    public void setExit(boolean exit) {
        this.exit = exit;
    }
}
