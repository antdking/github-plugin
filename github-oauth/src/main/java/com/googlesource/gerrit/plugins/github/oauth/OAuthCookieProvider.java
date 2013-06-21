// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.googlesource.gerrit.plugins.github.oauth;

import java.net.URLEncoder;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.servlet.http.Cookie;

import org.slf4j.Logger;

public class OAuthCookieProvider {
  private static final String UTF8 = "UTF-8";
  private static final String ENC_ALGO_PADDING = "AES/CBC/PKCS5Padding";
  private static final String JCE_PROVIDER = "SunJCE";
  private static final String ENC_ALGO = "AES";
  private static final Logger log = org.slf4j.LoggerFactory
      .getLogger(OAuthCookieProvider.class);
  private static final Long COOKIE_TIMEOUT = 15 * 60 * 1000L;
  private SecretKey aesKey;
  private byte[] IV;
  private SecureRandom sessionRnd = new SecureRandom();


  void init() {
    KeyGenerator kgen;
    try {
      kgen = KeyGenerator.getInstance(ENC_ALGO);
      kgen.init(128);
      SecureRandom sr = new SecureRandom();
      sr.setSeed(System.currentTimeMillis());
      byte[] key = new byte[16];
      IV = new byte[16];
      sr.nextBytes(key);
      sr.nextBytes(IV);
      aesKey = kgen.generateKey();
      sessionRnd.setSeed(System.currentTimeMillis());
    } catch (NoSuchAlgorithmException e) {
      log.error("Cannot find encryption algorithm " + ENC_ALGO);
      throw new IllegalArgumentException(e);
    }
  }

  public OAuthCookie getFromUser(String username) {
    try {
      return new OAuthCookie(username, encode(username));
    } catch (OAuthTokenException e) {
      return null;
    }
  }

  public OAuthCookie getFromCookie(Cookie cookie) {
    try {
      return new OAuthCookie(decode(cookie.getValue()), cookie.getValue());
    } catch (OAuthTokenException e) {
      return null;
    }
  }

  public String encode(String user) throws OAuthTokenException {
    try {
      long sessionId = sessionRnd.nextLong();
      long ts = System.currentTimeMillis();
      String userSession =
          String.format("%d/%d/%s", sessionId, ts,
              URLEncoder.encode(user, UTF8));
      byte[] plainText =
          (userSession + "/" + userSession.hashCode()).getBytes(UTF8);

      Cipher cipher = Cipher.getInstance(ENC_ALGO_PADDING, JCE_PROVIDER);
      cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(IV));
      byte[] enc = cipher.doFinal(plainText);
      return org.eclipse.jgit.util.Base64.encodeBytes(enc).trim();
    } catch (Exception e) {
      log.error("Encryption failed", e);
      throw new OAuthTokenException("Cannot generate session token for user "
          + user, e);
    }
  }

  public String decode(String sessionToken) throws OAuthTokenException {
    try {
      byte[] enc =
          org.eclipse.jgit.util.Base64.decode(sessionToken.trim().getBytes(),
              0, sessionToken.length());
      Cipher cipher = Cipher.getInstance(ENC_ALGO_PADDING, JCE_PROVIDER);
      cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(IV));

      String[] clearTextParts =
          new String(cipher.doFinal(enc), UTF8).split("/");

      isValid(sessionToken, clearTextParts);

      return clearTextParts[2];
    } catch (Exception e) {
      log.error("Decryption failed", e);
      throw new OAuthTokenException("Invalid session token " + sessionToken, e);
    }
  }

  private void isValid(String sessionToken, String[] clearTextParts)
      throws OAuthTokenException {
    int hashCode = Integer.parseInt(clearTextParts[3]);
    if (hashCode != (clearTextParts[0] + "/" + clearTextParts[1] + "/" + clearTextParts[2])
        .hashCode()) {
      throw new OAuthTokenException("Invalid or forged token " + sessionToken);
    }

    long ts = Long.parseLong(clearTextParts[1]);
    if ((System.currentTimeMillis() - ts) > COOKIE_TIMEOUT) {
      throw new OAuthTokenException("Session token " + sessionToken
          + " has expired");
    }
  }
}