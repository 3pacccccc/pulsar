/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.instance.stats;

import com.google.common.collect.EvictingQueue;
import com.google.common.util.concurrent.RateLimiter;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import lombok.Getter;
import org.apache.pulsar.functions.proto.InstanceCommunication;

public class SinkStatsManager extends ComponentStatsManager {

    public static final String PULSAR_SINK_METRICS_PREFIX = "pulsar_sink_";

    /** Declare metric names. **/
    public static final String SYSTEM_EXCEPTIONS_TOTAL = "system_exceptions_total";
    public static final String SINK_EXCEPTIONS_TOTAL = "sink_exceptions_total";
    public static final String LAST_INVOCATION = "last_invocation";
    public static final String RECEIVED_TOTAL = "received_total";
    public static final String WRITTEN_TOTAL = "written_total";

    public static final String SYSTEM_EXCEPTIONS_TOTAL_1min = "system_exceptions_1min";
    public static final String SINK_EXCEPTIONS_TOTAL_1min = "sink_exceptions_1min";
    public static final String RECEIVED_TOTAL_1min = "received_1min";
    public static final String WRITTEN_TOTAL_1min = "written_1min";

    /** Declare Prometheus stats. **/

    private final Counter statTotalRecordsReceived;

    private final Counter statTotalSysExceptions;

    private final Counter statTotalSinkExceptions;

    private final Counter statTotalWritten;

    private final Gauge statlastInvocation;

    // windowed metrics
    private final Counter statTotalRecordsReceived1min;

    private final Counter statTotalSysExceptions1min;

    private final Counter statTotalSinkExceptions1min;

    private final Counter statTotalWritten1min;

    // exceptions

    final Gauge sysExceptions;

    final Gauge sinkExceptions;

    // As an optimization
    private final Counter.Child statTotalRecordsReceivedChild;
    private final Counter.Child statTotalSysExceptionsChild;
    private final Counter.Child statTotalSinkExceptionsChild;
    private final Counter.Child statTotalWrittenChild;
    private final Gauge.Child statlastInvocationChild;

    private Counter.Child statTotalRecordsReceivedChild1min;
    private Counter.Child statTotalSysExceptions1minChild;
    private Counter.Child statTotalSinkExceptionsChild1min;
    private Counter.Child statTotalWrittenChild1min;

    @Getter
    private EvictingQueue<InstanceCommunication.FunctionStatus.ExceptionInformation> latestSystemExceptions =
            EvictingQueue.create(10);
    @Getter
    private EvictingQueue<InstanceCommunication.FunctionStatus.ExceptionInformation> latestSinkExceptions =
            EvictingQueue.create(10);

    private final RateLimiter sysExceptionRateLimiter;

    private final RateLimiter sinkExceptionRateLimiter;


    public SinkStatsManager(FunctionCollectorRegistry collectorRegistry, String[] metricsLabels,
                            ScheduledExecutorService scheduledExecutorService) {
        super(collectorRegistry, metricsLabels, scheduledExecutorService);

        statTotalRecordsReceived = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + RECEIVED_TOTAL,
                Counter.build()
                .name(PULSAR_SINK_METRICS_PREFIX + RECEIVED_TOTAL)
                .help("Total number of records sink has received from Pulsar topic(s).")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statTotalRecordsReceivedChild = statTotalRecordsReceived.labels(metricsLabels);

        statTotalSysExceptions = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + SYSTEM_EXCEPTIONS_TOTAL,
                Counter.build()
                .name(PULSAR_SINK_METRICS_PREFIX + SYSTEM_EXCEPTIONS_TOTAL)
                .help("Total number of system exceptions.")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statTotalSysExceptionsChild = statTotalSysExceptions.labels(metricsLabels);

        statTotalSinkExceptions = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + SINK_EXCEPTIONS_TOTAL,
                Counter.build()
                .name(PULSAR_SINK_METRICS_PREFIX + SINK_EXCEPTIONS_TOTAL)
                .help("Total number of sink exceptions.")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statTotalSinkExceptionsChild = statTotalSinkExceptions.labels(metricsLabels);

        statTotalWritten = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + WRITTEN_TOTAL,
                Counter.build()
                .name(PULSAR_SINK_METRICS_PREFIX + WRITTEN_TOTAL)
                .help("Total number of records processed by sink.")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statTotalWrittenChild = statTotalWritten.labels(metricsLabels);

        statlastInvocation = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + LAST_INVOCATION,
                Gauge.build()
                .name(PULSAR_SINK_METRICS_PREFIX + LAST_INVOCATION)
                .help("The timestamp of the last invocation of the sink.")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statlastInvocationChild = statlastInvocation.labels(metricsLabels);

        statTotalRecordsReceived1min = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + RECEIVED_TOTAL_1min,
                Counter.build()
                .name(PULSAR_SINK_METRICS_PREFIX + RECEIVED_TOTAL_1min)
                .help("Total number of messages sink has received from Pulsar topic(s) in the last 1 minute.")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statTotalRecordsReceivedChild1min = statTotalRecordsReceived1min.labels(metricsLabels);

        statTotalSysExceptions1min = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + SYSTEM_EXCEPTIONS_TOTAL_1min,
                Counter.build()
                .name(PULSAR_SINK_METRICS_PREFIX + SYSTEM_EXCEPTIONS_TOTAL_1min)
                .help("Total number of system exceptions in the last 1 minute.")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statTotalSysExceptions1minChild = statTotalSysExceptions1min.labels(metricsLabels);

        statTotalSinkExceptions1min = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + SINK_EXCEPTIONS_TOTAL_1min,
                Counter.build()
                .name(PULSAR_SINK_METRICS_PREFIX + SINK_EXCEPTIONS_TOTAL_1min)
                .help("Total number of sink exceptions in the last 1 minute.")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statTotalSinkExceptionsChild1min = statTotalSinkExceptions1min.labels(metricsLabels);

        statTotalWritten1min = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + WRITTEN_TOTAL_1min,
                Counter.build()
                .name(PULSAR_SINK_METRICS_PREFIX + WRITTEN_TOTAL_1min)
                .help("Total number of records processed by sink the last 1 minute.")
                .labelNames(METRICS_LABEL_NAMES)
                .create());
        statTotalWrittenChild1min = statTotalWritten1min.labels(metricsLabels);

        sysExceptions = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + "system_exception",
                Gauge.build()
                .name(PULSAR_SINK_METRICS_PREFIX + "system_exception")
                .labelNames(EXCEPTION_METRICS_LABEL_NAMES)
                .help("Exception from system code.")
                .create());

        sinkExceptions = collectorRegistry.registerIfNotExist(
                PULSAR_SINK_METRICS_PREFIX + "sink_exception",
                Gauge.build()
                .name(PULSAR_SINK_METRICS_PREFIX + "sink_exception")
                .labelNames(EXCEPTION_METRICS_LABEL_NAMES)
                .help("Exception from sink.")
                .create());

        sysExceptionRateLimiter = RateLimiter.create(5.0d / 60.0d);
        sinkExceptionRateLimiter = RateLimiter.create(5.0d / 60.0d);
    }

    @Override
    public void reset() {
        statTotalRecordsReceived1min.clear();
        statTotalRecordsReceivedChild1min = statTotalRecordsReceived1min.labels(metricsLabels);

        statTotalSysExceptions1min.clear();
        statTotalSysExceptions1minChild = statTotalSysExceptions1min.labels(metricsLabels);

        statTotalSinkExceptions1min.clear();
        statTotalSinkExceptionsChild1min = statTotalSinkExceptions1min.labels(metricsLabels);

        statTotalWritten1min.clear();
        statTotalWrittenChild1min = statTotalWritten1min.labels(metricsLabels);
    }

    @Override
    public void incrTotalReceived() {
        statTotalRecordsReceivedChild.inc();
        statTotalRecordsReceivedChild1min.inc();
    }

    @Override
    public void incrTotalProcessedSuccessfully() {
        statTotalWrittenChild.inc();
        statTotalWrittenChild1min.inc();
    }

    @Override
    public void incrSysExceptions(Throwable ex) {
        statTotalSysExceptionsChild.inc();
        statTotalSysExceptions1minChild.inc();

        long ts = System.currentTimeMillis();
        InstanceCommunication.FunctionStatus.ExceptionInformation info = getExceptionInfo(ex, ts);
        latestSystemExceptions.add(info);

        // report exception throw prometheus
        if (sysExceptionRateLimiter.tryAcquire()) {
            String[] exceptionMetricsLabels = getExceptionMetricsLabels(ex);
            sysExceptions.labels(exceptionMetricsLabels).set(1.0);
        }
    }

    @Override
    public void incrUserExceptions(Throwable ex) {
        incrSysExceptions(ex);
    }

    @Override
    public void incrSourceExceptions(Throwable ex) {
        incrSysExceptions(ex);
    }

    @Override
    public void incrSinkExceptions(Throwable ex) {
        statTotalSinkExceptionsChild.inc();
        statTotalSinkExceptionsChild1min.inc();

        long ts = System.currentTimeMillis();
        InstanceCommunication.FunctionStatus.ExceptionInformation info = getExceptionInfo(ex, ts);
        latestSinkExceptions.add(info);

        // report exception throw prometheus
        if (sinkExceptionRateLimiter.tryAcquire()) {
            String[] exceptionMetricsLabels = getExceptionMetricsLabels(ex);
            sinkExceptions.labels(exceptionMetricsLabels).set(1.0);
        }
    }

    private String[] getExceptionMetricsLabels(Throwable ex) {
        String[] exceptionMetricsLabels = Arrays.copyOf(metricsLabels, metricsLabels.length + 1);
        exceptionMetricsLabels[exceptionMetricsLabels.length - 1] = ex.getMessage() != null ? ex.getMessage() : "";
        return exceptionMetricsLabels;
    }

    @Override
    public void setLastInvocation(long ts) {
        statlastInvocationChild.set(ts);
    }


    @Override
    public void processTimeEnd(long startTime) {
        //no-op
    }

    @Override
    public double getTotalProcessedSuccessfully() {
        return statTotalWrittenChild.get();
    }

    @Override
    public double getTotalRecordsReceived() {
        return statTotalRecordsReceivedChild.get();
    }

    @Override
    public double getTotalSysExceptions() {
        return statTotalSysExceptionsChild.get();
    }

    @Override
    public double getTotalUserExceptions() {
        return 0;
    }

    @Override
    public double getLastInvocation() {
        return statlastInvocationChild.get();
    }

    @Override
    public double getAvgProcessLatency() {
        return 0;
    }

    @Override
    public double getTotalProcessedSuccessfully1min() {
        return statTotalWrittenChild1min.get();
    }

    @Override
    public double getTotalRecordsReceived1min() {
        return statTotalRecordsReceivedChild1min.get();
    }

    @Override
    public double getTotalSysExceptions1min() {
        return statTotalSysExceptions1minChild.get();
    }

    @Override
    public double getTotalUserExceptions1min() {
        return 0;
    }

    @Override
    public double getAvgProcessLatency1min() {
        return 0;
    }

    @Override
    public EvictingQueue<InstanceCommunication.FunctionStatus.ExceptionInformation> getLatestUserExceptions() {
        return emptyQueue;
    }

    @Override
    public EvictingQueue<InstanceCommunication.FunctionStatus.ExceptionInformation> getLatestSystemExceptions() {
        return latestSystemExceptions;
    }

    @Override
    public EvictingQueue<InstanceCommunication.FunctionStatus.ExceptionInformation> getLatestSourceExceptions() {
        return emptyQueue;
    }

    @Override
    public EvictingQueue<InstanceCommunication.FunctionStatus.ExceptionInformation> getLatestSinkExceptions() {
        return latestSinkExceptions;
    }
}
