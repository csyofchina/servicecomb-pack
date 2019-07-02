/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.servicecomb.pack.alpha.server.fsm;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import akka.actor.ActorSystem;
import io.grpc.netty.NettyChannelBuilder;
import java.util.Map;
import java.util.UUID;
import org.apache.servicecomb.pack.alpha.core.OmegaCallback;
import org.apache.servicecomb.pack.alpha.fsm.SagaActorState;
import org.apache.servicecomb.pack.alpha.fsm.TxState;
import org.apache.servicecomb.pack.alpha.fsm.model.SagaData;
import org.apache.servicecomb.pack.alpha.fsm.spring.integration.akka.SagaDataExtension;
import org.apache.servicecomb.pack.alpha.server.AlphaApplication;
import org.apache.servicecomb.pack.alpha.server.AlphaConfig;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {AlphaApplication.class, AlphaConfig.class},
    properties = {
        "alpha.server.host=0.0.0.0",
        "alpha.server.port=8090",
        "alpha.event.pollingInterval=1",
        "spring.main.allow-bean-definition-overriding=true",
        "alpha.model.actor.enabled=true",
        "spring.profiles.active=akka-persistence-mem"
       })
public class AlphaIntegrationFsmTest {
  private static final OmegaEventSender omegaEventSender = OmegaEventSender.builder().build();
  private static final int port = 8090;

  @Autowired(required = false)
  ActorSystem system;

  @Autowired
  private Map<String, Map<String, OmegaCallback>> omegaCallbacks;

  @BeforeClass
  public static void beforeClass() {
    omegaEventSender.configClient(NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build());
  }

  @AfterClass
  public static void afterClass() throws Exception {
    omegaEventSender.shutdown();
  }

  @Before
  public void before() {
    omegaEventSender.setOmegaCallbacks(omegaCallbacks);
    omegaEventSender.reset();
  }

  @After
  public void after() {
    omegaEventSender.onDisconnected();
  }

  @Test
  public void successfulTest() {
    final String globalTxId = UUID.randomUUID().toString();
    final String localTxId_1 = UUID.randomUUID().toString();
    final String localTxId_2 = UUID.randomUUID().toString();
    final String localTxId_3 = UUID.randomUUID().toString();
    omegaEventSender.onConnected();
    omegaEventSender.getOmegaEventSagaSimulator().sagaSuccessfulEvents(globalTxId, localTxId_1, localTxId_2, localTxId_3).stream().forEach( event -> {
      omegaEventSender.getBlockingStub().onTxEvent(event);
    });
    await().atMost(1, SECONDS).until(() -> omegaEventSender.getOmegaCallbacks() != null);
    await().atMost(1, SECONDS).until(() -> SagaDataExtension.SAGA_DATA_EXTENSION_PROVIDER.get(system).getSagaData(globalTxId) != null);
    SagaData sagaData = SagaDataExtension.SAGA_DATA_EXTENSION_PROVIDER.get(system).getSagaData(globalTxId);
    Assert.assertEquals(sagaData.getLastState(),SagaActorState.COMMITTED);
    Assert.assertEquals(sagaData.getTxEntityMap().size(),3);
    Assert.assertTrue(sagaData.getBeginTime() > 0);
    Assert.assertTrue(sagaData.getEndTime() > 0);
    Assert.assertTrue(sagaData.getEndTime() > sagaData.getBeginTime());
    Assert.assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(),TxState.COMMITTED);
    Assert.assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(),TxState.COMMITTED);
    Assert.assertEquals(sagaData.getTxEntityMap().get(localTxId_3).getState(),TxState.COMMITTED);
  }

  @Test
  public void lastTxAbortedEventTest() {
    omegaEventSender.onConnected();
    final String globalTxId = UUID.randomUUID().toString();
    final String localTxId_1 = UUID.randomUUID().toString();
    final String localTxId_2 = UUID.randomUUID().toString();
    final String localTxId_3 = UUID.randomUUID().toString();
    omegaEventSender.getOmegaEventSagaSimulator().lastTxAbortedEvents(globalTxId, localTxId_1, localTxId_2, localTxId_3).stream().forEach( event -> {
      omegaEventSender.getBlockingStub().onTxEvent(event);
    });
    await().atMost(1, SECONDS).until(() -> omegaEventSender.getOmegaCallbacks() != null);
    await().atMost(1, SECONDS).until(() -> {
      SagaData sagaData = SagaDataExtension.SAGA_DATA_EXTENSION_PROVIDER.get(system).getSagaData(globalTxId);
      return sagaData !=null && sagaData.getLastState()==SagaActorState.COMPENSATED;
    });
    SagaData sagaData = SagaDataExtension.SAGA_DATA_EXTENSION_PROVIDER.get(system).getSagaData(globalTxId);
    Assert.assertEquals(sagaData.getLastState(),SagaActorState.COMPENSATED);
    Assert.assertEquals(sagaData.getTxEntityMap().size(),3);
    Assert.assertTrue(sagaData.getBeginTime() > 0);
    Assert.assertTrue(sagaData.getEndTime() > 0);
    Assert.assertTrue(sagaData.getEndTime() > sagaData.getBeginTime());
    Assert.assertEquals(sagaData.getTxEntityMap().get(localTxId_1).getState(),TxState.COMPENSATED);
    Assert.assertEquals(sagaData.getTxEntityMap().get(localTxId_2).getState(),TxState.COMPENSATED);
    Assert.assertEquals(sagaData.getTxEntityMap().get(localTxId_3).getState(),TxState.FAILED);
  }
}