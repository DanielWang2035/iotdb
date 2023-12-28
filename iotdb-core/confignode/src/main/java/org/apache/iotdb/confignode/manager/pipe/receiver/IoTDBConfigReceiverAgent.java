/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.confignode.manager.pipe.receiver;

import org.apache.iotdb.commons.pipe.connector.payload.request.IoTDBConnectorRequestVersion;
import org.apache.iotdb.commons.pipe.receiver.IoTDBReceiverAgent;
import org.apache.iotdb.confignode.manager.ConfigManager;

public class IoTDBConfigReceiverAgent extends IoTDBReceiverAgent {

  private final ConfigManager configManager;

  public IoTDBConfigReceiverAgent(ConfigManager configManager) {
    this.configManager = configManager;
  }

  @Override
  protected void initConstructors() {
    RECEIVER_CONSTRUCTORS.put(
        IoTDBConnectorRequestVersion.VERSION_1.getVersion(),
        () -> new IoTDBConfigReceiverV1(configManager));
  }
}
