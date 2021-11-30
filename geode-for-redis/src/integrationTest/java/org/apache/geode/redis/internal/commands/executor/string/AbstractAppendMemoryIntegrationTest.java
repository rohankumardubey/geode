/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.redis.internal.commands.executor.string;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import org.apache.geode.redis.RedisIntegrationTest;
import org.apache.geode.redis.RedisTestHelper;
import org.apache.geode.test.awaitility.GeodeAwaitility;

public abstract class AbstractAppendMemoryIntegrationTest implements RedisIntegrationTest {
  private Jedis jedis;
  private static final int REDIS_CLIENT_TIMEOUT =
      Math.toIntExact(GeodeAwaitility.getTimeout().toMillis());

  @Before
  public void setUp() {
    jedis = new Jedis("localhost", getPort(), REDIS_CLIENT_TIMEOUT);
  }

  @After
  public void flushAll() {
    jedis.flushAll();
    jedis.close();
  }

  @Test
  public void testAppend_actuallyIncreasesBucketSize() {
    int listSize = 100_000;
    String key = "key";

    System.gc();
    Long startingMemValue = getUsedMemory(jedis);

    jedis.set(key, "initial");
    for (int i = 0; i < listSize; i++) {
      jedis.append(key, "morestuff");
    }

    GeodeAwaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(getUsedMemory(jedis)).isGreaterThan(startingMemValue));
  }

  private Long getUsedMemory(Jedis jedis) {
    Map<String, String> info = RedisTestHelper.getInfo(jedis);
    return Long.valueOf(info.get("used_memory"));
  }

}
