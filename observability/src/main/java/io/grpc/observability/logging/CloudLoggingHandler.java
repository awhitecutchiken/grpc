/*
 * Copyright 2022 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.observability.logging;

import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload.JsonPayload;
import com.google.cloud.logging.Severity;
import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Internal;
import io.grpc.internal.JsonParser;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Custom logging handler that outputs logs generated using {@link java.util.logging.Logger} to
 * Cloud Logging.
 */
@Internal
public class CloudLoggingHandler extends Handler {

  private static final String DEFAULT_LOG_NAME = "grpc-observability";
  private static final Level DEFAULT_LOG_LEVEL = Level.ALL;

  private LoggingOptions loggingOptions;
  private volatile Logging logging;
  private final Level baseLevel;
  private final String cloudLogName;

  /**
   * Creates a custom logging handler that publishes message to Cloud logging. Default log level is
   * set to Level.FINEST if level is not passed.
   */
  public CloudLoggingHandler() {
    this(DEFAULT_LOG_LEVEL, null, null);
  }

  /**
   * Creates a custom logging handler that publishes message to Cloud logging.
   *
   * @param level set the level for which message levels will be logged by the custom logger
   */
  public CloudLoggingHandler(Level level) {
    this(level, null, null);
  }

  /**
   * Creates a custom logging handler that publishes message to Cloud logging.
   *
   * @param level set the level for which message levels will be logged by the custom logger
   * @param logName the name of the log to which log entries are written
   */
  public CloudLoggingHandler(Level level, String logName) {
    this(level, logName, null);
  }

  /**
   * Creates a custom logging handler that publishes message to Cloud logging.
   *
   * @param level set the level for which message levels will be logged by the custom logger
   * @param logName the name of the log to which log entries are written
   * @param overrideProjectId the value of cloud project id to which logs are sent to by the custom
   *     logger
   */
  public CloudLoggingHandler(Level level, String logName, String overrideProjectId) {
    baseLevel = level.equals(DEFAULT_LOG_LEVEL) ? Level.FINEST : level;
    setLevel(baseLevel);

    cloudLogName = logName != null ? logName : DEFAULT_LOG_NAME;

    try {
      // TODO(dnvindhya) read the value from config instead of taking it as an argument
      String projectId = overrideProjectId != null ? overrideProjectId : null;

      if (!Strings.isNullOrEmpty(projectId)) {
        loggingOptions = LoggingOptions.newBuilder().setProjectId(overrideProjectId).build();
      } else {
        loggingOptions = LoggingOptions.getDefaultInstance();
      }

      logging = loggingOptions.getService();
    } catch (Exception ex) {
      reportError("Error in initializing Cloud Logging", ex, ErrorManager.OPEN_FAILURE);
    }
  }

  @Override
  public void publish(LogRecord record) {
    if (!(record instanceof LogRecordExtension)) {
      throw new IllegalArgumentException("Expected record of type LogRecordExtension");
    }

    Level logLevel = record.getLevel();
    GrpcLogRecord protoRecord = ((LogRecordExtension) record).getGrpcLogRecord();

    writeLog(protoRecord, logLevel);
  }

  private void writeLog(GrpcLogRecord logProto, Level logLevel) {
    try {
      Severity cloudLogLevel = getCloudLoggingLevel(logLevel);
      Map<String, Object> mapPayload = protoToMapConverter(logProto);

      LogEntry grpcLogEntry =
          LogEntry.newBuilder(JsonPayload.of(mapPayload))
              .setSeverity(cloudLogLevel)
              .setLogName(cloudLogName)
              .setResource(MonitoredResource.newBuilder("global").build())
              .build();

      logging.write(Collections.singleton(grpcLogEntry));
    } catch (Exception ex) {
      getErrorManager().error("Error in writing logs to cloud logging", ex,
          ErrorManager.WRITE_FAILURE);
    }
  }

  /* Google Cloud Logging API only accepts String payload or Json Payload
  Adding all the grpc o11y logging values as string makes it difficult to look up or index
  Hence we are going with the Json Payload approach
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> protoToMapConverter(GrpcLogRecord logProto)
      throws InvalidProtocolBufferException, IOException {
    JsonFormat.Printer printer = JsonFormat.printer().preservingProtoFieldNames();
    String recordJson = printer.print(logProto);

    return (Map<String, Object>) JsonParser.parse(recordJson);
  }

  @Override
  public void flush() {
    logging.flush();
  }

  @Override
  public synchronized void close() throws SecurityException {
    if (logging != null) {
      try {
        logging.close();
      } catch (Exception ex) {
        getErrorManager().error("Error in closing cloud logging handler", ex,
            ErrorManager.CLOSE_FAILURE);
      }
    }
  }

  private Severity getCloudLoggingLevel(Level recordLevel) {
    switch (recordLevel.intValue()) {
      case 300: // FINEST
      case 400: // FINER
      case 500: // FINE
        return Severity.DEBUG;
      case 700: // CONFIG
      case 800: // INFO
        return Severity.INFO;
      case 900: // WARNING
        return Severity.WARNING;
      case 1000: // SEVERE
        return Severity.ERROR;
      default:
        return Severity.DEFAULT;
    }
  }
}
