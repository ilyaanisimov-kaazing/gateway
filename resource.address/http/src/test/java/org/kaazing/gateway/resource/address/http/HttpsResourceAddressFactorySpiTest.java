/**
 * Copyright 2007-2015, Kaazing Corporation. All rights reserved.
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
package org.kaazing.gateway.resource.address.http;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.kaazing.gateway.resource.address.ResourceAddress.NEXT_PROTOCOL;
import static org.kaazing.gateway.resource.address.ResourceAddress.QUALIFIER;
import static org.kaazing.gateway.resource.address.ResourceAddress.TRANSPORT;
import static org.kaazing.gateway.resource.address.ResourceAddress.TRANSPORT_URI;
import static org.kaazing.gateway.resource.address.http.HttpResourceAddress.KEEP_ALIVE_TIMEOUT;
import static org.kaazing.gateway.resource.address.http.HttpResourceAddress.REALM_NAME;
import static org.kaazing.gateway.resource.address.http.HttpResourceAddress.REQUIRED_ROLES;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.kaazing.gateway.resource.address.ResourceAddress;
import org.kaazing.gateway.resource.address.ResourceAddressFactory;
import org.kaazing.gateway.resource.address.ResourceOption;
import org.kaazing.gateway.resource.address.ssl.SslResourceAddress;
import org.kaazing.gateway.resource.address.uri.URIUtils;

public class HttpsResourceAddressFactorySpiTest {

    private HttpsResourceAddressFactorySpi addressFactorySpi;
    private String addressURI;
    private Map<String, Object> options;

    @Before
    public void before() {
        addressFactorySpi = new HttpsResourceAddressFactorySpi();
        addressURI = "https://localhost:2020/";
        options = new HashMap<>();
        options.put("http.nextProtocol", "custom");
        options.put("http.qualifier", "random");
        options.put("http.keepAliveTimeout", (int) SECONDS.toMillis(5));
        options.put("http.realmName", "demo");
        options.put("http.requiredRoles", new String[] { "admin" });
        options.put("http.transport", "ssl://localhost:2121");
    }

    @Test
    public void shouldHaveHttpsSchemeName() throws Exception {
        assertEquals("https", addressFactorySpi.getSchemeName());
    }

    @Test (expected = IllegalArgumentException.class)
    public void shouldRequireHttpsSchemeName() throws Exception {
        addressFactorySpi.newResourceAddress("test://opaque");
    }

    @Test (expected = IllegalArgumentException.class)
    public void shouldRequireExplicitPath() throws Exception {
        addressFactorySpi.newResourceAddress("https://localhost:443");
    }

    @Test 
    public void shouldNotRequireExplicitPort() throws Exception {
        HttpResourceAddress address = addressFactorySpi.newResourceAddress("https://localhost/");
        URI location = address.getResource();
        assertEquals(location.getPort(), 443);
    }

    @Test
    public void shouldCreateAddressWithDefaultOptions() throws Exception {
        ResourceAddress address = addressFactorySpi.newResourceAddress(addressURI);
        assertNull(address.getOption(NEXT_PROTOCOL));
        assertNull(address.getOption(QUALIFIER));
        assertNull(address.getOption(TRANSPORT));
        assertEquals(address.getOption(KEEP_ALIVE_TIMEOUT).intValue(), 30);
        assertNull(address.getOption(REALM_NAME));
        assertEmpty(address.getOption(REQUIRED_ROLES));
    }

    @Test
    public void shouldCreateAddressWithOptions() {
        ResourceAddress address = addressFactorySpi.newResourceAddress(addressURI, options);
        assertEquals("custom", address.getOption(NEXT_PROTOCOL));
        assertEquals("random", address.getOption(QUALIFIER));
        assertNull(address.getOption(TRANSPORT));
        assertEquals(5000L, address.getOption(KEEP_ALIVE_TIMEOUT).longValue());
        assertEquals("demo", address.getOption(REALM_NAME));
        assertArrayEquals(new String[] { "admin" }, address.getOption(REQUIRED_ROLES));
    }

    @Test
    public void shouldCreateAddressWithDefaultTransport() throws Exception {
        ResourceAddressFactory addressFactory = ResourceAddressFactory.newResourceAddressFactory();
        ResourceAddress address = addressFactory.newResourceAddress(addressURI);
        assertNotNull(address.getOption(TRANSPORT_URI));
        assertEquals("ssl://localhost:2020", address.getOption(TRANSPORT_URI));
    }
    
    @Test
    public void shouldCreateAddressWithTransport() throws Exception {
        ResourceAddress address = addressFactorySpi.newResourceAddress(addressURI, options);
        assertNotNull(address.getOption(TRANSPORT_URI));
        assertEquals("ssl://localhost:2121", address.getOption(TRANSPORT_URI));
    }

    @Test
    public void shouldBeAbleToSetAMiddleLevelTransportOption()
            throws Exception {

        ResourceAddressFactory addressFactory = ResourceAddressFactory.newResourceAddressFactory();
        Map<String,Object> inputOptions = new LinkedHashMap<>();
        inputOptions.put("ssl.wantClientAuth", false);

        ResourceAddress address = addressFactory.newResourceAddress("https://localhost:4949/path", inputOptions);
        verifyTransport(address,  "ssl://localhost:4949");
        verifyTransportOptionValue(address.getTransport(), SslResourceAddress.WANT_CLIENT_AUTH, false);
    }

    private void assertEmpty(String[] objects) {
        if (objects != null) {
            assertEquals(0, objects.length);
        }
    }

    private void verifyTransport(ResourceAddress address,
                                 final String expectedTransportURI) {
        if (expectedTransportURI == null) {
            Assert.assertNull(address.getTransport());
        } else {
            String scheme = URIUtils.getScheme(expectedTransportURI);
            Assert.assertEquals(scheme, address.getTransport().getResource().getScheme());
            URI uriExpectedTransportURI = URI.create(expectedTransportURI);
            Assert.assertEquals(uriExpectedTransportURI, address.getTransport().getResource());
        }
    }

    private <T> void verifyTransportOptionValue(ResourceAddress address,
                                                ResourceOption<T> key,
                                                T o) {
        T actual = address.getOption(key);
        Assert.assertEquals(o, actual);
    }
    
}
