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

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.pipe.connector.payload.request.IoTDBConnectorRequestVersion;
import org.apache.iotdb.commons.pipe.connector.payload.request.PipeRequestType;
import org.apache.iotdb.commons.pipe.connector.payload.request.PipeTransferFileSealReq;
import org.apache.iotdb.commons.pipe.receiver.IoTDBFileReceiverV1;
import org.apache.iotdb.confignode.conf.ConfigNodeDescriptor;
import org.apache.iotdb.confignode.consensus.request.ConfigPhysicalPlan;
import org.apache.iotdb.confignode.consensus.request.write.database.DeleteDatabasePlan;
import org.apache.iotdb.confignode.consensus.request.write.pipe.PipeEnrichedPlan;
import org.apache.iotdb.confignode.consensus.request.write.template.CommitSetSchemaTemplatePlan;
import org.apache.iotdb.confignode.consensus.request.write.trigger.DeleteTriggerInTablePlan;
import org.apache.iotdb.confignode.manager.ConfigManager;
import org.apache.iotdb.confignode.manager.pipe.transfer.connector.payload.request.PipeTransferConfigHandshakeReq;
import org.apache.iotdb.confignode.manager.pipe.transfer.connector.payload.request.PipeTransferConfigPlanReq;
import org.apache.iotdb.confignode.manager.pipe.transfer.connector.payload.request.PipeTransferConfigSnapshotPieceReq;
import org.apache.iotdb.confignode.manager.pipe.transfer.connector.payload.request.PipeTransferConfigSnapshotSealReq;
import org.apache.iotdb.confignode.rpc.thrift.TDeleteDatabasesReq;
import org.apache.iotdb.confignode.rpc.thrift.TDropTriggerReq;
import org.apache.iotdb.confignode.rpc.thrift.TSetSchemaTemplateReq;
import org.apache.iotdb.consensus.exception.ConsensusException;
import org.apache.iotdb.db.pipe.connector.payload.airgap.AirGapPseudoTPipeTransferRequest;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.service.rpc.thrift.TPipeTransferReq;
import org.apache.iotdb.service.rpc.thrift.TPipeTransferResp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

public class IoTDBConfigReceiverV1 extends IoTDBFileReceiverV1 {
  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBConfigReceiverV1.class);
  private static final AtomicInteger queryIndex = new AtomicInteger(0);

  private final ConfigManager configManager;

  IoTDBConfigReceiverV1(ConfigManager configManager) {
    this.configManager = configManager;
  }

  @Override
  public IoTDBConnectorRequestVersion getVersion() {
    return IoTDBConnectorRequestVersion.VERSION_1;
  }

  @Override
  public TPipeTransferResp receive(TPipeTransferReq req) {
    try {
      final short rawRequestType = req.getType();
      if (PipeRequestType.isValidatedRequestType(rawRequestType)) {
        switch (PipeRequestType.valueOf(rawRequestType)) {
          case CONFIGNODE_HANDSHAKE:
            return handleTransferHandshake(
                PipeTransferConfigHandshakeReq.fromTPipeTransferReq(req));
          case TRANSFER_CONFIG_PLAN:
            return handleTransferConfigPlan(PipeTransferConfigPlanReq.fromTPipeTransferReq(req));
          case TRANSFER_CONFIG_SNAPSHOT_PIECE:
            return handleTransferFilePiece(
                PipeTransferConfigSnapshotPieceReq.fromTPipeTransferReq(req),
                req instanceof AirGapPseudoTPipeTransferRequest);
          case TRANSFER_CONFIG_SNAPSHOT_SEAL:
            return handleTransferFileSeal(
                PipeTransferConfigSnapshotSealReq.fromTPipeTransferReq(req));
          default:
            break;
        }
      }

      // Unknown request type, which means the request can not be handled by this receiver,
      // maybe the version of the receiver is not compatible with the sender
      final TSStatus status =
          RpcUtils.getStatus(
              TSStatusCode.PIPE_TYPE_ERROR,
              String.format("Unsupported PipeRequestType on ConfigNode %s.", rawRequestType));
      LOGGER.warn("Unsupported PipeRequestType on ConfigNode, response status = {}.", status);
      return new TPipeTransferResp(status);
    } catch (IOException | ConsensusException e) {
      String error = String.format("Serialization error during pipe receiving, %s", e);
      LOGGER.warn(error);
      return new TPipeTransferResp(RpcUtils.getStatus(TSStatusCode.PIPE_ERROR, error));
    }
  }

  private TPipeTransferResp handleTransferConfigPlan(PipeTransferConfigPlanReq req)
      throws IOException, ConsensusException {
    ConfigPhysicalPlan plan = ConfigPhysicalPlan.Factory.create(req.body);
    switch (plan.getType()) {
      case DeleteDatabase:
        return new TPipeTransferResp(
            configManager.deleteDatabases(
                new TDeleteDatabasesReq(
                        Collections.singletonList(((DeleteDatabasePlan) plan).getName()))
                    .setIsGeneratedByPipe(true)));
      case CommitSetSchemaTemplate:
        return new TPipeTransferResp(
            configManager.setSchemaTemplate(
                new TSetSchemaTemplateReq(
                        getPseudoQueryId(),
                        ((CommitSetSchemaTemplatePlan) plan).getName(),
                        ((CommitSetSchemaTemplatePlan) plan).getPath())
                    .setIsGeneratedByPipe(true)));
      case UnsetTemplate:
        // TODO: Sender send name to receiver and execute directly
        return new TPipeTransferResp(new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode()));
      case UpdateTriggerStateInTable:
        // TODO: Record complete message in trigger
        return new TPipeTransferResp(new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode()));
      case DeleteTriggerInTable:
        return new TPipeTransferResp(
            configManager.dropTrigger(
                new TDropTriggerReq(((DeleteTriggerInTablePlan) plan).getTriggerName())
                    .setIsGeneratedByPipe(true)));
      default:
        return new TPipeTransferResp(
            configManager.getConsensusManager().write(new PipeEnrichedPlan(plan)));
    }
  }

  // Used to construct pipe related procedures
  private String getPseudoQueryId() {
    return "pipe" + System.currentTimeMillis() + queryIndex.getAndIncrement();
  }

  @Override
  protected String getReceiverFileBaseDir() {
    return ConfigNodeDescriptor.getInstance().getConf().getPipeReceiverFileDir();
  }

  @Override
  protected TSStatus loadFile(PipeTransferFileSealReq req, String fileAbsolutePath) {
    // TODO
    return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
  }
}
