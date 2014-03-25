/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.remote;

import com.intellij.openapi.util.PasswordUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.remote.MutableRemoteCredentials;
import com.intellij.remote.RemoteCredentials;
import com.intellij.remote.RemoteSdkCredentials;
import com.intellij.util.xmlb.annotations.Transient;
import org.jdom.Element;
import org.jetbrains.annotations.Nullable;

/**
 * @author michael.golubev
 */
public class RemoteCredentialsHolder implements MutableRemoteCredentials {

  public static final String HOST = "HOST";
  public static final String PORT = "PORT";
  public static final String ANONYMOUS = "ANONYMOUS";
  public static final String USERNAME = "USERNAME";
  public static final String PASSWORD = "PASSWORD";
  public static final String USE_KEY_PAIR = "USE_KEY_PAIR";
  public static final String PRIVATE_KEY_FILE = "PRIVATE_KEY_FILE";
  public static final String KNOWN_HOSTS_FILE = "MY_KNOWN_HOSTS_FILE";
  public static final String PASSPHRASE = "PASSPHRASE";

  private String myHost;
  private int myPort;
  private boolean myAnonymous;
  private String myUserName;
  private String myPassword;
  private boolean myUseKeyPair;
  private String myPrivateKeyFile;
  private String myKnownHostsFile;
  private String myPassphrase;
  private boolean myStorePassword;
  private boolean myStorePassphrase;

  @Override
  public String getHost() {
    return myHost;
  }

  public void setHost(String host) {
    myHost = host;
  }

  @Override
  public int getPort() {
    return myPort;
  }

  public void setPort(int port) {
    myPort = port;
  }

  @Override
  @Transient
  public String getUserName() {
    return myUserName;
  }

  public void setUserName(String userName) {
    myUserName = userName;
  }

  @Override
  public String getPassword() {
    return myPassword;
  }

  public void setPassword(String password) {
    myPassword = password;
  }

  public void setStorePassword(boolean storePassword) {
    myStorePassword = storePassword;
  }

  public void setStorePassphrase(boolean storePassphrase) {
    myStorePassphrase = storePassphrase;
  }

  @Override
  public boolean isStorePassword() {
    return myStorePassword;
  }

  @Override
  public boolean isStorePassphrase() {
    return myStorePassphrase;
  }

  @Override
  public boolean isAnonymous() {
    return myAnonymous;
  }

  public void setAnonymous(boolean anonymous) {
    myAnonymous = anonymous;
  }

  @Override
  public String getPrivateKeyFile() {
    return myPrivateKeyFile;
  }

  public void setPrivateKeyFile(String privateKeyFile) {
    myPrivateKeyFile = privateKeyFile;
  }

  @Override
  public String getKnownHostsFile() {
    return myKnownHostsFile;
  }

  public void setKnownHostsFile(String knownHostsFile) {
    myKnownHostsFile = knownHostsFile;
  }

  @Override
  @Transient
  public String getPassphrase() {
    return myPassphrase;
  }

  public void setPassphrase(String passphrase) {
    myPassphrase = passphrase;
  }

  @Override
  public boolean isUseKeyPair() {
    return myUseKeyPair;
  }

  public void setUseKeyPair(boolean useKeyPair) {
    myUseKeyPair = useKeyPair;
  }

  public String getSerializedUserName() {
    if (myAnonymous || myUserName == null) return "";
    return myUserName;
  }

  public void setSerializedUserName(String userName) {
    if (StringUtil.isEmpty(userName)) {
      myUserName = null;
    }
    else {
      myUserName = userName;
    }
  }

  public String getSerializedPassword() {
    if (myAnonymous) return "";

    if (myStorePassword) {
      return PasswordUtil.encodePassword(myPassword);
    }
    else {
      return "";
    }
  }

  public void setSerializedPassword(String serializedPassword) {
    if (!StringUtil.isEmpty(serializedPassword)) {
      myPassword = PasswordUtil.decodePassword(serializedPassword);
      myStorePassword = true;
    }
    else {
      myPassword = null;
    }
  }

  @Nullable
  public String getSerializedPassphrase() {
    if (myStorePassphrase) {
      return PasswordUtil.encodePassword(myPassphrase);
    }
    else {
      return "";
    }
  }

  public void setSerializedPassphrase(String serializedPassphrase) {
    if (!StringUtil.isEmpty(serializedPassphrase)) {
      myPassphrase = PasswordUtil.decodePassword(serializedPassphrase);
      myStorePassphrase = true;
    }
    else {
      myPassphrase = null;
      myStorePassphrase = false;
    }
  }

  public void copyRemoteCredentialsTo(RemoteSdkCredentials to) {
    to.setHost(getHost());
    to.setPort(getPort());
    to.setAnonymous(isAnonymous());
    to.setUserName(getUserName());
    to.setPassword(getPassword());
    to.setUseKeyPair(isUseKeyPair());
    to.setPrivateKeyFile(getPrivateKeyFile());
    to.setKnownHostsFile(getKnownHostsFile());
    to.setStorePassword(isStorePassword());
    to.setStorePassphrase(isStorePassphrase());
  }

  public void load(Element element) {
    setHost(element.getAttributeValue(HOST));
    setPort(StringUtil.parseInt(element.getAttributeValue(PORT), 22));
    setAnonymous(StringUtil.parseBoolean(element.getAttributeValue(ANONYMOUS), false));
    setSerializedUserName(element.getAttributeValue(USERNAME));
    setSerializedPassword(element.getAttributeValue(PASSWORD));
    setPrivateKeyFile(StringUtil.nullize(element.getAttributeValue(PRIVATE_KEY_FILE)));
    setKnownHostsFile(StringUtil.nullize(element.getAttributeValue(KNOWN_HOSTS_FILE)));
    setSerializedPassphrase(element.getAttributeValue(PASSPHRASE));
    setUseKeyPair(StringUtil.parseBoolean(element.getAttributeValue(USE_KEY_PAIR), false));
  }

  public void save(Element rootElement) {
    rootElement.setAttribute(HOST, StringUtil.notNullize(getHost()));
    rootElement.setAttribute(PORT, Integer.toString(getPort()));
    rootElement.setAttribute(ANONYMOUS, Boolean.toString(isAnonymous()));
    rootElement.setAttribute(USERNAME, getSerializedUserName());
    rootElement.setAttribute(PASSWORD, getSerializedPassword());
    rootElement.setAttribute(PRIVATE_KEY_FILE, StringUtil.notNullize(getPrivateKeyFile()));
    rootElement.setAttribute(KNOWN_HOSTS_FILE, StringUtil.notNullize(getKnownHostsFile()));
    rootElement.setAttribute(PASSPHRASE, getSerializedPassphrase());
    rootElement.setAttribute(USE_KEY_PAIR, Boolean.toString(isUseKeyPair()));
  }

  public void copyFrom(RemoteCredentials from) {
    setHost(from.getHost());
    setPort(from.getPort());
    setAnonymous(from.isAnonymous());
    setUserName(from.getUserName());
    setPassword(from.getPassword());
    setUseKeyPair(from.isUseKeyPair());
    setPrivateKeyFile(from.getPrivateKeyFile());
    setKnownHostsFile(from.getKnownHostsFile());
    setStorePassword(from.isStorePassword());
    setStorePassphrase(from.isStorePassphrase());
  }
}