package ch.hevs.ag;

import ch.hevs.ag.Model.Block;
import ch.hevs.ag.Model.Transaction;
import ch.hevs.ag.Model.Wallet;
import ch.hevs.ag.ServiceData.BlockchainData;
import ch.hevs.ag.ServiceData.WalletData;
import ch.hevs.ag.Threads.MiningThread;
import ch.hevs.ag.Threads.PeerClient;
import ch.hevs.ag.Threads.PeerServer;
import ch.hevs.ag.Threads.UI;


import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.sql.*;
import java.time.LocalDateTime;

public class HelloApplication extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        new UI().start(stage);
        new PeerClient().start();
        new PeerServer(12351).start();
        new MiningThread().start();
    }

    @Override
    public void init()
    {
        try {
            //This creates your wallet if there is none and gives you a KeyPair.
            //We will create it in separate db for better security and ease of portability.
            Connection walletConnection = DriverManager
                    .getConnection("jdbc:sqlite:DB\\Wallet.sqlite");
            Statement walletStatment = walletConnection.createStatement();

            walletStatment.executeUpdate("CREATE TABLE IF NOT EXISTS WALLET (PRIVATE_KEY BLOB NOT NULL UNIQUE, PUBLIC_KEY BLOB NOT NULL UNIQUE, PRIMARY KEY (PRIVATE_KEY, PUBLIC_KEY))");

            ResultSet resultSet = walletStatment.executeQuery(" SELECT * FROM WALLET ");
            if (!resultSet.next()) {
                Wallet newWallet = new Wallet();
                byte[] pubBlob = newWallet.getPublicKey().getEncoded();
                byte[] prvBlob = newWallet.getPrivateKey().getEncoded();

                PreparedStatement pstmt = walletConnection
                        .prepareStatement("INSERT INTO WALLET(PRIVATE_KEY, PUBLIC_KEY) " +
                                " VALUES (?,?) ");
                pstmt.setBytes(1, prvBlob);
                pstmt.setBytes(2, pubBlob);
                pstmt.executeUpdate();
            }
            resultSet.close();
            walletStatment.close();
            walletConnection.close();

            WalletData.getInstance().loadWallet();


            Connection blockchainConnection = DriverManager
                    .getConnection("jdbc:sqlite:DB\\BlockChain.sqlite");
            Statement blockchainStmt = blockchainConnection.createStatement();

            blockchainStmt.executeUpdate("CREATE TABLE IF NOT EXISTS BLOCKCHAIN("+
                    " ID INTEGER NOT NULL UNIQUE , " +
                    " PREVIOUS_HASH BLOB UNIQUE, "+
                    " CURRENT_HASH BLOB UNIQUE, " +
                    " LEDGER_ID INTEGER NOT NULL UNIQUE, " +
                    " CREATED_ON TEXT, " +
                    " CREATED_BY BLOB, " +
                    " MINING_POINTS TEXT, " +
                    " LUCK NUMERIC, " +
                    " PRIMARY KEY(ID AUTOINCREMENT))");

            ResultSet resultSetBlockchain = blockchainStmt.executeQuery(" SELECT * FROM BLOCKCHAIN ");
            Transaction initBlockRewardTransaction = null;
            if(!resultSetBlockchain.next()) {
                Block firstBlock = new Block();
                firstBlock.setMinedBy(WalletData.getInstance().getWallet().getPublicKey().getEncoded());
                firstBlock.setTimeStamp(LocalDateTime.now().toString());
                firstBlock.setLuck(Math.random() * 1000000);
                //Helper class
                Signature signing = Signature.getInstance("SHA256withRSA");
                signing.initSign(WalletData.getInstance().getWallet().getPrivateKey());
                signing.update(firstBlock.toString().getBytes());
                firstBlock.setCurrHash(signing.sign());
                PreparedStatement pstmt = blockchainConnection.prepareStatement("INSERT INTO BLOCKCHAIN (PREVIOUS_HASH, CURRENT_HASH, LEDGER_ID," +
                        " CREATED_ON, CREATED_BY, MINING_POINTS, LUCK)VALUES (?,?,?,?,?,?,?)");

                pstmt.setBytes(1, firstBlock.getPrevHash());
                pstmt.setBytes(2, firstBlock.getCurrHash());
                pstmt.setInt(3, firstBlock.getLedgerId());
                pstmt.setString(4, firstBlock.getTimeStamp());
                pstmt.setBytes(5, WalletData.getInstance().getWallet().getPublicKey().getEncoded());
                pstmt.setInt(6, firstBlock.getMiningPoints());
                pstmt.setDouble(7, firstBlock.getLuck());
                pstmt.executeUpdate();

                Signature transSignature = Signature.getInstance("SHA256withRSA");
                initBlockRewardTransaction = new Transaction(WalletData.getInstance().getWallet(), WalletData.getInstance().getWallet().getPublicKey().getEncoded(), 100, 1, transSignature);
                resultSetBlockchain.close();
            }
            blockchainStmt.executeUpdate("CREATE TABLE IF NOT EXISTS TRANSACTIONS( " +
                    "ID INTEGER NOT NULL UNIQUE, " +
                    "\"FROM\" BLOB, " +
                    "\"TO\" BLOB, "+
                    "LEDGER_ID INTEGER, "+
                    "`VALUE` INTEGER, " +
                    "SIGNATURE BLOB UNIQUE, "+
                    "CREATED_ON TEXT, " +
                    "PRIMARY KEY(ID AUTOINCREMENT))");
            if(initBlockRewardTransaction !=null)
            {
                BlockchainData.getInstance().addTransaction(initBlockRewardTransaction, true);
                BlockchainData.getInstance().addTransactionState(initBlockRewardTransaction);
            }
            blockchainStmt.close();
            blockchainConnection.close();

        } catch (SQLException | GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
        BlockchainData.getInstance().loadBlockChain();
    }
}