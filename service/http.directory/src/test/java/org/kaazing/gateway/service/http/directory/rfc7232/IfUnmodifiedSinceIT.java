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
package org.kaazing.gateway.service.http.directory.rfc7232;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.rules.RuleChain.outerRule;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.kaazing.gateway.server.test.GatewayRule;
import org.kaazing.gateway.server.test.config.GatewayConfiguration;
import org.kaazing.gateway.server.test.config.builder.GatewayConfigurationBuilder;
import org.kaazing.k3po.junit.annotation.Specification;
import org.kaazing.k3po.junit.rules.K3poRule;

/**
 * RFC-7232, section 3.4 "If-Unmodified-Since"
 */
public class IfUnmodifiedSinceIT {
    private final K3poRule k3po =
            new K3poRule().setScriptRoot("org/kaazing/specification/http/rfc7232/preconditions/if.unmodified.since");
    private final TestRule timeout = new DisableOnDebug(new Timeout(5, SECONDS));
    private static final String DIRECTORY_SERVICE_ACCEPT = "http://localhost:8000";

    private final GatewayRule gateway = new GatewayRule() {
        {
            // @formatter:off
            GatewayConfiguration configuration =
                    new GatewayConfigurationBuilder()
                        .webRootDirectory(new File("src/test/webapp"))
                        .service()
                            .accept(DIRECTORY_SERVICE_ACCEPT)
                            .type("directory")
                            .property("directory", "/public")
                            .property("welcome-file", "index.html")
                        .done()
                    .done();
            // @formatter:on
            init(configuration);
        }
    };

    @Rule
    public final TestRule chain = outerRule(timeout).around(k3po).around(gateway);

    @Test
    @Specification("ignored.with.get/request")
    public void shouldIgnoreIfUnmodifiedSinceHeaderWithGet() throws Exception {
        k3po.finish();
    }

    @Test
    @Specification("ignored.with.head/request")
    public void shouldIgnoreIfUnmodifiedSinceHeaderWithHead() throws Exception {
        k3po.finish();
    }

}
