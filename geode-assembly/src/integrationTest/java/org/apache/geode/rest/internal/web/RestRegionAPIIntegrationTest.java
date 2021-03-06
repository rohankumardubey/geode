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
package org.apache.geode.rest.internal.web;

import static java.lang.System.lineSeparator;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.apache.geode.cache.Region.SEPARATOR;
import static org.apache.geode.cache.RegionShortcut.REPLICATE;
import static org.apache.geode.test.awaitility.GeodeAwaitility.getTimeout;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.http.MediaType;

import org.apache.geode.cache.CacheWriterException;
import org.apache.geode.cache.Region;
import org.apache.geode.rest.internal.web.controllers.Customer;
import org.apache.geode.test.junit.rules.ExecutorServiceRule;
import org.apache.geode.test.junit.rules.GeodeDevRestClient;
import org.apache.geode.test.junit.rules.RequiresGeodeHome;
import org.apache.geode.test.junit.rules.ServerStarterRule;

public class RestRegionAPIIntegrationTest {

  @SuppressWarnings("deprecation")
  private static final String APPLICATION_JSON_UTF8_VALUE = MediaType.APPLICATION_JSON_UTF8_VALUE;
  private static final List<String> JSON_DOCUMENTS = new ArrayList<>();

  private static GeodeDevRestClient restClient;

  @ClassRule
  public static RequiresGeodeHome requiresGeodeHome = new RequiresGeodeHome();

  @ClassRule
  public static ServerStarterRule server = new ServerStarterRule()
      .withRestService()
      .withPDXReadSerialized()
      .withRegion(REPLICATE, "regionA");

  @Rule
  public ExecutorServiceRule pool = new ExecutorServiceRule();

  @BeforeClass
  public static void setUpClass() throws URISyntaxException, IOException {
    restClient = new GeodeDevRestClient("localhost", server.getHttpPort(), false);
    initJsonDocuments();
  }

  @Before
  public void clearRegionA() {
    getRegionA().clear();
  }

  @Test
  public void getAllResources() {
    restClient.doGetAndAssert("")
        .hasStatusCode(HttpStatus.SC_OK)
        .hasContentType(APPLICATION_JSON_UTF8_VALUE)
        .hasResponseBody()
        .isEqualToIgnoringWhitespace(
            "{\"regions\":[{\"name\":\"regionA\",\"type\":\"REPLICATE\",\"key-constraint\":null,\"value-constraint\":null}]}");
  }

  @Test
  public void getRegionWhenEmpty() {
    restClient.doGetAndAssert("/regionA")
        .statusIsOk()
        .hasResponseBody()
        .isEqualToIgnoringWhitespace("{\"regionA\":[]}");
  }

  @Test
  public void getRegionAWhenHasData() throws CacheWriterException, IOException {
    Region<String, Customer> region = getRegionA();
    region.put("customer1", new Customer(1L, "jon", "doe", "123-456-789"));
    region.put("customer2", new Customer(2L, "jane", "doe", "123-456-999"));

    JsonNode jsonObject = restClient.doGetAndAssert("/regionA")
        .statusIsOk()
        .getJsonObject();

    assertThat(jsonObject.get("regionA")).hasSize(2);
  }

  @Test
  public void getSingleKey() throws CacheWriterException, IOException {
    Region<String, Customer> region = getRegionA();
    region.put("customer1", new Customer(1L, "jon", "doe", "123-456-789"));

    JsonNode jsonObject = restClient.doGetAndAssert("/regionA/customer1")
        .statusIsOk()
        .getJsonObject();

    System.out.println(jsonObject.toString());
    assertThat(jsonObject.get("firstName").asText()).isEqualTo("jon");
  }

  @Test
  public void putIntoRegionA() {
    for (int key = 0; key < JSON_DOCUMENTS.size(); key++) {
      restClient.doPutAndAssert("/regionA/" + key, JSON_DOCUMENTS.get(key)).statusIsOk();
    }

    assertThat(getRegionA()).hasSize(JSON_DOCUMENTS.size());
  }

  @Test
  public void postIntoRegionA() {
    for (int key = 0; key < JSON_DOCUMENTS.size(); key++) {
      restClient.doPostAndAssert("/regionA?key=" + key, JSON_DOCUMENTS.get(key)).statusIsOk();
    }

    assertThat(getRegionA()).hasSize(JSON_DOCUMENTS.size());
  }

  @Test
  public void putDuplicateWithoutReplace() {
    Region<String, Customer> region = getRegionA();
    region.put("customer1", new Customer(1L, "jon", "doe", "123-456-789"));

    // put with the same key and same value
    restClient.doPutAndAssert("/regionA/customer1",
        "{\"customerId\":1,\"firstName\":\"jon\",\"lastName\":\"doe\",\"socialSecurityNumber\":\"123-456-789\"}")
        .statusIsOk();

    assertThat(region).hasSize(1);

    // put the same key with a different value
    restClient.doPutAndAssert("/regionA/customer1",
        "{\"customerId\":1,\"firstName\":\"jane\",\"lastName\":\"doe\",\"socialSecurityNumber\":\"111-111-111\"}")
        .statusIsOk();

    assertThat(region).hasSize(1);

    // put with a different key
    restClient.doPutAndAssert("/regionA/customer2",
        "{\"customerId\":2,\"firstName\":\"jane\",\"lastName\":\"doe\",\"socialSecurityNumber\":\"111-111-111\"}")
        .statusIsOk();

    assertThat(region).hasSize(2);
  }

  @Test
  public void putDuplicateWithReplace() {
    Region<String, Customer> region = getRegionA();
    region.put("customer1", new Customer(1L, "jon", "doe", "123-456-789"));

    // replace with an existing key and different value
    restClient.doPutAndAssert("/regionA/customer1?op=REPLACE",
        "{\"customerId\":2,\"firstName\":\"jane\",\"lastName\":\"doe\",\"socialSecurityNumber\":\"123-456-789\"}")
        .statusIsOk();

    assertThat(region).hasSize(1);

    // trying to replace with a non-existent key
    restClient.doPutAndAssert("/regionA/customer2?op=REPLACE",
        "{\"customerId\":2,\"firstName\":\"jane\",\"lastName\":\"doe\",\"socialSecurityNumber\":\"123-456-789\"}")
        .hasStatusCode(HttpStatus.SC_NOT_FOUND);

    assertThat(region).hasSize(1);
  }

  @Test
  public void putWithCAS() {
    Region<?, ?> region = getRegionA();

    // do a regular put first
    restClient.doPutAndAssert("/regionA/customer1",
        "{\"customerId\":1,\"firstName\":\"jon\",\"lastName\":\"doe\"}")
        .statusIsOk();

    assertThat(region).hasSize(1);

    // invalid json doc in the request
    restClient.doPutAndAssert("/regionA/customer1?op=CAS",
        "{\"customerId\":1,\"firstName\":\"jon\",\"lastName\":\"doe\"}")
        .hasStatusCode(HttpStatus.SC_BAD_REQUEST);

    // put with CAS with existing key, but with different old value
    restClient.doPutAndAssert("/regionA/customer1?op=CAS",
        "{\"@old\":{\"customerId\":1,\"firstName\":\"jane\",\"lastName\":\"doe\"},"
            + "\"@new\":{\"customerId\":1,\"firstName\":\"mary\",\"lastName\":\"doe\"}}")
        .hasStatusCode(HttpStatus.SC_CONFLICT)
        .hasResponseBody()
        .isEqualToIgnoringWhitespace(
            "{\"customerId\":1,\"firstName\":\"jon\",\"lastName\":\"doe\"}");

    // put with CAS with different key, status is ok, but did not put a second entry in
    restClient.doPutAndAssert("/regionA/customer2?op=CAS",
        "{\"@old\":{\"customerId\":1,\"firstName\":\"jon\",\"lastName\":\"doe\"},"
            + "\"@new\":{\"customerId\":1,\"firstName\":\"mary\",\"lastName\":\"doe\"}}")
        .hasStatusCode(HttpStatus.SC_OK);

    assertThat(region).hasSize(1);

    // put with CAS successfully (with existing key, with correct old value)
    restClient.doPutAndAssert("/regionA/customer1?op=CAS",
        "{\"@old\":{\"customerId\":1,\"firstName\":\"jon\",\"lastName\":\"doe\"},"
            + "\"@new\":{\"customerId\":1,\"firstName\":\"jane\",\"lastName\":\"doe\"}}")
        .hasStatusCode(HttpStatus.SC_OK);

    assertThat(region).hasSize(1);
  }

  @Test
  public void deleteAll() {
    Region<String, Customer> region = getRegionA();
    region.put("1", new Customer(1L, "jon", "doe", "123-456-789"));
    region.put("2", new Customer(2L, "jane", "doe", "123-456-999"));
    region.put("3", new Customer(3L, "mary", "doe", "123-456-899"));

    restClient.doDeleteAndAssert("/regionA")
        .statusIsOk();

    assertThat(region).isEmpty();
  }

  @Test
  public void deleteWithKeys() {
    Region<String, Customer> region = getRegionA();
    region.put("1", new Customer(1L, "jon", "doe", "123-456-789"));
    region.put("2", new Customer(2L, "jane", "doe", "123-456-999"));
    region.put("3", new Customer(3L, "mary", "doe", "123-456-899"));

    restClient.doDeleteAndAssert("/regionA/1,2")
        .statusIsOk();

    assertThat(region).hasSize(1);
    assertThat(region.get("3")).isNotNull();
  }

  @Test
  public void deleteNonExistentKey() {
    restClient.doDeleteAndAssert("/regionA/1234")
        .hasStatusCode(HttpStatus.SC_NOT_FOUND);
  }

  @Test
  public void listKeys() throws CacheWriterException, IOException {
    Region<String, Customer> region = getRegionA();
    region.put("customer1", new Customer(1L, "jon", "doe", "123-456-789"));
    region.put("customer2", new Customer(2L, "jane", "doe", "123-456-999"));

    JsonNode jsonObject = restClient.doGetAndAssert("/regionA/keys")
        .statusIsOk()
        .getJsonObject();

    assertThat(jsonObject.get("keys").toString()).isEqualTo("[\"customer1\",\"customer2\"]");
  }

  @Test
  public void adhocQuery() throws IOException {
    restClient.doPutAndAssert("/regionA/1", DOCUMENT1).statusIsOk();
    restClient.doPutAndAssert("/regionA/2", DOCUMENT2).statusIsOk();
    restClient.doPutAndAssert("/regionA/3", DOCUMENT3).statusIsOk();

    String urlPrefix =
        "/queries/adhoc?q=" + URLEncoder.encode(
            "SELECT book.displayprice FROM " + SEPARATOR +
                "regionA e, e.store.book book WHERE book.displayprice > 5",
            "UTF-8");

    restClient.doGetAndAssert(urlPrefix)
        .statusIsOk()
        .hasJsonArrayOfDoubles()
        .hasSize(12)
        .containsExactlyInAnyOrder(18.95, 18.95, 8.99, 8.99, 8.99, 8.95, 22.99, 22.99, 22.99, 12.99,
            112.99, 112.99);
  }

  @Test
  public void preparedQuery() throws IOException {
    restClient.doPutAndAssert("/regionA/1", DOCUMENT1).statusIsOk();
    restClient.doPutAndAssert("/regionA/2", DOCUMENT2).statusIsOk();
    restClient.doPutAndAssert("/regionA/3", DOCUMENT3).statusIsOk();

    // create 5 prepared statements
    for (int i = 0; i < 5; i++) {
      String urlPrefix = "/queries/?id=" + "Query" + i + "&q=" + URLEncoder.encode(
          "SELECT book.displayprice FROM " + SEPARATOR
              + "regionA e, e.store.book book  WHERE book.displayprice > $1",
          "UTF-8");
      restClient.doPostAndAssert(urlPrefix, "").statusIsOk();
    }

    // get the list of defined queries and verify the size of them
    JsonNode jsonObject = restClient.doGetAndAssert("/queries")
        .statusIsOk()
        .getJsonObject();

    assertThat(jsonObject.get("queries")).hasSize(5);

    // execute each defined queries
    for (int i = 0; i < 5; i++) {
      restClient.doPostAndAssert("/queries/Query" + i, "[{\"@type\":\"double\",\"@value\":8.99}]")
          .statusIsOk()
          .hasJsonArrayOfDoubles()
          .hasSize(8)
          .containsExactlyInAnyOrder(18.95, 18.95, 22.99, 22.99, 22.99, 12.99, 112.99, 112.99);
    }
  }

  @Test
  public void concurrentRequests()
      throws ExecutionException, InterruptedException, TimeoutException {
    int entryCount = 20;
    Collection<Future<Void>> futures = new ArrayList<>();

    futures.add(pool.submit(() -> {
      for (int i = 0; i < entryCount; i++) {
        restClient.doPostAndAssert("/regionA?key=customer" + i,
            "{\"customerId\":" + i
                + ",\"firstName\":\"jon\",\"lastName\":\"doe\",\"socialSecurityNumber\":\"123-456-789\"}")
            .hasStatusCode(HttpStatus.SC_CREATED, HttpStatus.SC_CONFLICT);
      }
    }));

    futures.add(pool.submit(() -> {
      for (int i = 0; i < entryCount; i++) {
        restClient.doPutAndAssert("/regionA/customer" + i,
            "{\"customerId\":" + i
                + ",\"firstName\":\"jon\",\"lastName\":\"doe\",\"socialSecurityNumber\":\"123-456-789\"}")
            .hasStatusCode(HttpStatus.SC_OK)
            .hasResponseBody()
            .isEmpty();
      }
    }));

    futures.add(pool.submit(() -> {
      for (int i = 0; i < entryCount; i++) {
        restClient.doPutAndAssert("/regionA/customer" + i + "?op=REPLACE",
            "{\"customerId\":" + i
                + ",\"firstName\":\"jon\",\"lastName\":\"doe\",\"socialSecurityNumber\":\"123-456-789\"}")
            .hasStatusCode(HttpStatus.SC_OK, HttpStatus.SC_NOT_FOUND);
      }
    }));

    futures.add(pool.submit(() -> {
      for (int i = 0; i < entryCount; i++) {
        restClient.doGetAndAssert("/regionA/customer" + i)
            .hasStatusCode(HttpStatus.SC_OK, HttpStatus.SC_NOT_FOUND);
      }
    }));

    futures.add(pool.submit(() -> {
      for (int i = 0; i < entryCount; i++) {
        restClient.doDeleteAndAssert("/regionA/customer" + i)
            .hasStatusCode(HttpStatus.SC_OK, HttpStatus.SC_NOT_FOUND);
      }
    }));

    for (Future<Void> future : futures) {
      future.get(getTimeout().toMinutes(), MINUTES);
    }
  }

  private static void initJsonDocuments() throws URISyntaxException, IOException {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    URI uri = classLoader.getResource("sampleJson.json").toURI();

    String rawJson = IOUtils.toString(uri, Charset.defaultCharset());

    ObjectMapper mapper = new ObjectMapper();
    JsonNode json = mapper.readTree(rawJson);

    for (int i = 0; i < json.size(); i++) {
      JSON_DOCUMENTS.add(json.get(i).toString());
    }
  }

  private static <K, V> Region<K, V> getRegionA() {
    return server.getCache().getRegion(SEPARATOR + "regionA");
  }

  private static final String DOCUMENT1 =
      "{ \"store\": {" + lineSeparator() +
          "    \"book\": [ " + lineSeparator() +
          "      { \"category\": \"reference\"," + lineSeparator() +
          "        \"author\": \"Nigel Rees\"," + lineSeparator() +
          "        \"title\": \"Sayings of the Century\"," + lineSeparator() +
          "        \"displayprice\": 8.95" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"Evelyn Waugh\"," + lineSeparator() +
          "        \"title\": \"Sword of Honour\"," + lineSeparator() +
          "        \"displayprice\": 12.99" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"Herman Melville\"," + lineSeparator() +
          "        \"title\": \"Moby Dick\"," + lineSeparator() +
          "        \"isbn\": \"0-553-21311-3\"," + lineSeparator() +
          "        \"displayprice\": 8.99" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"J. R. R. Tolkien\"," + lineSeparator() +
          "        \"title\": \"The Lord of the Rings\"," + lineSeparator() +
          "        \"isbn\": \"0-395-19395-8\"," + lineSeparator() +
          "        \"displayprice\": 22.99" + lineSeparator() +
          "      }" + lineSeparator() +
          "    ]," + lineSeparator() +
          "    \"bicycle\": {" + lineSeparator() +
          "      \"color\": \"red\"," + lineSeparator() +
          "      \"displayprice\": 19.95," + lineSeparator() +
          "      \"foo:bar\": \"fooBar\"," + lineSeparator() +
          "      \"dot.notation\": \"new\"," + lineSeparator() +
          "      \"dash-notation\": \"dashes\"" + lineSeparator() +
          "    }" + lineSeparator() +
          "  }" + lineSeparator() +
          "}";

  private static final String DOCUMENT2 =
      "{ \"store\": {" + lineSeparator() +
          "    \"book\": [ " + lineSeparator() +
          "      { \"category\": \"reference\"," + lineSeparator() +
          "        \"author\": \"Nigel Rees\"," + lineSeparator() +
          "        \"title\": \"Sayings of the Century\"," + lineSeparator() +
          "        \"displayprice\": 18.95" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"Evelyn Waugh\"," + lineSeparator() +
          "        \"title\": \"Sword of Honour\"," + lineSeparator() +
          "        \"displayprice\": 112.99" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"Herman Melville\"," + lineSeparator() +
          "        \"title\": \"Moby Dick\"," + lineSeparator() +
          "        \"isbn\": \"0-553-21311-3\"," + lineSeparator() +
          "        \"displayprice\": 8.99" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"J. R. R. Tolkien\"," + lineSeparator() +
          "        \"title\": \"The Lord of the Rings\"," + lineSeparator() +
          "        \"isbn\": \"0-395-19395-8\"," + lineSeparator() +
          "        \"displayprice\": 22.99" + lineSeparator() +
          "      }" + lineSeparator() +
          "    ]," + lineSeparator() +
          "    \"bicycle\": {" + lineSeparator() +
          "      \"color\": \"red\"," + lineSeparator() +
          "      \"displayprice\": 19.95," + lineSeparator() +
          "      \"foo:bar\": \"fooBar\"," + lineSeparator() +
          "      \"dot.notation\": \"new\"," + lineSeparator() +
          "      \"dash-notation\": \"dashes\"" + lineSeparator() +
          "    }" + lineSeparator() +
          "  }" + lineSeparator() +
          "}";

  private static final String DOCUMENT3 =
      "{ \"store\": {" + lineSeparator() +
          "    \"book\": [ " + lineSeparator() +
          "      { \"category\": \"reference\"," + lineSeparator() +
          "        \"author\": \"Nigel Rees\"," + lineSeparator() +
          "        \"title\": \"Sayings of the Century\"," + lineSeparator() +
          "        \"displayprice\": 18.95" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"Evelyn Waugh\"," + lineSeparator() +
          "        \"title\": \"Sword of Honour\"," + lineSeparator() +
          "        \"displayprice\": 112.99" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"Herman Melville\"," + lineSeparator() +
          "        \"title\": \"Moby Dick\"," + lineSeparator() +
          "        \"isbn\": \"0-553-21311-3\"," + lineSeparator() +
          "        \"displayprice\": 8.99" + lineSeparator() +
          "      }," + lineSeparator() +
          "      { \"category\": \"fiction\"," + lineSeparator() +
          "        \"author\": \"J. R. R. Tolkien\"," + lineSeparator() +
          "        \"title\": \"The Lord of the Rings\"," + lineSeparator() +
          "        \"isbn\": \"0-395-19395-8\"," + lineSeparator() +
          "        \"displayprice\": 22.99" + lineSeparator() +
          "      }" + lineSeparator() +
          "    ]," + lineSeparator() +
          "    \"bicycle\": {" + lineSeparator() +
          "      \"color\": \"red\"," + lineSeparator() +
          "      \"displayprice\": 129.95," + lineSeparator() +
          "      \"foo:bar\": \"fooBar\"," + lineSeparator() +
          "      \"dot.notation\": \"new\"," + lineSeparator() +
          "      \"dash-notation\": \"dashes\"" + lineSeparator() +
          "    }" + lineSeparator() +
          "  }" + lineSeparator() +
          "}";
}
