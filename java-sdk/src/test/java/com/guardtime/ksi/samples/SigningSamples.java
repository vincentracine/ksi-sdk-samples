/*
 * Copyright 2013-2016 Guardtime, Inc.
 *
 * This file is part of the Guardtime client SDK.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in
 * writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES, CONDITIONS, OR OTHER LICENSES OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License. "Guardtime"
 * and "KSI" are trademarks or registered trademarks of Guardtime, Inc., and no license to
 * trademarks is granted; Guardtime reserves and retains all trademark rights.
 */
package com.guardtime.ksi.samples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.guardtime.ksi.KSI;
import com.guardtime.ksi.blocksigner.KsiBlockSigner;
import com.guardtime.ksi.exceptions.KSIException;
import com.guardtime.ksi.hashing.DataHash;
import com.guardtime.ksi.hashing.DataHasher;
import com.guardtime.ksi.hashing.HashAlgorithm;
import com.guardtime.ksi.unisignature.IdentityMetadata;
import com.guardtime.ksi.unisignature.KSISignature;
import com.guardtime.ksi.unisignature.verifier.policies.KeyBasedVerificationPolicy;
import com.guardtime.ksi.unisignature.verifier.policies.Policy;

public class SigningSamples extends KsiSamples {

    @Before
    public void setUp() throws KSIException {
        setUpKsi();
    }

    @After
    public void tearDown() {
        tearDownKsi();
    }

    /**
     * Creates a sample file, then signs it and stores the signature in a file.
     */
    @Test
    public void createAndSignSampleFile() throws IOException, KSIException {
        KSI ksi = getKsi();

        // Let's create a file to be singed
        File fileToSign = new File("sample-file-for-signing.txt");
        PrintWriter writer = new PrintWriter(fileToSign);
        writer.println("Sample file, generated for signing!");
        writer.close();

        // Sign it, the hash of the document is computed implicitly by the sign method
        KSISignature signature = ksi.sign(fileToSign);

        // Persist signature to file
        FileOutputStream fileOutputStream = new FileOutputStream(new File("sample-file-for-signing.txt.ksig"));
        signature.writeTo(fileOutputStream);
        fileOutputStream.close();
    }

    /**
     * Sign a byte array, in this example created from a simple line of text.
     */
    @Test
    public void signSampleByteArray() throws IOException, KSIException {
        KSI ksi = getKsi();

        // Whenever signing text data, make sure you control and know what the character set
        // (encoding) was otherwise you may have trouble in the verification later.
        byte[] document = "This is my document".getBytes(Charset.forName("UTF-8"));

        // Sign it, the hash of the document is computed implicitly by the sign method
        @SuppressWarnings("unused")
        KSISignature signature = ksi.sign(document);

        // Persist signature to file
        // signature.writeTo(...);
    }

    /**
     * Compute the hash of the signed document first using an input stream and then provide the hash
     * to the signing method.
     */
    @Test
    public void signHashDirectly() throws IOException, KSIException {
        KSI ksi = getKsi();

        // Compute the hash first, use the input stream to provide the data to save memory for
        // hashing very large documents
        // In this example we simply use an input stream from an array of bytes but in practice it
        // could be file input stream from a very large file (several GB)
        DataHasher dh = new DataHasher(HashAlgorithm.SHA2_256);
        dh.addData(new ByteArrayInputStream("Imagine this is a large file".getBytes(Charset.forName("UTF-8"))));

        // Provide the signing method with the computed hash instead of document itself
        @SuppressWarnings("unused")
        KSISignature signature = ksi.sign(dh.getHash());

        // Persist signature to file
        // signature.writeTo(...);
    }

    /**
     * Signs numbers 1 - 50 (as text) using client side aggregation (block signer). The Merkle tree
     * is built locally and only a single request is sent to KSI Gateway. For each item individual
     * KSI signature is returned. This helps achieving a great performance when a huge number of
     * files are needed to be signed without overloading the KSI GW.
     */
    @Test
    public void signMultipleItemsWithLocalAggregation() throws KSIException {
        KsiBlockSigner ksiBlockSigner = new KsiBlockSigner(getSimpleHttpClient());

        int itemCount = 50;

        // Add the items that need to be signed to the block signer
        DataHasher dh = new DataHasher(HashAlgorithm.SHA2_256);
        for (int i = 1; i <= itemCount; i++) {
            dh.reset();
            dh.addData(String.valueOf(i).getBytes());
            ksiBlockSigner.add(dh.getHash());
        }

        // Submit the signing request
        List<KSISignature> signatures = ksiBlockSigner.sign();

        // Just to illustrate that there are as many signatures as items
        assertEquals(itemCount, signatures.size());

        // Store the signatures as needed. Note that the signatures
        // in the list are not in the same order as the hashes were added
        // to the block signer. One simple option to find the right signature is by
        // the input hash in the signature. It assumes that all the hashes added
        // were unique which is currently the case.
        
        // In this example we find the hash for the item 15
        dh.reset();
        dh.addData(String.valueOf(15).getBytes());
        DataHash h = dh.getHash();

        KSISignature s15 = null;
        for (KSISignature s : signatures) {
        	if (s.getInputHash().equals(h)){
        		s15 = s;
        	}
        }

        assertNotNull(s15);
    }

    /**
     * Besides performance optimization, client side aggregation can be also used by embedding
     * metadata. This can be used, for instance, for linking the user identity authenticated by 3rd
     * party provider (in the same way as KSI GW does for its users). Although, the metadata fields
     * are fixed and named after how KSI infrastructure uses them, its up to the use case what is
     * the content and interpretation of metadata to be embedded, KSI signature just ensures its
     * integrity.
     * 
     */
    @Test
    public void linkUserIdToSignature() throws KSIException {
        KsiBlockSigner ksiBlockSigner = new KsiBlockSigner(getSimpleHttpClient());
        DataHasher dh = new DataHasher(HashAlgorithm.SHA2_256);

        // This is the data we are signing
        String data = "data";
        dh.addData(data.getBytes());

        // Suppose that this is the user that initiated the signing
        // and it has been verified using a 3rd party authentication provider (e.g. LDAP)
        String userId = "john.smith";

        // Add both, the data and the user to the block signer
        ksiBlockSigner.add(dh.getHash(), new IdentityMetadata(userId));
        List<KSISignature> signatures = ksiBlockSigner.sign();

        // We should get only one signature as we only had one item that we signed
        assertEquals(1, signatures.size());

        // Print the identity to show john.smith is there
        System.out.println(signatures.get(0).getIdentity());

        // Store the signature as needed
        // ...
    }

    /**
     * Normally, the hash provided by the user is assumed to be at the level 0 in
     * the global aggregation tree. In an advanced scenario someone may build
     * a Merkle tree externally and provide the root hash for signing to KSI SDK. If then
     * it is needed later to "augment" the received signature with the hash chain of the externally
     * built Merkle tree to get a KSI signature to the leaf of that tree,
     * the level of the root hash has to be correctly provided at signing time.
     */
    @Test
    public void setHashLevel() throws KSIException {
        KsiBlockSigner ksiBlockSigner = new KsiBlockSigner(getSimpleHttpClient());
        KSI ksi = getKsi();
        
        // Assume that this is the root of the externally built Merkle tree and the level of the root is 5
        DataHash rootHash = new DataHash(HashAlgorithm.SHA2_256, new byte[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31});
        
        // Sign the root hash, but also specify the level (5) so that later
        // one can augment the received KSI signature with the hash chains of externally
        // built Merkle tree to get KSI signature down to the leaf of that external tree
        ksiBlockSigner.add(rootHash, 5, new IdentityMetadata("randomClientId"));
        List<KSISignature> signatures = ksiBlockSigner.sign();

        // We should get only one signature as we only had one item that we signed
        assertEquals(1, signatures.size());        
        KSISignature sig = signatures.get(0);
        
        // The received signature verifies fine without specifying the  
        // level of the hash because the level of the first hash is still 0
        // and the level correction of first link was set to 5 (so the next computed hash
        // is expected to be at level 5 + 1 instead of 0 + 1).
        
        Policy p = new KeyBasedVerificationPolicy();
        assertTrue(ksi.verify(sig, p, rootHash).isOk());

        // In order to augment this signature with an aggregation hash chain,
        // that was below the provided root, the level correction has to be removed (set to 0).
    }

}
