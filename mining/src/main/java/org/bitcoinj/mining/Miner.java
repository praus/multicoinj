package org.bitcoinj.mining;

import java.io.File;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
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
import com.google.bitcoin.core.PrunedException;
import com.google.bitcoin.core.Sha256Hash;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Utils;
import com.google.bitcoin.core.VerificationException;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.store.FullPrunedBlockStore;
import com.google.bitcoin.store.H2FullPrunedBlockStore;
import com.google.common.io.BaseEncoding;

public class Miner {

	final static Logger log = LoggerFactory.getLogger(Miner.class);
	
	private NetworkParameters params;
	
	private final FullPrunedBlockStore store;
	
	private final FullPrunedBlockChain chain; 
	
	private final Wallet wallet;
	
	private final PeerGroup peerGroup;
	
	private final int getworkPort = 8010;
	
	private final Map<Sha256Hash, Block> mapNewBlock = new HashMap<Sha256Hash, Block>();
	
	public static final long WORK_MAXAGE = 60000L; // 60 seconds
	/**
	 * Transactions to be included in the next block
	 */
	private final List<Transaction> activeTransactions = new ArrayList<Transaction>();
	
	private Block latestNextBlock;
	private long latestNextBlockTime;
	
	private boolean blockchainDownloaded = false;
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		new Miner();
	}
	
	private void startGetWorkServer() {
		ServerBootstrap bootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        // Set up the event pipeline factory.
        bootstrap.setPipelineFactory(new GetworkPipelineFactory(this));

        // Bind and start to accept incoming connections.
        InetSocketAddress listenAddr = new InetSocketAddress(getworkPort);
        bootstrap.bind(listenAddr);
        
        log.debug("Getwork server started on {}", listenAddr);
	}
	
	public Miner() throws Exception {
		//params = NetworkParametersMulticoin.multiNet();
		params = NetworkParameters.testNet3();
		
		//store = new H2FullPrunedBlockStore(params, "~/miner-chain", Integer.MAX_VALUE);
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
				blockchainDownloaded = true;
			}
		});
		//peerGroup.downloadBlockChain(); // synchronous
		
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
				activeTransactions.add(t);
				// When somebody solves a block, we need to reset our own mining block
				reloadBlock(true); // force reload block
			}

			/**
			 * This is called once the block was successfully verified and linked to our version
			 * of block chain.
			 */
			@Override
			public void onBlocksDownloaded(Peer peer, Block block,
					int blocksLeft) {
				super.onBlocksDownloaded(peer, block, blocksLeft);
				log.info("New block from {}:\n{}", peer, chain.getChainHead());
				reloadBlock();
			}
		});
		
		
		reloadBlock();
		
		startGetWorkServer();
		
		log.info("Wallet balance: {}", wallet.getBalance());
		
		Object lock = new Object();
		while (true) {
			synchronized (lock) {
				lock.wait();
			}
		}
	}
	
	protected Block reloadBlock() {
		return reloadBlock(false);
	}
	
	protected Block reloadBlock(boolean force) {
		if (!force && latestNextBlock != null && (System.currentTimeMillis() - latestNextBlockTime) < WORK_MAXAGE) {
			return latestNextBlock;
		}
		
		final ECKey key = wallet.keychain.get(0);
		long time = System.currentTimeMillis() / 1000;
		BigInteger coinbaseValue = Utils.toNanoCoins(50, 0);
		Block chainHead = chain.getChainHead().getHeader();
		Block nextblock = chainHead.createNextMiningBlock(time, key.getPubKey(), coinbaseValue);
		for (Transaction t : activeTransactions)
			nextblock.addTransaction(t);
		
		mapNewBlock.put(nextblock.getMerkleRoot(), nextblock);
		latestNextBlock = nextblock;
		latestNextBlockTime = System.currentTimeMillis();
		return latestNextBlock;
	}

	public synchronized boolean submitBlock(Block solvedBlockHeader) {
		Block block = mapNewBlock.get(solvedBlockHeader.getMerkleRoot());
		if (block == null) {
			log.debug("No block with merkle root {} found!", solvedBlockHeader.getMerkleRoot());
			return false;
		}
		if (!solvedBlockHeader.getPrevBlockHash().equals(chain.getChainHead().getHeader().getHash())) {
			log.debug("Block with too old parent. Parent of the new block must be current chainhead.");
			return false;
		}
		block.setNonce(solvedBlockHeader.getNonce());
		
		log.info("solved block: {}", solvedBlockHeader.getHash());
		log.info("Block to broadcast: {}", block.getHash());
		log.info("block transactions: {}", block.getTransactions());
		
		boolean success = false;
		try {
			// check work
			success = chain.add(block);
			log.info("Block chain new block status: {}", success);
			if (success) {
				log.info("Broadcasting: {}\n{}", block.getHash(), block);
				peerGroup.broadcastBlock(block);
				activeTransactions.clear();
				reloadBlock(true);
			}
		} catch (VerificationException e) {
			e.printStackTrace();
			return false;
		} catch (PrunedException e) {
			e.printStackTrace();
			return false;
		}
		
		return success;
	}
	
	public synchronized byte[] getWork() {
		while (!blockchainDownloaded) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
			}
		}
		
		Block hdr = reloadBlock();
		
		long nVersion = hdr.getVersion();
		Sha256Hash hashPrevBlock = hdr.getPrevBlockHash();
		Sha256Hash hashMerkleRoot = hdr.getMerkleRoot();
		long nTime = hdr.getTimeSeconds();
		long nBits = hdr.getDifficultyTarget();
		long nNonce = hdr.getNonce();
		// 640 bits, 80 bytes, we need some stupid padding to get to 128 bytes / 1024 bits
		
		byte[] buf = new byte[128];
		byte[] hashPrevBlockEnc = Utils.reverseDwordBytes(Utils.reverseBytes(hashPrevBlock.getBytes()), 32);
		byte[] hashMerkleEnc = Utils.reverseDwordBytes(Utils.reverseBytes(hashMerkleRoot.getBytes()), 32);
		
		Utils.uint32ToByteArrayBE(nVersion, buf, 0);
		System.arraycopy(hashPrevBlockEnc, 0, buf, 4, 32);
		System.arraycopy(hashMerkleEnc, 0, buf, 36, 32);
		Utils.uint32ToByteArrayBE(nTime, buf, 68);
		Utils.uint32ToByteArrayBE(nBits, buf, 72);
		Utils.uint32ToByteArrayBE(nNonce, buf, 76);
		
		log.info("getwork merkle root:{}", hashMerkleRoot);
		//log.info("getWork: data={}", BaseEncoding.base16().lowerCase().encode(buf));
		BaseEncoding.base16().lowerCase().encode(buf);
		
		return buf;
	}

	public NetworkParameters getParams() {
		return params;
	}
	
}
