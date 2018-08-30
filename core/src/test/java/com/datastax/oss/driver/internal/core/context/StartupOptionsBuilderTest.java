/*
 * Copyright DataStax, Inc.
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
package com.datastax.oss.driver.internal.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.MavenCoordinates;
import com.datastax.oss.driver.api.core.Version;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfig;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.NodeStateListener;
import com.datastax.oss.driver.api.core.metadata.schema.SchemaChangeListener;
import com.datastax.oss.driver.api.core.tracker.RequestTracker;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.internal.core.DefaultMavenCoordinates;
import com.datastax.oss.driver.shaded.guava.common.collect.Lists;
import com.datastax.oss.driver.shaded.guava.common.collect.Maps;
import com.datastax.oss.protocol.internal.request.Startup;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class StartupOptionsBuilderTest {

  private final MavenCoordinates driverProperties =
      DefaultMavenCoordinates.buildFromResource(
          StartupOptionsBuilderTest.class.getResource(
              "/com/datastax/oss/driver/Driver.properties"));

  private DefaultDriverContext defaultDriverContext;

  // Mocks for instantiating the default driver context
  @Mock private DriverConfigLoader configLoader;
  List<TypeCodec<?>> typeCodecs = Lists.newArrayList();
  @Mock private NodeStateListener nodeStateListener;
  @Mock private SchemaChangeListener schemaChangeListener;
  @Mock private RequestTracker requestTracker;
  Map<String, Predicate<Node>> nodeFilters = Maps.newHashMap();
  @Mock private ClassLoader classLoader;
  @Mock private DriverConfig driverConfig;
  @Mock private DriverExecutionProfile defaultProfile;

  @Before
  public void before() {
    MockitoAnnotations.initMocks(this);
    Mockito.when(configLoader.getInitialConfig()).thenReturn(driverConfig);
    Mockito.when(driverConfig.getDefaultProfile()).thenReturn(defaultProfile);
    defaultDriverContext =
        new DefaultDriverContext(
            configLoader,
            typeCodecs,
            nodeStateListener,
            schemaChangeListener,
            requestTracker,
            nodeFilters,
            classLoader);
  }

  private void assertDefaultStartupOptions(Startup startup) {
    assertThat(startup.options).containsEntry(Startup.CQL_VERSION_KEY, "3.0.0");
    assertThat(startup.options)
        .containsEntry(StartupOptionsBuilder.DRIVER_NAME_KEY, driverProperties.getName());
    assertThat(startup.options).containsKey(StartupOptionsBuilder.DRIVER_VERSION_KEY);
    Version version = Version.parse(startup.options.get(StartupOptionsBuilder.DRIVER_VERSION_KEY));
    assertThat(version).isEqualByComparingTo(driverProperties.getVersion());
  }

  @Test
  public void should_build_minimal_startup_options() {
    Startup startup = new Startup(defaultDriverContext.getStartupOptions());
    assertThat(startup.options).doesNotContainKey(Startup.COMPRESSION_KEY);
    assertDefaultStartupOptions(startup);
  }

  @Test
  public void should_build_startup_options_with_compression() {
    Mockito.when(defaultProfile.isDefined(DefaultDriverOption.PROTOCOL_COMPRESSION))
        .thenReturn(Boolean.TRUE);
    Mockito.when(defaultProfile.getString(DefaultDriverOption.PROTOCOL_COMPRESSION))
        .thenReturn("lz4");
    Startup startup = new Startup(defaultDriverContext.getStartupOptions());
    // assert the compression option is present
    assertThat(startup.options).containsEntry(Startup.COMPRESSION_KEY, "lz4");
    assertDefaultStartupOptions(startup);
  }
}
