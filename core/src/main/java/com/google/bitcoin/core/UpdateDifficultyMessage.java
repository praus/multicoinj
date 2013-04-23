package com.google.bitcoin.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Update difficulty message
 */
public class UpdateDifficultyMessage extends Message {
    private static final Logger log = LoggerFactory.getLogger(UpdateDifficultyMessage.class);
    private static final long serialVersionUID = 1L;

    private long newDiff; // we need long because we can't have unsigned int
    private Date timestamp;
    private Date expiration;
    private byte[] signature;

    // See the getters for documentation of what each field means.
    private long version = 1;

    private transient Sha256Hash hash;
    private transient BigInteger timestampBig;
    private transient BigInteger expirationBig;

    
    
    public UpdateDifficultyMessage(NetworkParameters params, byte[] payloadBytes, boolean parseRetain, int length) throws ProtocolException {
        super(params, payloadBytes, 0, false, parseRetain, length);
    }

    @Override
    public String toString() {
        return String.format("UpdateDiff: %s %s [%s] %s", newDiff, timestamp, getHash().toString(),
                Utils.bytesToHexString(signature));
    }

    @Override
    void parse() throws ProtocolException {
        newDiff = readUint32();

        // Read the timestamps. Bitcoin uses seconds since the epoch.
        timestampBig = readUint64();
        expirationBig = readUint64();

        timestamp = new Date(timestampBig.longValue() * 1000);
        expiration = new Date(expirationBig.longValue() * 1000);
        signature = readByteArray();

        log.info("new difficulty: {}", newDiff);

        length = cursor - offset;
    }

    /**
     * Returns true if the digital signature attached to the message verifies.
     */
    public boolean isSignatureValid() {
        InputStream is = getClass().getResourceAsStream("pubkey.ecdsa");
        try {
            byte[] pubkey = Utils.readDiskSerializedStream(is);
            return ECKey.verify(Utils.doubleDigest(getContent()), signature, pubkey);
        } catch (IOException e) {
            log.error("Cannot open public key to verify signature", e);
        }
        return false;
    }

    @Override
    protected void parseLite() throws ProtocolException {
        // Do nothing, lazy parsing isn't useful for updatediff messages.
    }

    /**
     * @return data that are signed by the signature in this message
     */
    private byte[] getContent() {
        ByteArrayOutputStream ss = new ByteArrayOutputStream(20);
        try {
            Utils.uint32ToByteStreamLE(newDiff, ss);
            Utils.uint64ToByteStreamLE(timestampBig, ss);
            Utils.uint64ToByteStreamLE(expirationBig, ss);
        } catch (IOException e) {
        }
        return ss.toByteArray();
    }

    @Override
    public Sha256Hash getHash() {
        if (hash == null) {
            byte[] hashed = Utils.reverseBytes(Utils.doubleDigest(getContent()));
            hash = new Sha256Hash(hashed);
        }
        return hash;
    }

    // -----------------------
    // Field accessors.

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public Date getExpiration() {
        return expiration;
    }

    public void setExpiration(Date expiration) {
        this.expiration = expiration;
    }

    public long getVersion() {
        return version;
    }
}
