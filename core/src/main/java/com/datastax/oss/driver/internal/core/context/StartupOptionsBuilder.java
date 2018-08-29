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

import com.datastax.oss.driver.api.core.MavenCoordinates;
import com.datastax.oss.driver.internal.core.DefaultMavenCoordinates;
import com.datastax.oss.protocol.internal.request.Startup;
import com.datastax.oss.protocol.internal.util.collection.NullAllowingImmutableMap;
import java.util.HashMap;
import java.util.Map;
import net.jcip.annotations.Immutable;

@Immutable
public class StartupOptionsBuilder {

  public static final String DRIVER_NAME_KEY = "DRIVER_NAME";
  public static final String DRIVER_VERSION_KEY = "DRIVER_VERSION";

  private static final MavenCoordinates MAVEN_COORDINATES =
      DefaultMavenCoordinates.buildFromResource(
          StartupOptionsBuilder.class.getResource("/com/datastax/oss/driver/Driver.properties"));

  private final InternalDriverContext context;
  private final Map<String, String> additionalOptions = new HashMap<>();

  public StartupOptionsBuilder(InternalDriverContext context) {
    this.context = context;
  }

  /**
   * Adds additional startup options to the builder.
   *
   * <p>The additional options set here should NOT include DRIVER_NAME, DRIVER_VERSION, CQL_VERSION
   * or COMPRESSION as those are derived from the driver itself.
   *
   * @param additionalOptions Extra options to send in a Startup message.
   */
  public StartupOptionsBuilder withAdditionalOptions(Map<String, String> additionalOptions) {
    if (additionalOptions != null) {
      additionalOptions
          .entrySet()
          .forEach(
              (entry) -> {
                String additionalOptionKey = entry.getKey();
                String additionalOptionValue = entry.getValue();
                // only add non-internal options
                if (!DRIVER_NAME_KEY.equals(additionalOptionKey)
                    && !DRIVER_VERSION_KEY.equals(additionalOptionKey)
                    && !Startup.COMPRESSION_KEY.equals(additionalOptionKey)
                    && !Startup.CQL_VERSION_KEY.equals(additionalOptionKey)) {
                  this.additionalOptions.put(additionalOptionKey, additionalOptionValue);
                }
              });
    }
    return this;
  }

  /**
   * Builds a map of options to send in a Startup message.
   *
   * <p>The default set of options are built here and include {@link
   * com.datastax.oss.protocol.internal.request.Startup#COMPRESSION_KEY} (if the context passed in
   * has a compressor/algorithm set), and the driver's {@link #DRIVER_NAME_KEY} and {@link
   * #DRIVER_VERSION_KEY}. The {@link com.datastax.oss.protocol.internal.request.Startup}
   * constructor will add {@link
   * com.datastax.oss.protocol.internal.request.Startup#CQL_VERSION_KEY}.
   *
   * <p>Additional options can be set via {@link #withAdditionalOptions(java.util.Map)}.
   *
   * @return Map of Startup Options.
   */
  public Map<String, String> build() {
    NullAllowingImmutableMap.Builder<String, String> builder =
        NullAllowingImmutableMap.builder(3 + additionalOptions.size());
    // add compression option
    String compressionAlgorithm = context.getCompressor().algorithm();
    if (compressionAlgorithm != null && !compressionAlgorithm.trim().isEmpty()) {
      builder.put(Startup.COMPRESSION_KEY, compressionAlgorithm.trim());
    }
    // add driver name and version
    builder.put(DRIVER_NAME_KEY, getDriverName()).put(DRIVER_VERSION_KEY, getDriverVersion());
    // add any additonal options
    return builder.putAll(additionalOptions).build();
  }

  /**
   * Returns this driver's name.
   *
   * <p>By default, this method will pull from the bundled Driver.properties file. Subclasses should
   * override this method if they need to report a different Driver name on Startup.
   *
   * <p><b>NOTE:</b> The Driver name can not be set via {@link
   * #withAdditionalOptions(java.util.Map)}
   */
  protected String getDriverName() {
    return MAVEN_COORDINATES.getName();
  }

  /**
   * Returns this driver's version.
   *
   * <p>By default, this method will pull from the bundled Driver.properties file. Subclasses should
   * override this method if they need to report a different Driver version on Startup.
   *
   * <p><b>NOTE:</b> The Driver version can not be set via {@link
   * #withAdditionalOptions(java.util.Map)}
   */
  protected String getDriverVersion() {
    return MAVEN_COORDINATES.getVersion().toString();
  }
}
