/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.common.cloud;

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.cloud.AbstractZkTestCase;
import org.apache.solr.cloud.ZkTestServer;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolrZkClientTest extends SolrTestCaseJ4 {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  private static final String ROOT = "/";
  private static final String PATH = "/collections/collection1";
  
  protected ZkTestServer zkServer;

  SolrZkClient aclClient;
  SolrZkClient credentialsClient;
  SolrZkClient defaultClient;
  

  @Override
  public void setUp() throws Exception {
    super.setUp();
    
    final String SCHEME = "digest";
    final String AUTH = "user:pass";

    String zkDir = createTempDir().toString();
    log.info("ZooKeeper dataDir:" + zkDir);
    zkServer = new ZkTestServer(zkDir);
    zkServer.run();
    
    try (SolrZkClient client = new SolrZkClient(zkServer.getZkHost(), AbstractZkTestCase.TIMEOUT)) {
      // Set up chroot
      client.makePath("/solr", false, true);
    }

    defaultClient = new SolrZkClient(zkServer.getZkAddress(), AbstractZkTestCase.TIMEOUT);
    defaultClient.makePath(PATH, true);
    
    aclClient = new SolrZkClient(zkServer.getZkAddress(), AbstractZkTestCase.TIMEOUT) {
      @Override
      protected ZkACLProvider createZkACLProvider() {
        return new DefaultZkACLProvider() {
          @Override
          protected List<ACL> createGlobalACLsToAdd() {
            try {
              Id id = new Id(SCHEME, DigestAuthenticationProvider.generateDigest(AUTH));
              return Collections.singletonList(new ACL(ZooDefs.Perms.ALL, id));
            } catch (NoSuchAlgorithmException e) {
              throw new RuntimeException(e);
            }
          }
        };
      }
    };
    
    credentialsClient = new SolrZkClient(zkServer.getZkAddress(), AbstractZkTestCase.TIMEOUT) {
      @Override
      protected ZkCredentialsProvider createZkCredentialsToAddAutomatically() {
        return new DefaultZkCredentialsProvider() {
          @Override
          protected Collection<ZkCredentials> createCredentials() {
            return Collections.singleton(new ZkCredentials(SCHEME, AUTH.getBytes(StandardCharsets.UTF_8)));
          }
        };
      }
    };
  }
  
  @Override
  public void tearDown() throws Exception {
    aclClient.close();
    credentialsClient.close();
    defaultClient.close();
    zkServer.shutdown();
    super.tearDown();
  }

  
  @Test
  public void testSimpleUpdateACLs() throws KeeperException, InterruptedException {
    assertTrue("Initial create was in secure mode; please check the test", canRead(defaultClient, PATH));
    assertTrue("Credentialed client should always be able to read", canRead(credentialsClient, PATH));

    // convert to secure
    aclClient.updateACLs(ROOT);
    assertFalse("Default client should not be able to read root in secure mode", canRead(defaultClient, ROOT));
    assertFalse("Default client should not be able to read children in secure mode", canRead(defaultClient, PATH));
    assertTrue("Credentialed client should always be able to read root in secure mode", canRead(credentialsClient, ROOT));
    assertTrue("Credentialed client should always be able to read in secure mode", canRead(credentialsClient, PATH));

    // convert to non-secure
    credentialsClient.updateACLs(ROOT);
    assertTrue("Default client should work again after clearing ACLs", canRead(defaultClient, PATH));
    assertTrue("Credentialed client should always be able to read", canRead(credentialsClient, PATH));

    // convert a subtree to secure
    aclClient.updateACLs("/collections");
    assertTrue("Default client should read unaffected paths", canRead(defaultClient, ROOT));
    assertFalse("Default client should not read secure children", canRead(defaultClient, PATH));
  }
  
  private static boolean canRead(SolrZkClient zkClient, String path) throws KeeperException, InterruptedException {
    try {
      zkClient.getData(path, null, null, true);
      return true;
    } catch (KeeperException.NoAuthException e) {
      return false;
    }
  }
  
  @Test
  public void testCheckInterrupted() {
    assertFalse(Thread.currentThread().isInterrupted());
    SolrZkClient.checkInterrupted(new RuntimeException());
    assertFalse(Thread.currentThread().isInterrupted());
    SolrZkClient.checkInterrupted(new InterruptedException());
    assertTrue(Thread.currentThread().isInterrupted());
  }
  
  
}
