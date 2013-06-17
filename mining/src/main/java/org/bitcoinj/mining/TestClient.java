package org.bitcoinj.mining;

import java.io.File;
import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.bitcoin.core.AbstractPeerEventListener;
import com.google.bitcoin.core.Block;
import com.google.bitcoin.core.DownloadListener;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.FullPrunedBlockChain;
import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.Peer;
import com.google.bitcoin.core.PeerAddress;
import com.google.bitcoin.core.PeerGroup;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.UpdateDifficultyMessage;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.store.FullPrunedBlockStore;
import com.google.bitcoin.store.H2FullPrunedBlockStore;

public class TestClient {

    final static Logger log = LoggerFactory.getLogger(TestClient.class);

    private NetworkParameters params;

    private final FullPrunedBlockStore store;

    private final FullPrunedBlockChain chain;

    private final Wallet wallet;

    private final PeerGroup peerGroup;

    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        new TestClient();
    }

    public TestClient() throws Exception {
        // params = NetworkParametersMulticoin.multiNet();
        params = NetworkParameters.testNet3();

        // store = new H2FullPrunedBlockStore(params, "~/miner-chain",
        // Integer.MAX_VALUE);
        store = new H2FullPrunedBlockStore(params, "~/multinet", Integer.MAX_VALUE);
        chain = new FullPrunedBlockChain(params, store);

        // Try to read the wallet from storage, create a new one if not
        // possible.
        final File walletFile = new File("multinet.wallet");
        if (walletFile.exists()) {
            wallet = Wallet.loadFromFile(walletFile);
        } else {
            wallet = new Wallet(params);
            wallet.addKey(new ECKey());
            wallet.saveToFile(walletFile);
        }

        peerGroup = new PeerGroup(params, chain);
        peerGroup.setUserAgent("Miner", "1.0");
        peerGroup.addWallet(wallet);
        peerGroup.addAddress(new PeerAddress(new InetSocketAddress("localhost", 9001)));
        peerGroup.addAddress(new PeerAddress(new InetSocketAddress("localhost", 9002)));
        peerGroup.addAddress(new PeerAddress(new InetSocketAddress("localhost", 9003)));

        peerGroup.startAndWait();
        peerGroup.startBlockChainDownload(new DownloadListener() {
            @Override
            protected void doneDownload() {
                super.doneDownload();
            }
        });
        // peerGroup.downloadBlockChain(); // synchronous

        peerGroup.addEventListener(new AbstractPeerEventListener() {
            @Override
            public void onPeerConnected(Peer peer, int peerCount) {
                super.onPeerConnected(peer, peerCount);
                log.info("Peer Connected: {}", peer.getAddress().getAddr());
            }

            @Override
            public void onPeerDisconnected(Peer peer, int peerCount) {
                super.onPeerDisconnected(peer, peerCount);
                log.info("Peer Disconnected: {}", peer.getAddress().getAddr());
            }

            @Override
            public void onTransaction(Peer peer, Transaction t) {
                super.onTransaction(peer, t);
                log.info("Transaction: {}", t);
            }

            /**
             * This is called once the block was successfully verified and
             * linked to our version of block chain.
             */
            @Override
            public void onBlocksDownloaded(Peer peer, Block block, int blocksLeft) {
                super.onBlocksDownloaded(peer, block, blocksLeft);
                log.info("New block from {}:\n{}", peer, chain.getChainHead());
            }

            @Override
            public void onDifficultyChange(Peer peer, UpdateDifficultyMessage m) {
                log.info("Received update diff message: {}", m);
                super.onDifficultyChange(peer, m);
            }
        });

        log.info("Wallet balance: {}", wallet.getBalance());

        log.info("difficulty: {}", chain.getChainHead().getHeader().getDifficultyTarget());

        Object lock = new Object();
        while (true) {
            synchronized (lock) {
                lock.wait();
            }
        }
    }
}
