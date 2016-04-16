/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.rest;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.logging.LoggerMessageFormat;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.plugins.TestDeprecatedQueryBuilder;
import org.elasticsearch.rest.plugins.TestDeprecationHeaderRestAction;
import org.elasticsearch.rest.plugins.TestDeprecationPlugin;
import org.elasticsearch.test.ESIntegTestCase;

import org.hamcrest.Matcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.rest.plugins.TestDeprecationHeaderRestAction.TEST_DEPRECATED_SETTING_TRUE1;
import static org.elasticsearch.rest.plugins.TestDeprecationHeaderRestAction.TEST_DEPRECATED_SETTING_TRUE2;
import static org.elasticsearch.rest.plugins.TestDeprecationHeaderRestAction.TEST_NOT_DEPRECATED_SETTING;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Tests {@code DeprecationLogger} uses the {@code ThreadContext} to add response headers.
 */
public class DeprecationHttpIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("force.http.enabled", true)
                // change values of deprecated settings so that accessing them is logged
                .put(TEST_DEPRECATED_SETTING_TRUE1.getKey(), ! TEST_DEPRECATED_SETTING_TRUE1.getDefault(Settings.EMPTY))
                .put(TEST_DEPRECATED_SETTING_TRUE2.getKey(), ! TEST_DEPRECATED_SETTING_TRUE2.getDefault(Settings.EMPTY))
                // non-deprecated setting to ensure not everything is logged
                .put(TEST_NOT_DEPRECATED_SETTING.getKey(), ! TEST_NOT_DEPRECATED_SETTING.getDefault(Settings.EMPTY))
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(TestDeprecationPlugin.class);
    }

    /**
     * Attempts to do a scatter/gather request that expects unique responses per sub-request.
     */
    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/19222")
    public void testUniqueDeprecationResponsesMergedTogether() throws IOException {
        final String[] indices = new String[randomIntBetween(2, 5)];

        // add at least one document for each index
        for (int i = 0; i < indices.length; ++i) {
            indices[i] = "test" + i;

            // create indices with a single shard to reduce noise; the query only deprecates uniquely by index anyway
            assertTrue(prepareCreate(indices[i]).setSettings(Settings.builder().put("number_of_shards", 1)).get().isAcknowledged());

            int randomDocCount = randomIntBetween(1, 2);

            for (int j = 0; j < randomDocCount; ++j) {
                index(indices[i], "type", Integer.toString(j), "{\"field\":" + j + "}");
            }
        }

        refresh(indices);

        final String commaSeparatedIndices = Stream.of(indices).collect(Collectors.joining(","));

        final String body =
            "{\"query\":{\"bool\":{\"filter\":[{\"" + TestDeprecatedQueryBuilder.NAME +  "\":{}}]}}}";

        // trigger all index deprecations
        try (Response response = getRestClient().performRequest("GET",
                                                                "/" + commaSeparatedIndices + "/_search",
                                                                Collections.emptyMap(),
                                                                new StringEntity(body, RestClient.JSON_CONTENT_TYPE))) {
            assertThat(response.getStatusLine().getStatusCode(), equalTo(OK.getStatus()));

            final List<String> deprecatedWarnings = getWarningHeaders(response.getHeaders());
            final List<Matcher<String>> headerMatchers = new ArrayList<>(indices.length);

            for (String index : indices) {
                headerMatchers.add(containsString(LoggerMessageFormat.format("[{}] index", (Object)index)));
            }

            assertThat(deprecatedWarnings, hasSize(headerMatchers.size()));
            for (Matcher<String> headerMatcher : headerMatchers) {
                assertThat(deprecatedWarnings, hasItem(headerMatcher));
            }
        }
    }

    public void testDeprecationWarningsAppearInHeaders() throws IOException {
        doTestDeprecationWarningsAppearInHeaders();
    }

    public void testDeprecationHeadersDoNotGetStuck() throws IOException {
        doTestDeprecationWarningsAppearInHeaders();
        doTestDeprecationWarningsAppearInHeaders();
        if (rarely()) {
            doTestDeprecationWarningsAppearInHeaders();
        }
    }

    /**
     * Run a request that receives a predictably randomized number of deprecation warnings.
     * <p>
     * Re-running this back-to-back helps to ensure that warnings are not being maintained across requests.
     */
    private void doTestDeprecationWarningsAppearInHeaders() throws IOException {
        final boolean useDeprecatedField = randomBoolean();
        final boolean useNonDeprecatedSetting = randomBoolean();

        // deprecated settings should also trigger a deprecation warning
        final List<Setting<Boolean>> settings = new ArrayList<>(3);
        settings.add(TEST_DEPRECATED_SETTING_TRUE1);

        if (randomBoolean()) {
            settings.add(TEST_DEPRECATED_SETTING_TRUE2);
        }

        if (useNonDeprecatedSetting) {
            settings.add(TEST_NOT_DEPRECATED_SETTING);
        }

        Collections.shuffle(settings, random());

        // trigger all deprecations
        try (Response response = getRestClient().performRequest("GET",
                                                                "/_test_cluster/deprecated_settings",
                                                                Collections.emptyMap(),
                                                                buildSettingsRequest(settings, useDeprecatedField))) {
            assertThat(response.getStatusLine().getStatusCode(), equalTo(OK.getStatus()));

            final List<String> deprecatedWarnings = getWarningHeaders(response.getHeaders());
            final List<Matcher<String>> headerMatchers = new ArrayList<>(4);

            headerMatchers.add(equalTo(TestDeprecationHeaderRestAction.DEPRECATED_ENDPOINT));
            if (useDeprecatedField) {
                headerMatchers.add(equalTo(TestDeprecationHeaderRestAction.DEPRECATED_USAGE));
            }
            for (Setting<?> setting : settings) {
                if (setting.isDeprecated()) {
                    headerMatchers.add(containsString(LoggerMessageFormat.format("[{}] setting was deprecated", (Object)setting.getKey())));
                }
            }

            assertThat(deprecatedWarnings, hasSize(headerMatchers.size()));
            for (Matcher<String> headerMatcher : headerMatchers) {
                assertThat(deprecatedWarnings, hasItem(headerMatcher));
            }
        }
    }

    private List<String> getWarningHeaders(Header[] headers) {
        List<String> warnings = new ArrayList<>();

        for (Header header : headers) {
            if (header.getName().equals("Warning")) {
                warnings.add(header.getValue());
            }
        }

        return warnings;
    }

    private HttpEntity buildSettingsRequest(List<Setting<Boolean>> settings, boolean useDeprecatedField) throws IOException {
        XContentBuilder builder = JsonXContent.contentBuilder();

        builder.startObject().startArray(useDeprecatedField ? "deprecated_settings" : "settings");

        for (Setting<Boolean> setting : settings) {
            builder.value(setting.getKey());
        }

        builder.endArray().endObject();

        return new StringEntity(builder.string(), RestClient.JSON_CONTENT_TYPE);
    }

}
