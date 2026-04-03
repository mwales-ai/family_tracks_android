package com.familytracks.app;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts location data with AES-256-GCM and sends it as a UDP packet.
 *
 * Wire format:
 *   [36 bytes user UUID ascii][12 bytes nonce][16 bytes GCM tag][ciphertext]
 */
public class PacketSender
{
    private static final String TAG = "PacketSender";
    private static final int NONCE_SIZE = 12;
    private static final int TAG_BITS = 128;

    private final ServerConfig theConfig;
    private final SecureRandom theRandom;

    public PacketSender(ServerConfig config)
    {
        theConfig = config;
        theRandom = new SecureRandom();
    }

    /**
     * Encrypt and send a location payload over UDP.
     * Must be called from a background thread.
     */
    public void sendLocation(JSONObject payload)
    {
        if (!theConfig.isConfigured())
        {
            Log.w(TAG, "Not configured, skipping send");
            return;
        }

        try
        {
            byte[] plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);

            // Generate random 12-byte nonce
            byte[] nonce = new byte[NONCE_SIZE];
            theRandom.nextBytes(nonce);

            // Encrypt with AES-256-GCM
            SecretKeySpec keySpec = new SecretKeySpec(theConfig.getAesKey(), "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BITS, nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] encrypted = cipher.doFinal(plaintext);

            // Java appends the 16-byte tag to the end of the ciphertext.
            // Our server expects: [nonce][tag][ciphertext]
            // So we need to split: encrypted = [ciphertext_bytes][16-byte tag]
            int ciphertextLen = encrypted.length - 16;
            byte[] ciphertextOnly = new byte[ciphertextLen];
            byte[] tag = new byte[16];
            System.arraycopy(encrypted, 0, ciphertextOnly, 0, ciphertextLen);
            System.arraycopy(encrypted, ciphertextLen, tag, 0, 16);

            // Build the wire packet: [UUID][nonce][tag][ciphertext]
            byte[] uuidBytes = theConfig.getUserId().getBytes(StandardCharsets.US_ASCII);
            int totalLen = uuidBytes.length + NONCE_SIZE + 16 + ciphertextLen;
            ByteBuffer buf = ByteBuffer.allocate(totalLen);
            buf.put(uuidBytes);
            buf.put(nonce);
            buf.put(tag);
            buf.put(ciphertextOnly);

            byte[] packet = buf.array();

            // Send via UDP
            DatagramSocket socket = new DatagramSocket();
            InetAddress addr = InetAddress.getByName(theConfig.getHost());
            DatagramPacket dgram = new DatagramPacket(packet, packet.length, addr, theConfig.getPort());
            socket.send(dgram);
            socket.close();

            Log.d(TAG, "Sent " + packet.length + " bytes to "
                    + theConfig.getHost() + ":" + theConfig.getPort());
        }
        catch (Exception e)
        {
            Log.e(TAG, "Failed to send packet: " + e.getMessage());
        }
    }
}
