// Copyright (c) 2018, Yubico AB
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this
//    list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
// OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.yubico.internal.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class CertificateParser {
  public static final String ID_FIDO_GEN_CE_AAGUID = "1.3.6.1.4.1.45724.1.1.4";
  public static final String OID_CRL_DISTRIBUTION_POINTS = "2.5.29.31";
  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

  private static final List<String> FIXSIG =
      Arrays.asList(
          "CN=Yubico U2F EE Serial 776137165",
          "CN=Yubico U2F EE Serial 1086591525",
          "CN=Yubico U2F EE Serial 1973679733",
          "CN=Yubico U2F EE Serial 13503277888",
          "CN=Yubico U2F EE Serial 13831167861",
          "CN=Yubico U2F EE Serial 14803321578");

  private static final int UNUSED_BITS_BYTE_INDEX_FROM_END = 257;

  public static X509Certificate parsePem(String pemEncodedCert) throws CertificateException {
    return parseDer(
        pemEncodedCert
            .replaceAll("-----BEGIN CERTIFICATE-----", "")
            .replaceAll("-----END CERTIFICATE-----", "")
            .replaceAll("\n", ""));
  }

  public static X509Certificate parseDer(String base64DerEncodedCert) throws CertificateException {
    return parseDer(BASE64_DECODER.decode(base64DerEncodedCert));
  }

  public static X509Certificate parseDer(byte[] derEncodedCert) throws CertificateException {
    return parseDer(new ByteArrayInputStream(derEncodedCert));
  }

  public static X509Certificate parseDer(InputStream is) throws CertificateException {
    X509Certificate cert =
        (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(is);
    // Some known certs have an incorrect "unused bits" value, which causes problems on newer
    // versions of BouncyCastle.
    if (FIXSIG.contains(cert.getSubjectX500Principal().getName())) {
      byte[] encoded = cert.getEncoded();

      if (encoded.length >= UNUSED_BITS_BYTE_INDEX_FROM_END) {
        encoded[encoded.length - UNUSED_BITS_BYTE_INDEX_FROM_END] =
            0; // Fix the "unused bits" field (should always be 0).
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Expected DER encoded cert to be at least %d bytes, was %d: %s",
                UNUSED_BITS_BYTE_INDEX_FROM_END, encoded.length, cert));
      }

      cert =
          (X509Certificate)
              CertificateFactory.getInstance("X.509")
                  .generateCertificate(new ByteArrayInputStream(encoded));
    }
    return cert;
  }

  /**
   * Compute a Subject Key Identifier as defined as method (1) in RFC 5280 section 4.2.1.2.
   *
   * @throws NoSuchAlgorithmException if the SHA-1 hash algorithm is not available.
   * @see <a href="https://datatracker.ietf.org/doc/html/rfc5280#section-4.2.1.2">Internet X.509
   *     Public Key Infrastructure Certificate and Certificate Revocation List (CRL) Profile,
   *     section 4.2.1.2. Subject Key Identifier</a>
   */
  public static byte[] computeSubjectKeyIdentifier(final Certificate cert)
      throws NoSuchAlgorithmException {
    final byte[] spki = cert.getPublicKey().getEncoded();

    // SubjectPublicKeyInfo  ::=  SEQUENCE  {
    //     algorithm            AlgorithmIdentifier,
    //     subjectPublicKey     BIT STRING  }
    final byte algLength = spki[2 + 1];

    // BIT STRING begins with one octet specifying number of unused bits at end;
    // this is not included in the content to hash for a Subject Key Identifier.
    final int spkBitsStart = 2 + 2 + 2 + algLength + 1;

    return MessageDigest.getInstance("SHA-1")
        .digest(Arrays.copyOfRange(spki, spkBitsStart, spki.length));
  }

  /**
   * Parses an AAGUID into bytes. Refer to <a
   * href="https://www.w3.org/TR/2021/REC-webauthn-2-20210408/#sctn-packed-attestation-cert-requirements">Packed
   * Attestation Statement Certificate Requirements</a> on the W3C web site for details of the ASN.1
   * structure that this method parses.
   *
   * @param bytes the bytes making up value of the extension
   * @return the bytes of the AAGUID
   */
  private static byte[] parseAaguid(byte[] bytes) {

    if (bytes != null && bytes.length == 20) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);

      if (buffer.get() == (byte) 0x04
          && buffer.get() == (byte) 0x12
          && buffer.get() == (byte) 0x04
          && buffer.get() == (byte) 0x10) {
        byte[] aaguidBytes = new byte[16];
        buffer.get(aaguidBytes);

        return aaguidBytes;
      }
    }

    throw new IllegalArgumentException(
        "X.509 extension 1.3.6.1.4.1.45724.1.1.4 (id-fido-gen-ce-aaguid) is not valid.");
  }

  public static Optional<byte[]> parseFidoAaguidExtension(X509Certificate cert) {
    Optional<byte[]> result =
        Optional.ofNullable(cert.getExtensionValue(ID_FIDO_GEN_CE_AAGUID))
            .map(CertificateParser::parseAaguid);
    result.ifPresent(
        aaguid -> {
          if (cert.getCriticalExtensionOIDs().contains(ID_FIDO_GEN_CE_AAGUID)) {
            throw new IllegalArgumentException(
                String.format(
                    "X.509 extension %s (id-fido-gen-ce-aaguid) must not be marked critical.",
                    ID_FIDO_GEN_CE_AAGUID));
          }
        });
    return result;
  }

  public static Optional<Set<URL>> parseCrlDistributionPointsExtension(X509Certificate cert) {

    Set<URL> urls = new HashSet<>();
    final byte[] crldpExtension = cert.getExtensionValue(OID_CRL_DISTRIBUTION_POINTS);
    if (crldpExtension != null) {
      BinaryUtil.ParseDerResult<byte[]> octetString = BinaryUtil.parseDerOctetString(crldpExtension, 0);
      //int octetOffset = 0;
      //while (outerSeqOffset < outerSeq.result.length) {
      try {
        BinaryUtil.ParseDerResult<byte[]> CRLDistributionPoints = BinaryUtil.parseDerSequence(octetString.result, 0);
        //int outerSeqOffset = 0;

        try {
          BinaryUtil.ParseDerResult<byte[]> distributionPoints = BinaryUtil.parseDerSequence(CRLDistributionPoints.result, 0);
          //int distributionPointOffset = 0;

          try {
            BinaryUtil.ParseDerResult<byte[]> distributionPointName = BinaryUtil.parseDerChoice(distributionPoints.result, 0, (byte) 0);
           // distributionPointOffset = dp.nextOffset;

            try {
              BinaryUtil.ParseDerResult<byte[]> fullName = BinaryUtil.parseDerChoice(distributionPointName.result, 0, (byte) 0);

              try {
                BinaryUtil.ParseDerResult<byte[]> uriBytes = BinaryUtil.parseDerIA5String(fullName.result, 0);
                String uriString = new String(uriBytes.result, StandardCharsets.US_ASCII);
                URL url = new URL(uriString);
                urls.add(url);

              } catch (IllegalArgumentException e) {
                // Ignore
              } catch (MalformedURLException e) {
                throw new RuntimeException(e);
              }

            } catch (IllegalArgumentException e) {
              // Ignore
            }

            try {
              //BinaryUtil.ParseDerResult<byte[]> nameRelativeToCrlIssuer = BinaryUtil.parseDerChoice(dp.result, 0, (byte) 1);
            } catch (IllegalArgumentException e) {
              // Ignore
            }
          } catch (IllegalArgumentException e) {
            // Ignore
          }
          //distributionPointOffset = distributionPoint.nextOffset;


        } catch (IllegalArgumentException e) {
          // Ignore
        }

        /*try {
          BinaryUtil.ParseDerResult<byte[]> reasons = BinaryUtil.parseDerChoice(distributionPoint.result, distributionPointOffset, (byte) 1);
          distributionPointOffset = reasons.nextOffset;
        } catch (IllegalArgumentException e) {
          // Ignore
        }

        try {
          BinaryUtil.ParseDerResult<byte[]> crlIssuer = BinaryUtil.parseDerChoice(distributionPoint.result, distributionPointOffset, (byte) 2);
        } catch (IllegalArgumentException e) {
          // Ignore
        }*/

        //outerSeqOffset = distributionPoint.nextOffset;
      } catch (IllegalArgumentException e) {
        // Ignore
      }
    }

    Optional<byte[]> result =
        Optional.ofNullable(cert.getExtensionValue(ID_FIDO_GEN_CE_AAGUID))
            .map(CertificateParser::parseAaguid);
    result.ifPresent(
        aaguid -> {
          if (cert.getCriticalExtensionOIDs().contains(ID_FIDO_GEN_CE_AAGUID)) {
            throw new IllegalArgumentException(
                String.format(
                    "X.509 extension %s (id-fido-gen-ce-aaguid) must not be marked critical.",
                    ID_FIDO_GEN_CE_AAGUID));
          }
        });
    return Optional.of(urls);
  }
}
