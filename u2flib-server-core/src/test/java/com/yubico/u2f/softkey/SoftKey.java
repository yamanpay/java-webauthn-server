// Copyright 2014 Google Inc. All rights reserved.
//
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file or at
// https://developers.google.com/open-source/licenses/bsd

package com.yubico.u2f.softkey;

import com.yubico.u2f.TestVectors;
import com.yubico.u2f.exceptions.U2fException;
import com.yubico.u2f.data.messages.key.util.ByteInputStream;
import com.yubico.u2f.data.messages.key.RawAuthenticateResponse;
import com.yubico.u2f.data.messages.key.RawRegisterResponse;
import com.yubico.u2f.softkey.messages.AuthenticateRequest;
import com.yubico.u2f.softkey.messages.RegisterRequest;
import com.yubico.u2f.testdata.Gnubby;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;

import java.security.*;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

public class SoftKey implements Cloneable {
  private static final Logger Log = Logger.getLogger(SoftKey.class.getName());

  private final X509Certificate attestationCertificate;
  private final PrivateKey certificatePrivateKey;
  private final Map<String, KeyPair> dataStore;
  private int deviceCounter = 0;

  public SoftKey() {
    this(
            new HashMap<String, KeyPair>(),
            0,
            Gnubby.ATTESTATION_CERTIFICATE,
            Gnubby.ATTESTATION_CERTIFICATE_PRIVATE_KEY
    );
  }

  public SoftKey(
          Map<String, KeyPair> dataStore,
          int deviceCounter,
          X509Certificate attestationCertificate,
          PrivateKey certificatePrivateKey
  ) {
    this.dataStore = dataStore;
    this.deviceCounter = deviceCounter;
    this.attestationCertificate = attestationCertificate;
    this.certificatePrivateKey = certificatePrivateKey;
  }

  @Override
  public SoftKey clone() {
    return new SoftKey(
            this.dataStore,
            this.deviceCounter,
            this.attestationCertificate,
            this.certificatePrivateKey
    );
  }

  public RawRegisterResponse register(RegisterRequest registerRequest) throws Exception {
    Log.info(">> register");

    byte[] applicationSha256 = registerRequest.getApplicationSha256();
    byte[] challengeSha256 = registerRequest.getChallengeSha256();

    Log.info(" -- Inputs --");
    Log.info("  applicationSha256: " + Hex.encodeHexString(applicationSha256));
    Log.info("  challengeSha256: " + Hex.encodeHexString(challengeSha256));


    // generate ECC key
    SecureRandom random = new SecureRandom();
    ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec("secp256r1");
    KeyPairGenerator g = KeyPairGenerator.getInstance("ECDSA");
    g.initialize(ecSpec, random);
    KeyPair keyPair = g.generateKeyPair();

    byte[] keyHandle = new byte[64];
    random.nextBytes(keyHandle);
    System.out.println("Storing key for handle: " + Hex.encodeHexString(keyHandle));
    dataStore.put(new String(keyHandle), keyPair);

    byte[] userPublicKey = stripMetaData(keyPair.getPublic().getEncoded());

    byte[] signedData = RawRegisterResponse.packBytesToSign(applicationSha256, challengeSha256,
            keyHandle, userPublicKey);
    Log.info("Signing bytes " + Hex.encodeHexString(signedData));

    byte[] signature = sign(signedData, certificatePrivateKey);

    Log.info(" -- Outputs --");
    Log.info("  userPublicKey: " + Hex.encodeHexString(userPublicKey));
    Log.info("  keyHandle: " + Hex.encodeHexString(keyHandle));
    Log.info("  attestationCertificate: " + attestationCertificate);
    Log.info("  signature: " + Hex.encodeHexString(signature));

    Log.info("<< register");

    return new RawRegisterResponse(userPublicKey, keyHandle, attestationCertificate, signature);
  }

  private byte[] stripMetaData(byte[] a) {
    ByteInputStream bis = new ByteInputStream(a);
    bis.read(3);
    bis.read(bis.readUnsigned() + 1);
    int keyLength = bis.readUnsigned();
    bis.read(1);
    return bis.read(keyLength - 1);
  }

  public RawAuthenticateResponse authenticate(AuthenticateRequest authenticateRequest) throws Exception {
    Log.info(">> authenticate");

    byte control = authenticateRequest.getControl();
    byte[] applicationSha256 = authenticateRequest.getApplicationSha256();
    byte[] challengeSha256 = authenticateRequest.getChallengeSha256();
    byte[] keyHandle = authenticateRequest.getKeyHandle();

    Log.info(" -- Inputs --");
    Log.info("  control: " + control);
    Log.info("  applicationSha256: " + Hex.encodeHexString(applicationSha256));
    Log.info("  challengeSha256: " + Hex.encodeHexString(challengeSha256));
    Log.info("  keyHandle: " + Hex.encodeHexString(keyHandle));

    System.out.println("Fetching key for handle: " + Hex.encodeHexString(keyHandle));
    KeyPair keyPair = checkNotNull(dataStore.get(new String(keyHandle)));
    int counter = ++deviceCounter;
    byte[] signedData = RawAuthenticateResponse.packBytesToSign(applicationSha256, RawAuthenticateResponse.USER_PRESENT_FLAG,
            counter, challengeSha256);

    Log.info("Signing bytes " + Hex.encodeHexString(signedData));

    byte[] signature = sign(signedData, keyPair.getPrivate());

    Log.info(" -- Outputs --");
    Log.info("  userPresence: " + RawAuthenticateResponse.USER_PRESENT_FLAG);
    Log.info("  deviceCounter: " + counter);
    Log.info("  signature: " + Hex.encodeHexString(signature));

    Log.info("<< authenticate");

    return new RawAuthenticateResponse(RawAuthenticateResponse.USER_PRESENT_FLAG, counter, signature);
  }

  private byte[] sign(byte[] signedData, PrivateKey privateKey) throws Exception {
    Signature signature = Signature.getInstance("SHA256withECDSA");
    signature.initSign(privateKey);
    signature.update(signedData);
    return signature.sign();
  }
}